"""Community ownership uses public profile UUIDs; device IDs never leave the API."""

from datetime import datetime
from uuid import uuid4
from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    JSON,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column
from app.db.models import Base, utc_now


def uid():
    return str(uuid4())


class CommunityProfile(Base):
    __tablename__ = "community_profiles"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    device_id: Mapped[str] = mapped_column(String(128), unique=True)
    nickname: Mapped[str] = mapped_column(String(20), default="新朋友")
    avatar: Mapped[int] = mapped_column(default=0)
    restricted: Mapped[bool] = mapped_column(Boolean, default=False)
    configured: Mapped[bool] = mapped_column(Boolean, default=False)


class CommunityPost(Base):
    __tablename__ = "community_posts"
    __table_args__ = (UniqueConstraint("author_id", "client_id"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    author_id: Mapped[str] = mapped_column(
        ForeignKey("community_profiles.id"), index=True
    )
    client_id: Mapped[str] = mapped_column(String(36))
    fingerprint: Mapped[str] = mapped_column(String(64))
    title: Mapped[str] = mapped_column(String(50))
    content: Mapped[str] = mapped_column(Text)
    topic: Mapped[str] = mapped_column(String(24), default="成长分享")
    media_ids: Mapped[list] = mapped_column(JSON, default=list)
    status: Mapped[str] = mapped_column(String(16), default="published")
    reason: Mapped[str] = mapped_column(String(300), default="")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, index=True
    )


class CommunityMedia(Base):
    __tablename__ = "community_media"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    owner_id: Mapped[str] = mapped_column(
        ForeignKey("community_profiles.id"), index=True
    )
    post_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    size: Mapped[int] = mapped_column()
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )
    retired_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )


class CommunityComment(Base):
    __tablename__ = "community_comments"
    __table_args__ = (UniqueConstraint("author_id", "client_id"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    author_id: Mapped[str] = mapped_column(
        ForeignKey("community_profiles.id"), index=True
    )
    post_id: Mapped[str] = mapped_column(ForeignKey("community_posts.id"), index=True)
    client_id: Mapped[str] = mapped_column(String(36))
    content: Mapped[str] = mapped_column(String(500))
    status: Mapped[str] = mapped_column(String(16), default="published")
    reason: Mapped[str] = mapped_column(String(300), default="")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )


class CommunityLike(Base):
    __tablename__ = "community_likes"
    author_id: Mapped[str] = mapped_column(
        ForeignKey("community_profiles.id"), primary_key=True
    )
    post_id: Mapped[str] = mapped_column(
        ForeignKey("community_posts.id"), primary_key=True
    )


class CommunityBlock(Base):
    __tablename__ = "community_blocks"
    author_id: Mapped[str] = mapped_column(
        ForeignKey("community_profiles.id"), primary_key=True
    )
    target_id: Mapped[str] = mapped_column(
        ForeignKey("community_profiles.id"), primary_key=True
    )


class CommunityReport(Base):
    __tablename__ = "community_reports"
    __table_args__ = (UniqueConstraint("reporter_id", "kind", "target_id"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    reporter_id: Mapped[str] = mapped_column(ForeignKey("community_profiles.id"))
    kind: Mapped[str] = mapped_column(String(12))
    target_id: Mapped[str] = mapped_column(String(36))
    reason: Mapped[str] = mapped_column(String(32))
    detail: Mapped[str] = mapped_column(String(300), default="")
    status: Mapped[str] = mapped_column(String(16), default="open")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )


class CommunityQuota(Base):
    __tablename__ = "community_quotas"
    key: Mapped[str] = mapped_column(String(200), primary_key=True)
    used: Mapped[int] = mapped_column(default=0)


class CommunityAdmin(Base):
    __tablename__ = "community_admins"
    username: Mapped[str] = mapped_column(String(40), primary_key=True)
    password_hash: Mapped[str] = mapped_column(String(256))


class CommunityAdminSession(Base):
    __tablename__ = "community_admin_sessions"
    digest: Mapped[str] = mapped_column(String(64), primary_key=True)
    username: Mapped[str] = mapped_column(ForeignKey("community_admins.username"))
    csrf: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[int] = mapped_column()


class CommunityAudit(Base):
    __tablename__ = "community_audit"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(40))
    kind: Mapped[str] = mapped_column(String(16))
    target_id: Mapped[str] = mapped_column(String(36))
    action: Mapped[str] = mapped_column(String(20))
    reason: Mapped[str] = mapped_column(String(300))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )
