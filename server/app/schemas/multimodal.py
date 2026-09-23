from typing import Literal

from pydantic import BaseModel, Field


class MultimodalChatResponse(BaseModel):
    modality: Literal["audio", "image"]
    user_text: str
    reply: str
    model: str
    mock: bool = False


class SpeechSynthesisRequest(BaseModel):
    text: str = Field(min_length=1, max_length=600)
    voice: Literal["Serena", "Ethan"] = "Serena"
