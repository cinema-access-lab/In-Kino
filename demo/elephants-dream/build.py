#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the Elephants Dream demo package and a static test catalogue.

Downloads the Creative Commons sources listed in SOURCES, then:

1. converts the stereo soundtrack master to PCM WAV and fingerprints it (.csref),
2. transcodes JJ Hunt's audio description to AAC (.m4a),
3. checks that the AD track and the soundtrack master share one timeline,
4. validates the subtitles, and
5. writes a static catalogue (see shared/static_catalog.py) plus an attribution page.

Needs ffmpeg and ffprobe on PATH, and numpy and scipy. Run from the repository root:

    python demo/elephants-dream/build.py --out build/demo-site
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

import numpy as np
from scipy.signal import correlate

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "shared"))

from export_android import convert as to_csref  # noqa: E402
from fingerprint_lab import fingerprint_wav, match, write_fingerprint  # noqa: E402
from static_catalog import Asset, Film, Version, write_static_catalog  # noqa: E402

AD_REPO = (
    "https://media.githubusercontent.com/media/OwenEdwards/ElephantsDreamAudioDescription/"
    "290365c169715611baca18295b283c6be8401566/"
)

# Pinned from the reviewed build of 2026-11-19. None would mean "report, don't check".
SOURCES = {
    "master": dict(
        filename="ED-CM-St-16bit.flac",
        urls=[
            "https://media.xiph.org/ED/ED-CM-St-16bit.flac",
            "https://ftp.lysator.liu.se/pub/xiph/media/ED/ED-CM-St-16bit.flac",
        ],
        sha256="fc25f3658365529c599097954fa5342c3e20333cf55648e4cbbcaea95b360f18",
    ),
    "ad": dict(
        filename="Elephants Dream Audio Description.wav",
        urls=[AD_REPO + "Elephants%20Dream%20Audio%20Description.wav"],
        sha256="42cd28e27701e48f9bea4b23eadddc00775c104272cfb5e1b864d4f6198474bf",
    ),
    # Film with the description mixed in. Used only to check alignment, never published.
    "ad_mix": dict(
        filename="Elephants Dream with Audio Description.mov",
        urls=[AD_REPO + "Elephants%20Dream%20with%20Audio%20Description.mov"],
        sha256="2d129699f3ae1744b23ffa93433c7c3160a8cb9d46b7ec4cb2c2e9d9cd2d7483",
    ),
}

SUBTITLES = HERE / "subtitles.en.vtt"

# Excerpts (start, length in seconds) of the mixed film matched against the master fingerprint.
MATCH_EXCERPTS = [(40, 12), (150, 12), (330, 12), (480, 12)]
# Windows with dense description, used to locate the AD track inside the mixed film.
AD_WINDOWS = [(69, 30), (240, 30), (300, 30)]
# The fingerprint matcher resolves positions to about 0.093 s; correlation to 0.25 ms.
MAX_FILM_OFFSET_SECONDS = 0.15
MAX_AD_OFFSET_SECONDS = 0.05


def log(message: str) -> None:
    print(message, flush=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(name: str, cache: Path) -> Path:
    source = SOURCES[name]
    target = cache / source["filename"]
    if not target.exists():
        last_error = None
        for url in source["urls"]:
            for attempt in range(3):
                try:
                    log(f"Downloading {url}")
                    partial = target.with_suffix(target.suffix + ".partial")
                    headers = {"User-Agent": "in-kino-demo"}
                    request = urllib.request.Request(url, headers=headers)
                    with urllib.request.urlopen(request, timeout=120) as resp:
                        with partial.open("wb") as out:
                            shutil.copyfileobj(resp, out, 1 << 20)
                    partial.rename(target)
                    break
                except Exception as exc:  # noqa: BLE001 - retried, then reported
                    last_error = exc
                    log(f"  failed ({exc}), retrying")
                    time.sleep(5 * (attempt + 1))
            if target.exists():
                break
        if not target.exists():
            raise SystemExit(f"Could not download {name}: {last_error}")
    digest = sha256(target)
    log(f"{name}: {target.name}, {target.stat().st_size:,} bytes, sha256 {digest}")
    if source["sha256"] and digest != source["sha256"]:
        raise SystemExit(f"{name}: checksum mismatch (expected {source['sha256']})")
    return target


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return float(out.stdout.strip())


def mono_samples(path: Path, start: float, length: float, rate: int) -> np.ndarray:
    raw = subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-ss",
            str(start),
            "-t",
            str(length),
            "-i",
            str(path),
            "-vn",
            "-ac",
            "1",
            "-ar",
            str(rate),
            "-f",
            "f32le",
            "-",
        ],
        check=True,
        capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32)


