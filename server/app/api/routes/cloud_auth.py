from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from fastapi import HTTPException
import secrets

from app.core.cloud_access import CloudCredential, credential_for, digest, redeem, throttle
from app.core.config import get_settings
from app.db.models import Conversation
from app.db.session import get_db

router = APIRouter(prefix="/cloud-auth", tags=["cloud-test"])


class ActivationRequest(BaseModel):
    invite_code: str = Field(min_length=1, max_length=64)
    device_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


class BootstrapRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


PERMANENT_ACCESS = 253402300799  # Compatibility timestamp; open enrollment does not expire credentials.


@router.post("/bootstrap")
def bootstrap(body: BootstrapRequest, request: Request, db: Session = Depends(get_db)) -> dict:
    if not get_settings().cloud_open_enrollment:
        raise HTTPException(403, "自动接入尚未开启")
    throttle("bootstrap:" + (request.client.host if request.client else "unknown"), 20)
    existing = db.scalar(select(CloudCredential).where(CloudCredential.device_id == body.device_id))
    if existing:
        # A public device ID alone must never recover somebody else's conversations.
        owner = credential_for(request, db)
        if owner.device_id != body.device_id:
            raise HTTPException(403, "不能访问其他设备的数据")
        token = request.headers['authorization'].split(' ', 1)[1]
        existing.expires_at = PERMANENT_ACCESS
    else:
        if db.scalar(select(Conversation.id).where(Conversation.device_id == body.device_id)):
            raise HTTPException(409, "本设备已有历史数据，请联系组织者恢复设备凭据")
        token = secrets.token_urlsafe(32)
        db.add(CloudCredential(digest=digest(token), device_id=body.device_id, expires_at=PERMANENT_ACCESS))
    try:
        db.commit()
    except IntegrityError as error:
        db.rollback()
        raise HTTPException(409, "设备接入冲突，请重试") from error
    return {"access_token": token, "token_type": "bearer", "expires_at": PERMANENT_ACCESS, "device_id": body.device_id}


@router.post("/activate")
def activate(body: ActivationRequest, request: Request, db: Session = Depends(get_db)) -> dict:
    if get_settings().cloud_open_enrollment:
        return bootstrap(BootstrapRequest(device_id=body.device_id), request, db)
    throttle("invite:" + (request.client.host if request.client else "unknown"), 5)
    token, expires_at = redeem(db, body.invite_code, body.device_id)
    return {"access_token": token, "token_type": "bearer", "expires_at": expires_at, "device_id": body.device_id}


@router.get("/me")
def me(request: Request, db: Session = Depends(get_db)) -> dict:
    credential = credential_for(request, db)
    return {"device_id": credential.device_id,
            "expires_at": PERMANENT_ACCESS if get_settings().cloud_open_enrollment else credential.expires_at}
