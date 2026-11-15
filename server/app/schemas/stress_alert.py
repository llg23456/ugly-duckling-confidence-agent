from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


StressLevel = Literal["watch", "high"]


class StressTrendPoint(BaseModel):
    date: str
    level: Literal["unknown", "steady", "watch", "high"]
    signal_count: int = 0
    record_count: int = 0
    message_count: int = 0


class StressEvidenceItem(BaseModel):
    event_id: int
    date: str
    excerpt: str
    source_type: str
    source_message_id: int | None = None
    source_record_id: int | None = None
    level: Literal["watch", "high"]


class StressSupportItem(BaseModel):
    supporter_name: str
    reason: str
    editable_message: str
    small_step: str
    lighter_option: str


class StressFollowUpItem(BaseModel):
    id: int
    prompt: str
    ready: bool


class StressAlertDetail(BaseModel):
    id: int
    level: StressLevel
    status: str
    title: str
    prompt: str
    trend: list[StressTrendPoint]
    factors: list[str]
    evidence: list[StressEvidenceItem]
    self_check: dict | None = None
    support: StressSupportItem
    support_copy: str
    professional_support_hint: str
    follow_up: StressFollowUpItem | None = None
    created_at: datetime


class ActiveStressAlertResponse(BaseModel):
    alert: StressAlertDetail | None = None


class StressTrendSource(BaseModel):
    date: str
    source_type: Literal["record", "chat"]
    source_id: int
    excerpt: str
    level: Literal["steady", "watch", "high"]


class StressTrendReport(BaseModel):
    level: Literal["unknown", "steady", "watch", "high"]
    trend: list[StressTrendPoint]
    sources: list[StressTrendSource]
    record_count: int
    message_count: int
    observed_days: int
    alert: StressAlertDetail | None = None


class StressDeviceRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)


class StressSelfCheckRequest(StressDeviceRequest):
    impact: Literal["none", "some", "significant"]
    coping: Literal["manageable", "strained", "overwhelmed"]

