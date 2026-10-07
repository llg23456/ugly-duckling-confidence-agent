import json
from pathlib import Path

import pytest

from app.schemas import ChatRequest
from app.services.chat_service import _intent_for, _strategy_for, is_crisis_message, suppress_memory_for_message


CASES = json.loads((Path(__file__).parent / "fixtures" / "ai_dialogue_cases.json").read_text(encoding="utf-8"))


def test_quality_fixture_has_required_coverage() -> None:
    assert len(CASES) >= 40
    categories = {item["category"] for item in CASES}
    assert {"dialogue", "memory", "temporality", "correction", "control", "safety", "retrieval"}.issubset(categories)
    assert len({item["id"] for item in CASES}) == len(CASES)


@pytest.mark.parametrize("case_id,message,expected", [
    ("direct", "政治复习应该先做题还是先看课？", "direct_question"),
    ("emotion", "今天做题错了很多，我有点难受", "emotion"),
    ("correction", "不是北大，是北师大", "correction"),
    ("control", "这件事不要记", "memory_control"),
    ("reflection", "帮我复盘一下这周", "reflection"),
])
def test_deterministic_intent_baseline(case_id: str, message: str, expected: str) -> None:
    request = ChatRequest(device_id=case_id, message=message, mode="reflect" if expected == "reflection" else "listen")
    assert _intent_for(request) == expected


def test_safety_and_memory_control_gates() -> None:
    assert is_crisis_message("我现在不想活了") is True
    assert is_crisis_message("我今天只是有点累") is False
    assert suppress_memory_for_message("这件事不要记") is True
    assert suppress_memory_for_message("请记住我的目标") is False
    assert _strategy_for(ChatRequest(device_id="x", message="给我一个建议", mode="suggest")) == "small_step"
