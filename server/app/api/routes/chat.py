from fastapi import APIRouter, Depends, Path
from sqlalchemy.orm import Session

from app.db.repository import (
    all_messages, append_exchange, as_schema, get_conversation,
    get_or_create_conversation, model_history, recent_messages,
)
from app.db.session import get_db
from app.schemas import ChatRequest, ChatResponse
from app.schemas.chat import ConversationMessagesResponse
from app.services.chat_service import chat_with_fallback

router = APIRouter(tags=["chat"])


@router.post("/chat", response_model=ChatResponse)
def chat(request: ChatRequest, db: Session = Depends(get_db)) -> ChatResponse:
    conversation = get_conversation(db, request.device_id)
    history = model_history(recent_messages(db, conversation.id)) if conversation else []
    response = chat_with_fallback(request, history=history)
    conversation = conversation or get_or_create_conversation(db, request.device_id)
    user_message, assistant_message = append_exchange(
        db, conversation,
        user_content=request.message,
        assistant_content=response.reply,
        modality="text",
        is_mock=response.mock,
    )
    response.user_message_id = user_message.id
    response.assistant_message_id = assistant_message.id
    return response


@router.get("/conversations/{device_id}/messages", response_model=ConversationMessagesResponse)
def get_messages(
    device_id: str = Path(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> ConversationMessagesResponse:
    conversation = get_conversation(db, device_id)
    messages = all_messages(db, conversation.id) if conversation else []
    return ConversationMessagesResponse(device_id=device_id, messages=[as_schema(item) for item in messages])
