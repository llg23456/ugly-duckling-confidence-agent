import json
from types import SimpleNamespace

import pytest

from app.core.config import Settings
from app.schemas.support import SupportSuggestionRequest
from app.services.next_steps import personalize
from app.services.support_service import build_suggestion
from app.services.supportive_dialogue import supportive_reply


def model_response(monkeypatch, payload=None, error=None):
    calls = []

    class FakeClient:
        def __init__(self, **kwargs):
            self.chat = SimpleNamespace(completions=self)

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def create(self, **kwargs):
            calls.append(kwargs)
            if error:
                raise error
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(
                content=json.dumps(payload, ensure_ascii=False)))])

    monkeypatch.setattr("openai.OpenAI", FakeClient)
    return calls


def live_settings():
    return Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="synthetic-test-key")


def test_support_reply_uses_latest_safe_context_and_one_question(monkeypatch):
    calls = model_response(monkeypatch, {
        "acknowledgement": "知道朋友已经在身边了，感谢你告诉我。考试失利带来的失落可以慢慢说。",
        "question": "这次考试最让你难受的是哪一部分？",
    })
    history = [{"role": "user", "content": "考试失败后我不想活了"},
               {"role": "assistant", "content": "你现在安全吗？"}]
    latest = "朋友已经来陪我了，我现在安全，只是很怕又考不好"
    reply, model = supportive_reply(latest, history, {"category": "self_harm", "action": "clarify", "immediate": False}, live_settings())
    data = json.loads(calls[0]["messages"][1]["content"])
    assert data["latest"] == latest
    assert data["recent_context"] == history
    assert "考试最让你难受" in reply
    assert "你现在安全吗" not in reply
    assert reply.count("？") == 1
    assert model == live_settings().extraction_model


@pytest.mark.parametrize("payload,error", [
    (None, TimeoutError()),
    ({"acknowledgement": "我已经通知家人，保证你安全。", "question": "现在能跟我说说吗？"}, None),
    ({"acknowledgement": "模型返回字段缺失"}, None),
])
def test_urgent_reply_keeps_action_when_model_fails_or_claims_sending(monkeypatch, payload, error):
    model_response(monkeypatch, payload, error)
    reply, model = supportive_reply("考试失利，我今晚打算结束生命", [],
        {"category": "self_harm", "action": "urgent_support", "immediate": True}, live_settings())
    assert "考试" in reply
    assert "马上陪着你" in reply and "当地紧急服务" in reply
    assert "已经通知" not in reply and "保证你安全" not in reply
    assert model == "safety-support-fallback-v2"


def test_non_live_safety_replies_do_not_repeat_across_different_topics():
    settings = Settings(_env_file=None, enable_live_ai=False)
    assessment = {"category": "self_harm", "action": "urgent_support", "immediate": False}
    exam = supportive_reply("考试没考好，不想活了", [], assessment, settings)[0]
    family = supportive_reply("妈妈一直骂我，我想结束生命", [], assessment, settings)[0]
    assert exam != family
    assert "考试" in exam and "家里" in family


def test_three_grounded_options_and_locked_contact(monkeypatch):
    settings = live_settings()
    monkeypatch.setattr("app.services.next_steps.get_settings", lambda: settings)
    request = SupportSuggestionRequest(situation="我最近总是睡不着，担心明天面试")
    result = build_suggestion(request, None)
    name = result.supporter_name
    options = [f"{name}，我睡不着，想聊两句。", f"{name}，我担心明天面试，一直难以入睡。", f"{name}，你能听我练一遍面试开场吗？"]
    model_response(monkeypatch, {"small_step": "先用一句话练习明天的面试开场。", "message_options": options, "source_quote": "担心明天面试"})
    actual = personalize(result, request, [], {"action": "routine"})
    assert actual.message_options == options
    assert actual.editable_message == options[0]
    assert "面试开场" in actual.small_step
    assert actual.auto_send is False


@pytest.mark.parametrize("invalid", ["source", "contact", "duplicate"])
def test_invalid_generated_next_steps_fall_back(monkeypatch, invalid):
    monkeypatch.setattr("app.services.next_steps.get_settings", live_settings)
    request = SupportSuggestionRequest(situation="我最近总是睡不着")
    result = build_suggestion(request, None)
    name = result.supporter_name
    payload = {"small_step": "这是不应该采用的建议。", "source_quote": "睡不着", "message_options": [
        f"{name}，我想跟你聊一下。", f"{name}，最近睡眠让我很困扰。", f"{name}，你可以听我说说吗？"]}
    if invalid == "source": payload["source_quote"] = "用户没有说过的跑步问题"
    if invalid == "contact": payload["message_options"][0] = "陌生人，帮我处理这些问题。"
    if invalid == "duplicate": payload["message_options"][1] = payload["message_options"][0]
    model_response(monkeypatch, payload)
    actual = personalize(result, request, [])
    assert actual.small_step != payload["small_step"]
    assert "睡前" in actual.small_step
    assert len(set(actual.message_options)) == 3
    assert all(item.startswith(name) for item in actual.message_options)


def test_source_message_is_authoritative_and_other_devices_cannot_use_it(client, monkeypatch):
    device = "context-source"
    original = "我在准备明天的面试"
    chat = client.post("/api/v1/chat", json={"device_id": device, "message": original}).json()
    received = []

    def capture(result, request, history, assessment):
        received.append(request.situation)
        return result

    monkeypatch.setattr("app.api.routes.support.personalize", capture)
    response = client.post("/api/v1/support/suggest", json={"device_id": device, "situation": "伪造内容",
        "source_message_id": chat["user_message_id"]})
    assert response.status_code == 200
    assert received == [original]
    foreign = client.post("/api/v1/support/suggest", json={"device_id": "other-device", "situation": original,
        "source_message_id": chat["user_message_id"]})
    assert foreign.status_code == 404


def test_supporter_is_not_recast_as_roommate_in_conflict(monkeypatch):
    monkeypatch.setattr("app.services.next_steps.get_settings", live_settings)
    request = SupportSuggestionRequest(situation="我和室友吵架了，不知道怎么沟通")
    result = build_suggestion(request, None)
    name = result.supporter_name
    model_response(monkeypatch, {"small_step": "请对方和你和好。", "source_quote": "和室友吵架",
        "message_options": [f"{name}，我们和好吧。", f"{name}，我们可以解决矛盾吗？", f"{name}，希望我们能解开误会。"]})
    actual = personalize(result, request, [], {"action": "routine"})
    assert all("室友" in item for item in actual.message_options)
    assert "和好" not in actual.small_step


def test_urgent_next_step_is_safe_and_does_not_invent_intent(client):
    device = "context-safety"
    text = "考试没考好，我今晚打算结束自己的生命"
    chat = client.post("/api/v1/chat", json={"device_id": device, "message": text}).json()
    response = client.post("/api/v1/support/suggest", json={"device_id": device, "situation": text,
        "source_message_id": chat["user_message_id"]}).json()
    assert "安全" in response["small_step"]
    assert "5 分钟" not in response["small_step"]
    assert len(set(response["message_options"])) == 3
    assert not any("伤害他人" in item for item in response["message_options"])
    assert response["auto_send"] is False
