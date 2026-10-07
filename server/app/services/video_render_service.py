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


ASSET_ROOT = Path(__file__).resolve().parents[1] / "assets"
BACKGROUND_MUSIC_TRACKS = {
    "reflective": ASSET_ROOT / "bgm_reflective_chillax.m4a",
    "bright": ASSET_ROOT / "bgm_bright_side.m4a",
}
BACKGROUND_MUSIC_VOLUME = 0.174  # About 10 dB below the previous 0.55 mix gain.
# Keep the old constant for callers that only need the default track path.
BACKGROUND_MUSIC = BACKGROUND_MUSIC_TRACKS["bright"]

_REFLECTIVE_WORDS = (
    "绝望", "崩溃", "低落", "难过", "沮丧", "紧张", "忐忑", "焦虑", "压力",
    "担心", "害怕", "不自信", "怀疑", "受挫", "想放弃", "错了很多",
)
_BRIGHT_WORDS = (
    "轻松", "平静", "平稳", "开心", "快乐", "有力量", "期待", "更敢", "愿意",
    "帮助", "支持", "陪伴", "调整", "完成", "弄清楚", "看清", "休息", "运动", "户外",
)
_RECOVERY_WORDS = (
    "不再焦虑", "不焦虑", "没那么焦虑", "不再紧张", "不紧张", "没那么紧张",
    "不再难过", "没那么难过", "缓过来", "好多了",
)


class RenderScene(BaseModel):
    frame: str = Field(min_length=1)
    duration_ms: int = Field(ge=1, le=300_000)
    script_scene_index: int | None = Field(default=None, ge=0, le=39)
    narration: str = ""
    narration_duration_ms: int = Field(default=0, ge=0, le=300_000)
    original: str = ""


class RenderManifest(BaseModel):
    scenes: list[RenderScene] = Field(min_length=3, max_length=40)


def _event_music_mood(event: object) -> str:
    feeling = str(getattr(event, "feeling", "") or "").strip()
    text = "；".join(str(getattr(event, field, "") or "") for field in (
        "fact", "attempt", "own_effort", "support_received",
    ))
    combined = f"{feeling}；{text}"
    if any(word in combined for word in _RECOVERY_WORDS):
        return "bright"
    if any(word in feeling for word in _REFLECTIVE_WORDS):
        return "reflective"
    if any(word in feeling for word in _BRIGHT_WORDS):
        return "bright"
    reflective_score = sum(combined.count(word) for word in _REFLECTIVE_WORDS)
    bright_score = sum(combined.count(word) for word in _BRIGHT_WORDS)
    return "reflective" if reflective_score > bright_score else "bright"


def scene_music_moods(scenes: list[dict], events: list[object]) -> list[str]:
    """Map every video scene to one of the two approved music moods."""
    event_by_id = {getattr(event, "id", None): event for event in events}
    result: list[str] = []
    for scene in scenes:
        moods = [
            _event_music_mood(event_by_id[event_id])
            for event_id in scene.get("source_event_ids", [])
            if event_id in event_by_id
        ]
        reflective_count = moods.count("reflective")
        # A tie or missing mood evidence uses the calm/positive track.
        result.append("reflective" if reflective_count > len(moods) - reflective_count else "bright")
    return result


def _music_runs(moods: list[str], durations: list[float]) -> list[tuple[str, float]]:
    """Merge adjacent scenes with the same mood so their track keeps playing."""
    runs: list[tuple[str, float]] = []
    for mood, duration in zip(moods, durations, strict=True):
        selected = mood if mood in BACKGROUND_MUSIC_TRACKS else "bright"
        if runs and runs[-1][0] == selected:
            runs[-1] = (selected, runs[-1][1] + duration)
        else:
            runs.append((selected, duration))
    return runs


def _expanded_music_moods(draft: RenderManifest, moods: list[str] | None) -> list[str]:
    if moods is None:
        return ["bright"] * len(draft.scenes)
    if any(mood not in BACKGROUND_MUSIC_TRACKS for mood in moods):
        raise HTTPException(422, "视频情绪与片段配置不一致")
    indexes = [scene.script_scene_index for scene in draft.scenes]
    if any(index is not None for index in indexes):
        if any(index is None or index >= len(moods) for index in indexes):
            raise HTTPException(422, "视频情绪与片段配置不一致")
        return [moods[index] for index in indexes if index is not None]
    if len(moods) != len(draft.scenes):
        raise HTTPException(422, "视频情绪与片段配置不一致")
    return moods


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


def render_uploaded_video(
    manifest: str,
    files: list[UploadFile],
    scene_music_moods: list[str] | None = None,
) -> bytes:
    try:
        draft = RenderManifest.model_validate_json(manifest)
    except ValidationError as exc:
        raise HTTPException(422, "视频片段配置无效") from exc
    expanded_music_moods = _expanded_music_moods(draft, scene_music_moods)
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
        base_output = root / "growth-base.mp4"
        _run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "1", "-i", str(listing),
              "-c", "copy", "-movflags", "+faststart", str(base_output)])
        if any(not path.is_file() for path in BACKGROUND_MUSIC_TRACKS.values()):
            raise HTTPException(503, "视频背景音乐资源缺失")
        output = root / "growth.mp4"
        fade_out_start = max(0.0, total_duration - 1.5)
        runs = _music_runs(expanded_music_moods, [item[3] for item in prepared])
        mix_command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(base_output)]
        filters = ["[0:a]aresample=48000,volume=0.5[main]"]
        crossfade_duration = 0.5
        for index, (mood, duration) in enumerate(runs):
            mix_command += ["-stream_loop", "-1", "-i", str(BACKGROUND_MUSIC_TRACKS[mood])]
            # Give every non-final run a small overlap so a crossfade does not shorten the video.
            trim_duration = duration + (crossfade_duration if index < len(runs) - 1 else 0.0)
            filters.append(
                f"[{index + 1}:a]aresample=48000,atrim=duration={trim_duration:.3f},"
                "asetpts=PTS-STARTPTS,loudnorm=I=-22:LRA=7:TP=-2.0,"
                "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,"
                f"volume={BACKGROUND_MUSIC_VOLUME:.3f}[bg{index}]"
            )
        background_label = "[bg0]"
        for index in range(1, len(runs)):
            output_label = f"bgmix{index}"
            filters.append(
                f"{background_label}[bg{index}]acrossfade=d={crossfade_duration}:c1=tri:c2=tri"
                f"[{output_label}]"
            )
            background_label = f"[{output_label}]"
        filters.append(
            f"{background_label}afade=t=in:st=0:d=1.5,"
            f"afade=t=out:st={fade_out_start:.3f}:d=1.5[bg]"
        )
        filters.append(
            "[main][bg]amix=inputs=2:duration=first:dropout_transition=1:normalize=0,"
            "alimiter=limit=0.95[audio]"
        )
        mix_command += [
            "-filter_complex", ";".join(filters), "-map", "0:v:0", "-map", "[audio]",
            "-t", f"{total_duration:.3f}", "-c:v", "copy", "-c:a", "aac", "-ar", "48000", "-ac", "2",
            "-movflags", "+faststart", str(output),
        ]
        _run(mix_command)
        return output.read_bytes()
