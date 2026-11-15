from contextlib import contextmanager
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select, text

from app.core.config import Settings
from app.db.models import DailyStressSnapshot, NotificationOutbox, TrustedContact, WellbeingSignal
from app.db.session import get_db
from app.main import app
from app.services import wellbeing_assessment as screening
from app.services.notification_service import dispatch_pending
from app.services.wellbeing_service import CONSENT_VERSION, day_of, ingest_source, refresh_day


@contextmanager
def session():
    yield from app.dependency_overrides[get_db]()


@pytest.mark.parametrize("original,action", [
    ("我想结束自己的生命。", "urgent_support"),
    ("我不想 huó 了", "urgent_support"),
    ("我有轻生的念头", "urgent_support"),
    ("我想伤害自己", "urgent_support"),
    ("我不\u200b想活了", "urgent_support"),
    ("我今晚打算结束自己的生命", "urgent_support"),
    ("我现在就决定伤害别人", "urgent_support"),
    ("我想紫砂", "clarify"),
    ("好想重开", "clarify"),
    ("撑不下去了", "clarify"),
    ("我朋友说想死", "clarify"),
    ("我没有伤害自己的想法，今天状态很好", "routine"),
    ("我不想再伤害自己", "routine"),
    ("我没有想过自杀", "routine"),
    ("我今晚不打算自杀", "routine"),
    ("游戏里我想杀了那个BOSS", "routine"),
    ("我在写预防自杀的课程报告", "routine"),
    ("小说台词是我想死", "routine"),
    ("以前我想过轻生，现在安全了", "routine"),
    ("我想死你了", "routine"),
    ("请翻译这句台词：我不想活了", "routine"),
    ("今天任务有点多", "routine"),
    ("我不会伤害他", "routine"),
    ("我参加自杀预防培训", "clarify"),
    ("自残是什么", "clarify"),
    ("我没有轻生想法。但是我现在就决定伤害自己。", "urgent_support"),
])
def test_context_rules(original, action):
    assert screening.support_action(screening.rule_assessment(original)) == action


def test_semantic_output_must_match_original(monkeypatch):
    from types import SimpleNamespace
    class FakeClient:
        def __init__(self, **kwargs):
            self.chat = SimpleNamespace(completions=self)
        def create(self, **kwargs):
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content='{"category":"self_harm","evidence":["不存在的原文"]}'))])
        def close(self):
            pass
    monkeypatch.setattr("openai.OpenAI", FakeClient)
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="fake")
    value = screening.assess("今天忙完了", settings=settings)
    assert value["action"] == "assessment_unavailable"
    assert not value["notify_candidate"]
    assert value["pressure"] is None


def test_model_cannot_suppress_explicit_support_or_send_alone(monkeypatch):
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="fake")
    monkeypatch.setattr(screening, "_model_assessment", lambda *args: screening.Assessment())
    value = screening.assess("我今晚打算结束自己的生命", settings=settings)
    assert value["action"] == "urgent_support"
    monkeypatch.setattr(screening, "_model_assessment", lambda *args: screening.Assessment(
        category="self_harm", subject="self", time_scope="current", expression="assertion",
        intent="present", immediate=True, evidence=["我想重开"]))
    monkeypatch.setattr(screening, "_confirm_intent", lambda *args: screening.IntentConfirmation())
    assert not screening.assess("我想重开", settings=settings)["notify_candidate"]


def test_unavailable_is_not_safe_and_thoughts_do_not_auto_notify():
    settings = Settings(_env_file=None, enable_live_ai=False, dashscope_api_key="")
    assert screening.assess("你好", settings=settings)["action"] == "assessment_unavailable"
    assert not screening.assess("我今晚有轻生的念头", settings=settings)["notify_candidate"]


