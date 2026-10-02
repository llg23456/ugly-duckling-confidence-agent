import json
import re

from openai import OpenAI
from pydantic import BaseModel, Field

from app.core.config import get_settings
from app.db.models import GrowthEvent
from app.schemas.video import VideoKeywordSuggestion, VideoScene
from app.services.memory_service import local_day

PROMPT_VERSION = "p6.0"
STAGES = ("beginning", "difficulty", "small_step", "help", "change", "continuing")


class DailyCaption(BaseModel):
    event_id: int
    text: str = Field(min_length=1, max_length=120)


class DailyCaptions(BaseModel):
    pages: list[DailyCaption] = Field(min_length=3, max_length=7)


class KeywordDraft(BaseModel):
    suggestions: list[VideoKeywordSuggestion] = Field(min_length=3, max_length=10)


TOPIC_RULES = (
    ("学习备考", ("考研", "备考", "考试", "院校", "录取", "学习", "辅导课", "看书")),
    ("图书馆学习", ("图书馆", "自习室", "自习")),
    ("做题与复习", ("做题", "刷题", "错题", "复习", "题目", "改错")),
    ("压力与不自信", ("压力", "焦虑", "紧张", "忐忑", "不自信", "怀疑", "考不上", "害怕")),
    ("想放弃", ("放弃", "不想学", "不想坚持", "没毅力", "坚持不下", "摆烂")),
    ("家人支持", ("父母", "家人", "爸爸", "妈妈", "家里", "家长")),
    ("师兄师姐", ("师兄", "师姐", "学长", "学姐")),
    ("老师指导", ("老师", "导师", "学院")),
    ("运动与户外", ("运动", "健身", "慢跑", "操场", "散步", "户外", "公园")),
    ("休息与调整", ("休息", "睡觉", "熬夜", "调整", "方法")),
    ("沟通与求助", ("沟通", "聊天", "求助", "倾诉", "告诉", "商量")),
    ("鼓励与陪伴", ("小鸭", "安慰", "鼓励", "陪伴", "支持")),
    ("计划与行动", ("计划", "开始", "行动", "完成", "继续", "坚持", "重新")),
    ("工作与项目", ("工作", "汇报", "答辩", "项目", "代码", "小组")),
)


def _event_text(event: GrowthEvent) -> str:
    return " ".join(filter(None, (
        event.fact, event.feeling, event.own_effort, event.attempt,
        event.support_received, " ".join(event.people or []),
    )))


def _ensure_keyword_coverage(
    suggestions: list[VideoKeywordSuggestion],
    events: list[GrowthEvent],
) -> list[VideoKeywordSuggestion]:
    result = suggestions[:10]
    covered = {event_id for suggestion in result for event_id in suggestion.event_ids}
    missing = [event.id for event in events if event.id not in covered]
    if missing:
        if len(result) >= 10:
            result = result[:9]
            covered = {event_id for suggestion in result for event_id in suggestion.event_ids}
            missing = [event.id for event in events if event.id not in covered]
        result.append(VideoKeywordSuggestion(label="其他记录", event_ids=missing))
    return result


def _fallback_keywords(events: list[GrowthEvent]) -> list[VideoKeywordSuggestion]:
    suggestions: list[VideoKeywordSuggestion] = []
    for label, terms in TOPIC_RULES:
        ids = [event.id for event in events if any(term in _event_text(event) for term in terms)]
        if ids:
            suggestions.append(VideoKeywordSuggestion(label=label, event_ids=ids))
    if len(suggestions) < 3:
        difficult = ("难", "累", "错", "卡", "怕", "失败", "压力", "放弃", "不想")
        support_ids = [event.id for event in events if event.support_received or event.people]
        effort_ids = [event.id for event in events if event.own_effort or event.attempt]
        difficulty_ids = [event.id for event in events if any(word in _event_text(event) for word in difficult)]
        for label, ids in (("遇到困难", difficulty_ids), ("获得支持", support_ids), ("自己的行动", effort_ids)):
            if ids and label not in {item.label for item in suggestions}:
                suggestions.append(VideoKeywordSuggestion(label=label, event_ids=ids))
    if not suggestions and events:
        suggestions.append(VideoKeywordSuggestion(label="本周记录", event_ids=[event.id for event in events]))
    return _ensure_keyword_coverage(suggestions, events)


