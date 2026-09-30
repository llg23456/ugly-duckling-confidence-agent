from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Message, UserRecord
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas.onboarding import OnboardingAnalyzeRequest, OnboardingAnalyzeResponse, ProfileRefreshRequest
from app.services.onboarding_service import analyze_onboarding, onboarding_schema


router = APIRouter(prefix="/onboarding", tags=["onboarding"])


@router.get("/schema")
def get_schema() -> dict:
    return onboarding_schema()


@router.post("/analyze", response_model=OnboardingAnalyzeResponse)
def analyze(request: OnboardingAnalyzeRequest) -> OnboardingAnalyzeResponse:
    try:
        return analyze_onboarding(request)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"画像提取失败：{type(exc).__name__}") from exc


@router.post("/refresh", response_model=OnboardingAnalyzeResponse)
def refresh_profile(request: ProfileRefreshRequest, db: Session = Depends(get_db)) -> OnboardingAnalyzeResponse:
    conversation = get_conversation(db, request.device_id)
    if conversation is None:
        raise HTTPException(status_code=404, detail="还没有可用于更新画像的内容")
    messages = list(db.scalars(select(Message).where(
        Message.conversation_id == conversation.id,
        Message.role == "user",
    ).order_by(Message.id.desc()).limit(60)))
    records = list(db.scalars(select(UserRecord).where(
        UserRecord.conversation_id == conversation.id,
        UserRecord.status == "saved",
    ).order_by(UserRecord.id.desc()).limit(40)))
    evidence = [f"[对话-{item.modality}] {item.content}" for item in reversed(messages)]
    evidence.extend(
        f"[主动记录-{item.mode}] " + " ".join(
            part.strip() for part in (item.text, item.photo_comment, item.ai_description) if part and part.strip()
        )
        for item in reversed(records)
    )
    transcript = "\n".join(item for item in evidence if item.strip())[-12_000:]
    if not transcript.strip():
        raise HTTPException(status_code=404, detail="还没有可用于更新画像的内容")
    try:
        return analyze_onboarding(OnboardingAnalyzeRequest(
            transcript=transcript,
            existing_profile=request.existing_profile,
        ))
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"画像更新失败：{type(exc).__name__}") from exc
