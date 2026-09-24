from datetime import UTC, datetime
from hashlib import sha256

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.db.models import Conversation, Message
from app.schemas.chat import ConversationMessage


def get_conversation(session: Session, device_id: str) -> Conversation | None:
    return session.scalar(select(Conversation).where(Conversation.device_id == device_id))


def get_or_create_conversation(session: Session, device_id: str) -> Conversation:
    conversation = get_conversation(session, device_id)
    if conversation is not None:
        return conversation
    conversation = Conversation(device_id=device_id)
    session.add(conversation)
    try:
        session.flush()
    except IntegrityError:
        session.rollback()
        conversation = get_conversation(session, device_id)
        if conversation is None:
            raise
    return conversation


def recent_messages(session: Session, conversation_id: int, limit: int = 12) -> list[Message]:
    newest_first = session.scalars(
        select(Message)
        .where(Message.conversation_id == conversation_id)
        .order_by(Message.id.desc())
        .limit(limit)
    ).all()
    return list(reversed(newest_first))


def all_messages(session: Session, conversation_id: int) -> list[Message]:
    return list(session.scalars(
        select(Message)
        .where(Message.conversation_id == conversation_id)
        .order_by(Message.id.asc())
    ))


def model_history(messages: list[Message]) -> list[dict[str, str]]:
    return [
        {
            "role": message.role,
            "content": f"[{message.modality}] {message.content}"
            if message.modality != "text" else message.content,
        }
        for message in messages
    ]


def append_exchange(
    session: Session,
    conversation: Conversation,
    *,
    user_content: str,
    assistant_content: str,
    modality: str,
    media_ref: str | None = None,
    is_mock: bool = False,
) -> tuple[Message, Message]:
    user_message = Message(
        conversation_id=conversation.id,
        role="user",
        modality=modality,
        content=user_content,
        media_ref=media_ref,
    )
    assistant_message = Message(
        conversation_id=conversation.id,
        role="assistant",
        modality=modality,
        content=assistant_content,
        is_mock=is_mock,
    )
    session.add_all((user_message, assistant_message))
    conversation.updated_at = datetime.now(UTC)
    session.commit()
    session.refresh(user_message)
    session.refresh(assistant_message)
    return user_message, assistant_message


def media_fingerprint(content: bytes) -> str:
    # A source identifier only. Uploaded media remains in memory during inference.
    return f"sha256:{sha256(content).hexdigest()}"


def as_schema(message: Message) -> ConversationMessage:
    created_at = message.created_at
    if created_at.tzinfo is None:  # SQLite returns naive datetimes.
        created_at = created_at.replace(tzinfo=UTC)
    return ConversationMessage(
        id=message.id,
        role=message.role,
        modality=message.modality,
        content=message.content,
        media_ref=message.media_ref,
        mock=message.is_mock,
        created_at=created_at,
    )
