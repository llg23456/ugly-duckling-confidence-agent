from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient


LOCAL = timezone(timedelta(hours=8))


def _record(client: TestClient, device_id: str, record_id: str, text: str, days_ago: int) -> None:
    day = datetime.now(LOCAL).date() - timedelta(days=days_ago)
    created_at = datetime(day.year, day.month, day.day, 12, tzinfo=LOCAL)
    response = client.post("/api/v1/records/sync", json={
        "device_id": device_id,
        "records": [{
            "client_record_id": record_id,
            "mode": "text",
            "text": text,
            "photo_comment": "",
            "status": "saved",
            "created_at_ms": int(created_at.timestamp() * 1000),
        }],
    })
    assert response.status_code == 200, response.text


def test_repeated_signal_creates_one_owned_check_in(client: TestClient) -> None:
    _record(client, "check-in-owner", "day-1", "准备展示时还是很紧张。", 2)
    assert client.get("/api/v1/check-ins/pending?device_id=check-in-owner").json()["check_in"] is None

    _record(client, "check-in-owner", "day-2", "今天想到展示又有点担心。", 1)
    pending = client.get("/api/v1/check-ins/pending?device_id=check-in-owner").json()["check_in"]
    assert pending["reason"] == "anxious"
    assert len(pending["source_event_ids"]) == 2
    assert "诊断" not in pending["prompt"] and "抑郁" not in pending["prompt"]
    assert client.get("/api/v1/check-ins/pending?device_id=another-device").json()["check_in"] is None

    foreign = client.post(f"/api/v1/check-ins/{pending['id']}/respond", json={
        "device_id": "another-device", "choice": "not_now",
    })
    assert foreign.status_code == 404


def test_new_task_notice_is_one_time_and_keeps_question_pending(client: TestClient) -> None:
    _record(client, "notice", "day-1", "准备展示时还是很紧张。", 2)
    _record(client, "notice", "day-2", "今天想到展示又有点担心。", 1)
    first = client.get("/api/v1/check-ins/notice?device_id=notice").json()["check_in"]
    assert first is not None and first["reason"] == "anxious"
    assert client.get("/api/v1/check-ins/notice?device_id=notice").json()["check_in"] is None
    assert client.get("/api/v1/check-ins/pending?device_id=notice").json()["check_in"]["id"] == first["id"]


def test_response_honors_choice_and_cooldown(client: TestClient) -> None:
    _record(client, "cooldown", "day-1", "这件事还是卡住了。", 3)
    _record(client, "cooldown", "day-2", "今天仍然不知道怎么继续。", 2)
    pending = client.get("/api/v1/check-ins/pending?device_id=cooldown").json()["check_in"]

    response = client.post(f"/api/v1/check-ins/{pending['id']}/respond", json={
        "device_id": "cooldown", "choice": "not_now",
    })
    assert response.status_code == 200
    assert "不会再追问" in response.json()["acknowledgement"]
    _record(client, "cooldown", "day-3", "还是没有进展。", 1)
    assert client.get("/api/v1/check-ins/pending?device_id=cooldown").json()["check_in"] is None


def test_talk_choice_returns_sourced_follow_up(client: TestClient) -> None:
    _record(client, "talk", "day-1", "最近压力很大，晚上睡不好。", 2)
    _record(client, "talk", "day-2", "今天还是有点焦虑。", 1)
    pending = client.get("/api/v1/check-ins/pending?device_id=talk").json()["check_in"]
    response = client.post(f"/api/v1/check-ins/{pending['id']}/respond", json={
        "device_id": "talk", "choice": "talk",
    })
    assert response.status_code == 200
    assert response.json()["follow_up_message"].startswith("我想聊聊最近这件事：")
    assert client.get("/api/v1/check-ins/pending?device_id=talk").json()["check_in"] is None


def test_explicit_follow_up_can_trigger_once_but_acute_words_do_not(client: TestClient) -> None:
    _record(client, "explicit", "request", "这件事我今天不想展开，明天问问我。", 1)
    pending = client.get("/api/v1/check-ins/pending?device_id=explicit").json()["check_in"]
    assert pending["reason"] == "follow_up"

    _record(client, "acute", "day-1", "我有不想活的念头。", 2)
    _record(client, "acute", "day-2", "我还是想伤害自己。", 1)
    assert client.get("/api/v1/check-ins/pending?device_id=acute").json()["check_in"] is None


def test_chat_explicit_follow_up_is_scheduled_without_waiting_for_extraction(client: TestClient) -> None:
    response = client.post("/api/v1/chat", json={
        "device_id": "explicit-chat",
        "message": "这件事今天先不说了，明天问问我。",
    })
    assert response.status_code == 200, response.text
    assert response.json()["check_in_scheduled"] is True
    pending = client.get("/api/v1/check-ins/pending?device_id=explicit-chat").json()["check_in"]
    assert pending is not None and pending["reason"] == "follow_up"
