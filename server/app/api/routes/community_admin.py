"""Private admin routes: intentionally outside the gateway's /api/v1 allowlist."""

from datetime import UTC, datetime
from hashlib import pbkdf2_hmac, sha256
import hmac
from pathlib import Path
import secrets
import time
import ipaddress
from typing import Literal
from urllib.parse import urlparse
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlalchemy import delete, select
from sqlalchemy.orm import Session
from app.core.cloud_access import throttle
from app.db.session import get_db
from app.db.community import (
    CommunityAdmin,
    CommunityAdminSession,
    CommunityAudit,
    CommunityPost,
    CommunityProfile,
    CommunityReport,
    CommunityComment,
    CommunityMedia,
)
from app.services.community_service import media_path

router = APIRouter(prefix="/community-admin", tags=["private-admin"])
COOKIE = "community_admin_session"


def password_hash(password):
    salt = secrets.token_hex(16)
    return (
        salt
        + "$"
        + pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 600_000).hex()
    )


def password_valid(password, stored):
    salt, value = stored.split("$", 1)
    return hmac.compare_digest(
        pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 600_000).hex(),
        value,
    )


def local_only(request: Request):
    # Public reverse-proxy requests are denied even though the proxy connects on loopback.
    peer = request.client.host
    try:
        private_peer = ipaddress.ip_address(peer).is_private
    except ValueError:
        private_peer = peer == "testclient"
    if (
        not private_peer
        or request.headers.get("x-forwarded-for")
        or request.headers.get("forwarded")
    ):
        raise HTTPException(404, "Not found")
    host = urlparse("http://" + request.headers.get("host", "")).hostname
    if host not in {"127.0.0.1", "localhost", "::1", "testserver"}:
        raise HTTPException(404, "Not found")


def admin_session(request: Request, db: Session = Depends(get_db)):
    local_only(request)
    token = request.cookies.get(COOKIE, "")
    row = (
        db.get(CommunityAdminSession, sha256(token.encode()).hexdigest())
        if token
        else None
    )
    if not row or row.expires_at <= int(time.time()):
        raise HTTPException(401, "请登录管理员账号")
    if request.method not in {"GET", "HEAD"}:
        if not hmac.compare_digest(request.headers.get("x-csrf-token", ""), row.csrf):
            raise HTTPException(403, "请求校验失败，请刷新页面")
        origin = request.headers.get("origin")
        if origin and origin.rstrip("/") != str(request.base_url).rstrip("/"):
            raise HTTPException(403, "来源校验失败")
    return row


class Login(BaseModel):
    username: str = Field(min_length=1, max_length=40)
    password: str = Field(min_length=1, max_length=128)


@router.get("/")
def page(request: Request):
    local_only(request)
    return FileResponse(
        Path(__file__).resolve().parents[2] / "assets/community-admin.html",
        headers={
            "Cache-Control": "no-store",
            "Content-Security-Policy": "default-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'",
        },
    )


@router.post("/login")
def login(
    body: Login, request: Request, response: Response, db: Session = Depends(get_db)
):
    local_only(request)
    origin = request.headers.get("origin")
    if origin and origin.rstrip("/") != str(request.base_url).rstrip("/"):
        raise HTTPException(403, "来源校验失败")
    throttle("community-admin-login:" + request.client.host, 5)
    user = db.get(CommunityAdmin, body.username)
    if not user or not password_valid(body.password, user.password_hash):
        raise HTTPException(401, "账号或密码不正确")
    db.execute(
        delete(CommunityAdminSession).where(
            CommunityAdminSession.expires_at <= int(time.time())
        )
    )
    token = secrets.token_urlsafe(32)
    csrf = secrets.token_hex(24)
    db.add(
        CommunityAdminSession(
            digest=sha256(token.encode()).hexdigest(),
            username=user.username,
            csrf=csrf,
            expires_at=int(time.time()) + 7200,
        )
    )
    db.commit()
    response.set_cookie(
        COOKIE,
        token,
        httponly=True,
        samesite="strict",
        max_age=7200,
        path="/community-admin",
    )
    response.headers["Cache-Control"] = "no-store"
    return {"csrf": csrf, "username": user.username}


