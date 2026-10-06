from app.schemas import (
    ChatRequest,
    ChatResponse,
    EventExtractionRequest,
    EventExtractionResponse,
    GrowthEvent,
    ReviewResponse,
)
from app.schemas.review import ReviewMoment


def mock_chat(request: ChatRequest) -> ChatResponse:
    wants_step = request.mode == "suggest" or any(word in request.message for word in ("怎么办", "建议", "怎么调整"))
    if wants_step and any(word in request.message for word in ("累", "疲惫", "学不进去", "熬夜")):
        return ChatResponse(reply="听起来你有些累了。如果愿意，先离开书桌散步五分钟，回来再决定下一步。", strategy="small_step", evidence=[])
    if wants_step and any(word in request.message for word in ("目标院校", "师兄", "师姐", "备考经验")):
        return ChatResponse(reply="院校信息不清楚时，可以先写下一个问题，再问问目标院校的师兄师姐什么时候方便交流。", strategy="seek_support", evidence=[])
    return ChatResponse(
        reply="听起来你现在有些紧张。愿意说说最担心的那一小部分吗？",
        strategy="seek_support" if "答辩" in request.message else "listen",
        evidence=[],
    )


def mock_extract(request: EventExtractionRequest) -> EventExtractionResponse:
    return EventExtractionResponse(
        event=GrowthEvent(
            fact=request.text,
            feeling="紧张",
            attempt="主动准备课堂汇报",
            support_received=None,
            confidence=0.86,
        ),
        memory_decision="daily",
        reason="包含一次具体尝试，但是否具有长期价值仍需后续经历验证。",
    )


def mock_review(period: str) -> ReviewResponse:
    return ReviewResponse(
        period=period,
        title="九月的成长故事",
        own_effort="你主动表达困惑，并一次次练习开场。",
        support_received="老师回答了问题，室友陪你练习了两次。",
        moments=[
            ReviewMoment(date="9月5日", title="主动向老师提问", source="来自记录"),
            ReviewMoment(date="9月14日", title="请室友陪练两次", source="来自对话"),
            ReviewMoment(date="9月26日", title="完成课堂汇报开场", source="来自记录"),
        ],
        closing="你没有独自完成这一切，也没有少付出一分努力。",
    )
