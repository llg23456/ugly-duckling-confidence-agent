from datetime import date, datetime, timedelta, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas import ReviewResponse
from app.schemas.review import ReviewMoment
from app.services.memory_service import local_day
from app.services.mock_service import mock_review

router = APIRouter(prefix="/reviews", tags=["reviews"])


@router.get("/{period}", response_model=ReviewResponse)
def get_review(
    period: Literal["day", "week", "month"],
    device_id: str | None = Query(default=None, min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> ReviewResponse:
    if device_id is None:
        return mock_review(period)

    today = datetime.now(timezone(timedelta(hours=8))).date()
    conversation = get_conversation(db, device_id)
    events = db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation.id
    ).order_by(GrowthEvent.created_at.desc(), GrowthEvent.id.desc())).all() if conversation else []

    def in_period(event: GrowthEvent) -> bool:
        day = date.fromisoformat(local_day(event.created_at))
        if period == "day":
            return day == today
        if period == "week":
            return today - timedelta(days=6) <= day <= today
        return (day.year, day.month) == (today.year, today.month)

    selected = [event for event in events if in_period(event)]
    effort = list(dict.fromkeys(event.own_effort.strip() for event in selected if event.own_effort and event.own_effort.strip()))
    support = list(dict.fromkeys(event.support_received.strip() for event in selected if event.support_received and event.support_received.strip()))
    titles = {"day": "今天的成长记录", "week": "最近七天的成长记录", "month": "本月的成长记录"}
    return ReviewResponse(
        period=period,
        title=titles[period],
        own_effort="；".join(effort) if effort else "还没有记录自己的尝试。",
        support_received="；".join(support) if support else "还没有记录实际收到的帮助。",
        moments=[ReviewMoment(
            date=local_day(event.created_at), title=event.fact,
            source="来自反馈" if event.source_feedback_id else "来自对话" if event.source_user_message_id else "来自记录",
            source_id=event.source_user_message_id, source_feedback_id=event.source_feedback_id,
        ) for event in selected],
        closing="这些经历都值得认真记下。" if selected else "这段时间还没有成长事件，慢慢来就好。",
        mock=False,
    )
