from typing import Literal

from pydantic import BaseModel, Field


class MultimodalChatResponse(BaseModel):
    modality: Literal["audio", "image"]
    user_text: str
    reply: str
    model: str
    mock: bool = False
    user_message_id: int | None = None
    assistant_message_id: int | None = None


class SpeechSynthesisRequest(BaseModel):
    text: str = Field(min_length=1, max_length=600)
    voice: Literal["Serena", "Ethan"] = "Serena"
