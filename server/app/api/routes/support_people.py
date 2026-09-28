from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import SupportPerson, SupportSuggestion
from app.db.repository import get_conversation, get_or_create_conversation
from app.db.session import get_db
from app.schemas.support import SupportPeopleResponse, SupportPersonCreate, SupportPersonItem, SupportPersonUpdate

router = APIRouter(prefix="/support-people", tags=["support"])


def _item(person: SupportPerson) -> SupportPersonItem:
    return SupportPersonItem(
        id=person.id, name=person.name, relationship=person.relationship or "",
        kind=person.kind or "classmate", scenarios=person.scenarios or [], created_at=person.created_at,
    )


def _owned(db: Session, person_id: int, device_id: str) -> SupportPerson:
    conversation = get_conversation(db, device_id)
    person = db.get(SupportPerson, person_id)
    if not conversation or not person or person.conversation_id != conversation.id:
        raise HTTPException(status_code=404, detail="支持对象不存在")
    return person


def _apply(person: SupportPerson, request: SupportPersonCreate | SupportPersonUpdate) -> None:
    person.name = request.name.strip()
    person.relationship = request.relationship.strip()
    person.kind = request.kind
    person.scenarios = [item.strip() for item in request.scenarios if item.strip()]
    if not person.name or not person.relationship or any(len(item) > 120 for item in person.scenarios):
        raise HTTPException(status_code=422, detail="称呼、关系或场景格式不正确")


@router.get("", response_model=SupportPeopleResponse)
def list_people(device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> SupportPeopleResponse:
    conversation = get_conversation(db, device_id)
    if not conversation:
        return SupportPeopleResponse(people=[])
    people = db.scalars(select(SupportPerson).where(SupportPerson.conversation_id == conversation.id).order_by(SupportPerson.id)).all()
    return SupportPeopleResponse(people=[_item(person) for person in people])


@router.post("", response_model=SupportPersonItem, status_code=201)
def create_person(request: SupportPersonCreate, db: Session = Depends(get_db)) -> SupportPersonItem:
    conversation = get_or_create_conversation(db, request.device_id)
    person = SupportPerson(conversation_id=conversation.id, name="")
    _apply(person, request)
    db.add(person)
    db.commit()
    return _item(person)


@router.patch("/{person_id}", response_model=SupportPersonItem)
def update_person(person_id: int, request: SupportPersonUpdate, db: Session = Depends(get_db)) -> SupportPersonItem:
    person = _owned(db, person_id, request.device_id)
    _apply(person, request)
    db.commit()
    return _item(person)


@router.delete("/{person_id}", status_code=204)
def delete_person(person_id: int, device_id: str = Query(min_length=1, max_length=128), db: Session = Depends(get_db)) -> None:
    person = _owned(db, person_id, device_id)
    for suggestion in db.scalars(select(SupportSuggestion).where(SupportSuggestion.support_person_id == person_id)):
        suggestion.support_person_id = None
    db.delete(person)
    db.commit()
