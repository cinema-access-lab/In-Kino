#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Experimental, offline reference-audio fingerprinting and excerpt localization.

No proprietary formats or cloud services. Not production-ready, not intended as
an implementation of any particular third-party fingerprint format.

Dependencies: numpy, scipy. WAV PCM reading uses only Python stdlib.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import tempfile
import wave
from collections import defaultdict
from dataclasses import dataclass

import numpy as np
from scipy.ndimage import gaussian_filter1d, maximum_filter
from scipy.signal import resample_poly

TARGET_SR = 11025
NFFT = 2048
HOP = 256
FORMAT = "in-kino.landmarks.v0.1"


@dataclass
class Fingerprint:
    times: np.ndarray
    hashes: np.ndarray
    duration: float
    fingerprint_version: str = FORMAT


def _pcm_mono(data: bytes, sample_width: int, channels: int) -> np.ndarray:
    if sample_width == 1:
        values = (np.frombuffer(data, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif sample_width == 2:
        values = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0
    elif sample_width == 3:
        bits = np.frombuffer(data, dtype=np.uint8).reshape(-1, 3)
        raw = (
            bits[:, 0].astype(np.int32)
            | (bits[:, 1].astype(np.int32) << 8)
            | (bits[:, 2].astype(np.int32) << 16)
        )
        signed = (raw ^ 0x800000) - 0x800000
        values = signed.astype(np.float32) / 8388608.0
    elif sample_width == 4:
        values = np.frombuffer(data, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise ValueError(f"Unsupported PCM sample width: {sample_width} bytes")
    if len(values) % channels:
        raise ValueError("Malformed PCM: incomplete interleaved frame")
    return values.reshape(-1, channels).mean(axis=1, dtype=np.float32)


def _read_wav_segment(wf: wave.Wave_read, i0: int, i1: int) -> np.ndarray:
    wf.setpos(i0)
    return _pcm_mono(wf.readframes(i1 - i0), wf.getsampwidth(), wf.getnchannels())


def _wav_to_mono_memmap(path: str, folder: str) -> tuple[np.memmap, float]:
    """Converts PCM WAV to 11025 Hz via overlapping chunks (bounded RAM)."""
    with wave.open(str(path), "rb") as wf:
        if wf.getcomptype() != "NONE":
            raise ValueError("Only uncompressed integer PCM WAV files are supported")
        sr = wf.getframerate()
        total = wf.getnframes()
        if sr < 8000 or sr > 384000:
            raise ValueError(f"Unusual WAV sample rate: {sr}")
        duration = total / sr
        sample_count = math.ceil(total * TARGET_SR / sr)
        if sample_count == 0:
            raise ValueError("WAV is empty")
        out_path = os.path.join(folder, "mono-11025.f32")
        result = np.memmap(out_path, dtype=np.float32, mode="w+", shape=(sample_count,))
        duration_seconds = math.ceil(duration)
        reduction = math.gcd(TARGET_SR, sr)
        up, down = TARGET_SR // reduction, sr // reduction
        # Chunk boundaries are in whole seconds so input/output resampling
        # grids agree. One second of context suppresses filter-boundary defects.
        for second in range(0, duration_seconds, 30):
            start = second * sr
            stop = min(total, (second + 30) * sr)
            pad_start = max(0, start - sr)
            pad_stop = min(total, stop + sr)
            signal = _read_wav_segment(wf, pad_start, pad_stop)
            converted = resample_poly(signal, up, down).astype(np.float32)
            left = round((start - pad_start) * TARGET_SR / sr)
            output_start = round(start * TARGET_SR / sr)
            output_stop = min(sample_count, round(stop * TARGET_SR / sr))
            count = output_stop - output_start
            result[output_start:output_stop] = converted[left : left + count]
        result.flush()
        return result, duration


def _peak_pairs(mono: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Chunked STFT, local spectral maxima and relative landmark hashes."""
    total_frames = max(0, (len(mono) - NFFT) // HOP + 1)
    window = np.hanning(NFFT).astype(np.float32)
    results_t: list[int] = []
    results_h: list[int] = []
    # 50 seconds of anchors per chunk; 2.2-second context for landmark targets.
    frames_per_chunk = round(50 * TARGET_SR / HOP)
    context_frames = round(2.2 * TARGET_SR / HOP)
    for core_start in range(0, total_frames, frames_per_chunk):
        core_stop = min(total_frames, core_start + frames_per_chunk)
        f0 = max(0, core_start - context_frames)
        f1 = min(total_frames, core_stop + context_frames)
        first_sample = f0 * HOP
        final_sample = (f1 - 1) * HOP + NFFT
        signal = np.asarray(mono[first_sample:final_sample])
        if len(signal) < NFFT:
            continue
        frames = np.lib.stride_tricks.sliding_window_view(signal, NFFT)[::HOP]
        magnitude = np.abs(np.fft.rfft(frames * window[None, :], axis=1)).astype(np.float32)
        decibels = 20 * np.log10(np.maximum(magnitude, 1e-6))
        whitened = decibels - gaussian_filter1d(decibels, 14, axis=1)
        whitened[:, :46] = -1e3  # ignore <~250 Hz
        whitened[:, 745:] = -1e3  # ignore >~4 kHz
        candidate = (whitened >= maximum_filter(whitened, size=(7, 11), mode="nearest")) & (
            whitened > 5.0
        )
        # Avoid peaks far below each frame's level, e.g. quantization noise.
        candidate &= decibels > np.max(decibels, axis=1, keepdims=True) - 48.0
        peaks: list[tuple[int, int, float]] = []
        # Allow at most 2 peaks / ~93ms cell to bound size and suppress duplicates.
        cell = 4
        for row_start in range(0, len(frames), cell):
            r, c = np.nonzero(candidate[row_start : row_start + cell])
            if len(r) == 0:
                continue
            strength = whitened[row_start + r, c]
            winners = np.argsort(strength)[-2:]
            for j in winners:
                peaks.append((f0 + row_start + int(r[j]), int(c[j]), float(strength[j])))
        peaks.sort()
        # Pair anchors with up to 6 later peaks, in the 0.12-1.35 sec zone.
        for i, (t0, f_anchor, _) in enumerate(peaks):
            if t0 < core_start or t0 >= core_stop:
                continue
            formed = 0
            for t1, f_target, _ in peaks[i + 1 :]:
                dt = t1 - t0
                if dt > 59:
                    break
                if dt < 5:
                    continue
                if abs(f_anchor - f_target) < 7:
                    continue
                # Coarse spectral quantization improves tolerance to capture EQ.
                fa = f_anchor // 4
                fb = f_target // 4
                quantized_dt = (dt + 1) // 2
                fingerprint_hash = (fa << 16) | (fb << 8) | quantized_dt
                results_t.append(t0)
                results_h.append(fingerprint_hash)
                formed += 1
                if formed >= 6:
                    break
    return np.array(results_t, dtype=np.int32), np.array(results_h, dtype=np.uint32)


def fingerprint_wav(path: str) -> Fingerprint:
    with tempfile.TemporaryDirectory(prefix="in-kino-") as scratch:
        pcm, duration = _wav_to_mono_memmap(path, scratch)
        times, hashes = _peak_pairs(pcm)
        del pcm
    return Fingerprint(times, hashes, duration)


def write_fingerprint(path: str, fingerprint: Fingerprint) -> None:
    with open(path, "wb") as out:
        np.savez_compressed(
            out,
            times=fingerprint.times,
            hashes=fingerprint.hashes,
            duration=np.array(fingerprint.duration, np.float64),
            version=np.array(fingerprint.fingerprint_version),
        )


def read_fingerprint(path: str) -> Fingerprint:
    with np.load(path, allow_pickle=False) as contents:
        ver = str(contents["version"].item())
        if ver != FORMAT:
            raise ValueError(f"Incompatible fingerprint version {ver!r}; expected {FORMAT!r}")
        times = contents["times"].astype(np.int32)
        hashes = contents["hashes"].astype(np.uint32)
        if times.shape != hashes.shape or times.ndim != 1:
            raise ValueError("Invalid fingerprint arrays")
        return Fingerprint(times, hashes, float(contents["duration"].item()), ver)


def match(reference: Fingerprint, query: Fingerprint) -> dict:
    """Time-offset histogram voting with strong abstention for weak evidence."""
    if len(query.times) < 8:
        return {
            "matched": False,
            "reason": "insufficient query landmarks",
            "query_hashes": len(query.times),
        }
    inverse: dict[int, list[int]] = defaultdict(list)
    for t, h in zip(reference.times, reference.hashes, strict=True):
        inverse[int(h)].append(int(t))
    hist: dict[int, int] = defaultdict(int)
    # One occurrence per (query anchor, reference anchor) can vote.
    # Skip excessively common hashes to avoid silence/repeated score motifs.
    accepted = 0
    for query_t, h in zip(query.times, query.hashes, strict=True):
        candidates = inverse.get(int(h), [])
        if not candidates or len(candidates) > 100:
            continue
        accepted += 1
        for ref_t in candidates:
            bin_index = round((ref_t - int(query_t)) / 4)
            hist[bin_index] += 1
    if not hist:
        return {"matched": False, "reason": "no shared landmarks", "query_hashes": len(query.times)}

    def support(center: int) -> int:
        return sum(hist.get(center + d, 0) for d in (-1, 0, 1))

    best_bin = max(hist, key=support)
    best_hits = support(best_bin)
    alternatives = [k for k in hist if abs(k - best_bin) > 16]  # >1.5s apart
    second_hits = max((support(k) for k in alternatives), default=0)
    ratio = best_hits / max(1, second_hits)
    # Provisional thresholds; not calibrated to theater conditions.
    reliable = bool(best_hits >= 18 and ratio >= 1.6 and best_hits >= accepted * 0.05)
    return {
        "matched": reliable,
        "reference_time_at_query_start_seconds": round(best_bin * 4 * HOP / TARGET_SR, 3)
        if reliable
        else None,
        "best_votes": int(best_hits),
        "second_best_votes": int(second_hits),
        "margin_ratio": round(float(ratio), 3),
        "query_hashes": len(query.times),
        "usable_query_hashes": int(accepted),
        "reason": "candidate match" if reliable else "ambiguous or weak evidence",
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Experimental offline film-audio fingerprint lab (not GRETA .fpr)"
    )
    commands = parser.add_subparsers(dest="cmd", required=True)
    make = commands.add_parser(
        "fingerprint", help="Convert a PCM WAV to a v0.1 landmark fingerprint"
    )
    make.add_argument("wav")
    make.add_argument("output")
    find = commands.add_parser(
        "match", help="Find where an excerpt occurs in a reference fingerprint"
    )
    find.add_argument("reference")
    find.add_argument("query_wav")
    args = parser.parse_args()
    if args.cmd == "fingerprint":
        fp = fingerprint_wav(args.wav)
        write_fingerprint(args.output, fp)
        print(
            json.dumps(
                {
                    "fingerprint": args.output,
                    "duration_seconds": round(fp.duration, 3),
                    "hashes": len(fp.hashes),
                    "format": fp.fingerprint_version,
                },
                indent=2,
            )
        )
    elif args.cmd == "match":
        fp = read_fingerprint(args.reference)
        qry = fingerprint_wav(args.query_wav)
        print(json.dumps(match(fp, qry), indent=2))


if __name__ == "__main__":
    main()
