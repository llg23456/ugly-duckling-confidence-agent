from fastapi import APIRouter, Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import chat_router, check_ins_router, data_management_router, events_router, multimodal_router, memories_router, onboarding_router, reviews_router, records_router, stress_alerts_router, support_router, support_people_router, videos_router
from app.api.routes.dev_demo import router as dev_demo_router
from app.api.routes.wellbeing import router as wellbeing_router
from app.api.routes.device_sms import router as device_sms_router
from app.core.config import get_settings
from app.core.cloud_access import require_cloud_access
from app.api.routes.cloud_auth import router as cloud_auth_router
from app.api.routes.experience_feedback import router as experience_feedback_router

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    version="0.1.0",
    description="鸭力Go Android 客户端 API。配置百炼 Key 后启用真实对话，否则回退到 Mock。",
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
        "authentication_required": settings.cloud_auth_required,
    }


protected_api = APIRouter(dependencies=[Depends(require_cloud_access)])
protected_api.include_router(experience_feedback_router)
protected_api.include_router(chat_router)
protected_api.include_router(events_router)
protected_api.include_router(multimodal_router)
protected_api.include_router(memories_router)
protected_api.include_router(onboarding_router)
protected_api.include_router(support_router)
protected_api.include_router(support_people_router)
protected_api.include_router(reviews_router)
protected_api.include_router(records_router)
protected_api.include_router(data_management_router)
protected_api.include_router(videos_router)
protected_api.include_router(check_ins_router)
protected_api.include_router(stress_alerts_router)
protected_api.include_router(dev_demo_router)
protected_api.include_router(wellbeing_router)
protected_api.include_router(device_sms_router)
app.include_router(protected_api, prefix=settings.api_prefix)
app.include_router(cloud_auth_router, prefix=settings.api_prefix)
