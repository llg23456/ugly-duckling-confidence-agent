from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import Session

from app.db.models import SupportPerson
from app.db.session import engine_for_url
from app.services.support_service import needs_support


def test_support_strategy_uses_request_and_repeated_blockage() -> None:
    assert needs_support("我想找人帮我练习")
    assert needs_support("答辩准备压力很大")
    assert not needs_support("这道题我卡住了")
    assert needs_support("我又卡住了", [{"role": "user", "content": "昨天就卡住了"}])
    assert not needs_support("我今天完成了", [{"role": "user", "content": "之前卡住了"}])
    assert needs_support("考研专业方向不清楚，可以找谁请教")
    assert not needs_support("今天继续准备考研，感觉状态挺好")


def test_postgraduate_support_selects_senior_and_teacher_by_situation(client: TestClient) -> None:
    for name, kind in (("小林师姐", "senior"), ("学院王老师", "teacher"), ("妈妈", "family")):
        response = client.post("/api/v1/support-people", json={
            "device_id": "postgrad", "name": name, "relationship": name, "kind": kind,
        })
        assert response.status_code == 201
    senior = client.post("/api/v1/support/suggest", json={"device_id": "postgrad", "situation": "想了解目标院校的备考经验"}).json()
    assert senior["supporter_type"] == "senior" and senior["supporter_name"] == "小林师姐"
    assert "什么时候方便" in senior["editable_message"] and senior["auto_send"] is False
    teacher = client.post("/api/v1/support/suggest", json={"device_id": "postgrad", "situation": "考研专业方向不清楚"}).json()
    assert teacher["supporter_type"] == "teacher"
    assert "自己的理解" in teacher["small_step"]
    unnamed = client.post("/api/v1/support/suggest", json={"situation": "想了解目标院校"}).json()
    assert unnamed["supporter_id"] is None and unnamed["supporter_type"] == "senior"


def test_support_people_crud_and_ownership(client: TestClient) -> None:
    assert client.get("/api/v1/support-people?device_id=owner").json()["people"] == []
    created = client.post("/api/v1/support-people", json={
        "device_id": "owner", "name": "小林", "relationship": "同班同学", "kind": "classmate",
        "scenarios": ["答辩", "作业"],
    })
    assert created.status_code == 201
    person_id = created.json()["id"]
    assert created.json()["scenarios"] == ["答辩", "作业"]
    assert client.get("/api/v1/support-people?device_id=other").json()["people"] == []
    assert client.patch(f"/api/v1/support-people/{person_id}", json={
        "device_id": "other", "name": "冒名", "relationship": "同学", "kind": "classmate",
    }).status_code == 404
    updated = client.patch(f"/api/v1/support-people/{person_id}", json={
        "device_id": "owner", "name": "小林", "relationship": "室友", "kind": "friend", "scenarios": ["练习"],
    })
    assert updated.status_code == 200 and updated.json()["relationship"] == "室友"
    assert client.delete(f"/api/v1/support-people/{person_id}?device_id=other").status_code == 404
    assert client.delete(f"/api/v1/support-people/{person_id}?device_id=owner").status_code == 204
    assert client.get("/api/v1/support-people?device_id=owner").json()["people"] == []


