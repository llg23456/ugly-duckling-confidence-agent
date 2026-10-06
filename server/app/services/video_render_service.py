import json
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory

from fastapi import HTTPException, UploadFile

from app.core.config import get_settings


MAX_UPLOAD_BYTES = 64 * 1024 * 1024
MAX_VIDEO_MS = 5 * 60_000


def _ffmpeg_path() -> str:
    configured = get_settings().ffmpeg_path.strip()
    executable = configured or shutil.which("ffmpeg")
    if not executable:
        raise HTTPException(status_code=503, detail="电脑端没有找到 FFmpeg，暂时无法合成视频")
    return executable


def _validated_manifest(raw: str, uploaded_names: set[str]) -> list[dict]:
    try:
        payload = json.loads(raw)
        scenes = payload["scenes"]
    except (TypeError, KeyError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=422, detail="视频素材清单格式无效") from exc
    if not isinstance(scenes, list) or not 3 <= len(scenes) <= 7:
        raise HTTPException(status_code=422, detail="视频需要三到七个片段")
    cleaned: list[dict] = []
    total_duration_ms = 0
    for index, scene in enumerate(scenes):
        if not isinstance(scene, dict):
            raise HTTPException(status_code=422, detail="视频片段格式无效")
        frame = str(scene.get("frame", ""))
        narration = str(scene.get("narration", "")) or None
        original = str(scene.get("original", "")) or None
        try:
            duration_ms = int(scene["duration_ms"])
            narration_ms = int(scene.get("narration_duration_ms", 0))
        except (KeyError, TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail="视频片段时长无效") from exc
        if duration_ms <= 0:
            raise HTTPException(status_code=422, detail=f"第 {index + 1} 个视频片段时长无效")
        if frame not in uploaded_names or narration and narration not in uploaded_names or original and original not in uploaded_names:
            raise HTTPException(status_code=422, detail="视频素材文件不完整")
        total_duration_ms += duration_ms
        cleaned.append({
            "frame": frame,
            "narration": narration,
            "original": original,
            "duration_ms": duration_ms,
            "narration_duration_ms": max(0, narration_ms),
        })
    if total_duration_ms > MAX_VIDEO_MS:
        raise HTTPException(status_code=422, detail="视频总时长不能超过 5 分钟")
    return cleaned


def render_uploaded_video(manifest: str, uploads: list[UploadFile]) -> bytes:
    with TemporaryDirectory(prefix="confidence-video-") as temp_name:
        work = Path(temp_name)
        uploaded: dict[str, Path] = {}
        total_bytes = 0
        for index, upload in enumerate(uploads):
            original_name = Path(upload.filename or f"asset-{index}").name
            if original_name in uploaded:
                raise HTTPException(status_code=422, detail="视频素材文件名重复")
            content = upload.file.read(MAX_UPLOAD_BYTES + 1)
            total_bytes += len(content)
            if total_bytes > MAX_UPLOAD_BYTES:
                raise HTTPException(status_code=413, detail="视频素材总大小超过 64MB")
            target = work / original_name
            target.write_bytes(content)
            uploaded[original_name] = target

        scenes = _validated_manifest(manifest, set(uploaded))
        concat_lines: list[str] = []
        for scene in scenes:
            concat_lines.append(f"file '{scene['frame']}'")
            concat_lines.append(f"duration {scene['duration_ms'] / 1000:.3f}")
        concat_lines.append(f"file '{scenes[-1]['frame']}'")
        concat_file = work / "frames.txt"
        concat_file.write_text("\n".join(concat_lines), encoding="utf-8")

        command = [_ffmpeg_path(), "-y", "-f", "concat", "-safe", "0", "-i", concat_file.name]
        audio_inputs: list[tuple[int, int]] = []
        elapsed_ms = 0
        input_index = 1
        for scene in scenes:
            narration = scene["narration"]
            original = scene["original"]
            if narration:
                command.extend(["-i", narration])
                audio_inputs.append((input_index, elapsed_ms))
                input_index += 1
            if original:
                command.extend(["-i", original])
                original_start = elapsed_ms + scene["narration_duration_ms"] + (250 if narration else 0)
                audio_inputs.append((input_index, original_start))
                input_index += 1
            elapsed_ms += scene["duration_ms"]

        total_seconds = elapsed_ms / 1000
        command.extend([
            "-map", "0:v:0",
            "-vf", "fps=1,format=yuv420p",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-tune", "stillimage",
            "-r", "1",
        ])
        if audio_inputs:
            delayed = []
            filters = []
            for audio_number, (source_index, delay_ms) in enumerate(audio_inputs):
                label = f"a{audio_number}"
                filters.append(
                    f"[{source_index}:a]aresample=async=1:first_pts=0,"
                    f"adelay={delay_ms}|{delay_ms}[{label}]"
                )
                delayed.append(f"[{label}]")
            filters.append(
                f"{''.join(delayed)}amix=inputs={len(delayed)}:duration=longest:normalize=0,"
                f"apad,atrim=duration={total_seconds:.3f}[aout]"
            )
            command.extend([
                "-filter_complex", ";".join(filters),
                "-map", "[aout]",
                "-c:a", "aac",
                "-b:a", "128k",
            ])
        else:
            command.append("-an")
        output = work / "growth-video.mp4"
        command.extend([
            "-t", f"{total_seconds:.3f}",
            "-movflags", "+faststart",
            output.name,
        ])
        try:
            result = subprocess.run(
                command,
                cwd=work,
                capture_output=True,
                text=True,
                timeout=120,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise HTTPException(status_code=504, detail="电脑端视频合成超过两分钟，已停止") from exc
        if result.returncode != 0 or not output.exists() or output.stat().st_size == 0:
            raise HTTPException(status_code=500, detail="电脑端视频合成失败，请检查 FFmpeg")
        return output.read_bytes()
