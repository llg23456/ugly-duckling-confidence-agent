"""Read-only review/video materials. Raw expressions never become growth events.

Positive IDs keep the existing event API contract. Negative IDs identify a raw
message, saved record or feedback with no extracted event (-10 * id - kind).
They are resolved within the owning conversation again before video generation.
"""
from dataclasses import dataclass, field
from datetime import datetime
from hashlib import sha256
import json

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, Message, SupportFeedback, UserRecord, WellbeingSignal


@dataclass
class ReviewMaterial:
    id: int
    conversation_id: int
    fact: str
    created_at: datetime
    source_user_message_id: int | None = None
    source_record_id: int | None = None
    source_feedback_id: int | None = None
    source_type: str | None = None
    feeling: str | None = None
    attempt: str | None = None
    own_effort: str | None = None
    support_received: str | None = None
    sensitivity: str | None = "low"
    people: list = field(default_factory=list)
    confidence: float | None = None
    value_score: float | None = None
    memory_decision: str | None = None
    source_excerpt: str = ""
    safety_context: dict = field(default_factory=dict)


def review_materials(db: Session, conversation_id: int, *, include_shadowed_raw=False) -> list[ReviewMaterial]:
    events = list(db.scalars(select(GrowthEvent).where(GrowthEvent.conversation_id == conversation_id)))
    events = [e for e in events if e.source_type != "stress_scenario"]
    by_source = {}
    for event in events:
        for kind, source_id in ((1, event.source_user_message_id), (2, event.source_record_id), (3, event.source_feedback_id)):
            if source_id:
                by_source[(kind, source_id)] = event
    assessments = {(s.source_kind, s.source_id): s.assessment for s in db.scalars(select(WellbeingSignal).where(
        WellbeingSignal.conversation_id == conversation_id))}
    sensitive = {key for key, value in assessments.items() if value.get("action") in ("urgent_support", "clarify")}
    result = []
    covered = set()

    def add(kind, row, raw, label, **kwargs):
        event = by_source.get((kind, row.id))
        values = dict(id=-10 * row.id - kind, conversation_id=conversation_id,
                      fact=label + raw[:240], created_at=row.created_at, source_excerpt=raw,
                      source_type={1: "chat_source", 2: "record_source", 3: "feedback_source"}[kind], **kwargs)
        assessment = assessments.get(({1: "message", 2: "record", 3: "feedback"}[kind], row.id), {})
        values["safety_context"] = {key: assessment[key] for key in ("category", "subject", "intent", "expression", "action") if key in assessment}
        if ({1: "message", 2: "record", 3: "feedback"}[kind], row.id) in sensitive:
            values["sensitivity"] = "high"
        if event:
            covered.add(event.id)
            copied = {key: getattr(event, key) for key in ReviewMaterial.__dataclass_fields__ if hasattr(event, key)}
            copied["source_excerpt"] = raw
            copied["safety_context"] = values["safety_context"]
            copied["people"] = copied.get("people") or []
            if values.get("sensitivity") == "high":
                copied["sensitivity"] = "high"
            result.append(ReviewMaterial(**copied))
        if not event or include_shadowed_raw:
            result.append(ReviewMaterial(**values))

    for message in db.scalars(select(Message).where(Message.conversation_id == conversation_id, Message.role == "user")):
        raw = message.content
        if message.modality == "image":
            raw = "分享了图片，留言：" + raw + "\n[图片观察，AI识别待核实] " + (message.image_observation or "具体内容尚未可靠识别")
        add(1, message, raw, "聊天中提到：", source_user_message_id=message.id)
    for record in db.scalars(select(UserRecord).where(UserRecord.conversation_id == conversation_id, UserRecord.status == "saved")):
        raw = "\n".join(v for v in (record.text, record.photo_comment) if v)
        if record.ai_description:
            raw += "\n[图片观察，AI识别待核实] " + record.ai_description
        add(2, record, raw or "留下了一份生活记录", "记录中写道：", source_record_id=record.id)
    for feedback in db.scalars(select(SupportFeedback).where(SupportFeedback.conversation_id == conversation_id)):
        outcome = {"helped": "这次求助帮到了我", "not_helped": "这次求助没有帮到", "not_contacted": "还没有联系对方"}.get(feedback.outcome, "留下求助反馈")
        raw = "；".join(filter(None, (outcome, feedback.own_effort, feedback.support_received)))
        add(3, feedback, raw, "反馈：", source_feedback_id=feedback.id,
            own_effort=feedback.own_effort, support_received=feedback.support_received)
    for event in events:
        if event.id not in covered:
            values = {key: getattr(event, key) for key in ReviewMaterial.__dataclass_fields__ if hasattr(event, key)}
            values["people"] = values.get("people") or []
            result.append(ReviewMaterial(**values))
    return sorted(result, key=lambda m: (m.created_at.isoformat(), m.id))


def material_fingerprint(materials) -> str:
    rows = [{"id": m.id, "fact": m.fact, "source": getattr(m, "source_excerpt", ""),
             "safety_context": getattr(m, "safety_context", {}),
             "feeling": m.feeling, "attempt": m.attempt, "effort": m.own_effort,
             "support": m.support_received, "created_at": m.created_at.isoformat()} for m in materials]
    return sha256(json.dumps(rows, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def selected_materials(db: Session, conversation_id: int, ids: list[int]):
    allowed = {m.id: m for m in review_materials(db, conversation_id, include_shadowed_raw=True)}
    return sorted([allowed[i] for i in ids if i in allowed], key=lambda m: (m.created_at.isoformat(), m.id))
