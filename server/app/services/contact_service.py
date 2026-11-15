"""Save a support person and their phone/notification authorization atomically."""
import re
import secrets
from datetime import UTC, datetime
from hashlib import sha256

from fastapi import HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.db.models import NotificationOutbox, SupportPerson, TrustedContact
from app.services.notification_service import CONTEXT_SMS_CONSENT, device_sms_available
from app.services.wellbeing_service import preferences


def save_phone(db: Session, person: SupportPerson, request) -> str | None:
    if request.contact_version != 2:
        return None  # compatibility only; old requests cannot authorize contextual SMS
    phone = re.sub(r"[\s-]", "", request.phone or "")
    if phone.startswith("1"):
        phone = "+86" + phone
    if not re.fullmatch(r"\+861[3-9]\d{9}", phone):
        raise HTTPException(422, "手机号为必填项，请填写有效的中国大陆手机号码")
    if request.emergency_enabled:
        if not device_sms_available():
            raise HTTPException(409, "当前服务未开通手机短信，请关闭紧急提醒后保存联系方式")
        if not request.contact_consent or request.consent_version != CONTEXT_SMS_CONSENT:
            raise HTTPException(422, "请先确认成年、接收人同意，并同意短信包含触发类型和必要的原话摘要")
    duplicate = db.scalar(select(TrustedContact.id).where(
        TrustedContact.conversation_id == person.conversation_id,
        TrustedContact.phone == phone, TrustedContact.support_person_id != person.id))
    if duplicate:
        raise HTTPException(409, "该号码已用于另一位联系人，请检查或修改原联系人")
    contact = db.scalar(select(TrustedContact).where(TrustedContact.support_person_id == person.id))
    if contact is None:
        contact = TrustedContact(conversation_id=person.conversation_id, support_person_id=person.id, phone=phone)
        db.add(contact)
        db.flush()
    now = datetime.now(UTC)
    db.execute(update(NotificationOutbox).where(
        NotificationOutbox.contact_id == contact.id,
        NotificationOutbox.status.in_(["queued", "phone_queued", "phone_claimed"]),
    ).values(status="cancelled", updated_at=now))
    token = secrets.token_urlsafe(32) if request.emergency_enabled else None
    contact.phone = phone
    contact.verification_kind = "device_sms_v2" if token else "saved"
    contact.verified_at = now if token else None
    contact.code_digest = sha256(token.encode()).hexdigest() if token else None
    contact.code_expires_at, contact.attempts = None, 0
    if token:
        pref = preferences(db, person.conversation_id)
        if pref.consent_version != CONTEXT_SMS_CONSENT:
            # Never apply new disclosure permission to previously authorized demo contacts.
            db.execute(update(NotificationOutbox).where(
                NotificationOutbox.conversation_id == person.conversation_id,
                NotificationOutbox.status.in_(["queued", "phone_queued", "phone_claimed"]),
            ).values(status="cancelled", updated_at=now))
        pref.auto_notify, pref.consent_version, pref.consented_at = True, CONTEXT_SMS_CONSENT, now
    else:
        remaining = db.scalar(select(TrustedContact.id).where(
            TrustedContact.conversation_id == person.conversation_id,
            TrustedContact.verification_kind == "device_sms_v2", TrustedContact.verified_at.is_not(None)))
        pref = preferences(db, person.conversation_id)
        if not remaining and pref.consent_version == CONTEXT_SMS_CONSENT:
            pref.auto_notify = False
    return token
