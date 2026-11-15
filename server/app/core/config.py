from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "鸭力Go API"
    app_env: str = "development"
    api_prefix: str = "/api/v1"
    database_url: str = "sqlite:///./confidence_agent.db"
    cloud_auth_required: bool = False
    cloud_daily_requests: int = 60
    cloud_global_daily_requests: int = 300

    enable_live_ai: bool = False
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    dashscope_tts_url: str = (
        "https://dashscope.aliyuncs.com/api/v1/services/"
        "aigc/multimodal-generation/generation"
    )
    chat_model: str = "qwen3.8-omni-flash"
    tts_model: str = "qwen3-tts-flash"
    extraction_model: str = "qwen3.7-flash"
    embedding_model: str = "qwen3.7-text-embedding-flash"
    asr_model: str = "paraformer-v2"
    ffmpeg_path: str = ""
    # Real SMS is deliberately not implemented by the first-version provider.
    notification_mode: str = "disabled"  # disabled | mock (development only)
    wellbeing_model_timeout: float = 15.0
    counseling_url: str = ""  # A verified institution's HTTPS booking page, configured by the operator.
    community_media_dir: str = "./.local-data/community-media"
    community_media_limit_bytes: int = 2 * 1024 * 1024 * 1024

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls,
        init_settings,
        env_settings,
        dotenv_settings,
        file_secret_settings,
    ):
        # 项目专属 .env 优先，避免电脑上其他项目的 DASHSCOPE_API_KEY
        # 意外覆盖本项目的业务空间 Key。
        return init_settings, dotenv_settings, env_settings, file_secret_settings


@lru_cache
def get_settings() -> Settings:
    return Settings()
