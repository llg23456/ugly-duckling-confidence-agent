from typing import Any

from pydantic import BaseModel, Field


class ProfileField(BaseModel):
    value: str = "unknown"
    certainty: float = Field(default=0.0, ge=0, le=1)


class UserProfile(BaseModel):
    preferred_name: ProfileField = Field(default_factory=ProfileField)
    gender: ProfileField = Field(default_factory=ProfileField)
    age_range: ProfileField = Field(default_factory=ProfileField)
    life_stage: ProfileField = Field(default_factory=ProfileField)
    current_context: ProfileField = Field(default_factory=ProfileField)
    main_challenge: ProfileField = Field(default_factory=ProfileField)
    preferred_support_style: ProfileField = Field(default_factory=ProfileField)
    important_supporters: ProfileField = Field(default_factory=ProfileField)


class OnboardingAnalyzeRequest(BaseModel):
    transcript: str = Field(min_length=1, max_length=4_000)
    existing_profile: dict[str, Any] = Field(default_factory=dict)


class OnboardingAnalyzeResponse(BaseModel):
    profile: UserProfile
    missing_fields: list[str]
    follow_up: str | None = None
    complete: bool
    model: str | None = None
    mock: bool = False


class TranscriptionResponse(BaseModel):
    transcript: str
    model: str
