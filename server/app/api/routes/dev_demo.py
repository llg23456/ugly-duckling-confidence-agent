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
DEMO_THEME = "四周考研准备：从怀疑自己，到学会求助、调整和照顾生活"


class DemoDataRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)


class DemoDataResponse(BaseModel):
    created: int = 0
    deleted: int = 0
    theme: str


DEMO_DAYS = [
    ("想到准备考研，我有些紧张，还不确定自己适合什么专业。", None, None),
    ("翻了目标院校的招生说明，把想弄清楚的三个问题记在纸上。", "查阅招生说明并写下三个问题", None),
    ("整理本科课程笔记，发现有些知识还记得，有些需要重新学。", "整理了一份基础知识清单", None),
    ("去图书馆看了半小时书，先试试适合自己的学习节奏。", "在图书馆尝试半小时学习", None),
    ("向学院老师请教专业方向，老师建议我先了解课程和研究内容。", "主动向学院老师请教考研方向", "学院老师帮我澄清了专业选择"),
    ("查了目标院校和专业要求，虽然忐忑，还是把想去的学校记了下来。", "查清目标院校和专业要求", None),
    ("买好第一批考研书，看了一节辅导课，列出可以调整的学习计划。", "买书、看辅导课并列出计划", None),
    ("第一次带书去图书馆认真学习，回宿舍有些累，发现计划排得太满。", "完成第一天学习并记录实际用时", None),
    ("做题错了很多，看着修改痕迹，我怀疑自己是不是基础太差。", "标出错题并订正了一部分", None),
    ("才认真学了两天就想放弃，我觉得没有毅力的人可能根本考不上。", None, None),
    ("向小鸭说出想放弃的感受，也把压力告诉父母。他们说愿意支持我慢慢准备。", "完成二十分钟学习并和父母沟通", "小鸭陪我拆小任务，父母听完给予支持"),
    ("联系了目标院校的师姐，问她如何安排基础复习。她分享了先抓薄弱点的经验。", "写下问题并主动联系目标院校师姐", "师姐分享了基础复习经验"),
    ("按师姐的建议调整计划，只保留今天最重要的两项任务，晚上准时休息。", "调整学习计划并主动休息", None),
    ("重新翻开书，仍然忐忑，但愿意先做好眼前的一小部分。", "带着忐忑继续当天学习", None),
    ("和同学在操场慢跑了十分钟，没有追求跑多快，回来精神轻松了一些。", "学习之余在操场慢跑十分钟", "同学陪我一起运动"),
    ("一道题卡住很久，我整理了自己的解题过程，准备向老师问一个具体问题。", "整理解题过程和具体疑问", None),
    ("学院老师指出我混淆了两个概念，我重新画图整理了它们的关系。", "向老师提问并用图整理概念", "学院老师解释了两个概念的区别"),
    ("这次复习仍然会做错，我开始记录错误原因，而不是只责怪自己。", "用错题原因调整复习方法", None),
    ("周末和朋友到公园户外散步，看到了湖边的树，也让自己离开书桌一会儿。", "到公园户外散步并休息", "朋友陪我散步和聊天"),
    ("师兄提醒我别只比较学习时长，我把计划改成每次弄懂一个问题。", "和目标院校师兄交流后调整任务", "师兄分享了更适合我的学习方法"),
    ("第三周结束，紧张还在，我已经知道可以提问、调整方法，也可以休息。", "回顾了本周尝试和照顾自己的方式", None),
    ("做了一次小测验，分数没有明显提高，但比之前更能说清楚哪些知识不懂。", "完成小测验并分析薄弱点", None),
    ("试着把一个知识点讲给同学听，发现讲不清的地方，就重新查了笔记。", "通过讲解检查理解并补充笔记", "同学愿意听我讲解"),
    ("今天累了，没有继续熬夜。我给明天留了一项任务，按时睡觉。", "停止熬夜并主动安排休息", None),
    ("又去操场运动了一会儿，发现认真准备考试也可以给生活留一点空间。", "安排学习之余的运动时间", None),
    ("向师姐反馈最近的调整，她说我能具体表达问题了，我也看见自己的主动。", "向师姐反馈进展并认真听取意见", "师姐给了关于表达问题的具体反馈"),
    ("我和父母聊了备考近况，既说学习，也说最近散步、运动和交朋友的经历。", "主动分享备考和生活近况", "父母愿意听我讲生活里的变化"),
    ("回看四周，我还没有考试结果，但遇到困难时更愿意尝试、求助和照顾自己。", "完成四周回顾并保留下一步计划", None),
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
            people=[name for name in ("父母", "同学", "学院老师", "师姐", "师兄", "朋友", "小鸭") if support and name in support],
            confidence=1.0,
            value_score=0.8,
            memory_decision="ignore",
            sensitivity="low",
            model="demo_seed",
            prompt_version="demo.v3",
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
