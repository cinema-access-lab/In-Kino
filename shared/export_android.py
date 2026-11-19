#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Convert a .csfp landmark fingerprint (from fingerprint_lab.py) to the portable,
CRC-checked .csref format read by the Android app. Requires numpy only.

Binary schema (all little endian): 'CSRF' u32(version=1) f64(duration_seconds)
u32(count) then count of (u32 hash, i32 frame), sorted by hash ascending,
followed by CRC32 covering every preceding byte. Hop=256, sr=11025,
landmark hashes are exactly those produced by fingerprint_lab.py.
"""

import argparse
import struct
import zlib
from pathlib import Path

import numpy as np

VERSION = "in-kino.landmarks.v0.1"
MAGIC = b"CSRF"
MAX_COUNT = 5_000_000


def convert(src, dst):
    with np.load(str(src), allow_pickle=False) as fp:
        version = str(fp["version"].item())
        if version != VERSION:
            raise ValueError(f"Unsupported fingerprint version: {version}")
        times = fp["times"]
        hashes = fp["hashes"]
        duration = float(fp["duration"].item())
    if (
        times.ndim != 1
        or hashes.shape != times.shape
        or len(times) == 0
        or len(times) > MAX_COUNT
        or not np.isfinite(duration)
        or not 0 < duration < 12 * 3600
        or np.any(times < 0)
        or np.any(times > duration * 11025 / 256 + 150)
    ):
        raise ValueError("Invalid or oversized fingerprint")
    times = times.astype("<i4", copy=False)
    hashes = hashes.astype("<u4", copy=False)
    order = np.argsort(hashes, kind="stable")
    records = np.empty(len(times), dtype=[("hash", "<u4"), ("frame", "<i4")])
    records["hash"] = hashes[order]
    records["frame"] = times[order]
    header = struct.pack("<4sIdI", MAGIC, 1, duration, len(records))
    blob = header + records.tobytes()
    blob += struct.pack("<I", zlib.crc32(blob) & 0xFFFFFFFF)
    dst = Path(dst)
    dst.write_bytes(blob)
    return {
        "source": str(src),
        "output": str(dst),
        "duration_seconds": duration,
        "landmarks": len(records),
        "bytes": len(blob),
    }


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Export existing .csfp as offline Android .csref")
    ap.add_argument("source_csfp")
    ap.add_argument("destination_csref")
    args = ap.parse_args()
    print(convert(args.source_csfp, args.destination_csref))
