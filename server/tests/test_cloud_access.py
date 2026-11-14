import time
from contextlib import contextmanager

import pytest
from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import cloud_access as access
from app.core.config import Settings
from app.db.models import Conversation
from app.db.session import get_db
from app.main import app


@pytest.fixture
def cloud(client, monkeypatch):
    settings = Settings(_env_file=None, cloud_auth_required=True, enable_live_ai=False, dashscope_api_key="")
    monkeypatch.setattr(access, "get_settings", lambda: settings)
    access._windows.clear()
    @contextmanager
    def session():
        yield from app.dependency_overrides[get_db]()
    def issue(device=None):
        with session() as db:
            return access.issue_invite(db, "automated test", device)
    def activate(device="owner"):
        response = client.post('/api/v1/cloud-auth/activate', json={'invite_code': issue(), 'device_id': device})
        assert response.status_code == 200, response.text
        return {'Authorization': 'Bearer ' + response.json()['access_token']}
    yield client, settings, session, issue, activate
    access._windows.clear()


def test_unauthorized_and_bound_data_access(cloud):
    client, _, _, _, activate = cloud
    assert client.get('/health').status_code == 200
    assert client.get('/api/v1/conversations/owner/messages').status_code == 401
    headers = activate()
    result = client.post('/api/v1/chat', headers=headers, json={'device_id': 'owner', 'message': '今天练习介绍自己'})
    assert result.status_code == 200, result.text
    assert client.get('/api/v1/conversations/owner/messages', headers=headers).json()['messages']
    assert client.get('/api/v1/conversations/other/messages', headers=headers).status_code == 403
    json_headers={**headers,'Content-Type':'application/json'}
    assert client.get('/api/v1/conversations/other/messages', headers=json_headers).status_code == 403
    assert client.get('/api/v1/conversations/owner/messages', headers=json_headers).status_code == 200
    assert client.request('GET','/api/v1/conversations/owner/messages', headers=json_headers,content=b'bad').status_code == 400
    assert client.get('/api/v1/data/export?device_id=other', headers=headers).status_code == 403
    assert client.post('/api/v1/chat', headers=headers, json={'device_id':'other','message':'窥探'}).status_code == 403
    assert client.get('/api/v1/records?device_id=owner&device_id=other', headers=headers).status_code == 403
    assert client.post('/api/v1/multimodal/image', headers=headers, data={'device_id':'other'}, files={'file':('a.png',b'fake','image/png')}).status_code == 403
    # Missing multipart device_id must not fall back to the old android-demo default.
    assert client.post('/api/v1/multimodal/image', headers=headers, files={'file':('a.png',b'fake','image/png')}).status_code == 403


def test_invite_single_use_expiry_and_claim_protection(cloud):
    client, _, session, issue, _ = cloud
    code = issue()
    payload={'invite_code':code,'device_id':'new'}
    response=client.post('/api/v1/cloud-auth/activate',json=payload)
    assert response.status_code == 200
    assert client.post('/api/v1/cloud-auth/activate',json=payload).status_code == 401
    with session() as db:
        assert db.get(access.CloudInvite,access.digest(code)).used_at
        assert not db.get(access.CloudCredential,access.digest(response.json()['access_token'])).digest == response.json()['access_token']
        db.add(Conversation(device_id='existing'))
        db.commit()
    assert client.post('/api/v1/cloud-auth/activate',json={'invite_code':issue(),'device_id':'existing'}).status_code == 409
    expired=issue()
    with session() as db:
        db.get(access.CloudInvite,access.digest(expired)).expires_at=int(time.time())-1
        db.commit()
    assert client.post('/api/v1/cloud-auth/activate',json={'invite_code':expired,'device_id':'expired'}).status_code == 401


