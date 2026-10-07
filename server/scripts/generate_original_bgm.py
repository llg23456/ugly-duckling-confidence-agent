"""Generate the original, low-variation instrumental used by growth videos.

The WAV is intentionally temporary. FFmpeg encodes the bundled AAC/M4A asset.
No third-party melody or recording is used.
"""

from __future__ import annotations

import math
import shutil
import struct
import subprocess
import tempfile
import wave
from pathlib import Path


SAMPLE_RATE = 22_050
DURATION_SECONDS = 120
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "assets" / "gentle_growth_bgm.m4a"


def _frequency(midi: int) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def _sample(time_s: float) -> float:
    # Four closely related major-sixth/major-seventh voicings, changing slowly.
    chords = (
        (48, 55, 60, 64, 69),
        (48, 55, 59, 64, 69),
        (53, 57, 60, 64, 69),
        (48, 55, 60, 64, 71),
    )
    block = int(time_s // 10.0)
    local = time_s % 10.0
    chord = chords[block % len(chords)]
    pad_envelope = math.sin(math.pi * local / 10.0) ** 0.45
    value = sum(
        0.032 * math.sin(2 * math.pi * _frequency(note) * time_s + index * 0.31)
        for index, note in enumerate(chord)
    ) * pad_envelope

    beat = 60.0 / 72.0
    step = int(time_s / beat)
    note_time = time_s - step * beat
    note = chord[(step // 2) % len(chord)] + (12 if step % 8 in (3, 7) else 0)
    pluck_envelope = min(1.0, note_time * 5.0) * math.exp(-1.65 * note_time)
    value += 0.075 * pluck_envelope * (
        math.sin(2 * math.pi * _frequency(note) * time_s)
        + 0.24 * math.sin(4 * math.pi * _frequency(note) * time_s)
    )

    # A quiet, slow-moving upper tone keeps the track light without a dramatic peak.
    shimmer_time = time_s % (beat * 8)
    shimmer_envelope = min(1.0, shimmer_time * 2.0) * math.exp(-0.55 * shimmer_time)
    value += 0.018 * shimmer_envelope * math.sin(2 * math.pi * _frequency(chord[-1] + 12) * time_s)

    global_fade = min(1.0, time_s / 2.5, (DURATION_SECONDS - time_s) / 2.5)
    return max(-0.92, min(0.92, value * max(0.0, global_fade)))


def main() -> None:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise SystemExit("ffmpeg is required to encode the background music")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="duck-bgm-") as directory:
        wav_path = Path(directory) / "gentle-growth.wav"
        with wave.open(str(wav_path), "wb") as writer:
            writer.setparams((1, 2, SAMPLE_RATE, 0, "NONE", "not compressed"))
            chunk = bytearray()
            for index in range(SAMPLE_RATE * DURATION_SECONDS):
                chunk += struct.pack("<h", int(_sample(index / SAMPLE_RATE) * 32767))
                if len(chunk) >= SAMPLE_RATE * 2:
                    writer.writeframesraw(chunk)
                    chunk.clear()
            if chunk:
                writer.writeframesraw(chunk)
        subprocess.run(
            [
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav_path),
                "-c:a", "aac", "-b:a", "96k", "-ar", "44100", "-ac", "2",
                "-metadata", "title=Gentle Growth", "-metadata", "artist=Little Ugly Duckling",
                str(OUTPUT),
            ],
            check=True,
        )
    print(f"generated {OUTPUT} ({OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
