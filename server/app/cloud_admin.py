"""Private SSH CLI only; never exposed as an HTTP administration route."""
import argparse
import time

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core.cloud_access import CloudCredential, CloudInvite, issue_invite
from app.core.config import get_settings
from app.db.session import engine_for_url


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    issue = sub.add_parser("issue")
    issue.add_argument("--label", required=True)
    issue.add_argument("--device-id")
    revoke = sub.add_parser("revoke")
    revoke.add_argument("--device-id", required=True)
    sub.add_parser("list")
    with Session(engine_for_url(get_settings().database_url)) as db:
        args = parser.parse_args()
        if args.command == "issue":
            print(issue_invite(db, args.label, args.device_id))
        elif args.command == "revoke":
            now = int(time.time())
            db.execute(update(CloudCredential).where(CloudCredential.device_id == args.device_id).values(revoked_at=now))
            db.execute(update(CloudInvite).where(CloudInvite.device_id == args.device_id, CloudInvite.used_at.is_(None)).values(expires_at=now))
            db.commit()
            print("Device credentials and outstanding bound invitations revoked.")
        else:
            for row in db.scalars(select(CloudCredential)):
                print(row.device_id, row.expires_at, "revoked" if row.revoked_at else "active")


if __name__ == "__main__":
    main()