def test_distress_keeps_supportive_dialogue_without_harm_interrogation(monkeypatch):
    from app.schemas import ChatRequest, ChatResponse
    from app.services import chat_service

    original = "这几天考试压力很大，连续两晚睡不好，学习也很难集中注意。"
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="fake")
    monkeypatch.setattr(screening, "_model_assessment", lambda *args: screening.Assessment(
        category="severe_distress", subject="self", time_scope="recent", expression="assertion",
        intent="absent", evidence=[original], pressure=75, pressure_evidence=original))
    monkeypatch.setattr(chat_service, "_chat_with_fallback", lambda *args: ChatResponse(
        reply="最近考试和睡眠都让你很吃力，我们先聊聊最困扰你的部分。", strategy="listen", mock=False, model="test"))
    result = chat_service.chat_with_fallback(ChatRequest(device_id="distress-test", message=original), settings)
    assert not result.safety_triggered
    assert result.strategy == "listen"
    assert result.wellbeing["category"] == "severe_distress"
    assert result.wellbeing["pressure"] == 75
    assert not result.wellbeing["notify_candidate"]
    assert result.wellbeing["action"] == "routine"


def test_daily_check_in_history_weights_and_deletion(client):
    today = client.get("/api/v1/wellbeing/today?device_id=day").json()
    assert today["today"]["score"] is None
    for mood, pressure in ((5, 75), (4, 50)):
        r = client.post("/api/v1/wellbeing/check-in", json={"device_id": "day", "mood": mood, "pressure": pressure})
        assert r.status_code == 200
    assert r.json()["score"] == 50  # happy mood is not converted into low pressure
    with session() as db:
        from app.db.repository import get_conversation
        owner = get_conversation(db, "day")
        assessment = {**screening.Assessment(pressure=100, pressure_evidence="很难承受").model_dump(),
                      "action": "routine", "model": "test", "notify_candidate": False}
        ingest_source(db, owner.id, "record", 900, "很难承受", datetime.now(UTC), assessment)
        ingest_source(db, owner.id, "record", 901, "很难承受", datetime.now(UTC), assessment)
        db.commit()
    current = client.get("/api/v1/wellbeing/today?device_id=day").json()["today"]
    assert current["score"] == 65 and current["source_count"] == 1 and current["disagreement"]
    for days in (7, 30, 365):
        response = client.get(f"/api/v1/wellbeing/history?device_id=day&days={days}")
        assert response.status_code == 200, response.text
        series = response.json()
        assert len(series["points"]) == days and series["coverage"] == 1
        assert all(p["score"] is None for p in series["points"][:-1])
    assert client.post("/api/v1/wellbeing/check-in", json={"device_id": "day", "mood": 3, "pressure": 999}).status_code == 422
    assert client.get("/api/v1/wellbeing/history?device_id=day&days=999").status_code == 422
    assert client.patch("/api/v1/wellbeing/preferences", json={"device_id": "day", "self_weight": 90}).status_code == 200
    assert client.get("/api/v1/wellbeing/today?device_id=day").json()["today"]["score"] == 55
    exported = client.get("/api/v1/data/export?device_id=day").json()["data"]
    assert len(exported["daily_check_ins"]) == 1
    assert client.delete("/api/v1/data?device_id=day").status_code == 204
    assert not client.get("/api/v1/data/export?device_id=day").json()["data"]["daily_stress"]


@pytest.fixture
def mock_mode(client, monkeypatch):
    settings = Settings(_env_file=None, app_env="development", notification_mode="mock", enable_live_ai=False, dashscope_api_key="")
    monkeypatch.setattr("app.services.notification_service.get_settings", lambda: settings)
    return settings


def bind(client, device="owner"):
    person = client.post("/api/v1/support-people", json={
        "device_id": device, "name": "测试联系人", "relationship": "家人", "kind": "family", "scenarios": [],
    }).json()
    response = client.post("/api/v1/wellbeing/contacts", json={
        "device_id": device, "support_person_id": person["id"], "phone": "13800000000", "trusted_confirmed": True,
    })
    assert response.status_code == 200, response.text
    challenge = response.json()
    verified = client.post(f"/api/v1/wellbeing/contacts/{challenge['id']}/verify",
                           json={"device_id": device, "code": challenge["simulation_code"]})
    assert verified.status_code == 200, verified.text
    return challenge["id"], person["id"]


def enable(client, device="owner"):
    return client.patch("/api/v1/wellbeing/preferences", json={
        "device_id": device, "auto_notify": True, "adult_confirmed": True, "consent_version": CONSENT_VERSION,
    })


def danger(client, device="owner"):
    return client.post("/api/v1/chat", json={"device_id": device, "message": "我今晚打算结束自己的生命"})


