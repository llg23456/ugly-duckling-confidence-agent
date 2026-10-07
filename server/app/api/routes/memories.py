from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Path, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, Memory, MemoryDeletion, Message, UserRecord
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas.memory import MemoryItem, MemoryListResponse, MemoryUpdateRequest
from app.services.memory_service import local_day, set_memory_embedding

router = APIRouter(prefix="/memories", tags=["memories"])


def _owned(db: Session, memory_id: int, device_id: str) -> Memory:
    conversation = get_conversation(db, device_id)
    memory = db.get(Memory, memory_id)
    if not conversation or not memory or memory.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="记忆不存在")
    return memory


def _item(db: Session, memory: Memory) -> MemoryItem:
    source_id = (memory.source_message_ids or [None])[0]
    source = db.get(Message, source_id) if source_id else None
    source_date = local_day(source.created_at) if source else None
    source_type = {"text": "chat", "image": "photo", "audio": "voice"}.get(source.modality) if source else None
    if source is None and memory.event_id:
        event = db.get(GrowthEvent, memory.event_id)
        record = db.get(UserRecord, event.source_record_id) if event and event.source_record_id else None
        if record and record.conversation_id == memory.conversation_id:
            source_id = None
            source_date = local_day(record.created_at)
            source_type = {"text": "record", "photo": "photo", "voice": "voice"}.get(record.mode, "record")
    return MemoryItem(
        id=memory.id, content=memory.content, status=memory.status or "active",
        source_id=source_id,
        source_date=source_date,
        source_type=source_type,
        value_score=memory.value_score,
        sensitivity=memory.sensitivity,
        kind=memory.kind or "experience",
        confidence=memory.confidence,
        canonical_key=memory.canonical_key,
        source_excerpt=memory.source_excerpt,
        temporal_scope=memory.temporal_scope,
        fact_status=memory.fact_status,
        last_seen_at=memory.last_seen_at,
        occurrence_count=memory.occurrence_count or 1,
        supersedes_id=memory.supersedes_id,
        created_at=memory.created_at,
    )


@router.get("", response_model=MemoryListResponse)
def list_memories(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> MemoryListResponse:
    conversation = get_conversation(db, device_id)
    if not conversation:
        return MemoryListResponse(memories=[])
    memories = db.scalars(select(Memory).where(Memory.conversation_id == conversation.id).order_by(Memory.id.desc())).all()
    return MemoryListResponse(memories=[_item(db, item) for item in memories])


@router.patch("/{memory_id}", response_model=MemoryItem)
def edit_memory(memory_id: int, request: MemoryUpdateRequest, db: Session = Depends(get_db)) -> MemoryItem:
    memory = _owned(db, memory_id, request.device_id)
    memory.content = request.content.strip()
    if not memory.content:
        raise HTTPException(status_code=422, detail="记忆内容不能为空")
    memory.status = "active"
    memory.is_user_edited = True
    memory.updated_at = datetime.now(UTC)
    memory.last_seen_at = memory.updated_at
    memory.confidence = 1.0
    memory.embedding = None
    memory.embedding_model = None
    set_memory_embedding(memory)
    db.commit()
    return _item(db, memory)


@router.post("/{memory_id}/confirm", response_model=MemoryItem)
def confirm_memory(memory_id: int, device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> MemoryItem:
    memory = _owned(db, memory_id, device_id)
    if memory.status != "pending":
        raise HTTPException(status_code=409, detail="这条记忆无需确认")
    if memory.supersedes_id:
        previous = db.get(Memory, memory.supersedes_id)
        if previous and previous.conversation_id == memory.conversation_id and previous.status == "active":
            previous.status = "superseded"
            previous.updated_at = datetime.now(UTC)
    memory.status = "active"
    memory.updated_at = datetime.now(UTC)
    db.commit()
    return _item(db, memory)


@router.delete("/{memory_id}", status_code=204)
def delete_memory(memory_id: int, device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> None:
    memory = _owned(db, memory_id, device_id)
    dependents = list(db.scalars(select(Memory).where(
        Memory.conversation_id == memory.conversation_id,
        Memory.supersedes_id == memory.id,
    )))
    for dependent in dependents:
        dependent.supersedes_id = None
    db.add(MemoryDeletion(
        conversation_id=memory.conversation_id,
        deleted_memory_id=memory.id,
        source_message_ids=memory.source_message_ids or [],
    ))
    db.delete(memory)
    db.commit()
