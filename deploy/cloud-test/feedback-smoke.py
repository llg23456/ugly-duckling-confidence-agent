"""Public HTTPS feedback acceptance, using a disposable identity and sample-only XLSX.

Run via docker compose exec -T api python - < deploy/cloud-test/feedback-smoke.py.
No model calls. The workbook contains only this script's synthetic submissions.
"""
from datetime import UTC, datetime
import json
from pathlib import Path
import time
from uuid import uuid4

import httpx
from openpyxl import load_workbook
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.cloud_access import CloudCredential, CloudInvite, digest, issue_invite
from app.core.config import get_settings
from app.db.models import ExperienceFeedback
from app.db.session import engine_for_url
from app.feedback_export import export_excel

DEVICE='feedback-smoke-'+uuid4().hex
REPORT=Path('/backups/feedback-verification.json')
SAMPLE=Path('/backups/feedback-acceptance-example.xlsx')
report={'started_at':datetime.now(UTC).isoformat(),'fixture':'synthetic acceptance only, not real user feedback',
        'origin':'https://api.yaligo-growth.top','device_id':DEVICE,'checks':{}}
engine=engine_for_url(get_settings().database_url)
header={}
invite_digest=None


def request(client,method,path,expected=200,**kwargs):
    response=client.request(method,'/api/v1'+path,**kwargs)
    assert response.status_code==expected,(method,path,response.status_code)
    return response.json() if response.content else None


try:
    with httpx.Client(base_url=report['origin'],timeout=40,follow_redirects=False) as client:
        body={'device_id':DEVICE,'client_submission_id':str(uuid4()),'survey_version':'experience-v1',
            'app_version':'acceptance-example','identity':{'label':'验收样例-第一次','life_stage':'大学生','gender':'女生','age_range':'18—22岁'},
            'answers':{'duration':'first','features':['chat','record'],'satisfaction':'2','difficult_help':'worse',
                       'issues':['advice'],'issue_detail':'验收样例：建议不符合实际。','comment':'=1+1'},'consent':True}
        request(client,'POST','/experience-feedback',401,json=body)
        report['checks']['unauthenticated_401']=True
        with Session(engine) as db:
            invite=issue_invite(db,'feedback-smoke')
            invite_digest=digest(invite)
        token=request(client,'POST','/cloud-auth/activate',json={'device_id':DEVICE,'invite_code':invite})['access_token']
        header={'Authorization':'Bearer '+token}
        del token,invite
        request(client,'POST','/experience-feedback',403,headers=header,json={**body,'device_id':DEVICE+'-other'})
        report['checks']['cross_device_403']=True
        request(client,'POST','/experience-feedback',422,headers=header,json={**body,'consent':False})
        report['checks']['explicit_consent_required']=True
        first=request(client,'POST','/experience-feedback',headers=header,json=body)
        assert datetime.fromisoformat(first['submitted_at']).tzinfo is not None
        assert request(client,'POST','/experience-feedback',headers=header,json=body)==first
        report['checks']['idempotent_retry_same_id_and_server_time']=True
        time.sleep(1.1)
        second_body={**body,'client_submission_id':str(uuid4()),
            'identity':{'label':'验收样例-第二次','life_stage':'研究生','gender':'暂不透露','age_range':'23—29岁'},
            'answers':{'duration':'2_3','features':['chat'],'satisfaction':'4','difficult_help':'a_little','difficult_types':['listen','step']}}
        second=request(client,'POST','/experience-feedback',headers=header,json=second_body)
        assert second['id']!=first['id'] and second['submitted_at']>first['submitted_at']
        report['submission_times_utc']=[first['submitted_at'],second['submitted_at']]
        report['checks']['separate_submissions_keep_distinct_server_times']=True
        data=request(client,'GET','/data/export',headers=header,params={'device_id':DEVICE})['data']['experience_feedback']
        assert len(data)==2 and data[0]['identity_snapshot']['life_stage']=='大学生'
        assert data[1]['identity_snapshot']['life_stage']=='研究生'
        report['checks']['identity_snapshots_and_own_data_export']=True
        with Session(engine) as db:
            rows=list(db.scalars(select(ExperienceFeedback).where(ExperienceFeedback.device_id==DEVICE).order_by(ExperienceFeedback.id)))
            report['sample_export']=export_excel(rows,SAMPLE)
        book=load_workbook(SAMPLE)
        assert book['填写明细'].max_row==3 and book['每人最新一份'].max_row==2
        assert book['填写明细'].cell(2,4).value=='大学生' and book['填写明细'].cell(3,4).value=='研究生'
        report['checks']['xlsx_contains_both_identity_snapshots_and_latest_only_summary']=True
        local=book['填写明细'].cell(2,7).value
        utc=book['填写明细'].cell(2,8).value
        assert (local-utc).total_seconds()==28800
        report['checks']['xlsx_beijing_and_utc_times']=True
        assert book['文字建议'].cell(3,7).value=='=1+1' and book['文字建议'].cell(3,7).data_type=='s'
        report['checks']['user_text_is_not_excel_formula']=True
        report['passed']=True
finally:
    try:
        if header:
            with httpx.Client(base_url=report['origin'],timeout=40) as client:
                request(client,'DELETE','/data',204,headers=header,params={'device_id':DEVICE})
                data=request(client,'GET','/data/export',headers=header,params={'device_id':DEVICE})['data']['experience_feedback']
                assert data==[]
                report['checks']['delete_without_chat_conversation']=True
    finally:
        with Session(engine) as db:
            credential=db.scalar(select(CloudCredential).where(CloudCredential.device_id==DEVICE))
            if credential: credential.revoked_at=int(time.time())
            if invite_digest:
                invitation=db.get(CloudInvite,invite_digest)
                if invitation: invitation.expires_at=int(time.time())
            db.commit()
            remaining=db.scalar(select(func.count()).select_from(ExperienceFeedback).where(ExperienceFeedback.device_id==DEVICE))
            report['checks']['synthetic_rows_removed']=remaining==0
            report['checks']['synthetic_credential_revoked']=credential is not None and credential.revoked_at is not None
        report['passed']=report.get('passed',False) and all(report['checks'].values())
        report['finished_at']=datetime.now(UTC).isoformat()
        REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(report,ensure_ascii=False,indent=2))
