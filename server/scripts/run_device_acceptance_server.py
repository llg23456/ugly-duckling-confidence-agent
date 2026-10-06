"""Start a local Mock API with an isolated database for Android acceptance tests."""

import argparse
from pathlib import Path
import shutil
import sys

import uvicorn


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--database", type=Path, default=root / "tmp/device-acceptance/acceptance.db")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg") or "")
    args = parser.parse_args()
    database = args.database.resolve()
    database.parent.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(root))
    from app.core import config

    # Do not load the real .env or invoke paid models from acceptance fixtures.
    settings = config.Settings(
        _env_file=None, app_env="development", enable_live_ai=False, dashscope_api_key="",
        database_url=f"sqlite:///{database.as_posix()}", ffmpeg_path=args.ffmpeg,
    )
    config.get_settings = lambda: settings
    from app.main import app

    uvicorn.run(app, host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
