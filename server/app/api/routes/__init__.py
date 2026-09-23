from .chat import router as chat_router
from .events import router as events_router
from .multimodal import router as multimodal_router
from .reviews import router as reviews_router
from .support import router as support_router

__all__ = ["chat_router", "events_router", "multimodal_router", "reviews_router", "support_router"]
