"""Render the Android frame/audio manifest in an isolated temporary directory."""

import json
import math
import shutil
import struct
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

from fastapi import HTTPException, UploadFile
from pydantic import BaseModel, Field, ValidationError

from app.core.config import get_settings


class RenderScene(BaseModel):
    frame: str = Field(min_length=1)
    duration_ms: int = Field(ge=1, le=300_000)
    narration: str = ""
    narration_duration_ms: int = Field(default=0, ge=0, le=300_000)
    original: str = ""


class RenderManifest(BaseModel):
    scenes: list[RenderScene] = Field(min_length=3, max_length=7)


def _run(command: list[str], *, timeout: int = 120) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(command, capture_output=True, check=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(504, "视频合成超时，请减少素材后重试") from exc
    except subprocess.CalledProcessError as exc:
        raise HTTPException(422, "素材无法解码或合成，请检查照片和音频") from exc


def _repair_wave(data: bytes) -> bytes:
    # Some streamed PCM WAVs retain placeholder RIFF/data lengths.
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        return data
    result = bytearray(data)
    struct.pack_into("<I", result, 4, len(result) - 8)
    offset = 12
    while offset + 8 <= len(result):
        size = struct.unpack_from("<I", result, offset + 4)[0]
        if result[offset:offset + 4] == b"data":
            struct.pack_into("<I", result, offset + 4, len(result) - offset - 8)
            break
        offset += 8 + size + size % 2
    return bytes(result)


def _duration(ffprobe: str, path: Path) -> float:
    result = _run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path)], timeout=30)
    try:
        value = float(json.loads(result.stdout)["format"]["duration"])
        if not math.isfinite(value) or value <= 0:
            raise ValueError
        return value
    except (KeyError, ValueError, TypeError) as exc:
        raise HTTPException(422, "无法读取音频时长") from exc


def render_uploaded_video(manifest: str, files: list[UploadFile]) -> bytes:
    try:
        draft = RenderManifest.model_validate_json(manifest)
    except ValidationError as exc:
        raise HTTPException(422, "视频片段配置无效") from exc
    filenames = [file.filename or "" for file in files]
    referenced = {name for scene in draft.scenes for name in (scene.frame, scene.narration, scene.original) if name}
    if (len(filenames) != len(set(filenames)) or set(filenames) != referenced
            or any("/" in name or "\\" in name or name in (".", "..") for name in filenames)):
        raise HTTPException(422, "上传素材与片段配置不一致")
    ffmpeg = get_settings().ffmpeg_path.strip() or shutil.which("ffmpeg")
    if not ffmpeg or not Path(ffmpeg).is_file():
        raise HTTPException(503, "电脑端未配置 FFmpeg")
    sibling = Path(ffmpeg).with_name("ffprobe.exe" if Path(ffmpeg).suffix.lower() == ".exe" else "ffprobe")
    ffprobe = str(sibling) if sibling.is_file() else shutil.which("ffprobe")
    if not ffprobe:
        raise HTTPException(503, "电脑端未配置 FFprobe")
    with TemporaryDirectory(prefix="duck-video-") as directory:
        root = Path(directory)
        paths: dict[str, Path] = {}
        total_bytes = 0
        for index, upload in enumerate(files):
            content = upload.file.read(40 * 1024 * 1024 + 1)
            total_bytes += len(content)
            if not content or len(content) > 40 * 1024 * 1024 or total_bytes > 80 * 1024 * 1024:
                raise HTTPException(413, "素材过大或为空，请缩小后重试")
            path = root / f"asset-{index}.bin"
            path.write_bytes(_repair_wave(content))
            paths[upload.filename] = path
        prepared = []
        total_duration = 0.0
        for scene in draft.scenes:
            narration = paths.get(scene.narration)
            original = paths.get(scene.original)
            narration_duration = _duration(ffprobe, narration) if narration else 0.0
            original_duration = min(_duration(ffprobe, original), 5.0) if original else 0.0
            duration = max(scene.duration_ms / 1000, narration_duration + original_duration, 0.5)
            total_duration += duration
            prepared.append((scene, narration, original, duration))
        if total_duration > 300:
            raise HTTPException(422, "成长小片最长为五分钟，请减少素材或音频")
        clips = []
        for index, (scene, narration, original, duration) in enumerate(prepared):
            command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-loop", "1", "-i", str(paths[scene.frame])]
            filters, audio_labels = [], []
            for audio in (narration, original):
                if audio:
                    number = len(audio_labels) + 1
                    command += ["-i", str(audio)]
                    trim = "atrim=duration=5," if audio == original else ""
                    filters.append(f"[{number}:a]{trim}aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS[a{number}]")
                    audio_labels.append(f"[a{number}]")
            if audio_labels:
                join = f"{''.join(audio_labels)}concat=n={len(audio_labels)}:v=0:a=1," if len(audio_labels) > 1 else audio_labels[0]
                filters.append(f"{join}apad,atrim=duration={duration}[audio]")
            else:
                filters.append(f"anullsrc=r=48000:cl=stereo,atrim=duration={duration}[audio]")
            filters.append("[0:v]scale=540:960:force_original_aspect_ratio=decrease,pad=540:960:(ow-iw)/2:(oh-ih)/2:color=0xFFFBF2,setsar=1[video]")
            clip = root / f"clip-{index}.mp4"
            command += ["-filter_complex", ";".join(filters), "-map", "[video]", "-map", "[audio]", "-t", str(duration),
                        "-r", "24", "-c:v", "libx264", "-preset", "ultrafast", "-threads", "2", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-ar", "48000", "-ac", "2", str(clip)]
            _run(command)
            clips.append(clip)
        listing = root / "clips.txt"
        listing.write_text("\n".join(f"file '{clip.name}'" for clip in clips), encoding="utf-8")
        output = root / "growth.mp4"
        _run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "1", "-i", str(listing),
              "-c", "copy", "-movflags", "+faststart", str(output)])
        return output.read_bytes()
