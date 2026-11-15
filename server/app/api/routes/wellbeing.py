import hmac
import re
import secrets
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from typing import Literal
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session

from app.db.models import DailyCheckIn, DailyStressSnapshot, NotificationOutbox, SupportPerson, TrustedContact, WellbeingSignal
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.core.config import get_settings
from app.services.notification_service import NOTICE_TEXT, DEVICE_SMS_TEXT, DEVICE_SMS_CONSENT, CONTEXT_SMS_CONSENT, mock_enabled, device_sms_available
from app.services.wellbeing_service import CONSENT_VERSION, SCORE_VERSION, day_of, preferences, refresh_day

router = APIRouter(prefix="/wellbeing", tags=["wellbeing"])


class DeviceRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)


class CheckInRequest(DeviceRequest):
    mood: int = Field(ge=1, le=5)
    pressure: Literal[0, 25, 50, 75, 100]


class PreferencesRequest(DeviceRequest):
    reminder_threshold: int | None = Field(default=None, ge=50, le=90)
    self_weight: int | None = Field(default=None, ge=50, le=90)
    auto_notify: bool | None = None
    adult_confirmed: bool = False
    consent_version: str | None = None


class ContactRequest(DeviceRequest):
    support_person_id: int = Field(gt=0)
    phone: str = Field(min_length=11, max_length=24)
    trusted_confirmed: bool


class VerifyRequest(DeviceRequest):
    code: str = Field(pattern=r"^\d{6}$")


class SelfCheckRequest(DeviceRequest):
    sleep: int = Field(ge=0, le=3)
    daily_impact: int = Field(ge=0, le=3)
    coping: int = Field(ge=0, le=3)
    immediate_danger: bool


@router.get("/resources")
def resources(device_id: str = Query(min_length=1, max_length=128)) -> dict:
    configured = get_settings().counseling_url.strip()
    parsed = urlparse(configured)
    valid = parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username and not parsed.password
    return {"counseling_url": configured if valid else None,
            "notice": "预约入口由项目组织者核验和配置；应用不提供诊断或保证预约成功。"}


def _pref_dict(pref) -> dict:
    return {"reminder_threshold": pref.reminder_threshold, "self_weight": pref.self_weight,
            "auto_notify": pref.auto_notify, "consent_version": CONSENT_VERSION,
            "notification_mode": "device_sms" if device_sms_available() and pref.consent_version in (DEVICE_SMS_CONSENT, CONTEXT_SMS_CONSENT) else "mock" if mock_enabled() else "disabled",
            "contextual_sms": pref.consent_version == CONTEXT_SMS_CONSENT,
            "device_sms_available": device_sms_available()}


