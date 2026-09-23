from fastapi import APIRouter

from app.schemas import EventExtractionRequest, EventExtractionResponse
from app.services.mock_service import mock_extract

router = APIRouter(prefix="/events", tags=["events"])


@router.post("/extract", response_model=EventExtractionResponse)
def extract_event(request: EventExtractionRequest) -> EventExtractionResponse:
    return mock_extract(request)
