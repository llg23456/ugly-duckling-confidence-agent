import json
import logging
import re
from typing import Any, Literal

from pydantic import BaseModel, Field

from app.core.config import Settings, get_settings
from app.schemas import ChatRequest, ChatResponse
from app.schemas.chat import MemoryEvidence
from app.services.memory_service import memory_prompt
from app.services.mock_service import mock_chat
from app.services.support_service import needs_support


logger = logging.getLogger(__name__)

CHAT_PROMPT_VERSION = "chat-v2"
ACUTE_TERMS = (
    "想死", "不想活", "自杀", "结束生命", "伤害自己", "伤害别人", "杀了",
)
MEMORY_SUPPRESSION_TERMS = (
    "不要记", "别记", "不用记", "别保存", "不要保存", "忘掉刚才", "忘记刚才",
)


SYSTEM_PROMPT = """你是“小丑鸭”，一位温柔、克制、尊重边界的成长陪伴伙伴。
先处理用户真正提出的问题，再考虑情绪回应；没有明确证据时，不擅自判断用户紧张、难过或焦虑。
回答具体、自然，避免反复使用“听起来……愿意说说……”等固定句式。
有明确问题时先直接回答。适合建议时最多提出一个可拒绝的小步骤；一轮最多追问一个问题。
回复通常控制在40到180个汉字，可根据问题复杂度自然缩短或延长，不以成绩或录取评价用户价值。
只有在确实相关且能帮助当前对话时才引用记忆；不得补造次数、日期、经历、关系或已完成行为。
不得诊断心理或身体疾病，不得替代医生、心理咨询师或紧急服务，也不得替用户联系任何人。
只输出一个 JSON 对象，不要输出 Markdown。"""


class DialogueEnvelope(BaseModel):
    reply: str = Field(min_length=1, max_length=800)
    intent: Literal[
        "direct_question", "emotion", "reflection", "information",
        "correction", "memory_control", "casual",
    ] = "casual"
    strategy: Literal["listen", "reflect", "small_step", "seek_support"] = "listen"
    topic: str = Field(default="", max_length=120)
    used_memory_ids: list[int] = Field(default_factory=list, max_length=4)
    used_record_ids: list[int] = Field(default_factory=list, max_length=4)


def _content_to_text(content: Any) -> str:
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                parts.append(str(item.get("text", "")))
            else:
                text = getattr(item, "text", None)
                if text:
                    parts.append(str(text))
        return "".join(parts).strip()
    return ""


