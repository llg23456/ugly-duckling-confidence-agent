import hashlib
import json
import logging
import re
from datetime import UTC, datetime
from typing import Any, Literal

from openai import OpenAI
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import DailySummary, GrowthEvent, Memory, MemoryDeletion, Message, UserRecord
from app.db.session import engine_for_url
from app.services.check_in_service import evaluate_check_in
from app.services.memory_service import local_day, set_memory_embedding


logger = logging.getLogger(__name__)
PROMPT_VERSION = "memory-v2"
FORGET_PREVIOUS_TERMS = ("忘掉刚才", "忘记刚才", "别再记刚才")
NO_STORE_TERMS = ("不要记", "别记", "不用记", "别保存", "不要保存")
ACUTE_TERMS = ("想死", "不想活", "自杀", "结束生命", "伤害自己", "伤害别人", "杀了")


def _unit_score(value: Any) -> Any:
    if isinstance(value, str):
        qualitative = {
            "very_high": 1.0,
            "high": .9,
            "medium": .6,
            "moderate": .6,
            "low": .3,
            "very_low": .1,
        }
        if value.lower() in qualitative:
            return qualitative[value.lower()]
    try:
        number = float(value)
    except (TypeError, ValueError):
        return value
    if number > 1:
        number = number / (5 if number <= 5 else 10)
    return max(0.0, min(1.0, number))


def score_components(candidate: "EventCandidate") -> dict[str, float]:
    return {
        "long_term_value": candidate.long_term_value,
        "growth_significance": candidate.growth_significance,
        "specificity": candidate.specificity,
        "future_reuse": candidate.future_reuse,
        "support_value": candidate.support_value,
    }


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
    fact_status: Literal["asserted", "planned", "completed", "negated"] = "asserted"
    long_term_value: float = Field(ge=0, le=1)
    growth_significance: float = Field(ge=0, le=1)
    specificity: float = Field(ge=0, le=1)
    future_reuse: float = Field(ge=0, le=1)
    support_value: float = Field(ge=0, le=1)

    @field_validator("feeling", "attempt", "own_effort", "support_received", mode="before")
    @classmethod
    def normalize_optional_text(cls, value: Any) -> Any:
        if value is None:
            return None
        return value.strip() if isinstance(value, str) and value.strip() else None

    @field_validator("people", mode="before")
    @classmethod
    def normalize_people(cls, value: Any) -> Any:
        if value is None:
            return []
        if isinstance(value, str):
            return [value] if value.strip() else []
        return value

    @field_validator(
        "confidence", "long_term_value", "growth_significance", "specificity", "future_reuse", "support_value",
        mode="before",
    )
    @classmethod
    def normalize_scores(cls, value: Any) -> Any:
        return _unit_score(value)

    @field_validator("fact_status", mode="before")
    @classmethod
    def normalize_fact_status(cls, value: Any) -> Any:
        return {
            "current": "asserted",
            "ongoing": "asserted",
            "fact": "asserted",
            "done": "completed",
            "plan": "planned",
            "negative": "negated",
        }.get(value, value)

    @field_validator("sensitivity", mode="before")
    @classmethod
    def normalize_sensitivity(cls, value: Any) -> Any:
        if isinstance(value, (int, float)):
            return "low" if value <= .33 else "medium" if value <= .66 else "high"
        return {"moderate": "medium", "sensitive": "high"}.get(value, value)


