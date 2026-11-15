"""Consistent SQLite backup; restore only with the application stopped."""

import argparse
from contextlib import closing
from datetime import UTC, datetime
import os
from pathlib import Path
import sqlite3
import json
import hashlib
import shutil
from uuid import UUID


def backup(
    database: Path, output: Path, keep: int = 7, media: Path | None = None
) -> Path:
    output.mkdir(parents=True, exist_ok=True)
    with (output / ".backup.lock").open("a+b") as lock:
        if os.name == "nt":
            import msvcrt

            lock.write(b"\0")
            lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl

            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            return _backup(database, output, keep, media)
        finally:
            if os.name == "nt":
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def _backup(database: Path, output: Path, keep: int, media: Path | None) -> Path:
    if not database.is_file():
        raise FileNotFoundError(database)
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
    target = output / f"confidence-agent-{stamp}.sqlite3"
    temporary = target.with_suffix(".partial")
    try:
        with closing(
            sqlite3.connect(
                database.resolve().as_uri() + "?mode=ro", uri=True, timeout=30
            )
        ) as source:
            with closing(sqlite3.connect(temporary)) as destination:
                source.backup(destination)
                if destination.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                    raise RuntimeError("Backup integrity check failed")
        os.chmod(temporary, 0o600)
        temporary.replace(target)
        # UUID-named immutable pictures are copied once. Each consistent DB snapshot has its own manifest.
        # Deleted post media is inaccessible immediately but retained on disk for seven days, so an
        # in-flight backup can safely copy pictures referenced by its earlier database snapshot.
        manifest = []
        with closing(sqlite3.connect(target)) as snapshot:
            exists = snapshot.execute(
                "SELECT 1 FROM sqlite_master WHERE name='community_posts'"
            ).fetchone()
            if exists:
                for (ids,) in snapshot.execute(
                    "SELECT media_ids FROM community_posts WHERE status IN ('published','hidden')"
                ):
                    for identifier in json.loads(ids):
                        identifier = str(UUID(identifier))
                        if media is None:
                            raise RuntimeError(
                                "Community pictures require --media or COMMUNITY_MEDIA_DIR"
                            )
                        source = media / (identifier + ".jpg")
                        digest = hashlib.sha256(source.read_bytes()).hexdigest()
                        mirror = output / "community-media" / source.name
                        mirror.parent.mkdir(exist_ok=True)
                        if not mirror.exists():
                            staging = mirror.with_suffix(".partial")
                            shutil.copyfile(source, staging)
                            os.chmod(staging, 0o600)
                            staging.replace(mirror)
                        elif hashlib.sha256(mirror.read_bytes()).hexdigest() != digest:
                            raise RuntimeError("Media backup digest mismatch")
                        manifest.append(
                            {
                                "id": identifier,
                                "sha256": digest,
                                "bytes": source.stat().st_size,
                            }
                        )
        metadata = target.with_suffix(".media.json")
        metadata.write_text(
            json.dumps({"database": target.name, "media": manifest}, indent=2),
            encoding="utf-8",
        )
        os.chmod(metadata, 0o600)
    except Exception:
        target.unlink(missing_ok=True)
        target.with_suffix(".media.json").unlink(missing_ok=True)
        raise
    finally:
        temporary.unlink(missing_ok=True)
    for old in sorted(output.glob("confidence-agent-*.sqlite3"), reverse=True)[keep:]:
        old.unlink()
        old.with_suffix(".media.json").unlink(missing_ok=True)
    referenced = set()
    for manifest in output.glob("confidence-agent-*.media.json"):
        referenced.update(
            row["id"]
            for row in json.loads(manifest.read_text(encoding="utf-8"))["media"]
        )
    for picture in (output / "community-media").glob("*.jpg"):
        if picture.stem not in referenced:
            picture.unlink()
    print(target)
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--database", type=Path, default=Path("/data/confidence_agent.db")
    )
    parser.add_argument("--output", type=Path, default=Path("/backups"))
    parser.add_argument(
        "--media",
        type=Path,
        default=(
            Path(os.environ["COMMUNITY_MEDIA_DIR"])
            if os.environ.get("COMMUNITY_MEDIA_DIR")
            else None
        ),
    )
    args = parser.parse_args()
    backup(args.database, args.output, media=args.media)
