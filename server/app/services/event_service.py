import json
import logging
from datetime import UTC, datetime
from typing import Literal

from openai import OpenAI
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import DailySummary, GrowthEvent, Memory, Message, UserRecord
from app.db.session import engine_for_url
from app.services.memory_service import local_day, set_memory_embedding
from app.services.check_in_service import evaluate_check_in

logger = logging.getLogger(__name__)
PROMPT_VERSION = "p1.1"


class EventCandidate(BaseModel):
    fact: str = Field(max_length=500)
    feeling: str | None = None
    attempt: str | None = None
    own_effort: str | None = None
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
            {"role": "system", "content": "从用户原话提取一件真实成长事件，仅输出 JSON 对象，不得补造事实。没有具体事件时 fact 为空字符串。字段及类型必须严格如下：fact、feeling、attempt、own_effort、support_received 是描述原话的字符串，后四项允许 null；own_effort 只写用户明确做过的尝试，support_received 只写明确已经收到的帮助，不把建议当成事实；people 是字符串数组；confidence、long_term_value、growth_significance、specificity、future_reuse、support_value 是 0 到 1 的数字；sensitivity 是 low、medium、high 之一；is_conflicting 是布尔值。仅对 long_term_value、growth_significance、specificity、future_reuse、support_value 这五项评分。涉及健康、关系冲突、隐私时 sensitivity 为 medium 或 high；与已有记忆矛盾时 is_conflicting 为 true。所有字段都必须出现。"},
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
                feeling=candidate.feeling, attempt=candidate.attempt, own_effort=candidate.own_effort or candidate.attempt,
                support_received=candidate.support_received,
                people=candidate.people, confidence=candidate.confidence, value_score=score,
                sensitivity=candidate.sensitivity, memory_decision=outcome,
                model=settings.extraction_model, prompt_version=PROMPT_VERSION,
            )
            db.add(event)
            db.flush()
            if outcome in ("long_term", "confirm"):
                memory = Memory(
                    conversation_id=conversation_id, content=candidate.fact.strip(),
                    source_message_ids=[user_message_id], event_id=event.id,
                    status="active" if outcome == "long_term" else "pending",
                    value_score=score, sensitivity=candidate.sensitivity, is_user_edited=False,
                    model=settings.extraction_model, prompt_version=PROMPT_VERSION,
                )
                set_memory_embedding(memory)
                db.add(memory)
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
            evaluate_check_in(db, conversation_id)
            db.commit()
    except Exception:
        logger.exception("P1 event extraction failed for source message %s", user_message_id)


def process_record(database_url: str, conversation_id: int, record_id: int) -> None:
    """Apply the established event and memory rules to a saved life record."""
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return
    try:
        with Session(engine_for_url(database_url)) as db:
            record = db.get(UserRecord, record_id)
            if not record or record.conversation_id != conversation_id or record.status != "saved":
                return
            event = db.scalar(select(GrowthEvent).where(GrowthEvent.source_record_id == record.id))
            if event is None:
                return
            text = "\n".join(part.strip() for part in (
                record.text,
                record.photo_comment,
                record.ai_description or "",
            ) if part and part.strip())
            if not text:
                return
            existing_contents = list(db.scalars(select(Memory.content).where(
                Memory.conversation_id == conversation_id,
                Memory.status == "active",
            )))
            candidate = extract_candidate(text, existing_contents)
            score, outcome = decision(candidate)
            if not candidate.fact.strip():
                return

            event.fact = candidate.fact.strip()
            event.feeling = candidate.feeling
            event.attempt = candidate.attempt
            event.own_effort = candidate.own_effort or candidate.attempt
            event.support_received = candidate.support_received
            event.people = candidate.people
            event.confidence = candidate.confidence
            event.value_score = score
            event.sensitivity = candidate.sensitivity
            event.memory_decision = "record"
            event.model = settings.extraction_model
            event.prompt_version = PROMPT_VERSION

            existing_memory = db.scalar(select(Memory).where(Memory.event_id == event.id))
            if existing_memory is not None and not existing_memory.is_user_edited:
                db.delete(existing_memory)
            evaluate_check_in(db, conversation_id)
            db.commit()
    except Exception:
        logger.exception("Record event extraction failed for source record %s", record_id)
