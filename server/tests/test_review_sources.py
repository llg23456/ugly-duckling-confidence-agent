from datetime import UTC, datetime, timedelta
from sqlalchemy import select

from app.db.models import GrowthEvent, Message, UserRecord
from app.db.repository import get_or_create_conversation
from app.services.memory_service import local_day
from app.services.review_service import today_local
from app.services.review_materials import review_materials
from test_wellbeing import session


def seed_message(device="sources", text="我打算明天学习，但今天还没开始", day=None, modality="text", observation=None):
    with session() as db:
        conversation = get_or_create_conversation(db, device)
        message = Message(conversation_id=conversation.id, role="user", content=text, modality=modality,
                          image_observation=observation, created_at=day or datetime.now(UTC))
        db.add(message); db.commit()
        return message.id


def day_review(client, day=None):
    day = day or today_local().isoformat()
    response = client.post("/api/v1/reviews/overview", json={"device_id": "sources", "period": "day", "start_date": day, "end_date": day})
    assert response.status_code == 200, response.text
    return response.json()["review"]


def test_every_user_source_is_visible_without_inventing_growth_events(client):
    msg = seed_message()
    image = seed_message(text="这是我收到的反馈截图", modality="image", observation="截图文字建议把汇报的结论放到前面")
    with session() as db:
        conversation = get_or_create_conversation(db, "sources")
        db.add(Message(conversation_id=conversation.id, role="assistant", content="这不是用户的事实", modality="text"))
        for status in ("draft", "saved"):
            db.add(UserRecord(conversation_id=conversation.id, client_record_id=status, mode="text", text=status + "记录",
                              photo_comment="", ai_description="", status=status, created_at=datetime.now(UTC)))
        db.commit()
    review = day_review(client)
    ids = {m["source_id"] for m in review["moments"]}
    assert {msg, image}.issubset(ids)
    assert len(review["moments"]) == 3
    assert "还没开始" in review["story"] and "汇报" in review["story"]
    assert "draft记录" not in review["story"] and "不是用户的事实" not in review["story"]
    with session() as db:
        assert db.scalar(select(GrowthEvent)) is None
    assert len(client.get("/api/v1/reviews/materials?device_id=sources").json()["events"]) == 3
    assert client.get("/api/v1/reviews/materials?device_id=other").json()["events"] == []


def test_cache_tracks_content_changes_and_source_deletion(client):
    msg = seed_message(text="明天我想练习表达")
    before = day_review(client)
    with session() as db:
        db.get(Message, msg).content = "计划改了，我想先休息"
        db.commit()
    after = day_review(client)
    assert after["source_fingerprint"] != before["source_fingerprint"]
    assert "计划改了" in after["story"] and "练习表达" not in after["story"]
    assert client.delete(f"/api/v1/conversations/sources/messages/{msg}").status_code == 204
    assert day_review(client)["moments"] == []


def test_extracted_event_is_not_counted_twice_and_original_is_available(client):
    msg = seed_message(text="我做了练习，也想聊聊后来的焦虑")
    with session() as db:
        message = db.get(Message, msg)
        event = GrowthEvent(conversation_id=message.conversation_id, fact="做了练习", source_user_message_id=msg,
                            source_type="chat", created_at=message.created_at)
        db.add(event); db.commit()
        materials = review_materials(db, message.conversation_id)
        assert len(materials) == 1 and materials[0].id == event.id
        assert "焦虑" in materials[0].source_excerpt


def test_three_days_of_chat_can_be_video_materials_and_deletion_invalidates_script(client):
    ids = [seed_message(text=f"第{i}天想做一点练习，尚未完成", day=datetime.now(UTC) - timedelta(days=i)) for i in range(3)]
    raw_ids = [-10 * i - 1 for i in ids]
    assert client.post("/api/v1/videos/scripts", json={"device_id": "other", "event_ids": raw_ids}).status_code == 422
    response = client.post("/api/v1/videos/scripts", json={"device_id": "sources", "event_ids": raw_ids})
    assert response.status_code == 201, response.text
    script = response.json()
    assert len(script["scenes"]) == 3 and all("尚未完成" in s["text"] for s in script["scenes"])
    client.delete(f"/api/v1/conversations/sources/messages/{ids[0]}")
    assert client.get(f"/api/v1/videos/scripts/{script['id']}?device_id=sources").status_code == 404


def test_image_observation_is_saved_as_unconfirmed_source(client, monkeypatch):
    from app.schemas.multimodal import MultimodalChatResponse
    monkeypatch.setattr("app.api.routes.multimodal.chat_with_image", lambda *a, **k: MultimodalChatResponse(
        modality="image", user_text="反馈截图", reply="我们看看建议", model="test", image_observation="截图写着：下次可以慢一点"))
    result = client.post("/api/v1/multimodal/image", data={"device_id": "sources", "prompt": "反馈截图"}, files={"file": ("test.png", b"test", "image/png")})
    assert result.status_code == 200, result.text
    with session() as db:
        message = db.get(Message, result.json()["user_message_id"])
        assert message.image_observation == "截图写着：下次可以慢一点"
    assert "AI识别待核实" in day_review(client)["story"]


def test_actions_are_separate_options_grounded_in_latest_need(client):
    academic = client.post("/api/v1/support/suggest", json={"situation": "代码题目卡住了，想请人帮我找错误"}).json()
    emotional = client.post("/api/v1/support/suggest", json={"situation": "我今晚打算结束自己的生命"}).json()
    assert len(set(academic["action_options"])) == 3
    assert len(set(emotional["action_options"])) == 3
    assert academic["action_options"] != emotional["action_options"]
    assert "紧急服务" in "".join(emotional["action_options"])
    assert "学习" not in "".join(emotional["action_options"])
