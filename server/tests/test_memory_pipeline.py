from importlib import import_module
from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, select, text
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.db.models import Conversation, Memory, Message
from app.db.session import engine_for_url
from app.schemas import ChatResponse
from app.services.event_service import (
    EventCandidate,
    MemoryPointCandidate,
    TurnExtraction,
    decision,
    memory_point_decision,
)
from app.services.memory_service import local_day, recall


def candidate(**changes) -> EventCandidate:
    payload = dict(
        fact="我在课堂上主动提问", confidence=.95, sensitivity="low",
        long_term_value=.8, growth_significance=.8, specificity=.8,
        future_reuse=.8, support_value=.8,
    )
    payload.update(changes)
    return EventCandidate(**payload)


def memory_point(message_id: int = 1, **changes) -> MemoryPointCandidate:
    payload = dict(
        content="我会主动向老师提问",
        kind="experience",
        evidence_quote="我今天在课堂上主动提问了",
        source_message_ids=[message_id],
        confidence=.95,
        sensitivity="low",
        temporal_scope="dated",
        fact_status="completed",
        importance=.85,
        future_reuse=.8,
        canonical_key="experience:ask_teacher",
    )
    payload.update(changes)
    return MemoryPointCandidate(**payload)


def test_decision_thresholds_and_sensitive_gate() -> None:
    assert decision(candidate())[1] == "long_term"
    assert decision(candidate(long_term_value=.6, growth_significance=.6, specificity=.6, future_reuse=.6, support_value=.6))[1] == "daily"
    assert decision(candidate(long_term_value=.3, growth_significance=.3, specificity=.3, future_reuse=.3, support_value=.3))[1] == "ignore"
    assert decision(candidate(sensitivity="high"))[1] == "confirm"
    assert decision(candidate(confidence=.5))[1] == "confirm"
    assert decision(candidate(is_conflicting=True))[1] == "confirm"
    assert decision(candidate(fact=""))[1] == "ignore"
    assert decision(candidate(fact_status="planned"))[1] == "ignore"
    assert decision(candidate(fact_status="negated"))[1] == "ignore"
    assert memory_point_decision(memory_point())[1] == "long_term"
    assert memory_point_decision(memory_point(fact_status="planned"))[1] == "ignore"
    assert memory_point_decision(memory_point(sensitivity="medium"))[1] == "confirm"


def test_extract_store_recall_edit_and_delete(client: TestClient, monkeypatch) -> None:
    chat_route = import_module("app.api.routes.chat")
    event_service = import_module("app.services.event_service")
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="test")
    monkeypatch.setattr(event_service, "get_settings", lambda: settings)
    def fake_extract(text, existing, context=None, current_message_id=None):
        if "今天" not in text:
            return TurnExtraction()
        return TurnExtraction(
            growth_event=candidate(),
            memory_points=[memory_point(current_message_id, source_message_ids=[current_message_id])],
        )

    monkeypatch.setattr(event_service, "extract_turn", fake_extract)
    observed = []

    def fake_chat(request, history=None, evidence=None):
        observed.append((history, evidence))
        return ChatResponse(reply="我听到了。", strategy="listen", mock=False, evidence=evidence or [])

    monkeypatch.setattr(chat_route, "chat_with_fallback", fake_chat)
    first = client.post("/api/v1/chat", json={"device_id": "p1-device", "message": "我今天在课堂上主动提问了"})
    assert first.status_code == 200
    source_id = first.json()["user_message_id"]
    memories = client.get("/api/v1/memories?device_id=p1-device").json()["memories"]
    assert len(memories) == 1
    memory = memories[0]
    assert memory["source_id"] == source_id and memory["status"] == "active"
    events = client.get("/api/v1/events?device_id=p1-device").json()["events"]
    assert events[0]["source_id"] == source_id and events[0]["memory_decision"] == "long_term"

    second = client.post("/api/v1/chat", json={"device_id": "p1-device", "message": "课堂提问之后呢"})
    evidence = second.json()["evidence"]
    assert evidence and evidence[0]["source_id"] == source_id
    assert evidence[0]["memory_id"] == memory["id"]
    assert observed[-1][1][0].summary == memory["content"]

    edited = client.patch(f"/api/v1/memories/{memory['id']}", json={"device_id": "p1-device", "content": "我在数学课主动提问"})
    assert edited.status_code == 200 and edited.json()["content"] == "我在数学课主动提问"
    assert client.patch(f"/api/v1/memories/{memory['id']}", json={"device_id": "someone-else", "content": "bad"}).status_code == 404
    deleted = client.delete(f"/api/v1/memories/{memory['id']}?device_id=p1-device")
    assert deleted.status_code == 204
    assert client.get("/api/v1/memories?device_id=p1-device").json()["memories"] == []
    third = client.post("/api/v1/chat", json={"device_id": "p1-device", "message": "课堂提问还记得吗"})
    assert third.json()["evidence"] == []
    assert all("我今天在课堂上主动提问了" not in item["content"] for item in observed[-1][0])


