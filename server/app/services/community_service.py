from datetime import UTC, datetime, timedelta, timezone
from hashlib import sha256
from pathlib import Path
import json
import threading
from functools import wraps
from fastapi import HTTPException
from sqlalchemy import delete, func, select, update, or_
from sqlalchemy.dialects.sqlite import insert
from app.core.config import get_settings
from app.db.community import (
    CommunityProfile,
    CommunityPost,
    CommunityMedia,
    CommunityComment,
    CommunityLike,
    CommunityBlock,
    CommunityReport,
    CommunityQuota,
)

media_lock = threading.Lock()  # Deployment uses one worker; storage cap is serialized.
write_lock = threading.RLock()


def serialized(fn):
    @wraps(fn)
    def call(*args, **kwargs):
        with write_lock:
            return fn(*args, **kwargs)

    return call


def media_root():
    root = Path(get_settings().community_media_dir).resolve()
    root.mkdir(parents=True, exist_ok=True)
    return root


def media_path(id):
    from uuid import UUID

    try:
        name = str(UUID(str(id)))
    except ValueError:
        raise HTTPException(404, "图片不存在")
    return media_root() / (name + ".jpg")


def profile(db, device):
    row = db.scalar(
        select(CommunityProfile).where(CommunityProfile.device_id == device)
    )
    if row is None:
        row = CommunityProfile(device_id=device)
        db.add(row)
        db.flush()
        row.nickname = "新朋友" + row.id[:4]
    return row


def public_profile(p):
    return {"id": p.id, "nickname": p.nickname, "avatar": p.avatar}


def publishing(p):
    if p.restricted:
        raise HTTPException(403, "社区发布功能已受限，私人陪伴功能仍可使用")
    if not p.configured:
        raise HTTPException(409, "请先设置独立的社区昵称")


def quota(db, p, kind, limit):
    day = datetime.now(UTC).astimezone(timezone(timedelta(hours=8))).date().isoformat()
    key = f"{day}:{sha256(p.device_id.encode()).hexdigest()}:{kind}"
    db.execute(insert(CommunityQuota).values(key=key, used=0).on_conflict_do_nothing())
    result = db.execute(
        update(CommunityQuota)
        .where(CommunityQuota.key == key, CommunityQuota.used < limit)
        .values(used=CommunityQuota.used + 1)
    )
    if result.rowcount != 1:
        raise HTTPException(
            429,
            "今日社区"
            + {"post": "发帖", "comment": "评论", "upload": "图片上传"}[kind]
            + "额度已用完",
        )


def blocked(db, p):
    return set(
        db.scalars(
            select(CommunityBlock.target_id).where(CommunityBlock.author_id == p.id)
        )
    )


def post_owned_or_visible(db, id, p, require_public=False):
    row = db.get(CommunityPost, id)
    if not row or row.status == "deleted" or row.author_id in blocked(db, p):
        raise HTTPException(404, "帖子不存在或已下架")
    if row.status != "published" and (require_public or row.author_id != p.id):
        raise HTTPException(404, "帖子不存在或已下架")
    return row


def post_dict(db, row, p):
    excludes = blocked(db, p)
    count = db.scalar(
        select(func.count())
        .select_from(CommunityComment)
        .where(
            CommunityComment.post_id == row.id,
            CommunityComment.status == "published",
            CommunityComment.author_id.not_in(excludes),
        )
    )
    return {
        "id": row.id,
        "author": public_profile(db.get(CommunityProfile, row.author_id)),
        "title": row.title,
        "content": row.content,
        "topic": row.topic,
        "media_ids": row.media_ids if row.status == "published" else [],
        "created_at": row.created_at.replace(
            tzinfo=row.created_at.tzinfo or UTC
        ).isoformat(),
        "is_mine": row.author_id == p.id,
        "status": row.status,
        "reason": row.reason if row.author_id == p.id else "",
        "like_count": db.scalar(
            select(func.count())
            .select_from(CommunityLike)
            .where(CommunityLike.post_id == row.id)
        ),
        "comment_count": count,
        "liked": db.get(CommunityLike, (p.id, row.id)) is not None,
    }


def comment_dict(db, row, p):
    return {
        "id": row.id,
        "post_id": row.post_id,
        "author": public_profile(db.get(CommunityProfile, row.author_id)),
        "content": row.content,
        "is_mine": row.author_id == p.id,
        "created_at": row.created_at.replace(
            tzinfo=row.created_at.tzinfo or UTC
        ).isoformat(),
    }


