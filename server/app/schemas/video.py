from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

Stage = Literal["difficulty", "small_step", "help", "change", "continuing"]


class VideoScene(BaseModel):
    stage: Stage
    text: str = Field(min_length=1, max_length=120)
    source_event_ids: list[int] = Field(default_factory=list, max_length=6)


class VideoScriptRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    event_ids: list[int] = Field(min_length=1, max_length=6)


class VideoScriptUpdate(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    scenes: list[VideoScene] = Field(min_length=2, max_length=5)


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