def test_pending_confirmation(client: TestClient, monkeypatch) -> None:
    chat_route = import_module("app.api.routes.chat")
    event_service = import_module("app.services.event_service")
    monkeypatch.setattr(event_service, "get_settings", lambda: Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="test"))
    monkeypatch.setattr(event_service, "extract_turn", lambda text, existing, context=None, current_message_id=None: TurnExtraction(
        growth_event=candidate(sensitivity="medium"),
        memory_points=[memory_point(
            current_message_id,
            source_message_ids=[current_message_id],
            sensitivity="medium",
            evidence_quote=text,
        )],
    ))
    monkeypatch.setattr(chat_route, "chat_with_fallback", lambda request, history=None, evidence=None: ChatResponse(reply="收到", strategy="listen", mock=False))
    client.post("/api/v1/chat", json={"device_id": "sensitive", "message": "我在课堂上主动提问"})
    item = client.get("/api/v1/memories?device_id=sensitive").json()["memories"][0]
    assert item["status"] == "pending"
    assert client.post("/api/v1/chat", json={"device_id": "sensitive", "message": "课堂提问"}).json()["evidence"] == []
    assert client.post(f"/api/v1/memories/{item['id']}/confirm?device_id=sensitive").json()["status"] == "active"
    assert client.post(f"/api/v1/memories/{item['id']}/confirm?device_id=sensitive").status_code == 409


def test_daily_draft_and_extraction_failure_do_not_break_chat(client: TestClient, monkeypatch) -> None:
    chat_route = import_module("app.api.routes.chat")
    event_service = import_module("app.services.event_service")
    monkeypatch.setattr(event_service, "get_settings", lambda: Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="test"))
    monkeypatch.setattr(event_service, "extract_turn", lambda text, existing, context=None, current_message_id=None: TurnExtraction(
        growth_event=candidate(
            long_term_value=.6, growth_significance=.6, specificity=.6, future_reuse=.6, support_value=.6,
        ),
    ) if text != "broken" else (_ for _ in ()).throw(ValueError("invalid JSON")))
    monkeypatch.setattr(chat_route, "chat_with_fallback", lambda request, history=None, evidence=None: ChatResponse(reply="收到", strategy="listen", mock=False))
    assert client.post("/api/v1/chat", json={"device_id": "daily", "message": "今天试了一次"}).status_code == 200
    assert client.get("/api/v1/memories?device_id=daily").json()["memories"] == []
    summaries = client.get("/api/v1/events/daily-summaries?device_id=daily").json()["summaries"]
    assert len(summaries) == 1 and summaries[0]["status"] == "draft"
    assert client.post("/api/v1/chat", json={"device_id": "daily", "message": "broken"}).status_code == 200
    assert len(client.get("/api/v1/events?device_id=daily").json()["events"]) == 1


def test_multiple_memory_points_merge_and_explicit_correction(client: TestClient, monkeypatch) -> None:
    chat_route = import_module("app.api.routes.chat")
    event_service = import_module("app.services.event_service")
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="test")
    monkeypatch.setattr(event_service, "get_settings", lambda: settings)
    monkeypatch.setattr(chat_route, "chat_with_fallback", lambda request, history=None, evidence=None: ChatResponse(
        reply="收到", strategy="listen", mock=False,
    ))

    def fake_extract(text, existing, context=None, current_message_id=None):
        if "小李" in text:
            return TurnExtraction(memory_points=[
                memory_point(
                    current_message_id,
                    content="用户希望被称为小李",
                    kind="identity",
                    evidence_quote="我叫小李",
                    source_message_ids=[current_message_id],
                    temporal_scope="stable",
                    fact_status="asserted",
                    canonical_key="identity:preferred_name",
                ),
                memory_point(
                    current_message_id,
                    content="用户的目标院校是北大",
                    kind="goal",
                    evidence_quote="目标院校是北大",
                    source_message_ids=[current_message_id],
                    temporal_scope="ongoing",
                    fact_status="asserted",
                    canonical_key="goal:target_school",
                ),
            ])
        if "还是北大" in text:
            return TurnExtraction(memory_points=[memory_point(
                current_message_id,
                content="用户的目标院校是北大",
                kind="goal",
                evidence_quote="目标院校还是北大",
                source_message_ids=[current_message_id],
                temporal_scope="ongoing",
                fact_status="asserted",
                canonical_key="goal:target_school",
            )])
        if "不是北大" in text:
            return TurnExtraction(memory_points=[memory_point(
                current_message_id,
                content="用户的目标院校是北师大",
                kind="goal",
                evidence_quote="不是北大，是北师大",
                source_message_ids=[current_message_id],
                temporal_scope="ongoing",
                fact_status="asserted",
                canonical_key="goal:target_school",
                explicit_correction=True,
            )])
        return TurnExtraction()

    monkeypatch.setattr(event_service, "extract_turn", fake_extract)
    client.post("/api/v1/chat", json={"device_id": "atomic", "message": "我叫小李，目标院校是北大"})
    client.post("/api/v1/chat", json={"device_id": "atomic", "message": "我的目标院校还是北大"})
    rows = client.get("/api/v1/memories?device_id=atomic").json()["memories"]
    assert len(rows) == 2
    assert next(item for item in rows if item["canonical_key"] == "goal:target_school")["occurrence_count"] == 2

    client.post("/api/v1/chat", json={"device_id": "atomic", "message": "不是北大，是北师大"})
    rows = client.get("/api/v1/memories?device_id=atomic").json()["memories"]
    goals = [item for item in rows if item["canonical_key"] == "goal:target_school"]
    assert {item["status"] for item in goals} == {"active", "superseded"}
    assert next(item for item in goals if item["status"] == "active")["content"] == "用户的目标院校是北师大"


