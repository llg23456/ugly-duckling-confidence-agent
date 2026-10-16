from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Path
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.repository import (
    all_messages, append_exchange, as_schema, get_conversation,
    get_or_create_conversation, model_history, recent_messages,
)
from app.db.session import get_db
from app.db.models import GrowthEvent, Memory, Message, ProactiveCheckIn, StressAlert, SupportSuggestion
from app.schemas import ChatRequest, ChatResponse
from app.schemas.chat import ConversationMessagesResponse
from app.services.chat_service import CHAT_PROMPT_VERSION, chat_with_fallback, suppress_memory_for_message
from app.services.check_in_service import schedule_explicit_follow_up
from app.services.event_service import process_turn
from app.services.memory_service import postgraduate_setback_evidence, recall
from app.services.support_service import is_postgraduate_setback

router = APIRouter(tags=["chat"])


@router.post("/chat", response_model=ChatResponse)
def chat(request: ChatRequest, background_tasks: BackgroundTasks, db: Session = Depends(get_db)) -> ChatResponse:
    conversation = get_conversation(db, request.device_id)
    history = model_history(recent_messages(db, conversation.id)) if conversation else []
    evidence = (
        recall(db, conversation.id, request.message, history=history, intent=request.mode)
        if conversation and not suppress_memory_for_message(request.message)
        else []
    )
    if conversation and is_postgraduate_setback(request.message):
        preferred = postgraduate_setback_evidence(db, conversation.id)
        if preferred is not None:
            preferred_key = (preferred.memory_id, preferred.source_record_id, preferred.source_id)
            evidence = [preferred, *[
                item for item in evidence
                if (item.memory_id, item.source_record_id, item.source_id) != preferred_key
            ]][:4]
    response = chat_with_fallback(request, history=history, evidence=evidence)
    conversation = conversation or get_or_create_conversation(db, request.device_id)
    user_message, assistant_message = append_exchange(
        db, conversation,
        user_content=request.message,
        assistant_content=response.reply,
        modality="text",
        is_mock=response.mock,
        used_memory_ids=[item.memory_id for item in response.evidence if item.memory_id],
        assistant_model=response.model,
        prompt_version=(
            "safety-rule-v1" if response.safety_triggered
            else "postgraduate-setback-rule-v1" if response.model == "postgraduate-setback-rule-v1"
            else "postgraduate-start-rule-v1" if response.model == "postgraduate-start-rule-v1"
            else CHAT_PROMPT_VERSION
        ),
    )
    response.user_message_id = user_message.id
    response.assistant_message_id = assistant_message.id
    if not response.safety_triggered and schedule_explicit_follow_up(db, conversation.id, request.message) is not None:
        db.commit()
        response.check_in_scheduled = True
    if not response.safety_triggered:
        background_tasks.add_task(process_turn, str(db.get_bind().url), conversation.id, user_message.id, assistant_message.id)
    return response


@router.get("/conversations/{device_id}/messages", response_model=ConversationMessagesResponse)
def get_messages(
    device_id: str = Path(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> ConversationMessagesResponse:
    conversation = get_conversation(db, device_id)
    messages = all_messages(db, conversation.id) if conversation else []
    return ConversationMessagesResponse(device_id=device_id, messages=[as_schema(item) for item in messages])


@router.delete("/conversations/{device_id}/messages/{message_id}", status_code=204)
def delete_message(
    device_id: str = Path(min_length=1, max_length=128),
    message_id: int = Path(gt=0),
    db: Session = Depends(get_db),
) -> None:
    conversation = get_conversation(db, device_id)
    message = db.get(Message, message_id)
    if conversation is None or message is None or message.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="消息不存在")

    affected_event_ids = []
    for event in db.scalars(select(GrowthEvent).where(GrowthEvent.conversation_id == conversation.id)):
        if event.source_user_message_id == message_id:
            affected_event_ids.append(event.id)
            event.source_user_message_id = None
        if message_id in (event.source_message_ids or []):
            affected_event_ids.append(event.id)
            event.source_message_ids = [item for item in event.source_message_ids if item != message_id]
    follow_up_ids = []
    for alert in db.scalars(select(StressAlert).where(StressAlert.conversation_id == conversation.id)):
        if set(alert.source_event_ids or []).intersection(affected_event_ids):
            if alert.follow_up_check_in_id:
                follow_up_ids.append(alert.follow_up_check_in_id)
            db.delete(alert)
    db.flush()
    for check_in_id in follow_up_ids:
        check_in = db.get(ProactiveCheckIn, check_in_id)
        if check_in is not None:
            db.delete(check_in)
    for memory in db.scalars(select(Memory).where(Memory.conversation_id == conversation.id)):
        if message_id in (memory.source_message_ids or []):
            memory.source_message_ids = [item for item in memory.source_message_ids if item != message_id]
    for suggestion in db.scalars(select(SupportSuggestion).where(
        SupportSuggestion.conversation_id == conversation.id,
        SupportSuggestion.source_message_id == message_id,
    )):
        suggestion.source_message_id = None

    db.delete(message)
    db.commit()
