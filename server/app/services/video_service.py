import json
import re

from openai import OpenAI
from pydantic import BaseModel, Field

from app.core.config import get_settings
from app.db.models import GrowthEvent
from app.schemas.video import VideoKeywordSuggestion, VideoScene
from app.services.memory_service import local_day

PROMPT_VERSION = "p7.0"
STAGES = ("beginning", "difficulty", "small_step", "help", "change", "continuing")


class DailyCaption(BaseModel):
    date: str = Field(min_length=10, max_length=10)
    event_ids: list[int] = Field(min_length=1, max_length=40)
    text: str = Field(min_length=1, max_length=1200)


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


def group_events_by_day(events: list[GrowthEvent]) -> list[tuple[str, list[GrowthEvent]]]:
    grouped: dict[str, list[GrowthEvent]] = {}
    for event in events:
        grouped.setdefault(local_day(event.created_at), []).append(event)
    return list(grouped.items())


def _stage(events: list[GrowthEvent], index: int, total: int) -> str:
    text = " ".join(_event_text(event) for event in events)
    difficulty = ("卡住", "失败", "错了", "很累", "不想", "放弃", "不敢", "紧张", "怀疑", "不自信", "考不上")
    if index == 0:
        return "beginning"
    if any(event.support_received for event in events):
        return "help"
    if any(word in text for word in difficulty):
        return "difficulty"
    if index == total - 1:
        return "continuing"
    if any(event.own_effort or event.attempt for event in events):
        return "small_step"
    return "change"


def _clean_clause(value: str | None) -> str:
    compact = re.sub(r"\s+", " ", value or "").strip()
    compact = re.sub(r"[。！？!?]+\s*", "，", compact)
    return compact.strip("。！？!?；;，, ")


def _one_sentence(value: str) -> str:
    cleaned = _clean_clause(value).rstrip("；")
    if not cleaned:
        raise ValueError("每日故事摘要不能为空")
    return cleaned + "。"


def _daily_sentence(events: list[GrowthEvent]) -> str:
    clauses: list[str] = []
    for event in events:
        fact = _clean_clause(event.fact)
        if fact and fact not in clauses:
            clauses.append(fact)
        additions = (
            ("当时我感到", event.feeling),
            ("我尝试了", event.own_effort or event.attempt),
            ("我得到的支持是", event.support_received),
        )
        for prefix, raw in additions:
            value = _clean_clause(raw)
            if value and not any(value in clause for clause in clauses):
                clauses.append(f"{prefix}{value}")
    return _one_sentence("；".join(clauses))


def _fallback(groups: list[tuple[str, list[GrowthEvent]]]) -> DailyCaptions:
    return DailyCaptions(pages=[DailyCaption(
        date=day,
        event_ids=[event.id for event in day_events],
        text=_daily_sentence(day_events),
    ) for day, day_events in groups])


def generate_captions(events: list[GrowthEvent]) -> tuple[DailyCaptions, str | None]:
    groups = group_events_by_day(events)
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return _fallback(groups), None

    days = [{
        "date": day,
        "events": [{
            "id": item.id,
            "fact": item.fact,
            "feeling": item.feeling,
            "own_effort": item.own_effort,
            "attempt": item.attempt,
            "support_received": item.support_received,
        } for item in day_events],
    } for day, day_events in groups]
    client = OpenAI(api_key=settings.dashscope_api_key, base_url=settings.dashscope_base_url, timeout=35, max_retries=0)
    response = client.chat.completions.create(
        model=settings.chat_model,
        messages=[
            {"role": "system", "content": "为成长小片按天整理故事摘要。输入已按日期分组；一天只输出一页，日期、event_ids、页数与顺序必须完全一致。只输出 JSON：{\"pages\":[{\"date\":\"YYYY-MM-DD\",\"event_ids\":[整数],\"text\":\"一句话摘要\"}]}。一句话可以用逗号和分号串联，但必须清楚覆盖当天输入中的每一件事，包括重要感受、自己的尝试和实际获得的支持；不能漏掉事件，也不得添加、调换或夸大事实。文字自然、适合口播，不把多天内容合并。"},
            {"role": "user", "content": json.dumps({"days": days}, ensure_ascii=False)},
        ],
        response_format={"type": "json_object"}, temperature=0,
        reasoning_effort="none", max_tokens=2400,
    )
    captions = DailyCaptions.model_validate_json(response.choices[0].message.content or "")
    expected = [(day, [event.id for event in day_events]) for day, day_events in groups]
    actual = [(page.date, page.event_ids) for page in captions.pages]
    if actual != expected:
        raise ValueError("成长小片每日摘要与来源事件不一致")
    cleaned = DailyCaptions(pages=[
        DailyCaption(date=page.date, event_ids=page.event_ids, text=_one_sentence(page.text))
        for page in captions.pages
    ])
    return cleaned, settings.chat_model


def scenes_for(events: list[GrowthEvent], captions: DailyCaptions) -> list[VideoScene]:
    groups = group_events_by_day(events)
    caption_by_day = {page.date: page for page in captions.pages}
    return [VideoScene(
        stage=_stage(day_events, index, len(groups)),
        title="这一天的记录",
        date=day,
        text=caption_by_day[day].text.strip(),
        source_event_ids=[event.id for event in day_events],
    ) for index, (day, day_events) in enumerate(groups)]
