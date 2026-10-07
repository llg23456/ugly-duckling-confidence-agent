import io
import json
import math
import shutil
import struct
import subprocess
import wave
import zlib
from types import SimpleNamespace

import pytest
from fastapi import HTTPException, UploadFile

from app.services.video_render_service import (
    BACKGROUND_MUSIC_TRACKS, RenderManifest, _expanded_music_moods, _music_runs,
    render_uploaded_video, scene_music_moods,
)


def test_background_music_asset_is_bundled():
    assert set(BACKGROUND_MUSIC_TRACKS) == {"reflective", "bright"}
    assert all(path.is_file() and path.stat().st_size > 100_000 for path in BACKGROUND_MUSIC_TRACKS.values())


def test_scene_music_moods_and_adjacent_runs():
    events = [
        SimpleNamespace(id=1, feeling="忐忑", fact="担心复试", attempt=None, own_effort=None, support_received=None),
        SimpleNamespace(id=2, feeling="低落", fact="今天有点难过", attempt=None, own_effort=None, support_received=None),
        SimpleNamespace(id=3, feeling="平稳", fact="按计划完成复习", attempt=None, own_effort=None, support_received=None),
        SimpleNamespace(id=4, feeling="轻松", fact="散步后轻松了", attempt=None, own_effort=None, support_received=None),
    ]
    scenes = [{"source_event_ids": [index]} for index in range(1, 5)]
    moods = scene_music_moods(scenes, events)
    assert moods == ["reflective", "reflective", "bright", "bright"]
    assert _music_runs(moods, [1.0, 2.0, 3.0, 4.0]) == [
        ("reflective", 3.0), ("bright", 7.0),
    ]


def test_music_mood_defaults_to_bright_for_tie_or_recovery():
    events = [
        SimpleNamespace(id=1, feeling="", fact="虽然担心但完成了", attempt=None, own_effort=None, support_received=None),
        SimpleNamespace(id=2, feeling="没那么焦虑了", fact="缓过来一些", attempt=None, own_effort=None, support_received=None),
    ]
    assert scene_music_moods([{"source_event_ids": [1, 2]}], events) == ["bright"]


def test_expanded_photo_pages_keep_their_script_scene_music():
    draft = RenderManifest.model_validate({"scenes": [
        {"frame": "a.png", "duration_ms": 1000, "script_scene_index": 0},
        {"frame": "b.png", "duration_ms": 1000, "script_scene_index": 0},
        {"frame": "c.png", "duration_ms": 1000, "script_scene_index": 1},
    ]})
    assert _expanded_music_moods(draft, ["reflective", "bright"]) == [
        "reflective", "reflective", "bright",
    ]


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


def test_render_rejects_music_mood_count_mismatch():
    with pytest.raises(HTTPException) as error:
        render_uploaded_video(json.dumps(_manifest()), _uploads(), ["bright"])
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
    video = render_uploaded_video(
        json.dumps(draft),
        _uploads({"n.audio": bytes(narration), "o.audio": _wave(6, 12000)}),
        ["reflective", "bright", "bright"],
    )
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
