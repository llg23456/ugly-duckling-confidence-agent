"""Local-only device acceptance backend with a new database and image-request counts.

Uses explicit Settings(_env_file=None): this project's .env normally overrides env vars.
Run with server/.venv/Scripts/python.exe server/scripts/run_community_cache_acceptance.py.
Never used by the production app.main entry point.
"""

import argparse
from collections import defaultdict
from pathlib import Path
import sys
import threading
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8769)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2] / ".codex_tmp" / "community-cache-tests"
    workspace = (root / uuid4().hex).resolve()
    if not workspace.is_relative_to(root.resolve()):
        raise RuntimeError("Unexpected acceptance path")
    workspace.mkdir(parents=True, exist_ok=False)
    import app.core.config as config

    settings = config.Settings(
        _env_file=None,
        app_env="development",
        enable_live_ai=False,
        cloud_auth_required=False,
        dashscope_api_key="",
        notification_mode="disabled",
        database_url="sqlite:///" + (workspace / "acceptance.db").as_posix(),
        community_media_dir=str(workspace / "media"),
    )
    config.get_settings = lambda: settings
    from app.main import app
    from fastapi import Query
    import uvicorn

    counts = defaultdict(int)
    lock = threading.Lock()

    @app.middleware("http")
    async def count_media(request, call_next):
        if request.method == "GET" and request.url.path.startswith(
            "/api/v1/community/media/"
        ):
            with lock:
                counts[request.query_params.get("device_id", "")] += 1
        return await call_next(request)

    @app.get("/_community_cache_metrics")
    def metrics(device_id: str = Query(min_length=1)):
        with lock:
            return {"media_gets": counts[device_id]}

    print(
        "Isolated acceptance database: " + str(workspace / "acceptance.db"), flush=True
    )
    uvicorn.run(app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
