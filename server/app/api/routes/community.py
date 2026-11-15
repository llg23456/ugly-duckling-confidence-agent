from datetime import UTC, datetime
from hashlib import sha256
from io import BytesIO
import json
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator
from sqlalchemy import delete, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from app.core.config import get_settings
from app.db.session import get_db
from app.db.community import (
    CommunityProfile,
    CommunityPost,
    CommunityMedia,
    CommunityComment,
    CommunityLike,
    CommunityBlock,
    CommunityReport,
)
from app.services.community_service import (
    profile,
    public_profile,
    publishing,
    quota,
    blocked,
    post_owned_or_visible,
    post_dict,
    comment_dict,
    delete_post,
    media_root,
    media_path,
    media_lock,
    serialized,
)

router = APIRouter(prefix="/community", tags=["community"])


class Device(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    device_id: str = Field(min_length=1, max_length=128)


class ProfileEdit(Device):
    nickname: str = Field(min_length=2, max_length=20)
    avatar: int = Field(default=0, ge=0, le=5)


class PostCreate(Device):
    client_id: UUID
    title: str = Field(min_length=1, max_length=50)
    content: str = Field(default="", max_length=2000)
    topic: str = Field(default="成长分享", min_length=1, max_length=24)
    media_ids: list[UUID] = Field(default_factory=list, max_length=3)

    @model_validator(mode="after")
    def nonempty(self):
        if not self.content and not self.media_ids:
            raise ValueError("请填写正文或添加图片")
        if len(set(self.media_ids)) != len(self.media_ids):
            raise ValueError("图片不能重复")
        return self


class CommentCreate(Device):
    client_id: UUID
    content: str = Field(min_length=1, max_length=500)


class ReportCreate(Device):
    kind: Literal["post", "comment"]
    target_id: UUID
    reason: Literal["harassment", "privacy", "spam", "dangerous", "other"]
    detail: str = Field(default="", max_length=300)


def resolve_unique(db, model, author, client):
    return db.scalar(
        select(model).where(model.author_id == author, model.client_id == str(client))
    )


@router.get("/profile")
@serialized
def me(
    device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)
):
    p = profile(db, device_id)
    db.commit()
    return {**public_profile(p), "configured": p.configured, "restricted": p.restricted}


