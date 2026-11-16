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


def test_batch_csv_validity_and_single_use(cloud, capsys, monkeypatch):
    import csv
    import io
    from datetime import datetime
    from app import cloud_invites

    _, _, session, _, activate = cloud
    existing = activate('existing-tester')
    with session() as db:
        engine = db.get_bind()
    monkeypatch.setattr(cloud_invites, 'engine_for_url', lambda _: engine)
    before = int(time.time())
    cloud_invites.main(['--label-prefix', 'trial', '--count', '20'])
    rows = list(csv.DictReader(io.StringIO(capsys.readouterr().out)))
    assert len(rows) == len({row['invite_code'] for row in rows}) == 20
    assert rows[0]['label'] == 'trial-001' and rows[-1]['label'] == 'trial-020'
    assert all(row['recipient'] == row['note'] == '' for row in rows)
    expires = int(datetime.fromisoformat(rows[0]['redeem_before']).timestamp())
    until = int(datetime.fromisoformat(rows[0]['access_until']).timestamp())
    assert before + 7 * 86400 <= expires <= int(time.time()) + 7 * 86400
    assert until - expires == 23 * 86400
    assert rows[0]['redeem_before'].endswith('+08:00')
    with session() as db:
        invite = db.get(access.CloudInvite, access.digest(rows[0]['invite_code']))
        assert invite.digest != rows[0]['invite_code']
        assert invite.device_id is None
    # A code is still usable after the previous 24-hour deadline, then consumed once.
    monkeypatch.setattr(access.time, 'time', lambda: before + 2 * 86400)
    with session() as db:
        _, authorized_until = access.redeem(db, rows[0]['invite_code'], 'batch-user')
        assert authorized_until == until
        with pytest.raises(access.HTTPException) as used:
            access.redeem(db, rows[0]['invite_code'], 'another-user')
        assert used.value.status_code == 401
    monkeypatch.setattr(access.time, 'time', lambda: before + 8 * 86400)
    with session() as db:
        with pytest.raises(access.HTTPException) as expired:
            access.redeem(db, rows[1]['invite_code'], 'too-late')
        assert expired.value.status_code == 401
    assert cloud[0].get('/api/v1/cloud-auth/me', headers=existing).status_code == 200


@pytest.mark.parametrize('args', [
    ['--count', '0'], ['--count', '201'], ['--invite-days', '0'],
    ['--invite-days', '31'], ['--access-days', '91'],
    ['--invite-days', '7', '--access-days', '6'], ['--label-prefix', 'x' * 97],
])
def test_invalid_batch_arguments_do_not_open_database(args, monkeypatch):
    from app import cloud_invites
    def unexpected_database(_):
        pytest.fail('Invalid arguments must be rejected before opening the database')
    monkeypatch.setattr(cloud_invites, 'engine_for_url', unexpected_database)
    with pytest.raises(SystemExit) as invalid:
        cloud_invites.main(['--label-prefix', 'invalid', *args])
    assert invalid.value.code == 2


def test_batch_transaction_rolls_back_on_duplicate_codes(cloud, monkeypatch):
    from sqlalchemy.exc import IntegrityError
    from app import cloud_invites
    _, _, session, _, _ = cloud
    monkeypatch.setattr(cloud_invites.secrets, 'token_hex', lambda _: 'a' * 20)
    with session() as db:
        with pytest.raises(IntegrityError):
            cloud_invites.issue_batch(db, 'atomic-batch', 2)
        db.rollback()
        assert db.scalar(select(access.CloudInvite).where(access.CloudInvite.label.like('atomic-batch-%'))) is None


def test_unlimited_daily_quotas_include_already_exhausted_users(cloud):
    client, settings, session, _, activate = cloud
    first = activate('quota-first'); second = activate('quota-second')
    settings.cloud_daily_requests = settings.cloud_global_daily_requests = 0
    day = time.strftime('%Y-%m-%d', time.gmtime())
    keys = [f'{day}:quota-first', f'{day}:quota-second', f'{day}:*']
    with session() as db:
        db.add_all([access.CloudUsage(key=key, used=1_000_000) for key in keys])
        db.commit()
    for device, headers in [('quota-first', first), ('quota-second', second)]:
        for _ in range(2):
            assert client.post('/api/v1/chat', headers=headers,
                               json={'device_id': device, 'message': '今天好'}).status_code == 200
    with session() as db:
        assert all(db.get(access.CloudUsage, key).used == 1_000_000 for key in keys)
    assert client.get('/api/v1/conversations/quota-second/messages', headers=first).status_code == 403
    assert client.get('/api/v1/conversations/quota-first/messages').status_code == 401


@pytest.mark.parametrize('per_device,global_limit', [(0, 1), (1, 0)])
def test_daily_quota_switches_are_independent(cloud, per_device, global_limit):
    client, settings, _, _, activate = cloud
    headers = activate()
    settings.cloud_daily_requests = per_device
    settings.cloud_global_daily_requests = global_limit
    payload = {'device_id': 'owner', 'message': '今天好'}
    assert client.post('/api/v1/chat', headers=headers, json=payload).status_code == 200
    assert client.post('/api/v1/chat', headers=headers, json=payload).status_code == 429


def test_open_enrollment_without_invitation_and_device_isolation(cloud, monkeypatch):
    from app.api.routes import cloud_auth
    client, settings, session, _, activate = cloud
    old_headers = activate('previous-owner')
    with session() as db:
        old = db.scalar(select(access.CloudCredential).where(access.CloudCredential.device_id == 'previous-owner'))
        old.expires_at = int(time.time()) - 1
        db.add(Conversation(device_id='history-without-credential'))
        db.commit()
    settings.cloud_open_enrollment = True
    monkeypatch.setattr(cloud_auth, 'get_settings', lambda: settings)
    response = client.post('/api/v1/cloud-auth/bootstrap', json={'device_id': 'new-open-device'})
    assert response.status_code == 200
    assert response.json()['expires_at'] == cloud_auth.PERMANENT_ACCESS
    headers = {'Authorization': 'Bearer ' + response.json()['access_token']}
    assert client.get('/api/v1/conversations/new-open-device/messages', headers=headers).status_code == 200
    assert client.get('/api/v1/conversations/previous-owner/messages', headers=headers).status_code == 403
    assert client.get('/api/v1/cloud-auth/me', headers=old_headers).status_code == 200
    renewed = client.post('/api/v1/cloud-auth/bootstrap', headers=old_headers, json={'device_id': 'previous-owner'})
    assert renewed.status_code == 200 and renewed.json()['expires_at'] == cloud_auth.PERMANENT_ACCESS
    assert client.post('/api/v1/cloud-auth/bootstrap', json={'device_id': 'previous-owner'}).status_code == 401
    assert client.post('/api/v1/cloud-auth/bootstrap', headers=headers, json={'device_id': 'previous-owner'}).status_code == 403
    assert client.post('/api/v1/cloud-auth/bootstrap', json={'device_id': 'history-without-credential'}).status_code == 409
    with session() as db:
        row = db.scalar(select(access.CloudCredential).where(access.CloudCredential.device_id == 'new-open-device'))
        row.revoked_at = int(time.time()); db.commit()
    assert client.get('/api/v1/cloud-auth/me', headers=headers).status_code == 401


def test_bootstrap_disabled_by_default(cloud):
    assert cloud[0].post('/api/v1/cloud-auth/bootstrap', json={'device_id': 'new'}).status_code == 403
