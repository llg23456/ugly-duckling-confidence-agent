from datetime import date
from typing import Literal

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas import ReviewResponse
from app.schemas.review import PendingDailyRequest, PendingDailyResponse, ReviewGenerateRequest
from app.services.mock_service import mock_review
from app.services.memory_service import local_day
from app.services.review_service import date_range, review_for, today_local

router = APIRouter(prefix="/reviews", tags=["reviews"])


@router.post("/generate", response_model=ReviewResponse)
def generate_review(request: ReviewGenerateRequest, db: Session = Depends(get_db)) -> ReviewResponse:
    start, end = date_range(request.period, request.start_date, request.end_date)
    return review_for(db, request.device_id, request.period, start, end, persist=True)


@router.post("/generate-pending-daily", response_model=PendingDailyResponse)
def generate_pending_daily(request: PendingDailyRequest, db: Session = Depends(get_db)) -> PendingDailyResponse:
    conversation = get_conversation(db, request.device_id)
    if conversation is None:
        return PendingDailyResponse()
    dates = sorted({local_day(item.created_at) for item in db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation.id
    )) if local_day(item.created_at) < today_local().isoformat()}, reverse=True)
    return PendingDailyResponse(reviews=[review_for(
        db, request.device_id, "day", date.fromisoformat(day), date.fromisoformat(day), persist=True,
    ) for day in dates])


@router.get("/{period}", response_model=ReviewResponse)
def get_review(
    period: Literal["day", "week", "month"],
    device_id: str | None = Query(default=None, min_length=1, max_length=128),
    start_date: date | None = None,
    end_date: date | None = None,
    db: Session = Depends(get_db),
) -> ReviewResponse:
    if device_id is None:
        return mock_review(period)
    start, end = date_range(period, start_date, end_date)
    return review_for(db, device_id, period, start, end, persist=False)
