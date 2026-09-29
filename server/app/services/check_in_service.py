from collections import Counter
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, ProactiveCheckIn
from app.services.memory_service import local_day


SIGNALS = {
    "stuck": ("卡住", "做不到", "没办法", "失败", "没进展", "不知道怎么", "不敢开始", "放弃"),
    "anxious": ("紧张", "害怕", "担心", "焦虑", "慌", "压力很大", "睡不着", "睡得不好"),
    "low": ("低落", "难受", "没精神", "提不起劲", "撑不住", "很疲惫", "很累", "不想动"),
}
EXPLICIT_FOLLOW_UP = ("之后问问我", "以后问问我", "明天问问我", "过几天问问我", "到时候提醒我", "记得再问我")
ACUTE_WORDS = ("想死", "不想活", "自杀", "伤害自己", "伤害别人", "杀了")


def _aware(value: datetime | None) -> datetime | None:
    if value is None or value.tzinfo is not None:
        return value
    return value.replace(tzinfo=UTC)


def _category(event: GrowthEvent) -> str | None:
    text = " ".join(filter(None, (event.fact, event.feeling))).strip()
    if not text or event.sensitivity == "high" or any(word in text for word in ACUTE_WORDS):
        return None
    if any(word in text for word in EXPLICIT_FOLLOW_UP):
        return "follow_up"
    matches = [key for key, words in SIGNALS.items() if any(word in text for word in words)]
    return matches[0] if matches else None


def _prompt(reason: str, anchor: GrowthEvent) -> str:
    if reason == "follow_up":
        return "你之前希望小鸭之后再来问问。现在想说说这件事后来怎么样了吗？"
    if reason == "anxious":
        return "这几天你不止一次提到紧张或担心。小鸭记着这件事，今天想不想说说现在怎么样了？"
    if reason == "low":
        return "这几天你几次提到有点累或难受。小鸭想先轻轻问一句：现在的你还好吗？"
    return "这几天你不止一次提到有些地方卡住了。现在再回头看，最难的那一小段还在吗？"


def explicit_follow_up_requested(text: str) -> bool:
    normalized = text.strip()
    return (
        bool(normalized)
        and not any(word in normalized for word in ACUTE_WORDS)
        and any(word in normalized for word in EXPLICIT_FOLLOW_UP)
    )


def schedule_explicit_follow_up(
    db: Session,
    conversation_id: int,
    text: str,
    now: datetime | None = None,
) -> ProactiveCheckIn | None:
    """Create an explicit future follow-up immediately, without waiting for AI extraction."""
    if not explicit_follow_up_requested(text):
        return None
    now = now or datetime.now(UTC)
    latest_task = db.scalar(select(ProactiveCheckIn).where(
        ProactiveCheckIn.conversation_id == conversation_id,
    ).order_by(ProactiveCheckIn.id.desc()))
    if latest_task is not None:
        if latest_task.status == "pending":
            return None
        cooldown_until = _aware(latest_task.cooldown_until)
        if cooldown_until is not None and cooldown_until > now:
            return None
    task = ProactiveCheckIn(
        conversation_id=conversation_id,
        reason="follow_up",
        prompt="你之前希望小鸭之后再来问问。现在想说说这件事后来怎么样了吗？",
        source_event_ids=[],
        status="pending",
        # 当前响应会立即告诉用户“小鸭记住了”，因此不再重复弹建立提示。
        notified_at=now,
    )
    db.add(task)
    db.flush()
    return task


def evaluate_check_in(db: Session, conversation_id: int, now: datetime | None = None) -> ProactiveCheckIn | None:
    now = now or datetime.now(UTC)
    latest_task = db.scalar(select(ProactiveCheckIn).where(
        ProactiveCheckIn.conversation_id == conversation_id,
    ).order_by(ProactiveCheckIn.id.desc()))
    if latest_task is not None:
        if latest_task.status == "pending":
            if latest_task.reason == "follow_up" and not latest_task.source_event_ids:
                explicit_event = db.scalar(select(GrowthEvent).where(
                    GrowthEvent.conversation_id == conversation_id,
                ).order_by(GrowthEvent.created_at.desc(), GrowthEvent.id.desc()))
                if explicit_event is not None and _category(explicit_event) == "follow_up":
                    latest_task.source_event_ids = [explicit_event.id]
                    latest_task.updated_at = now
                    db.flush()
            return latest_task
        cooldown_until = _aware(latest_task.cooldown_until)
        if cooldown_until is not None and cooldown_until > now:
            return None

    recent = list(db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation_id,
    ).order_by(GrowthEvent.created_at.desc(), GrowthEvent.id.desc())))
    recent = [item for item in recent if (_aware(item.created_at) or now) >= now - timedelta(days=14)]
    classified = [(item, _category(item)) for item in recent]
    signals = [(item, reason) for item, reason in classified if reason is not None]
    if not signals:
        return None

    explicit = next(((item, reason) for item, reason in signals if reason == "follow_up"), None)
    if explicit is not None:
        selected = [explicit[0]]
        reason = "follow_up"
    else:
        recent_enough = [pair for pair in signals if (_aware(pair[0].created_at) or now) >= now - timedelta(days=7)]
        counts = Counter(reason for _, reason in recent_enough)
        if not counts:
            return None
        reason, count = counts.most_common(1)[0]
        same_reason = [item for item, item_reason in recent_enough if item_reason == reason]
        days = {local_day(item.created_at) for item in same_reason}
        if count < 2 or len(days) < 2:
            return None
        selected = list(reversed(same_reason[:3]))

    selected_ids = [item.id for item in selected]
    if latest_task is not None and set(selected_ids).issubset(set(latest_task.source_event_ids or [])):
        return None
    task = ProactiveCheckIn(
        conversation_id=conversation_id,
        reason=reason,
        prompt=_prompt(reason, selected[-1]),
        source_event_ids=selected_ids,
        status="pending",
    )
    db.add(task)
    db.flush()
    return task
