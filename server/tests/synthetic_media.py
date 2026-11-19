# SPDX-License-Identifier: Apache-2.0
"""Deterministic test media, generated on the fly so the repository needs no audio files."""

import functools
import io
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "shared"))

from export_android import convert  # noqa: E402
from fingerprint_lab import fingerprint_wav, write_fingerprint  # noqa: E402

SUBTITLES_SRT = (
    b"1\n00:00:05,000 --> 00:00:07,500\n[MUSIC PLAYS]\n\n"
    b"2\n00:00:15,000 --> 00:00:17,500\n- Hello!\n- Hi!\n\n"
    b"3\n00:00:21,000 --> 00:00:24,000\n[DOOR CLOSES]\n"
)


def soundtrack_wav(seconds: int = 30, seed: int = 712, rate: int = 22050) -> bytes:
    """Tone bursts at random pitches over light noise: a fingerprintable stand-in for a film."""
    rng = np.random.default_rng(seed)
    signal = 0.02 * rng.standard_normal(seconds * rate)
    t = 0.0
    while t < seconds:
        length = rng.uniform(0.08, 0.4)
        start, stop = int(t * rate), min(int((t + length) * rate), len(signal))
        n = np.arange(stop - start)
        envelope = np.hanning(len(n)) if len(n) > 1 else np.ones(len(n))
        signal[start:stop] += (
            rng.uniform(0.1, 0.3) * envelope * np.sin(2 * np.pi * rng.uniform(300, 3500) * n / rate)
        )
        t += rng.uniform(0.05, 0.2)
    pcm = (np.clip(signal, -0.95, 0.95) * 32767).astype("<i2")
    out = io.BytesIO()
    with wave.open(out, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm.tobytes())
    return out.getvalue()


@functools.lru_cache(maxsize=None)
def fingerprint_csref() -> bytes:
    """A valid .csref fingerprint of soundtrack_wav(), built with the real pipeline."""
    with tempfile.TemporaryDirectory() as tmp:
        folder = Path(tmp)
        (folder / "reference.wav").write_bytes(soundtrack_wav())
        write_fingerprint(str(folder / "film.csfp"), fingerprint_wav(str(folder / "reference.wav")))
        convert(folder / "film.csfp", folder / "film.csref")
        return (folder / "film.csref").read_bytes()
