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
DEMO_THEME = "9月7日至13日：从怀疑自己考不上，到带着忐忑坚定备考"


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
    ("决定认真准备考研后，我查了目标院校和专业方向。看着录取要求有些忐忑，但还是把想去的学校记了下来。", "查清目标院校和专业要求", None),
    ("买好了第一批考研书，也看了一节辅导课。我给自己列了学习计划，觉得终于可以开始了。", "买书、看辅导课并列出计划", None),
    ("第一次带着书去图书馆，从早学到晚。回宿舍时很累，但至少完成了计划里的第一天。", "在图书馆完成第一天学习", None),
    ("第二天继续做题，却错了很多。看着满页修改痕迹，我开始怀疑自己是不是基础太差。", "把错题标出来并订正了一部分", None),
    ("只坚持了两天就不想学了。我觉得自己没有毅力，这样的人可能根本考不上，甚至想把考研计划放弃。", None, None),
    ("我把想放弃和不自信告诉了小鸭。小鸭没有催我，只陪我把任务缩成二十分钟，还建议我把压力告诉家人；父母听完后说愿意支持我慢慢准备。", "先完成二十分钟学习，并主动和父母沟通", "小鸭陪我拆小任务，父母听完后给予支持"),
    ("今天重新翻开了书。虽然想到考研还是有点忐忑，但我不再认定自己一定做不到；每天和小鸭聊一聊，情绪慢慢缓下来，我愿意继续坚定地往前走。", "带着忐忑重新开始当天的学习", "持续聊天和家人的支持让我找回了一些动力"),
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
            feeling="不自信" if "不自信" in fact else "忐忑" if "忐忑" in fact else "紧张" if any(word in fact for word in ("紧张", "担心", "害怕")) else None,
            attempt=effort,
            own_effort=effort,
            support_received=support,
            people=["父母"] if support and any(word in support for word in ("父母", "家人")) else ["同学"] if support and "同学" in support else ["老师"] if support and "老师" in support else [],
            confidence=1.0,
            value_score=0.8,
            memory_decision="ignore",
            sensitivity="low",
            model="demo_seed",
            prompt_version="demo.v2",
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
