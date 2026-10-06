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
    demo_text = " ".join(item["fact"] for item in events if item["source_type"] == "demo")
    assert all(word in demo_text for word in ("考研", "师姐", "师兄", "学院老师", "运动", "户外", "休息"))
    assert "小组汇报" not in demo_text and "正式汇报" not in demo_text
    keywords = client.post("/api/v1/videos/keywords", json={
        "device_id": device_id, "event_ids": [item["id"] for item in events if item["source_type"] == "demo"],
    }).json()
    assert {"师兄师姐", "老师指导", "运动与户外"}.issubset({item["label"] for item in keywords["suggestions"]})

    cleared = client.request("DELETE", "/api/v1/dev/demo-data", json={"device_id": device_id})
    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["deleted"] == 28
    records = client.get(f"/api/v1/records?device_id={device_id}").json()["records"]
    assert len(records) == 1 and records[0]["client_record_id"] == "real-record"
    events = client.get(f"/api/v1/events?device_id={device_id}").json()["events"]
    assert len(events) == 1


def test_fixed_exam_week_uses_september_7_to_13_and_stays_demo_only(client: TestClient) -> None:
    device_id = "fixed-exam-week"
    created = client.post("/api/v1/dev/demo-data", json={
        "device_id": device_id,
        "preset": "exam_week_2026_09_07",
    })
    assert created.status_code == 200, created.text
    assert created.json()["created"] == 7
    assert created.json()["range_start"] == "2026-09-07"
    assert created.json()["range_end"] == "2026-09-13"

    events = client.get(f"/api/v1/events?device_id={device_id}").json()["events"]
    assert len(events) == 7
    assert all(item["source_type"] == "demo" for item in events)
    assert sorted(item["created_at"][:10] for item in events) == [
        f"2026-09-{day:02d}" for day in range(7, 14)
    ]
    story = " ".join(item["fact"] for item in events)
    assert all(word in story for word in ("父母", "学院老师", "师兄师姐", "运动", "户外", "表达"))
    assert "录取" not in story


def test_unknown_demo_preset_is_rejected(client: TestClient) -> None:
    response = client.post("/api/v1/dev/demo-data", json={"device_id": "bad-preset", "preset": "unknown"})
    assert response.status_code == 400
