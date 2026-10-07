from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=8_000)
    mode: Literal["listen", "reflect", "suggest"] = "listen"


class MemoryEvidence(BaseModel):
    summary: str
    source_date: str
    source_type: Literal["record", "chat", "photo", "voice"]
    source_id: int | None = None
    source_record_id: int | None = None
    memory_id: int | None = None
    kind: str | None = None
    confidence: float | None = None
    relevance_score: float | None = None


class ChatResponse(BaseModel):
    reply: str
    strategy: Literal["listen", "reflect", "small_step", "seek_support"]
    evidence: list[MemoryEvidence] = Field(default_factory=list)
    mock: bool = True
    model: str | None = None
    mock_reason: str | None = None
    user_message_id: int | None = None
    assistant_message_id: int | None = None
    check_in_scheduled: bool = False
    safety_triggered: bool = False


class ConversationMessage(BaseModel):
    id: int
    role: Literal["user", "assistant"]
    modality: Literal["text", "image", "audio"]
    content: str
    media_ref: str | None = None
    mock: bool = False
    model: str | None = None
    prompt_version: str | None = None
    created_at: datetime


class ConversationMessagesResponse(BaseModel):
    device_id: str
    messages: list[ConversationMessage]
