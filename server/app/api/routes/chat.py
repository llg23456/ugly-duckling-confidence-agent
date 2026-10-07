from fastapi import APIRouter, BackgroundTasks, Depends, Path
from sqlalchemy.orm import Session

from app.db.repository import (
    all_messages, append_exchange, as_schema, get_conversation,
    get_or_create_conversation, model_history, recent_messages,
)
from app.db.session import get_db
from app.schemas import ChatRequest, ChatResponse
from app.schemas.chat import ConversationMessagesResponse
from app.services.chat_service import CHAT_PROMPT_VERSION, chat_with_fallback, suppress_memory_for_message
from app.services.check_in_service import schedule_explicit_follow_up
from app.services.event_service import process_turn
from app.services.memory_service import recall

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
        prompt_version="safety-rule-v1" if response.safety_triggered else CHAT_PROMPT_VERSION,
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
