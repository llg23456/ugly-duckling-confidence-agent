from datetime import UTC, datetime, time, timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import DailySummary, GrowthEvent, ProactiveCheckIn, Review, UserRecord
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.services.review_service import today_local
from app.services.check_in_service import evaluate_check_in


router = APIRouter(prefix="/dev/demo-data", tags=["development"])
DEMO_PREFIX = "demo-growth-"
DEMO_THEME = "从害怕课堂展示，到完成一次小组汇报"


class DemoDataRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)


class DemoDataResponse(BaseModel):
    created: int = 0
    deleted: int = 0
    theme: str


DEMO_DAYS = [
    ("知道下个月要做小组汇报，第一反应是紧张，担心自己说不好。", None, None),
    ("把汇报要求重新读了一遍，先圈出了自己没看懂的地方。", "重新读了一遍要求", None),
    ("本来想开始整理资料，却因为不知道从哪里下手拖了一会儿。", "打开了资料文件", None),
    ("只写下了三个最想讲清楚的问题，没有逼自己一次完成。", "写下三个问题", None),
    ("查到一篇有用的资料，终于对汇报主题有了一点方向。", "整理了一条有用资料", None),
    ("休息了一天，没有继续准备，但把想到的内容随手记了下来。", "留下了一句想法", None),
    ("回看这一周，虽然进展不快，但已经不再完全不知道怎么开始。", "回看并确认了起点", None),
    ("把汇报拆成开场、主体和结尾三部分，任务看起来没那么吓人了。", "拆分了汇报结构", None),
    ("写开场时反复删改，还是觉得自己的表达不够好。", "完成了开场初稿", None),
    ("对着手机试讲了一分钟，声音有点小，但坚持说完了。", "完成一分钟试讲", None),
    ("听回自己的录音，发现语速太快，于是标出了需要停顿的位置。", "回听并标注停顿", None),
    ("完成了第一版提纲，内容还不完整，但已经能够顺着讲下来。", "完成第一版提纲", None),
    ("今天有些累，只修改了一个段落，就决定先停下来。", "修改一个段落后主动休息", None),
    ("第二周结束时，已经有了可以继续修改的完整框架。", "保留了一份完整框架", None),
    ("把提纲发给同学看，有点怕被否定，但还是发出去了。", "主动请同学看提纲", "同学愿意帮忙看提纲"),
    ("同学说例子有些抽象，我补了一张更直观的图片。", "根据反馈补充图片", "同学指出了表达不清的地方"),
    ("试着站起来完整讲了一遍，中间卡住两次，但没有直接放弃。", "完成一次完整试讲", None),
    ("针对卡住的地方做了提示卡，第二遍比第一遍顺了一些。", "制作提示卡并再次练习", None),
    ("向老师确认了一个概念，发现之前担心的问题其实可以简化。", "主动向老师确认问题", "老师帮助澄清了概念"),
    ("和组员一起调整了分工，我负责的部分变得更清楚。", "说明自己的进度并调整分工", "组员一起调整了分工"),
    ("第三周结束，紧张还在，但已经知道卡住时可以怎么继续。", "总结了应对卡顿的方法", None),
    ("在空教室进行了一次模拟汇报，开头仍然紧张，后面慢慢稳定下来。", "完成模拟汇报", None),
    ("根据模拟结果删掉了两页不重要的内容，重点更清楚了。", "精简了两页内容", None),
    ("汇报前一晚还是睡得不太好，但没有继续熬夜修改。", "按计划停止修改并休息", None),
    ("正式汇报时开头声音有些抖，但把准备的内容完整讲完了。", "完成正式汇报", None),
    ("回答问题时有一处没答好，结束后把它记下来准备再弄懂。", "记录没答好的问题", None),
    ("老师肯定了结构清楚，同学也说比试讲时更自然。", "认真听取反馈", "收到了老师和同学的具体反馈"),
    ("回看这四周，变化不是突然不紧张，而是紧张时也能继续往下做。", "完成四周回顾", None),
]


def _ensure_development() -> None:
    if get_settings().app_env.lower() == "production":
        raise HTTPException(status_code=404, detail="演示数据工具仅在开发环境可用")


def _clear_demo_rows(db: Session, conversation_id: int) -> int:
    demo_records = db.scalars(select(UserRecord).where(
        UserRecord.conversation_id == conversation_id,
        UserRecord.client_record_id.like(f"{DEMO_PREFIX}%"),
    )).all()
    record_ids = [item.id for item in demo_records]
    event_ids = list(db.scalars(select(GrowthEvent.id).where(
        GrowthEvent.conversation_id == conversation_id,
        GrowthEvent.source_record_id.in_(record_ids),
    ))) if record_ids else []
    if event_ids:
        for check_in in db.scalars(select(ProactiveCheckIn).where(ProactiveCheckIn.conversation_id == conversation_id)).all():
            if set(check_in.source_event_ids or []).intersection(event_ids):
                db.delete(check_in)
        db.execute(delete(GrowthEvent).where(GrowthEvent.id.in_(event_ids)))
    if record_ids:
        db.execute(delete(UserRecord).where(UserRecord.id.in_(record_ids)))
    # 回望与日摘要是派生缓存；清除后会依据剩余真实事件重新生成。
    db.execute(delete(Review).where(Review.conversation_id == conversation_id))
    db.execute(delete(DailySummary).where(DailySummary.conversation_id == conversation_id))
    return len(demo_records)


@router.post("", response_model=DemoDataResponse)
def create_demo_data(request: DemoDataRequest, db: Session = Depends(get_db)) -> DemoDataResponse:
    _ensure_development()
    conversation = get_or_create_conversation(db, request.device_id)
    _clear_demo_rows(db, conversation.id)
    yesterday = today_local() - timedelta(days=1)
    first_day = yesterday - timedelta(days=len(DEMO_DAYS) - 1)
    for index, (fact, effort, support) in enumerate(DEMO_DAYS):
        day = first_day + timedelta(days=index)
        created_at = datetime.combine(day, time(hour=4), tzinfo=UTC)  # 北京时间中午 12 点
        record = UserRecord(
            conversation_id=conversation.id,
            client_record_id=f"{DEMO_PREFIX}{day.isoformat()}",
            mode="text",
            text=fact,
            photo_comment="",
            status="saved",
            created_at=created_at,
            updated_at=datetime.now(UTC),
        )
        db.add(record)
        db.flush()
        db.add(GrowthEvent(
            conversation_id=conversation.id,
            source_record_id=record.id,
            source_message_ids=[],
            source_type="demo",
            fact=fact,
            feeling="紧张" if any(word in fact for word in ("紧张", "担心", "怕")) else None,
            attempt=effort,
            own_effort=effort,
            support_received=support,
            people=["同学"] if support and "同学" in support else ["老师"] if support else [],
            confidence=1.0,
            value_score=0.8,
            memory_decision="ignore",
            sensitivity="low",
            model="demo_seed",
            prompt_version="demo.v1",
            created_at=created_at,
        ))
    db.flush()
    evaluate_check_in(db, conversation.id)
    db.commit()
    return DemoDataResponse(created=len(DEMO_DAYS), theme=DEMO_THEME)


@router.delete("", response_model=DemoDataResponse)
def delete_demo_data(request: DemoDataRequest, db: Session = Depends(get_db)) -> DemoDataResponse:
    _ensure_development()
    conversation = get_conversation(db, request.device_id)
    if conversation is None:
        return DemoDataResponse(theme=DEMO_THEME)
    deleted = _clear_demo_rows(db, conversation.id)
    db.commit()
    return DemoDataResponse(deleted=deleted, theme=DEMO_THEME)
