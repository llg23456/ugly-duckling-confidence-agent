from datetime import UTC, datetime, timedelta, timezone
from hashlib import sha256

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.db.models import DailyCheckIn, DailyStressSnapshot, NotificationOutbox, WellbeingPreference, WellbeingSignal
from app.services.wellbeing_assessment import PROMPT_VERSION, Assessment, assess, support_action

LOCAL_ZONE = timezone(timedelta(hours=8))
CONSENT_VERSION = "trusted-contact-adult-v1"
SCORE_VERSION = "daily-pressure-v2"


def day_of(value: datetime | None = None) -> str:
    value = value or datetime.now(UTC)
    return value.replace(tzinfo=value.tzinfo or UTC).astimezone(LOCAL_ZONE).date().isoformat()


def preferences(db: Session, conversation_id: int) -> WellbeingPreference:
    value = db.scalar(select(WellbeingPreference).where(WellbeingPreference.conversation_id == conversation_id))
    if value is None:
        value = WellbeingPreference(conversation_id=conversation_id, reminder_threshold=70, self_weight=70, auto_notify=False)
        db.add(value)
        db.flush()
    return value


def refresh_day(db: Session, conversation_id: int, day: str) -> dict:
    pref = preferences(db, conversation_id)
    row = db.scalar(select(DailyStressSnapshot).where(
        DailyStressSnapshot.conversation_id == conversation_id, DailyStressSnapshot.day == day))
    weight = row.value.get("self_weight", pref.self_weight) if row and day != day_of() else pref.self_weight
    check = db.scalar(select(DailyCheckIn).where(DailyCheckIn.conversation_id == conversation_id, DailyCheckIn.day == day))
    signals = db.scalars(select(WellbeingSignal).where(
        WellbeingSignal.conversation_id == conversation_id, WellbeingSignal.day == day,
    ).order_by(WellbeingSignal.id.desc())).all()
    seen = set()
    samples = []
    for signal in signals:
        if signal.pressure is not None and signal.content_hash not in seen:
            samples.append(signal)
            seen.add(signal.content_hash)
        if len(samples) >= 12:
            break
    ai = sum(s.pressure for s in samples) / len(samples) if samples else None
    own = check.pressure if check else None
    combined = (own * weight / 100 + ai * (100 - weight) / 100
                if own is not None and ai is not None else own if own is not None else ai)
    score = min(100, max(0, int((combined + 2.5) // 5) * 5)) if combined is not None else None
    base_score = score
    # A daily reference preserves a strong expression for that day, even if the
    # immediate-support fast path skipped pressure estimation. This is a product
    # display floor, NOT a measured stress estimate or a suicide risk probability.
    # Ordinary subsequent messages cannot erase it; deleting its source can.
    attention = []
    for signal in signals:
        value = {key: val for key, val in signal.assessment.items() if key in Assessment.model_fields}
        if support_action(Assessment.model_validate(value)) == "urgent_support":
            attention.append(signal)
    if attention:
        score = max(score or 0, 90)
    result = {
        "date": day, "timezone": "Asia/Shanghai", "score": score,
        "base_score": base_score, "attention_floor": 90 if attention else None,
        "score_basis": "daily_strong_expression" if attention else "weighted",
        "attention_sources": [{"kind": s.source_kind, "id": s.source_id} for s in attention],
        "self_score": own, "mood": check.mood if check else None,
        "ai_score": round(ai, 1) if ai is not None else None, "self_weight": weight,
        "level": "unknown" if score is None else "calm" if score < 40 else "loaded" if score < 70 else "high",
        "source_count": len(samples), "observed_count": len(signals),
        "sources": [{"kind": s.source_kind, "id": s.source_id} for s in samples],
        "disagreement": own is not None and ai is not None and abs(own - ai) >= 40,
        "version": SCORE_VERSION, "updated_at": datetime.now(UTC).isoformat(),
    }
    if row is None:
        row = DailyStressSnapshot(conversation_id=conversation_id, day=day, value=result)
        db.add(row)
    else:
        row.value, row.updated_at = result, datetime.now(UTC)
    db.flush()
    return result


def ingest_source(db: Session, conversation_id: int, kind: str, source_id: int, text: str,
                  created_at: datetime, assessment: dict | None = None) -> WellbeingSignal:
    existing = db.scalar(select(WellbeingSignal).where(
        WellbeingSignal.conversation_id == conversation_id, WellbeingSignal.source_kind == kind,
        WellbeingSignal.source_id == source_id))
    digest = sha256(text.encode("utf-8")).hexdigest()
    if existing and existing.content_hash == digest and existing.day == day_of(created_at):
        return existing
    if existing:
        remove_source(db, conversation_id, kind, source_id)
    value = assessment if assessment is not None else assess(text)
    signal = WellbeingSignal(
        conversation_id=conversation_id, source_kind=kind, source_id=source_id,
        content_hash=digest, day=day_of(created_at), pressure=value.get("pressure"),
        assessment=value, model=value["model"], prompt_version=PROMPT_VERSION,
        created_at=created_at,
    )
    db.add(signal)
    db.flush()
    refresh_day(db, conversation_id, signal.day)
    # Historical/offline uploads contribute to history, never retroactive notifications.
    age = datetime.now(UTC) - created_at.replace(tzinfo=created_at.tzinfo or UTC)
    if timedelta(0) <= age <= timedelta(minutes=15) and value.get("notify_candidate"):
        from app.services.notification_service import enqueue
        enqueue(db, signal)
    return signal


def remove_source(db: Session, conversation_id: int, kind: str, source_id: int) -> None:
    rows = db.scalars(select(WellbeingSignal).where(
        WellbeingSignal.conversation_id == conversation_id, WellbeingSignal.source_kind == kind,
        WellbeingSignal.source_id == source_id)).all()
    days = set()
    for row in rows:
        days.add(row.day)
        db.execute(delete(NotificationOutbox).where(NotificationOutbox.signal_id == row.id))
        db.delete(row)
    db.flush()
    for day in days:
        refresh_day(db, conversation_id, day)


def process_record_assessment(database_url: str, conversation_id: int, record_id: int) -> None:
    """Model I/O outside a DB transaction; never resurrect a deleted/edited source."""
    from app.db.models import UserRecord
    from app.db.session import engine_for_url
    engine = engine_for_url(database_url)
    with Session(engine) as db:
        record = db.get(UserRecord, record_id)
        if not record or record.conversation_id != conversation_id or record.status != "saved":
            return
        original = "\n".join(s for s in (record.text, record.photo_comment) if s)
        source_day = day_of(record.created_at)
    if not original:
        return
    value = assess(original)
    digest = sha256(original.encode("utf-8")).hexdigest()
    with Session(engine) as db:
        record = db.get(UserRecord, record_id)
        signal = db.scalar(select(WellbeingSignal).where(
            WellbeingSignal.conversation_id == conversation_id, WellbeingSignal.source_kind == "record",
            WellbeingSignal.source_id == record_id))
        if not record or record.status != "saved" or not signal or signal.content_hash != digest:
            return
        current = "\n".join(s for s in (record.text, record.photo_comment) if s)
        if current != original or day_of(record.created_at) != source_day:
            return
        if value.get("notify_candidate") and signal.assessment.get("notification_sms_v2"):
            value = {**value, "notification_sms_v2": signal.assessment["notification_sms_v2"]}
        signal.pressure, signal.assessment, signal.model = value.get("pressure"), value, value["model"]
        refresh_day(db, conversation_id, signal.day)
        # The asynchronous semantic result can now qualify a paraphrase too.
        # Never replay old records or duplicate the initial offline notification.
        age = datetime.now(UTC) - signal.created_at.replace(tzinfo=signal.created_at.tzinfo or UTC)
        if value.get("notify_candidate") and timedelta(0) <= age <= timedelta(minutes=15):
            from app.services.notification_service import enqueue
            enqueue(db, signal)
        db.commit()