class MemoryPointCandidate(BaseModel):
    content: str = Field(max_length=500)
    kind: Literal["identity", "preference", "goal", "ongoing_context", "support_person", "experience"]
    evidence_quote: str = Field(max_length=500)
    source_message_ids: list[int] = Field(default_factory=list, max_length=7)
    confidence: float = Field(ge=0, le=1)
    sensitivity: Literal["low", "medium", "high"]
    temporal_scope: Literal["stable", "ongoing", "dated", "one_off"]
    fact_status: Literal["asserted", "planned", "completed", "negated"]
    importance: float = Field(ge=0, le=1)
    future_reuse: float = Field(ge=0, le=1)
    canonical_key: str = Field(min_length=1, max_length=160)
    explicit_remember: bool = False
    explicit_correction: bool = False
    is_conflicting: bool = False
    supersedes_memory_id: int | None = None

    @field_validator("confidence", "importance", "future_reuse", mode="before")
    @classmethod
    def normalize_scores(cls, value: Any) -> Any:
        return _unit_score(value)

    @field_validator("kind", mode="before")
    @classmethod
    def normalize_kind(cls, value: Any) -> Any:
        return {
            "context": "ongoing_context",
            "support": "support_person",
            "relationship": "support_person",
            "event": "experience",
        }.get(value, value)

    @field_validator("temporal_scope", mode="before")
    @classmethod
    def normalize_temporal_scope(cls, value: Any) -> Any:
        return {
            "current": "ongoing",
            "recent": "ongoing",
            "permanent": "stable",
            "temporary": "dated",
            "event": "one_off",
            "long_term": "stable",
            "short_term": "dated",
            "current_session": "ongoing",
            "past": "dated",
            "past_event": "dated",
            "future": "ongoing",
            "specific": "dated",
        }.get(value, value)

    @field_validator("fact_status", mode="before")
    @classmethod
    def normalize_fact_status(cls, value: Any) -> Any:
        return {
            "current": "asserted",
            "ongoing": "asserted",
            "fact": "asserted",
            "done": "completed",
            "plan": "planned",
            "negative": "negated",
        }.get(value, value)

    @field_validator("sensitivity", mode="before")
    @classmethod
    def normalize_sensitivity(cls, value: Any) -> Any:
        if isinstance(value, (int, float)):
            return "low" if value <= .33 else "medium" if value <= .66 else "high"
        return {"moderate": "medium", "sensitive": "high"}.get(value, value)


class TurnExtraction(BaseModel):
    growth_event: EventCandidate | None = None
    memory_points: list[MemoryPointCandidate] = Field(default_factory=list, max_length=5)


def decision(candidate: EventCandidate) -> tuple[float, str]:
    score = round(
        .30 * candidate.long_term_value + .25 * candidate.growth_significance
        + .20 * candidate.specificity + .15 * candidate.future_reuse
        + .10 * candidate.support_value, 3
    )
    if not candidate.fact.strip() or candidate.fact_status in {"planned", "negated"}:
        return score, "ignore"
    if candidate.sensitivity != "low" or candidate.is_conflicting or candidate.confidence < .75:
        return score, "confirm"
    if score >= .72:
        return score, "long_term"
    if score >= .52:
        return score, "daily"
    return score, "ignore"


def memory_point_decision(candidate: MemoryPointCandidate) -> tuple[float, str]:
    score = round(.65 * candidate.importance + .35 * candidate.future_reuse, 3)
    if not candidate.content.strip() or not candidate.evidence_quote.strip() or candidate.fact_status == "negated":
        return score, "ignore"
    if candidate.kind == "experience" and candidate.fact_status == "planned":
        return score, "ignore"
    if (
        candidate.sensitivity != "low"
        or (candidate.is_conflicting and not candidate.explicit_correction)
        or candidate.confidence < .75
    ):
        return score, "confirm"
    threshold = .72 if candidate.kind == "experience" else .52
    if candidate.explicit_remember or score >= threshold:
        return score, "long_term"
    return score, "ignore"


def _existing_payload(existing: list[Any]) -> list[dict[str, Any]]:
    payload: list[dict[str, Any]] = []
    for item in existing[:30]:
        if isinstance(item, str):
            payload.append({"content": item})
        elif isinstance(item, dict):
            payload.append(item)
        else:
            payload.append({
                "id": getattr(item, "id", None),
                "content": getattr(item, "content", ""),
                "kind": getattr(item, "kind", None),
                "canonical_key": getattr(item, "canonical_key", None),
                "status": getattr(item, "status", None),
            })
    return payload


