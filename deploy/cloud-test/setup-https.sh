#!/usr/bin/env bash
# Run as root on the Hong Kong instance after verifying api.yaligo-growth.top DNS.
set -euo pipefail
test "$(id -u)" = 0
root=/opt/ugly-duckling-cloudtest
test -f "$root/current/deploy/cloud-test/Caddyfile.bootstrap"
getent ahostsv4 api.yaligo-growth.top | awk '{print $1}' | sort -u | grep -Fxq '47.83.165.140'

if ! command -v caddy >/dev/null; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y --no-install-recommends debian-keyring debian-archive-keyring apt-transport-https curl gnupg
    key=$(mktemp)
    list=$(mktemp)
    trap 'rm -f "$key" "$list"' EXIT
    curl --fail --silent --show-error --location --max-time 60 \
        https://dl.cloudsmith.io/public/caddy/stable/gpg.key -o "$key"
    gpg --dearmor --yes --output /usr/share/keyrings/caddy-stable-archive-keyring.gpg "$key"
    curl --fail --silent --show-error --location --max-time 60 \
        https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt -o "$list"
    install -m 644 "$list" /etc/apt/sources.list.d/caddy-stable.list
    chmod 644 /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    apt-get update -qq
    # Supply our restricted configuration before the package starts its service.
    install -d -m 755 /etc/caddy
    test ! -f /etc/caddy/Caddyfile
    install -m 644 "$root/current/deploy/cloud-test/Caddyfile.bootstrap" /etc/caddy/Caddyfile
    apt-get -o Dpkg::Options::=--force-confold install -y caddy
else
    # Existing configurations must be reviewed rather than silently replaced.
    cmp "$root/current/deploy/cloud-test/Caddyfile.bootstrap" /etc/caddy/Caddyfile
fi

caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl enable --now caddy
systemctl reload caddy
systemctl is-active caddy
caddy version
