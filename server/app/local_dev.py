"""Isolated local development: python -m app.local_dev [--live-ai].

Uses a separate database and explicit settings; never changes the cloud invite gate.
"""
import argparse
from pathlib import Path

from app.core import config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live-ai", action="store_true", help="Use the existing .env AI credentials")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--host", default="127.0.0.1", help="Use 0.0.0.0 to allow phones on the same trusted LAN")
    args = parser.parse_args()
    storage = Path(".local-data")
    storage.mkdir(exist_ok=True)
    runtime = config.Settings(
        _env_file=".env" if args.live_ai else None,
        app_env="development", database_url=f"sqlite:///{storage / 'wellbeing.db'}",
        cloud_auth_required=False, notification_mode="mock",
        enable_live_ai=args.live_ai,
        **({} if args.live_ai else {"dashscope_api_key": ""}),
    )
    config.get_settings = lambda: runtime
    # Recover committed mock jobs left behind by an interrupted local run.
    from app.services.notification_service import dispatch_for_url
    dispatch_for_url(runtime.database_url)
    import uvicorn
    uvicorn.run("app.main:app", host=args.host, port=args.port)


if __name__ == "__main__":
    main()