def extract_turn(
    text: str,
    existing: list[Any],
    context: list[dict[str, Any]] | None = None,
    current_message_id: int | None = None,
) -> TurnExtraction:
    settings = get_settings()
    client = OpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=35,
        max_retries=0,
    )
    schema_example = {
        "growth_event": {
            "fact": "用户明确完成的成长事件",
            "feeling": None,
            "attempt": None,
            "own_effort": None,
            "support_received": None,
            "people": [],
            "confidence": 0.9,
            "sensitivity": "low",
            "is_conflicting": False,
            "fact_status": "completed",
            "long_term_value": 0.7,
            "growth_significance": 0.7,
            "specificity": 0.8,
            "future_reuse": 0.6,
            "support_value": 0.0,
        },
        "memory_points": [{
            "content": "一个原子事实",
            "kind": "experience",
            "evidence_quote": "用户原话",
            "source_message_ids": [current_message_id or 0],
            "confidence": 0.9,
            "sensitivity": "low",
            "temporal_scope": "dated",
            "fact_status": "completed",
            "importance": 0.7,
            "future_reuse": 0.6,
            "canonical_key": "experience:topic",
            "explicit_remember": False,
            "explicit_correction": False,
            "is_conflicting": False,
            "supersedes_memory_id": None,
        }],
    }
    system = """你负责分析一轮用户对话，输出一个 JSON 对象，包含 growth_event 和 memory_points。
growth_event 最多一个：把本轮明确发生的成长行为合并为一个事件；纯计划、否定、闲聊或没有具体事件时为 null。
memory_points 是 0 到 5 个原子记忆点，每项只能表达一个事实，类型只能是 identity、preference、goal、ongoing_context、support_person、experience。
不同类型或不同主题必须拆开，不能把“是大三学生”和“目标院校是北师大”合并为同一条；前者是 identity，后者是 goal。
即使类型相同，不同字段也要拆开：例如“我叫小李，现在大三”应生成 identity:preferred_name 和 identity:life_stage 两条。
只允许把 role=user 的原话当作事实证据。助手回复只用于理解指代，绝不能把助手建议写成用户已经完成的行为。
必须区分 fact_status：asserted、planned、completed、negated。明天准备做属于 planned；“没有联系老师”属于 negated，不能改写为已获得帮助。
evidence_quote 必须直接摘自某条用户消息；source_message_ids 只能填写相应用户消息 ID。
canonical_key 使用稳定的英文类别键，例如 goal:target_school、preference:support_style；同一主题纠正前后使用相同键。
明确说“记住”时 explicit_remember=true；明确以“不是A，是B”等方式改正时 explicit_correction=true，并尽量填写 supersedes_memory_id。
普通冲突设置 is_conflicting=true，不自行覆盖。涉及健康、亲密关系冲突、身份隐私等内容设为 medium 或 high。
growth_event 字段必须符合既有 EventCandidate：fact、feeling、attempt、own_effort、support_received、people、confidence、sensitivity、is_conflicting、fact_status，以及五个 0 到 1 的评分。
memory_points 每项必须包含全部字段：content、kind、evidence_quote、source_message_ids、confidence、sensitivity、temporal_scope、fact_status、importance、future_reuse、canonical_key、explicit_remember、explicit_correction、is_conflicting、supersedes_memory_id。
不要输出解释或 Markdown。没有成长事件时 growth_event 必须为 null，不得改用 type、description 等其他字段。
必须严格沿用下面示例的键名和数据类型，示例值仅用于说明格式：
""" + json.dumps(schema_example, ensure_ascii=False)
    payload = {
        "current_message_id": current_message_id,
        "current_message": text,
        "context": context or [{"id": current_message_id, "role": "user", "content": text}],
        "existing_memories": _existing_payload(existing),
        "prompt_version": PROMPT_VERSION,
    }
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
    ]
    kwargs = dict(
        model=settings.extraction_model,
        messages=messages,
        response_format={"type": "json_object"},
        temperature=0,
        reasoning_effort="none",
        max_tokens=1800,
    )
    response = client.chat.completions.create(**kwargs)
    raw = response.choices[0].message.content or ""
    try:
        return TurnExtraction.model_validate_json(raw)
    except Exception as exc:
        repair = client.chat.completions.create(**{
            **kwargs,
            "messages": [
                *messages,
                {"role": "assistant", "content": raw},
                {
                    "role": "user",
                    "content": (
                        "上一条 JSON 不符合固定契约。请只修复结构和字段类型，不增加事实；"
                        "growth_event 只能为 null 或使用示例中的全部键。校验错误："
                        + str(exc)[:1200]
                    ),
                },
            ],
        })
        return TurnExtraction.model_validate_json(repair.choices[0].message.content or "")


