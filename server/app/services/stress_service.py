from collections import defaultdict
from datetime import UTC, date, datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, Message, ProactiveCheckIn, StressAlert, SupportPerson, UserRecord
from app.schemas.stress_alert import (
    StressAlertDetail,
    StressEvidenceItem,
    StressFollowUpItem,
    StressSupportItem,
    StressTrendPoint,
)
from app.schemas.support import SupportSuggestionRequest
from app.services.memory_service import local_day
from app.services.support_service import build_suggestion, choose_person


LOCAL_TIMEZONE = timezone(timedelta(hours=8))
ACTIVE_STATUSES = ("active", "confirmed", "follow_up_scheduled")
ACUTE_WORDS = (
    "想死", "不想活", "自杀", "轻生", "结束生命", "割腕", "伤害自己", "伤害别人", "杀了", "同归于尽",
)
RECOVERY_WORDS = (
    "好多了", "好转了", "已经好了", "没事了", "缓过来了", "恢复了", "不再焦虑",
    "不焦虑", "不紧张", "不担心", "压力不大", "没有压力", "没什么压力", "并没有压力", "并不焦虑",
)
FUTURE_ONLY_WORDS = ("以后可能会", "将来可能会", "明天可能会", "下周可能会", "下个月可能会", "到时候可能会")
WATCH_WORDS = (
    "压力", "紧张", "担心", "焦虑", "发慌", "很慌", "疲惫", "很累", "撑不住",
    "烦躁", "不安", "卡住", "没精神", "提不起劲", "负担", "喘不过气",
)
HIGH_WORDS = (
    "压力很大", "压力太大", "非常紧张", "特别焦虑", "很焦虑", "非常焦虑", "很慌",
    "快撑不住", "喘不过气", "崩溃", "难以承受", "无法应付",
)
FUNCTION_WORDS = (
    "睡不着", "睡不好", "失眠", "难以入睡", "注意力", "集中注意", "无法集中", "专注不了",
    "吃不下", "没胃口", "影响学习", "学不进去", "影响工作", "影响生活", "日常节奏",
)


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _today(now: datetime) -> date:
    return _aware(now).astimezone(LOCAL_TIMEZONE).date()


def _event_text(event: GrowthEvent) -> str:
    return " ".join(part.strip() for part in (event.fact, event.feeling or "") if part and part.strip())


def _evidence_text(db: Session, event: GrowthEvent) -> tuple[str, str]:
    if event.source_record_id:
        record = db.get(UserRecord, event.source_record_id)
        if record is not None:
            text = " ".join(part.strip() for part in (
                record.text,
                record.photo_comment,
                record.ai_description or "",
            ) if part and part.strip())
            return text or _event_text(event), "记录"
    if event.source_user_message_id:
        message = db.get(Message, event.source_user_message_id)
        if message is not None:
            source = {"text": "聊天", "image": "图片对话", "audio": "语音对话"}.get(message.modality, "聊天")
            return message.content or _event_text(event), source
    # 场景记录本身已经保存了完整原句；界面只展示事实字段，避免把情绪摘要再次拼接造成重复。
    return event.fact.strip(), "记录" if event.source_type == "stress_scenario" else "聊天"


def _event_level(event: GrowthEvent, now: datetime, *, scenario: bool) -> str | None:
    if event.sensitivity == "high":
        return None
    if scenario:
        if event.source_type != "stress_scenario":
            return None
    elif event.source_type in {"demo", "stress_scenario"}:
        return None
    elif event.source_record_id is None and event.source_user_message_id is None:
        # 来源被删除或不是用户主动提交的聊天/记录时，不再作为判断依据。
        return None
    event_day = date.fromisoformat(local_day(event.created_at))
    if event_day > _today(now):
        return None
    text = _event_text(event)
    if not text or any(word in text for word in ACUTE_WORDS):
        return None
    if any(word in text for word in RECOVERY_WORDS + FUTURE_ONLY_WORDS):
        return None
    if any(word in text for word in HIGH_WORDS) or any(word in text for word in FUNCTION_WORDS):
        return "high"
    return "watch" if any(word in text for word in WATCH_WORDS) else None


def analyze_recent_events(
    events: list[GrowthEvent],
    now: datetime | None = None,
    *,
    scenario: bool = False,
) -> tuple[str | None, list[dict], list[str], list[GrowthEvent], dict[int, str]]:
    now = now or datetime.now(UTC)
    today = _today(now)
    start = today - timedelta(days=6)
    daily: dict[str, list[tuple[GrowthEvent, str]]] = defaultdict(list)
    event_levels: dict[int, str] = {}
    for event in events:
        event_day = date.fromisoformat(local_day(event.created_at))
        if not start <= event_day <= today:
            continue
        level = _event_level(event, now, scenario=scenario)
        if level is None:
            continue
        daily[event_day.isoformat()].append((event, level))
        event_levels[event.id] = level

    trend: list[dict] = []
    signal_days: list[str] = []
    high_days: list[str] = []
    for offset in range(7):
        day = (start + timedelta(days=offset)).isoformat()
        rows = daily.get(day, [])
        level = "high" if any(row[1] == "high" for row in rows) else "watch" if rows else "steady"
        trend.append({"date": day, "level": level, "signal_count": len(rows)})
        if rows:
            signal_days.append(day)
        if level == "high":
            high_days.append(day)

    level: str | None = None
    if len(high_days) >= 2 or (len(signal_days) >= 3 and signal_days[-1] in high_days):
        level = "high"
    elif len(signal_days) >= 2:
        level = "watch"

    selected = [
        event
        for day in signal_days
        for event, _level in daily[day]
    ]
    factors = []
    if signal_days:
        factors.append(f"最近7天有{len(signal_days)}个不同日期出现压力信号")
    if high_days:
        factors.append(f"其中{len(high_days)}天出现较明显的压力或日常影响")
    if selected:
        sources = {
            "记录" if item.source_record_id or item.source_type == "stress_scenario" else "聊天"
            for item in selected
        }
        factors.append(f"依据来自{'和'.join(sorted(sources))}中的连续线索")
    return level, trend, factors, selected, event_levels


