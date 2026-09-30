from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

Stage = Literal["beginning", "difficulty", "small_step", "change", "continuing", "help"]


class VideoKeywordSuggestion(BaseModel):
    label: str = Field(min_length=2, max_length=12)
    event_ids: list[int] = Field(min_length=1, max_length=40)


class VideoKeywordSuggestionRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    event_ids: list[int] = Field(min_length=1, max_length=40)


class VideoKeywordSuggestionResponse(BaseModel):
    suggestions: list[VideoKeywordSuggestion] = Field(max_length=10)
    model: str | None


class VideoScene(BaseModel):
    stage: Stage
    title: str = Field(default="成长记录", min_length=1, max_length=40)
    date: str | None = Field(default=None, max_length=10)
    text: str = Field(min_length=1, max_length=120)
    source_event_ids: list[int] = Field(default_factory=list, min_length=1, max_length=1)


class VideoScriptRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    event_ids: list[int] = Field(min_length=3, max_length=7)


class VideoScriptUpdate(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    scenes: list[VideoScene] = Field(min_length=3, max_length=7)


class VideoScriptResponse(BaseModel):
    id: int
    scenes: list[VideoScene]
    source_event_ids: list[int]
    model: str | None
    prompt_version: str
    is_user_edited: bool
    mock: bool
    created_at: datetime
    updated_at: datetime
