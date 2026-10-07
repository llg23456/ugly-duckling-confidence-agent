from datetime import UTC, datetime

from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, VideoScript
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas.video import (
    VideoKeywordSuggestionRequest, VideoKeywordSuggestionResponse,
    VideoScriptRequest, VideoScriptResponse, VideoScriptUpdate,
)
from app.services.video_service import (
    PROMPT_VERSION, generate_captions, group_events_by_day, scenes_for, suggest_keywords,
)
from app.services.video_render_service import render_uploaded_video, scene_music_moods

router = APIRouter(prefix="/videos", tags=["videos"])


def _response(script: VideoScript) -> VideoScriptResponse:
    return VideoScriptResponse(
        id=script.id, scenes=script.scenes, source_event_ids=script.source_event_ids,
        model=script.model, prompt_version=script.prompt_version,
        is_user_edited=script.is_user_edited, mock=script.model is None,
        created_at=script.created_at, updated_at=script.updated_at,
    )


def _owned(db: Session, script_id: int, device_id: str) -> VideoScript:
    conversation = get_conversation(db, device_id)
    script = db.get(VideoScript, script_id)
    if conversation is None or script is None or script.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="脚本不存在")
    return script


@router.post("/keywords", response_model=VideoKeywordSuggestionResponse)
def keyword_suggestions(
    request: VideoKeywordSuggestionRequest,
    db: Session = Depends(get_db),
) -> VideoKeywordSuggestionResponse:
    conversation = get_conversation(db, request.device_id)
    if conversation is None or len(request.event_ids) != len(set(request.event_ids)):
        raise HTTPException(status_code=422, detail="请选择本设备的真实事件")
    events = list(db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation.id,
        GrowthEvent.id.in_(request.event_ids),
    ).order_by(GrowthEvent.created_at, GrowthEvent.id)))
    if len(events) != len(request.event_ids):
        raise HTTPException(status_code=422, detail="部分事件已不存在，请返回周报告后重新进入")
    if any(item.sensitivity == "high" for item in events):
        raise HTTPException(status_code=422, detail="高敏感内容不能加入分享视频")
    suggestions, model = suggest_keywords(events)
    return VideoKeywordSuggestionResponse(suggestions=suggestions, model=model)


@router.post("/scripts", response_model=VideoScriptResponse, status_code=201)
def create_script(request: VideoScriptRequest, db: Session = Depends(get_db)) -> VideoScriptResponse:
    conversation = get_conversation(db, request.device_id)
    if conversation is None or len(request.event_ids) != len(set(request.event_ids)):
        raise HTTPException(status_code=422, detail="请选择本设备的真实事件")
    events = list(db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation.id,
        GrowthEvent.id.in_(request.event_ids),
    ).order_by(GrowthEvent.created_at, GrowthEvent.id)))
    if len(events) != len(request.event_ids):
        raise HTTPException(status_code=422, detail="部分事件已不存在，请返回周报告后重新进入")
    if any(item.sensitivity == "high" for item in events):
        raise HTTPException(status_code=422, detail="高敏感内容不能加入分享视频")
    day_count = len(group_events_by_day(events))
    if day_count not in range(3, 8):
        raise HTTPException(status_code=422, detail="成长小片需要选择三到七天的素材")
    try:
        captions, model = generate_captions(events)
        scenes = scenes_for(events, captions)
    except Exception as exc:
        raise HTTPException(status_code=502, detail="脚本暂时无法生成，请重试") from exc
    script = VideoScript(
        conversation_id=conversation.id,
        scenes=[scene.model_dump() for scene in scenes],
        source_event_ids=[item.id for item in events],
        model=model, prompt_version=PROMPT_VERSION,
        is_user_edited=False,
    )
    db.add(script)
    db.commit()
    return _response(script)


@router.get("/scripts/{script_id}", response_model=VideoScriptResponse)
def get_script(script_id: int, device_id: str, db: Session = Depends(get_db)) -> VideoScriptResponse:
    return _response(_owned(db, script_id, device_id))


@router.patch("/scripts/{script_id}", response_model=VideoScriptResponse)
def update_script(script_id: int, request: VideoScriptUpdate, db: Session = Depends(get_db)) -> VideoScriptResponse:
    script = _owned(db, script_id, request.device_id)
    original_groups = [tuple(scene.get("source_event_ids", [])) for scene in script.scenes]
    group_positions = {group: index for index, group in enumerate(original_groups)}
    requested_groups = [tuple(scene.source_event_ids) for scene in request.scenes]
    if (
        len(requested_groups) != len(set(requested_groups))
        or any(group not in group_positions for group in requested_groups)
        or requested_groups != sorted(requested_groups, key=group_positions.get)
        or any(not scene.text.strip() for scene in request.scenes)
    ):
        raise HTTPException(status_code=422, detail="片段来源或内容无效")
    script.scenes = [scene.model_dump() for scene in request.scenes]
    script.is_user_edited = True
    script.updated_at = datetime.now(UTC)
    db.commit()
    return _response(script)


@router.post("/render/{script_id}", response_class=Response)
def render_video(
    script_id: int,
    device_id: str = Form(min_length=1, max_length=128),
    manifest: str = Form(min_length=2),
    files: list[UploadFile] = File(min_length=3, max_length=120),
    db: Session = Depends(get_db),
) -> Response:
    script = _owned(db, script_id, device_id)
    events = list(db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == script.conversation_id,
        GrowthEvent.id.in_(script.source_event_ids),
    )))
    music_moods = scene_music_moods(script.scenes, events)
    video = render_uploaded_video(manifest, files, scene_music_moods=music_moods)
    return Response(
        content=video,
        media_type="video/mp4",
        headers={"Cache-Control": "no-store", "Content-Disposition": "inline; filename=growth-video.mp4"},
    )
