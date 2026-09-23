from typing import Literal

from pydantic import BaseModel, Field


class EventExtractionRequest(BaseModel):
    text: str = Field(min_length=1, max_length=12_000)
    source_type: Literal["record", "chat", "photo", "voice"]


class GrowthEvent(BaseModel):
    fact: str
    feeling: str | None = None
    attempt: str | None = None
    support_received: str | None = None
    confidence: float = Field(ge=0, le=1)


class EventExtractionResponse(BaseModel):
    event: GrowthEvent
    memory_decision: Literal["ignore", "daily", "long_term", "confirm"]
    reason: str
    mock: bool = True