def check_film_timeline(reference, mix: Path, scratch: Path) -> list[float]:
    """Match excerpts of the mixed film against the master; returns offsets in seconds."""
    offsets = []
    for start, length in MATCH_EXCERPTS:
        excerpt = scratch / f"excerpt-{start}.wav"
        ffmpeg(
            "-ss",
            str(start),
            "-t",
            str(length),
            "-i",
            str(mix),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "22050",
            "-c:a",
            "pcm_s16le",
            str(excerpt),
        )
        result = match(reference, fingerprint_wav(str(excerpt)))
        if not result["matched"]:
            raise SystemExit(f"Mixed film excerpt at {start}s did not match the master: {result}")
        offset = result["reference_time_at_query_start_seconds"] - start
        log(
            f"  film excerpt at {start:>3}s -> master {start + offset:.3f}s "
            f"(offset {offset:+.3f}s, {result['best_votes']} votes)"
        )
        offsets.append(offset)
    return offsets


def check_ad_timeline(ad: Path, mix: Path) -> list[float]:
    """Cross-correlate the AD track with the mixed film; returns AD offsets in seconds."""
    rate, margin = 4000, 2.0
    offsets = []
    for start, length in AD_WINDOWS:
        voice = mono_samples(ad, start, length, rate)
        mixed = mono_samples(mix, start - margin, length + 2 * margin, rate)
        if voice.std() < 1e-4:
            raise SystemExit(f"AD track is silent around {start}s")
        score = correlate(mixed - mixed.mean(), voice - voice.mean(), mode="valid")
        lag = int(np.argmax(score))
        peak = score[lag] / (np.sqrt(np.sum(voice**2) * np.sum(mixed**2)) + 1e-12)
        offset = lag / rate - margin
        log(f"  AD window at {start:>3}s -> offset {offset:+.3f}s (correlation {peak:.2f})")
        offsets.append(offset)
    return offsets


