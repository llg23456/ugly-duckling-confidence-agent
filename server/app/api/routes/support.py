from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Conversation, GrowthEvent, Message, SupportFeedback, SupportPerson, SupportSuggestion, WellbeingSignal
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.schemas.support import (
    SupportFeedbackItem, SupportFeedbackListResponse, SupportFeedbackRequest,
    SupportFeedbackResponse, SupportSuggestionRequest, SupportSuggestionResponse,
)
from app.services.support_service import build_suggestion, choose_person, explicit_support_person, next_study_support_kind
from app.services.support_intent import infer_support_intent
from app.services.check_in_service import evaluate_check_in
from app.services.next_steps import personalize
from app.services.wellbeing_assessment import rule_assessment, support_action

router = APIRouter(prefix="/support", tags=["support"])


@router.post("/suggest", response_model=SupportSuggestionResponse)
def suggest_support(request: SupportSuggestionRequest, db: Session = Depends(get_db)) -> SupportSuggestionResponse:
    conversation = get_or_create_conversation(db, request.device_id) if request.device_id else None
    if request.source_message_id is not None:
        source = db.get(Message, request.source_message_id)
        if not conversation or not source or source.conversation_id != conversation.id or source.role != "user":
            raise HTTPException(status_code=404, detail="来源消息不存在")
        request = request.model_copy(update={"situation": source.content})
    signal = db.scalar(select(WellbeingSignal).where(
        WellbeingSignal.conversation_id == conversation.id, WellbeingSignal.source_kind == "message",
        WellbeingSignal.source_id == request.source_message_id)) if conversation and request.source_message_id else None
    rules = rule_assessment(request.situation)
    assessment = dict(signal.assessment) if signal else {**rules.model_dump(), "action": support_action(rules)}
    contacted_kinds: set[str] = set()
    if conversation:
        contacted_kinds = set(db.scalars(
            select(SupportSuggestion.supporter_type)
            .join(SupportFeedback, SupportFeedback.suggestion_id == SupportSuggestion.id)
            .where(
                SupportSuggestion.conversation_id == conversation.id,
                SupportFeedback.outcome.in_(("helped", "not_helped")),
            )
        ).all())
    history = []
    conversation_id = conversation.id if conversation else None
    if conversation:
        query = select(Message).where(Message.conversation_id == conversation_id)
        if request.source_message_id:
            query = query.where(Message.id <= request.source_message_id)
        history = [{"role": item.role, "content": item.content}
                   for item in reversed(db.scalars(query.order_by(Message.id.desc()).limit(8)).all())]
    db.commit()  # Release the read transaction before the model request.
    intent = infer_support_intent(request.situation, history, assessment)
    # Fetch current user-configured purposes after inference, without sending names
    # or phone numbers to the intent classifier. The model cannot invent a contact.
    people = list(db.scalars(select(SupportPerson).where(SupportPerson.conversation_id == conversation_id))) if conversation_id else []
    explicit = explicit_support_person(request.situation, people)
    required_kind = None if explicit or intent.need == "emotional" else next_study_support_kind(request.situation, contacted_kinds)
    person = choose_person(request, people, required_kind=required_kind, assessment=assessment, resolved_need=intent.need)
    if not person or person.kind != required_kind:
        required_kind = None
    result = build_suggestion(request, person, required_kind=required_kind, resolved_need=intent.need, focus=intent.focus)
    if explicit:
        result.reason = f"你这次明确想联系{explicit.name}，先按你的选择，问问对方是否方便。"
    elif required_kind == "teacher":
        result.reason = "你已经和师兄或师姐交流过，下一步可以请老师帮你确认专业方向或复习重点。"
    elif required_kind == "family":
        result.reason = "你已经向老师请教过具体问题，下一步可以让家人了解你的压力和需要的陪伴。"
    person_id = person.id if person else None
    person_snapshot = (person.name, person.relationship, person.kind, list(person.scenarios or [])) if person else None
    assessment = {**assessment, "support_need": intent.need, "support_focus": intent.focus}
    db.commit()
    result = personalize(result, request, history, assessment)
    if conversation_id:
        if db.get(Conversation, conversation_id) is None:
            raise HTTPException(status_code=404, detail="会话已删除")
        if request.source_message_id and db.get(Message, request.source_message_id) is None:
            raise HTTPException(status_code=404, detail="来源消息已删除")
        if person_id:
            current = db.get(SupportPerson, person_id)
            if not current or (current.name, current.relationship, current.kind, list(current.scenarios or [])) != person_snapshot:
                raise HTTPException(status_code=409, detail="支持者已更新，请重试")
        saved = SupportSuggestion(
            conversation_id=conversation_id, support_person_id=person_id,
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
    if event is not None:
        db.flush()
        evaluate_check_in(db, conversation.id)
    db.commit()
    return SupportFeedbackResponse(feedback=_feedback_item(feedback, suggestion), event_id=event.id if event else None)


@router.get("/feedback", response_model=SupportFeedbackListResponse)
def list_feedback(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> SupportFeedbackListResponse:
    conversation = get_conversation(db, device_id)
    if not conversation:
        return SupportFeedbackListResponse(feedback=[])
    records = db.scalars(select(SupportFeedback).where(SupportFeedback.conversation_id == conversation.id).order_by(SupportFeedback.id.desc())).all()
    return SupportFeedbackListResponse(feedback=[_feedback_item(item, db.get(SupportSuggestion, item.suggestion_id)) for item in records])
