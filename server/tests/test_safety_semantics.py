from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.core.config import Settings
from app.db.models import NotificationOutbox, UserRecord, WellbeingSignal
from app.db.repository import get_or_create_conversation
from app.services import wellbeing_assessment as screening
from app.services.wellbeing_service import ingest_source, process_record_assessment
from test_contact_notifications import contact
from test_device_sms import BASE, sms_mode
from test_wellbeing import session


def personal(text, immediate=False):
    return screening.Assessment(category="self_harm", subject="self", time_scope="current",
                                expression="assertion", intent="present", immediate=immediate, evidence=[text])


def semantic_mode(monkeypatch, original):
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="test-only")
    monkeypatch.setattr(screening, "get_settings", lambda: settings)
    monkeypatch.setattr("app.services.chat_service.get_settings", lambda: settings)
    monkeypatch.setattr(screening, "_model_assessment", lambda *a: personal(original))
    monkeypatch.setattr(screening, "_confirm_intent", lambda *a: screening.IntentConfirmation(
        current_personal_intent=True, category="self_harm", evidence=[original]))
    monkeypatch.setattr("app.services.chat_service.supportive_reply", lambda *a: ("我会认真听，眼下有人可以陪着你吗？", "test"))
    return settings


@pytest.mark.parametrize("original", [
    "我真的学不下去了，我已经不想活了考试太难了",
    "我已经不想活了", "这条命我不愿意再继续了", "最近一直有轻生的念头",
    "不是游戏，我是真的想紫砂", "是的，是我自己的想法，不是说着玩的",
])
def test_verified_personal_ideation_does_not_require_plan_or_keyword(monkeypatch, original):
    settings = semantic_mode(monkeypatch, original)
    result = screening.assess(original, settings=settings)
    assert result["notify_candidate"] and result["notification_level"] == "concern"
    assert result["notification_basis"] == "semantic_confirmation"
    assert not result["immediate"]


@pytest.mark.parametrize("original", [
    "今天真的要累死了，玛德，学的快不想活了",
    "加班加到要累死了，只想赶紧回家睡觉", "这破作业学得快不想活了",
])
def test_stress_hyperbole_does_not_cross_safety_boundary(monkeypatch, original):
    settings = semantic_mode(monkeypatch, original)
    monkeypatch.setattr(screening, "_model_assessment", lambda *a: screening.Assessment(
        category="severe_distress", subject="self", time_scope="current", expression="assertion",
        intent="absent", evidence=[original], pressure=75, pressure_evidence=original))
    monkeypatch.setattr(screening, "_confirm_intent", lambda *a: pytest.fail("distress must not be escalated"))
    result = screening.assess(original, settings=settings)
    assert result["action"] == "routine" and result["pressure"] == 75
    assert not result["notify_candidate"] and result["intent"] == "absent"


def test_model_context_can_override_non_immediate_keyword_rule(monkeypatch):
    text = "我不想活了是刚刚随口的抱怨，我没有伤害自己的念头"
    settings = semantic_mode(monkeypatch, text)
    monkeypatch.setattr(screening, "_model_assessment", lambda *a: screening.Assessment(
        category="severe_distress", subject="self", time_scope="current", expression="assertion",
        intent="absent", evidence=[text]))
    value = screening.assess(text, settings=settings)
    assert value["action"] == "routine" and not value["notify_candidate"]


def test_temporal_words_with_joke_context_do_not_bypass_semantics(monkeypatch):
    text = "我今晚打算自杀只是夸张抱怨，不是认真的"
    settings = semantic_mode(monkeypatch, text)
    monkeypatch.setattr(screening, "_model_assessment", lambda *a: screening.Assessment(
        category="none", subject="self", time_scope="current", expression="joke", intent="absent"))
    value = screening.assess(text, settings=settings)
    assert value["semantic_available"] and not value["notify_candidate"]


@pytest.mark.parametrize("confirmation", [
    screening.IntentConfirmation(),
    screening.IntentConfirmation(current_personal_intent=True, category="self_harm", evidence=["别人的旧话"]),
    screening.IntentConfirmation(current_personal_intent=True, category="harm_to_others", evidence=["好想重开"]),
])
def test_disagreement_or_ungrounded_second_read_requires_clarification(monkeypatch, confirmation):
    text = "好想重开"
    settings = semantic_mode(monkeypatch, text)
    monkeypatch.setattr(screening, "_confirm_intent", lambda *a: confirmation)
    value = screening.assess(text, settings=settings)
    assert not value["notify_candidate"] and value["action"] == "clarify"