ATTRIBUTION_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>In-Kino demo catalogue</title>
<style>
  body {{ font: 17px/1.5 system-ui, sans-serif; max-width: 42rem; margin: 2rem auto;
         padding: 0 1rem; background: #091620; color: #f5fafc; }}
  a {{ color: #69e4d0; }} code {{ background: #132532; padding: .1em .3em; border-radius: 4px; }}
  h1 {{ font-size: 1.7rem; }} li {{ margin: .4rem 0; }}
</style>
</head>
<body>
<h1>In-Kino demo catalogue</h1>
<p>This is a read-only test catalogue for the
<a href="https://github.com/cinema-access-lab/In-Kino">In-Kino</a> Android app.
In the app, open <b>Settings</b> and enter this page's address as the catalogue server,
then open <b>Online Catalogue</b>.</p>
<p>Catalogue data: <a href="api/catalog"><code>api/catalog</code></a></p>

<h2>Elephants Dream (2006)</h2>
<ul>
<li><b>Film and soundtrack:</b> &copy; 2006 Blender Foundation / Netherlands Media Art Institute /
<a href="https://orange.blender.org">www.elephantsdream.org</a>, licensed under
<a href="https://creativecommons.org/licenses/by/2.5/">CC BY 2.5</a>. The fingerprint is
derived from the stereo master distributed by <a href="https://media.xiph.org/">Xiph.Org</a>.</li>
<li><b>Audio description:</b> by <a href="http://www.jjhunt.com/">JJ Hunt</a>, from
<a href="https://github.com/OwenEdwards/ElephantsDreamAudioDescription">OwenEdwards/ElephantsDreamAudioDescription</a>,
licensed under <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>.
Transcoded to AAC; otherwise unchanged.</li>
<li><b>Subtitles:</b> English captions from the
<a href="https://github.com/videojs/video.js/tree/v8.21.0/docs/examples/elephantsdream"
>Video.js examples</a>
(&copy; Brightcove, Inc., Apache License 2.0), transcribing the film's dialogue.
They contain dialogue only, not sound descriptions.</li>
</ul>
<p>Built {built} from commit <code>{commit}</code>.</p>
</body>
</html>
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True, help="output folder for the site")
    parser.add_argument("--cache", type=Path, default=HERE / ".cache", help="download cache")
    parser.add_argument("--commit", default="local", help="source commit, shown on the page")
    parser.add_argument(
        "--excerpts",
        type=Path,
        help="also write the fingerprint and film excerpts here, for AppMatcherCheck.java",
    )
    args = parser.parse_args()
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            raise SystemExit(
                f"{tool} was not found. Install FFmpeg (on Windows: winget install Gyan.FFmpeg),"
                " then open a new terminal."
            )
    args.cache.mkdir(parents=True, exist_ok=True)

    master = fetch("master", args.cache)
    ad_source = fetch("ad", args.cache)
    mix = fetch("ad_mix", args.cache)

    with tempfile.TemporaryDirectory(prefix="in-kino-demo-") as tmp:
        scratch = Path(tmp)
        package = scratch / "package"
        package.mkdir()

        log("Fingerprinting the soundtrack master")
        reference_wav = scratch / "reference.wav"
        ffmpeg("-i", str(master), "-vn", "-c:a", "pcm_s16le", str(reference_wav))
        reference = fingerprint_wav(str(reference_wav))
        csfp = scratch / "film.csfp"
        write_fingerprint(str(csfp), reference)
        csref = package / "film.csref"
        to_csref(csfp, csref)
        film_seconds = reference.duration

        log("Transcoding the audio description")
        ad = package / "audio-description.m4a"
        ffmpeg(
            "-i",
            str(ad_source),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "44100",
            "-c:a",
            "aac",
            "-b:a",
            "96k",
            "-movflags",
            "+faststart",
            str(ad),
        )

        durations = {
            "master": round(film_seconds, 3),
            "ad": round(duration(ad_source), 3),
            "ad_mix": round(duration(mix), 3),
        }
        log(f"Durations: {durations}")

        log("Checking that the mixed film follows the master's timeline")
        film_offsets = check_film_timeline(reference, mix, scratch)
        log("Checking that the AD track follows the mixed film's timeline")
        ad_offsets = check_ad_timeline(ad_source, mix)
        # Median, because single fingerprint matches jitter by about one 0.093 s bin.
        worst_film = float(np.median(np.abs(film_offsets)))
        worst_ad = max(abs(o) for o in ad_offsets)
        if worst_film > MAX_FILM_OFFSET_SECONDS or worst_ad > MAX_AD_OFFSET_SECONDS:
            raise SystemExit(
                f"Timelines differ (film median {worst_film:.3f}s, AD {worst_ad:.3f}s). "
                "The AD track would need re-timing before publishing."
            )
        log(f"Alignment OK: film median {worst_film:.3f}s, AD within {worst_ad:.3f}s")

        if args.excerpts:
            args.excerpts.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(csref, args.excerpts / "film.csref")
            for start, length in MATCH_EXCERPTS:
                ffmpeg(
                    "-ss",
                    str(start),
                    "-t",
                    str(length),
                    "-i",
                    str(mix),
                    "-vn",
                    "-ac",
                    "1",
                    "-ar",
                    "11025",
                    "-f",
                    "s16le",
                    str(args.excerpts / f"excerpt-{start}.s16"),
                )

        subtitles = package / "subtitles.en.vtt"
        shutil.copyfile(SUBTITLES, subtitles)

        film = Film(
            key="elephants-dream",
            title="Elephants Dream",
            versions=[
                Version(
                    key="2006-original",
                    label="Original release (2006)",
                    fps=24.0,
                    language="en",
                    duration_seconds=film_seconds,
                    assets=[
                        Asset("fingerprint", csref, "und"),
                        Asset("ad", ad, "en"),
                        Asset("sdh", subtitles, "en"),
                    ],
                )
            ],
        )
        if args.out.exists():
            shutil.rmtree(args.out)
        catalog = write_static_catalog(args.out, [film])

    (args.out / "index.html").write_text(
        ATTRIBUTION_HTML.format(built=time.strftime("%Y-%m-%d"), commit=args.commit[:12]),
        encoding="utf-8",
    )
    report = {
        "durations_seconds": durations,
        "film_offsets_seconds": film_offsets,
        "ad_offsets_seconds": ad_offsets,
        "sources_sha256": {
            name: sha256(args.cache / SOURCES[name]["filename"]) for name in SOURCES
        },
        "assets": catalog["films"][0]["versions"][0]["assets"],
    }
    (args.out / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    log(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
