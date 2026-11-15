"""Local-demo SIM transport. No SMS is sent by this server.

Enrollment is explicit, manually checked (not OTP verified), one recipient per
device. Claims are never reissued; uncertain delivery is not automatically retried.
"""
import hmac
import re
import secrets
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.api.routes.wellbeing import ContactRequest, DeviceRequest
from app.db.models import NotificationOutbox, SupportPerson, TrustedContact, WellbeingSignal
from app.db.repository import get_conversation
from app.db.session import get_db
from app.services.notification_service import (DEVICE_SMS_CONSENT, DEVICE_SMS_TEXT, CONTEXT_SMS_CONSENT,
    device_sms_available, contextual_notice, matching_context_contact)
from app.services.wellbeing_service import preferences

router = APIRouter(prefix="/wellbeing/device-sms", tags=["wellbeing"])


class Enrollment(ContactRequest):
    phone_repeat: str = Field(min_length=11, max_length=24)
    adult_confirmed: bool
    consent_version: str


class DeviceToken(DeviceRequest):
    enrollment_token: str = Field(min_length=32, max_length=128)


class Receipt(DeviceRequest):
    claim_token: str = Field(min_length=32, max_length=128)
    status: Literal["phone_sent", "phone_delivered", "phone_failed", "phone_unknown"]


class SendAuthorization(DeviceToken):
    claim_token: str = Field(min_length=32, max_length=128)


def _enabled():
    if not device_sms_available():
        raise HTTPException(409, "手机短信目前仅在本地开发服务可用")


def _phone(value: str) -> str:
    value = re.sub(r"[\s-]", "", value)
    if value.startswith("1"):
        value = "+86" + value
    if not re.fullmatch(r"\+861[3-9]\d{9}", value):
        raise HTTPException(422, "请填写中国大陆手机号")
    return value


def _digest(token: str) -> str:
    return sha256(token.encode()).hexdigest()


def _enrolled(db: Session, request: DeviceToken):
    _enabled()
    conversation = get_conversation(db, request.device_id)
    contacts = db.scalars(select(TrustedContact).where(
        TrustedContact.conversation_id == (conversation.id if conversation else -1),
        TrustedContact.verification_kind.in_(["device_sms", "device_sms_v2"]),
        TrustedContact.verified_at.is_not(None))).all()
    contact = next((c for c in contacts if c.code_digest and hmac.compare_digest(c.code_digest, _digest(request.enrollment_token))), None)
    if not contact:
        raise HTTPException(403, "本机短信授权已失效，请重新配置")
    pref = preferences(db, conversation.id)
    expected = CONTEXT_SMS_CONSENT if contact.verification_kind == "device_sms_v2" else DEVICE_SMS_CONSENT
    if not pref.auto_notify or pref.consent_version != expected:
        raise HTTPException(409, "自动短信已关闭")
    return contact


@router.post("/setup")
def setup(request: Enrollment, db: Session = Depends(get_db)) -> dict:
    _enabled()
    if not request.trusted_confirmed or not request.adult_confirmed or request.consent_version != DEVICE_SMS_CONSENT:
        raise HTTPException(422, "请确认成年、接收人已同意演示，并同意自动发送及短信费用")
    phone = _phone(request.phone)
    if phone != _phone(request.phone_repeat):
        raise HTTPException(422, "两次输入的号码不一致")
    conversation = get_conversation(db, request.device_id)
    person = db.get(SupportPerson, request.support_person_id)
    if not conversation or not person or person.conversation_id != conversation.id:
        raise HTTPException(404, "请先在自己的支持圈添加接收人")
    rows = db.scalars(select(TrustedContact).where(TrustedContact.conversation_id == conversation.id)).all()
    if any(c.phone == phone and c.support_person_id != person.id for c in rows):
        raise HTTPException(409, "这个号码已绑定其他支持者，请先解除原绑定")
    contact = next((c for c in rows if c.support_person_id == person.id), None)
    if contact is None:
        if len(rows) >= 3:
            raise HTTPException(409, "最多绑定3位联系人，请先解除一个绑定")
        contact = TrustedContact(conversation_id=conversation.id, support_person_id=person.id, phone=phone)
        db.add(contact)
    for previous in rows:
        if previous.verification_kind == "device_sms":
            previous.verification_kind, previous.verified_at, previous.code_digest = "pending", None, None
    now, token = datetime.now(UTC), secrets.token_urlsafe(32)
    contact.phone, contact.verification_kind = phone, "device_sms"
    # verified_at is the manual confirmation time; API/UI never call this OTP verified.
    contact.verified_at, contact.code_digest, contact.code_expires_at = now, _digest(token), None
    pref = preferences(db, conversation.id)
    pref.auto_notify, pref.consent_version, pref.consented_at = True, DEVICE_SMS_CONSENT, now
    db.execute(update(NotificationOutbox).where(
        NotificationOutbox.conversation_id == conversation.id,
        NotificationOutbox.status.in_(["queued", "phone_queued", "phone_claimed"]),
    ).values(status="cancelled", updated_at=now))
    db.commit()
    return {"contact_id": contact.id, "enrollment_token": token, "template": DEVICE_SMS_TEXT,
            "notice": "人工核对演示号码，未验证归属；仅对开启后的新信号自动发送"}


