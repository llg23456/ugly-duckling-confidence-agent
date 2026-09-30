from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Barrier, Lock

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, text

from app.services import review_service
from app.db.session import engine_for_url

LOCAL = timezone(timedelta(hours=8))


def record(client: TestClient, device: str, record_id: str, text_value: str, day_offset: int = 0,
           mode: str = "text", status: str = "saved", photo_comment: str = "") -> dict:
    day = datetime.now(LOCAL).date() + timedelta(days=day_offset)
    timestamp = datetime(day.year, day.month, day.day, 12, tzinfo=LOCAL)
    response = client.post("/api/v1/records/sync", json={"device_id": device, "records": [{
        "client_record_id": record_id, "mode": mode, "text": text_value,
        "photo_comment": photo_comment, "status": status,
        "created_at_ms": int(timestamp.timestamp() * 1000),
    }]})
    assert response.status_code == 200, response.text
    return response.json()["records"][0]


def test_record_sync_is_idempotent_and_source_is_owned(client: TestClient) -> None:
    first = record(client, "owner", "local-1", "我练了十分钟开场")
    second = record(client, "owner", "local-1", "我练了十分钟开场")
    assert first["id"] == second["id"] and first["event_id"] == second["event_id"]
    draft = record(client, "owner", "draft-1", "尚未写完", status="draft")
    assert draft["event_id"] is None
    photo = record(client, "owner", "photo-1", "", mode="photo", photo_comment="今天拍下了练习现场")
    assert photo["event_id"] is not None
    assert client.get(f"/api/v1/records/{photo['id']}?device_id=other").status_code == 404
    assert client.get(f"/api/v1/records/{photo['id']}?device_id=owner").json()["photo_comment"] == "今天拍下了练习现场"
    events = client.get("/api/v1/events?device_id=owner").json()["events"]
    assert len(events) == 2
    assert any(item["source_record_id"] == photo["id"] for item in events)


def test_record_near_local_midnight_appears_in_today_review(client: TestClient) -> None:
    today = datetime.now(LOCAL).date()
    early = datetime(today.year, today.month, today.day, 0, 30, tzinfo=LOCAL)
    response = client.post("/api/v1/records/sync", json={"device_id": "midnight", "records": [{
        "client_record_id": "early", "mode": "text", "text": "凌晨记下的一句话",
        "status": "saved", "created_at_ms": int(early.timestamp() * 1000),
    }]})
    assert response.status_code == 200
    review = client.post("/api/v1/reviews/generate", json={"device_id": "midnight", "period": "day"}).json()
    assert review["moments"][0]["date"] == today.isoformat()


def test_previous_day_review_is_saved_with_source_and_retries(client: TestClient) -> None:
    item = record(client, "review-owner", "yesterday", "昨天练习时卡住了，后来重新开始", -1, mode="voice")
    yesterday = (datetime.now(LOCAL).date() - timedelta(days=1)).isoformat()
    request = {"device_id": "review-owner", "period": "day", "start_date": yesterday, "end_date": yesterday}
    first = client.post("/api/v1/reviews/generate", json=request)
    assert first.status_code == 200, first.text
    data = first.json()
    assert data["id"] is not None and data["mock"] is False
    assert data["source_event_ids"] == [item["event_id"]]
    assert data["moments"][0]["source_record_id"] == item["id"]
    assert "卡住" in data["pause_or_restart"]
    assert [section["key"] for section in data["sections"]] == ["happened", "difficulty", "attempt", "response"]
    assert client.post("/api/v1/reviews/generate", json=request).json()["id"] == data["id"]
    pending = client.post("/api/v1/reviews/generate-pending-daily", json={"device_id": "review-owner"}).json()["reviews"]
    assert len(pending) == 1 and pending[0]["id"] == data["id"]
    assert client.get(f"/api/v1/reviews/day?device_id=review-owner&start_date={yesterday}&end_date={yesterday}").json()["id"] == data["id"]
    assert client.get(f"/api/v1/reviews/day?device_id=other&start_date={yesterday}&end_date={yesterday}").json()["moments"] == []
    record(client, "review-owner", "yesterday", "昨天练习时卡住了，后来向老师请教", -1, mode="voice")
    updated = client.post("/api/v1/reviews/generate", json=request).json()
    assert updated["id"] == data["id"] and "请教" in updated["story"]
    assert updated["source_event_ids"] == data["source_event_ids"]


def test_weekly_review_keeps_effort_and_actual_help(client: TestClient) -> None:
    record(client, "weekly", "first", "我今天自己做了练习")
    person = client.post("/api/v1/support-people", json={
        "device_id": "weekly", "name": "小林", "relationship": "同学", "kind": "classmate",
    }).json()
    suggestion = client.post("/api/v1/support/suggest", json={
        "device_id": "weekly", "situation": "我想练习", "preferred_supporters": ["classmate"],
    }).json()
    assert suggestion["supporter_id"] == person["id"]
    feedback = client.post("/api/v1/support/feedback", json={
        "device_id": "weekly", "suggestion_id": suggestion["suggestion_id"],
        "outcome": "helped", "own_effort": "我先练了开场", "support_received": "小林听我练习并给了建议",
    }).json()
    review = client.post("/api/v1/reviews/generate", json={"device_id": "weekly", "period": "week"}).json()
    assert "我先练了开场" in review["own_effort"]
    assert "小林听我练习" in review["support_received"]
    assert len(review["source_event_ids"]) == 2
    assert any(item["source_feedback_id"] == feedback["feedback"]["id"] for item in review["moments"])
    assert review["next_step"]
    assert [section["key"] for section in review["sections"]] == ["completed", "difficulty", "process", "change", "unfinished"]
    assert review["affirmation"]