def test_automatic_mock_consent_cooldown_and_privacy(client, mock_mode):
    assert enable(client).status_code == 409
    contact_id, _ = bind(client)
    assert not client.get("/api/v1/wellbeing/contacts?device_id=owner").json()["contacts"][0]["phone_masked"].endswith("00000000")
    assert danger(client).status_code == 200
    assert client.get("/api/v1/wellbeing/notifications?device_id=owner").json()["items"] == []
    assert enable(client).status_code == 200
    response = danger(client)
    assert response.json()["safety_triggered"] and response.json()["wellbeing"]["notify_candidate"]
    notices = client.get("/api/v1/wellbeing/notifications?device_id=owner").json()
    assert len(notices["items"]) == 1
    assert notices["items"][0]["status"] == "simulated"
    assert "结束" not in notices["template"]
    danger(client)
    assert len(client.get("/api/v1/wellbeing/notifications?device_id=owner").json()["items"]) == 1
    assert client.get("/api/v1/wellbeing/contacts?device_id=other").json()["contacts"] == []
    assert client.delete(f"/api/v1/wellbeing/contacts/{contact_id}?device_id=other").status_code == 404
    export = client.get("/api/v1/data/export?device_id=owner").json()["data"]
    assert "code_digest" not in export["trusted_contacts"][0]
    assert client.delete("/api/v1/data?device_id=owner").status_code == 204
    with session() as db:
        assert not db.execute(text("PRAGMA foreign_key_check")).all()


def test_worker_rechecks_consent_and_expiry(client, mock_mode, monkeypatch):
    bind(client)
    enable(client)
    monkeypatch.setattr("app.api.routes.chat.dispatch_for_url", lambda url: None)
    danger(client)
    client.patch("/api/v1/wellbeing/preferences", json={"device_id": "owner", "auto_notify": False})
    with session() as db:
        dispatch_pending(db)
        assert db.scalar(select(NotificationOutbox)).status == "cancelled"
    enable(client)
    danger(client)
    with session() as db:
        queued = db.scalar(select(NotificationOutbox).where(NotificationOutbox.status == "queued"))
        queued.expires_at = datetime.now(UTC) - timedelta(seconds=1)
        db.commit()
        dispatch_pending(db)
        assert queued.status == "expired"


def test_production_cannot_use_mock_and_contact_removal_cascades(client, mock_mode):
    cid, pid = bind(client)
    enable(client)
    danger(client)
    assert client.delete(f"/api/v1/support-people/{pid}?device_id=owner").status_code == 204
    with session() as db:
        assert db.get(TrustedContact, cid) is None
        assert db.scalar(select(NotificationOutbox)) is None
    mock_mode.app_env = "production"
    assert enable(client).status_code == 409
    assert client.post("/api/v1/wellbeing/contacts", json={
        "device_id": "owner", "support_person_id": pid, "phone": "13800000000", "trusted_confirmed": True,
    }).status_code == 409


def test_history_upload_no_alert_and_photo_description_not_intent(client, mock_mode):
    bind(client)
    enable(client)
    for rid, age, user_text, description in (
        ("old", 86400, "我今晚打算结束自己的生命", ""),
        ("photo", 0, "", "我今晚打算结束自己的生命"),
    ):
        response = client.post("/api/v1/records/sync", json={"device_id": "owner", "records": [{
            "client_record_id": rid, "mode": "photo" if description else "text", "text": user_text,
            "photo_comment": "", "ai_description": description, "status": "saved",
            "created_at_ms": int((datetime.now(UTC) - timedelta(seconds=age)).timestamp() * 1000),
        }]})
        assert response.status_code == 200, response.text
    assert client.get("/api/v1/wellbeing/notifications?device_id=owner").json()["items"] == []
    client.delete("/api/v1/records/client/old?device_id=owner")
    with session() as db:
        assert len(db.scalars(select(WellbeingSignal)).all()) == 1


def test_self_check_not_diagnostic_and_owner_isolation(client):
    response = client.post("/api/v1/wellbeing/self-check", json={
        "device_id": "owner", "sleep": 3, "daily_impact": 2, "coping": 1, "immediate_danger": False,
    })
    assert response.json()["action"] == "seek_support"
    assert "不是标准化" in response.json()["notice"]
    assert client.get("/api/v1/wellbeing/today?device_id=other").json()["today"]["score"] is None


