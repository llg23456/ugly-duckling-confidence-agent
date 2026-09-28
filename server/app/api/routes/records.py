from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, UserRecord
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.schemas.record import RecordItem, RecordListResponse, RecordSyncRequest

router = APIRouter(prefix="/records", tags=["records"])


def _item(db: Session, record: UserRecord) -> RecordItem:
    event_id = db.scalar(select(GrowthEvent.id).where(GrowthEvent.source_record_id == record.id))
    return RecordItem(
        id=record.id, client_record_id=record.client_record_id, mode=record.mode,
        text=record.text, photo_comment=record.photo_comment, status=record.status,
        created_at=record.created_at, event_id=event_id,
    )


@router.post("/sync", response_model=RecordListResponse)
def sync_records(request: RecordSyncRequest, db: Session = Depends(get_db)) -> RecordListResponse:
    conversation = get_or_create_conversation(db, request.device_id)
    result = []
    for input_record in request.records:
        record = db.scalar(select(UserRecord).where(
            UserRecord.conversation_id == conversation.id,
            UserRecord.client_record_id == input_record.client_record_id,
        ))
        if record is None:
            record = UserRecord(conversation_id=conversation.id, client_record_id=input_record.client_record_id)
            db.add(record)
        record.mode = input_record.mode
        record.text = input_record.text.strip()
        record.photo_comment = input_record.photo_comment.strip()
        record.status = input_record.status
        record.created_at = datetime.fromtimestamp(input_record.created_at_ms / 1000, UTC)
        record.updated_at = datetime.now(UTC)
        db.flush()
        event = db.scalar(select(GrowthEvent).where(GrowthEvent.source_record_id == record.id))
        if record.status == "saved":
            fact = record.photo_comment or record.text
            if not fact:
                fact = {"voice": "保存了一段语音记录", "photo": "保存了一张照片记录"}.get(record.mode, "")
            if fact:
                if event is None:
                    event = GrowthEvent(conversation_id=conversation.id, source_record_id=record.id,
                                        source_message_ids=[], source_type=record.mode,
                                        confidence=1.0, memory_decision="ignore", model="user_record",
                                        prompt_version="p3.1")
                    db.add(event)
                event.fact = fact
                event.created_at = record.created_at
        elif event is not None:
            db.delete(event)
        db.flush()
        result.append(_item(db, record))
    db.commit()
    return RecordListResponse(records=result)


@router.get("", response_model=RecordListResponse)
def list_records(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> RecordListResponse:
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return RecordListResponse(records=[])
    rows = db.scalars(select(UserRecord).where(UserRecord.conversation_id == conversation.id).order_by(UserRecord.created_at.desc())).all()
    return RecordListResponse(records=[_item(db, row) for row in rows])


@router.get("/{record_id}", response_model=RecordItem)
def get_record(record_id: int, device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> RecordItem:
    conversation = get_conversation(db, device_id)
    record = db.get(UserRecord, record_id)
    if conversation is None or record is None or record.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="记录不存在")
    return _item(db, record)
