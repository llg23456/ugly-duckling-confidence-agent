from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.core.config import Settings
from app.db.models import NotificationOutbox, TrustedContact
from app.services.notification_service import DEVICE_SMS_CONSENT, DEVICE_SMS_TEXT, dispatch_pending
from test_wellbeing import session

BASE = "/api/v1/wellbeing/device-sms"


@pytest.fixture
def sms_mode(client, monkeypatch):
    settings = Settings(_env_file=None, app_env="development", notification_mode="mock", enable_live_ai=False,
                        dashscope_api_key="", cloud_auth_required=False)
    monkeypatch.setattr("app.services.notification_service.get_settings", lambda: settings)
    return settings


def setup(client, device="sms-owner", phone="13800000000"):
    person = client.post("/api/v1/support-people", json={"device_id": device, "name": "演示接收人",
        "relationship": "家人", "kind": "family", "scenarios": ["情绪低落"]}).json()
    body = {"device_id": device, "support_person_id": person["id"], "phone": phone, "phone_repeat": phone,
            "trusted_confirmed": True, "adult_confirmed": True, "consent_version": DEVICE_SMS_CONSENT}
    result = client.post(BASE + "/setup", json=body)
    assert result.status_code == 200, result.text
    return {"device_id": device, "enrollment_token": result.json()["enrollment_token"]}, body


def danger(client, device="sms-owner"):
    return client.post("/api/v1/chat", json={"device_id": device, "message": "我今晚打算结束自己的生命"})


def test_real_jobs_are_explicit_and_never_dispatched_by_mock(client, sms_mode):
    danger(client)
    auth, body = setup(client)
    assert client.post(BASE + "/claim", json=auth).json()["job"] is None  # no replay
    for text in ("我有轻生的念头", "我没有轻生的念头", "小说台词是我今晚打算结束自己的生命"):
        client.post("/api/v1/chat", json={"device_id": "sms-owner", "message": text})
        assert client.post(BASE + "/claim", json=auth).json()["job"] is None
    danger(client)
    with session() as db:
        dispatch_pending(db)
        assert db.scalar(select(NotificationOutbox)).status == "phone_queued"
    claimed = client.post(BASE + "/claim", json=auth).json()["job"]
    assert claimed["phone"] == "+8613800000000" and claimed["text"] == DEVICE_SMS_TEXT
    assert "演示" in claimed["text"] and "结束自己的生命" not in claimed["text"]
    assert client.post(BASE + "/claim", json=auth).json()["job"] is None
    job_auth = {**auth, "claim_token": claimed["claim_token"]}
    assert client.post(f"{BASE}/{claimed['id']}/authorize", json=job_auth).status_code == 200
    assert client.post(f"{BASE}/{claimed['id']}/authorize", json=job_auth).status_code == 409
    receipt = {"device_id": "sms-owner", "claim_token": claimed["claim_token"], "status": "phone_sent"}
    assert client.post(f"{BASE}/{claimed['id']}/receipt", json=receipt).json()["status"] == "phone_sent"
    receipt["status"] = "phone_delivered"
    assert client.post(f"{BASE}/{claimed['id']}/receipt", json=receipt).json()["status"] == "phone_delivered"
    receipt["status"] = "phone_failed"
    assert client.post(f"{BASE}/{claimed['id']}/receipt", json=receipt).json()["status"] == "phone_delivered"
    danger(client)
    assert client.post(BASE + "/claim", json=auth).json()["job"] is None  # cooldown
    with session() as db:
        assert db.scalar(select(TrustedContact)).code_digest != auth["enrollment_token"]


def test_enrollment_validates_confirmation_and_production_gate(client, sms_mode):
    auth, body = setup(client)
    for change, status in [({"adult_confirmed": False}, 422), ({"trusted_confirmed": False}, 422),
                           ({"phone_repeat": "13900000000"}, 422), ({"device_id": "other"}, 404)]:
        assert client.post(BASE + "/setup", json={**body, **change}).status_code == status
    assert client.post(BASE + "/claim", json={**auth, "device_id": "other"}).status_code == 403
    assert client.post(BASE + "/claim", json={**auth, "enrollment_token": "x" * 43}).status_code == 403
    sms_mode.cloud_auth_required = True
    assert client.post(BASE + "/setup", json=body).status_code == 409
    sms_mode.cloud_auth_required = False
    sms_mode.app_env = "production"
    assert client.post(BASE + "/claim", json=auth).status_code == 409


@pytest.mark.parametrize("revoke", ["disable", "rebind", "delete_contact", "delete_source", "expired"])
def test_claim_is_rechecked_before_actual_phone_send(client, sms_mode, revoke):
    auth, body = setup(client)
    message = danger(client).json()
    claimed = client.post(BASE + "/claim", json=auth).json()["job"]
    if revoke == "disable":
        client.patch("/api/v1/wellbeing/preferences", json={"device_id": "sms-owner", "auto_notify": False})
    elif revoke == "rebind":
        assert client.post(BASE + "/setup", json={**body, "phone": "13900000000", "phone_repeat": "13900000000"}).status_code == 200
    elif revoke == "delete_contact":
        with session() as db:
            cid = db.scalar(select(TrustedContact.id))
        client.delete(f"/api/v1/wellbeing/contacts/{cid}?device_id=sms-owner")
    elif revoke == "delete_source":
        client.delete(f"/api/v1/conversations/sms-owner/messages/{message['user_message_id']}")
    else:
        with session() as db:
            db.scalar(select(NotificationOutbox)).expires_at = datetime.now(UTC) - timedelta(seconds=1)
            db.commit()
    result = client.post(f"{BASE}/{claimed['id']}/authorize", json={**auth, "claim_token": claimed["claim_token"]})
    assert result.status_code in (403, 404, 409)


def test_expired_job_and_lost_claim_response_are_not_retried(client, sms_mode):
    auth, _ = setup(client)
    danger(client)
    with session() as db:
        db.scalar(select(NotificationOutbox)).expires_at = datetime.now(UTC) - timedelta(seconds=1)
        db.commit()
    assert client.post(BASE + "/claim", json=auth).json()["job"] is None
    danger(client)
    claimed = client.post(BASE + "/claim", json=auth).json()["job"]
    with session() as db:
        db.get(NotificationOutbox, claimed["id"]).expires_at = datetime.now(UTC) - timedelta(seconds=1)
        db.commit()
    assert client.post(BASE + "/claim", json=auth).json()["job"] is None
    with session() as db:
        assert db.get(NotificationOutbox, claimed["id"]).status == "phone_unknown"


def test_wrong_receipt_cannot_update_another_job(client, sms_mode):
    auth, _ = setup(client)
    danger(client)
    job = client.post(BASE + "/claim", json=auth).json()["job"]
    payload = {"device_id": "sms-owner", "claim_token": "x" * 43, "status": "phone_sent"}
    assert client.post(f"{BASE}/{job['id']}/receipt", json=payload).status_code == 403
    payload.update(device_id="other", claim_token=job["claim_token"])
    assert client.post(f"{BASE}/{job['id']}/receipt", json=payload).status_code == 404
