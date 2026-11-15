from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.db.models import DailyStressSnapshot, NotificationOutbox, SupportPerson
from app.services.support_service import choose_person
from app.schemas.support import SupportSuggestionRequest
from app.services.wellbeing_service import SCORE_VERSION
from test_wellbeing import session


@pytest.mark.parametrize("original,expected", [
    ("我今晚想结束自己的生命", 90),
    ("我今晚打算结束自己的生命", 90),
    ("我有轻生的念头", 90),
    ("我没有轻生的念头", 50),
    ("以前我想过轻生，现在安全了", 50),
    ("小说台词是我今晚想结束自己的生命", 50),
    ("我朋友说想死", 50),
])
def test_pressure_reflects_current_own_expression_not_keyword(client, original, expected):
    client.post("/api/v1/wellbeing/check-in", json={"device_id": "pressure-fix", "mood": 3, "pressure": 50})
    result = client.post("/api/v1/chat", json={"device_id": "pressure-fix", "message": original})
    assert result.status_code == 200, result.text
    today = client.get("/api/v1/wellbeing/today?device_id=pressure-fix").json()["today"]
    assert today["score"] == expected and today["base_score"] == 50
    assert today["ai_score"] is None  # do not label the display rule as a model estimate
    assert today["version"] == SCORE_VERSION
    assert client.get("/api/v1/wellbeing/history?device_id=pressure-fix").json()["points"][-1]["score"] == expected


def test_daily_expression_survives_chitchat_but_source_deletion_recalculates(client):
    device = "daily-floor"
    client.post("/api/v1/wellbeing/check-in", json={"device_id": device, "mood": 3, "pressure": 50})
    risk = client.post("/api/v1/chat", json={"device_id": device, "message": "我今晚想结束自己的生命"}).json()
    assert not risk["wellbeing"]["notify_candidate"]  # thoughts alone do not send
    client.post("/api/v1/chat", json={"device_id": device, "message": "你好"})
    client.patch("/api/v1/wellbeing/preferences", json={"device_id": device, "self_weight": 90})
    assert client.get(f"/api/v1/wellbeing/today?device_id={device}").json()["today"]["score"] == 90
    client.delete(f"/api/v1/conversations/{device}/messages/{risk['user_message_id']}")
    today = client.get(f"/api/v1/wellbeing/today?device_id={device}").json()["today"]
    assert today["score"] == 50 and today["attention_sources"] == []


@pytest.mark.parametrize("first", ["today", "history"])
def test_old_today_snapshot_is_upgraded_without_resending(first, client):
    client.post("/api/v1/chat", json={"device_id": "cache-fix", "message": "我今晚打算结束自己的生命"})
    with session() as db:
        row = db.scalar(select(DailyStressSnapshot))
        row.value = {**row.value, "version": "wellbeing-v1.1", "score": 45}
        db.commit()
    payload = client.get(f"/api/v1/wellbeing/{first}?device_id=cache-fix").json()
    value = payload["today"] if first == "today" else payload["points"][-1]
    assert value["score"] == 90 and value["version"] == SCORE_VERSION
    assert client.get("/api/v1/wellbeing/notifications?device_id=cache-fix").json()["items"] == []


def people():
    return [SupportPerson(id=1, name="吴老师", relationship="老师", kind="teacher", scenarios=["专业问题"]),
            SupportPerson(id=2, name="母亲", relationship="妈妈", kind="family", scenarios=["情绪低落"])]


@pytest.mark.parametrize("situation,want", [
    ("我有轻生的念头", 2), ("我有轻声念头", 2), ("我今晚想结束自己的生命", 2),
    ("专业课考砸了，我很难过想有人陪陪我", 2), ("这道专业课的概念不理解", 1),
    ("最近很低落，但我想找吴老师聊聊", 1),
])
def test_saved_contact_purpose_before_order_or_generic_preference(situation, want):
    request = SupportSuggestionRequest(situation=situation, preferred_supporters=["teacher"])
    assert choose_person(request, people()).id == want


def test_no_academic_only_fallback_and_rejected_person_stays_excluded():
    assert choose_person(SupportSuggestionRequest(situation="我有轻生的念头"), people()[:1]) is None
    assert choose_person(SupportSuggestionRequest(situation="我很难过，但不想找母亲"), people()) is None
    custom = [SupportPerson(id=3, name="刘老师", relationship="老师", kind="teacher", scenarios=["倾诉和心理陪伴"])]
    assert choose_person(SupportSuggestionRequest(situation="我有轻生的念头"), custom).id == 3


def test_recommendation_endpoint_uses_persisted_assessment_and_correct_recipient(client):
    device = "purpose-route"
    for person in people():
        client.post("/api/v1/support-people", json={"device_id": device, "name": person.name,
            "relationship": person.relationship, "kind": person.kind, "scenarios": person.scenarios})
    chat = client.post("/api/v1/chat", json={"device_id": device, "message": "我有轻生的念头"}).json()
    result = client.post("/api/v1/support/suggest", json={"device_id": device,
        "situation": "专业问题", "source_message_id": chat["user_message_id"], "preferred_supporters": ["teacher"]}).json()
    assert result["supporter_name"] == "母亲"
    assert all("母亲" in item for item in result["message_options"])
