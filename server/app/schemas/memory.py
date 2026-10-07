from datetime import datetime

from pydantic import BaseModel, Field


class MemoryItem(BaseModel):
    id: int
    content: str
    status: str
    source_id: int | None
    source_date: str | None
    source_type: str | None
    value_score: float | None
    sensitivity: str | None
    kind: str | None = None
    confidence: float | None = None
    canonical_key: str | None = None
    source_excerpt: str | None = None
    temporal_scope: str | None = None
    fact_status: str | None = None
    last_seen_at: datetime | None = None
    occurrence_count: int = 1
    supersedes_id: int | None = None
    created_at: datetime


class MemoryListResponse(BaseModel):
    memories: list[MemoryItem]


class MemoryUpdateRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    content: str = Field(min_length=1, max_length=500)


class EventItem(BaseModel):
    id: int
    fact: str
    feeling: str | None
    attempt: str | None
    own_effort: str | None
    support_received: str | None
    people: list[str]
    confidence: float | None
    value_score: float | None
    memory_decision: str | None
    sensitivity: str | None = None
    source_id: int | None
    source_feedback_id: int | None
    source_record_id: int | None
    source_type: str | None
    created_at: datetime


class EventListResponse(BaseModel):
    events: list[EventItem]


class DailySummaryItem(BaseModel):
    day: str
    content: str
    source_event_ids: list[int]
    status: str


class DailySummaryListResponse(BaseModel):
    summaries: list[DailySummaryItem]
