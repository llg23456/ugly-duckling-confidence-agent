"""Public wellbeing acceptance for a disposable identity; no SMS is sent.

Run inside the active container via stdin. This makes a small number of billable
model calls through synthetic chat/record requests. Never exports credentials.
"""
from datetime import UTC, datetime
import json
from pathlib import Path
import time
from uuid import uuid4

import httpx
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.cloud_access import CloudCredential, CloudInvite, digest, issue_invite
from app.core.config import get_settings
from app.db.models import Conversation, ExperienceFeedback
from app.db.session import engine_for_url
from app.services.notification_service import device_sms_available, mock_enabled

ORIGIN='https://api.yaligo-growth.top'
DEVICE='wellbeing-smoke-'+uuid4().hex
REPORT=Path('/backups/wellbeing-verification.json')
report={'started_at':datetime.now(UTC).isoformat(),'origin':ORIGIN,'device_id':DEVICE,
        'fixture':'synthetic acceptance only; no personal data or real contact numbers','checks':{}}
engine=engine_for_url(get_settings().database_url)
headers={}
invite_digest=None


def call(client,method,path,expected=200,**kwargs):
    response=client.request(method,'/api/v1'+path,**kwargs)
    assert response.status_code==expected,(method,path,response.status_code)
    return response.json() if response.content else None


def today(client):
    return call(client,'GET','/wellbeing/today',headers=headers,params={'device_id':DEVICE})


