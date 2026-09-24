from datetime import date, datetime, timedelta, timezone

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, Review
from app.db.repository import get_conversation
from app.schemas.review import ReviewMoment, ReviewResponse
from app.services.memory_service import local_day


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


def compose_review(period: str, start: date, end: date, events: list[GrowthEvent]) -> ReviewResponse:
    names = {"day": "一天的成长回望", "week": "这一周的成长回望", "month": "这个月的成长故事"}
    if not events:
        return ReviewResponse(
            period=period, range_start=start.isoformat(), range_end=end.isoformat(),
            title=names[period], story="", own_effort="还没有记录自己的尝试。",
            support_received="还没有记录实际收到的帮助。", pause_or_restart="还没有相关记录。",
            next_step="愿意时，从很小的一件事开始就好。", moments=[], source_event_ids=[],
            closing="这段时间还没有成长事件，慢慢来就好。", mock=False,
        )

    efforts = list(dict.fromkeys(item.own_effort.strip() for item in events if item.own_effort and item.own_effort.strip()))
    helps = list(dict.fromkeys(item.support_received.strip() for item in events if item.support_received and item.support_received.strip()))
    setbacks = ("卡住", "失败", "停下", "暂停", "重新", "没帮到", "没进展", "不敢")
    paused = next((item.fact for item in events if any(word in (item.fact + (item.feeling or "")) for word in setbacks)), None)
    selected = _key_moments(events, period)
    moments = [ReviewMoment(
        event_id=item.id, date=local_day(item.created_at), title=item.fact,
        source="来自反馈" if item.source_feedback_id else "来自记录" if item.source_record_id else "来自对话" if item.source_user_message_id else "来自事件",
        source_id=item.source_user_message_id, source_feedback_id=item.source_feedback_id,
        source_record_id=item.source_record_id,
    ) for item in selected]
    story = "；".join(f"{moment.date}，{moment.title}" for moment in moments) + "。"
    if helps:
        story += " 这段时间也确实收到了帮助。"
    return ReviewResponse(
        period=period, range_start=start.isoformat(), range_end=end.isoformat(),
        title=names[period], story=story,
        own_effort="；".join(efforts) if efforts else "还没有记录自己的尝试。",
        support_received="；".join(helps) if helps else "还没有记录实际收到的帮助。",
        pause_or_restart=paused or "还没有记录停顿或重新开始。",
        next_step=f"可以沿着“{efforts[-1][:60]}”再试一小步。" if efforts else "可以从一件最小的事开始，愿意时再记下它。",
        moments=moments, source_event_ids=[item.id for item in events],
        closing="你做过的尝试，和别人给予的帮助，都值得被看见。" if helps else "这些经历都值得认真记下。",
        mock=False,
    )


def saved_review(db: Session, conversation_id: int, period: str, start: date, end: date) -> Review | None:
    return db.scalar(select(Review).where(
        Review.conversation_id == conversation_id, Review.period == period,
        Review.range_start == start.isoformat(), Review.range_end == end.isoformat(),
    ))


def review_for(db: Session, device_id: str, period: str, start: date, end: date, *, persist: bool) -> ReviewResponse:
    conversation = get_conversation(db, device_id)
    events = events_in_range(db, conversation.id, start, end) if conversation else []
    response = compose_review(period, start, end, events)
    if not conversation or not events:
        return response
    stored = saved_review(db, conversation.id, period, start, end)
    payload = response.model_dump_json(exclude={"id", "generated_at"})
    if stored is not None and stored.content == payload:
        response.id = stored.id
        response.generated_at = stored.updated_at or stored.created_at
        return response
    if not persist:
        return response
    if stored is None:
        stored = Review(conversation_id=conversation.id, period=period,
                        range_start=start.isoformat(), range_end=end.isoformat())
        db.add(stored)
    stored.source_event_ids = response.source_event_ids
    stored.content = payload
    stored.updated_at = datetime.now(timezone.utc)
    db.commit()
    response.id = stored.id
    response.generated_at = stored.updated_at
    return response