def test_forget_previous_removes_memory_and_skips_recall(client: TestClient, monkeypatch) -> None:
    chat_route = import_module("app.api.routes.chat")
    event_service = import_module("app.services.event_service")
    monkeypatch.setattr(event_service, "get_settings", lambda: Settings(
        _env_file=None, enable_live_ai=True, dashscope_api_key="test",
    ))
    observed = []

    def fake_chat(request, history=None, evidence=None):
        observed.append(evidence or [])
        return ChatResponse(reply="收到", strategy="listen", mock=False, evidence=evidence or [])

    monkeypatch.setattr(chat_route, "chat_with_fallback", fake_chat)
    monkeypatch.setattr(event_service, "extract_turn", lambda text, existing, context=None, current_message_id=None: TurnExtraction(
        memory_points=[memory_point(
            current_message_id,
            content="用户准备报考北大",
            kind="goal",
            evidence_quote=text,
            source_message_ids=[current_message_id],
            temporal_scope="ongoing",
            fact_status="asserted",
            canonical_key="goal:target_school",
        )],
    ))
    client.post("/api/v1/chat", json={"device_id": "forget", "message": "我准备报考北大"})
    assert len(client.get("/api/v1/memories?device_id=forget").json()["memories"]) == 1
    client.post("/api/v1/chat", json={"device_id": "forget", "message": "忘掉刚才那件事"})
    assert observed[-1] == []
    assert client.get("/api/v1/memories?device_id=forget").json()["memories"] == []


def test_p0_sqlite_migration_keeps_rows(tmp_path) -> None:
    url = f"sqlite:///{tmp_path / 'old.db'}"
    old = create_engine(url)
    with old.begin() as db:
        db.execute(text("CREATE TABLE conversations (id INTEGER PRIMARY KEY, device_id VARCHAR(128), created_at DATETIME, updated_at DATETIME)"))
        db.execute(text("CREATE TABLE messages (id INTEGER PRIMARY KEY, conversation_id INTEGER, role VARCHAR(16), modality VARCHAR(16), content TEXT, media_ref VARCHAR(160), is_mock BOOLEAN, created_at DATETIME)"))
        db.execute(text("CREATE TABLE growth_events (id INTEGER PRIMARY KEY, conversation_id INTEGER, fact TEXT, source_message_ids JSON, created_at DATETIME)"))
        db.execute(text("CREATE TABLE memories (id INTEGER PRIMARY KEY, conversation_id INTEGER, content TEXT, source_message_ids JSON, created_at DATETIME)"))
        db.execute(text("INSERT INTO conversations VALUES (1, 'old-phone', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
        db.execute(text("INSERT INTO messages VALUES (1, 1, 'user', 'text', '以前的消息', NULL, 0, CURRENT_TIMESTAMP)"))
    upgraded = engine_for_url(url)
    with Session(upgraded) as db:
        assert db.get(Message, 1).content == "以前的消息"
        assert db.get(Message, 1).used_memory_ids is None
        assert db.scalars(select(Memory)).all() == []
        columns = {item["name"] for item in inspect(upgraded).get_columns("memories")}
        assert {"kind", "confidence", "canonical_key", "source_excerpt", "occurrence_count"}.issubset(columns)


def test_three_day_old_memory_has_source_and_is_recalled(tmp_path) -> None:
    engine = engine_for_url(f"sqlite:///{tmp_path / 'older.db'}")
    old_time = datetime.now(UTC) - timedelta(days=3)
    with Session(engine) as db:
        conversation = Conversation(device_id="older")
        db.add(conversation)
        db.flush()
        source = Message(conversation_id=conversation.id, role="user", modality="text", content="我主动向老师提问", created_at=old_time)
        db.add(source)
        db.flush()
        db.add(Memory(conversation_id=conversation.id, content="主动向老师提问", source_message_ids=[source.id], status="active", value_score=.8, created_at=old_time))
        db.commit()
        evidence = recall(db, conversation.id, "老师提问时我还是紧张")
        assert len(evidence) == 1
        assert evidence[0].source_id == source.id
        assert evidence[0].source_date == local_day(old_time)