def test_suggestion_feedback_and_event_keep_both_sides(client: TestClient) -> None:
    person = client.post("/api/v1/support-people", json={
        "device_id": "support-device", "name": "小林", "relationship": "同学", "kind": "classmate", "scenarios": ["答辩"],
    }).json()
    other = client.post("/api/v1/support-people", json={
        "device_id": "support-device", "name": "王老师", "relationship": "导师", "kind": "teacher", "scenarios": ["论文"],
    }).json()
    proposal = client.post("/api/v1/support/suggest", json={
        "device_id": "support-device", "situation": "我准备答辩开场卡住了", "preferred_supporters": ["classmate"],
    })
    assert proposal.status_code == 200
    data = proposal.json()
    assert data["supporter_id"] == person["id"]
    assert data["supporter_name"] == "小林"
    assert "30 秒" in data["small_step"]
    assert data["lighter_option"]
    assert data["editable_message"] and data["auto_send"] is False
    assert data["mock"] is False and data["suggestion_id"] is not None
    wrong = client.post("/api/v1/support/feedback", json={
        "device_id": "other", "suggestion_id": data["suggestion_id"], "outcome": "helped",
    })
    assert wrong.status_code == 404
    saved = client.post("/api/v1/support/feedback", json={
        "device_id": "support-device", "suggestion_id": data["suggestion_id"],
        "outcome": "helped", "own_effort": "我先练了 30 秒开场", "support_received": "小林听我练习并指出一句不清楚",
    })
    assert saved.status_code == 200 and saved.json()["event_id"] is not None
    assert saved.json()["feedback"]["supporter_name"] == "小林"
    assert client.post("/api/v1/support/feedback", json={
        "device_id": "support-device", "suggestion_id": data["suggestion_id"], "outcome": "helped",
    }).status_code == 409
    event = client.get("/api/v1/events?device_id=support-device").json()["events"][0]
    assert event["own_effort"] == "我先练了 30 秒开场"
    assert event["support_received"] == "小林听我练习并指出一句不清楚"
    assert event["source_feedback_id"] == saved.json()["feedback"]["id"]
    review = client.get("/api/v1/reviews/month?device_id=support-device").json()
    assert review["mock"] is False
    assert "我先练了 30 秒开场" in review["own_effort"]
    assert "小林听我练习" in review["support_received"]
    assert review["moments"][0]["source_feedback_id"] == saved.json()["feedback"]["id"]
    assert client.get("/api/v1/reviews/month?device_id=other").json()["moments"] == []
    assert client.get("/api/v1/support/feedback?device_id=support-device").json()["feedback"][0]["outcome"] == "helped"
    assert client.delete(f"/api/v1/support-people/{person['id']}?device_id=support-device").status_code == 204
    assert client.get("/api/v1/support/feedback?device_id=support-device").json()["feedback"][0]["supporter_name"] == "小林"


def test_no_contact_feedback_does_not_fabricate_help(client: TestClient) -> None:
    suggestion = client.post("/api/v1/support/suggest", json={
        "device_id": "no-contact", "situation": "有点卡住",
    }).json()
    result = client.post("/api/v1/support/feedback", json={
        "device_id": "no-contact", "suggestion_id": suggestion["suggestion_id"],
        "outcome": "not_contacted", "support_received": "不应保存",
    }).json()
    assert result["event_id"] is None
    assert result["feedback"]["support_received"] is None
    assert client.get("/api/v1/events?device_id=no-contact").json()["events"] == []
    review = client.get("/api/v1/reviews/month?device_id=no-contact").json()
    assert review["mock"] is False and review["moments"] == []
    assert "还没有记录" in review["support_received"]


def test_p1_database_upgrade_adds_support_fields(tmp_path) -> None:
    url = f"sqlite:///{tmp_path / 'p1.db'}"
    old = create_engine(url)
    with old.begin() as db:
        db.execute(text("CREATE TABLE conversations (id INTEGER PRIMARY KEY, device_id VARCHAR(128), created_at DATETIME, updated_at DATETIME)"))
        db.execute(text("CREATE TABLE support_people (id INTEGER PRIMARY KEY, conversation_id INTEGER, name VARCHAR(120), relationship VARCHAR(120), created_at DATETIME)"))
        db.execute(text("INSERT INTO conversations VALUES (1, 'old-phone', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
        db.execute(text("INSERT INTO support_people VALUES (1, 1, '老朋友', '朋友', CURRENT_TIMESTAMP)"))
    upgraded = engine_for_url(url)
    with Session(upgraded) as db:
        assert db.get(SupportPerson, 1).name == "老朋友"
        assert db.get(SupportPerson, 1).scenarios is None
    assert "support_feedback" in inspect(upgraded).get_table_names()


def test_repeated_blockage_changes_chat_strategy(client: TestClient) -> None:
    first = client.post("/api/v1/chat", json={"device_id": "stuck", "message": "这个步骤我卡住了"})
    second = client.post("/api/v1/chat", json={"device_id": "stuck", "message": "我又卡住了"})
    assert first.json()["strategy"] == "listen"
    assert second.json()["strategy"] == "seek_support"
