from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, ProactiveCheckIn, StressAlert
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.schemas.stress_alert import (
    ActiveStressAlertResponse,
    StressAlertDetail,
    StressDeviceRequest,
    StressSelfCheckRequest,
)
from app.services.stress_service import ACTIVE_STATUSES, adjusted_level, alert_detail, evaluate_stress_alert


router = APIRouter(prefix="/stress-alerts", tags=["stress-alerts"])


def _owned_alert(db: Session, alert_id: int, device_id: str) -> StressAlert:
    conversation = get_conversation(db, device_id)
    alert = db.get(StressAlert, alert_id)
    if conversation is None or alert is None or alert.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="压力提醒不存在")
    return alert


def _clear_scenario(db: Session, conversation_id: int) -> int:
    old_alerts = list(db.scalars(select(StressAlert).where(
        StressAlert.conversation_id == conversation_id,
        StressAlert.is_scenario.is_(True),
    )))
    follow_up_ids = [item.follow_up_check_in_id for item in old_alerts if item.follow_up_check_in_id]
    for item in old_alerts:
        db.delete(item)
    db.flush()
    if follow_up_ids:
        db.execute(delete(ProactiveCheckIn).where(ProactiveCheckIn.id.in_(follow_up_ids)))
    result = db.execute(delete(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation_id,
        GrowthEvent.source_type == "stress_scenario",
    ))
    return result.rowcount or 0


@router.get("/active", response_model=ActiveStressAlertResponse)
def active_alert(
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> ActiveStressAlertResponse:
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return ActiveStressAlertResponse()
    alert = db.scalar(select(StressAlert).where(
        StressAlert.conversation_id == conversation.id,
        StressAlert.status.in_(ACTIVE_STATUSES),
    ).order_by(StressAlert.updated_at.desc(), StressAlert.id.desc()))
    return ActiveStressAlertResponse(alert=alert_detail(db, alert) if alert else None)


@router.get("/{alert_id}", response_model=StressAlertDetail)
def get_alert(
    alert_id: int,
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> StressAlertDetail:
    return alert_detail(db, _owned_alert(db, alert_id, device_id))


@router.post("/prepare-scenario", response_model=StressAlertDetail)
def prepare_scenario(request: StressDeviceRequest, db: Session = Depends(get_db)) -> StressAlertDetail:
    conversation = get_or_create_conversation(db, request.device_id)
    _clear_scenario(db, conversation.id)
    now = datetime.now(UTC)
    rows = (
        (4, "最近任务有点多，想到接下来的安排时总有些紧张。", "有些不安"),
        (2, "这两天压力很大，晚上翻来覆去睡不好。", "很疲惫"),
        (0, "一想到复习安排就很慌，坐下来也很难集中注意力。", "难以专注"),
    )
    for days_ago, fact, feeling in rows:
        db.add(GrowthEvent(
            conversation_id=conversation.id,
            fact=fact,
            feeling=feeling,
            source_message_ids=[],
            source_type="stress_scenario",
            confidence=1.0,
            value_score=0.0,
            memory_decision="ignore",
            sensitivity="low",
            model="stress-rule-v1",
            prompt_version="stress-rule-v1",
            created_at=now - timedelta(days=days_ago),
        ))
    db.flush()
    alert = evaluate_stress_alert(db, conversation.id, now, scenario=True)
    if alert is None:
        raise HTTPException(status_code=500, detail="压力趋势准备失败")
    db.commit()
    db.refresh(alert)
    return alert_detail(db, alert, now)


@router.delete("/scenario", response_model=dict)
def clear_scenario(
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
) -> dict:
    conversation = get_conversation(db, device_id)
    if conversation is None:
        return {"deleted": 0}
    deleted = _clear_scenario(db, conversation.id)
    db.commit()
    return {"deleted": deleted}


@router.post("/{alert_id}/self-check", response_model=StressAlertDetail)
def submit_self_check(
    alert_id: int,
    request: StressSelfCheckRequest,
    db: Session = Depends(get_db),
) -> StressAlertDetail:
    alert = _owned_alert(db, alert_id, request.device_id)
    now = datetime.now(UTC)
    alert.self_check = {"impact": request.impact, "coping": request.coping, "submitted_at": now.isoformat()}
    alert.confirmed_level = adjusted_level(alert.level, request.impact, request.coping)
    alert.status = "confirmed"
    alert.actions = [*(alert.actions or []), {"type": "self_check", "at": now.isoformat()}]
    alert.updated_at = now
    db.commit()
    db.refresh(alert)
    return alert_detail(db, alert, now)


@router.post("/{alert_id}/follow-up", response_model=StressAlertDetail)
def schedule_follow_up(
    alert_id: int,
    request: StressDeviceRequest,
    db: Session = Depends(get_db),
) -> StressAlertDetail:
    alert = _owned_alert(db, alert_id, request.device_id)
    now = datetime.now(UTC)
    task = db.get(ProactiveCheckIn, alert.follow_up_check_in_id) if alert.follow_up_check_in_id else None
    if task is None:
        task = ProactiveCheckIn(
            conversation_id=alert.conversation_id,
            reason="stress_follow_up",
            prompt="昨天你提到这段压力持续了一阵子。今天感觉怎么样？",
            source_event_ids=alert.source_event_ids or [],
            status="pending",
            available_after=now + timedelta(days=1),
        )
        db.add(task)
        db.flush()
        alert.follow_up_check_in_id = task.id
    alert.status = "follow_up_scheduled"
    alert.actions = [*(alert.actions or []), {"type": "follow_up_scheduled", "at": now.isoformat()}]
    alert.updated_at = now
    db.commit()
    db.refresh(alert)
    return alert_detail(db, alert, now)


@router.post("/{alert_id}/follow-up/ready", response_model=StressAlertDetail)
def make_follow_up_ready(
    alert_id: int,
    request: StressDeviceRequest,
    db: Session = Depends(get_db),
) -> StressAlertDetail:
    alert = _owned_alert(db, alert_id, request.device_id)
    if not alert.is_scenario:
        raise HTTPException(status_code=409, detail="真实回访会在到期后出现")
    task = db.get(ProactiveCheckIn, alert.follow_up_check_in_id) if alert.follow_up_check_in_id else None
    if task is None or task.status != "pending":
        raise HTTPException(status_code=409, detail="没有可推进的回访")
    now = datetime.now(UTC)
    task.available_after = now
    task.updated_at = now
    alert.updated_at = now
    db.commit()
    db.refresh(alert)
    return alert_detail(db, alert, now)


@router.post("/{alert_id}/dismiss", response_model=StressAlertDetail)
def dismiss_alert(
    alert_id: int,
    request: StressDeviceRequest,
    db: Session = Depends(get_db),
) -> StressAlertDetail:
    alert = _owned_alert(db, alert_id, request.device_id)
    now = datetime.now(UTC)
    alert.status = "dismissed"
    if alert.follow_up_check_in_id:
        task = db.get(ProactiveCheckIn, alert.follow_up_check_in_id)
        if task is not None and task.status == "pending":
            task.status = "dismissed"
            task.responded_at = now
            task.updated_at = now
    alert.actions = [*(alert.actions or []), {"type": "dismissed", "at": now.isoformat()}]
    alert.updated_at = now
    db.commit()
    db.refresh(alert)
    return alert_detail(db, alert, now)