def is_crisis_message(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    return any(term in compact for term in ACUTE_TERMS)


def suppress_memory_for_message(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    return any(term in compact for term in MEMORY_SUPPRESSION_TERMS)


def _intent_for(request: ChatRequest) -> str:
    message = request.message.strip()
    if suppress_memory_for_message(message):
        return "memory_control"
    if any(word in message for word in ("不是", "说错了", "更正", "改成", "其实是")):
        return "correction"
    if request.mode == "reflect" or any(word in message for word in ("回顾", "复盘", "回想", "总结")):
        return "reflection"
    if "?" in message or "？" in message or any(
        word in message for word in ("怎么", "为什么", "是什么", "能不能", "可以吗", "要不要", "哪里", "多少")
    ):
        return "direct_question"
    if any(word in message for word in ("难受", "低落", "害怕", "紧张", "担心", "焦虑", "生气", "委屈", "很累")):
        return "emotion"
    if any(word in message for word in ("我叫", "我是", "我在", "我准备", "我计划", "我喜欢", "我希望")):
        return "information"
    return "casual"


def _strategy_for(request: ChatRequest, history: list[dict[str, str]] | None = None) -> str:
    if needs_support(request.message, history):
        return "seek_support"
    if request.mode == "suggest":
        return "small_step"
    if request.mode == "reflect":
        return "reflect"
    return "listen"


def dialogue_system_prompt(
    request: ChatRequest,
    history: list[dict[str, str]] | None,
    evidence: list[MemoryEvidence],
) -> tuple[str, str, str]:
    intent = _intent_for(request)
    strategy = _strategy_for(request, history)
    schema = {
        "reply": "给用户看的自然回复",
        "intent": intent,
        "strategy": strategy,
        "topic": "本轮核心主题",
        "used_memory_ids": [],
        "used_record_ids": [],
    }
    directive = f"""
当前程序判断：intent={intent}，strategy={strategy}。
除非用户表达发生明显变化，否则 strategy 必须保持为 {strategy}；不得自行升级为 seek_support。
若用户在纠正信息，先确认新信息，不重复旧错误。若用户要求不要记录或忘记，不引用相关旧记忆。
输出格式必须严格符合：{json.dumps(schema, ensure_ascii=False)}
intent 只能取 direct_question、emotion、reflection、information、correction、memory_control、casual；
strategy 只能取 listen、reflect、small_step、seek_support。两个 used 列表只能填写整数 ID；
仅当回复正文实际引用了对应事实时才填写，没有使用时返回空数组。
prompt_version={CHAT_PROMPT_VERSION}
"""
    return SYSTEM_PROMPT + directive + "\n" + memory_prompt(evidence), intent, strategy


def _validated_strategy(envelope: DialogueEnvelope, required: str) -> str:
    if required in {"seek_support", "small_step", "reflect"}:
        return required
    if envelope.strategy == "seek_support":
        return "listen"
    return envelope.strategy


def _used_evidence(envelope: DialogueEnvelope, evidence: list[MemoryEvidence]) -> list[MemoryEvidence]:
    memory_ids = set(envelope.used_memory_ids)
    record_ids = set(envelope.used_record_ids)
    return [
        item for item in evidence
        if (item.memory_id is not None and item.memory_id in memory_ids)
        or (item.source_record_id is not None and item.source_record_id in record_ids)
    ]


def _parse_dialogue(content: Any) -> DialogueEnvelope:
    text = _content_to_text(content)
    if not text:
        raise ValueError("Model returned empty content")
    return DialogueEnvelope.model_validate_json(text)


def generate_structured_dialogue(
    client: Any,
    *,
    model: str,
    messages: list[dict[str, Any]],
    required_strategy: str,
    evidence: list[MemoryEvidence],
    max_tokens: int = 450,
) -> tuple[DialogueEnvelope, str, list[MemoryEvidence]]:
    kwargs = dict(
        model=model,
        messages=messages,
        response_format={"type": "json_object"},
        temperature=0.55,
        reasoning_effort="none",
        max_tokens=max_tokens,
    )
    completion = client.chat.completions.create(**kwargs)
    raw = completion.choices[0].message.content
    try:
        envelope = _parse_dialogue(raw)
    except Exception:
        repair_messages = [
            *messages,
            {"role": "assistant", "content": _content_to_text(raw)},
            {
                "role": "user",
                "content": "上一条不符合 JSON 契约。只重新输出完整、合法的 JSON 对象，不要解释。",
            },
        ]
        completion = client.chat.completions.create(**{
            **kwargs,
            "messages": repair_messages,
            "temperature": 0,
        })
        envelope = _parse_dialogue(completion.choices[0].message.content)
    strategy = _validated_strategy(envelope, required_strategy)
    return envelope, strategy, _used_evidence(envelope, evidence)


def _crisis_response() -> ChatResponse:
    return ChatResponse(
        reply=(
            "我很在意你现在的安全。如果你正准备伤害自己或他人，请立刻远离可能造成伤害的物品，"
            "联系身边可信任的人陪着你，并拨打当地急救电话或前往最近的急诊。"
        ),
        strategy="listen",
        evidence=[],
        mock=False,
        model="safety-rule-v1",
        safety_triggered=True,
    )


def _live_chat(
    request: ChatRequest,
    settings: Settings,
    history: list[dict[str, str]] | None = None,
    evidence: list[MemoryEvidence] | None = None,
) -> ChatResponse:
    # 延迟导入：未安装模型 SDK 或未配置 Key 时，Mock 服务仍可独立运行。
    from openai import OpenAI

    evidence = evidence or []
    system_prompt, _intent, required_strategy = dialogue_system_prompt(request, history, evidence)
    client = OpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=30.0,
        max_retries=1,
    )
    envelope, strategy, used_evidence = generate_structured_dialogue(
        client,
        model=settings.chat_model,
        messages=[
            {"role": "system", "content": system_prompt},
            *(history or []),
            {"role": "user", "content": request.message},
        ],
        required_strategy=required_strategy,
        evidence=evidence,
    )
    return ChatResponse(
        reply=envelope.reply.strip(),
        strategy=strategy,
        evidence=used_evidence,
        mock=False,
        model=settings.chat_model,
    )


def chat_with_fallback(
    request: ChatRequest,
    settings: Settings | None = None,
    history: list[dict[str, str]] | None = None,
    evidence: list[MemoryEvidence] | None = None,
) -> ChatResponse:
    if is_crisis_message(request.message):
        return _crisis_response()

    active_settings = settings or get_settings()
    if not active_settings.enable_live_ai:
        response = mock_chat(request)
        response.strategy = _strategy_for(request, history)
        response.mock_reason = "ENABLE_LIVE_AI is false"
        return response
    if not active_settings.dashscope_api_key.strip():
        response = mock_chat(request)
        response.strategy = _strategy_for(request, history)
        response.mock_reason = "DASHSCOPE_API_KEY is not configured"
        return response

    try:
        return _live_chat(request, active_settings, history, evidence)
    except Exception as exc:  # 外部服务失败时保证演示仍可继续。
        logger.exception("DashScope chat failed: %s", type(exc).__name__)
        response = mock_chat(request)
        response.strategy = _strategy_for(request, history)
        response.mock_reason = f"DashScope call failed: {type(exc).__name__}"
        return response
