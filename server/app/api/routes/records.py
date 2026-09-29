from datetime import UTC, datetime

from fastapi import APIRouter, BackgroundTasks, Depends, File, HTTPException, Query, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import DailySummary, GrowthEvent, Memory, ProactiveCheckIn, Review, UserRecord, VideoScript
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.schemas.record import RecordItem, RecordListResponse, RecordSyncRequest
from app.services.check_in_service import evaluate_check_in
from app.services.event_service import process_record
from app.services.multimodal_service import chat_with_image

router = APIRouter(prefix="/records", tags=["records"])


def _item(db: Session, record: UserRecord) -> RecordItem:
    event_id = db.scalar(select(GrowthEvent.id).where(GrowthEvent.source_record_id == record.id))
    return RecordItem(
        id=record.id, client_record_id=record.client_record_id, mode=record.mode,
        text=record.text, photo_comment=record.photo_comment,
        ai_description=record.ai_description or "", status=record.status,
        created_at=record.created_at, event_id=event_id,
    )


@router.post("/sync", response_model=RecordListResponse)
def sync_records(request: RecordSyncRequest, background_tasks: BackgroundTasks, db: Session = Depends(get_db)) -> RecordListResponse:
    conversation = get_or_create_conversation(db, request.device_id)
    result = []
    records_to_process: list[int] = []
    for input_record in request.records:
        record = db.scalar(select(UserRecord).where(
            UserRecord.conversation_id == conversation.id,
            UserRecord.client_record_id == input_record.client_record_id,
        ))
        is_new = record is None
        if record is None:
            record = UserRecord(conversation_id=conversation.id, client_record_id=input_record.client_record_id)
            db.add(record)
        old_created_at_ms = None if is_new or record.created_at is None else int(
            record.created_at.replace(tzinfo=record.created_at.tzinfo or UTC).timestamp() * 1000
        )
        changed = is_new or any((
            record.mode != input_record.mode,
            record.text != input_record.text.strip(),
            record.photo_comment != input_record.photo_comment.strip(),
            (record.ai_description or "") != input_record.ai_description.strip(),
            record.status != input_record.status,
            old_created_at_ms != input_record.created_at_ms,
        ))
        record.mode = input_record.mode
        record.text = input_record.text.strip()
        record.photo_comment = input_record.photo_comment.strip()
        record.ai_description = input_record.ai_description.strip()
        record.status = input_record.status
        if changed:
            record.embedding = None
            record.embedding_model = None
        record.created_at = datetime.fromtimestamp(input_record.created_at_ms / 1000, UTC)
        record.updated_at = datetime.now(UTC)
        db.flush()
        event = db.scalar(select(GrowthEvent).where(GrowthEvent.source_record_id == record.id))
        if record.status == "saved":
            fact = record.photo_comment or record.text or record.ai_description
            if not fact:
                fact = {"voice": "保存了一段语音记录", "photo": "保存了一张照片记录"}.get(record.mode, "")
            if fact:
                if event is None:
                    event = GrowthEvent(conversation_id=conversation.id, source_record_id=record.id,
                                        source_message_ids=[], source_type=record.mode,
                                        confidence=1.0, value_score=1.0, memory_decision="record", model="user_record",
                                        prompt_version="p3.1")
                    db.add(event)
                event.fact = fact
                event.created_at = record.created_at
                if changed:
                    _invalidate_event_derivatives(db, record.conversation_id, event.id)
                    records_to_process.append(record.id)
        elif event is not None:
            _delete_record_and_derived(db, record, delete_record=False)
        db.flush()
        result.append(_item(db, record))
    evaluate_check_in(db, conversation.id)
    db.commit()
    database_url = get_settings().database_url
    for record_id in records_to_process:
        background_tasks.add_task(process_record, database_url, conversation.id, record_id)
    return RecordListResponse(records=result)


@router.post("/describe-photo")
async def describe_photo(file: UploadFile = File(...)) -> dict[str, str]:
    mime_type = (file.content_type or "").lower()
    if mime_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=415, detail="仅支持 JPEG、PNG 或 WebP 图片")
    content = await file.read(8 * 1024 * 1024 + 1)
    await file.close()
    if not content:
        raise HTTPException(status_code=400, detail="图片为空")
    if len(content) > 8 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="图片过大")
    try:
        result = chat_with_image(
            content,
            mime_type,
            "请为这张生活记录生成一条客观、便于日后检索的中文描述。只写画面中确实可见的主体、动作、场景和文字，不推测身份、关系、疾病或情绪，控制在40字以内。",
        )
        return {"description": result.reply.strip()[:2000]}
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"照片描述失败：{type(exc).__name__}") from exc


@router.get("", response_model=RecordListResponse)
def list_records(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> RecordListResponse:
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return RecordListResponse(records=[])
    rows = db.scalars(select(UserRecord).where(UserRecord.conversation_id == conversation.id).order_by(UserRecord.created_at.desc())).all()
    return RecordListResponse(records=[_item(db, row) for row in rows])


def _invalidate_event_derivatives(db: Session, conversation_id: int, event_id: int) -> None:
    for summary in db.scalars(select(DailySummary).where(DailySummary.conversation_id == conversation_id)).all():
        if event_id in (summary.source_event_ids or []):
            db.delete(summary)
    for review in db.scalars(select(Review).where(Review.conversation_id == conversation_id)).all():
        if event_id in (review.source_event_ids or []):
            db.delete(review)
    for script in db.scalars(select(VideoScript).where(VideoScript.conversation_id == conversation_id)).all():
        if event_id in (script.source_event_ids or []):
            db.delete(script)
    for check_in in db.scalars(select(ProactiveCheckIn).where(ProactiveCheckIn.conversation_id == conversation_id)).all():
        if event_id in (check_in.source_event_ids or []):
            db.delete(check_in)


def _delete_record_and_derived(db: Session, record: UserRecord, *, delete_record: bool = True) -> None:
    event = db.scalar(select(GrowthEvent).where(GrowthEvent.source_record_id == record.id))
    if event is not None:
        event_id = event.id
        _invalidate_event_derivatives(db, record.conversation_id, event_id)
        for memory in db.scalars(select(Memory).where(Memory.event_id == event_id)).all():
            db.delete(memory)
        db.delete(event)
    if delete_record:
        db.delete(record)


@router.delete("/client/{client_record_id}", status_code=204)
def delete_record_by_client_id(
    client_record_id: str,
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> None:
    conversation = get_conversation(db, device_id)
    record = db.scalar(select(UserRecord).where(
        UserRecord.conversation_id == (conversation.id if conversation else -1),
        UserRecord.client_record_id == client_record_id,
    ))
    if conversation is None or record is None:
        # Idempotent deletion lets an Android tombstone be retried safely.
        return
    _delete_record_and_derived(db, record)
    db.commit()


@router.get("/{record_id}", response_model=RecordItem)
def get_record(record_id: int, device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> RecordItem:
    conversation = get_conversation(db, device_id)
    record = db.get(UserRecord, record_id)
    if conversation is None or record is None or record.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="记录不存在")
    return _item(db, record)
