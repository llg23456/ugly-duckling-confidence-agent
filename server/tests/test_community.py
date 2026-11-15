from datetime import UTC, datetime, timedelta
from io import BytesIO
from uuid import uuid4

import pytest
from PIL import Image
from sqlalchemy import select

from app.core.config import Settings
from app.core import cloud_access
from app.db.session import get_db
from app.db.community import CommunityAdmin, CommunityMedia, CommunityQuota
from app.api.routes.community_admin import password_hash
from app.main import app
from app.services.community_service import cleanup_media

ROOT = "/api/v1/community"


def test_parallel_retries_only_create_one_post(community):
    from concurrent.futures import ThreadPoolExecutor

    c, _ = community
    client_id = str(uuid4())
    with ThreadPoolExecutor(max_workers=4) as executor:
        replies = list(executor.map(lambda _: create(c, client_id=client_id), range(4)))
    assert all(r.status_code == 200 for r in replies)
    assert len({r.json()["id"] for r in replies}) == 1
    assert len(c.get(ROOT + "/posts", params={"device_id": "bob"}).json()["posts"]) == 1


def test_erasing_profile_does_not_reset_device_publish_limit(community):
    c, _ = community
    for _ in range(10):
        assert create(c).status_code == 200
    assert c.delete("/api/v1/data?device_id=alice").status_code == 204
    c.patch(ROOT + "/profile", json={"device_id": "alice", "nickname": "重新开始"})
    assert create(c).status_code == 429


@pytest.fixture
def community(client, tmp_path, monkeypatch):
    settings = Settings(
        _env_file=None,
        enable_live_ai=False,
        dashscope_api_key="",
        community_media_dir=str(tmp_path / "media"),
    )
    monkeypatch.setattr("app.services.community_service.get_settings", lambda: settings)
    monkeypatch.setattr("app.api.routes.community.get_settings", lambda: settings)
    cloud_access._windows.clear()
    for device in ("alice", "bob"):
        assert (
            client.patch(
                ROOT + "/profile",
                json={"device_id": device, "nickname": device, "avatar": 1},
            ).status_code
            == 200
        )
    yield client, settings
    cloud_access._windows.clear()


def create(client, device="alice", **extra):
    body = dict(
        device_id=device,
        client_id=str(uuid4()),
        title="今天的一小步",
        content="和师姐聊了复习方法",
        topic="学习交流",
    )
    body.update(extra)
    return client.post(ROOT + "/posts", json=body)


def photo():
    image = Image.new("RGB", (2000, 1000), "green")
    exif = Image.Exif()
    exif[270] = "private location note"
    out = BytesIO()
    image.save(out, format="JPEG", exif=exif)
    return out.getvalue()


def upload(client, device="alice"):
    return client.post(
        ROOT + "/media",
        data={"device_id": device},
        files={"file": ("photo.jpg", photo(), "image/jpeg")},
    )


def admin(client):
    with next(app.dependency_overrides[get_db]()) as db:
        db.add(
            CommunityAdmin(
                username="reviewer",
                password_hash=password_hash("a-long-private-password"),
            )
        )
        db.commit()
    result = client.post(
        "/community-admin/login",
        json={"username": "reviewer", "password": "a-long-private-password"},
    )
    assert result.status_code == 200, result.text
    return {"x-csrf-token": result.json()["csrf"], "Origin": "http://testserver"}


def test_shared_feed_ownership_idempotency_and_privacy(community):
    c, _ = community
    key = str(uuid4())
    first = create(c, client_id=key)
    assert first.status_code == 200, first.text
    post = first.json()
    pid = post["id"]
    assert create(c, client_id=key).json()["id"] == pid
    assert create(c, client_id=key, content="different").status_code == 409
    feed = c.get(ROOT + "/posts", params={"device_id": "bob"}).json()["posts"]
    assert len(feed) == 1 and not feed[0]["is_mine"]
    assert "device_id" not in str(feed) and "alice" not in str(feed[0]["author"]["id"])
    assert (
        c.request(
            "DELETE", ROOT + "/posts/" + pid, json={"device_id": "bob"}
        ).status_code
        == 404
    )
    for _ in range(2):
        assert (
            c.put(ROOT + "/posts/" + pid + "/like", json={"device_id": "bob"}).json()[
                "like_count"
            ]
            == 1
        )
    for _ in range(2):
        assert (
            c.request(
                "DELETE", ROOT + "/posts/" + pid + "/like", json={"device_id": "bob"}
            ).json()["like_count"]
            == 0
        )
    cid = str(uuid4())
    payload = {"device_id": "bob", "client_id": cid, "content": "加油，慢慢来"}
    comment = c.post(ROOT + "/posts/" + pid + "/comments", json=payload).json()
    assert (
        c.post(ROOT + "/posts/" + pid + "/comments", json=payload).json()["id"]
        == comment["id"]
    )
    assert (
        c.request(
            "DELETE", ROOT + "/comments/" + comment["id"], json={"device_id": "alice"}
        ).status_code
        == 404
    )
    assert (
        c.get(ROOT + "/posts/" + pid, params={"device_id": "alice"}).json()[
            "comment_count"
        ]
        == 1
    )
    assert (
        c.request(
            "DELETE", ROOT + "/comments/" + comment["id"], json={"device_id": "bob"}
        ).status_code
        == 204
    )


