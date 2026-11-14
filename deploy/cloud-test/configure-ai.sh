#!/bin/bash
# Run interactively on the server. The key never appears in shell history.
set -euo pipefail
read -r -s -p 'Paste the project DashScope API key (hidden): ' DUCKLING_API_KEY
printf '\n'
export DUCKLING_API_KEY
python3 - <<'PY'
import json
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

key = os.environ['DUCKLING_API_KEY'].strip()
if not key or '\n' in key or '\r' in key:
    raise SystemExit('Empty or multiline key rejected; configuration unchanged')
if '*' in key or '…' in key:
    raise SystemExit('Masked API key rejected. Copy the complete original key, not the console display; configuration unchanged')
path = Path('/opt/ugly-duckling-cloudtest/config/runtime.env')
lines = path.read_text().splitlines()
config = {}
for line in lines:
    if not line.strip() or line.lstrip().startswith('#') or '=' not in line:
        continue
    name, value = line.split('=', 1)
    value = value.strip()
    if value.startswith('"'):
        value = json.loads(value)
    else:
        value = value.strip("'")
    config[name.strip()] = value
if key.startswith('sk-sp-'):
    raise SystemExit('This app needs a standard Model Studio API key, not a Coding Plan key; configuration unchanged')
endpoint = config.get('DASHSCOPE_BASE_URL', 'https://dashscope.aliyuncs.com/compatible-mode/v1').rstrip('/')
request = Request(endpoint + '/chat/completions',
    data=json.dumps({'model': config.get('CHAT_MODEL', 'qwen3.8-omni-flash'),
                     'messages': [{'role': 'user', 'content': '你好'}],
                     'reasoning_effort': 'none', 'max_tokens': 8}).encode(),
    headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
try:
    with urlopen(request, timeout=45) as response:
        result = json.load(response)
    if not result.get('choices'):
        raise SystemExit('Unexpected model response; configuration unchanged')
except HTTPError as exc:
    # Print only HTTP status, never an upstream message that may contain a key.
    raise SystemExit(f'Model verification failed: HTTP {exc.code}; check API key, region and model permissions. Configuration unchanged') from None
except (URLError, TimeoutError):
    raise SystemExit('Model endpoint unavailable or timed out; configuration unchanged') from None
print('Real model request verified')
values = {'ENABLE_LIVE_AI': 'true', 'DASHSCOPE_API_KEY': json.dumps(key)}
lines = [line for line in lines if not any(line.startswith(name + '=') for name in values)]
temporary = path.with_suffix('.pending')
temporary.touch(mode=0o600, exist_ok=False)
temporary.write_text('\n'.join(lines + [name + '=' + value for name, value in values.items()]) + '\n')
temporary.replace(path)
os.chmod(path, 0o600)
print('AI configuration saved; key hidden')
PY
unset DUCKLING_API_KEY
cd /opt/ugly-duckling-cloudtest/current
docker compose -f deploy/cloud-test/compose.yaml up -d --wait --wait-timeout 90
curl --fail --silent http://127.0.0.1:8000/health
printf '\n'
