from importlib import import_module
from types import SimpleNamespace
import sys
import json

from fastapi.testclient import TestClient
from sqlalchemy import inspect

from app.core.config import Settings
from app.db.session import engine_for_url
from app.schemas import ChatRequest, ChatResponse, MultimodalChatResponse
from app.schemas.chat import MemoryEvidence
from app.services.chat_service import _live_chat


def test_database_initializes_p0_tables(tmp_path) -> None:
    engine = engine_for_url(f"sqlite:///{tmp_path / 'fresh.db'}")
    assert {"conversations", "messages", "growth_events", "memories", "support_people", "reviews"}.issubset(
        set(inspect(engine).get_table_names())
    )


def test_live_chat_sends_history_to_model(monkeypatch) -> None:
    captured = {}

    class FakeOpenAI:
        def __init__(self, **_kwargs):
            self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

        def create(self, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps({
                "reply": "先把最卡的一题标出来，再决定下一步。",
                "intent": "direct_question",
                "strategy": "listen",
                "topic": "学习下一步",
                "used_memory_ids": [],
                "used_record_ids": [],
            }, ensure_ascii=False)))])

    monkeypatch.setitem(sys.modules, "openai", SimpleNamespace(OpenAI=FakeOpenAI))
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="test-key")
    response = _live_chat(
        ChatRequest(device_id="test", message="那下一步呢？"),
        settings,
        [{"role": "user", "content": "我练了开场"}, {"role": "assistant", "content": "先试十秒"}],
    )
    assert response.mock is False
    assert [item["role"] for item in captured["messages"]] == ["system", "user", "assistant", "user"]
    assert captured["messages"][-1]["content"] == "那下一步呢？"
    assert captured["response_format"] == {"type": "json_object"}
    assert "strategy=listen" in captured["messages"][0]["content"]
    assert response.reply.startswith("先把")


def test_live_chat_returns_only_evidence_used_by_model(monkeypatch) -> None:
    class FakeOpenAI:
        def __init__(self, **_kwargs):
            self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

        def create(self, **_kwargs):
            content = json.dumps({
                "reply": "你之前说过数学容易卡住，可以先从那道错题开始。",
                "intent": "direct_question",
                "strategy": "listen",
                "topic": "数学复习",
                "used_memory_ids": [2, 999],
                "used_record_ids": [],
            }, ensure_ascii=False)
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])

    monkeypatch.setitem(sys.modules, "openai", SimpleNamespace(OpenAI=FakeOpenAI))
    settings = Settings(_env_file=None, enable_live_ai=True, dashscope_api_key="test-key")
    evidence = [
        MemoryEvidence(summary="目标院校是北师大", source_date="2026-10-01", source_type="chat", source_id=1, memory_id=1),
        MemoryEvidence(summary="数学复习容易卡住", source_date="2026-10-02", source_type="chat", source_id=2, memory_id=2),
    ]
    response = _live_chat(ChatRequest(device_id="test", message="数学怎么继续？"), settings, evidence=evidence)
    assert [item.memory_id for item in response.evidence] == [2]


def test_conversation_persists_and_uses_only_last_12_messages(client: TestClient, monkeypatch) -> None:
    chat_route = import_module("app.api.routes.chat")
    received_history = []

    def fake_chat(request, history=None, evidence=None):
        received_history.append(history)
        return ChatResponse(reply=f"reply {request.message}", strategy="listen", mock=False)

    monkeypatch.setattr(chat_route, "chat_with_fallback", fake_chat)
    for number in range(14):
        response = client.post(
            "/api/v1/chat",
            json={"device_id": "first-device", "message": f"turn {number}"},
        )
        assert response.status_code == 200
        assert response.json()["user_message_id"] is not None
        assert response.json()["assistant_message_id"] is not None

    assert len(received_history[-1]) == 12
    assert received_history[-1][0] == {"role": "user", "content": "turn 7"}
    assert received_history[-1][-1] == {"role": "assistant", "content": "reply turn 12"}

    history = client.get("/api/v1/conversations/first-device/messages")
    assert history.status_code == 200
    messages = history.json()["messages"]
    assert len(messages) == 28
    assert messages[0]["content"] == "turn 0"
    assert messages[-1]["content"] == "reply turn 13"
    assert messages[0]["created_at"].endswith("Z")
    assert client.get("/api/v1/conversations/other-device/messages").json()["messages"] == []


def test_image_and_audio_share_conversation_and_keep_source_refs(client: TestClient, monkeypatch) -> None:
    multimodal_route = import_module("app.api.routes.multimodal")
    observed_history = []

    def fake_image(_content, _mime, prompt, history=None, evidence=None):
        observed_history.append(history)
        return MultimodalChatResponse(modality="image", user_text=prompt, reply="图片里有一本书", model="test")

    def fake_audio(_content, _mime, _format, _device_id, history=None, recall_for_text=None):
        observed_history.append(history)
        return MultimodalChatResponse(modality="audio", user_text="我读完了", reply="你读完了这本书", model="test")

    monkeypatch.setattr(multimodal_route, "chat_with_image", fake_image)
    monkeypatch.setattr(multimodal_route, "chat_with_audio", fake_audio)
    image = client.post(
        "/api/v1/multimodal/image",
        data={"prompt": "看看这张图", "device_id": "mixed-device"},
        files={"file": ("photo.png", b"fake-png", "image/png")},
    )
    assert image.status_code == 200
    audio = client.post(
        "/api/v1/multimodal/audio",
        data={"device_id": "mixed-device"},
        files={"file": ("voice.wav", b"fake-wav", "audio/wav")},
    )
    assert audio.status_code == 200
    assert observed_history[0] == []
    assert observed_history[1][-1] == {"role": "assistant", "content": "[image] 图片里有一本书"}
    messages = client.get("/api/v1/conversations/mixed-device/messages").json()["messages"]
    assert [message["modality"] for message in messages] == ["image", "image", "audio", "audio"]
    assert messages[0]["content"] == "看看这张图"
    assert messages[0]["media_ref"].startswith("sha256:")
    assert messages[2]["content"] == "我读完了"
    assert messages[2]["media_ref"].startswith("sha256:")
