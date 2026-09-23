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


class ChatResponse(BaseModel):
    reply: str
    strategy: Literal["listen", "reflect", "small_step", "seek_support"]
    evidence: list[MemoryEvidence] = Field(default_factory=list)
    mock: bool = True
    model: str | None = None
    mock_reason: str | None = None
