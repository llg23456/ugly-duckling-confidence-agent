from .chat import router as chat_router
from .events import router as events_router
from .multimodal import router as multimodal_router
from .memories import router as memories_router
from .onboarding import router as onboarding_router
from .reviews import router as reviews_router
from .records import router as records_router
from .videos import router as videos_router
from .support import router as support_router
from .support_people import router as support_people_router

__all__ = ["chat_router", "events_router", "multimodal_router", "memories_router", "onboarding_router", "reviews_router", "records_router", "support_router", "support_people_router", "videos_router"]
