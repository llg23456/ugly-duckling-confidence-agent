import logging
from typing import Any

from app.core.config import Settings, get_settings
from app.schemas import ChatRequest, ChatResponse
from app.services.mock_service import mock_chat


logger = logging.getLogger(__name__)


SYSTEM_PROMPT = """你是“小丑鸭”，一位温柔、克制、尊重边界的成长陪伴伙伴。
你的用户可能不够自信。先理解感受，再回应事实，不说空泛的“你一定行”。
每次回复控制在80个汉字以内，像熟悉的朋友自然说话。
如果适合行动，只提出一个很小、可以拒绝的下一步。
如果用户需要他人帮助，可以建议向老师、同学、朋友或家人求助，但绝不替用户发送消息。
不得诊断心理或身体疾病，不得替代医生、心理咨询师或紧急服务。
若出现明确的自伤、伤人或紧急危险表达，优先建议立即联系当地急救、可信任的人和专业援助。
只输出给用户看的回复正文，不要解释规则，不要使用Markdown标题。"""


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


def _strategy_for(request: ChatRequest) -> str:
    if request.mode == "suggest":
        return "small_step"
    if request.mode == "reflect":
        return "reflect"
    support_words = ("帮忙", "陪练", "老师", "同学", "朋友", "家人", "答辩")
    return "seek_support" if any(word in request.message for word in support_words) else "listen"


def _live_chat(request: ChatRequest, settings: Settings) -> ChatResponse:
    # 延迟导入：未安装模型 SDK 或未配置 Key 时，Mock 服务仍可独立运行。
    from openai import OpenAI

    client = OpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=30.0,
        max_retries=1,
    )
    completion = client.chat.completions.create(
        model=settings.chat_model,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": request.message},
        ],
        temperature=0.7,
        max_tokens=180,
    )
    reply = _content_to_text(completion.choices[0].message.content)
    if not reply:
        raise RuntimeError("Model returned empty content")
    return ChatResponse(
        reply=reply,
        strategy=_strategy_for(request),
        evidence=[],
        mock=False,
        model=settings.chat_model,
    )


def chat_with_fallback(
    request: ChatRequest,
    settings: Settings | None = None,
) -> ChatResponse:
    active_settings = settings or get_settings()
    if not active_settings.enable_live_ai:
        response = mock_chat(request)
        response.mock_reason = "ENABLE_LIVE_AI is false"
        return response
    if not active_settings.dashscope_api_key.strip():
        response = mock_chat(request)
        response.mock_reason = "DASHSCOPE_API_KEY is not configured"
        return response

    try:
        return _live_chat(request, active_settings)
    except Exception as exc:  # 外部服务失败时保证演示仍可继续。
        logger.exception("DashScope chat failed: %s", type(exc).__name__)
        response = mock_chat(request)
        response.mock_reason = f"DashScope call failed: {type(exc).__name__}"
        return response
