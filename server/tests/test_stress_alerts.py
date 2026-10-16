from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient


LOCAL = timezone(timedelta(hours=8))


def _record(client: TestClient, device_id: str, record_id: str, text: str, days_ago: int) -> int:
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
    return response.json()["records"][0]["id"]


def test_cross_day_threshold_and_single_day_does_not_trigger(client: TestClient) -> None:
    _record(client, "single", "one", "今天任务很多，感到有些压力。", 1)
    assert client.get("/api/v1/stress-alerts/active?device_id=single").json()["alert"] is None

    _record(client, "cross-day", "one", "准备展示时感到紧张。", 3)
    _record(client, "cross-day", "two", "今天想到安排还是很担心。", 1)
    alert = client.get("/api/v1/stress-alerts/active?device_id=cross-day").json()["alert"]
    assert alert["level"] == "watch"
    assert len(alert["evidence"]) == 2
    assert len(alert["trend"]) == 7


def test_negation_recovery_future_and_acute_content_are_excluded(client: TestClient) -> None:
    cases = (
        ("recovery", "前几天很焦虑，不过现在已经好多了。"),
        ("future", "以后可能会因为考试感到压力。"),
        ("acute", "我有伤害自己的念头。"),
    )
    for device_id, text in cases:
        _record(client, device_id, "one", text, 3)
        _record(client, device_id, "two", text, 1)
        assert client.get(f"/api/v1/stress-alerts/active?device_id={device_id}").json()["alert"] is None
    _record(client, "future-date", "one", "最近压力很大，睡得不好。", -2)
    _record(client, "future-date", "two", "今天很焦虑，很难专注。", -1)
    assert client.get("/api/v1/stress-alerts/active?device_id=future-date").json()["alert"] is None


def test_high_threshold_self_check_and_device_ownership(client: TestClient) -> None:
    _record(client, "high", "one", "最近压力很大，晚上一直睡不好。", 3)
    _record(client, "high", "two", "今天很慌，完全无法集中注意力。", 1)
    alert = client.get("/api/v1/stress-alerts/active?device_id=high").json()["alert"]
    assert alert["level"] == "high"

    foreign = client.post(f"/api/v1/stress-alerts/{alert['id']}/self-check", json={
        "device_id": "another-device", "impact": "none", "coping": "manageable",
    })
    assert foreign.status_code == 404

    adjusted = client.post(f"/api/v1/stress-alerts/{alert['id']}/self-check", json={
        "device_id": "high", "impact": "none", "coping": "manageable",
    })
    assert adjusted.status_code == 200
    assert adjusted.json()["level"] == "watch"
    assert "诊断" in adjusted.json()["support_copy"]
    assert "疾病" not in adjusted.text


def test_prepare_is_repeatable_and_follow_up_uses_pending_interface(client: TestClient) -> None:
    first = client.post("/api/v1/stress-alerts/prepare-scenario", json={"device_id": "scene"})
    second = client.post("/api/v1/stress-alerts/prepare-scenario", json={"device_id": "scene"})
    assert first.status_code == second.status_code == 200
    assert second.json()["level"] == "high"
    excerpts = [item["excerpt"] for item in second.json()["evidence"]]
    assert excerpts[0] == "最近任务有点多，想到接下来的安排时总有些紧张。"
    assert all(text not in " ".join(excerpts) for text in ("有些不安", "很疲惫", "难以专注"))
    exported = client.get("/api/v1/data/export?device_id=scene").json()["data"]
    scenario_events = [item for item in exported["growth_events"] if item["source_type"] == "stress_scenario"]
    assert len(scenario_events) == 3
    assert len(exported["stress_alerts"]) == 1

    alert_id = second.json()["id"]
    scheduled = client.post(f"/api/v1/stress-alerts/{alert_id}/follow-up", json={"device_id": "scene"})
    assert scheduled.status_code == 200
    assert scheduled.json()["follow_up"]["ready"] is False
    assert client.get("/api/v1/check-ins/pending?device_id=scene").json()["check_in"] is None

    ready = client.post(f"/api/v1/stress-alerts/{alert_id}/follow-up/ready", json={"device_id": "scene"})
    assert ready.status_code == 200
    pending = client.get("/api/v1/check-ins/pending?device_id=scene").json()["check_in"]
    assert pending["reason"] == "stress_follow_up"
    response = client.post(f"/api/v1/check-ins/{pending['id']}/respond", json={
        "device_id": "scene", "choice": "improved",
    })
    assert response.status_code == 200
    assert client.get("/api/v1/stress-alerts/active?device_id=scene").json()["alert"] is None


def test_source_delete_invalidates_alert_and_export_contains_evidence(client: TestClient) -> None:
    _record(client, "delete-source", "one", "最近压力很大，晚上睡不好。", 3)
    _record(client, "delete-source", "two", "今天很焦虑，注意力也很差。", 1)
    exported = client.get("/api/v1/data/export?device_id=delete-source").json()["data"]
    assert len(exported["stress_alerts"]) == 1
    assert len(exported["stress_alert_evidence"][0]["source_events"]) == 2

    deleted = client.delete("/api/v1/records/client/one?device_id=delete-source")
    assert deleted.status_code == 204
    assert client.get("/api/v1/stress-alerts/active?device_id=delete-source").json()["alert"] is None


def test_delete_all_removes_stress_alerts_and_follow_up(client: TestClient) -> None:
    alert = client.post("/api/v1/stress-alerts/prepare-scenario", json={"device_id": "erase"}).json()
    client.post(f"/api/v1/stress-alerts/{alert['id']}/follow-up", json={"device_id": "erase"})
    assert client.delete("/api/v1/data?device_id=erase").status_code == 204
    data = client.get("/api/v1/data/export?device_id=erase").json()["data"]
    assert data["stress_alerts"] == []
    assert data["proactive_check_ins"] == []


def test_clear_scenario_removes_events_alert_and_follow_up(client: TestClient) -> None:
    alert = client.post("/api/v1/stress-alerts/prepare-scenario", json={"device_id": "clear-scene"}).json()
    client.post(f"/api/v1/stress-alerts/{alert['id']}/follow-up", json={"device_id": "clear-scene"})
    response = client.delete("/api/v1/stress-alerts/scenario?device_id=clear-scene")
    assert response.status_code == 200
    assert response.json()["deleted"] == 3
    data = client.get("/api/v1/data/export?device_id=clear-scene").json()["data"]
    assert not [item for item in data["growth_events"] if item["source_type"] == "stress_scenario"]
    assert data["stress_alerts"] == []
    assert data["proactive_check_ins"] == []
