from typing import Literal

from pydantic import BaseModel


class ReviewMoment(BaseModel):
    date: str
    title: str
    source: str
    source_id: int | None = None
    source_feedback_id: int | None = None


class ReviewResponse(BaseModel):
    period: Literal["day", "week", "month"]
    title: str
    own_effort: str
    support_received: str
    moments: list[ReviewMoment]
    closing: str
    mock: bool = True
