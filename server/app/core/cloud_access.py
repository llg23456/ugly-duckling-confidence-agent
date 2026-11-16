"""Invitation-only access for the single-worker, SQLite cloud test deployment."""
import hashlib
import secrets
import threading
import time
from collections import defaultdict, deque

from fastapi import Depends, HTTPException, Request
from sqlalchemy import Integer, String, select, update
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.orm import Mapped, Session, mapped_column

from app.core.config import get_settings
from app.db.models import Base, Conversation
from app.db.session import get_db


class CloudInvite(Base):
    __tablename__ = "cloud_invites"
    digest: Mapped[str] = mapped_column(String(64), primary_key=True)
    label: Mapped[str] = mapped_column(String(100))
    device_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    expires_at: Mapped[int] = mapped_column(Integer)
    access_until: Mapped[int] = mapped_column(Integer)
    used_at: Mapped[int | None] = mapped_column(Integer, nullable=True)


class CloudCredential(Base):
    __tablename__ = "cloud_credentials"
    digest: Mapped[str] = mapped_column(String(64), primary_key=True)
    device_id: Mapped[str] = mapped_column(String(128), unique=True)
    expires_at: Mapped[int] = mapped_column(Integer)
    revoked_at: Mapped[int | None] = mapped_column(Integer, nullable=True)


class CloudUsage(Base):
    __tablename__ = "cloud_usage"
    key: Mapped[str] = mapped_column(String(180), primary_key=True)
    used: Mapped[int] = mapped_column(Integer, default=0)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


_windows: dict[str, deque] = defaultdict(deque)
_lock = threading.Lock()
_render_slot = threading.BoundedSemaphore(1)


def throttle(key: str, limit: int, seconds: int = 60) -> None:
    now = time.monotonic()
    with _lock:
        # Bound memory even when many source addresses try invitation redemption.
        if len(_windows) > 4096:
            for old in list(_windows):
                if not _windows[old] or _windows[old][-1] <= now - 3600:
                    del _windows[old]
            if len(_windows) > 4096:
                raise HTTPException(429, "请求较多，请稍后再试", headers={"Retry-After": "60"})
        window = _windows[key]
        while window and window[0] <= now - seconds:
            window.popleft()
        if len(window) >= limit:
            raise HTTPException(429, "请求较频繁，请稍后再试", headers={"Retry-After": str(seconds)})
        window.append(now)


def issue_invite(db: Session, label: str, device_id: str | None = None) -> str:
    now = int(time.time())
    code = secrets.token_hex(10).upper()
    db.add(CloudInvite(digest=digest(code), label=label, device_id=device_id,
                       expires_at=now + 86400, access_until=now + 30 * 86400))
    db.commit()
    return code


def redeem(db: Session, code: str, device_id: str) -> tuple[str, int]:
    now = int(time.time())
    invite = db.get(CloudInvite, digest(code.strip().upper()))
    if not invite or invite.used_at is not None or invite.expires_at <= now or invite.access_until <= now:
        raise HTTPException(401, "邀请码无效、已使用或已过期")
    if invite.device_id and invite.device_id != device_id:
        raise HTTPException(403, "邀请码与本设备不匹配，请联系组织者")
    # Generic invitations cannot claim an existing device's data by guessing its UUID.
    if not invite.device_id and (
        db.scalar(select(Conversation.id).where(Conversation.device_id == device_id))
        or db.scalar(select(CloudCredential.digest).where(CloudCredential.device_id == device_id))
    ):
        raise HTTPException(409, "本设备已有云测数据，请联系组织者申请设备专用邀请码")
    consumed = db.execute(update(CloudInvite).where(
        CloudInvite.digest == invite.digest, CloudInvite.used_at.is_(None),
        CloudInvite.expires_at > now,
    ).values(used_at=now))
    if consumed.rowcount != 1:
        db.rollback()
        raise HTTPException(401, "邀请码已使用")
    # A replacement invitation can rotate a lost/revoked credential; the old token stops working.
    db.query(CloudCredential).filter(CloudCredential.device_id == device_id).delete()
    token = secrets.token_urlsafe(32)
    db.add(CloudCredential(digest=digest(token), device_id=device_id, expires_at=invite.access_until))
    db.commit()
    return token, invite.access_until


def credential_for(request: Request, db: Session) -> CloudCredential:
    authorization = request.headers.get("authorization", "")
    scheme, _, token = authorization.partition(" ")
    credential = db.get(CloudCredential, digest(token)) if scheme.lower() == "bearer" and token and len(token) < 256 else None
    if not credential or credential.revoked_at is not None or (
        credential.expires_at <= int(time.time()) and not get_settings().cloud_open_enrollment
    ):
        raise HTTPException(401, "云测授权已失效，请输入新的邀请码", headers={"WWW-Authenticate": "Bearer"})
    return credential


STATELESS_PATHS = {
    "/events/extract", "/onboarding/schema", "/onboarding/analyze",
    "/multimodal/transcribe", "/multimodal/speech", "/records/describe-photo",
}


async def require_cloud_access(request: Request, db: Session = Depends(get_db)) -> None:
    settings = get_settings()
    if not settings.cloud_auth_required:
        return
    credential = credential_for(request, db)
    throttle("device:" + credential.device_id, 90)
    ids = request.query_params.getlist("device_id")
    if "device_id" in request.path_params:
        ids.append(request.path_params["device_id"])
    content_type = request.headers.get("content-type", "").split(";", 1)[0]
    if content_type == "application/json":
        try:
            body = await request.json() if await request.body() else {}
        except (ValueError, UnicodeDecodeError) as error:
            raise HTTPException(400, "请求 JSON 无效") from error
        if isinstance(body, dict) and "device_id" in body:
            ids.append(body["device_id"])
    elif content_type in {"multipart/form-data", "application/x-www-form-urlencoded"}:
        form = await request.form()
        ids.extend(form.getlist("device_id"))
    relative_path = request.url.path.removeprefix(settings.api_prefix)
    if not ids and relative_path not in STATELESS_PATHS:
        raise HTTPException(403, "请求缺少本设备身份")
    if any(value != credential.device_id for value in ids):
        raise HTTPException(403, "不能访问其他设备的数据")
    request.state.cloud_device_id = credential.device_id
    # The legacy review GET can generate an AI review; it must consume quota too.
    may_generate_review = relative_path in {"/reviews/day", "/reviews/week", "/reviews/month"}
    if request.method not in {"GET", "HEAD", "OPTIONS"} or may_generate_review:
        day = time.strftime("%Y-%m-%d", time.gmtime())
        for key, limit in [(f"{day}:{credential.device_id}", settings.cloud_daily_requests),
                           (f"{day}:*", settings.cloud_global_daily_requests)]:
            if limit <= 0:  # Zero disables this quota, including for already exhausted users.
                continue
            db.execute(insert(CloudUsage).values(key=key, used=0).on_conflict_do_nothing())
            result = db.execute(update(CloudUsage).where(CloudUsage.key == key, CloudUsage.used < limit)
                                .values(used=CloudUsage.used + 1))
            if result.rowcount != 1:
                db.rollback()
                raise HTTPException(429, "今日云测额度已用完，请明天再试或联系组织者", headers={"Retry-After": "3600"})
        db.commit()
    else:
        db.rollback()  # End the auth read transaction before route/background work.


def limit_video_render():
    if not get_settings().cloud_auth_required:
        yield
        return
    if not _render_slot.acquire(blocking=False):
        raise HTTPException(429, "已有成长小片正在合成，请稍后重试", headers={"Retry-After": "30"})
    try:
        yield
    finally:
        _render_slot.release()