def test_monthly_nodes_include_setback_and_empty_does_not_fabricate(client: TestClient) -> None:
    for index in range(8):
        record(client, "monthly", f"entry-{index}", "我练习时卡住了" if index == 3 else f"第{index}次练习")
    response = client.post("/api/v1/reviews/generate", json={"device_id": "monthly", "period": "month"}).json()
    assert len(response["moments"]) == 6
    assert any("卡住" in item["title"] for item in response["moments"])
    assert len(response["source_event_ids"]) == 8
    empty = client.post("/api/v1/reviews/generate", json={"device_id": "empty", "period": "month"}).json()
    assert empty["id"] is None and empty["moments"] == [] and empty["story"] == ""
    assert client.post("/api/v1/reviews/generate-pending-daily", json={"device_id": "empty"}).json()["reviews"] == []


def test_overview_returns_one_complete_week_or_month_payload(client: TestClient) -> None:
    record(client, "overview", "entry-1", "昨天完成了一次练习", day_offset=-1)
    today = datetime.now(LOCAL).date()
    week_start = today - timedelta(days=6)
    weekly = client.post("/api/v1/reviews/overview", json={
        "device_id": "overview", "period": "week",
        "start_date": week_start.isoformat(), "end_date": today.isoformat(),
    })
    assert weekly.status_code == 200, weekly.text
    assert weekly.json()["review"]["period"] == "week"
    assert len(weekly.json()["daily_reviews"]) == 7

    month_start = today.replace(day=1)
    monthly = client.post("/api/v1/reviews/overview", json={
        "device_id": "overview", "period": "month",
        "start_date": month_start.isoformat(), "end_date": today.isoformat(),
    })
    assert monthly.status_code == 200, monthly.text
    assert monthly.json()["review"]["period"] == "month"
    assert monthly.json()["weekly_reviews"]


def test_concurrent_weekly_overview_reuses_the_same_cache(
    client: TestClient,
    monkeypatch,
) -> None:
    record(client, "concurrent-review", "entry-1", "今天完成了学习计划")
    original_saved_review = review_service.saved_review
    initial_reads = 0
    initial_reads_lock = Lock()
    both_requests_read_empty_cache = Barrier(2)

    def synchronized_saved_review(*args, **kwargs):
        nonlocal initial_reads
        stored = original_saved_review(*args, **kwargs)
        if stored is None:
            with initial_reads_lock:
                initial_reads += 1
                read_number = initial_reads
            if read_number <= 2:
                both_requests_read_empty_cache.wait(timeout=5)
        return stored

    monkeypatch.setattr(review_service, "saved_review", synchronized_saved_review)
    request = {"device_id": "concurrent-review", "period": "week"}
    with ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(lambda _: client.post(
            "/api/v1/reviews/overview",
            json=request,
        ), range(2)))

    assert [response.status_code for response in responses] == [200, 200]
    review_ids = {response.json()["review"]["id"] for response in responses}
    assert None not in review_ids
    assert len(review_ids) == 1


def test_review_range_validation_and_p2_database_upgrade(client: TestClient, tmp_path) -> None:
    today = datetime.now(LOCAL).date().isoformat()
    assert client.post("/api/v1/reviews/generate", json={
        "device_id": "bad", "period": "day", "start_date": today,
    }).status_code == 422
    url = f"sqlite:///{tmp_path / 'p2.db'}"
    old = create_engine(url)
    with old.begin() as db:
        db.execute(text("CREATE TABLE reviews (id INTEGER PRIMARY KEY, conversation_id INTEGER, period VARCHAR(16), content TEXT, source_event_ids JSON, created_at DATETIME)"))
        db.execute(text("CREATE TABLE growth_events (id INTEGER PRIMARY KEY, conversation_id INTEGER, fact TEXT, source_message_ids JSON, source_user_message_id INTEGER, source_feedback_id INTEGER, created_at DATETIME)"))
        db.execute(text("CREATE TABLE messages (id INTEGER PRIMARY KEY, conversation_id INTEGER, role VARCHAR(16), modality VARCHAR(16), content TEXT, created_at DATETIME)"))
        db.execute(text("CREATE TABLE memories (id INTEGER PRIMARY KEY, conversation_id INTEGER, content TEXT, source_message_ids JSON, created_at DATETIME)"))
        db.execute(text("CREATE TABLE support_people (id INTEGER PRIMARY KEY, conversation_id INTEGER, name VARCHAR(120), relationship VARCHAR(120), created_at DATETIME)"))
        db.execute(text("INSERT INTO reviews VALUES (1, 1, 'day', '{}', '[]', CURRENT_TIMESTAMP)"))
    upgraded = engine_for_url(url)
    assert "source_record_id" in {col["name"] for col in inspect(upgraded).get_columns("growth_events")}
    assert "range_start" in {col["name"] for col in inspect(upgraded).get_columns("reviews")}
    assert "records" in inspect(upgraded).get_table_names()
    with upgraded.connect() as db:
        assert db.execute(text("SELECT COUNT(*) FROM reviews")).scalar_one() == 1