def test_confirmation_timeout_never_notifies(monkeypatch):
    settings = semantic_mode(monkeypatch, "我不愿继续这条命了")
    def timeout(*args):
        raise TimeoutError()
    monkeypatch.setattr(screening, "_confirm_intent", timeout)
    value = screening.assess("我不愿继续这条命了", settings=settings)
    assert not value["notify_candidate"] and value["notification_basis"] == "confirmation_unavailable"


@pytest.mark.parametrize("text", [
    "今天真的要累死了，玛德，学的快不想活了", "我想知道自杀是什么意思",
    "如果我今晚打算自杀你会怎么判断", "我朋友已经不想活了", "我不想死",
    "我没有轻生念头", "游戏失败想重开", "去年我不想活，现在已经安全了",
])
def test_offline_ambiguity_denials_and_other_people_never_notify(text):
    value = screening.assess(text, use_model=False)
    assert not value["notify_candidate"]
    if "学的快" in text:
        assert value["category"] == "severe_distress" and value["action"] != "urgent_support"


def test_concern_sms_uses_mother_and_does_not_invent_a_plan(client, sms_mode, monkeypatch):
    mom, _, auth = contact(client)
    _, _, teacher = contact(client, name="吴老师", phone="13900000000", scenes=["专业问题"], kind="teacher")
    text = "我真的学不下去了，我已经不想活了考试太难了"
    semantic_mode(monkeypatch, text)
    response = client.post("/api/v1/chat", json={"device_id": "context-sms", "message": text})
    assert response.status_code == 200, response.text
    assert response.json()["wellbeing"]["notify_candidate"]
    assert client.post(BASE + "/claim", json=teacher).json()["job"] is None
    job = client.post(BASE + "/claim", json=auth).json()["job"]
    assert job["contact_id"] == mom["contact_id"] and text in job["text"]
    assert "相关想法" in job["text"] and "近期行动" not in job["text"]
    assert client.post(f"{BASE}/{job['id']}/authorize", json={**auth, "claim_token": job["claim_token"]}).status_code == 200
    assert client.get("/api/v1/wellbeing/today?device_id=context-sms").json()["today"]["score"] == 90
    # Repetition stays in the existing cooldown and does not create a second SMS.
    client.post("/api/v1/chat", json={"device_id": "context-sms", "message": text})
    assert len(client.get("/api/v1/wellbeing/notifications?device_id=context-sms").json()["items"]) == 1


def test_high_pressure_complaint_changes_pressure_without_red_line_or_sms(client, sms_mode, monkeypatch):
    contact(client)
    text = "今天真的要累死了，玛德，学的快不想活了"
    semantic_mode(monkeypatch, text)
    monkeypatch.setattr(screening, "_model_assessment", lambda *a: screening.Assessment(
        category="severe_distress", subject="self", time_scope="current", expression="assertion",
        intent="absent", evidence=[text], pressure=75, pressure_evidence=text))
    from app.services.mock_service import mock_chat
    monkeypatch.setattr("app.services.chat_service._chat_with_fallback", lambda request, *a: mock_chat(request))
    response = client.post("/api/v1/chat", json={"device_id": "context-sms", "message": text})
    assert response.status_code == 200 and not response.json()["safety_triggered"]
    today = client.get("/api/v1/wellbeing/today?device_id=context-sms").json()["today"]
    assert today["score"] == 75 and today["attention_floor"] is None
    assert client.get("/api/v1/wellbeing/notifications?device_id=context-sms").json()["items"] == []


@pytest.mark.parametrize("days_ago,expected_jobs", [(0, 1), (1, 0)])
def test_record_semantic_result_enqueues_once_only_for_fresh_sources(client, sms_mode, monkeypatch, days_ago, expected_jobs):
    contact(client)
    text = "这条命我不愿意再继续了"
    with session() as db:
        conversation = get_or_create_conversation(db, "context-sms")
        record = UserRecord(conversation_id=conversation.id, client_record_id="semantic-record", mode="text",
                            text=text, photo_comment="", ai_description="", status="saved",
                            created_at=datetime.now(UTC) - timedelta(days=days_ago))
        db.add(record); db.flush()
        ingest_source(db, conversation.id, "record", record.id, text, record.created_at, screening.assess(text, use_model=False))
        db.commit()
        args = str(db.get_bind().url), conversation.id, record.id
    semantic_mode(monkeypatch, text)
    process_record_assessment(*args)
    process_record_assessment(*args)
    with session() as db:
        assert db.scalar(select(WellbeingSignal)).assessment["notify_candidate"]
        assert len(db.scalars(select(NotificationOutbox)).all()) == expected_jobs
