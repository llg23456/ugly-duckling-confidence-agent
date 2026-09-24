from functools import lru_cache
from typing import Iterator

from sqlalchemy import create_engine, event, inspect
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import Base


SQLITE_ADDITIONS = {
    "messages": {"used_memory_ids": "JSON"},
    "growth_events": {
        "source_user_message_id": "INTEGER",
        "source_type": "VARCHAR(16)",
        "feeling": "TEXT",
        "attempt": "TEXT",
        "support_received": "TEXT",
        "people": "JSON",
        "confidence": "FLOAT",
        "value_score": "FLOAT",
        "sensitivity": "VARCHAR(16)",
        "memory_decision": "VARCHAR(16)",
        "model": "VARCHAR(100)",
        "prompt_version": "VARCHAR(32)",
    },
    "memories": {
        "event_id": "INTEGER",
        "status": "VARCHAR(16)",
        "value_score": "FLOAT",
        "sensitivity": "VARCHAR(16)",
        "is_user_edited": "BOOLEAN",
        "model": "VARCHAR(100)",
        "prompt_version": "VARCHAR(32)",
        "updated_at": "DATETIME",
    },
}


def upgrade_existing_sqlite(engine) -> None:
    """Add P1 fields to databases created by P0 without touching user rows."""
    from sqlalchemy import text

    with engine.begin() as connection:
        for table, additions in SQLITE_ADDITIONS.items():
            existing = {column["name"] for column in inspect(connection).get_columns(table)}
            for name, column_type in additions.items():
                if name not in existing:
                    connection.execute(text(f"ALTER TABLE {table} ADD COLUMN {name} {column_type}"))
        connection.execute(text(
            "CREATE UNIQUE INDEX IF NOT EXISTS ix_growth_events_source_user_message_id "
            "ON growth_events (source_user_message_id)"
        ))
        connection.execute(text(
            "CREATE UNIQUE INDEX IF NOT EXISTS ix_memories_event_id ON memories (event_id)"
        ))


@lru_cache
def engine_for_url(database_url: str):
    engine = create_engine(
        database_url,
        connect_args={"check_same_thread": False} if database_url.startswith("sqlite") else {},
        pool_pre_ping=True,
    )
    if database_url.startswith("sqlite"):
        @event.listens_for(engine, "connect")
        def enable_foreign_keys(connection, _record) -> None:
            cursor = connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()
    Base.metadata.create_all(engine)
    if database_url.startswith("sqlite"):
        upgrade_existing_sqlite(engine)
    return engine


def get_db() -> Iterator[Session]:
    engine = engine_for_url(get_settings().database_url)
    with Session(engine) as session:
        yield session
