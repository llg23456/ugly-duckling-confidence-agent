import io
import json
import math
import shutil
import struct
import subprocess
import wave
import zlib

import pytest
from fastapi import HTTPException, UploadFile

from app.services.video_render_service import render_uploaded_video


def _png() -> bytes:
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\0" + b"\xff\xee\xdd" * 2 + b"\0" + b"\xff\xee\xdd" * 2)) + chunk(b"IEND", b"")


def _wave(seconds, amplitude):
    output = io.BytesIO()
    with wave.open(output, "wb") as writer:
        writer.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
        writer.writeframes(b"".join(struct.pack("<h", int(amplitude * math.sin(index * 2 * math.pi * 440 / 8000))) for index in range(int(seconds * 8000))))
    return output.getvalue()


def _uploads(extra=None):
    content = {f"frame-{index}.png": _png() for index in range(3)} | (extra or {})
    return [UploadFile(filename=name, file=io.BytesIO(data)) for name, data in content.items()]


def _manifest():
    return {"scenes": [{"frame": f"frame-{index}.png", "duration_ms": 500} for index in range(3)]}


def test_render_rejects_missing_or_unsafe_files():
    with pytest.raises(HTTPException) as error:
        render_uploaded_video(json.dumps(_manifest()), _uploads()[:-1])
    assert error.value.status_code == 422
    draft = _manifest()
    draft["scenes"][0]["frame"] = "../frame-0.png"
    files = _uploads()
    files[0].filename = "../frame-0.png"
    with pytest.raises(HTTPException) as error:
        render_uploaded_video(json.dumps(draft), files)
    assert error.value.status_code == 422


def test_render_rejects_total_over_five_minutes():
    if not shutil.which("ffmpeg"):
        pytest.skip("FFmpeg is required for render tests")
    draft = _manifest()
    for scene in draft["scenes"]:
        scene["duration_ms"] = 101_000
    with pytest.raises(HTTPException) as error:
        render_uploaded_video(json.dumps(draft), _uploads())
    assert error.value.status_code == 422


def test_render_plays_narration_then_capped_original_and_repairs_wave(tmp_path):
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        pytest.skip("FFmpeg and FFprobe are required for media integration")
    narration = bytearray(_wave(0.3, 1500))
    struct.pack_into("<I", narration, 4, 0xFFFFFFFF)
    struct.pack_into("<I", narration, 40, 0xFFFFFFFF)
    draft = _manifest()
    draft["scenes"][0].update(narration="n.audio", original="o.audio", narration_duration_ms=99999)
    video = render_uploaded_video(json.dumps(draft), _uploads({"n.audio": bytes(narration), "o.audio": _wave(6, 12000)}))
    path = tmp_path / "test.mp4"
    path.write_bytes(video)
    result = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration:stream=codec_name,width,height", "-of", "json", str(path)], capture_output=True, check=True)
    info = json.loads(result.stdout)
    assert 6.2 < float(info["format"]["duration"]) < 6.7
    assert any(stream.get("codec_name") == "h264" and stream["width"] == 540 and stream["height"] == 960 for stream in info["streams"])
    assert any(stream.get("codec_name") == "aac" for stream in info["streams"])
    audio = subprocess.run([ffmpeg, "-v", "error", "-i", str(path), "-f", "s16le", "-ac", "1", "-ar", "8000", "-"], capture_output=True, check=True).stdout
    samples = struct.unpack(f"<{len(audio) // 2}h", audio)
    def rms(start):
        segment = samples[int(start * 8000):int((start + 0.1) * 8000)]
        return math.sqrt(sum(item * item for item in segment) / len(segment))
    assert rms(0.1) < rms(0.7) / 3