def test_search_pagination_blocks_and_no_fake_items(community):
    c, _ = community
    assert c.get(ROOT + "/posts", params={"device_id": "bob"}).json()["posts"] == []
    # Exercise pagination without bypassing the real daily publish limit.
    for index in range(23):
        device = "writer-" + str(index)
        c.patch(
            ROOT + "/profile",
            json={"device_id": device, "nickname": "朋友" + str(index)},
        )
        assert create(c, device=device, title=f"分享{index}").status_code == 200
    page = c.get(ROOT + "/posts", params={"device_id": "bob"}).json()
    assert len(page["posts"]) == 20 and page["next_cursor"]
    rest = c.get(
        ROOT + "/posts", params={"device_id": "bob", "cursor": page["next_cursor"]}
    ).json()
    assert len(rest["posts"]) == 3 and not set(p["id"] for p in page["posts"]) & set(
        p["id"] for p in rest["posts"]
    )
    assert (
        len(
            c.get(
                ROOT + "/posts",
                params={"device_id": "bob", "q": "分享22", "topic": "学习交流"},
            ).json()["posts"]
        )
        == 1
    )
    target = page["posts"][0]["author"]["id"]
    assert (
        c.put(ROOT + "/blocks/" + target, json={"device_id": "bob"}).status_code == 200
    )
    assert (
        len(c.get(ROOT + "/blocks", params={"device_id": "bob"}).json()["profiles"])
        == 1
    )
    assert (
        c.get(
            ROOT + "/posts/" + page["posts"][0]["id"], params={"device_id": "bob"}
        ).status_code
        == 404
    )
    c.request("DELETE", ROOT + "/blocks/" + target, json={"device_id": "bob"})
    assert (
        c.get(
            ROOT + "/posts/" + page["posts"][0]["id"], params={"device_id": "bob"}
        ).status_code
        == 200
    )


def test_picture_normalization_access_capacity_and_retirement(community):
    c, settings = community
    media = upload(c).json()
    mid = media["id"]
    assert c.get(ROOT + "/media/" + mid, params={"device_id": "bob"}).status_code == 404
    raw = c.get(ROOT + "/media/" + mid, params={"device_id": "alice"})
    image = Image.open(BytesIO(raw.content))
    assert max(image.size) == 1600 and not image.getexif()
    assert "no-store" in raw.headers["cache-control"]
    assert create(c, device="bob", media_ids=[mid]).status_code == 422
    post = create(c, media_ids=[mid], content="").json()
    assert c.get(ROOT + "/media/" + mid, params={"device_id": "bob"}).status_code == 200
    assert create(c, media_ids=[mid]).status_code == 422
    settings.community_media_limit_bytes = 1
    assert upload(c).status_code == 507
    assert create(c).status_code == 200
    assert (
        c.post(
            ROOT + "/media",
            data={"device_id": "alice"},
            files={"file": ("bad.jpg", b"not an image", "image/jpeg")},
        ).status_code
        == 415
    )
    assert (
        c.request(
            "DELETE", ROOT + "/posts/" + post["id"], json={"device_id": "alice"}
        ).status_code
        == 204
    )
    assert (
        c.get(ROOT + "/media/" + mid, params={"device_id": "alice"}).status_code == 404
    )
    with next(app.dependency_overrides[get_db]()) as db:
        db.get(CommunityMedia, mid).retired_at = datetime.now(UTC) - timedelta(days=8)
        db.commit()
        assert cleanup_media(db) == 1