def weighted(snapshot):
    own,ai,weight=snapshot['self_score'],snapshot['ai_score'],snapshot['self_weight']
    raw=own*weight/100+ai*(100-weight)/100 if own is not None and ai is not None else own if own is not None else ai
    return min(100,max(0,int((raw+2.5)//5)*5)) if raw is not None else None


try:
    assert get_settings().app_env=='production' and get_settings().cloud_auth_required
    assert get_settings().notification_mode=='disabled' and not device_sms_available() and not mock_enabled()
    report['checks']['production_sms_disabled']=True
    with httpx.Client(base_url=ORIGIN,timeout=120,follow_redirects=False) as client:
        call(client,'GET','/wellbeing/today',401,params={'device_id':DEVICE})
        report['checks']['unauthorized_401']=True
        with Session(engine) as db:
            code=issue_invite(db,'wellbeing-release-acceptance')
            invite_digest=digest(code)
        auth=call(client,'POST','/cloud-auth/activate',json={'device_id':DEVICE,'invite_code':code})
        headers={'Authorization':'Bearer '+auth['access_token']}
        del code,auth
        call(client,'GET','/wellbeing/today',403,headers=headers,params={'device_id':DEVICE+'-other'})
        report['checks']['cross_device_403']=True
        blank=today(client)
        assert blank['today']['score'] is None and blank['today']['source_count']==0
        assert blank['today']['timezone']=='Asia/Shanghai'
        assert blank['preferences']['notification_mode']=='disabled' and not blank['preferences']['device_sms_available']
        report['checks']['new_identity_no_invented_score']=True
        first=call(client,'POST','/wellbeing/check-in',headers=headers,json={'device_id':DEVICE,'mood':5,'pressure':50})
        second=call(client,'POST','/wellbeing/check-in',headers=headers,json={'device_id':DEVICE,'mood':1,'pressure':50})
        assert first['score']==second['score']==50 and second['self_score']==50
        report['checks']['self_check_persists_and_mood_does_not_set_pressure']=True
        call(client,'POST','/wellbeing/check-in',422,headers=headers,json={'device_id':DEVICE,'mood':3,'pressure':999})
        report['checks']['invalid_pressure_rejected']=True
        call(client,'PATCH','/wellbeing/preferences',headers=headers,json={'device_id':DEVICE,'self_weight':70})
        call(client,'PATCH','/wellbeing/preferences',409,headers=headers,json={'device_id':DEVICE,'auto_notify':True})
        call(client,'POST','/wellbeing/device-sms/claim',409,headers=headers,
             json={'device_id':DEVICE,'enrollment_token':'synthetic-acceptance-token-never-enrolled'})
        report['checks']['cloud_sms_enable_and_claim_blocked']=True

        original='最近复习压力很大，已经影响睡眠，白天也很难集中注意力，但我没有伤害自己或他人的想法。'
        started=time.perf_counter()
        chat=call(client,'POST','/chat',headers=headers,json={'device_id':DEVICE,'message':original})
        report['chat_elapsed_seconds']=round(time.perf_counter()-started,3)
        assessment=chat.get('wellbeing') or {}
        report['assessment']={k:assessment.get(k) for k in ['pressure','pressure_evidence','action','semantic_available','model','notify_candidate']}
        assert chat['mock'] is False and chat['reply']
        assert assessment.get('semantic_available') is True and assessment.get('pressure') in {0,25,50,75,100}
        assert assessment.get('pressure_evidence') and assessment['pressure_evidence'] in original
        assert not chat['safety_triggered'] and not assessment['notify_candidate']
        report['checks']['live_chat_pressure_has_original_evidence']=True
        snapshot=today(client)['today']
        assert snapshot['source_count']==1 and snapshot['ai_score'] is not None and snapshot['score']==weighted(snapshot)
        report['chat_snapshot']=snapshot
        report['checks']['self_and_model_weighted_score']=True
        call(client,'PATCH','/wellbeing/preferences',headers=headers,json={'device_id':DEVICE,'self_weight':90})
        changed=today(client)['today']
        assert changed['self_weight']==90 and changed['score']==weighted(changed)
        report['checks']['weight_change_recalculates_score']=True

        record=call(client,'POST','/records/sync',headers=headers,json={'device_id':DEVICE,'records':[
            {'client_record_id':'acceptance-calm','mode':'text','status':'saved','text':'今天晚饭后去操场散步，感觉很放松。',
             'created_at_ms':int(time.time()*1000)-1000}]})['records'][0]
        for _ in range(20):
            snapshot=today(client)['today']
            if snapshot['source_count']==2: break
            time.sleep(2)
        assert snapshot['source_count']==2 and any(s['kind']=='record' and s['id']==record['id'] for s in snapshot['sources'])
        assert snapshot['score']==weighted(snapshot)
        report['record_snapshot']=snapshot
        report['checks']['saved_record_async_model_assessment']=True
        for days in [7,30,365]:
            history=call(client,'GET','/wellbeing/history',headers=headers,params={'device_id':DEVICE,'days':days})
            assert len(history['points'])==days and history['coverage']==1
            assert all(p['score'] is None for p in history['points'][:-1])
        report['checks']['seven_thirty_and_year_history_no_fabricated_days']=True
        call(client,'GET','/wellbeing/history',422,headers=headers,params={'device_id':DEVICE,'days':999})
        call(client,'DELETE','/records/client/acceptance-calm',204,headers=headers,params={'device_id':DEVICE})
        reduced=today(client)['today']
        assert reduced['source_count']==1 and reduced['score']==weighted(reduced)
        report['checks']['source_deletion_updates_score']=True
        legacy=call(client,'POST','/stress-alerts/trend',headers=headers,json={'device_id':DEVICE})
        assert legacy['message_count']==1 and len(legacy['trend'])==7
        report['checks']['legacy_stress_trend_compatible']=True

        feedback={'device_id':DEVICE,'client_submission_id':str(uuid4()),'survey_version':'experience-v1',
                  'app_version':'wellbeing-acceptance','identity':{'label':'功能验收','life_stage':'大学生'},
                  'answers':{'duration':'first','features':['stress'],'satisfaction':'3'},'consent':True}
        receipt=call(client,'POST','/experience-feedback',headers=headers,json=feedback)
        assert call(client,'POST','/experience-feedback',headers=headers,json=feedback)==receipt
        exported=call(client,'GET','/data/export',headers=headers,params={'device_id':DEVICE})['data']
        assert len(exported['daily_check_ins'])==1 and len(exported['wellbeing_signals'])==1
        assert len(exported['experience_feedback'])==1 and exported['notifications']==[]
        report['checks']['wellbeing_and_feedback_export_coexist']=True
        report['passed']=True
except Exception as error:
    report['passed']=False
    report['error']=type(error).__name__+': '+str(error)
    raise
finally:
    try:
        if headers:
            with httpx.Client(base_url=ORIGIN,timeout=120,follow_redirects=False) as client:
                call(client,'DELETE','/data',204,headers=headers,params={'device_id':DEVICE})
                data=call(client,'GET','/data/export',headers=headers,params={'device_id':DEVICE})['data']
                assert all(not data[k] for k in ['daily_check_ins','daily_stress','wellbeing_signals','wellbeing_preferences','notifications','experience_feedback'])
                report['checks']['new_data_and_feedback_deleted']=True
    finally:
        with Session(engine) as db:
            credential=db.scalar(select(CloudCredential).where(CloudCredential.device_id==DEVICE))
            if credential: credential.revoked_at=int(time.time())
            if invite_digest:
                invite=db.get(CloudInvite,invite_digest)
                if invite: invite.expires_at=int(time.time())
            db.commit()
            report['checks']['synthetic_identity_removed']=db.scalar(select(Conversation.id).where(Conversation.device_id==DEVICE)) is None
            report['checks']['synthetic_credential_revoked']=credential is not None and credential.revoked_at is not None
            report['checks']['synthetic_feedback_removed']=not db.scalar(select(func.count()).select_from(ExperienceFeedback).where(ExperienceFeedback.device_id==DEVICE))
        report['passed']=report.get('passed',False) and all(report['checks'].values())
        report['finished_at']=datetime.now(UTC).isoformat()
        REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(report,ensure_ascii=False,indent=2))
