from fastapi import APIRouter

from app.schemas import ChatRequest, ChatResponse
from app.services.chat_service import chat_with_fallback

router = APIRouter(prefix="/chat", tags=["chat"])


@router.post("", response_model=ChatResponse)
def chat(request: ChatRequest) -> ChatResponse:
    return chat_with_fallback(request)
