from fastapi import APIRouter, File, Form, HTTPException, Response, UploadFile

from app.core.config import get_settings
from app.schemas import MultimodalChatResponse, SpeechSynthesisRequest, TranscriptionResponse
from app.services.multimodal_service import chat_with_audio, chat_with_image, synthesize_speech, transcribe_audio


router = APIRouter(prefix="/multimodal", tags=["multimodal"])

MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_AUDIO_BYTES = 6 * 1024 * 1024
IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}
AUDIO_FORMATS = {
    "audio/wav": "wav",
    "audio/x-wav": "wav",
    "audio/mpeg": "mp3",
    "audio/aac": "aac",
    "audio/amr": "amr",
    "audio/3gpp": "3gp",
}


async def _read_limited(upload: UploadFile, limit: int) -> bytes:
    content = await upload.read(limit + 1)
    await upload.close()
    if not content:
        raise HTTPException(status_code=400, detail="上传文件为空")
    if len(content) > limit:
        raise HTTPException(status_code=413, detail="上传文件过大")
    return content


@router.post("/image", response_model=MultimodalChatResponse)
async def image_chat(
    file: UploadFile = File(...),
    prompt: str = Form("请看看这张图片，告诉我你注意到了什么。"),
) -> MultimodalChatResponse:
    mime_type = (file.content_type or "").lower()
    if mime_type not in IMAGE_TYPES:
        raise HTTPException(status_code=415, detail="仅支持 JPEG、PNG 或 WebP 图片")
    content = await _read_limited(file, MAX_IMAGE_BYTES)
    try:
        return chat_with_image(content, mime_type, prompt.strip())
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"图片理解失败：{type(exc).__name__}") from exc


@router.post("/audio", response_model=MultimodalChatResponse)
async def audio_chat(
    file: UploadFile = File(...),
    device_id: str = Form("android-demo"),
) -> MultimodalChatResponse:
    mime_type = (file.content_type or "").lower()
    audio_format = AUDIO_FORMATS.get(mime_type)
    if not audio_format:
        raise HTTPException(status_code=415, detail="仅支持 WAV、MP3、AAC、AMR 或 3GP 音频")
    content = await _read_limited(file, MAX_AUDIO_BYTES)
    try:
        return chat_with_audio(content, mime_type, audio_format, device_id)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"语音理解失败：{type(exc).__name__}") from exc


@router.post("/transcribe", response_model=TranscriptionResponse)
async def transcribe(file: UploadFile = File(...)) -> TranscriptionResponse:
    mime_type = (file.content_type or "").lower()
    audio_format = AUDIO_FORMATS.get(mime_type)
    if not audio_format:
        raise HTTPException(status_code=415, detail="仅支持 WAV、MP3、AAC、AMR 或 3GP 音频")
    content = await _read_limited(file, MAX_AUDIO_BYTES)
    try:
        settings = get_settings()
        transcript_text = transcribe_audio(content, mime_type, audio_format, settings)
        return TranscriptionResponse(transcript=transcript_text, model=settings.chat_model)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"语音转写失败：{type(exc).__name__}") from exc


@router.post("/speech", response_class=Response)
def speech(request: SpeechSynthesisRequest) -> Response:
    try:
        audio, media_type = synthesize_speech(request.text.strip(), request.voice)
        return Response(
            content=audio,
            media_type=media_type,
            headers={
                "Cache-Control": "no-store",
                "X-TTS-Voice": request.voice,
            },
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"语音合成失败：{type(exc).__name__}") from exc
