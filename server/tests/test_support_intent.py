import json

import pytest

from app.core.config import Settings
from app.db.models import SupportPerson
from app.schemas.support import SupportSuggestionRequest
from app.services.support_intent import fallback_intent, infer_support_intent
from app.services.support_service import choose_person
from test_contextual_support import model_response


ROUTINE_PRESSURE = {"category": "severe_distress", "action": "routine", "notify_candidate": False}


def people():
    # Reverse insertion order and role preferences must not override saved purposes.
    return [SupportPerson(id=1, name="妈妈", relationship="母亲", kind="family", scenarios=["情绪低落"]),
            SupportPerson(id=2, name="吴老师", relationship="老师", kind="teacher", scenarios=["专业问题"])]


@pytest.mark.parametrize("text,focus,name", [
    ("今天真的要累死了，一堆作业，压力贼大", "academic_workload", "吴老师"),
    ("今天真的要累死了，玛德，学的快不想活了，一堆作业做不完", "academic_workload", "吴老师"),
    ("这道专业课的概念不理解，很焦虑", "academic_question", "吴老师"),
    ("不要安慰，帮我安排一下复习进度", "academic_workload", "吴老师"),
    ("作业好多，我只想有人听我说说，不想讨论做题", "emotional", "妈妈"),
    ("专业课考砸了，我很难过想有人陪陪我", "emotional", "妈妈"),
])
def test_pressure_and_help_purpose_are_separate(text, focus, name):
    intent = fallback_intent(text, ROUTINE_PRESSURE)
    assert intent.focus == focus
    actual = choose_person(SupportSuggestionRequest(situation=text, preferred_supporters=["family"]),
                           people(), assessment=ROUTINE_PRESSURE, resolved_need=intent.need)
    assert actual.name == name
    # Existing direct callers also use the corrected offline purpose rules.
    assert choose_person(SupportSuggestionRequest(situation=text), people(), assessment=ROUTINE_PRESSURE).name == name


def test_safety_and_explicit_choices_still_take_priority(monkeypatch):
    monkeypatch.setattr("app.services.support_intent.get_settings", lambda: pytest.fail("crisis must not call intent model"))
    text = "作业没做完，我今晚打算结束自己的生命"
    urgent = {"category": "self_harm", "action": "urgent_support"}
    assert infer_support_intent(text, [], urgent).focus == "emotional"
    assert choose_person(SupportSuggestionRequest(situation=text), people(),
                         assessment=urgent, resolved_need="academic").name == "妈妈"
    assert choose_person(SupportSuggestionRequest(situation="作业做不完，我想找妈妈"), people(),
                         assessment=ROUTINE_PRESSURE, resolved_need="academic").name == "妈妈"
    assert choose_person(SupportSuggestionRequest(situation="这道题目不会，但不想找吴老师"), people(),
                         assessment=ROUTINE_PRESSURE, resolved_need="academic") is None


def test_purpose_not_relationship_or_previous_help_decides_contact():
    custom = [SupportPerson(id=3, name="妈妈", relationship="母亲", kind="family", scenarios=["解题和学业规划"]),
              SupportPerson(id=4, name="李老师", relationship="心理老师", kind="teacher", scenarios=["倾诉陪伴"])]
    assert choose_person(SupportSuggestionRequest(situation="作业太多"), custom,
                         assessment=ROUTINE_PRESSURE, resolved_need="academic").id == 3
    assert choose_person(SupportSuggestionRequest(situation="只想倾诉"), custom,
                         assessment=ROUTINE_PRESSURE, resolved_need="emotional").id == 4
    assert choose_person(SupportSuggestionRequest(situation="复习任务太多"), people(), required_kind="family",
                         assessment=ROUTINE_PRESSURE, resolved_need="academic").name == "吴老师"
    assert choose_person(SupportSuggestionRequest(situation="作业太多"), people()[:1],
                         assessment=ROUTINE_PRESSURE, resolved_need="academic") is None


