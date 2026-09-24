from fastapi.testclient import TestClient

def test_chat_contract(client: TestClient) -> None:
    response = client.post(
        "/api/v1/chat",
        json={"device_id": "demo-device", "message": "我明天要答辩", "mode": "listen"},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["mock"] is True
    assert payload["strategy"] == "seek_support"


def test_support_never_auto_sends(client: TestClient) -> None:
    response = client.post(
        "/api/v1/support/suggest",
        json={"situation": "想找人陪练", "preferred_supporters": ["classmate"]},
    )
    assert response.status_code == 200
    assert response.json()["auto_send"] is False
