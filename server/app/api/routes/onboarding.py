from fastapi import APIRouter, HTTPException

from app.schemas.onboarding import OnboardingAnalyzeRequest, OnboardingAnalyzeResponse
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