def delete_post(db, row):
    row.status = "deleted"
    row.title = ""
    row.content = ""
    row.reason = ""
    row.media_ids = []
    db.execute(
        update(CommunityMedia)
        .where(CommunityMedia.post_id == row.id)
        .values(retired_at=datetime.now(UTC))
    )
    db.execute(delete(CommunityLike).where(CommunityLike.post_id == row.id))
    db.execute(
        update(CommunityComment)
        .where(CommunityComment.post_id == row.id)
        .values(status="deleted", content="", reason="")
    )
    # Reports cannot retain user content after source deletion.
    comment_ids = select(CommunityComment.id).where(CommunityComment.post_id == row.id)
    db.execute(
        delete(CommunityReport).where(
            or_(
                (CommunityReport.kind == "post")
                & (CommunityReport.target_id == row.id),
                (CommunityReport.kind == "comment")
                & CommunityReport.target_id.in_(comment_ids),
            )
        )
    )


def export_community(db, device):
    from fastapi.encoders import jsonable_encoder
    from sqlalchemy.inspection import inspect

    p = db.scalar(select(CommunityProfile).where(CommunityProfile.device_id == device))
    if not p:
        return {
            "profile": None,
            "posts": [],
            "comments": [],
            "likes": [],
            "reports": [],
            "blocks": [],
        }

    def rows(model, where):
        return [
            jsonable_encoder(
                {
                    c.key: getattr(r, c.key)
                    for c in inspect(model).columns
                    if c.key not in {"fingerprint", "device_id"}
                }
            )
            for r in db.scalars(select(model).where(where))
        ]

    return {
        "profile": {**public_profile(p), "restricted": p.restricted},
        "posts": rows(CommunityPost, CommunityPost.author_id == p.id),
        "comments": rows(CommunityComment, CommunityComment.author_id == p.id),
        "likes": rows(CommunityLike, CommunityLike.author_id == p.id),
        "reports": rows(CommunityReport, CommunityReport.reporter_id == p.id),
        "blocks": rows(CommunityBlock, CommunityBlock.author_id == p.id),
    }


def erase_community(db, device):
    p = db.scalar(select(CommunityProfile).where(CommunityProfile.device_id == device))
    if not p:
        return
    for post in db.scalars(
        select(CommunityPost).where(CommunityPost.author_id == p.id)
    ):
        delete_post(db, post)
    db.execute(delete(CommunityReport).where(CommunityReport.reporter_id == p.id))
    db.execute(delete(CommunityLike).where(CommunityLike.author_id == p.id))
    db.execute(
        delete(CommunityBlock).where(
            or_(CommunityBlock.author_id == p.id, CommunityBlock.target_id == p.id)
        )
    )
    db.execute(
        update(CommunityComment)
        .where(CommunityComment.author_id == p.id)
        .values(status="deleted", content="", reason="")
    )
    comment_ids = select(CommunityComment.id).where(CommunityComment.author_id == p.id)
    db.execute(
        delete(CommunityReport).where(
            CommunityReport.kind == "comment",
            CommunityReport.target_id.in_(comment_ids),
        )
    )
    db.execute(
        update(CommunityMedia)
        .where(CommunityMedia.owner_id == p.id)
        .values(retired_at=datetime.now(UTC))
    )
    # Keep a tombstone UUID for references; unlink its device and remove its public identity.
    p.device_id = "deleted-" + p.id
    p.nickname = "已注销"
    p.avatar = 0
    p.restricted = True


def cleanup_media(db):
    now = datetime.now(UTC)
    rows = db.scalars(
        select(CommunityMedia).where(
            or_(
                (CommunityMedia.post_id.is_(None))
                & (CommunityMedia.created_at < now - timedelta(hours=24)),
                CommunityMedia.retired_at < now - timedelta(days=7),
            )
        )
    ).all()
    removed = 0
    for row in rows:
        media_path(row.id).unlink(missing_ok=True)
        db.delete(row)
        removed += 1
    known = set(db.scalars(select(CommunityMedia.id)))
    for path in media_root().glob("*.jpg"):
        if (
            path.stem not in known
            and path.stat().st_mtime < (now - timedelta(hours=24)).timestamp()
        ):
            path.unlink(missing_ok=True)
            removed += 1
    # Retain only operational daily counters, with no raw device identifier.
    oldest = (now - timedelta(days=35)).date().isoformat()
    db.execute(delete(CommunityQuota).where(CommunityQuota.key < oldest))
    db.commit()
    return removed