def extract_candidate(text: str, existing: list[str]) -> EventCandidate:
    """Compatibility wrapper for the public single-event endpoint and saved records."""
    extracted = extract_turn(text, existing)
    return extracted.growth_event or EventCandidate(
        fact="",
        confidence=1.0,
        sensitivity="low",
        long_term_value=0,
        growth_significance=0,
        specificity=0,
        future_reuse=0,
        support_value=0,
    )


def _normalized(value: str) -> str:
    return re.sub(r"[\W_]+", "", value, flags=re.UNICODE).lower()


def _content_similarity(left: str, right: str) -> float:
    left_terms = set(re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]{2}", left.lower()))
    right_terms = set(re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]{2}", right.lower()))
    if _normalized(left) == _normalized(right):
        return 1.0
    if not left_terms or not right_terms:
        return 0.0
    return len(left_terms & right_terms) / max(1, len(left_terms | right_terms))


def _quote_supported(point: MemoryPointCandidate, user_messages: dict[int, str]) -> bool:
    quote = _normalized(point.evidence_quote)
    if not quote:
        return False
    candidate_ids = point.source_message_ids or list(user_messages)
    return any(
        message_id in user_messages and quote in _normalized(user_messages[message_id])
        for message_id in candidate_ids
    )


def _source_fingerprint(message_id: int, point: MemoryPointCandidate) -> str:
    raw = f"{message_id}|{point.canonical_key}|{_normalized(point.content)}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _forget_previous(db: Session, conversation_id: int, current_message_id: int) -> None:
    previous = db.scalar(select(Message).where(
        Message.conversation_id == conversation_id,
        Message.role == "user",
        Message.id < current_message_id,
    ).order_by(Message.id.desc()))
    if previous is None:
        return
    memories = list(db.scalars(select(Memory).where(
        Memory.conversation_id == conversation_id,
        Memory.status.in_(("active", "pending")),
    )))
    for memory in memories:
        if previous.id not in (memory.source_message_ids or []):
            continue
        for dependent in db.scalars(select(Memory).where(
            Memory.conversation_id == conversation_id,
            Memory.supersedes_id == memory.id,
        )):
            dependent.supersedes_id = None
        if not db.scalar(select(MemoryDeletion.id).where(MemoryDeletion.deleted_memory_id == memory.id)):
            db.add(MemoryDeletion(
                conversation_id=conversation_id,
                deleted_memory_id=memory.id,
                source_message_ids=memory.source_message_ids or [],
            ))
        db.delete(memory)


