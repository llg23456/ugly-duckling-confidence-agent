"""Durable mock delivery and an explicitly enrolled local-device SMS outbox."""
from datetime import UTC, datetime, timedelta
import re

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import Message, UserRecord, SupportPerson, NotificationOutbox, TrustedContact, WellbeingPreference, WellbeingSignal
from app.services.wellbeing_service import CONSENT_VERSION, LOCAL_ZONE

NOTICE_TEXT = "鸭力Go检测到可能需要及时陪伴的安全信号。请尽快联系并确认对方安全；如有立即危险，请联系当地紧急服务。这不是医学诊断。"
DEVICE_SMS_CONSENT = "device-sms-demo-adult-v1"
DEVICE_SMS_TEXT = "【鸭力Go演示】检测到需及时陪伴的安全信号，请联系这部手机的使用者确认安全。这是已约定的功能演示短信，不是诊断。"
CONTEXT_SMS_CONSENT = "device-sms-context-adult-v2"


def contextual_notice(db: Session, signal: WellbeingSignal) -> str | None:
    """Use persisted user evidence, never generated claims of an act already done."""
    if not signal.assessment.get("notify_candidate"):
        return None
    if signal.source_kind == "message":
        source = db.get(Message, signal.source_id)
        if not source or source.conversation_id != signal.conversation_id or source.role != "user":
            return None
        original, place = source.content, "对话"
    elif signal.source_kind == "record":
        source = db.get(UserRecord, signal.source_id)
        if not source or source.conversation_id != signal.conversation_id or source.status != "saved":
            return None
        original, place = "\n".join(s for s in (source.text, source.photo_comment) if s), "个人记录"
    else:
        return None
    snippets = [part for part in signal.assessment.get("evidence", []) if part.strip() and part in original]
    if not snippets:
        return None
    # Keep one necessary clause, avoid leaking unrelated contact details or URLs.
    quote = re.sub(r"https?://\S+|[\w.+-]+@[\w.-]+\.\w+|(?:\+?86)?1[3-9]\d{9}", "[联系方式已省略]", snippets[0])
    quote = re.sub(r"[\s\x00-\x1f]+", " ", quote).strip()
    quote = quote[:40] + ("…" if len(quote) > 40 else "")
    kind = "自伤或轻生" if signal.assessment.get("category") == "self_harm" else "伤害他人"
    time = signal.created_at.replace(tzinfo=signal.created_at.tzinfo or UTC).astimezone(LOCAL_ZONE).strftime("%m月%d日%H:%M")
    detail = (f"表达了{kind}意图，并出现近期行动信号" if signal.assessment.get("immediate")
              else f"表达了{kind}相关想法，需要及时关心与陪伴")
    return (f"【鸭力Go安全提醒】此号码的使用者于北京时间{time}在{place}中{detail}。"
            f"相关原话：“{quote}”。请尽快联系确认安全；若有立即危险，请联系当地紧急服务。系统识别待核实，不代表行为已发生。")


def matching_context_contact(db: Session, signal: WellbeingSignal) -> TrustedContact | None:
    from app.services.support_service import choose_person, SCENE_WORDS
    from app.schemas.support import SupportSuggestionRequest
    contacts = list(db.scalars(select(TrustedContact).where(
        TrustedContact.conversation_id == signal.conversation_id,
        TrustedContact.verification_kind == "device_sms_v2", TrustedContact.verified_at.is_not(None))))
    people = [db.get(SupportPerson, contact.support_person_id) for contact in contacts]
    # Purpose and advance authorization constrain automated recipients. A teacher
    # saved solely for academic questions is never an emergency fallback.
    people = [person for person in people if person and (
        any(word in scene for scene in person.scenarios or [] for word in SCENE_WORDS["emotional"])
        or (not person.scenarios and person.kind in ("family", "friend", "professional")))]
    if not people:
        return None
    source = db.get(Message if signal.source_kind == "message" else UserRecord, signal.source_id)
    context = (source.content if signal.source_kind == "message" else "\n".join(filter(None, (source.text, source.photo_comment)))) if source else ""
    chosen = choose_person(SupportSuggestionRequest(situation=context[:4000] or "需要及时陪伴"),
                           people, assessment=signal.assessment)
    return next((c for c in contacts if chosen and c.support_person_id == chosen.id), None)


