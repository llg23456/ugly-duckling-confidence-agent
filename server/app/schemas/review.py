from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, Field


class ReviewMoment(BaseModel):
    event_id: int | None = None
    date: str
    title: str
    source: str
    source_id: int | None = None
    source_feedback_id: int | None = None
    source_record_id: int | None = None


class ReviewGenerateRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    period: Literal["day", "week", "month"]
    start_date: date | None = None
    end_date: date | None = None


class PendingDailyRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)


class PendingDailyResponse(BaseModel):
    reviews: list["ReviewResponse"] = Field(default_factory=list)


class ReviewResponse(BaseModel):
    id: int | None = None
    period: Literal["day", "week", "month"]
    range_start: str | None = None
    range_end: str | None = None
    title: str
    story: str = ""
    own_effort: str
    support_received: str
    pause_or_restart: str = ""
    next_step: str = ""
    moments: list[ReviewMoment]
    source_event_ids: list[int] = Field(default_factory=list)
    closing: str
    generated_at: datetime | None = None
    mock: bool = True