def test_bound_rotation_revoke_and_expiry(cloud):
    client, _, session, issue, activate = cloud
    old=activate()
    code=issue('owner')
    assert client.post('/api/v1/cloud-auth/activate',json={'invite_code':code,'device_id':'other'}).status_code == 403
    new=client.post('/api/v1/cloud-auth/activate',json={'invite_code':code,'device_id':'owner'}).json()
    assert client.get('/api/v1/cloud-auth/me',headers=old).status_code == 401
    header={'Authorization':'Bearer '+new['access_token']}
    assert client.get('/api/v1/cloud-auth/me',headers=header).status_code == 200
    with session() as db:
        row=db.scalar(select(access.CloudCredential))
        row.expires_at=int(time.time())-1
        db.commit()
    assert client.get('/api/v1/records?device_id=owner',headers=header).status_code == 401
    with session() as db:
        row=db.scalar(select(access.CloudCredential))
        row.expires_at=int(time.time())+1000
        row.revoked_at=int(time.time())
        db.commit()
    assert client.get('/api/v1/cloud-auth/me',headers=header).status_code == 401


def test_quotas_and_activation_throttle(cloud):
    client, settings, _, _, activate = cloud
    header=activate()
    settings.cloud_daily_requests=1
    assert client.post('/api/v1/chat',headers=header,json={'device_id':'owner','message':'今天好'}).status_code == 200
    assert client.post('/api/v1/chat',headers=header,json={'device_id':'owner','message':'再试'}).status_code == 429
    assert client.get('/api/v1/conversations/owner/messages',headers=header).status_code == 200
    for _ in range(4):
        assert client.post('/api/v1/cloud-auth/activate',json={'invite_code':'wrong','device_id':'x'}).status_code == 401
    assert client.post('/api/v1/cloud-auth/activate',json={'invite_code':'wrong','device_id':'x'}).status_code == 429


def test_every_business_route_rejects_unauthenticated_requests(cloud):
    import re
    client = cloud[0]
    paths = app.openapi()['paths']
    checked = 0
    for path, methods in paths.items():
        if not path.startswith('/api/v1/') or '/cloud-auth/' in path:
            continue
        for method in methods:
            response = client.request(method, re.sub(r'\{[^}]+\}', '1', path), json={})
            assert response.status_code == 401, (method, path, response.text)
            checked += 1
    assert checked >= 35


def test_video_slot_released():
    original = access.get_settings
    access.get_settings=lambda: Settings(_env_file=None,cloud_auth_required=True)
    try:
        first=access.limit_video_render(); next(first)
        with pytest.raises(Exception) as error:
            next(access.limit_video_render())
        assert error.value.status_code==429
        first.close()
        second=access.limit_video_render(); next(second); second.close()
    finally:
        access.get_settings=original


def test_invite_atomic_redemption(cloud):
    from concurrent.futures import ThreadPoolExecutor
    _, _, session, issue, _ = cloud
    code=issue()
    def attempt(device):
        with session() as db:
            try:
                access.redeem(db,code,device)
                return 200
            except access.HTTPException as error:
                return error.status_code
    with ThreadPoolExecutor(2) as pool:
        assert sorted(pool.map(attempt,['first','second'])) == [200,401]


def test_global_quota_is_shared_across_devices(cloud):
    client, settings, session, _, activate = cloud
    one=activate('one'); two=activate('two')
    settings.cloud_global_daily_requests=1
    assert client.post('/api/v1/chat',headers=one,json={'device_id':'one','message':'今天好'}).status_code == 200
    assert client.post('/api/v1/chat',headers=two,json={'device_id':'two','message':'今天好'}).status_code == 429
    with session() as db:
        usage=db.scalar(select(access.CloudUsage).where(access.CloudUsage.key.endswith(':two')))
        assert usage is None or usage.used == 0


def test_legacy_review_get_consumes_quota(cloud):
    client, settings, _, _, activate = cloud
    header=activate()
    settings.cloud_daily_requests=1
    assert client.post('/api/v1/chat',headers=header,json={'device_id':'owner','message':'今天好'}).status_code == 200
    assert client.get('/api/v1/reviews/week?device_id=owner',headers=header).status_code == 429
    assert client.get('/api/v1/conversations/owner/messages',headers=header).status_code == 200
