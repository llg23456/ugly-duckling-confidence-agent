from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import GrowthEvent, VideoScript
from app.db.repository import get_conversation
from app.db.session import get_db
from app.schemas.video import VideoScriptRequest, VideoScriptResponse, VideoScriptUpdate
from app.services.video_service import PROMPT_VERSION, STAGES, generate_captions, scenes_for

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


@router.post("/scripts", response_model=VideoScriptResponse, status_code=201)
def create_script(request: VideoScriptRequest, db: Session = Depends(get_db)) -> VideoScriptResponse:
    conversation = get_conversation(db, request.device_id)
    if conversation is None or len(request.event_ids) != len(set(request.event_ids)):
        raise HTTPException(status_code=422, detail="请选择本设备的真实事件")
    events = list(db.scalars(select(GrowthEvent).where(
        GrowthEvent.conversation_id == conversation.id,
        GrowthEvent.id.in_(request.event_ids),
    ).order_by(GrowthEvent.created_at, GrowthEvent.id)))
    if len(events) != len(request.event_ids) or any(item.sensitivity not in (None, "low") for item in events):
        raise HTTPException(status_code=422, detail="事件不存在或含敏感内容")
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
    stages = [scene.stage for scene in request.scenes]
    if stages != [stage for stage in STAGES if stage in stages]:
        raise HTTPException(status_code=422, detail="片段顺序或内容无效")
    allowed = set(script.source_event_ids)
    if any(not set(scene.source_event_ids).issubset(allowed) or not scene.text.strip() for scene in request.scenes):
        raise HTTPException(status_code=422, detail="片段来源或内容无效")
    script.scenes = [scene.model_dump() for scene in request.scenes]
    script.is_user_edited = True
    script.updated_at = datetime.now(UTC)
    db.commit()
    return _response(script)
