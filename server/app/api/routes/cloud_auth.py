from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.core.cloud_access import credential_for, redeem, throttle
from app.db.session import get_db

router = APIRouter(prefix="/cloud-auth", tags=["cloud-test"])


class ActivationRequest(BaseModel):
    invite_code: str = Field(min_length=1, max_length=64)
    device_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


@router.post("/activate")
def activate(body: ActivationRequest, request: Request, db: Session = Depends(get_db)) -> dict:
    throttle("invite:" + (request.client.host if request.client else "unknown"), 5)
    token, expires_at = redeem(db, body.invite_code, body.device_id)
    return {"access_token": token, "token_type": "bearer", "expires_at": expires_at, "device_id": body.device_id}


@router.get("/me")
def me(request: Request, db: Session = Depends(get_db)) -> dict:
    credential = credential_for(request, db)
    return {"device_id": credential.device_id, "expires_at": credential.expires_at}
