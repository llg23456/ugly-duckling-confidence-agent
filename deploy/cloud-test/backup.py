"""Consistent SQLite backup; restore only with the application stopped."""
import argparse
from datetime import UTC, datetime
import os
from pathlib import Path
import sqlite3


def backup(database: Path, output: Path, keep: int = 7) -> Path:
    if not database.is_file():
        raise FileNotFoundError(database)
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(UTC).strftime('%Y%m%dT%H%M%S%fZ')
    target = output / f'confidence-agent-{stamp}.sqlite3'
    temporary = target.with_suffix('.partial')
    try:
        with sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True, timeout=30) as source:
            with sqlite3.connect(temporary) as destination:
                source.backup(destination)
                if destination.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                    raise RuntimeError('Backup integrity check failed')
        os.chmod(temporary, 0o600)
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    for old in sorted(output.glob('confidence-agent-*.sqlite3'), reverse=True)[keep:]:
        old.unlink()
    print(target)
    return target


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, default=Path('/data/confidence_agent.db'))
    parser.add_argument('--output', type=Path, default=Path('/backups'))
    args = parser.parse_args()
    backup(args.database, args.output)
