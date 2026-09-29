from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Query
from fastapi.encoders import jsonable_encoder
from sqlalchemy import delete, select
from sqlalchemy.inspection import inspect as sqlalchemy_inspect
from sqlalchemy.orm import Session

from app.db.models import (
    Conversation,
    DailySummary,
    GrowthEvent,
    Memory,
    MemoryDeletion,
    Message,
    ProactiveCheckIn,
    Review,
    SupportFeedback,
    SupportPerson,
    SupportSuggestion,
    UserRecord,
    VideoScript,
)
from app.db.repository import get_conversation
from app.db.session import get_db

router = APIRouter(prefix="/data", tags=["data"])


def _rows(db: Session, model, conversation_id: int) -> list[dict]:
    result = []
    for row in db.scalars(select(model).where(model.conversation_id == conversation_id)).all():
        values = {
            column.key: getattr(row, column.key)
            for column in sqlalchemy_inspect(model).columns
            if column.key not in {"embedding"}
        }
        result.append(jsonable_encoder(values))
    return result


@router.get("/export")
def export_data(
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> dict:
    conversation = get_conversation(db, device_id)
    empty = {
        "messages": [], "records": [], "growth_events": [], "memories": [],
        "daily_summaries": [], "reviews": [], "support_people": [],
        "support_suggestions": [], "support_feedback": [], "video_scripts": [],
        "proactive_check_ins": [],
    }
    if conversation is None:
        return {"exported_at": datetime.now(UTC).isoformat(), "device_id": device_id, "data": empty}
    data = {
        "messages": _rows(db, Message, conversation.id),
        "records": _rows(db, UserRecord, conversation.id),
        "growth_events": _rows(db, GrowthEvent, conversation.id),
        "memories": _rows(db, Memory, conversation.id),
        "daily_summaries": _rows(db, DailySummary, conversation.id),
        "reviews": _rows(db, Review, conversation.id),
        "support_people": _rows(db, SupportPerson, conversation.id),
        "support_suggestions": _rows(db, SupportSuggestion, conversation.id),
        "support_feedback": _rows(db, SupportFeedback, conversation.id),
        "video_scripts": _rows(db, VideoScript, conversation.id),
        "proactive_check_ins": _rows(db, ProactiveCheckIn, conversation.id),
    }
    return {"exported_at": datetime.now(UTC).isoformat(), "device_id": device_id, "data": data}


@router.delete("", status_code=204)
def delete_all_data(
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> None:
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return
    conversation_id = conversation.id
    # Dependents first: this works with SQLite foreign keys enabled and remains idempotent.
    for model in (
        MemoryDeletion,
        Memory,
        DailySummary,
        Review,
        VideoScript,
        ProactiveCheckIn,
        GrowthEvent,
        SupportFeedback,
        SupportSuggestion,
        SupportPerson,
        UserRecord,
        Message,
    ):
        db.execute(delete(model).where(model.conversation_id == conversation_id))
    db.execute(delete(Conversation).where(Conversation.id == conversation_id))
    db.commit()
