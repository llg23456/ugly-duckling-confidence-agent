from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app.services.video_service import DraftCaptions


def _event(client: TestClient, device_id: str, text: str, record_id: str) -> int:
    timestamp = datetime.now(timezone(timedelta(hours=8)))
    response = client.post("/api/v1/records/sync", json={"device_id": device_id, "records": [{
        "client_record_id": record_id, "mode": "text", "text": text,
        "created_at_ms": int(timestamp.timestamp() * 1000),
    }]})
    assert response.status_code == 200
    return response.json()["records"][0]["event_id"]


def test_video_script_uses_owned_events_and_can_be_edited(client: TestClient) -> None:
    first = _event(client, "video-owner", "答辩开场卡住了", "first")
    second = _event(client, "video-owner", "后来继续练习了一次", "second")
    third = _event(client, "video-owner", "现在愿意再试一步", "third")
    foreign = _event(client, "another", "别人的经历", "foreign")
    assert client.post("/api/v1/videos/scripts", json={
        "device_id": "video-owner", "event_ids": [first, second, foreign],
    }).status_code == 422
    created = client.post("/api/v1/videos/scripts", json={
        "device_id": "video-owner", "event_ids": [third, second, first],
    })
    assert created.status_code == 201, created.text
    script = created.json()
    assert script["mock"] is True and script["model"] is None
    assert script["source_event_ids"] == [first, second, third]
    assert [scene["stage"] for scene in script["scenes"]] == [
        "beginning", "difficulty", "small_step", "change", "continuing",
    ]
    assert "答辩开场" in script["scenes"][0]["text"]
    assert "卡住" in script["scenes"][1]["text"]
    assert client.get(f"/api/v1/videos/scripts/{script['id']}?device_id=another").status_code == 404
    revised = [script["scenes"][0], script["scenes"][3], script["scenes"][4]]
    revised[0]["text"] = "答辩那天，开场卡住了"
    updated = client.patch(f"/api/v1/videos/scripts/{script['id']}", json={
        "device_id": "video-owner", "scenes": revised,
    })
    assert updated.status_code == 200 and updated.json()["is_user_edited"] is True
    assert len(updated.json()["scenes"]) == 3
    assert client.patch(f"/api/v1/videos/scripts/{script['id']}", json={
        "device_id": "video-owner", "scenes": list(reversed(revised)),
    }).status_code == 422


def test_video_script_does_not_persist_invalid_model_output(client: TestClient, monkeypatch) -> None:
    event_ids = [
        _event(client, "bad-model", "我今天练习了开场", "one"),
        _event(client, "bad-model", "中间有点紧张", "two"),
        _event(client, "bad-model", "后来又试了一次", "three"),
    ]
    def fail(_events):
        raise ValueError("invalid model JSON")
    monkeypatch.setattr("app.api.routes.videos.generate_captions", fail)
    response = client.post("/api/v1/videos/scripts", json={"device_id": "bad-model", "event_ids": event_ids})
    assert response.status_code == 502
    monkeypatch.setattr("app.api.routes.videos.generate_captions", lambda _events: (
        DraftCaptions(beginning="从练习开场开始", difficulty="有点紧张", small_step="练习了开场",
                      change="今天练习了开场", continuing="继续下一小步"), "test-model",
    ))
    valid = client.post("/api/v1/videos/scripts", json={"device_id": "bad-model", "event_ids": event_ids})
    assert valid.status_code == 201 and valid.json()["mock"] is False
    assert valid.json()["model"] == "test-model"
    assert valid.json()["id"] == 1
