import json

from openai import OpenAI
from pydantic import BaseModel, Field

from app.core.config import get_settings
from app.db.models import GrowthEvent
from app.schemas.video import VideoScene

PROMPT_VERSION = "p4.1"
STAGES = ("difficulty", "small_step", "help", "change", "continuing")


class DraftCaptions(BaseModel):
    difficulty: str = Field(min_length=1, max_length=120)
    small_step: str = Field(min_length=1, max_length=120)
    help: str = Field(min_length=1, max_length=120)
    change: str = Field(min_length=1, max_length=120)
    continuing: str = Field(min_length=1, max_length=120)


def _first(events: list[GrowthEvent], field: str) -> GrowthEvent | None:
    return next((item for item in events if getattr(item, field, None)), None)


def _anchors(events: list[GrowthEvent]) -> dict[str, GrowthEvent | None]:
    setback = ("卡住", "失败", "停下", "暂停", "不敢", "紧张", "没帮到")
    return {
        "difficulty": next((item for item in events if any(word in (item.fact + (item.feeling or "")) for word in setback)), None),
        "small_step": _first(events, "own_effort") or _first(events, "attempt"),
        "help": _first(events, "support_received"),
        "change": events[-1],
        "continuing": events[-1],
    }


def _fallback(anchors: dict[str, GrowthEvent | None]) -> DraftCaptions:
    difficulty = anchors["difficulty"]
    effort = anchors["small_step"]
    help_event = anchors["help"]
    change = anchors["change"]
    return DraftCaptions(
        difficulty=(difficulty.fact[:120] if difficulty else "还没有记录困难片段"),
        small_step=((effort.own_effort or effort.attempt)[:120] if effort else "还没有记录自己的尝试"),
        help=(help_event.support_received[:120] if help_event else "还没有记录实际收到的帮助"),
        change=change.fact[:120],
        continuing="这段经历仍在继续，可以从下一小步开始。",
    )


def generate_captions(events: list[GrowthEvent]) -> tuple[DraftCaptions, str | None]:
    settings = get_settings()
    anchors = _anchors(events)
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return _fallback(anchors), None

    facts = [{"id": item.id, "fact": item.fact, "feeling": item.feeling,
              "own_effort": item.own_effort, "support_received": item.support_received}
             for item in events]
    client = OpenAI(api_key=settings.dashscope_api_key, base_url=settings.dashscope_base_url, timeout=35, max_retries=0)
    response = client.chat.completions.create(
        model=settings.chat_model,
        messages=[
            {"role": "system", "content": "为用户成长小片写五个中文短字幕，只输出 JSON 对象，字段严格为 difficulty、small_step、help、change、continuing，值均为不超过120字的非空字符串。仅根据给出的真实事件措辞，不新增人物、帮助、成功结果或具体行为。若某段没有证据，明确写还没有相关记录。continuing 只提出温和的下一步，不宣称已经发生。五段顺序为困难、小尝试、获得帮助、当前变化、仍在继续。"},
            {"role": "user", "content": json.dumps({"events": facts}, ensure_ascii=False)},
        ],
        response_format={"type": "json_object"}, temperature=0,
        reasoning_effort="none", max_tokens=650,
    )
    captions = DraftCaptions.model_validate_json(response.choices[0].message.content or "")
    return captions, settings.chat_model


def scenes_for(events: list[GrowthEvent], captions: DraftCaptions) -> list[VideoScene]:
    anchors = _anchors(events)
    return [VideoScene(
        stage=stage, text=getattr(captions, stage).strip(),
        source_event_ids=[anchors[stage].id] if anchors[stage] else [],
    ) for stage in STAGES]
