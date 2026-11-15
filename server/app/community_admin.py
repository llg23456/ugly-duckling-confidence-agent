"""Private SSH administration and media housekeeping."""

import argparse
import getpass
import sys
from sqlalchemy import delete
from sqlalchemy.orm import Session
from app.core.config import get_settings
from app.db.session import engine_for_url
from app.db.community import CommunityAdmin, CommunityAdminSession
from app.api.routes.community_admin import password_hash
from app.services.community_service import cleanup_media


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create")
    create.add_argument("--username", default="admin")
    create.add_argument("--password-stdin", action="store_true")
    sub.add_parser("cleanup-media")
    args = parser.parse_args()
    with Session(engine_for_url(get_settings().database_url)) as db:
        if args.command == "cleanup-media":
            print({"removed": cleanup_media(db)})
            return
        if not 1 <= len(args.username) <= 40:
            parser.error("username length invalid")
        password = (
            sys.stdin.readline().rstrip("\r\n")
            if args.password_stdin
            else getpass.getpass("New administrator password: ")
        )
        if not 12 <= len(password) <= 128:
            parser.error("Use 12-128 characters")
        if db.get(CommunityAdmin, args.username):
            parser.error("Administrator already exists")
        db.add(
            CommunityAdmin(
                username=args.username, password_hash=password_hash(password)
            )
        )
        db.commit()
        print("Administrator created. Public access is not enabled.")


if __name__ == "__main__":
    main()
