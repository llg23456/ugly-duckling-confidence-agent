from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import pytest
from openpyxl import load_workbook
from sqlalchemy import func, select

from app.core import cloud_access
from app.core.config import Settings
from app.db.models import ExperienceFeedback
from app.db.session import get_db
from app.feedback_export import export_excel
from app.main import app


def payload(**changes):
    body = dict(device_id='survey-user',client_submission_id=str(uuid4()),survey_version='experience-v1',
                app_version='1.0-cloudtest (1)',identity={'life_stage':'大学生','label':'体验A'},
                answers={'duration':'first','features':['chat','record'],'satisfaction':'4'},consent=True)
    body.update(changes)
    return body


def rows():
    with next(app.dependency_overrides[get_db]()) as db:
        return list(db.scalars(select(ExperienceFeedback).order_by(ExperienceFeedback.id)))


def test_snapshot_server_time_and_idempotency(client):
    body=payload()
    first=client.post('/api/v1/experience-feedback',json=body)
    assert first.status_code==200,first.text
    assert datetime.fromisoformat(first.json()['submitted_at']).tzinfo is not None
    again=client.post('/api/v1/experience-feedback',json=body)
    assert again.json()==first.json()
    changed={**body,'identity':{'life_stage':'研究生'}}
    assert client.post('/api/v1/experience-feedback',json=changed).status_code==409
    assert len(rows())==1 and rows()[0].identity_snapshot['life_stage']=='大学生'
    # Another deliberate submission is retained as a separate snapshot.
    assert client.post('/api/v1/experience-feedback',json=payload(identity={'life_stage':'研究生'})).status_code==200
    assert [r.identity_snapshot['life_stage'] for r in rows()]==['大学生','研究生']


@pytest.mark.parametrize('changes',[
    {'consent':False}, {'survey_version':'unknown'},
    {'answers':{'duration':'first','features':['none','chat'],'satisfaction':'4'}},
    {'answers':{'duration':'first','features':['chat'],'satisfaction':'bad'}},
    {'answers':{'duration':'first','features':['chat']}},
    {'answers':{'duration':'first','features':['chat'],'satisfaction':'4','helpful_features':['video']}},
    {'answers':{'duration':'first','features':['none'],'satisfaction':'4','change_growth':'better'}},
    {'answers':{'duration':'first','features':['chat'],'satisfaction':'4','difficult_help':'none','difficult_types':['listen']}},
    {'answers':{'duration':'first','features':['chat'],'satisfaction':'4','issues':['none'],'issue_detail':'不应提交'}},
    {'answers':{'duration':'first','features':['chat'],'satisfaction':'4','comment':'x'*501}},
    {'answers':{'duration':'first','features':['chat'],'satisfaction':'4','goals':['listen','feelings','growth','support']}},
    {'identity':{'label':'x'*41}},
])
def test_invalid_answers_rejected(client,changes):
    response=client.post('/api/v1/experience-feedback',json=payload(**changes))
    assert response.status_code==422,response.text
    assert rows()==[]


def test_negative_difficult_experience_and_export_delete_without_conversation(client):
    body=payload()
    body['answers'].update(difficult_help='worse',issues=['advice'],issue_detail='建议不适合我')
    assert client.post('/api/v1/experience-feedback',json=body).status_code==200
    mine=client.get('/api/v1/data/export?device_id=survey-user').json()['data']['experience_feedback']
    assert len(mine)==1 and mine[0]['answers']['difficult_help']=='worse'
    assert 'payload_hash' not in mine[0]
    assert client.get('/api/v1/data/export?device_id=other').json()['data']['experience_feedback']==[]
    assert client.delete('/api/v1/data?device_id=other').status_code==204
    assert len(rows())==1
    assert client.delete('/api/v1/data?device_id=survey-user').status_code==204
    assert rows()==[]


def test_cloud_identity_required_and_bound(client,monkeypatch):
    settings=Settings(_env_file=None,cloud_auth_required=True)
    monkeypatch.setattr(cloud_access,'get_settings',lambda: settings)
    cloud_access._windows.clear()
    assert client.post('/api/v1/experience-feedback',json=payload()).status_code==401
    with next(app.dependency_overrides[get_db]()) as db:
        code=cloud_access.issue_invite(db,'survey-test')
    auth=client.post('/api/v1/cloud-auth/activate',json={'device_id':'owner','invite_code':code}).json()
    headers={'Authorization':'Bearer '+auth['access_token']}
    assert client.post('/api/v1/experience-feedback',headers=headers,json=payload()).status_code==403
    assert client.post('/api/v1/experience-feedback',headers=headers,json=payload(device_id='owner')).status_code==200
    assert client.get('/api/v1/data/export?device_id=survey-user',headers=headers).status_code==403
    cloud_access._windows.clear()


def test_excel_latest_identity_times_and_literal_text(client,tmp_path):
    first=payload(identity={'life_stage':'大学生','label':'=1+1'})
    first['answers'].update(satisfaction='1',comment='=HYPERLINK("bad")')
    client.post('/api/v1/experience-feedback',json=first)
    second=payload(identity={'life_stage':'研究生','label':'体验A'})
    second['answers'].update(satisfaction='5',usability_appearance='unknown',difficult_help='not_used')
    client.post('/api/v1/experience-feedback',json=second)
    result=export_excel(rows(),tmp_path/'survey.xlsx')
    assert result['submissions']==2 and result['latest']==1
    book=load_workbook(tmp_path/'survey.xlsx')
    assert book.sheetnames==['填写明细','每人最新一份','选项统计','文字建议','题目与口径']
    detail=book['填写明细']
    assert detail.max_row==3 and '云测设备编号' not in [c.value for c in detail[1]]
    assert detail.cell(2,3).value=='=1+1' and detail.cell(2,3).data_type=='s'
    assert detail.cell(2,4).value=='大学生' and detail.cell(3,4).value=='研究生'
    assert (detail.cell(2,7).value-detail.cell(2,8).value).total_seconds()==28800
    assert book['每人最新一份'].max_row==2
    assert book['文字建议'].cell(2,7).value=='=HYPERLINK("bad")'
    assert book['文字建议'].cell(2,7).data_type=='s'
    stats=list(book['选项统计'].values)
    satisfied=next(r for r in stats[1:] if r[1].startswith('4.') and r[2]=='很满意')
    assert satisfied[3:]==(1,1,1,1,1)
    uncertain=next(r for r in stats[1:] if r[1]=='5. 页面是否清楚好看' and r[2]=='没体验或不好判断')
    assert uncertain[4:]==(1,0,1,None)
    export_excel(rows(),tmp_path/'private.xlsx',True)
    assert load_workbook(tmp_path/'private.xlsx')['填写明细'].cell(2,3).value=='survey-user'


def test_catalog_matches_android():
    from app.schemas.experience_feedback import CATALOG
    import json
    path=Path(__file__).parents[2]/'app/src/main/assets/experience_survey.json'
    if not path.exists(): pytest.skip('Android sources not included in deployment image')
    assert json.loads(path.read_text(encoding='utf-8'))==CATALOG
