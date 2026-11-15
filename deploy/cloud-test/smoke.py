"""Run inside the container against its real HTTP listener with AI disabled."""
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import time
import zlib
from zoneinfo import ZoneInfo

import httpx


def main():
    started = time.monotonic()
    device = 'cloud-smoke-20261113'
    client = httpx.Client(base_url='http://127.0.0.1:8000', timeout=150)
    def request(method, path, **kwargs):
        result = client.request(method, '/api/v1' + path, **kwargs)
        result.raise_for_status()
        return result.json()

    health = client.get('/health').raise_for_status().json()
    assert health['status'] == 'ok' and health['mock'] is True
    chat = request('POST', '/chat', json={'device_id': device, 'message': '这是一条虚构的云端部署测试消息。'})
    assert chat['mock'] is True and chat['user_message_id'] > 0
    messages = request('GET', f'/conversations/{device}/messages')['messages']
    assert len(messages) >= 2
    ids = []
    for index, text in enumerate(['练习了十分钟自我介绍。', '向老师请教了一个具体问题。', '和同学散步后又练习了一次。']):
        day = datetime.now(ZoneInfo('Asia/Shanghai')) - timedelta(days=2-index)
        records = request('POST', '/records/sync', json={'device_id': device, 'records': [{
            'client_record_id': f'cloud-record-{index}', 'mode': 'text', 'text': text,
            'created_at_ms': int(day.timestamp() * 1000),
        }]})['records']
        ids.append(records[0]['event_id'])
    assert all(ids)
    review = request('POST', '/reviews/overview', json={'device_id': device, 'period': 'week'})
    assert review['review']
    script = request('POST', '/videos/scripts', json={'device_id': device, 'event_ids': ids})
    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 2, 2, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(b'\0' + b'\xff\xee\xdd' * 2 + b'\0' + b'\xff\xee\xdd' * 2))
           + chunk(b'IEND', b''))
    manifest = {'scenes': [{'frame': f'frame-{i}.png', 'duration_ms': 500} for i in range(3)]}
    response = client.post(f'/api/v1/videos/render/{script["id"]}', data={
        'device_id': device, 'manifest': json.dumps(manifest),
    }, files=[('files', (f'frame-{i}.png', png, 'image/png')) for i in range(3)])
    response.raise_for_status()
    video = Path('/backups/cloud-smoke.mp4')
    video.write_bytes(response.content)
    probe = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=codec_name,width,height',
        '-of', 'json', str(video),
    ]))
    assert any(s.get('codec_name') == 'h264' and s.get('width') == 540 and s.get('height') == 960 for s in probe['streams'])
    assert any(s.get('codec_name') == 'aac' for s in probe['streams'])
    assert 1 <= float(probe['format']['duration']) <= 3
    exported = request('GET', f'/data/export?device_id={device}')
    assert exported['device_id'] == device
    report = {'device_id': device, 'health': health, 'http_chat': 'passed (mock)',
              'messages': len(messages), 'record_events': len(ids), 'weekly_review': 'passed',
              'video_script': 'passed (mock)', 'video_render': 'passed', 'video_probe': probe,
              'video_sha256': hashlib.sha256(response.content).hexdigest(),
              'export': 'passed', 'elapsed_seconds': round(time.monotonic()-started, 2),
              'real_ai_tested': False, 'android_playback_tested': False}
    Path('/backups/cloud-smoke-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
