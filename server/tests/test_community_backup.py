import importlib.util
import json
import sqlite3
from pathlib import Path
from uuid import uuid4

import pytest


def module():
    script = Path("/srv/app/backup.py")
    if not script.is_file():
        script = Path(__file__).resolve().parents[2] / "deploy/cloud-test/backup.py"
    spec = importlib.util.spec_from_file_location("community_backup", script)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_paired_backups_deduplicate_media_and_verify_manifest(tmp_path):
    media = tmp_path / "media"
    media.mkdir()
    identifier = str(uuid4())
    (media / (identifier + ".jpg")).write_bytes(b"immutable image")
    database = tmp_path / "live.db"
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE community_posts (status TEXT,media_ids TEXT)")
        db.execute(
            "INSERT INTO community_posts VALUES (?,?)",
            ("hidden", json.dumps([identifier])),
        )
    backup = module()
    out = tmp_path / "backups"
    first = backup.backup(database, out, keep=2, media=media)
    second = backup.backup(database, out, keep=2, media=media)
    assert first.exists() and second.exists()
    assert len(list((out / "community-media").glob("*.jpg"))) == 1
    manifest = json.loads(second.with_suffix(".media.json").read_text())
    assert (
        manifest["database"] == second.name and manifest["media"][0]["id"] == identifier
    )
    with sqlite3.connect(database) as db:
        db.execute("UPDATE community_posts SET status='deleted'")
    backup.backup(database, out, keep=1, media=media)
    assert not first.exists() and not second.with_suffix(".media.json").exists()
    assert list((out / "community-media").glob("*.jpg")) == []


def test_missing_picture_does_not_leave_an_unpaired_database(tmp_path):
    database = tmp_path / "live.db"
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE community_posts (status TEXT,media_ids TEXT)")
        db.execute(
            "INSERT INTO community_posts VALUES (?,?)",
            ("published", json.dumps([str(uuid4())])),
        )
    out = tmp_path / "backups"
    with pytest.raises(FileNotFoundError):
        module().backup(database, out, media=tmp_path / "missing")
    assert list(out.glob("*.sqlite3")) == [] and list(out.glob("*.partial")) == []