def device_sms_available() -> bool:
    settings = get_settings()
    return (settings.app_env == "development" and not settings.cloud_auth_required
            and settings.notification_mode == "mock")


def mock_enabled() -> bool:
    settings = get_settings()
    return settings.app_env == "development" and settings.notification_mode == "mock"


def enqueue(db: Session, signal: WellbeingSignal) -> None:
    if not mock_enabled() or not signal.assessment.get("notify_candidate"):
        return
    pref = db.scalar(select(WellbeingPreference).where(WellbeingPreference.conversation_id == signal.conversation_id))
    if not pref or not pref.auto_notify or pref.consent_version not in (CONSENT_VERSION, DEVICE_SMS_CONSENT, CONTEXT_SMS_CONSENT):
        return
    contextual = pref.consent_version == CONTEXT_SMS_CONSENT
    device_mode = pref.consent_version in (DEVICE_SMS_CONSENT, CONTEXT_SMS_CONSENT)
    if device_mode and not device_sms_available():
        return
    now = datetime.now(UTC)
    contacts = list(db.scalars(select(TrustedContact).where(
        TrustedContact.conversation_id == signal.conversation_id,
        TrustedContact.verification_kind == ("device_sms_v2" if contextual else "device_sms" if device_mode else "mock"),
        TrustedContact.verified_at.is_not(None),
    )))
    if contextual:
        selected = matching_context_contact(db, signal)
        message = contextual_notice(db, signal)
        if not selected or not message:
            return
        contacts = [selected]
        signal.assessment = {**signal.assessment, "notification_sms_v2": message}
    for contact in contacts:
        if db.scalar(select(NotificationOutbox.id).where(
                NotificationOutbox.signal_id == signal.id, NotificationOutbox.contact_id == contact.id)) is not None:
            continue
        recent = db.scalar(select(NotificationOutbox.id).where(
            NotificationOutbox.contact_id == contact.id,
            NotificationOutbox.status.in_(["queued", "simulated", "phone_queued", "phone_claimed", "phone_sending",
                                          "phone_sent", "phone_delivered", "phone_failed", "phone_unknown"]),
            NotificationOutbox.created_at > now - (timedelta(minutes=10) if device_mode else timedelta(hours=6))))
        if recent is None:
            db.add(NotificationOutbox(
                conversation_id=signal.conversation_id, signal_id=signal.id, contact_id=contact.id,
                status="phone_queued" if device_mode else "queued",
                expires_at=now + timedelta(minutes=2 if device_mode else 15),
            ))


def dispatch_pending(db: Session) -> int:
    """Run from the worker or after a committed request; safe under concurrent workers.

    The atomic state change IS the mock provider's delivery operation. A future real
    provider must use provider-side idempotency + receipt reconciliation, not reuse
    this transaction as a claim that a real recipient received a message.
    """
    count = 0
    now = datetime.now(UTC)
    rows = db.scalars(select(NotificationOutbox).where(NotificationOutbox.status == "queued")).all()
    for row in rows:
        pref = db.scalar(select(WellbeingPreference).where(WellbeingPreference.conversation_id == row.conversation_id))
        contact = db.get(TrustedContact, row.contact_id)
        signal = db.get(WellbeingSignal, row.signal_id)
        expired = row.expires_at.replace(tzinfo=row.expires_at.tzinfo or UTC) <= now
        valid = (mock_enabled() and pref and pref.auto_notify and pref.consent_version == CONSENT_VERSION
                 and contact and contact.verification_kind == "mock" and contact.verified_at
                 and signal and signal.assessment.get("notify_candidate"))
        status = "expired" if expired else "simulated" if valid else "cancelled"
        updated = db.execute(update(NotificationOutbox).where(
            NotificationOutbox.id == row.id, NotificationOutbox.status == "queued",
        ).values(status=status, attempts=NotificationOutbox.attempts + 1,
                 provider_ref=f"mock:{row.id}" if status == "simulated" else None, updated_at=now))
        count += updated.rowcount
    db.commit()
    return count


def dispatch_for_url(database_url: str) -> None:
    from app.db.session import engine_for_url
    with Session(engine_for_url(database_url)) as db:
        dispatch_pending(db)
