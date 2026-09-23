from typing import Literal

from fastapi import APIRouter

from app.schemas import ReviewResponse
from app.services.mock_service import mock_review

router = APIRouter(prefix="/reviews", tags=["reviews"])


@router.get("/{period}", response_model=ReviewResponse)
def get_review(period: Literal["day", "week", "month"]) -> ReviewResponse:
    return mock_review(period)
