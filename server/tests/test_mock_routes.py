from fastapi.testclient import TestClient

def test_chat_contract(client: TestClient) -> None:
    response = client.post(
        "/api/v1/chat",
        json={"device_id": "demo-device", "message": "我明天要答辩", "mode": "listen"},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["mock"] is True
    assert payload["strategy"] == "listen"


def test_support_never_auto_sends(client: TestClient) -> None:
    response = client.post(
        "/api/v1/support/suggest",
        json={"situation": "想找人陪练", "preferred_supporters": ["classmate"]},
    )
    assert response.status_code == 200
    assert response.json()["auto_send"] is False


def test_crisis_gate_is_deterministic_and_skips_memory_pipeline(client: TestClient) -> None:
    response = client.post(
        "/api/v1/chat",
        json={"device_id": "safety-device", "message": "我现在不想活了", "mode": "listen"},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["safety_triggered"] is True
    assert payload["mock"] is False
    assert payload["evidence"] == []
    assert client.get("/api/v1/events?device_id=safety-device").json()["events"] == []
