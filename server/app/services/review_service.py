from datetime import date, datetime, timedelta, timezone
import json

from fastapi import HTTPException
from openai import OpenAI
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import GrowthEvent, Review
from app.db.repository import get_conversation
from app.schemas.review import ReviewMoment, ReviewResponse, ReviewSection
from app.services.memory_service import local_day


class StructuredReviewDraft(BaseModel):
    story: str = Field(min_length=1, max_length=360)
    sections: list[ReviewSection] = Field(min_length=4, max_length=5)
    affirmation: str = Field(min_length=1, max_length=180)
    next_step: str = Field(min_length=1, max_length=160)
    closing: str = Field(min_length=1, max_length=160)


SECTION_KEYS = {
    "day": ["happened", "difficulty", "attempt", "response"],
    "week": ["completed", "difficulty", "process", "change", "unfinished"],
    "month": ["experiences", "difficulty", "change", "next_step"],
}
REVIEW_STRUCTURE_VERSION = "review-flow-v2"
DIFFICULTY_TERMS = (
    "卡住", "失败", "错了", "做错", "很累", "疲惫", "不想", "放弃", "停下", "暂停",
    "没进展", "不敢", "害怕", "焦虑", "怀疑", "不自信", "没有毅力", "做不到", "考不上",
)
CHANGE_TERMS = ("重新", "不再", "慢慢", "愿意", "继续", "稳定", "缓下来", "找回", "完成")


def today_local() -> date:
    return datetime.now(timezone(timedelta(hours=8))).date()


def date_range(period: str, start: date | None, end: date | None) -> tuple[date, date]:
    today = today_local()
    if (start is None) != (end is None):
        raise HTTPException(status_code=422, detail="开始和结束日期必须一起填写")
    if start is None:
        start = today if period == "day" else today - timedelta(days=6) if period == "week" else today.replace(day=1)
        end = today
    if start > end or end > today:
        raise HTTPException(status_code=422, detail="日期范围无效")
    if period == "day" and start != end:
        raise HTTPException(status_code=422, detail="日回望只能选择一天")
    if period == "week" and (end - start).days > 6:
        raise HTTPException(status_code=422, detail="周回望最多覆盖七天")
    if period == "month" and (start.year, start.month) != (end.year, end.month):
        raise HTTPException(status_code=422, detail="月回望必须在同一自然月")
    return start, end


def events_in_range(db: Session, conversation_id: int, start: date, end: date) -> list[GrowthEvent]:
    events = db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation_id
    ).order_by(GrowthEvent.created_at, GrowthEvent.id)).all()
    return [item for item in events if start.isoformat() <= local_day(item.created_at) <= end.isoformat()]


def _key_moments(events: list[GrowthEvent], period: str) -> list[GrowthEvent]:
    if period != "month" or len(events) <= 6:
        return events
    setback = ("卡住", "失败", "停下", "暂停", "重新", "没帮到", "没进展", "不敢")
    chosen = {events[0].id, events[-1].id}
    ranked = sorted(events[1:-1], key=lambda item: (
        any(word in (item.fact + (item.feeling or "")) for word in setback),
        bool(item.support_received), bool(item.own_effort), item.value_score or 0,
    ), reverse=True)
    chosen.update(item.id for item in ranked[:4])
    return [item for item in events if item.id in chosen]


def _unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(value.strip() for value in values if value and value.strip()))


def _join(values: list[str], empty: str, limit: int = 3) -> str:
    selected = _unique(values)[:limit]
    return "；".join(selected) if selected else empty


def _is_difficulty(event: GrowthEvent) -> bool:
    text = f"{event.fact} {event.feeling or ''}"
    return any(word in text for word in DIFFICULTY_TERMS)


def _difficulty_score(event: GrowthEvent) -> int:
    text = f"{event.fact} {event.feeling or ''}"
    strong = ("放弃", "考不上", "没有毅力", "做不到", "不自信", "怀疑", "害怕", "焦虑")
    medium = ("错了", "做错", "卡住", "失败", "不想", "不敢", "没进展")
    return sum(3 for word in strong if word in text) + sum(2 for word in medium if word in text) + int("很累" in text or "疲惫" in text)


