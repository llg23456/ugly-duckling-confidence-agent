from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.schemas import EventExtractionRequest, EventExtractionResponse
from app.services.mock_service import mock_extract
from app.services.event_service import (
    EventCandidate,
    decision,
    extract_turn,
    memory_point_decision,
)
from app.core.config import get_settings
from app.db.models import DailySummary, GrowthEvent
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas.event import ExtractedMemoryPoint, GrowthEvent as GrowthEventSchema
from app.schemas.memory import DailySummaryItem, DailySummaryListResponse, EventItem, EventListResponse

router = APIRouter(prefix="/events", tags=["events"])


@router.post("/extract", response_model=EventExtractionResponse)
def extract_event(request: EventExtractionRequest) -> EventExtractionResponse:
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return mock_extract(request)
    extracted = extract_turn(request.text, [])
    candidate = extracted.growth_event or EventCandidate(
        fact="", confidence=1.0, sensitivity="low", long_term_value=0,
        growth_significance=0, specificity=0, future_reuse=0, support_value=0,
    )
    score, outcome = decision(candidate)
    memory_points = []
    for item in extracted.memory_points:
        _memory_score, memory_outcome = memory_point_decision(item)
        memory_points.append(ExtractedMemoryPoint(
            content=item.content,
            kind=item.kind,
            evidence_quote=item.evidence_quote,
            confidence=item.confidence,
            sensitivity=item.sensitivity,
            temporal_scope=item.temporal_scope,
            fact_status=item.fact_status,
            memory_decision=memory_outcome,
        ))
    return EventExtractionResponse(
        event=GrowthEventSchema(
            fact=candidate.fact, feeling=candidate.feeling, attempt=candidate.attempt,
            own_effort=candidate.own_effort or candidate.attempt,
            support_received=candidate.support_received, confidence=candidate.confidence,
        ), memory_decision=outcome,
        reason=f"规则评分 {score:.3f}；敏感度 {candidate.sensitivity}。",
        mock=False,
        memory_points=memory_points,
    )


@router.get("", response_model=EventListResponse)
def list_events(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> EventListResponse:
    conversation = get_conversation(db, device_id)
    if not conversation:
        return EventListResponse(events=[])
    events = db.scalars(select(GrowthEvent).where(GrowthEvent.conversation_id == conversation.id).order_by(GrowthEvent.id.desc())).all()
    return EventListResponse(events=[EventItem(
        id=event.id, fact=event.fact, feeling=event.feeling, attempt=event.attempt,
        own_effort=event.own_effort, support_received=event.support_received, people=event.people or [],
        confidence=event.confidence, value_score=event.value_score,
        memory_decision=event.memory_decision, sensitivity=event.sensitivity,
        source_id=event.source_user_message_id,
        source_feedback_id=event.source_feedback_id,
        source_record_id=event.source_record_id,
        source_type=event.source_type, created_at=event.created_at,
    ) for event in events])


@router.get("/daily-summaries", response_model=DailySummaryListResponse)
def list_daily_summaries(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> DailySummaryListResponse:
    conversation = get_conversation(db, device_id)
    if not conversation:
        return DailySummaryListResponse(summaries=[])
    rows = db.scalars(select(DailySummary).where(DailySummary.conversation_id == conversation.id).order_by(DailySummary.day.desc())).all()
    return DailySummaryListResponse(summaries=[DailySummaryItem(
        day=row.day, content=row.content, source_event_ids=row.source_event_ids or [], status=row.status,
    ) for row in rows])