@router.get("/session")
def session(admin=Depends(admin_session)):
    return {"csrf": admin.csrf, "username": admin.username}


@router.post("/logout")
def logout(
    response: Response, admin=Depends(admin_session), db: Session = Depends(get_db)
):
    db.delete(admin)
    db.commit()
    response.delete_cookie(COOKIE, path="/community-admin")
    return {"ok": True}


@router.get("/items")
def items(
    kind: Literal["posts", "comments", "reports", "profiles", "audit"] = "reports",
    page: int = 0,
    admin=Depends(admin_session),
    db: Session = Depends(get_db),
):
    if page < 0:
        raise HTTPException(422, "页码不正确")
    model = {
        "posts": CommunityPost,
        "comments": CommunityComment,
        "reports": CommunityReport,
        "profiles": CommunityProfile,
        "audit": CommunityAudit,
    }[kind]
    order = model.created_at.desc() if kind != "profiles" else model.id
    rows = list(db.scalars(select(model).order_by(order).offset(page * 30).limit(31)))
    from sqlalchemy.inspection import inspect
    from fastapi.encoders import jsonable_encoder

    return {
        "items": [
            jsonable_encoder(
                {
                    c.key: getattr(r, c.key)
                    for c in inspect(model).columns
                    if c.key not in {"device_id", "fingerprint", "client_id"}
                }
            )
            for r in rows[:30]
        ],
        "has_more": len(rows) > 30,
    }


@router.get("/target/{kind}/{id}")
def target(
    kind: Literal["post", "comment"],
    id: str,
    admin=Depends(admin_session),
    db: Session = Depends(get_db),
):
    from fastapi.encoders import jsonable_encoder
    from sqlalchemy.inspection import inspect

    model = CommunityPost if kind == "post" else CommunityComment
    row = db.get(model, id)
    if not row:
        raise HTTPException(404, "内容不存在")
    result = jsonable_encoder(
        {
            c.key: getattr(row, c.key)
            for c in inspect(model).columns
            if c.key not in {"fingerprint", "client_id"}
        }
    )
    result["author"] = db.get(CommunityProfile, row.author_id).nickname
    return result


class Action(BaseModel):
    kind: Literal["post", "comment", "report", "profile"]
    target_id: str = Field(min_length=1, max_length=36)
    action: Literal["hide", "restore", "resolve", "restrict", "unrestrict"]
    reason: str = Field(min_length=1, max_length=300)


@router.post("/action")
def action(body: Action, admin=Depends(admin_session), db: Session = Depends(get_db)):
    model = {
        "post": CommunityPost,
        "comment": CommunityComment,
        "report": CommunityReport,
        "profile": CommunityProfile,
    }[body.kind]
    row = db.get(model, body.target_id)
    if not row:
        raise HTTPException(404, "对象不存在")
    if body.kind in {"post", "comment"} and body.action in {"hide", "restore"}:
        if row.status == "deleted":
            raise HTTPException(409, "用户已删除的内容不能恢复")
        row.status = "hidden" if body.action == "hide" else "published"
        row.reason = body.reason
    elif body.kind == "report" and body.action == "resolve":
        row.status = "resolved"
    elif body.kind == "profile" and body.action in {"restrict", "unrestrict"}:
        row.restricted = body.action == "restrict"
    else:
        raise HTTPException(422, "不支持此操作")
    db.add(
        CommunityAudit(
            username=admin.username,
            kind=body.kind,
            target_id=body.target_id,
            action=body.action,
            reason=body.reason,
        )
    )
    db.commit()
    return {"ok": True}


@router.get("/media/{id}")
def image(id: str, admin=Depends(admin_session), db: Session = Depends(get_db)):
    row = db.get(CommunityMedia, id)
    if not row or row.retired_at is not None:
        raise HTTPException(404, "图片不存在")
    path = media_path(row.id)
    if not path.is_file():
        raise HTTPException(404, "图片不存在")
    return FileResponse(
        path, media_type="image/jpeg", headers={"Cache-Control": "no-store"}
    )