def _main_difficulties(events: list[GrowthEvent], limit: int = 2) -> list[GrowthEvent]:
    positions = {item.id: index for index, item in enumerate(events)}
    ranked = sorted((item for item in events if _is_difficulty(item)), key=_difficulty_score, reverse=True)[:limit]
    return sorted(ranked, key=lambda item: positions[item.id])


def _structured_sections(
    period: str,
    events: list[GrowthEvent],
    efforts: list[str],
    helps: list[str],
    paused: str | None,
    next_step: str,
) -> list[ReviewSection]:
    facts = _unique([item.fact for item in events])
    feelings = _unique([item.feeling for item in events if item.feeling])
    attempts = _unique([
        value
        for item in events
        for value in (item.own_effort, item.attempt)
        if value
    ])

    if period == "day":
        return [
            ReviewSection(key="happened", title="发生的事情", content=_join(facts, "这一天还没有留下具体事件。")),
            ReviewSection(
                key="difficulty", title="感受与困难",
                content=_join(feelings + ([paused] if paused else []), "还没有记录明显的困难。", 2),
            ),
            ReviewSection(
                key="attempt", title="尝试与结果",
                content=_join(attempts, "还没有记录具体尝试或结果。", 3),
            ),
            ReviewSection(
                key="response", title="小鸭想对你说",
                content="愿意把今天如实留下来，本身就是在认真照顾自己。",
            ),
        ]

    if period == "week":
        difficulty_events = [item for item in events if _is_difficulty(item)]
        main_difficulties = _main_difficulties(events)
        difficulty_start = events.index(difficulty_events[0]) if difficulty_events else len(events)
        starting_events = [item for item in events[:difficulty_start] if not _is_difficulty(item)]
        if not starting_events:
            starting_events = [item for item in events if not _is_difficulty(item)][:2]
        process_events = events[difficulty_start:] if difficulty_events else events
        process_values = _unique([
            value
            for item in process_events
            for value in (item.own_effort, item.attempt, item.support_received)
            if value
        ])
        last_difficulty_index = events.index(difficulty_events[-1]) if difficulty_events else -1
        change_event = next((
            item for item in reversed(events[last_difficulty_index + 1:])
            if any(word in item.fact for word in CHANGE_TERMS) or item.own_effort or item.support_received
        ), None)
        change = (
            f"在经历“{difficulty_events[-1].fact}”之后，{change_event.fact}"
            if difficulty_events and change_event else
            "这一周记录了行动，但还没有足够证据说明状态已经发生变化。"
        )
        remaining = (
            f"{events[-1].feeling}仍然存在，但不必等它完全消失再行动。{next_step}"
            if events[-1].feeling else next_step
        )
        return [
            ReviewSection(
                key="completed", title="开始与行动",
                content=_join([item.fact for item in starting_events], "这一周还没有留下明确的起点。", 3),
            ),
            ReviewSection(
                key="difficulty", title="遇到的困难",
                content=_join([item.fact for item in main_difficulties], "还没有记录明显的困难。", 2),
            ),
            ReviewSection(
                key="process", title="应对与支持",
                content=_join(process_values, "还没有记录具体的应对过程或实际支持。", 4),
            ),
            ReviewSection(key="change", title="真实的变化", content=change),
            ReviewSection(key="unfinished", title="仍在继续", content=remaining),
        ]

    return [
        ReviewSection(key="experiences", title="这个月的主要经历", content=_join(facts, "这个月还没有留下具体经历。", 4)),
        ReviewSection(key="difficulty", title="反复出现的困难", content=paused or _join(feelings, "还没有记录反复出现的困难。", 2)),
        ReviewSection(
            key="change", title="看得见的变化",
            content=(f"从“{facts[0]}”到“{facts[-1]}”，变化来自一次次真实的小行动。" if len(facts) > 1 else _join(facts, "还没有足够记录形成变化线索。", 1)),
        ),
        ReviewSection(key="next_step", title="下一小步", content=next_step),
    ]