def suggest_keywords(events: list[GrowthEvent]) -> tuple[list[VideoKeywordSuggestion], str | None]:
    fallback = _fallback_keywords(events)
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return fallback, None
    rows = [{
        "id": event.id, "fact": event.fact, "feeling": event.feeling,
        "own_effort": event.own_effort, "support_received": event.support_received,
        "people": event.people,
    } for event in events]
    try:
        client = OpenAI(api_key=settings.dashscope_api_key, base_url=settings.dashscope_base_url, timeout=25, max_retries=0)
        response = client.chat.completions.create(
            model=settings.chat_model,
            messages=[
                {"role": "system", "content": "从成长事件中整理3到10个便于用户筛选视频素材的中文主题。每个主题2到8个字，具体、易懂，可使用目标、场景、行动、困难、人物支持或情绪变化，例如‘考研准备’‘图书馆学习’‘家人支持’。每个主题必须列出真正相关的event_id；同一事件可以属于多个主题。不得添加输入之外的事实或ID。只输出JSON：{\"suggestions\":[{\"label\":\"主题\",\"event_ids\":[整数]}]}。"},
                {"role": "user", "content": json.dumps({"events": rows}, ensure_ascii=False)},
            ],
            response_format={"type": "json_object"}, temperature=0,
            reasoning_effort="none", max_tokens=1200,
        )
        draft = KeywordDraft.model_validate_json(response.choices[0].message.content or "")
        allowed = {event.id for event in events}
        positions = {event.id: index for index, event in enumerate(events)}
        cleaned: list[VideoKeywordSuggestion] = []
        labels: set[str] = set()
        for suggestion in draft.suggestions:
            label = re.sub(r"\s+", "", suggestion.label.strip())[:12]
            raw_ids = set(suggestion.event_ids)
            if not label or label in labels or not raw_ids or any(event_id not in allowed for event_id in raw_ids):
                continue
            ids = sorted(raw_ids, key=positions.__getitem__)
            labels.add(label)
            cleaned.append(VideoKeywordSuggestion(label=label, event_ids=ids))
        if len(cleaned) < 3:
            return fallback, None
        return _ensure_keyword_coverage(cleaned, events), settings.chat_model
    except Exception:
        return fallback, None


def _stage(event: GrowthEvent, index: int, total: int) -> str:
    text = f"{event.fact} {event.feeling or ''}"
    difficulty = ("卡住", "失败", "错了", "很累", "不想", "放弃", "不敢", "紧张", "怀疑", "不自信", "考不上")
    if index == 0:
        return "beginning"
    if event.support_received:
        return "help"
    if any(word in text for word in difficulty):
        return "difficulty"
    if index == total - 1:
        return "continuing"
    if event.own_effort or event.attempt:
        return "small_step"
    return "change"


def _fallback(events: list[GrowthEvent]) -> DailyCaptions:
    return DailyCaptions(pages=[DailyCaption(event_id=item.id, text=item.fact[:120]) for item in events])


def generate_captions(events: list[GrowthEvent]) -> tuple[DailyCaptions, str | None]:
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return _fallback(events), None

    facts = [{"id": item.id, "fact": item.fact, "feeling": item.feeling,
              "own_effort": item.own_effort, "support_received": item.support_received}
             for item in events]
    client = OpenAI(api_key=settings.dashscope_api_key, base_url=settings.dashscope_base_url, timeout=35, max_retries=0)
    response = client.chat.completions.create(
        model=settings.chat_model,
        messages=[
            {"role": "system", "content": "为成长小片逐条改写字幕。输入有几条事件就输出几页，顺序、event_id 和数量必须完全一致。只输出 JSON：{\"pages\":[{\"event_id\":整数,\"text\":不超过120字}]}。每页只概括对应事件，可让前后语气自然衔接，但不得合并日期、挪用别页事实、虚构人物、帮助、行为或结果。文字要适合口播，句子简洁。"},
            {"role": "user", "content": json.dumps({"events": facts}, ensure_ascii=False)},
        ],
        response_format={"type": "json_object"}, temperature=0,
        reasoning_effort="none", max_tokens=1600,
    )
    captions = DailyCaptions.model_validate_json(response.choices[0].message.content or "")
    if [page.event_id for page in captions.pages] != [item.id for item in events]:
        raise ValueError("成长小片页面与来源事件不一致")
    return captions, settings.chat_model


def scenes_for(events: list[GrowthEvent], captions: DailyCaptions) -> list[VideoScene]:
    text_by_id = {page.event_id: page.text.strip() for page in captions.pages}
    return [VideoScene(
        stage=_stage(event, index, len(events)),
        title="这一天的记录",
        date=local_day(event.created_at),
        text=text_by_id[event.id],
        source_event_ids=[event.id],
    ) for index, event in enumerate(events)]
