from types import SimpleNamespace

import pytest

from app.core.config import Settings
from app.services import review_service
from test_review_sources import seed_message, day_review


def fake_model(monkeypatch, content):
    class Client:
        def __init__(self, **kwargs):
            self.chat = SimpleNamespace(completions=self)
        def create(self, **kwargs):
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])
        def close(self):
            pass
    monkeypatch.setattr(review_service, "OpenAI", Client)
    monkeypatch.setattr(review_service, "get_settings", lambda: Settings(
        _env_file=None, enable_live_ai=True, dashscope_api_key="test-only"))


def test_day_uses_one_sentence_and_keeps_original_materials(client, monkeypatch):
    a = seed_message(text="今天考试很难，我很烦")
    b = seed_message(text="我把老师的反馈截图发来了", modality="image", observation="截图写着结论不够明确")
    fake_model(monkeypatch, '{"summary":"今天聊到了考试带来的压力，并分享了老师的反馈截图。"}')
    result = day_review(client)
    assert result["one_sentence"] == "今天聊到了考试带来的压力，并分享了老师的反馈截图。"
    assert {a, b}.issubset({m["source_id"] for m in result["moments"]})


@pytest.mark.parametrize("content", ['{"summary":"第一句。第二句。"}', '{"summary":"' + "长" * 100 + '"}'])
def test_long_or_multi_sentence_model_output_falls_back_to_concise_source(client, monkeypatch, content):
    seed_message(text="今天练习了三道数学题。然后打算明天继续，还没有开始。")
    fake_model(monkeypatch, content)
    result = day_review(client)
    assert len(result["one_sentence"]) <= 72 and result["one_sentence"].count("。") == 1
    assert "今天练习了三道数学题" in result["one_sentence"]


def test_daily_summary_cannot_relabel_pressure_complaint_as_suicidal_intent(client, monkeypatch):
    seed_message(text="今天真的要累死了，玛德，学的快不想活了")
    fake_model(monkeypatch, '{"summary":"今天学习很累，甚至产生了不想活的念头。"}')
    result = day_review(client)
    assert "不想活" not in result["one_sentence"] and "念头" not in result["one_sentence"]
    assert "学习" in result["one_sentence"] and len(result["moments"]) == 1


def test_daily_summary_does_not_intensify_ordinary_complaint(client, monkeypatch):
    seed_message(text="今天学习很累，想休息一下")
    fake_model(monkeypatch, '{"summary":"今天学习后情绪崩溃，陷入绝望。"}')
    result = day_review(client)
    assert "崩溃" not in result["one_sentence"] and "绝望" not in result["one_sentence"]
    assert "休息" in result["one_sentence"]


def test_source_screening_change_invalidates_daily_summary_cache(client):
    from datetime import UTC, datetime
    from app.db.models import Message
    from app.services.review_materials import review_materials
    from app.services.wellbeing_service import ingest_source
    from app.services.wellbeing_assessment import assess
    from test_wellbeing import session
    text = "今天真的要累死了，玛德，学的快不想活了"
    mid = seed_message(text=text)
    before = day_review(client)
    with session() as db:
        message = db.get(Message, mid)
        ingest_source(db, message.conversation_id, "message", mid, text, datetime.now(UTC), assess(text, use_model=False))
        db.commit()
        material = review_materials(db, message.conversation_id)[0]
        assert material.safety_context["category"] == "severe_distress"
        assert material.safety_context["intent"] == "absent"
    assert day_review(client)["source_fingerprint"] != before["source_fingerprint"]
