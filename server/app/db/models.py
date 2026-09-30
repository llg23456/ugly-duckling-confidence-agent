from datetime import UTC, datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Index, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utc_now() -> datetime:
    return datetime.now(UTC)


class Base(DeclarativeBase):
    pass


class Conversation(Base):
    __tablename__ = "conversations"

    id: Mapped[int] = mapped_column(primary_key=True)
    device_id: Mapped[str] = mapped_column(String(128), unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    messages: Mapped[list["Message"]] = relationship(back_populates="conversation")


class Message(Base):
    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    role: Mapped[str] = mapped_column(String(16))
    modality: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(Text)
    media_ref: Mapped[str | None] = mapped_column(String(160), nullable=True)
    is_mock: Mapped[bool] = mapped_column(default=False)
    used_memory_ids: Mapped[list[int] | None] = mapped_column(JSON, default=list, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    conversation: Mapped[Conversation] = relationship(back_populates="messages")


class GrowthEvent(Base):
    __tablename__ = "growth_events"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    fact: Mapped[str] = mapped_column(Text)
    source_message_ids: Mapped[list[int]] = mapped_column(JSON, default=list)
    source_user_message_id: Mapped[int | None] = mapped_column(ForeignKey("messages.id"), unique=True, nullable=True)
    source_type: Mapped[str | None] = mapped_column(String(16), nullable=True)
    feeling: Mapped[str | None] = mapped_column(Text, nullable=True)
    attempt: Mapped[str | None] = mapped_column(Text, nullable=True)
    own_effort: Mapped[str | None] = mapped_column(Text, nullable=True)
    support_received: Mapped[str | None] = mapped_column(Text, nullable=True)
    source_feedback_id: Mapped[int | None] = mapped_column(ForeignKey("support_feedback.id"), unique=True, nullable=True)
    source_record_id: Mapped[int | None] = mapped_column(ForeignKey("records.id"), unique=True, nullable=True)
    people: Mapped[list[str] | None] = mapped_column(JSON, nullable=True)
    confidence: Mapped[float | None] = mapped_column(Float, nullable=True)
    value_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    score_components: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    sensitivity: Mapped[str | None] = mapped_column(String(16), nullable=True)
    memory_decision: Mapped[str | None] = mapped_column(String(16), nullable=True)
    model: Mapped[str | None] = mapped_column(String(100), nullable=True)
    prompt_version: Mapped[str | None] = mapped_column(String(32), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class Memory(Base):
    __tablename__ = "memories"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    content: Mapped[str] = mapped_column(Text)
    source_message_ids: Mapped[list[int]] = mapped_column(JSON, default=list)
    event_id: Mapped[int | None] = mapped_column(ForeignKey("growth_events.id"), unique=True, nullable=True)
    status: Mapped[str | None] = mapped_column(String(16), nullable=True)
    value_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    sensitivity: Mapped[str | None] = mapped_column(String(16), nullable=True)
    is_user_edited: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    model: Mapped[str | None] = mapped_column(String(100), nullable=True)
    prompt_version: Mapped[str | None] = mapped_column(String(32), nullable=True)
    embedding: Mapped[list[float] | None] = mapped_column(JSON, nullable=True)
    embedding_model: Mapped[str | None] = mapped_column(String(100), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class MemoryDeletion(Base):
    __tablename__ = "memory_deletions"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    deleted_memory_id: Mapped[int] = mapped_column(unique=True)
    source_message_ids: Mapped[list[int]] = mapped_column(JSON, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class DailySummary(Base):
    __tablename__ = "daily_summaries"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    day: Mapped[str] = mapped_column(String(10))
    content: Mapped[str] = mapped_column(Text)
    source_event_ids: Mapped[list[int]] = mapped_column(JSON, default=list)
    status: Mapped[str] = mapped_column(String(16), default="draft")
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class SupportPerson(Base):
    __tablename__ = "support_people"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    name: Mapped[str] = mapped_column(String(120))
    relationship: Mapped[str | None] = mapped_column(String(120), nullable=True)
    kind: Mapped[str | None] = mapped_column(String(24), nullable=True)
    scenarios: Mapped[list[str] | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class SupportSuggestion(Base):
    __tablename__ = "support_suggestions"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    support_person_id: Mapped[int | None] = mapped_column(ForeignKey("support_people.id"), nullable=True)
    source_message_id: Mapped[int | None] = mapped_column(ForeignKey("messages.id"), nullable=True)
    situation: Mapped[str] = mapped_column(Text)
    supporter_type: Mapped[str] = mapped_column(String(24))
    supporter_name: Mapped[str] = mapped_column(String(120))
    reason: Mapped[str] = mapped_column(Text)
    editable_message: Mapped[str] = mapped_column(Text)
    small_step: Mapped[str] = mapped_column(Text)
    lighter_option: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class SupportFeedback(Base):
    __tablename__ = "support_feedback"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    suggestion_id: Mapped[int] = mapped_column(ForeignKey("support_suggestions.id"), unique=True)
    outcome: Mapped[str] = mapped_column(String(24))
    own_effort: Mapped[str | None] = mapped_column(Text, nullable=True)
    support_received: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class UserRecord(Base):
    __tablename__ = "records"
    __table_args__ = (UniqueConstraint("conversation_id", "client_record_id", name="uq_records_device_client_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    client_record_id: Mapped[str] = mapped_column(String(80))
    mode: Mapped[str] = mapped_column(String(16))
    text: Mapped[str] = mapped_column(Text)
    photo_comment: Mapped[str] = mapped_column(Text)
    ai_description: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(16))
    embedding: Mapped[list[float] | None] = mapped_column(JSON, nullable=True)
    embedding_model: Mapped[str | None] = mapped_column(String(100), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class Review(Base):
    __tablename__ = "reviews"
    __table_args__ = (Index(
        "ix_reviews_period_range",
        "conversation_id",
        "period",
        "range_start",
        "range_end",
        unique=True,
    ),)

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    period: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(Text)
    source_event_ids: Mapped[list[int]] = mapped_column(JSON, default=list)
    range_start: Mapped[str | None] = mapped_column(String(10), nullable=True)
    range_end: Mapped[str | None] = mapped_column(String(10), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class VideoScript(Base):
    __tablename__ = "video_scripts"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    scenes: Mapped[list[dict]] = mapped_column(JSON)
    source_event_ids: Mapped[list[int]] = mapped_column(JSON)
    model: Mapped[str | None] = mapped_column(String(100), nullable=True)
    prompt_version: Mapped[str] = mapped_column(String(32))
    is_user_edited: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class ProactiveCheckIn(Base):
    __tablename__ = "proactive_check_ins"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    reason: Mapped[str] = mapped_column(String(24))
    prompt: Mapped[str] = mapped_column(Text)
    source_event_ids: Mapped[list[int]] = mapped_column(JSON, default=list)
    status: Mapped[str] = mapped_column(String(24), default="pending")
    notified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    shown_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    responded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    cooldown_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