def test_reports_admin_csrf_hide_restore_and_restriction(community):
    c, _ = community
    media = upload(c).json()["id"]
    post = create(c, media_ids=[media]).json()
    for _ in range(2):
        assert (
            c.post(
                ROOT + "/reports",
                json={
                    "device_id": "bob",
                    "kind": "post",
                    "target_id": post["id"],
                    "reason": "privacy",
                },
            ).status_code
            == 200
        )
    assert (
        c.get(ROOT + "/posts/" + post["id"], params={"device_id": "bob"}).status_code
        == 200
    )
    assert c.get("/community-admin/items").status_code == 401
    assert (
        c.get("/community-admin/", headers={"X-Forwarded-For": "1.2.3.4"}).status_code
        == 404
    )
    assert (
        c.get("/community-admin/", headers={"Host": "api.example.com"}).status_code
        == 404
    )
    headers = admin(c)
    assert len(c.get("/community-admin/items?kind=reports").json()["items"]) == 1
    action = {
        "kind": "post",
        "target_id": post["id"],
        "action": "hide",
        "reason": "包含他人隐私",
    }
    assert c.post("/community-admin/action", json=action).status_code == 403
    assert (
        c.post(
            "/community-admin/action",
            headers={**headers, "Origin": "http://evil.example"},
            json=action,
        ).status_code
        == 403
    )
    assert (
        c.post("/community-admin/action", headers=headers, json=action).status_code
        == 200
    )
    assert (
        c.get(ROOT + "/posts/" + post["id"], params={"device_id": "bob"}).status_code
        == 404
    )
    assert (
        c.get(ROOT + "/media/" + media, params={"device_id": "alice"}).status_code
        == 404
    )
    own = c.get(ROOT + "/posts", params={"device_id": "alice", "mine": True}).json()[
        "posts"
    ][0]
    assert own["reason"] == "包含他人隐私" and own["media_ids"] == []
    assert c.get("/community-admin/media/" + media).status_code == 200
    action["action"] = "restore"
    c.post("/community-admin/action", headers=headers, json=action)
    assert (
        c.get(ROOT + "/media/" + media, params={"device_id": "bob"}).status_code == 200
    )
    assert (
        c.post(
            "/community-admin/action",
            headers=headers,
            json={
                "kind": "profile",
                "target_id": post["author"]["id"],
                "action": "restrict",
                "reason": "重复违规",
            },
        ).status_code
        == 200
    )
    assert create(c).status_code == 403
    c.request("DELETE", ROOT + "/posts/" + post["id"], json={"device_id": "alice"})
    assert (
        c.post("/community-admin/action", headers=headers, json=action).status_code
        == 409
    )
    assert len(c.get("/community-admin/items?kind=audit").json()["items"]) == 3


def test_export_and_erase_without_private_conversation(community):
    c, _ = community
    mid = upload(c).json()["id"]
    pid = create(c, media_ids=[mid]).json()["id"]
    result = c.get("/api/v1/data/export?device_id=alice").json()["data"]
    assert result["messages"] == [] and len(result["community"]["posts"]) == 1
    assert "device_id" not in str(result["community"])
    result = c.request("DELETE", "/api/v1/data?device_id=alice")
    assert result.status_code == 204, result.text
    assert c.get(ROOT + "/posts", params={"device_id": "bob"}).json()["posts"] == []
    assert c.get(ROOT + "/media/" + mid, params={"device_id": "bob"}).status_code == 404
    assert (
        c.get("/api/v1/data/export?device_id=alice").json()["data"]["community"][
            "profile"
        ]
        is None
    )


def test_separate_model_quota_and_bound_device(community, monkeypatch):
    c, settings = community
    settings.cloud_auth_required = True
    settings.cloud_daily_requests = 1
    monkeypatch.setattr(cloud_access, "get_settings", lambda: settings)
    with next(app.dependency_overrides[get_db]()) as db:
        invite = cloud_access.issue_invite(db, "community test")
        db.commit()
    token = c.post(
        "/api/v1/cloud-auth/activate",
        json={"invite_code": invite, "device_id": "authorized"},
    ).json()["access_token"]
    headers = {"Authorization": "Bearer " + token}
    assert c.get(ROOT + "/posts?device_id=authorized").status_code == 401
    assert c.get(ROOT + "/posts?device_id=alice", headers=headers).status_code == 403
    assert (
        c.patch(
            ROOT + "/profile",
            headers=headers,
            json={"device_id": "authorized", "nickname": "云测朋友"},
        ).status_code
        == 200
    )
    for _ in range(10):
        body = {
            "device_id": "authorized",
            "client_id": str(uuid4()),
            "title": "小步",
            "content": "试一试",
        }
        assert c.post(ROOT + "/posts", headers=headers, json=body).status_code == 200
    assert (
        c.post(
            ROOT + "/posts", headers=headers, json={**body, "client_id": str(uuid4())}
        ).status_code
        == 429
    )
    # Exhausted community publishing does not exhaust the model's independent daily budget.
    assert (
        c.post(
            "/api/v1/chat",
            headers=headers,
            json={"device_id": "authorized", "message": "今天好"},
        ).status_code
        == 200
    )
    assert (
        c.post(
            "/api/v1/chat",
            headers=headers,
            json={"device_id": "authorized", "message": "再试"},
        ).status_code
        == 429
    )
