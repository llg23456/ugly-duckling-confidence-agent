from sqlalchemy import select
import pytest

from app.db.models import NotificationOutbox, SupportPerson
from app.services.notification_service import CONTEXT_SMS_CONSENT
from test_device_sms import sms_mode, BASE
from test_wellbeing import session


def contact(client, *, name="妈妈", phone="13800000000", scenes=None, kind="family", enabled=True, device="context-sms"):
    body = dict(device_id=device, name=name, relationship=name, kind=kind,
                scenarios=scenes or ["情绪低落"], contact_version=2, phone=phone,
                emergency_enabled=enabled, contact_consent=True, consent_version=CONTEXT_SMS_CONSENT)
    result = client.post("/api/v1/support-people", json=body)
    assert result.status_code == 201, result.text
    item = result.json()
    return item, body, dict(device_id=device, enrollment_token=item["enrollment_token"])


@pytest.mark.parametrize("change", [{"phone": ""}, {"phone": "123"}, {"contact_consent": False}, {"consent_version": "old"}])
def test_integrated_editor_validates_before_commit(client, sms_mode, change):
    _, body, _ = contact(client)
    result = client.post("/api/v1/support-people", json={**body, "name": "不应保存", **change})
    assert result.status_code == 422
    with session() as db:
        assert db.scalar(select(SupportPerson).where(SupportPerson.name == "不应保存")) is None


def test_recipient_and_notice_are_grounded_in_this_turn(client, sms_mode):
    mom, body, auth = contact(client)
    teacher, _, teacher_auth = contact(client, name="吴老师", phone="13900000000", scenes=["专业问题"], kind="teacher")
    for text in ("我有轻生的念头", "我没有轻生的念头", "小说台词是我今晚打算结束自己的生命"):
        client.post("/api/v1/chat", json={"device_id": body["device_id"], "message": text})
        assert client.post(BASE + "/claim", json=auth).json()["job"] is None
    text = "我今晚打算结束自己的生命"
    client.post("/api/v1/chat", json={"device_id": body["device_id"], "message": text})
    assert client.post(BASE + "/claim", json=teacher_auth).json()["job"] is None
    job = client.post(BASE + "/claim", json=auth).json()["job"]
    assert job["contact_id"] == mom["contact_id"]
    assert text in job["text"] and "北京时间" in job["text"] and "在对话中表达了" in job["text"]
    assert "演示" not in job["text"] and "不代表行为已发生" in job["text"]
    history = client.get("/api/v1/wellbeing/notifications?device_id=context-sms").json()["items"]
    assert history[0]["message"] == job["text"]
    assert client.post(f"{BASE}/{job['id']}/authorize", json={**auth, "claim_token": job["claim_token"]}).status_code == 200
    people = client.get("/api/v1/support-people?device_id=context-sms").json()["people"]
    assert all(p["enrollment_token"] is None for p in people)
    with session() as db:
        assert len(db.scalars(select(NotificationOutbox)).all()) == 1


def test_rebinding_invalidates_only_this_contact_and_pending_job(client, sms_mode):
    mom, body, auth = contact(client)
    friend, _, friend_auth = contact(client, name="小林", phone="13900000000", kind="friend")
    client.post("/api/v1/chat", json={"device_id": body["device_id"], "message": "妈妈，我今晚打算结束自己的生命"})
    job = client.post(BASE + "/claim", json=auth).json()["job"]
    changed = client.patch(f"/api/v1/support-people/{mom['id']}", json={**body, "phone": "13700000000"})
    assert changed.status_code == 200, changed.text
    assert client.post(BASE + "/claim", json=auth).status_code == 403
    assert client.post(BASE + "/claim", json=friend_auth).status_code == 200
    assert client.post(f"{BASE}/{job['id']}/authorize", json={**auth, "claim_token": job["claim_token"]}).status_code == 403


def test_phone_only_contact_never_enables_sms(client, sms_mode):
    item, body, auth = contact(client, enabled=False)
    assert not item["emergency_enabled"] and item["enrollment_token"] is None
    client.post("/api/v1/chat", json={"device_id": body["device_id"], "message": "我今晚打算结束自己的生命"})
    assert client.get("/api/v1/wellbeing/notifications?device_id=context-sms").json()["items"] == []


def test_disabling_contacts_preserves_other_authorizations_and_stops_when_empty(client, sms_mode):
    mom, body, auth = contact(client)
    friend, other_body, other_auth = contact(client, name="小林", phone="13900000000", kind="friend")
    result = client.patch(f"/api/v1/support-people/{mom['id']}", json={**body, "emergency_enabled": False})
    assert result.status_code == 200
    assert client.post(BASE + "/claim", json=auth).status_code == 403
    assert client.post(BASE + "/claim", json=other_auth).status_code == 200
    client.patch(f"/api/v1/support-people/{friend['id']}", json={**other_body, "emergency_enabled": False})
    assert not client.get("/api/v1/wellbeing/today?device_id=context-sms").json()["preferences"]["auto_notify"]


def test_scene_change_after_claim_blocks_send(client, sms_mode):
    mom, body, auth = contact(client)
    client.post("/api/v1/chat", json={"device_id": body["device_id"], "message": "我今晚打算结束自己的生命"})
    job = client.post(BASE + "/claim", json=auth).json()["job"]
    # Legacy clients may edit only support scenes; authorization still rechecks them.
    legacy = {k: body[k] for k in ("device_id", "name", "relationship", "kind")}
    client.patch(f"/api/v1/support-people/{mom['id']}", json={**legacy, "scenarios": ["专业问题"]})
    result = client.post(f"{BASE}/{job['id']}/authorize", json={**auth, "claim_token": job["claim_token"]})
    assert result.status_code == 409
