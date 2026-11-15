import hashlib
import json

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.db.models import ExperienceFeedback
from app.db.session import get_db
from app.schemas.experience_feedback import FeedbackSubmission

router = APIRouter(prefix='/experience-feedback', tags=['experience-feedback'])


@router.post('')
def submit_feedback(request: FeedbackSubmission, db: Session = Depends(get_db)) -> dict:
    values = request.model_dump(mode='json')
    fingerprint = hashlib.sha256(json.dumps(values, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    def existing():
        return db.scalar(select(ExperienceFeedback).where(
            ExperienceFeedback.device_id == request.device_id,
            ExperienceFeedback.client_submission_id == str(request.client_submission_id)))
    row = existing()
    if row is None:
        row = ExperienceFeedback(device_id=request.device_id,
            client_submission_id=str(request.client_submission_id), survey_version=request.survey_version,
            app_version=request.app_version, identity_snapshot=request.identity.model_dump(),
            answers=request.answers, consent=True, payload_hash=fingerprint)
        db.add(row)
        try:
            db.commit()
            db.refresh(row)
        except IntegrityError:
            db.rollback()
            row = existing()
            if row is None:
                raise
    if row.payload_hash != fingerprint:
        raise HTTPException(409, '这次提交已收到，请重新开始一份评价')
    from datetime import UTC
    stamp = row.submitted_at.replace(tzinfo=UTC) if row.submitted_at.tzinfo is None else row.submitted_at
    return {'id': row.id, 'submitted_at': stamp.isoformat(), 'survey_version': row.survey_version}