@router.post("/claim")
def claim(request: DeviceToken, db: Session = Depends(get_db)) -> dict:
    contact = _enrolled(db, request)
    now = datetime.now(UTC)
    db.execute(update(NotificationOutbox).where(
        NotificationOutbox.conversation_id == contact.conversation_id,
        NotificationOutbox.status.in_(["phone_claimed", "phone_sending"]), NotificationOutbox.expires_at <= now,
    ).values(status="phone_unknown", updated_at=now))
    rows = db.scalars(select(NotificationOutbox).where(
        NotificationOutbox.contact_id == contact.id, NotificationOutbox.status == "phone_queued",
    ).order_by(NotificationOutbox.id)).all()
    for row in rows:
        recent = db.scalar(select(NotificationOutbox.id).where(
            NotificationOutbox.contact_id == contact.id, NotificationOutbox.id != row.id,
            NotificationOutbox.status.in_(["phone_claimed", "phone_sending", "phone_sent", "phone_delivered", "phone_unknown", "phone_failed"]),
            NotificationOutbox.created_at > now - timedelta(minutes=10)))
        signal = db.get(WellbeingSignal, row.signal_id)
        expired = row.expires_at.replace(tzinfo=row.expires_at.tzinfo or UTC) <= now
        valid = signal and signal.assessment.get("notify_candidate") and not recent
        contextual = contact.verification_kind == "device_sms_v2"
        text = DEVICE_SMS_TEXT
        if valid and contextual:
            target = matching_context_contact(db, signal)
            text = signal.assessment.get("notification_sms_v2")
            valid = bool(target and target.id == contact.id and text and contextual_notice(db, signal))
        token = secrets.token_urlsafe(32)
        status = "expired" if expired else "phone_claimed" if valid else "cancelled"
        changed = db.execute(update(NotificationOutbox).where(
            NotificationOutbox.id == row.id, NotificationOutbox.status == "phone_queued",
        ).values(status=status, provider_ref=_digest(token) if status == "phone_claimed" else None,
                 attempts=NotificationOutbox.attempts + 1, updated_at=now))
        if changed.rowcount and status == "phone_claimed":
            job = {"id": row.id, "contact_id": contact.id, "claim_token": token, "phone": contact.phone, "text": text,
                   "expires_at": row.expires_at.replace(tzinfo=UTC).isoformat()}
            db.commit()
            return {"job": job}
    db.commit()
    return {"job": None}


def _claimed(db, job_id, request):
    conversation = get_conversation(db, request.device_id)
    row = db.get(NotificationOutbox, job_id)
    if not row or not conversation or row.conversation_id != conversation.id:
        raise HTTPException(404, "通知不存在")
    if not row.provider_ref or not hmac.compare_digest(row.provider_ref, _digest(request.claim_token)):
        raise HTTPException(403, "通知授权不匹配")
    return row


@router.post("/{job_id}/authorize")
def authorize(job_id: int, request: SendAuthorization, db: Session = Depends(get_db)) -> dict:
    contact = _enrolled(db, request)
    row = _claimed(db, job_id, request)
    now = datetime.now(UTC)
    signal = db.get(WellbeingSignal, row.signal_id)
    if row.contact_id != contact.id or not signal or not signal.assessment.get("notify_candidate"):
        raise HTTPException(409, "通知依据已失效")
    if contact.verification_kind == "device_sms_v2":
        target = matching_context_contact(db, signal)
        if not target or target.id != contact.id or not contextual_notice(db, signal):
            raise HTTPException(409, "联系人用途或触发依据已变化，已阻止发送")
    changed = db.execute(update(NotificationOutbox).where(
        NotificationOutbox.id == job_id, NotificationOutbox.status == "phone_claimed",
        NotificationOutbox.expires_at > now,
    ).values(status="phone_sending", updated_at=now).execution_options(synchronize_session=False))
    db.commit()
    if not changed.rowcount:
        raise HTTPException(409, "通知已处理或过期，不会再次发送")
    return {"authorized": True}


@router.post("/{job_id}/receipt")
def receipt(job_id: int, request: Receipt, db: Session = Depends(get_db)) -> dict:
    _enabled()
    row = _claimed(db, job_id, request)
    # Late OS receipts may resolve unknown delivery; never downgrade a delivered SMS.
    transitions = {
        "phone_sending": {"phone_sent", "phone_delivered", "phone_failed", "phone_unknown"},
        "phone_unknown": {"phone_sent", "phone_delivered", "phone_failed"},
        "phone_sent": {"phone_delivered", "phone_failed"},
    }
    allowed = [status for status, targets in transitions.items() if request.status in targets]
    db.execute(update(NotificationOutbox).where(
        NotificationOutbox.id == row.id, NotificationOutbox.status.in_(allowed),
    ).values(status=request.status, updated_at=datetime.now(UTC)))
    db.commit()
    db.refresh(row)
    return {"status": row.status}