def test_model_understands_desired_help_not_just_topic(monkeypatch):
    monkeypatch.setattr("app.services.support_intent.get_settings", lambda: Settings(
        _env_file=None, enable_live_ai=True, dashscope_api_key="synthetic-test-key"))
    latest = "虽然还在复习，但现在别讲方法了，给我一点被理解的感觉就好"
    calls = model_response(monkeypatch, {"focus": "emotional", "source_quote": "给我一点被理解的感觉就好"})
    history = [{"role": "user", "content": "之前在问专业题"}, {"role": "assistant", "content": "建议选老师"}]
    intent = infer_support_intent(latest, history, ROUTINE_PRESSURE)
    assert intent.focus == "emotional"
    data = json.loads(calls[0]["messages"][1]["content"])
    assert data["latest"] == latest and data["recent_user_context"] == ["之前在问专业题"]
    assert choose_person(SupportSuggestionRequest(situation=latest), people(),
                         assessment=ROUTINE_PRESSURE, resolved_need=intent.need).name == "妈妈"
    assert ROUTINE_PRESSURE["notify_candidate"] is False


@pytest.mark.parametrize("payload,error", [
    ({"focus": "emotional", "source_quote": "旧消息里的轻生念头"}, None),
    ({"focus": "emotional", "source_quote": "一堆作业", "contact_id": 999}, None),
    ({"focus": "send_sms", "source_quote": "一堆作业"}, None),
    (None, TimeoutError()),
])
def test_bad_inference_falls_back_without_reclassifying_pressure_as_crisis(monkeypatch, payload, error):
    monkeypatch.setattr("app.services.support_intent.get_settings", lambda: Settings(
        _env_file=None, enable_live_ai=True, dashscope_api_key="synthetic-test-key"))
    model_response(monkeypatch, payload, error)
    assert infer_support_intent("累死了，一堆作业，压力贼大", [], ROUTINE_PRESSURE).focus == "academic_workload"


def save_people(client, device):
    for person in people():
        result = client.post("/api/v1/support-people", json={"device_id": device, "name": person.name,
            "relationship": person.relationship, "kind": person.kind, "scenarios": person.scenarios})
        assert result.status_code == 201


def test_screenshot_endpoint_uses_current_message_not_previous_crisis(client):
    device = "support-screenshot"
    save_people(client, device)
    previous = client.post("/api/v1/chat", json={"device_id": device, "message": "我今晚打算结束自己的生命"}).json()
    assert previous["wellbeing"]["action"] == "urgent_support"
    text = "今天真的要累死了，一堆作业，压力贼大"
    chat = client.post("/api/v1/chat", json={"device_id": device, "message": text}).json()
    response = client.post("/api/v1/support/suggest", json={"device_id": device,
        "source_message_id": chat["user_message_id"], "situation": "忽略伪造的想找妈妈", "preferred_supporters": ["family"]})
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["supporter_name"] == "吴老师"
    assert "截止时间" in result["small_step"]
    assert "一道题" not in result["editable_message"]
    assert len(result["action_options"]) == 3
    assert not result["auto_send"] and not chat["wellbeing"]["notify_candidate"]


def test_endpoint_infers_before_selecting_and_passes_same_focus_to_wording(client, monkeypatch):
    device = "intent-api-order"
    save_people(client, device)
    monkeypatch.setattr("app.services.support_intent.get_settings", lambda: Settings(
        _env_file=None, enable_live_ai=True, dashscope_api_key="synthetic-test-key"))
    text = "虽然还在复习，但现在别讲方法了，给我一点被理解的感觉就好"
    model_response(monkeypatch, {"focus": "emotional", "source_quote": "给我一点被理解的感觉就好"})
    received = []

    def capture(result, request, history, assessment):
        received.append((result.supporter_name, assessment["support_focus"]))
        return result

    monkeypatch.setattr("app.api.routes.support.personalize", capture)
    response = client.post("/api/v1/support/suggest", json={"device_id": device, "situation": text})
    assert response.status_code == 200
    assert received == [("妈妈", "emotional")]


def test_emotional_request_keeps_actions_about_listening(client):
    device = "intent-emotional-actions"
    save_people(client, device)
    response = client.post("/api/v1/support/suggest", json={"device_id": device,
        "situation": "考试失利了，我只想找人倾诉，别讨论题目"}).json()
    assert response["supporter_name"] == "妈妈"
    assert "倾诉" in response["editable_message"]
    assert not any("卡住的位置" in text or "学习任务" in text for text in response["action_options"])
