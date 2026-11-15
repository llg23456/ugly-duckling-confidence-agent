"""Two invited identities over real HTTP. Run inside the API container after deployment.

Creates only explicitly labelled acceptance data, cleans it up, never prints credentials.
The private admin is temporary and removed at the end; normal admin credentials are untouched.
"""

import argparse
from io import BytesIO
import json
import secrets
import time
from uuid import uuid4

import httpx
from PIL import Image
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.core.cloud_access import issue_invite, digest, CloudCredential, CloudInvite
from app.core.config import get_settings
from app.db.session import engine_for_url
from app.db.community import CommunityAdmin, CommunityAdminSession, CommunityQuota
from app.api.routes.community_admin import password_hash


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://api.yaligo-growth.top")
    parser.add_argument("--admin-url", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    engine = engine_for_url(get_settings().database_url)
    name = "acceptance-" + uuid4().hex[:12]
    password = secrets.token_urlsafe(30)
    users = [name + "-a", name + "-b"]
    clients = []
    invites = []
    checked = []
    private = httpx.Client(base_url=args.admin_url, timeout=30, trust_env=False)

    def check(label, condition):
        assert condition, label
        checked.append(label)

    def req(client, method, path, device=None, status=200, **kwargs):
        if device:
            if method == "GET":
                kwargs["params"] = {**kwargs.get("params", {}), "device_id": device}
            elif "files" in kwargs:
                kwargs["data"] = {**kwargs.get("data", {}), "device_id": device}
            else:
                kwargs["json"] = {**kwargs.get("json", {}), "device_id": device}
        response = client.request(method, path, **kwargs)
        assert response.status_code == status, (
            method,
            path,
            response.status_code,
            response.text[:200],
        )
        return (
            response.json()
            if response.content
            and response.headers.get("content-type", "").startswith("application/json")
            else response
        )

    root = "/api/v1/community"
    try:
        with Session(engine) as db:
            db.add(CommunityAdmin(username=name, password_hash=password_hash(password)))
            db.commit()
            for user in users:
                invites.append(issue_invite(db, "community acceptance", user))
        for user, invite in zip(users, invites):
            client = httpx.Client(base_url=args.base_url, timeout=40, trust_env=False)
            clients.append(client)
            activation = req(
                client,
                "POST",
                "/api/v1/cloud-auth/activate",
                json={"invite_code": invite, "device_id": user},
            )
            client.headers["Authorization"] = "Bearer " + activation["access_token"]
            req(
                client,
                "PATCH",
                root + "/profile",
                user,
                json={"nickname": "社区验收临时用户", "avatar": 1},
            )
        a, b = clients
        ua, ub = users
        check(
            "bound_device", req(a, "GET", root + "/posts", ub, status=403) is not None
        )
        image = Image.new("RGB", (2000, 1000), "green")
        exif = Image.Exif()
        exif[270] = "private metadata"
        raw = BytesIO()
        image.save(raw, format="JPEG", exif=exif)
        media = req(
            a,
            "POST",
            root + "/media",
            ua,
            files={"file": ("photo.jpg", raw.getvalue(), "image/jpeg")},
        )["id"]
        req(b, "GET", root + "/media/" + media, ub, status=404)
        checked.append("unbound_picture_owner_only")
        payload = {
            "client_id": str(uuid4()),
            "title": "社区验收临时内容",
            "content": "自动验收结束后会删除，不属于体验反馈",
            "topic": "成长分享",
            "media_ids": [media],
        }
        post = req(a, "POST", root + "/posts", ua, json=payload)
        pid = post["id"]
        check(
            "publish_retry_same_id",
            req(a, "POST", root + "/posts", ua, json=payload)["id"] == pid,
        )
        other = req(b, "GET", root + "/posts/" + pid, ub)
        check(
            "shared_feed_public_uuid",
            not other["is_mine"] and "device_id" not in str(other),
        )
        normalized = req(b, "GET", root + "/media/" + media, ub)
        decoded = Image.open(BytesIO(normalized.content))
        check(
            "normalized_picture_without_exif",
            max(decoded.size) == 1600 and not decoded.getexif(),
        )
        for _ in range(2):
            check(
                "idempotent_like",
                req(b, "PUT", root + "/posts/" + pid + "/like", ub)["like_count"] == 1,
            )
        comment_body = {"client_id": str(uuid4()), "content": "这是一条验收评论"}
        comment = req(
            b, "POST", root + "/posts/" + pid + "/comments", ub, json=comment_body
        )
        check(
            "comment_retry_same_id",
            req(b, "POST", root + "/posts/" + pid + "/comments", ub, json=comment_body)[
                "id"
            ]
            == comment["id"],
        )
        req(b, "DELETE", root + "/posts/" + pid, ub, status=404)
        checked.append("author_only_delete")
        report = {
            "kind": "post",
            "target_id": pid,
            "reason": "other",
            "detail": "验收举报，无需人工处理",
        }
        req(b, "POST", root + "/reports", ub, json=report)
        req(b, "POST", root + "/reports", ub, json=report)
        req(b, "PUT", root + "/blocks/" + post["author"]["id"], ub)
        req(b, "GET", root + "/posts/" + pid, ub, status=404)
        req(b, "DELETE", root + "/blocks/" + post["author"]["id"], ub)
        checked.append("block_and_unblock")
        login = req(
            private,
            "POST",
            "/community-admin/login",
            json={"username": name, "password": password},
        )
        private.headers["x-csrf-token"] = login["csrf"]
        private.headers["Origin"] = args.admin_url
        action = {
            "kind": "post",
            "target_id": pid,
            "action": "hide",
            "reason": "验收下架",
        }
        req(private, "POST", "/community-admin/action", json=action)
        req(b, "GET", root + "/posts/" + pid, ub, status=404)
        req(a, "GET", root + "/media/" + media, ua, status=404)
        req(private, "GET", "/community-admin/media/" + media)
        checked.append("moderation_hides_post_and_picture")
        action["action"] = "restore"
        req(private, "POST", "/community-admin/action", json=action)
        req(b, "GET", root + "/media/" + media, ub)
        checked.append("restore_content")
        req(
            private,
            "POST",
            "/community-admin/action",
            json={
                "kind": "profile",
                "target_id": post["author"]["id"],
                "action": "restrict",
                "reason": "验收限制",
            },
        )
        req(
            a,
            "POST",
            root + "/posts",
            ua,
            status=403,
            json={**payload, "client_id": str(uuid4()), "media_ids": []},
        )
        checked.append("publishing_restriction")
        exported = req(a, "GET", "/api/v1/data/export", ua)["data"]
        check(
            "own_export",
            len(exported["community"]["posts"]) == 1 and exported["messages"] == [],
        )
        req(a, "DELETE", "/api/v1/data", ua, status=204)
        req(b, "GET", root + "/posts/" + pid, ub, status=404)
        req(b, "GET", root + "/media/" + media, ub, status=404)
        checked.append("erasure_inaccessible")
        with httpx.Client(
            base_url=args.base_url, timeout=30, trust_env=False
        ) as outsider:
            req(outsider, "GET", root + "/posts", ua, status=401)
            req(outsider, "GET", "/community-admin/", status=404)
        checked.append("public_admin_and_unauthenticated_api_denied")
        print(
            json.dumps(
                {
                    "passed": True,
                    "checks": checked,
                    "count": len(checked),
                    "synthetic_only": True,
                    "cleanup": True,
                },
                ensure_ascii=False,
            )
        )
    finally:
        for client, user in zip(clients, users):
            try:
                req(client, "DELETE", "/api/v1/data", user, status=204)
            finally:
                client.close()
        private.close()
        with Session(engine) as db:
            db.execute(
                delete(CommunityAdminSession).where(
                    CommunityAdminSession.username == name
                )
            )
            db.execute(delete(CommunityAdmin).where(CommunityAdmin.username == name))
            db.execute(
                delete(CloudCredential).where(CloudCredential.device_id.in_(users))
            )
            db.execute(
                delete(CloudInvite).where(
                    CloudInvite.digest.in_([digest(i) for i in invites])
                )
            )
            db.commit()


if __name__ == "__main__":
    main()