def evaluate_stress_alert(
    db: Session,
    conversation_id: int,
    now: datetime | None = None,
    *,
    scenario: bool = False,
) -> StressAlert | None:
    now = now or datetime.now(UTC)
    events = list(db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation_id,
    ).order_by(GrowthEvent.created_at, GrowthEvent.id)))
    level, trend, factors, selected, _event_levels = analyze_recent_events(events, now, scenario=scenario)
    if level is None:
        return None
    source_ids = [item.id for item in selected]
    active = db.scalar(select(StressAlert).where(
        StressAlert.conversation_id == conversation_id,
        StressAlert.is_scenario == scenario,
        StressAlert.status.in_(ACTIVE_STATUSES),
    ).order_by(StressAlert.id.desc()))
    if active is not None:
        active.level = level
        active.source_event_ids = source_ids
        active.trend = trend
        active.factors = factors
        active.updated_at = now
        db.flush()
        return active
    latest = db.scalar(select(StressAlert).where(
        StressAlert.conversation_id == conversation_id,
        StressAlert.is_scenario == scenario,
    ).order_by(StressAlert.id.desc()))
    if latest is not None and latest.status == "dismissed" and _aware(latest.updated_at) > now - timedelta(days=7):
        return None
    alert = StressAlert(
        conversation_id=conversation_id,
        level=level,
        status="active",
        source_event_ids=source_ids,
        trend=trend,
        factors=factors,
        is_scenario=scenario,
    )
    db.add(alert)
    db.flush()
    return alert


def adjusted_level(base_level: str, impact: str, coping: str) -> str:
    if impact == "significant" or coping == "overwhelmed" or (impact == "some" and coping == "strained"):
        return "high"
    if impact == "none" and coping == "manageable":
        return "watch"
    return base_level


def alert_detail(db: Session, alert: StressAlert, now: datetime | None = None) -> StressAlertDetail:
    now = now or datetime.now(UTC)
    events = [
        item for event_id in (alert.source_event_ids or [])
        if (item := db.get(GrowthEvent, event_id)) is not None and item.conversation_id == alert.conversation_id
    ]
    _level, _trend, _factors, _selected, event_levels = analyze_recent_events(
        events,
        now,
        scenario=alert.is_scenario,
    )
    evidence = []
    for event in events:
        if event.id not in event_levels:
            continue
        excerpt, source_type = _evidence_text(db, event)
        evidence.append(StressEvidenceItem(
            event_id=event.id,
            date=local_day(event.created_at),
            excerpt=excerpt[:160],
            source_type=source_type,
            source_message_id=event.source_user_message_id,
            source_record_id=event.source_record_id,
            level=event_levels.get(event.id, "watch"),
        ))

    situation = "；".join(item.excerpt for item in evidence[-3:]) or "最近一段时间压力持续存在"
    people = list(db.scalars(select(SupportPerson).where(
        SupportPerson.conversation_id == alert.conversation_id,
    ).order_by(SupportPerson.id)))
    support_request = SupportSuggestionRequest(
        situation=situation,
        preferred_supporters=["family", "friend", "teacher"],
    )
    suggestion = build_suggestion(support_request, choose_person(support_request, people))
    support = StressSupportItem(
        supporter_name=suggestion.supporter_name or "一位你信任的人",
        reason=suggestion.reason,
        editable_message=suggestion.editable_message,
        small_step=suggestion.small_step,
        lighter_option=suggestion.lighter_option,
    )
    follow_up = None
    if alert.follow_up_check_in_id:
        task = db.get(ProactiveCheckIn, alert.follow_up_check_in_id)
        if task is not None:
            available_after = _aware(task.available_after) if task.available_after else None
            follow_up = StressFollowUpItem(
                id=task.id,
                prompt=task.prompt,
                ready=task.status == "pending" and (available_after is None or available_after <= now),
            )
    effective_level = alert.confirmed_level or alert.level
    return StressAlertDetail(
        id=alert.id,
        level=effective_level,
        status=alert.status,
        title="最近的压力似乎持续了一阵子",
        prompt="它有没有开始影响你的睡眠、专注或日常节奏？",
        trend=[StressTrendPoint(**item) for item in (alert.trend or [])],
        factors=alert.factors or [],
        evidence=evidence,
        self_check=alert.self_check,
        support=support,
        support_copy="这不是诊断。它只是把你主动留下的连续压力线索放在一起，帮助你更早看见变化。",
        professional_support_hint="如果压力持续加重、明显影响生活，或你感到难以保证自己和他人的安全，请尽快联系可信任的人和当地专业支持。",
        follow_up=follow_up,
        created_at=alert.created_at,
    )
