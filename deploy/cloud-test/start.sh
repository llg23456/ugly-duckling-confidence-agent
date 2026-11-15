#!/bin/sh
set -eu
python - <<'PY'
import sqlite3
from app.core.config import get_settings
from app.db.session import engine_for_url

url = get_settings().database_url
if url.startswith('sqlite:///'):
    path = url.removeprefix('sqlite:///')
    with sqlite3.connect(path, timeout=30) as db:
        db.execute('PRAGMA journal_mode=WAL')
engine_for_url(url)
PY
exec "$@"
