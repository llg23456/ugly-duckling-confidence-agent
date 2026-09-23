from app.schemas import (
    ChatRequest,
    ChatResponse,
    EventExtractionRequest,
    EventExtractionResponse,
    GrowthEvent,
    ReviewResponse,
    SupportSuggestionRequest,
    SupportSuggestionResponse,
)
from app.schemas.chat import MemoryEvidence
from app.schemas.review import ReviewMoment


def mock_chat(request: ChatRequest) -> ChatResponse:
    return ChatResponse(
        reply="紧张是真的，但你不是从零开始。上周你已经完整练习过三次。",
        strategy="seek_support" if "答辩" in request.message else "listen",
        evidence=[
            MemoryEvidence(summary="完成了三次完整练习", source_date="2026-09-16", source_type="record")
        ],
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


def mock_support(request: SupportSuggestionRequest) -> SupportSuggestionResponse:
    supporter = request.preferred_supporters[0] if request.preferred_supporters else "classmate"
    return SupportSuggestionResponse(
        supporter_type=supporter,
        reason="找熟悉的同学进行短时间陪练，压力通常比正式模拟更低。",
        editable_message="我明天要答辩，有点紧张。你愿意听我练一下30秒开场吗？",
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
