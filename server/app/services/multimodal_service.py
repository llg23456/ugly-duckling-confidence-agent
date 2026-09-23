import base64

import httpx
from openai import OpenAI

from app.core.config import Settings, get_settings
from app.schemas import ChatRequest, MultimodalChatResponse
from app.services.chat_service import SYSTEM_PROMPT, chat_with_fallback


TRANSCRIPTION_PROMPT = """请准确转写这段用户语音。
只输出用户实际说出的文字，不要回答，不要解释，不要添加引号。
如果存在少量听不清的部分，用“[听不清]”标注；如果没有可辨识语音，输出“[没有识别到语音]”。"""

IMAGE_PROMPT_SUFFIX = """请只根据图片中确实可见的内容回答，不要臆测身份、疾病或隐私。
如果图片信息不足，直接说明需要用户补充什么。回复保持温柔、简短。"""


def _client(settings: Settings) -> OpenAI:
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        raise RuntimeError("Live AI is not configured")
    return OpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=60.0,
        max_retries=1,
    )


def _data_uri(content: bytes, mime_type: str) -> str:
    encoded = base64.b64encode(content).decode("ascii")
    return f"data:{mime_type};base64,{encoded}"


def _message_text(content) -> str:
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                parts.append(str(item.get("text", "")))
            else:
                text = getattr(item, "text", None)
                if text:
                    parts.append(str(text))
        return "".join(parts).strip()
    return ""


def chat_with_image(
    image_bytes: bytes,
    mime_type: str,
    prompt: str,
    settings: Settings | None = None,
) -> MultimodalChatResponse:
    active_settings = settings or get_settings()
    completion = _client(active_settings).chat.completions.create(
        model=active_settings.chat_model,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {"url": _data_uri(image_bytes, mime_type)},
                    },
                    {"type": "text", "text": f"{prompt}\n\n{IMAGE_PROMPT_SUFFIX}"},
                ],
            },
        ],
        reasoning_effort="none",
        max_tokens=240,
    )
    reply = _message_text(completion.choices[0].message.content)
    if not reply:
        raise RuntimeError("Image model returned empty content")
    return MultimodalChatResponse(
        modality="image",
        user_text=prompt,
        reply=reply,
        model=active_settings.chat_model,
    )


def chat_with_audio(
    audio_bytes: bytes,
    mime_type: str,
    audio_format: str,
    device_id: str,
    settings: Settings | None = None,
) -> MultimodalChatResponse:
    active_settings = settings or get_settings()
    response = _client(active_settings).responses.create(
        model=active_settings.chat_model,
        input=[
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": TRANSCRIPTION_PROMPT},
                    {
                        "type": "input_audio",
                        "data": _data_uri(audio_bytes, mime_type),
                        "format": audio_format,
                    },
                ],
            }
        ],
    )
    transcript = response.output_text.strip()
    if not transcript:
        raise RuntimeError("Audio model returned empty transcription")

    chat_response = chat_with_fallback(
        ChatRequest(device_id=device_id, message=transcript, mode="listen"),
        settings=active_settings,
    )
    if chat_response.mock:
        raise RuntimeError(chat_response.mock_reason or "Chat fallback was used")
    return MultimodalChatResponse(
        modality="audio",
        user_text=transcript,
        reply=chat_response.reply,
        model=active_settings.chat_model,
    )


def synthesize_speech(
    text: str,
    voice: str,
    settings: Settings | None = None,
) -> tuple[bytes, str]:
    """Use Qwen3-TTS and proxy the short-lived audio back to Android."""
    active_settings = settings or get_settings()
    if not active_settings.enable_live_ai or not active_settings.dashscope_api_key.strip():
        raise RuntimeError("Live AI is not configured")

    with httpx.Client(timeout=60.0, follow_redirects=True) as client:
        response = client.post(
            active_settings.dashscope_tts_url,
            headers={
                "Authorization": f"Bearer {active_settings.dashscope_api_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": active_settings.tts_model,
                "input": {
                    "text": text,
                    "voice": voice,
                    "language_type": "Chinese",
                },
            },
        )
        response.raise_for_status()
        result = response.json()
        audio_url = result.get("output", {}).get("audio", {}).get("url")
        if not audio_url:
            raise RuntimeError("TTS model returned no audio URL")

        audio_response = client.get(audio_url)
        audio_response.raise_for_status()
        media_type = audio_response.headers.get("content-type", "audio/wav").split(";", 1)[0]
        if not media_type.startswith("audio/"):
            media_type = "audio/wav"
        return audio_response.content, media_type