def compose_review(period: str, start: date, end: date, events: list[GrowthEvent]) -> ReviewResponse:
    names = {"day": "一天的成长回望", "week": "这一周的成长回望", "month": "这个月的成长故事"}
    if not events:
        return ReviewResponse(
            period=period, range_start=start.isoformat(), range_end=end.isoformat(),
            title=names[period], story="", own_effort="还没有记录自己的尝试。",
            support_received="还没有记录实际收到的帮助。", pause_or_restart="还没有相关记录。",
            next_step="愿意时，从很小的一件事开始就好。", sections=[], affirmation="",
            moments=[], source_event_ids=[],
            closing="这段时间还没有成长事件，慢慢来就好。", mock=False,
        )

    efforts = _unique([item.own_effort for item in events if item.own_effort])
    helps = _unique([item.support_received for item in events if item.support_received])
    difficulty_events = [item for item in events if _is_difficulty(item)]
    paused = _join([item.fact for item in _main_difficulties(events)], "还没有记录停顿或重新开始。", 2) if difficulty_events else None
    selected = _key_moments(events, period)
    moments = [ReviewMoment(
        event_id=item.id, date=local_day(item.created_at), title=item.fact,
        source="来自反馈" if item.source_feedback_id else "来自记录" if item.source_record_id else "来自对话" if item.source_user_message_id else "来自事件",
        source_id=item.source_user_message_id, source_feedback_id=item.source_feedback_id,
        source_record_id=item.source_record_id,
    ) for item in selected]
    story = "；".join(moment.title for moment in moments) + "。"
    if helps:
        story += " 这段时间也确实收到了帮助。"
    next_step = f"可以沿着“{efforts[-1][:60]}”再试一小步。" if efforts else "可以从一件最小的事开始，愿意时再记下它。"
    affirmation = (
        f"你确实做过“{efforts[-1][:60]}”，这份具体的尝试值得被看见。"
        if efforts else "你愿意把这些经历如实留下来，这本身就是认真看见自己。"
    )
    sections = _structured_sections(period, events, efforts, helps, paused, next_step)
    if period == "week":
        story = " ".join(f"{item.title}：{item.content}。" for item in sections[:4])
    return ReviewResponse(
        period=period, range_start=start.isoformat(), range_end=end.isoformat(),
        title=names[period], story=story,
        own_effort="；".join(efforts) if efforts else "还没有记录自己的尝试。",
        support_received="；".join(helps) if helps else "还没有记录实际收到的帮助。",
        pause_or_restart=paused or "还没有记录停顿或重新开始。",
        next_step=next_step,
        sections=sections,
        affirmation=affirmation,
        moments=moments, source_event_ids=[item.id for item in events],
        closing="你做过的尝试，和别人给予的帮助，都值得被看见。" if helps else "这些经历都值得认真记下。",
        mock=False, structure_version=REVIEW_STRUCTURE_VERSION,
    )


