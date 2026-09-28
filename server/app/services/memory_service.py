import re
from datetime import UTC, datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Memory, Message
from app.schemas.chat import MemoryEvidence


def _terms(value: str) -> set[str]:
    latin = re.findall(r"[a-z0-9]+", value.lower())
    han = re.findall(r"[\u4e00-\u9fff]+", value)
    return set(latin) | {part[i:i + 2] for part in han for i in range(len(part) - 1)}


def local_day(value: datetime) -> str:
    aware = value if value.tzinfo else value.replace(tzinfo=UTC)
    return aware.astimezone(timezone(timedelta(hours=8))).date().isoformat()


def recall(session: Session, conversation_id: int, query: str) -> list[MemoryEvidence]:
    """Keyword candidate search, followed by value and recency reranking."""
    terms = _terms(query)
    if not terms:
        return []
    ranked = []
    for memory in session.scalars(select(Memory).where(
        Memory.conversation_id == conversation_id, Memory.status == "active"
    )):
        source_id = (memory.source_message_ids or [None])[0]
        source = session.get(Message, source_id) if source_id else None
        if not source or source.conversation_id != conversation_id or source.role != "user":
            continue
        overlap = len(terms & _terms(memory.content)) / max(1, len(terms))
        if overlap == 0:
            continue
        age_days = max(0, (datetime.now(UTC) - memory.created_at.replace(tzinfo=UTC)).days)
        ranked.append((overlap, memory.value_score or 0, 1 / (1 + age_days / 30), memory, source))
    candidates = sorted(ranked, key=lambda row: row[:3], reverse=True)[:10]
    result = []
    for _, _, _, memory, source in candidates[:4]:
        result.append(MemoryEvidence(
            summary=memory.content,
            source_date=local_day(source.created_at),
            source_type={"text": "chat", "image": "photo", "audio": "voice"}.get(source.modality, "chat"),
            source_id=source.id,
            memory_id=memory.id,
        ))
    return result


def memory_prompt(evidence: list[MemoryEvidence]) -> str:
    if not evidence:
        return ""
    lines = [f"- {item.summary}（来源消息 {item.source_id}，{item.source_date}）" for item in evidence]
    return "可参考的用户记忆如下。只在与当前问题相关时自然提及，不要添加未经来源支持的数量、日期或经历：\n" + "\n".join(lines)
