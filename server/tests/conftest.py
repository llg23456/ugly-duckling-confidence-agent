from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.db.models import Base
from app.db.session import get_db
from app.main import app


@pytest.fixture
def client(tmp_path, monkeypatch) -> Iterator[TestClient]:
    settings = Settings(_env_file=None, enable_live_ai=False, dashscope_api_key="")
    monkeypatch.setattr("app.services.chat_service.get_settings", lambda: settings)
    monkeypatch.setattr("app.services.event_service.get_settings", lambda: settings)
    monkeypatch.setattr("app.main.settings", settings)
    engine = create_engine(f"sqlite:///{tmp_path / 'test.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)

    def test_db():
        with Session(engine) as session:
            yield session

    app.dependency_overrides[get_db] = test_db
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        app.dependency_overrides.clear()
        engine.dispose()
