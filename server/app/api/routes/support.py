from fastapi import APIRouter

from app.schemas import SupportSuggestionRequest, SupportSuggestionResponse
from app.services.mock_service import mock_support

router = APIRouter(prefix="/support", tags=["support"])


@router.post("/suggest", response_model=SupportSuggestionResponse)
def suggest_support(request: SupportSuggestionRequest) -> SupportSuggestionResponse:
    return mock_support(request)
