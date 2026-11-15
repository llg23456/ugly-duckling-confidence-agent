"""SSH-only batch invitation tool; also runnable via stdin in an existing API container.

The operator copy needs no new API image, HTTP endpoint, or schema migration.
"""
import argparse
import csv
from datetime import datetime, timedelta, timezone
import secrets
import sys
import time

from sqlalchemy.orm import Session

from app.core.cloud_access import CloudInvite, digest
from app.core.config import get_settings
from app.db.session import engine_for_url


def issue_batch(db: Session, label_prefix: str, count: int, *,
                invite_days: int = 7, access_days: int = 30) -> list[tuple[str, CloudInvite]]:
    if not 1 <= count <= 200:
        raise ValueError("Batch size must be 1-200")
    if not 1 <= len(label_prefix) <= 96:
        raise ValueError("Label prefix must contain 1-96 characters")
    if not 1 <= invite_days <= 30 or not 1 <= access_days <= 90:
        raise ValueError("Invitation validity must be 1-30 days; access validity must be 1-90 days")
    if invite_days > access_days:
        raise ValueError("Invitation validity cannot exceed access validity")
    now = int(time.time())
    rows = []
    for number in range(1, count + 1):
        code = secrets.token_hex(10).upper()
        row = CloudInvite(digest=digest(code), label=f"{label_prefix}-{number:03d}",
                          device_id=None, expires_at=now + invite_days * 86400,
                          access_until=now + access_days * 86400)
        rows.append((code, row))
    db.add_all([row for _, row in rows])
    db.commit()
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description="Issue one-use cloud invitations as CSV (private SSH only)")
    parser.add_argument("--label-prefix", required=True)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--invite-days", type=int, default=7)
    parser.add_argument("--access-days", type=int, default=30)
    args = parser.parse_args(argv)
    if not 1 <= args.invite_days <= 30 or not 1 <= args.access_days <= 90:
        parser.error("--invite-days must be 1-30; --access-days must be 1-90")
    if args.invite_days > args.access_days:
        parser.error("--invite-days cannot exceed --access-days (both start at creation)")
    if not 1 <= args.count <= 200 or not 1 <= len(args.label_prefix) <= 96:
        parser.error("--count must be 1-200; --label-prefix must contain 1-96 characters")
    with Session(engine_for_url(get_settings().database_url)) as db:
        rows = issue_batch(db, args.label_prefix, args.count,
                           invite_days=args.invite_days, access_days=args.access_days)
        writer = csv.writer(sys.stdout)
        writer.writerow(["label", "invite_code", "redeem_before", "access_until", "recipient", "note"])
        zone = timezone(timedelta(hours=8))
        for code, row in rows:
            writer.writerow([row.label, code,
                             datetime.fromtimestamp(row.expires_at, zone).isoformat(),
                             datetime.fromtimestamp(row.access_until, zone).isoformat(), "", ""])


if __name__ == "__main__":
    main()