def polish_review(response: ReviewResponse, events: list[GrowthEvent]) -> ReviewResponse:
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip() or len(events) < 2:
        return response
    facts = [{
        "date": local_day(item.created_at),
        "fact": item.fact,
        "feeling": item.feeling,
        "attempt": item.attempt,
        "own_effort": item.own_effort,
        "support_received": item.support_received,
    } for item in events]
    expected_keys = SECTION_KEYS[response.period]
    client = OpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=35,
        max_retries=0,
    )
    completion = client.chat.completions.create(
        model=settings.chat_model,
        messages=[
            {
                "role": "system",
                "content": (
                    "你负责整理用户的日/周/月成长回望。只输出 JSON，不得新增来源中没有的人物、行为、帮助、结果或诊断。"
                    "语言简洁、温和、具体，不逐条复述日期，不喊口号。sections 的 key 必须严格按给定顺序，"
                    "title 用自然中文短标题，content 每项最多120字。肯定必须指出有记录支持的具体努力；"
                    "若缺少证据，要明确写尚未记录，不能补写。周回望必须形成严格的时间逻辑："
                    "completed 只写困难发生前的起点、目标和已做行动；difficulty 只写受挫、疲惫、自我怀疑或想放弃，"
                    "不能放入结尾的积极状态；process 只写困难出现后的具体应对、自己的尝试和实际收到的支持；"
                    "change 必须比较困难前后，只写证据支持的认知或行动变化；unfinished 写仍存在的担忧和下一小步。"
                    "同一事实不要在多个栏目重复，禁止把首尾原话加引号后直接拼成所谓变化。story 也按上述顺序概括。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "period": response.period,
                    "expected_section_keys": expected_keys,
                    "source_events": facts,
                    "fallback": response.model_dump(exclude={"id", "generated_at", "model", "mock"}),
                }, ensure_ascii=False),
            },
        ],
        response_format={"type": "json_object"},
        temperature=0,
        reasoning_effort="none",
        max_tokens=1000,
    )
    draft = StructuredReviewDraft.model_validate_json(completion.choices[0].message.content or "")
    if [section.key for section in draft.sections] != expected_keys:
        raise ValueError("结构化回望栏目不符合约定")
    return response.model_copy(update={
        "story": draft.story.strip(),
        "sections": draft.sections,
        "affirmation": draft.affirmation.strip(),
        "next_step": draft.next_step.strip(),
        "closing": draft.closing.strip(),
        "model": settings.chat_model,
    })
def saved_review(db: Session, conversation_id: int, period: str, start: date, end: date) -> Review | None:
    return db.scalar(select(Review).where(
        Review.conversation_id == conversation_id, Review.period == period,
        Review.range_start == start.isoformat(), Review.range_end == end.isoformat(),
    ))


def review_for(
    db: Session,
    device_id: str,
    period: str,
    start: date,
    end: date,
    *,
    persist: bool,
    polish: bool = True,
) -> ReviewResponse:
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return compose_review(period, start, end, [])
    events = events_in_range(db, conversation.id, start, end)
    stored = saved_review(db, conversation.id, period, start, end)
    source_ids = [item.id for item in events]
    if stored is not None and stored.source_event_ids == source_ids:
        try:
            cached = ReviewResponse.model_validate_json(stored.content)
            if cached.sections and cached.structure_version == REVIEW_STRUCTURE_VERSION:
                cached.id = stored.id
                cached.generated_at = stored.updated_at or stored.created_at
                return cached
        except Exception:
            pass
    response = compose_review(period, start, end, events)
    if not events:
        return response
    if polish:
        try:
            response = polish_review(response, events)
        except Exception:
            # 模型超时、不可用或输出越界时，保留上面完全由来源生成的可靠模板。
            pass
    payload = response.model_dump_json(exclude={"id", "generated_at"})
    if stored is not None and stored.content == payload:
        response.id = stored.id
        response.generated_at = stored.updated_at or stored.created_at
        return response
    if not persist:
        return response
    if stored is None:
        stored = Review(
            conversation_id=conversation.id,
            period=period,
            range_start=start.isoformat(),
            range_end=end.isoformat(),
            source_event_ids=response.source_event_ids,
            content=payload,
            updated_at=datetime.now(timezone.utc),
        )
        db.add(stored)
        try:
            db.commit()
        except IntegrityError:
            # 两个 overview 请求可能同时发现缓存不存在；唯一键由先提交者占用。
            # 回滚失败事务并复用已创建的缓存，使生成接口保持幂等。
            db.rollback()
            stored = saved_review(db, conversation.id, period, start, end)
            if stored is None:
                raise
            stored.source_event_ids = response.source_event_ids
            stored.content = payload
            stored.updated_at = datetime.now(timezone.utc)
            db.commit()
    else:
        stored.source_event_ids = response.source_event_ids
        stored.content = payload
        stored.updated_at = datetime.now(timezone.utc)
        db.commit()
    response.id = stored.id
    response.generated_at = stored.updated_at
    return response