@router.get("/today")
def today(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> dict:
    conversation = get_or_create_conversation(db, device_id)
    pref = preferences(db, conversation.id)
    day = day_of()
    snapshot = db.scalar(select(DailyStressSnapshot).where(
        DailyStressSnapshot.conversation_id == conversation.id, DailyStressSnapshot.day == day))
    value = (snapshot.value if snapshot and snapshot.value.get("version") == SCORE_VERSION
             else refresh_day(db, conversation.id, day))
    latest = db.scalar(select(WellbeingSignal).where(
        WellbeingSignal.conversation_id == conversation.id, WellbeingSignal.day == day,
    ).order_by(WellbeingSignal.id.desc()))
    db.commit()
    return {"today": value, "preferences": _pref_dict(pref),
            "safety": {"action": latest.assessment["action"], "created_at": latest.created_at.isoformat()} if latest else None,
            "notice": "压力分数是个人记录参考，不是医学诊断或危险概率；安全检测独立运行。"}


@router.post("/check-in")
def check_in(request: CheckInRequest, db: Session = Depends(get_db)) -> dict:
    conversation = get_or_create_conversation(db, request.device_id)
    day = day_of()
    row = db.scalar(select(DailyCheckIn).where(DailyCheckIn.conversation_id == conversation.id, DailyCheckIn.day == day))
    if row is None:
        row = DailyCheckIn(conversation_id=conversation.id, day=day, mood=request.mood, pressure=request.pressure)
        db.add(row)
    row.mood, row.pressure, row.updated_at = request.mood, request.pressure, datetime.now(UTC)
    db.flush()
    result = refresh_day(db, conversation.id, day)
    db.commit()
    return result


@router.get("/history")
def history(device_id: str = Query(min_length=1, max_length=128),
            days: int = Query(default=7), db: Session = Depends(get_db)) -> dict:
    if days not in (7, 30, 365):
        raise HTTPException(422, "仅支持7、30或365天")
    conversation = get_conversation(db, device_id)
    end = datetime.fromisoformat(day_of()).date()
    start = end - timedelta(days=days - 1)
    rows = db.scalars(select(DailyStressSnapshot).where(
        DailyStressSnapshot.conversation_id == (conversation.id if conversation else -1),
        DailyStressSnapshot.day >= start.isoformat(), DailyStressSnapshot.day <= end.isoformat(),
    )).all()
    by_day = {r.day: r.value for r in rows}
    # Upgrade today's cache even when the trend is requested before the main card.
    if conversation and end.isoformat() in by_day and by_day[end.isoformat()].get("version") != SCORE_VERSION:
        by_day[end.isoformat()] = refresh_day(db, conversation.id, end.isoformat())
        db.commit()
    points = [by_day.get((start + timedelta(days=i)).isoformat(),
                        {"date": (start + timedelta(days=i)).isoformat(), "score": None, "level": "unknown"})
              for i in range(days)]
    return {"days": days, "timezone": "Asia/Shanghai", "points": points,
            "coverage": sum(p["score"] is not None for p in points)}


@router.patch("/preferences")
def update_preferences(request: PreferencesRequest, db: Session = Depends(get_db)) -> dict:
    conversation = get_or_create_conversation(db, request.device_id)
    pref = preferences(db, conversation.id)
    if request.auto_notify is True:
        if not mock_enabled():
            raise HTTPException(409, "真实短信服务尚未接入，当前不能开启自动通知")
        if not request.adult_confirmed or request.consent_version != CONSENT_VERSION:
            raise HTTPException(422, "请先确认年满18岁，并阅读本版自动通知说明")
        verified = db.scalar(select(TrustedContact.id).where(
            TrustedContact.conversation_id == conversation.id, TrustedContact.verification_kind == "mock",
            TrustedContact.verified_at.is_not(None)))
        if not verified:
            raise HTTPException(409, "请先完成可信联系人的模拟验证")
        pref.consented_at, pref.consent_version = datetime.now(UTC), CONSENT_VERSION
    if request.auto_notify is not None:
        pref.auto_notify = request.auto_notify
        if not request.auto_notify:
            db.execute(update(NotificationOutbox).where(
                NotificationOutbox.conversation_id == conversation.id,
                NotificationOutbox.status.in_(["queued", "phone_queued", "phone_claimed"]),
            ).values(status="cancelled", updated_at=datetime.now(UTC)))
    if request.reminder_threshold is not None:
        pref.reminder_threshold = request.reminder_threshold
    if request.self_weight is not None:
        pref.self_weight = request.self_weight
        refresh_day(db, conversation.id, day_of())
    db.commit()
    return _pref_dict(pref)


def _contact(db: Session, contact_id: int, device_id: str) -> TrustedContact:
    conversation = get_conversation(db, device_id)
    contact = db.get(TrustedContact, contact_id)
    if not conversation or not contact or contact.conversation_id != conversation.id:
        raise HTTPException(404, "联系人不存在")
    return contact


@router.get("/contacts")
def contacts(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> dict:
    conversation = get_conversation(db, device_id)
    rows = db.scalars(select(TrustedContact).where(
        TrustedContact.conversation_id == (conversation.id if conversation else -1))).all()
    return {"mode": "mock" if mock_enabled() else "disabled", "contacts": [
        {"id": c.id, "support_person_id": c.support_person_id,
         "phone_masked": c.phone[:5] + "****" + c.phone[-4:],
         "verification": c.verification_kind, "verified": c.verified_at is not None and c.verification_kind == "mock",
         "emergency_enabled": c.verification_kind == "device_sms_v2" and c.verified_at is not None,
         "demo_confirmed": c.verified_at is not None and c.verification_kind == "device_sms"}
        for c in rows
    ]}


@router.post("/contacts")
def request_verification(request: ContactRequest, db: Session = Depends(get_db)) -> dict:
    if not mock_enabled():
        raise HTTPException(409, "手机验证服务尚未接入；模拟验证仅用于本地开发")
    conversation = get_conversation(db, request.device_id)
    person = db.get(SupportPerson, request.support_person_id)
    if not conversation or not person or person.conversation_id != conversation.id:
        raise HTTPException(404, "请先在支持圈添加这个人")
    if not request.trusted_confirmed:
        raise HTTPException(422, "请确认这是你信任且愿意接收提醒的人")
    phone = re.sub(r"[\s-]", "", request.phone)
    if phone.startswith("1"):
        phone = "+86" + phone
    if not re.fullmatch(r"\+861[3-9]\d{9}", phone):
        raise HTTPException(422, "第一版仅支持中国大陆手机号码")
    contact = db.scalar(select(TrustedContact).where(TrustedContact.support_person_id == person.id))
    duplicate = db.scalar(select(TrustedContact).where(
        TrustedContact.conversation_id == conversation.id, TrustedContact.phone == phone,
        TrustedContact.support_person_id != person.id))
    if duplicate:
        raise HTTPException(409, "这个号码已经绑定，避免重复通知")
    now = datetime.now(UTC)
    if contact and contact.code_expires_at and contact.code_expires_at.replace(tzinfo=UTC) > now + timedelta(minutes=9):
        raise HTTPException(429, "请一分钟后再申请模拟验证码")
    if contact is None:
        if len(db.scalars(select(TrustedContact).where(TrustedContact.conversation_id == conversation.id)).all()) >= 3:
            raise HTTPException(409, "最多绑定3位可信联系人")
        contact = TrustedContact(conversation_id=conversation.id, support_person_id=person.id, phone=phone)
        db.add(contact)
        db.flush()
    # Changing a number revokes its previous verification and cancels pending sends.
    db.execute(update(NotificationOutbox).where(
        NotificationOutbox.contact_id == contact.id, NotificationOutbox.status.in_(["queued", "phone_queued", "phone_claimed"]),
    ).values(status="cancelled"))
    code = f"{secrets.randbelow(1000000):06d}"
    contact.phone, contact.verification_kind, contact.verified_at = phone, "pending", None
    contact.code_digest = sha256(f"{contact.id}:{code}".encode()).hexdigest()
    contact.code_expires_at, contact.attempts = now + timedelta(minutes=10), 0
    db.commit()
    return {"id": contact.id, "mode": "mock", "simulation_code": code,
            "notice": "本地模拟码，没有发送短信；不代表真实号码已经核验。"}


@router.post("/contacts/{contact_id}/verify")
def verify(contact_id: int, request: VerifyRequest, db: Session = Depends(get_db)) -> dict:
    if not mock_enabled():
        raise HTTPException(409, "模拟验证仅用于本地开发")
    contact = _contact(db, contact_id, request.device_id)
    if (not contact.code_digest or not contact.code_expires_at or contact.attempts >= 5
            or contact.code_expires_at.replace(tzinfo=UTC) <= datetime.now(UTC)):
        raise HTTPException(409, "验证码失效，请重新申请")
    contact.attempts += 1
    valid = hmac.compare_digest(contact.code_digest, sha256(f"{contact.id}:{request.code}".encode()).hexdigest())
    if valid:
        contact.verification_kind, contact.verified_at, contact.code_digest = "mock", datetime.now(UTC), None
    db.commit()
    if not valid:
        raise HTTPException(422, "验证码不正确")
    return {"verified": True, "verification": "mock", "notice": "模拟验证成功，未核验真实手机号"}


@router.delete("/contacts/{contact_id}", status_code=204)
def remove_contact(contact_id: int, device_id: str = Query(min_length=1, max_length=128),
                   db: Session = Depends(get_db)) -> None:
    contact = _contact(db, contact_id, device_id)
    db.execute(delete(NotificationOutbox).where(NotificationOutbox.contact_id == contact.id))
    db.delete(contact)
    db.commit()


@router.get("/notifications")
def notifications(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> dict:
    conversation = get_conversation(db, device_id)
    now = datetime.now(UTC)
    if conversation:
        for states, status in ((["phone_queued"], "expired"), (["phone_claimed", "phone_sending"], "phone_unknown")):
            db.execute(update(NotificationOutbox).where(
                NotificationOutbox.conversation_id == conversation.id,
                NotificationOutbox.status.in_(states), NotificationOutbox.expires_at <= now,
            ).values(status=status, updated_at=now))
        db.commit()
    rows = db.scalars(select(NotificationOutbox).where(
        NotificationOutbox.conversation_id == (conversation.id if conversation else -1),
    ).order_by(NotificationOutbox.id.desc()).limit(30)).all()
    pref = preferences(db, conversation.id) if conversation else None
    phone_mode = pref and pref.consent_version in (DEVICE_SMS_CONSENT, CONTEXT_SMS_CONSENT) and device_sms_available()
    def notice_context(row):
        signal = db.get(WellbeingSignal, row.signal_id)
        return signal.assessment.get("notification_sms_v2") if signal else None
    contextual = bool(pref and pref.consent_version == CONTEXT_SMS_CONSENT)
    return {"mode": "device_sms" if phone_mode else "mock" if mock_enabled() else "disabled",
            "template": "按触发来源生成，包含时间、类型和必要原话摘要" if contextual else DEVICE_SMS_TEXT if phone_mode else NOTICE_TEXT, "items": [
        {"id": r.id, "status": r.status, "created_at": r.created_at.isoformat(),
         "contact_id": r.contact_id, "attempts": r.attempts, "provider_ref": r.provider_ref if r.status == "simulated" else None,
         "message": notice_context(r)}
        for r in rows
    ]}


@router.post("/self-check")
def self_check(request: SelfCheckRequest) -> dict:
    # Original, informal reflection questions. No copyrighted scale or diagnostic cutoff.
    if request.immediate_danger:
        return {"action": "urgent_support", "feedback": "请先确保此刻安全，让信任的人陪着你。如有立即危险，请联系当地紧急服务或前往急诊。问卷不会自动发送短信。"}
    impacted = max(request.sleep, request.daily_impact, request.coping) >= 2
    return {"action": "seek_support" if impacted else "small_step",
            "feedback": "近期的负担已经影响日常，可以联系学校心理中心、正规医疗机构或你信任的咨询师安排进一步评估。" if impacted
            else "可以先选一个小的休息或求助步骤，继续记录变化；如果困扰持续或加重，寻求专业支持。",
            "notice": "这是自编的生活影响自查，不是标准化心理量表，也不能诊断或排除疾病。"}