def test_history_recalculation_preserves_previous_weight(client):
    from app.db.models import DailyCheckIn
    from app.db.repository import get_or_create_conversation
    from app.services.wellbeing_service import preferences, remove_source
    with session() as db:
        conversation = get_or_create_conversation(db, "historic-weight")
        yesterday = datetime.now(UTC) - timedelta(days=1)
        day = day_of(yesterday)
        db.add(DailyCheckIn(conversation_id=conversation.id, day=day, mood=4, pressure=50))
        value = {**screening.Assessment(pressure=100, pressure_evidence="负担很大").model_dump(),
                 "action": "routine", "model": "test", "notify_candidate": False}
        ingest_source(db, conversation.id, "record", 50, "负担很大", yesterday, value)
        preferences(db, conversation.id).self_weight = 90
        refreshed = refresh_day(db, conversation.id, day)
        assert refreshed["self_weight"] == 70 and refreshed["score"] == 65
        remove_source(db, conversation.id, "record", 50)
        assert refresh_day(db, conversation.id, day)["score"] == 50
        db.commit()


def test_counseling_resource_requires_configured_https(client, monkeypatch):
    settings = Settings(_env_file=None, counseling_url="javascript:alert(1)")
    monkeypatch.setattr("app.api.routes.wellbeing.get_settings", lambda: settings)
    assert client.get("/api/v1/wellbeing/resources?device_id=owner").json()["counseling_url"] is None
    settings.counseling_url = "https://example.org/verified-appointment"
    assert client.get("/api/v1/wellbeing/resources?device_id=owner").json()["counseling_url"] == settings.counseling_url


def test_model_estimate_flows_through_chat_and_source_deletion(client, monkeypatch):
    from app.services.mock_service import mock_chat
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="fake")
    monkeypatch.setattr("app.services.chat_service.get_settings", lambda: settings)
    monkeypatch.setattr("app.services.chat_service._live_chat", lambda request, *args: mock_chat(request))
    monkeypatch.setattr(screening, "_model_assessment", lambda original, *args: screening.Assessment(
        pressure=75, pressure_evidence=original))
    client.post("/api/v1/wellbeing/check-in", json={"device_id": "estimated", "mood": 4, "pressure": 25})
    response = client.post("/api/v1/chat", json={"device_id": "estimated", "message": "最近考试压力大，影响了睡眠"})
    assert response.status_code == 200
    assert response.json()["wellbeing"]["semantic_available"]
    before = client.get("/api/v1/wellbeing/today?device_id=estimated").json()["today"]
    assert before["score"] == 40 and before["ai_score"] == 75
    message_id = response.json()["user_message_id"]
    assert client.delete(f"/api/v1/conversations/estimated/messages/{message_id}").status_code == 204
    after = client.get("/api/v1/wellbeing/today?device_id=estimated").json()["today"]
    assert after["score"] == 25 and after["ai_score"] is None


def test_record_deleted_during_model_call_is_not_resurrected(client, monkeypatch):
    from app.services.wellbeing_service import process_record_assessment
    from app.db.repository import get_conversation
    response = client.post("/api/v1/records/sync", json={"device_id": "record-race", "records": [{
        "client_record_id": "race", "mode": "text", "text": "最近睡得不好", "photo_comment": "",
        "status": "saved", "created_at_ms": int(datetime.now(UTC).timestamp() * 1000),
    }]})
    record_id = response.json()["records"][0]["id"]
    with session() as db:
        conversation_id = get_conversation(db, "record-race").id
        database_url = str(db.get_bind().url)
    def delayed_assessment(original):
        assert client.delete("/api/v1/records/client/race?device_id=record-race").status_code == 204
        return {**screening.Assessment(pressure=75, pressure_evidence=original).model_dump(),
                "action": "routine", "model": "test", "notify_candidate": False}
    monkeypatch.setattr("app.services.wellbeing_service.assess", delayed_assessment)
    process_record_assessment(database_url, conversation_id, record_id)
    with session() as db:
        assert db.scalar(select(WellbeingSignal).where(WellbeingSignal.conversation_id == conversation_id)) is None
    assert client.get("/api/v1/wellbeing/today?device_id=record-race").json()["today"]["score"] is None
