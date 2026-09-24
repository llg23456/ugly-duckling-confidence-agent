import json
import logging
from typing import Literal

from openai import OpenAI
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import DailySummary, GrowthEvent, Memory, Message
from app.db.session import engine_for_url
from app.services.memory_service import local_day

logger = logging.getLogger(__name__)
PROMPT_VERSION = "p1.1"


class EventCandidate(BaseModel):
    fact: str = Field(max_length=500)
    feeling: str | None = None
    attempt: str | None = None
    support_received: str | None = None
    people: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0, le=1)
    sensitivity: Literal["low", "medium", "high"]
    is_conflicting: bool = False
    long_term_value: float = Field(ge=0, le=1)
    growth_significance: float = Field(ge=0, le=1)
    specificity: float = Field(ge=0, le=1)
    future_reuse: float = Field(ge=0, le=1)
    support_value: float = Field(ge=0, le=1)


def decision(candidate: EventCandidate) -> tuple[float, str]:
    score = round(
        .30 * candidate.long_term_value + .25 * candidate.growth_significance
        + .20 * candidate.specificity + .15 * candidate.future_reuse
        + .10 * candidate.support_value, 3
    )
    if not candidate.fact.strip():
        return score, "ignore"
    if candidate.sensitivity != "low" or candidate.is_conflicting or candidate.confidence < .75:
        return score, "confirm"
    if score >= .72:
        return score, "long_term"
    if score >= .52:
        return score, "daily"
    return score, "ignore"


def extract_candidate(text: str, existing: list[str]) -> EventCandidate:
    settings = get_settings()
    client = OpenAI(api_key=settings.dashscope_api_key, base_url=settings.dashscope_base_url, timeout=35, max_retries=0)
    response = client.chat.completions.create(
        model=settings.extraction_model,
        messages=[
            {"role": "system", "content": "从用户原话提取一件真实成长事件，仅输出 JSON 对象，不得补造事实。没有具体事件时 fact 为空字符串。字段及类型必须严格如下：fact、feeling、attempt、support_received 是描述原话的字符串，后三项允许 null；people 是字符串数组；confidence、long_term_value、growth_significance、specificity、future_reuse、support_value 是 0 到 1 的数字；sensitivity 是 low、medium、high 之一；is_conflicting 是布尔值。仅对 long_term_value、growth_significance、specificity、future_reuse、support_value 这五项评分。涉及健康、关系冲突、隐私时 sensitivity 为 medium 或 high；与已有记忆矛盾时 is_conflicting 为 true。所有字段都必须出现。"},
            {"role": "user", "content": json.dumps({"message": text, "existing_memories": existing[:10]}, ensure_ascii=False)},
        ],
        response_format={"type": "json_object"},
        temperature=0,
        reasoning_effort="none",
        max_tokens=550,
    )
    return EventCandidate.model_validate_json(response.choices[0].message.content or "")


def process_turn(database_url: str, conversation_id: int, user_message_id: int, assistant_message_id: int) -> None:
    """Run after reply persistence. Failure is logged and never changes the reply."""
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return
    try:
        with Session(engine_for_url(database_url)) as db:
            user_message = db.get(Message, user_message_id)
            assistant_message = db.get(Message, assistant_message_id)
            if not user_message or not assistant_message or user_message.conversation_id != conversation_id or assistant_message.conversation_id != conversation_id:
                return
            if db.scalar(select(GrowthEvent.id).where(GrowthEvent.source_user_message_id == user_message_id)):
                return
            existing = list(db.scalars(select(Memory.content).where(Memory.conversation_id == conversation_id, Memory.status == "active")))
            candidate = extract_candidate(user_message.content, existing)
            score, outcome = decision(candidate)
            if not candidate.fact.strip():
                return
            event = GrowthEvent(
                conversation_id=conversation_id, fact=candidate.fact, source_message_ids=[user_message_id, assistant_message_id],
                source_user_message_id=user_message_id,
                source_type={"text": "chat", "image": "photo", "audio": "voice"}.get(user_message.modality, "chat"),
                feeling=candidate.feeling, attempt=candidate.attempt, support_received=candidate.support_received,
                people=candidate.people, confidence=candidate.confidence, value_score=score,
                sensitivity=candidate.sensitivity, memory_decision=outcome,
                model=settings.extraction_model, prompt_version=PROMPT_VERSION,
            )
            db.add(event)
            db.flush()
            if outcome in ("long_term", "confirm"):
                db.add(Memory(
                    conversation_id=conversation_id, content=candidate.fact.strip(),
                    source_message_ids=[user_message_id], event_id=event.id,
                    status="active" if outcome == "long_term" else "pending",
                    value_score=score, sensitivity=candidate.sensitivity, is_user_edited=False,
                    model=settings.extraction_model, prompt_version=PROMPT_VERSION,
                ))
            elif outcome == "daily":
                day = local_day(user_message.created_at)
                summary = db.scalar(select(DailySummary).where(
                    DailySummary.conversation_id == conversation_id, DailySummary.day == day,
                ))
                if summary is None:
                    db.add(DailySummary(
                        conversation_id=conversation_id, day=day, content=candidate.fact.strip(),
                        source_event_ids=[event.id], status="draft",
                    ))
                else:
                    summary.content += "\n" + candidate.fact.strip()
                    summary.source_event_ids = [*(summary.source_event_ids or []), event.id]
                    summary.updated_at = user_message.created_at
            db.commit()
    except Exception:
        logger.exception("P1 event extraction failed for source message %s", user_message_id)
