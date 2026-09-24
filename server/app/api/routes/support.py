from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, Message, SupportFeedback, SupportPerson, SupportSuggestion
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.schemas.support import (
    SupportFeedbackItem, SupportFeedbackListResponse, SupportFeedbackRequest,
    SupportFeedbackResponse, SupportSuggestionRequest, SupportSuggestionResponse,
)
from app.services.support_service import build_suggestion, choose_person

router = APIRouter(prefix="/support", tags=["support"])


@router.post("/suggest", response_model=SupportSuggestionResponse)
def suggest_support(request: SupportSuggestionRequest, db: Session = Depends(get_db)) -> SupportSuggestionResponse:
    conversation = get_or_create_conversation(db, request.device_id) if request.device_id else None
    if request.source_message_id is not None:
        source = db.get(Message, request.source_message_id)
        if not conversation or not source or source.conversation_id != conversation.id or source.role != "user":
            raise HTTPException(status_code=404, detail="来源消息不存在")
    people = list(db.scalars(select(SupportPerson).where(SupportPerson.conversation_id == conversation.id))) if conversation else []
    person = choose_person(request, people)
    result = build_suggestion(request, person)
    if conversation:
        saved = SupportSuggestion(
            conversation_id=conversation.id, support_person_id=person.id if person else None,
            source_message_id=request.source_message_id, situation=request.situation.strip(),
            supporter_type=result.supporter_type, supporter_name=result.supporter_name,
            reason=result.reason, editable_message=result.editable_message,
            small_step=result.small_step, lighter_option=result.lighter_option,
        )
        db.add(saved)
        db.commit()
        result.suggestion_id = saved.id
    return result


def _feedback_item(feedback: SupportFeedback, suggestion: SupportSuggestion) -> SupportFeedbackItem:
    return SupportFeedbackItem(
        id=feedback.id, suggestion_id=feedback.suggestion_id, outcome=feedback.outcome,
        own_effort=feedback.own_effort, support_received=feedback.support_received,
        supporter_name=suggestion.supporter_name, created_at=feedback.created_at,
    )


@router.post("/feedback", response_model=SupportFeedbackResponse)
def save_feedback(request: SupportFeedbackRequest, db: Session = Depends(get_db)) -> SupportFeedbackResponse:
    conversation = get_conversation(db, request.device_id)
    suggestion = db.get(SupportSuggestion, request.suggestion_id)
    if not conversation or not suggestion or suggestion.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="建议不存在")
    if db.scalar(select(SupportFeedback.id).where(SupportFeedback.suggestion_id == suggestion.id)):
        raise HTTPException(status_code=409, detail="这条建议已经反馈过")
    effort = request.own_effort.strip() if request.own_effort else None
    received = request.support_received.strip() if request.support_received else None
    if request.outcome == "helped" and not received:
        received = f"{suggestion.supporter_name}帮到了我"
    if request.outcome != "helped":
        received = None
    feedback = SupportFeedback(
        conversation_id=conversation.id, suggestion_id=suggestion.id,
        outcome=request.outcome, own_effort=effort or None, support_received=received or None,
    )
    db.add(feedback)
    db.flush()
    event = None
    if request.outcome == "helped" or (request.outcome == "not_helped" and effort):
        fact = received if request.outcome == "helped" else f"我尝试向{suggestion.supporter_name}求助，但这次没有帮到我"
        event = GrowthEvent(
            conversation_id=conversation.id, fact=fact,
            source_message_ids=[suggestion.source_message_id] if suggestion.source_message_id else [],
            source_type="feedback", source_feedback_id=feedback.id,
            attempt=effort, own_effort=effort, support_received=received,
            people=[suggestion.supporter_name], confidence=1.0,
            sensitivity="low", memory_decision="ignore", model="user_feedback", prompt_version="p2.1",
        )
        db.add(event)
    db.commit()
    return SupportFeedbackResponse(feedback=_feedback_item(feedback, suggestion), event_id=event.id if event else None)


@router.get("/feedback", response_model=SupportFeedbackListResponse)
def list_feedback(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> SupportFeedbackListResponse:
    conversation = get_conversation(db, device_id)
    if not conversation:
        return SupportFeedbackListResponse(feedback=[])
    records = db.scalars(select(SupportFeedback).where(SupportFeedback.conversation_id == conversation.id).order_by(SupportFeedback.id.desc())).all()
    return SupportFeedbackListResponse(feedback=[_feedback_item(item, db.get(SupportSuggestion, item.suggestion_id)) for item in records])
