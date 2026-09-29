import re
import math
from datetime import UTC, datetime, timedelta, timezone

from openai import OpenAI

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import Memory, Message, UserRecord
from app.schemas.chat import MemoryEvidence


def _terms(value: str) -> set[str]:
    latin = re.findall(r"[a-z0-9]+", value.lower())
    han = re.findall(r"[\u4e00-\u9fff]+", value)
    return set(latin) | {part[i:i + 2] for part in han for i in range(len(part) - 1)}


def local_day(value: datetime) -> str:
    aware = value if value.tzinfo else value.replace(tzinfo=UTC)
    return aware.astimezone(timezone(timedelta(hours=8))).date().isoformat()


def embeddings_for(texts: list[str]) -> list[list[float]]:
    settings = get_settings()
    cleaned = [text[:4000] for text in texts if text.strip()]
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip() or not cleaned:
        return []
    try:
        client = OpenAI(
            api_key=settings.dashscope_api_key,
            base_url=settings.dashscope_base_url,
            timeout=20,
            max_retries=0,
        )
        response = client.embeddings.create(model=settings.embedding_model, input=cleaned)
        return [list(item.embedding) for item in sorted(response.data, key=lambda item: item.index)]
    except Exception:
        return []


def embedding_for(text: str) -> list[float] | None:
    vectors = embeddings_for([text])
    return vectors[0] if vectors else None


def _cosine(left: list[float] | None, right: list[float] | None) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    norm = math.sqrt(sum(a * a for a in left) * sum(b * b for b in right))
    return dot / norm if norm else 0.0


def set_memory_embedding(memory: Memory) -> None:
    vector = embedding_for(memory.content)
    if vector:
        memory.embedding = vector
        memory.embedding_model = get_settings().embedding_model


def _source(session: Session, memory: Memory, conversation_id: int):
    source_id = (memory.source_message_ids or [None])[0]
    message = session.get(Message, source_id) if source_id else None
    if message and message.conversation_id == conversation_id and message.role == "user":
        return message, message.id, {"text": "chat", "image": "photo", "audio": "voice"}.get(message.modality, "chat")
    return None


def _record_text(record: UserRecord) -> str:
    text = "\n".join(part.strip() for part in (
        record.text,
        record.photo_comment,
        record.ai_description or "",
    ) if part and part.strip())
    if text:
        return text
    return {"photo": "保存了一张照片记录", "voice": "保存了一段语音记录"}.get(record.mode, "保存了一条生活记录")


def recall(session: Session, conversation_id: int, query: str) -> list[MemoryEvidence]:
    """Hybrid keyword/vector search, followed by value and recency reranking."""
    terms = _terms(query)
    query_embedding = embedding_for(query)
    if not terms and not query_embedding:
        return []
    ranked: list[tuple[float, float, float, MemoryEvidence]] = []
    memories = list(session.scalars(select(Memory).where(
        Memory.conversation_id == conversation_id, Memory.status == "active"
    )))
    records = list(session.scalars(select(UserRecord).where(
        UserRecord.conversation_id == conversation_id,
        UserRecord.status == "saved",
    )))
    if query_embedding:
        missing = [memory for memory in memories if not memory.embedding][:50]
        vectors = embeddings_for([memory.content for memory in missing])
        if len(vectors) == len(missing):
            for memory, vector in zip(missing, vectors):
                memory.embedding = vector
                memory.embedding_model = get_settings().embedding_model
        missing_records = [record for record in records if not record.embedding][:50]
        record_vectors = embeddings_for([_record_text(record) for record in missing_records])
        if len(record_vectors) == len(missing_records):
            for record, vector in zip(missing_records, record_vectors):
                record.embedding = vector
                record.embedding_model = get_settings().embedding_model
    for memory in memories:
        source_info = _source(session, memory, conversation_id)
        if not source_info:
            continue
        source, source_id, source_type = source_info
        overlap = len(terms & _terms(memory.content)) / max(1, len(terms)) if terms else 0.0
        semantic = max(0.0, _cosine(query_embedding, memory.embedding))
        if overlap == 0 and semantic < 0.35:
            continue
        age_days = max(0, (datetime.now(UTC) - memory.created_at.replace(tzinfo=UTC)).days)
        relevance = (0.55 * overlap + 0.45 * semantic) if query_embedding else overlap
        ranked.append((
            relevance,
            memory.value_score or 0,
            1 / (1 + age_days / 30),
            MemoryEvidence(
                summary=memory.content,
                source_date=local_day(source.created_at),
                source_type=source_type,
                source_id=source_id,
                memory_id=memory.id,
            ),
        ))
    for record in records:
        content = _record_text(record)
        overlap = len(terms & _terms(content)) / max(1, len(terms)) if terms else 0.0
        semantic = max(0.0, _cosine(query_embedding, record.embedding))
        if overlap == 0 and semantic < 0.35:
            continue
        age_days = max(0, (datetime.now(UTC) - record.created_at.replace(tzinfo=UTC)).days)
        relevance = (0.55 * overlap + 0.45 * semantic) if query_embedding else overlap
        ranked.append((
            relevance,
            1.0,
            1 / (1 + age_days / 30),
            MemoryEvidence(
                summary=content,
                source_date=local_day(record.created_at),
                source_type={"text": "record", "photo": "photo", "voice": "voice"}.get(record.mode, "record"),
                source_record_id=record.id,
            ),
        ))
    return [row[3] for row in sorted(ranked, key=lambda row: row[:3], reverse=True)[:4]]


def memory_prompt(evidence: list[MemoryEvidence]) -> str:
    if not evidence:
        return ""
    lines = []
    for item in evidence:
        source = f"记录 {item.source_record_id}" if item.source_record_id else f"消息 {item.source_id}"
        lines.append(f"- {item.summary}（来源{source}，{item.source_date}）")
    return "可参考的用户记录与记忆如下。只在与当前问题相关时自然提及，不要添加未经来源支持的数量、日期或经历：\n" + "\n".join(lines)
