from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, ProactiveCheckIn, StressAlert
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas.check_in import (
    CheckInItem, CheckInResponseRequest, CheckInResponseResult, PendingCheckInResponse,
)


router = APIRouter(prefix="/check-ins", tags=["check-ins"])


def _owned_pending(db: Session, check_in_id: int, device_id: str) -> ProactiveCheckIn:
    conversation = get_conversation(db, device_id)
    check_in = db.get(ProactiveCheckIn, check_in_id)
    if conversation is None or check_in is None or check_in.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="主动问候不存在")
    if check_in.status != "pending":
        raise HTTPException(status_code=409, detail="这次问候已经回应过")
    return check_in


@router.get("/pending", response_model=PendingCheckInResponse)
def pending_check_in(
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> PendingCheckInResponse:
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return PendingCheckInResponse()
    now = datetime.now(UTC)
    check_in = db.scalar(select(ProactiveCheckIn).where(
        ProactiveCheckIn.conversation_id == conversation.id,
        ProactiveCheckIn.status == "pending",
        or_(ProactiveCheckIn.available_after.is_(None), ProactiveCheckIn.available_after <= now),
    ).order_by(ProactiveCheckIn.id.desc()))
    if check_in is None:
        return PendingCheckInResponse()
    if check_in.shown_at is None:
        check_in.shown_at = now
        check_in.notified_at = check_in.notified_at or check_in.shown_at
        check_in.updated_at = check_in.shown_at
        db.commit()
    return PendingCheckInResponse(check_in=CheckInItem(
        id=check_in.id,
        reason=check_in.reason,
        prompt=check_in.prompt,
        source_event_ids=check_in.source_event_ids or [],
        created_at=check_in.created_at,
    ))


@router.get("/notice", response_model=PendingCheckInResponse)
def newly_created_check_in_notice(
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> PendingCheckInResponse:
    """Consume only the one-time 'scheduled' notice; keep the future question pending."""
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return PendingCheckInResponse()
    now = datetime.now(UTC)
    check_in = db.scalar(select(ProactiveCheckIn).where(
        ProactiveCheckIn.conversation_id == conversation.id,
        ProactiveCheckIn.status == "pending",
        ProactiveCheckIn.notified_at.is_(None),
        ProactiveCheckIn.shown_at.is_(None),
        or_(ProactiveCheckIn.available_after.is_(None), ProactiveCheckIn.available_after <= now),
    ).order_by(ProactiveCheckIn.id.desc()))
    if check_in is None:
        return PendingCheckInResponse()
    check_in.notified_at = now
    check_in.updated_at = check_in.notified_at
    db.commit()
    return PendingCheckInResponse(check_in=CheckInItem(
        id=check_in.id,
        reason=check_in.reason,
        prompt=check_in.prompt,
        source_event_ids=check_in.source_event_ids or [],
        created_at=check_in.created_at,
    ))


@router.post("/{check_in_id}/respond", response_model=CheckInResponseResult)
def respond_to_check_in(
    check_in_id: int,
    request: CheckInResponseRequest,
    db: Session = Depends(get_db),
) -> CheckInResponseResult:
    check_in = _owned_pending(db, check_in_id, request.device_id)
    now = datetime.now(UTC)
    check_in.status = {"talk": "accepted", "improved": "improved", "not_now": "dismissed"}[request.choice]
    check_in.responded_at = now
    check_in.updated_at = now
    check_in.cooldown_until = now + timedelta(days={"talk": 1, "improved": 3, "not_now": 7}[request.choice])

    follow_up = None
    if request.choice == "talk":
        source = db.get(GrowthEvent, (check_in.source_event_ids or [])[-1]) if check_in.source_event_ids else None
        follow_up = f"我想聊聊最近这件事：{source.fact}" if source else "我想聊聊你刚刚主动问我的这件事。"
        acknowledgement = "好，我在这里。我们就从你最想说的一点开始。"
    elif request.choice == "improved":
        acknowledgement = "听到你比之前好一点，小鸭替你松了一口气。今天不用继续展开也可以。"
    else:
        acknowledgement = "好，这次先不说。小鸭会尊重你的节奏，最近几天不会再追问。"
    if check_in.reason == "stress_follow_up":
        alert = db.scalar(select(StressAlert).where(
            StressAlert.follow_up_check_in_id == check_in.id,
            StressAlert.conversation_id == check_in.conversation_id,
        ))
        if alert is not None:
            alert.status = {"talk": "confirmed", "improved": "resolved", "not_now": "dismissed"}[request.choice]
            alert.actions = [*(alert.actions or []), {
                "type": f"follow_up_{request.choice}",
                "at": now.isoformat(),
            }]
            alert.updated_at = now
    db.commit()
    return CheckInResponseResult(
        id=check_in.id,
        choice=request.choice,
        acknowledgement=acknowledgement,
        follow_up_message=follow_up,
    )