@router.patch("/profile")
@serialized
def edit_profile(body: ProfileEdit, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    p.nickname = body.nickname
    p.avatar = body.avatar
    p.configured = True
    db.commit()
    return {**public_profile(p), "configured": True, "restricted": p.restricted}


@router.get("/posts")
@serialized
def feed(
    device_id: str = Query(min_length=1, max_length=128),
    q: str = Query(default="", max_length=100),
    topic: str = Query(default="", max_length=24),
    mine: bool = False,
    cursor: UUID | None = None,
    db: Session = Depends(get_db),
):
    p = profile(db, device_id)
    stmt = select(CommunityPost).where(CommunityPost.author_id.not_in(blocked(db, p)))
    stmt = (
        stmt.where(CommunityPost.author_id == p.id, CommunityPost.status != "deleted")
        if mine
        else stmt.where(CommunityPost.status == "published")
    )
    if q.strip():
        stmt = stmt.where(
            or_(
                CommunityPost.title.contains(q.strip(), autoescape=True),
                CommunityPost.content.contains(q.strip(), autoescape=True),
            )
        )
    if topic:
        stmt = stmt.where(CommunityPost.topic == topic)
    if cursor:
        anchor = db.get(CommunityPost, str(cursor))
        if not anchor:
            raise HTTPException(422, "分页位置已失效，请刷新")
        stmt = stmt.where(
            or_(
                CommunityPost.created_at < anchor.created_at,
                (CommunityPost.created_at == anchor.created_at)
                & (CommunityPost.id < anchor.id),
            )
        )
    rows = list(
        db.scalars(
            stmt.order_by(
                CommunityPost.created_at.desc(), CommunityPost.id.desc()
            ).limit(21)
        )
    )
    result = {
        "posts": [post_dict(db, r, p) for r in rows[:20]],
        "next_cursor": rows[19].id if len(rows) > 20 else None,
    }
    db.commit()
    return result


@router.post("/posts")
@serialized
def publish(body: PostCreate, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    fingerprint = sha256(
        json.dumps(
            body.model_dump(mode="json", exclude={"device_id"}),
            sort_keys=True,
            ensure_ascii=False,
        ).encode()
    ).hexdigest()
    old = resolve_unique(db, CommunityPost, p.id, body.client_id)
    if old:
        if old.fingerprint != fingerprint:
            raise HTTPException(409, "该发布编号已使用，请重新编辑后发布")
        if old.status == "deleted":
            raise HTTPException(410, "这次发布已删除")
        return post_dict(db, old, p)
    publishing(p)
    quota(db, p, "post", 10)
    media = []
    for id in body.media_ids:
        r = db.get(CommunityMedia, str(id))
        if (
            not r
            or r.owner_id != p.id
            or r.post_id is not None
            or r.retired_at is not None
        ):
            raise HTTPException(422, "图片不存在或不能用于本帖")
        if not media_path(r.id).is_file():
            raise HTTPException(422, "图片已失效，请重新上传")
        media.append(r)
    row = CommunityPost(
        author_id=p.id,
        client_id=str(body.client_id),
        fingerprint=fingerprint,
        title=body.title,
        content=body.content,
        topic=body.topic,
        media_ids=[r.id for r in media],
    )
    db.add(row)
    db.flush()
    for r in media:
        r.post_id = row.id
    db.commit()
    return post_dict(db, row, p)


@router.get("/posts/{post_id}")
@serialized
def detail(
    post_id: UUID,
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
):
    p = profile(db, device_id)
    row = post_owned_or_visible(db, str(post_id), p)
    result = post_dict(db, row, p)
    db.commit()
    return result


@router.delete("/posts/{post_id}", status_code=204)
@serialized
def erase_post(post_id: UUID, body: Device, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    row = db.get(CommunityPost, str(post_id))
    if not row or row.author_id != p.id:
        raise HTTPException(404, "只能删除自己的帖子")
    delete_post(db, row)
    db.commit()


@router.put("/posts/{post_id}/like")
@serialized
def like(post_id: UUID, body: Device, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    post = post_owned_or_visible(db, str(post_id), p, True)
    if not db.get(CommunityLike, (p.id, post.id)):
        db.add(CommunityLike(author_id=p.id, post_id=post.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return post_dict(db, post, p)


@router.delete("/posts/{post_id}/like")
@serialized
def unlike(post_id: UUID, body: Device, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    post = post_owned_or_visible(db, str(post_id), p, True)
    db.execute(
        delete(CommunityLike).where(
            CommunityLike.author_id == p.id, CommunityLike.post_id == post.id
        )
    )
    db.commit()
    return post_dict(db, post, p)


@router.get("/posts/{post_id}/comments")
@serialized
def comments(
    post_id: UUID,
    device_id: str = Query(min_length=1, max_length=128),
    cursor: UUID | None = None,
    db: Session = Depends(get_db),
):
    p = profile(db, device_id)
    post = post_owned_or_visible(db, str(post_id), p, True)
    stmt = select(CommunityComment).where(
        CommunityComment.post_id == post.id,
        CommunityComment.status == "published",
        CommunityComment.author_id.not_in(blocked(db, p)),
    )
    if cursor:
        anchor = db.get(CommunityComment, str(cursor))
        if not anchor or anchor.post_id != post.id:
            raise HTTPException(422, "分页位置已失效")
        stmt = stmt.where(
            or_(
                CommunityComment.created_at > anchor.created_at,
                (CommunityComment.created_at == anchor.created_at)
                & (CommunityComment.id > anchor.id),
            )
        )
    rows = list(
        db.scalars(
            stmt.order_by(CommunityComment.created_at, CommunityComment.id).limit(21)
        )
    )
    result = {
        "comments": [comment_dict(db, r, p) for r in rows[:20]],
        "next_cursor": rows[19].id if len(rows) > 20 else None,
    }
    db.commit()
    return result


@router.post("/posts/{post_id}/comments")
@serialized
def add_comment(post_id: UUID, body: CommentCreate, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    post = post_owned_or_visible(db, str(post_id), p, True)
    old = resolve_unique(db, CommunityComment, p.id, body.client_id)
    if old:
        if (
            old.content != body.content
            or old.post_id != post.id
            or old.status == "deleted"
        ):
            raise HTTPException(409, "该评论编号已使用")
        return comment_dict(db, old, p)
    publishing(p)
    quota(db, p, "comment", 50)
    row = CommunityComment(
        author_id=p.id,
        post_id=post.id,
        client_id=str(body.client_id),
        content=body.content,
    )
    db.add(row)
    db.commit()
    return comment_dict(db, row, p)


@router.delete("/comments/{comment_id}", status_code=204)
@serialized
def erase_comment(comment_id: UUID, body: Device, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    row = db.get(CommunityComment, str(comment_id))
    if not row or row.author_id != p.id:
        raise HTTPException(404, "只能删除自己的评论")
    row.status = "deleted"
    row.content = ""
    row.reason = ""
    db.execute(
        delete(CommunityReport).where(
            CommunityReport.kind == "comment", CommunityReport.target_id == row.id
        )
    )
    db.commit()


@router.post("/reports")
@serialized
def report(body: ReportCreate, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    if body.kind == "post":
        post_owned_or_visible(db, str(body.target_id), p, True)
    else:
        row = db.get(CommunityComment, str(body.target_id))
        if not row or row.status != "published" or row.author_id in blocked(db, p):
            raise HTTPException(404, "评论不存在")
        post_owned_or_visible(db, row.post_id, p, True)
    old = db.scalar(
        select(CommunityReport).where(
            CommunityReport.reporter_id == p.id,
            CommunityReport.kind == body.kind,
            CommunityReport.target_id == str(body.target_id),
        )
    )
    if not old:
        db.add(
            CommunityReport(
                reporter_id=p.id,
                kind=body.kind,
                target_id=str(body.target_id),
                reason=body.reason,
                detail=body.detail,
            )
        )
    db.commit()
    return {"received": True}


@router.get("/blocks")
@serialized
def blocks(
    device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)
):
    p = profile(db, device_id)
    rows = list(
        db.scalars(
            select(CommunityProfile).where(CommunityProfile.id.in_(blocked(db, p)))
        )
    )
    db.commit()
    return {"profiles": [public_profile(r) for r in rows]}


@router.put("/blocks/{author_id}")
@serialized
def block(author_id: UUID, body: Device, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    target = db.get(CommunityProfile, str(author_id))
    if not target or target.id == p.id:
        raise HTTPException(422, "无法屏蔽此作者")
    if not db.get(CommunityBlock, (p.id, target.id)):
        db.add(CommunityBlock(author_id=p.id, target_id=target.id))
    db.commit()
    return {"blocked": True}


@router.delete("/blocks/{author_id}")
@serialized
def unblock(author_id: UUID, body: Device, db: Session = Depends(get_db)):
    p = profile(db, body.device_id)
    db.execute(
        delete(CommunityBlock).where(
            CommunityBlock.author_id == p.id, CommunityBlock.target_id == str(author_id)
        )
    )
    db.commit()
    return {"blocked": False}


@router.post("/media")
@serialized
def upload(
    device_id: str = Form(min_length=1, max_length=128),
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
):
    from PIL import Image, ImageOps, UnidentifiedImageError

    p = profile(db, device_id)
    publishing(p)
    content = file.file.read(5 * 1024 * 1024 + 1)
    file.file.close()
    if len(content) > 5 * 1024 * 1024:
        raise HTTPException(413, "图片不能超过5MB")
    try:
        with Image.open(BytesIO(content)) as source:
            if (
                source.format not in {"JPEG", "PNG", "WEBP"}
                or source.width * source.height > 20_000_000
            ):
                raise ValueError()
            image = ImageOps.exif_transpose(source)
            image.thumbnail((1600, 1600))
            if image.mode in ("RGBA", "LA"):
                rgba = image.convert("RGBA")
                canvas = Image.new("RGB", rgba.size, "white")
                canvas.paste(rgba, mask=rgba.getchannel("A"))
                image = canvas
            else:
                image = image.convert("RGB")
            buffer = BytesIO()
            image.save(buffer, format="JPEG", quality=85)
            normalized = buffer.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        raise HTTPException(415, "仅支持有效的JPEG、PNG、WebP图片（不超过2000万像素）")
    with media_lock:
        # Include retired/unbound pictures and files left by a process interruption.
        used = sum(path.stat().st_size for path in media_root().glob("*.jpg"))
        if used + len(normalized) > get_settings().community_media_limit_bytes:
            raise HTTPException(507, "社区图片容量已满，请稍后再试或发布文字")
        quota(db, p, "upload", 30)
        row = CommunityMedia(id=str(uuid4()), owner_id=p.id, size=len(normalized))
        db.add(row)
        path = media_path(row.id)
        try:
            path.write_bytes(normalized)
            db.commit()
        except Exception:
            path.unlink(missing_ok=True)
            raise
    return {"id": row.id, "size": row.size}


@router.get("/media/{media_id}")
@serialized
def image(
    media_id: UUID,
    device_id: str = Query(min_length=1, max_length=128),
    db: Session = Depends(get_db),
):
    p = profile(db, device_id)
    row = db.get(CommunityMedia, str(media_id))
    if not row or row.retired_at is not None:
        raise HTTPException(404, "图片不存在")
    if row.post_id:
        post_owned_or_visible(db, row.post_id, p, True)
    elif row.owner_id != p.id:
        raise HTTPException(404, "图片不存在")
    path = media_path(row.id)
    if not path.is_file():
        raise HTTPException(404, "图片不存在")
    db.commit()
    return FileResponse(
        path,
        media_type="image/jpeg",
        headers={
            "Cache-Control": "private, no-store",
            "X-Content-Type-Options": "nosniff",
        },
    )
