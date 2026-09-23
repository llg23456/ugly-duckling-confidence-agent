from typing import Literal

from pydantic import BaseModel, Field


class SupportSuggestionRequest(BaseModel):
    situation: str = Field(min_length=1, max_length=4_000)
    preferred_supporters: list[Literal["teacher", "classmate", "friend", "family", "professional"]] = Field(default_factory=list)


class SupportSuggestionResponse(BaseModel):
    supporter_type: Literal["teacher", "classmate", "friend", "family", "professional"]
    reason: str
    editable_message: str
    auto_send: bool = False
    mock: bool = True
