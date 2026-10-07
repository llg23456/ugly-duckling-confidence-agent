import base64
from collections.abc import Callable
import struct

import httpx
from openai import OpenAI

from app.core.config import Settings, get_settings
from app.schemas import ChatRequest, MultimodalChatResponse
from app.services.chat_service import (
    chat_with_fallback,
    dialogue_system_prompt,
    generate_structured_dialogue,
    is_crisis_message,
)
from app.schemas.chat import MemoryEvidence


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


def _finalize_streaming_wav(content: bytes) -> bytes:
    """Replace streaming WAV placeholder sizes with the downloaded byte lengths."""
    if len(content) < 20 or content[:4] != b"RIFF" or content[8:12] != b"WAVE":
        return content

    audio = bytearray(content)
    struct.pack_into("<I", audio, 4, min(len(audio) - 8, 0xFFFFFFFF))
    offset = 12
    while offset + 8 <= len(audio):
        chunk_name = bytes(audio[offset:offset + 4])
        chunk_size = struct.unpack_from("<I", audio, offset + 4)[0]
        data_start = offset + 8
        if chunk_name == b"data":
            remaining = len(audio) - data_start
            if chunk_size > remaining:
                struct.pack_into("<I", audio, offset + 4, min(remaining, 0xFFFFFFFF))
            break
        next_offset = data_start + chunk_size + (chunk_size % 2)
        if next_offset <= offset or next_offset > len(audio):
            break
        offset = next_offset
    return bytes(audio)


def chat_with_image(
    image_bytes: bytes,
    mime_type: str,
    prompt: str,
    settings: Settings | None = None,
    history: list[dict[str, str]] | None = None,
    evidence: list[MemoryEvidence] | None = None,
) -> MultimodalChatResponse:
    active_settings = settings or get_settings()
    evidence = evidence or []
    request = ChatRequest(device_id="image-chat", message=prompt, mode="listen")
    if is_crisis_message(prompt):
        safety = chat_with_fallback(request, settings=active_settings, history=history, evidence=[])
        return MultimodalChatResponse(
            modality="image", user_text=prompt, reply=safety.reply,
            model=safety.model or "safety-rule-v1", evidence=[],
            strategy=safety.strategy, safety_triggered=True,
        )
    system_prompt, _intent, required_strategy = dialogue_system_prompt(request, history, evidence)
    messages = [
            {"role": "system", "content": system_prompt},
            *(history or []),
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
        ]
    try:
        envelope, strategy, used_evidence = generate_structured_dialogue(
            _client(active_settings),
            model=active_settings.chat_model,
            messages=messages,
            required_strategy=required_strategy,
            evidence=evidence,
            max_tokens=500,
        )
    except Exception:
        return MultimodalChatResponse(
            modality="image",
            user_text=prompt,
            reply="图片这次没有被可靠识别，请稍后重试，或用一句话补充你希望我关注的内容。",
            model=active_settings.chat_model,
            evidence=[],
            strategy="listen",
            mock=True,
        )
    return MultimodalChatResponse(
        modality="image",
        user_text=prompt,
        reply=envelope.reply,
        model=active_settings.chat_model,
        evidence=used_evidence,
        strategy=strategy,
    )


def chat_with_audio(
    audio_bytes: bytes,
    mime_type: str,
    audio_format: str,
    device_id: str,
    settings: Settings | None = None,
    history: list[dict[str, str]] | None = None,
    evidence: list[MemoryEvidence] | None = None,
    recall_for_text: Callable[[str], list[MemoryEvidence]] | None = None,
) -> MultimodalChatResponse:
    active_settings = settings or get_settings()
    transcript = transcribe_audio(audio_bytes, mime_type, audio_format, active_settings)
    if recall_for_text:
        evidence = recall_for_text(transcript)

    chat_response = chat_with_fallback(
        ChatRequest(device_id=device_id, message=transcript, mode="listen"),
        settings=active_settings,
        history=history,
        evidence=evidence,
    )
    return MultimodalChatResponse(
        modality="audio",
        user_text=transcript,
        reply=chat_response.reply,
        model=chat_response.model or active_settings.chat_model,
        mock=chat_response.mock,
        evidence=chat_response.evidence,
        strategy=chat_response.strategy,
        safety_triggered=chat_response.safety_triggered,
    )


def transcribe_audio(
    audio_bytes: bytes,
    mime_type: str,
    audio_format: str,
    settings: Settings | None = None,
) -> str:
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
    return transcript


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
        return _finalize_streaming_wav(audio_response.content), media_type
