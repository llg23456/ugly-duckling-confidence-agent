"""Verify the deployed trend API through public HTTPS using one disposable identity.

Run inside the active API container with production settings. Only the generated
identity is changed. Credentials stay in memory and are revoked in finally.
Record sync and chat may invoke the configured cloud model.
"""
from datetime import UTC, datetime, timedelta
import json
from pathlib import Path
import time
import uuid

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.cloud_access import CloudCredential, CloudInvite, digest, issue_invite
from app.core.config import get_settings
from app.db.models import Conversation
from app.db.session import engine_for_url


ORIGIN = "https://api.yaligo-growth.top"
DEVICE = "trend-smoke-" + uuid.uuid4().hex
REPORT = Path("/backups") / (DEVICE + "-verification.json")
report = {"started_at": datetime.now(UTC).isoformat(), "origin": ORIGIN,
          "device_id": DEVICE, "fixture": "synthetic acceptance records only",
          "suite": "stress-trend", "checks": {}}
engine = engine_for_url(get_settings().database_url)
headers = {}
code_digest = None


def save():
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


def request(client, method, path, expected=200, **kwargs):
    response = client.request(method, "/api/v1" + path, **kwargs)
    assert response.status_code == expected, (method, path, response.status_code)
    return response.json() if response.content else None


def trend(client):
    body = request(client, "POST", "/stress-alerts/trend", headers=headers,
                   json={"device_id": DEVICE})
    assert len(body["trend"]) == 7
    report["latest_trend"] = body
    report.setdefault("trend_snapshots", []).append(body)
    save()
    return body


def record(client, key, text, days):
    stamp = datetime.now(UTC) - timedelta(days=days, seconds=2)
    body = request(client, "POST", "/records/sync", headers=headers, json={
        "device_id": DEVICE, "records": [{"client_record_id": key, "mode": "text",
        "text": text, "status": "saved", "created_at_ms": int(stamp.timestamp() * 1000)}]})
    assert len(body["records"]) == 1
    return body["records"][0]["id"]


try:
    with httpx.Client(base_url=ORIGIN, timeout=150, follow_redirects=False) as client:
        health = client.get("/health")
        assert health.status_code == 200 and health.json()["authentication_required"] is True
        report["health"] = health.json()
        request(client, "POST", "/stress-alerts/trend", expected=401, json={"device_id": DEVICE})
        report["checks"]["unauthenticated_401"] = True
        with Session(engine) as db:
            assert db.scalar(select(Conversation.id).where(Conversation.device_id == DEVICE)) is None
            code = issue_invite(db, "stress-trend-smoke-hk05")
            code_digest = digest(code)
        authorization = request(client, "POST", "/cloud-auth/activate",
                                json={"invite_code": code, "device_id": DEVICE})
        headers = {"Authorization": "Bearer " + authorization["access_token"]}
        del code, authorization
        request(client, "POST", "/stress-alerts/trend", expected=403, headers=headers,
                json={"device_id": DEVICE + "-other"})
        report["checks"]["cross_device_403"] = True
        empty = trend(client)
        assert empty["level"] == "unknown" and empty["record_count"] == 0
        assert empty["message_count"] == empty["observed_days"] == 0
        assert empty["sources"] == [] and empty["alert"] is None
        assert all(p["level"] == "unknown" for p in empty["trend"])
        report["checks"]["empty_seven_days_no_fabricated_data"] = True
        assert request(client, "POST", "/stress-alerts/refresh", headers=headers,
                       json={"device_id": DEVICE}) == {"alert": None}

        calm_text = "今天和朋友在公园散步，感觉很放松。"
        calm_id = record(client, "calm", calm_text, 2)
        calm = trend(client)
        assert calm["level"] == "steady" and calm["record_count"] == 1
        assert calm["observed_days"] == 1 and calm["alert"] is None
        assert any(s["source_id"] == calm_id and s["excerpt"] == calm_text for s in calm["sources"])
        report["checks"]["steady_data_without_alert"] = True

        watch_id = record(client, "watch", "今天复习有点紧张。", 1)
        watch = trend(client)
        assert watch["level"] == "watch" and watch["record_count"] == 2
        assert watch["observed_days"] == 2 and watch["alert"] is None
        assert any(s["source_id"] == watch_id and s["level"] == "watch" for s in watch["sources"])
        report["checks"]["single_pressure_day_without_alert"] = True

        high_id = record(client, "high", "今天复习压力很大，晚上睡不好。", 0)
        high = trend(client)
        assert high["level"] == "high" and high["record_count"] == 3
        assert high["observed_days"] == 3
        assert any(s["source_id"] == high_id and s["level"] == "high" for s in high["sources"])
        report["checks"]["higher_signal_in_owned_records"] = True

        # All deletions target this script's generated identity and record IDs.
        request(client, "DELETE", "/records/client/high", expected=204, headers=headers,
                params={"device_id": DEVICE})
        reduced = trend(client)
        assert reduced["level"] == "watch" and reduced["record_count"] == 2
        assert all(s["source_id"] != high_id for s in reduced["sources"] if s["source_type"] == "record")
        report["checks"]["deleted_source_removed_from_trend"] = True

        chat = request(client, "POST", "/chat", headers=headers,
                       json={"device_id": DEVICE, "message": "今天休息得很好。"})
        assert chat["reply"]
        with_chat = trend(client)
        assert with_chat["message_count"] == 1
        assert any(s["source_type"] == "chat" and s["excerpt"] == "今天休息得很好。"
                   for s in with_chat["sources"])
        report["chat_mock"] = chat.get("mock")
        report["checks"]["user_chat_included"] = True
        report["passed"] = True
except Exception as error:
    report["passed"] = False
    report["error"] = type(error).__name__ + ": " + str(error)
    raise
finally:
    try:
        if headers:
            with httpx.Client(base_url=ORIGIN, timeout=150, follow_redirects=False) as client:
                request(client, "DELETE", "/data", expected=204, headers=headers, params={"device_id": DEVICE})
                erased = trend(client)
                assert erased["record_count"] == erased["message_count"] == erased["observed_days"] == 0
                assert erased["level"] == "unknown" and erased["alert"] is None
                report["checks"]["delete_all_resets_trend"] = True
    except Exception as error:
        report["passed"] = False
        report["cleanup_error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        with Session(engine) as db:
            credential = db.scalar(select(CloudCredential).where(CloudCredential.device_id == DEVICE))
            if credential:
                credential.revoked_at = int(time.time())
            if code_digest:
                invitation = db.get(CloudInvite, code_digest)
                if invitation:
                    invitation.expires_at = int(time.time())
            db.commit()
            report["checks"]["credential_revoked"] = credential is not None and credential.revoked_at is not None
            report["checks"]["synthetic_conversation_removed"] = db.scalar(
                select(Conversation.id).where(Conversation.device_id == DEVICE)) is None
        report["finished_at"] = datetime.now(UTC).isoformat()
        save()
        print(json.dumps(report, ensure_ascii=False, indent=2))
