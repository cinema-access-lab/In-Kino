# SPDX-License-Identifier: Apache-2.0
"""Validation of published In-Kino v1 asset formats.

This module intentionally has no Android/Python audio DSP dependencies.  Its
acceptance criteria are derived from FingerprintReference.read(InputStream)
and SubtitleTimeline.parse(Reader), with additional rejection of malformed
subtitle blocks rather than allowing partially discarded captions.

When changing an Android reader's accepted format, update these validators and
both sets of tests together.  See docs/package-validation.md.
"""

from __future__ import annotations

import math
import re
import struct
import zlib
from pathlib import Path


class PackageValidationError(ValueError):
    """The supplied asset cannot be safely published to an Android client."""


CSREF_MAX_BYTES = 40_000_024  # FingerprintReference.java stream size limit
CSREF_MAX_RECORDS = 5_000_000
CSREF_MIN_PUBLISHED_RECORDS = 8  # conservative publisher quality floor
SUBTITLE_MAX_CHARS = 4_000_000  # SubtitleTimeline.java
SUBTITLE_MAX_CUES = 50_000


def validate_csref_file(path: Path) -> float:
    """Validate v1 header, CRC and *all* records; return duration in seconds.

    Hashes are stored as unsigned u32 and must be nondecreasing; frames are
    signed i32 and must be nonnegative.  The time bound matches the v1 exporter
    (11025 Hz / 256 hop, 150-frame tolerance).  No records are reordered or
    silently repaired: clients must see precisely the bytes the server accepts.
    """
    path = Path(path)
    size = path.stat().st_size
    if size < 24 or size > CSREF_MAX_BYTES:
        raise PackageValidationError("Fingerprint size exceeds Android v1 reader limits")
    data = path.read_bytes()
    try:
        magic, version, duration, count = struct.unpack_from("<4sIdI", data)
    except struct.error as exc:
        raise PackageValidationError("Truncated .csref header") from exc
    if magic != b"CSRF" or version != 1:
        raise PackageValidationError("Not a supported .csref v1 fingerprint")
    if not math.isfinite(duration) or not (0 < duration < 12 * 3600):
        raise PackageValidationError("Invalid fingerprint duration")
    if not (CSREF_MIN_PUBLISHED_RECORDS <= count <= CSREF_MAX_RECORDS):
        raise PackageValidationError("Invalid fingerprint record count")
    if size != 24 + count * 8:
        raise PackageValidationError("Fingerprint header/record size mismatch")
    crc = struct.unpack_from("<I", data, size - 4)[0]
    if zlib.crc32(data[:-4]) & 0xFFFFFFFF != crc:
        raise PackageValidationError("Fingerprint checksum invalid")
    previous_hash = -1
    max_frame = duration * 11025 / 256 + 150
    for hash_value, frame in struct.iter_unpack("<Ii", memoryview(data)[20:-4]):
        if hash_value < previous_hash:
            raise PackageValidationError("Fingerprint hashes not in unsigned ascending order")
        if frame < 0 or frame > max_frame:
            raise PackageValidationError("Fingerprint timestamp outside reference duration")
        previous_hash = hash_value
    return duration


# Matches Android SubtitleTimeline.java's TIME_LINE / TIME patterns.
_TIME_LINE = re.compile(
    r"^\s*((?:\d{1,3}:)?\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*"
    r"((?:\d{1,3}:)?\d{2}:\d{2}[,.]\d{1,3})(?:\s+.*)?$"
)
_TIME = re.compile(r"^(?:(\d{1,3}):)?(\d{2}):(\d{2})[,.](\d{1,3})$")
_MARKUP = re.compile(r"</?(?:i|b|u|font)(?:\s+[^>]*)?>", re.I)
_BLOCK_SPLIT = re.compile(r"\n[ \t]*\n")


def _milliseconds(token: str) -> int:
    match = _TIME.fullmatch(token)
    if match is None:
        raise PackageValidationError("Invalid subtitle timecode")
    hours = int(match.group(1) or 0)
    minutes, seconds = int(match.group(2)), int(match.group(3))
    if minutes >= 60 or seconds >= 60:
        raise PackageValidationError("Subtitle timecode minutes/seconds out of range")
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + int(match.group(4).ljust(3, "0"))


def validate_sdh_file(path: Path) -> int:
    """Verify a UTF-8 SRT or WebVTT contains only well-formed, displayable cues.

    The Android parser skips unrecognised or malformed blocks.  For publishing,
    reject such blocks instead of silently dropping accessibility text.  Allow
    WebVTT headers, NOTE, STYLE and REGION blocks, which Android also skips.
    Overlap and non-consecutive cue IDs are deliberately permitted.
    """
    path = Path(path)
    if path.suffix.lower() not in (".srt", ".vtt"):
        raise PackageValidationError("Expected .srt or .vtt subtitles")
    try:
        content = path.read_text(encoding="utf-8-sig")
    except UnicodeError as exc:
        raise PackageValidationError("Subtitles must be UTF-8") from exc
    if len(content) > SUBTITLE_MAX_CHARS:
        raise PackageValidationError("Subtitle file exceeds Android character limit")
    content = content.replace("\ufeff", "").replace("\r\n", "\n").replace("\r", "\n")
    cue_count = 0
    for block in _BLOCK_SPLIT.split(content):
        if not block.strip():
            continue
        lines = block.split("\n")
        first = lines[0].strip()
        if path.suffix.lower() == ".vtt":
            # WebVTT metadata, cues and notes are separated by blank lines.
            if (
                first.startswith("WEBVTT")
                or first == "STYLE"
                or first == "REGION"
                or first.startswith("NOTE")
            ):
                continue
        timeline = -1
        match = None
        for index, line in enumerate(lines):
            candidate = _TIME_LINE.fullmatch(line)
            if candidate is not None:
                timeline, match = index, candidate
                break
        if timeline < 0 or match is None:
            raise PackageValidationError("Unrecognised or invalid subtitle cue block")
        if timeline >= len(lines) - 1:
            raise PackageValidationError("Subtitle cue is missing its text")
        start = _milliseconds(match.group(1))
        end = _milliseconds(match.group(2))
        if end <= start:
            raise PackageValidationError("Subtitle cue ends before or at its start")
        # Do not strip SDH labels, dialogue dashes or other semantic content.
        # The Android reader strips common formatting tags and decodes these
        # entities before determining whether any displayable text remains.
        body = []
        for line in lines[timeline + 1 :]:
            cleaned = (
                _MARKUP.sub("", line)
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", '"')
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .strip()
            )
            if cleaned:
                body.append(cleaned)
        if not body:
            raise PackageValidationError("Subtitle cue has no displayable text")
        cue_count += 1
        if cue_count > SUBTITLE_MAX_CUES:
            raise PackageValidationError("Subtitle file exceeds Android cue-count limit")
    if cue_count == 0:
        raise PackageValidationError("No valid subtitle cues found")
    return cue_count
