from typing import Literal

from pydantic import BaseModel, Field


class EventExtractionRequest(BaseModel):
    text: str = Field(min_length=1, max_length=12_000)
    source_type: Literal["record", "chat", "photo", "voice"]


class GrowthEvent(BaseModel):
    fact: str
    feeling: str | None = None
    attempt: str | None = None
    own_effort: str | None = None
    support_received: str | None = None
    confidence: float = Field(ge=0, le=1)


class ExtractedMemoryPoint(BaseModel):
    content: str
    kind: Literal["identity", "preference", "goal", "ongoing_context", "support_person", "experience"]
    evidence_quote: str
    confidence: float = Field(ge=0, le=1)
    sensitivity: Literal["low", "medium", "high"]
    temporal_scope: Literal["stable", "ongoing", "dated", "one_off"]
    fact_status: Literal["asserted", "planned", "completed", "negated"]
    memory_decision: Literal["ignore", "long_term", "confirm"]


class EventExtractionResponse(BaseModel):
    event: GrowthEvent
    memory_decision: Literal["ignore", "daily", "long_term", "confirm"]
    reason: str
    mock: bool = True
    memory_points: list[ExtractedMemoryPoint] = Field(default_factory=list)
