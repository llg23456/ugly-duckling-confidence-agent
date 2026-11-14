"""Small real-AI acceptance run, using only synthetic identities and media.

Run once inside the cloud container after configuring a dedicated model key.
This performs billable model calls. Never write credentials to the report.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import UTC, datetime
import hashlib
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import time
import zlib

import httpx


ROOT = Path('/backups')
REPORT = ROOT / 'cloud-live-result.json'
DEVICE = 'cloud-live-' + datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')
report = {'device_id': DEVICE, 'fixture': 'synthetic content only', 'steps': {},
          'android_playback_tested': False, 'started_at': datetime.now(UTC).isoformat()}


def save():
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


def call(path, **kwargs):
    with httpx.Client(base_url='http://127.0.0.1:8000', timeout=150) as client:
        response = client.post('/api/v1' + path, **kwargs)
        if response.status_code >= 400:
            raise RuntimeError(f'HTTP {response.status_code}: {response.text[:300]}')
        return response


def check_chat():
    body = call('/chat', json={'device_id': DEVICE,
        'message': '我叫云测小林，平时喜欢在图书馆安静学习。接下来我想练习自我介绍，今天已经完整练习了两次。'}).json()
    assert body['mock'] is False, body.get('mock_reason')
    assert body['model'] and body['reply']
    return body


def check_image():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + b''.join(
        b'\xee\x20\x20' if 50 <= x < 200 and 80 <= y < 175 else b'\xff\xff\xff'
        for x in range(256)) for y in range(256))
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 256, 256, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))
    body = call('/multimodal/image', data={'device_id': DEVICE + '-image',
        'prompt': '请客观描述图片中主要的颜色和形状。'},
        files={'file': ('synthetic-rectangle.png', png, 'image/png')}).json()
    assert body['mock'] is False
    assert '红' in body['reply'] and ('矩形' in body['reply'] or '长方形' in body['reply']), body['reply']
    return body


def check_tts():
    body = call('/multimodal/speech', json={
        'text': '今天我练习了自我介绍，也向老师请教了一个问题。', 'voice': 'Serena'})
    assert body.headers.get('content-type', '').startswith('audio/')
    path = ROOT / 'cloud-live-tts.wav'
    path.write_bytes(body.content)
    info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error',
        '-show_entries', 'format=duration:stream=codec_name,sample_rate', '-of', 'json', str(path)]))
    assert 1 < float(info['format']['duration']) < 30
    return {'bytes': len(body.content), 'sha256': hashlib.sha256(body.content).hexdigest(), 'probe': info}


def check_audio():
    body = call('/multimodal/audio', data={'device_id': DEVICE + '-audio'},
        files={'file': ('synthetic-tts.wav', (ROOT / 'cloud-live-tts.wav').read_bytes(), 'audio/wav')}).json()
    assert body['mock'] is False
    assert '自我介绍' in body['user_text'] and '老师' in body['user_text'], body['user_text']
    return body


def check_memory():
    deadline = time.monotonic() + 65
    memories = []
    while time.monotonic() < deadline:
        with httpx.Client(timeout=10) as client:
            response = client.get('http://127.0.0.1:8000/api/v1/memories', params={'device_id': DEVICE})
            response.raise_for_status()
            memories = response.json()['memories']
        if any('云测小林' in m['content'] for m in memories):
            break
        time.sleep(2)
    identity = [m for m in memories if '云测小林' in m['content']]
    assert identity, 'No extracted synthetic identity found within 65 seconds'
    assert all(m['source_id'] and m['source_excerpt'] for m in identity)
    recalled = call('/chat', json={'device_id': DEVICE, 'message': '我叫什么名字？'}).json()
    assert recalled['mock'] is False and '云测小林' in recalled['reply']
    assert any('云测小林' in e['summary'] for e in recalled['evidence']), 'Answer lacks actual memory evidence'
    with sqlite3.connect('file:/data/confidence_agent.db?mode=ro', uri=True) as db:
        model_rows = db.execute('SELECT DISTINCT m.model FROM memories m JOIN conversations c ON c.id=m.conversation_id WHERE c.device_id=?', (DEVICE,)).fetchall()
        embedding_rows = db.execute('SELECT DISTINCT m.embedding_model FROM memories m JOIN conversations c ON c.id=m.conversation_id WHERE c.device_id=?', (DEVICE,)).fetchall()
    return {'memories': identity, 'reply': recalled, 'extraction_models': [r[0] for r in model_rows],
            'embedding_models': [r[0] for r in embedding_rows]}


def run_step(name, fn):
    start = time.monotonic()
    try:
        result = {'status': 'passed', 'result': fn()}
    except Exception as exc:
        result = {'status': 'failed', 'error_type': type(exc).__name__, 'error': str(exc)[:400]}
    result['seconds'] = round(time.monotonic() - start, 2)
    print(json.dumps({'step': name, **{k: v for k, v in result.items() if k != 'result'}}, ensure_ascii=False), flush=True)
    return result


def main():
    health = httpx.get('http://127.0.0.1:8000/health').raise_for_status().json()
    assert health['ai_configured'] and not health['mock']
    report['health'] = health
    save()
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {pool.submit(run_step, name, fn): name for name, fn in
                [('chat', check_chat), ('image', check_image), ('tts', check_tts)]}
        for job in as_completed(jobs):
            report['steps'][jobs[job]] = job.result()
            save()
    for name, fn, prerequisite in [('audio', check_audio, 'tts'), ('memory', check_memory, 'chat')]:
        report['steps'][name] = (run_step(name, fn) if report['steps'][prerequisite]['status'] == 'passed'
                                  else {'status': 'blocked', 'prerequisite': prerequisite})
        save()
    report['completed_at'] = datetime.now(UTC).isoformat()
    report['all_passed'] = all(v['status'] == 'passed' for v in report['steps'].values())
    save()
    print('LIVE_REPORT', str(REPORT), 'ALL_PASSED', report['all_passed'], flush=True)
    raise SystemExit(0 if report['all_passed'] else 1)


if __name__ == '__main__':
    main()
