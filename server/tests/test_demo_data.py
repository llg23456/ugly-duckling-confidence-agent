from fastapi.testclient import TestClient


def test_demo_data_can_be_created_and_cleared_without_deleting_real_records(client: TestClient) -> None:
    device_id = "demo-owner"
    real = client.post("/api/v1/records/sync", json={
        "device_id": device_id,
        "records": [{
            "client_record_id": "real-record",
            "mode": "text",
            "text": "这是一条真实测试记录",
            "photo_comment": "",
            "status": "saved",
            "created_at_ms": 1_790_553_600_000,
        }],
    })
    assert real.status_code == 200, real.text

    created = client.post("/api/v1/dev/demo-data", json={"device_id": device_id})
    assert created.status_code == 200, created.text
    assert created.json()["created"] == 28
    events = client.get(f"/api/v1/events?device_id={device_id}").json()["events"]
    assert len(events) == 29
    assert sum(item["source_type"] == "demo" for item in events) == 28

    cleared = client.request("DELETE", "/api/v1/dev/demo-data", json={"device_id": device_id})
    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["deleted"] == 28
    records = client.get(f"/api/v1/records?device_id={device_id}").json()["records"]
    assert len(records) == 1 and records[0]["client_record_id"] == "real-record"
    events = client.get(f"/api/v1/events?device_id={device_id}").json()["events"]
    assert len(events) == 1