def _upsert_memory_point(
    db: Session,
    *,
    conversation_id: int,
    source_message_id: int,
    point: MemoryPointCandidate,
    known_memories: list[Memory],
    model: str,
) -> Memory | None:
    score, outcome = memory_point_decision(point)
    if outcome == "ignore":
        return None
    fingerprint = _source_fingerprint(source_message_id, point)
    duplicate_source = next((item for item in known_memories if item.source_fingerprint == fingerprint), None)
    if duplicate_source is not None:
        return duplicate_source

    active = [item for item in known_memories if item.status in ("active", "pending")]
    target = next((item for item in active if item.id == point.supersedes_memory_id), None)
    same_key = [
        item for item in active
        if item.canonical_key and item.canonical_key == point.canonical_key
    ]
    duplicate = next((
        item for item in active
        if _content_similarity(item.content, point.content) >= .82
        or (item in same_key and _normalized(item.content) == _normalized(point.content))
    ), None)
    now = datetime.now(UTC)
    if duplicate is not None:
        duplicate.source_message_ids = list(dict.fromkeys([
            *(duplicate.source_message_ids or []),
            *point.source_message_ids,
        ]))
        duplicate.source_excerpt = point.evidence_quote
        duplicate.last_seen_at = now
        duplicate.occurrence_count = (duplicate.occurrence_count or 1) + 1
        duplicate.confidence = max(duplicate.confidence or 0, point.confidence)
        duplicate.value_score = max(duplicate.value_score or 0, score)
        if not duplicate.kind:
            duplicate.kind = point.kind
        if not duplicate.canonical_key:
            duplicate.canonical_key = point.canonical_key
        return duplicate

    if target is None and same_key:
        target = max(same_key, key=lambda item: item.id)
    if target is not None and _normalized(target.content) != _normalized(point.content):
        if point.explicit_correction:
            if outcome == "long_term":
                target.status = "superseded"
                target.updated_at = now
        else:
            outcome = "confirm"

    memory = Memory(
        conversation_id=conversation_id,
        content=point.content.strip(),
        source_message_ids=point.source_message_ids,
        status="active" if outcome == "long_term" else "pending",
        value_score=score,
        sensitivity=point.sensitivity,
        is_user_edited=False,
        model=model,
        prompt_version=PROMPT_VERSION,
        kind=point.kind,
        confidence=point.confidence,
        canonical_key=point.canonical_key,
        source_excerpt=point.evidence_quote,
        temporal_scope=point.temporal_scope,
        fact_status=point.fact_status,
        source_fingerprint=fingerprint,
        last_seen_at=now,
        occurrence_count=1,
        supersedes_id=target.id if target is not None and point.explicit_correction else None,
    )
    set_memory_embedding(memory)
    db.add(memory)
    db.flush()
    known_memories.append(memory)
    return memory


