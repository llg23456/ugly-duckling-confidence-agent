from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


SupporterType = Literal["teacher", "senior", "classmate", "friend", "family", "professional"]
FeedbackOutcome = Literal["helped", "not_helped", "not_contacted"]


class SupportPersonCreate(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=120)
    relationship: str = Field(min_length=1, max_length=120)
    kind: SupporterType
    scenarios: list[str] = Field(default_factory=list, max_length=10)


class SupportPersonUpdate(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=120)
    relationship: str = Field(min_length=1, max_length=120)
    kind: SupporterType
    scenarios: list[str] = Field(default_factory=list, max_length=10)


class SupportPersonItem(BaseModel):
    id: int
    name: str
    relationship: str
    kind: SupporterType
    scenarios: list[str]
    created_at: datetime


class SupportPeopleResponse(BaseModel):
    people: list[SupportPersonItem]


class SupportSuggestionRequest(BaseModel):
    situation: str = Field(min_length=1, max_length=4_000)
    preferred_supporters: list[SupporterType] = Field(default_factory=list)
    device_id: str | None = Field(default=None, min_length=1, max_length=128)
    source_message_id: int | None = None


class SupportSuggestionResponse(BaseModel):
    supporter_type: SupporterType
    reason: str
    editable_message: str
    small_step: str = "先试一个很小的步骤。"
    lighter_option: str = "先问问对方是否方便，不必马上说明全部。"
    supporter_id: int | None = None
    supporter_name: str | None = None
    suggestion_id: int | None = None
    auto_send: bool = False
    mock: bool = True


class SupportFeedbackRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    suggestion_id: int
    outcome: FeedbackOutcome
    own_effort: str | None = Field(default=None, max_length=500)
    support_received: str | None = Field(default=None, max_length=500)


class SupportFeedbackItem(BaseModel):
    id: int
    suggestion_id: int
    outcome: FeedbackOutcome
    own_effort: str | None
    support_received: str | None
    supporter_name: str
    created_at: datetime


class SupportFeedbackResponse(BaseModel):
    feedback: SupportFeedbackItem
    event_id: int | None = None


class SupportFeedbackListResponse(BaseModel):
    feedback: list[SupportFeedbackItem]
