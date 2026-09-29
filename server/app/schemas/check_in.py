from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


CheckInChoice = Literal["talk", "improved", "not_now"]


class CheckInItem(BaseModel):
    id: int
    reason: Literal["stuck", "anxious", "low", "follow_up"]
    prompt: str
    source_event_ids: list[int] = Field(default_factory=list)
    created_at: datetime


class PendingCheckInResponse(BaseModel):
    check_in: CheckInItem | None = None


class CheckInResponseRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    choice: CheckInChoice


class CheckInResponseResult(BaseModel):
    id: int
    choice: CheckInChoice
    acknowledgement: str
    follow_up_message: str | None = None
