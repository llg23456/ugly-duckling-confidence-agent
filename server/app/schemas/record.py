from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


class RecordInput(BaseModel):
    client_record_id: str = Field(min_length=1, max_length=80)
    mode: Literal["text", "voice", "photo"]
    text: str = Field(default="", max_length=4000)
    photo_comment: str = Field(default="", max_length=2000)
    status: Literal["saved", "draft"] = "saved"
    created_at_ms: int = Field(ge=946684800000, le=4102444800000)


class RecordSyncRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    records: list[RecordInput] = Field(max_length=100)


class RecordItem(BaseModel):
    id: int
    client_record_id: str
    mode: str
    text: str
    photo_comment: str
    status: str
    created_at: datetime
    event_id: int | None


class RecordListResponse(BaseModel):
    records: list[RecordItem]
