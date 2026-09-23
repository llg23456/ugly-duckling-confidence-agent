from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import chat_router, events_router, multimodal_router, onboarding_router, reviews_router, support_router
from app.core.config import get_settings

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    version="0.1.0",
    description="小丑鸭 Android 客户端 API。配置百炼 Key 后启用真实对话，否则回退到 Mock。",
)

if settings.app_env == "development":
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )


@app.get("/health", tags=["system"])
def health() -> dict[str, str | bool]:
    api_key_present = bool(settings.dashscope_api_key.strip())
    ai_configured = settings.enable_live_ai and api_key_present
    return {
        "status": "ok",
        "service": "confidence-agent-api",
        "environment": settings.app_env,
        "mock": not ai_configured,
        "ai_configured": ai_configured,
        "live_ai_enabled": settings.enable_live_ai,
        "api_key_present": api_key_present,
        "chat_model": settings.chat_model,
        "tts_model": settings.tts_model,
    }


app.include_router(chat_router, prefix=settings.api_prefix)
app.include_router(events_router, prefix=settings.api_prefix)
app.include_router(multimodal_router, prefix=settings.api_prefix)
app.include_router(onboarding_router, prefix=settings.api_prefix)
app.include_router(support_router, prefix=settings.api_prefix)
app.include_router(reviews_router, prefix=settings.api_prefix)