def process_turn(database_url: str, conversation_id: int, user_message_id: int, assistant_message_id: int) -> None:
    """Run after reply persistence. Failure is logged and never changes the reply."""
    settings = get_settings()
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        return
    try:
        with Session(engine_for_url(database_url)) as db:
            user_message = db.get(Message, user_message_id)
            assistant_message = db.get(Message, assistant_message_id)
            if (
                not user_message or not assistant_message
                or user_message.conversation_id != conversation_id
                or assistant_message.conversation_id != conversation_id
            ):
                return
            compact = re.sub(r"\s+", "", user_message.content)
            if any(term in compact for term in ACUTE_TERMS):
                return
            if any(term in compact for term in FORGET_PREVIOUS_TERMS):
                _forget_previous(db, conversation_id, user_message_id)
                db.commit()
                return
            if any(term in compact for term in NO_STORE_TERMS):
                return

            context_rows = list(db.scalars(select(Message).where(
                Message.conversation_id == conversation_id,
                Message.id <= user_message_id,
            ).order_by(Message.id.desc()).limit(7)))
            context_rows.reverse()
            context = [
                {"id": item.id, "role": item.role, "content": item.content, "modality": item.modality}
                for item in context_rows
            ]
            user_context = {item.id: item.content for item in context_rows if item.role == "user"}
            known_memories = list(db.scalars(select(Memory).where(
                Memory.conversation_id == conversation_id,
                Memory.status.in_(("active", "pending")),
            ).order_by(Memory.id.desc()).limit(100)))
            extracted = extract_turn(
                user_message.content,
                known_memories,
                context=context,
                current_message_id=user_message_id,
            )

            event = db.scalar(select(GrowthEvent).where(
                GrowthEvent.source_user_message_id == user_message_id,
            ))
            event_candidate = extracted.growth_event
            event_outcome = "ignore"
            event_score = 0.0
            if event_candidate is not None:
                event_score, event_outcome = decision(event_candidate)
            if event is None and event_candidate is not None and event_outcome != "ignore":
                event = GrowthEvent(
                    conversation_id=conversation_id,
                    fact=event_candidate.fact.strip(),
                    source_message_ids=[user_message_id, assistant_message_id],
                    source_user_message_id=user_message_id,
                    source_type={"text": "chat", "image": "photo", "audio": "voice"}.get(user_message.modality, "chat"),
                    feeling=event_candidate.feeling,
                    attempt=event_candidate.attempt,
                    own_effort=event_candidate.own_effort or event_candidate.attempt,
                    support_received=event_candidate.support_received,
                    people=event_candidate.people,
                    confidence=event_candidate.confidence,
                    value_score=event_score,
                    score_components=score_components(event_candidate),
                    sensitivity=event_candidate.sensitivity,
                    memory_decision=event_outcome,
                    model=settings.extraction_model,
                    prompt_version=PROMPT_VERSION,
                )
                db.add(event)
                db.flush()

            points = list(extracted.memory_points)
            if not points and event_candidate is not None and event_outcome in ("long_term", "confirm"):
                points = [MemoryPointCandidate(
                    content=event_candidate.fact,
                    kind="experience",
                    evidence_quote=user_message.content,
                    source_message_ids=[user_message_id],
                    confidence=event_candidate.confidence,
                    sensitivity=event_candidate.sensitivity,
                    temporal_scope="dated",
                    fact_status=event_candidate.fact_status,
                    importance=event_score,
                    future_reuse=event_candidate.future_reuse,
                    canonical_key=f"experience:{user_message_id}",
                    is_conflicting=event_candidate.is_conflicting,
                )]

            for point in points[:5]:
                point.source_message_ids = [
                    item for item in point.source_message_ids if item in user_context
                ] or [user_message_id]
                if not _quote_supported(point, user_context):
                    logger.warning("Discarded unsupported memory point for message %s", user_message_id)
                    continue
                _upsert_memory_point(
                    db,
                    conversation_id=conversation_id,
                    source_message_id=user_message_id,
                    point=point,
                    known_memories=known_memories,
                    model=settings.extraction_model,
                )

            if event is not None and event_outcome == "daily":
                day = local_day(user_message.created_at)
                summary = db.scalar(select(DailySummary).where(
                    DailySummary.conversation_id == conversation_id,
                    DailySummary.day == day,
                ))
                if summary is None:
                    db.add(DailySummary(
                        conversation_id=conversation_id,
                        day=day,
                        content=event.fact,
                        source_event_ids=[event.id],
                        status="draft",
                    ))
                elif event.id not in (summary.source_event_ids or []):
                    summary.content += "\n" + event.fact
                    summary.source_event_ids = [*(summary.source_event_ids or []), event.id]
                    summary.updated_at = user_message.created_at
            evaluate_check_in(db, conversation_id)
            db.commit()
    except Exception:
        logger.exception("Memory-v2 extraction failed for source message %s", user_message_id)


def process_record(database_url: str, conversation_id: int, record_id: int) -> None:
    """Apply the established event rules to a saved life record."""
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
            score, _outcome = decision(candidate)
            if not candidate.fact.strip() or candidate.fact_status in {"planned", "negated"}:
                return

            event.fact = candidate.fact.strip()
            event.feeling = candidate.feeling
            event.attempt = candidate.attempt
            event.own_effort = candidate.own_effort or candidate.attempt
            event.support_received = candidate.support_received
            event.people = candidate.people
            event.confidence = candidate.confidence
            event.value_score = score
            event.score_components = score_components(candidate)
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
