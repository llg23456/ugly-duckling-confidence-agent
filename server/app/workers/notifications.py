"""python -m app.workers.notifications [--once]; only the mock provider exists."""
import argparse
import time

from app.core.config import get_settings
from app.services.notification_service import dispatch_for_url


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--local", action="store_true", help="Use .local-data/wellbeing.db and mock settings")
    args = parser.parse_args()
    if args.local:
        from pathlib import Path
        from app.core.config import Settings
        import app.services.notification_service as notifications
        Path(".local-data").mkdir(exist_ok=True)
        settings = Settings(_env_file=None, app_env="development", notification_mode="mock",
                            database_url="sqlite:///.local-data/wellbeing.db", enable_live_ai=False, dashscope_api_key="")
        notifications.get_settings = lambda: settings
    else:
        settings = get_settings()
    while True:
        dispatch_for_url(settings.database_url)
        if args.once:
            return
        time.sleep(5)


if __name__ == "__main__":
    main()
