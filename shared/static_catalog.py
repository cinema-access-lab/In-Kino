# SPDX-License-Identifier: Apache-2.0
"""Write a read-only catalogue as plain files, for hosting on any static web server.

The output has exactly the paths and JSON the FastAPI server's public endpoints return:

    api/catalog
    api/versions/<version id>
    api/assets/<asset id>/download
    health

so the Android app can download from, for example, GitHub Pages without running the server.
Publishing (drafts, uploads, withdrawal) still needs the real server.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from package_validation import validate_csref_file, validate_sdh_file

# Fixed namespace so that IDs stay the same across rebuilds of the same content. The app
# recognises downloaded films by version ID.
NAMESPACE = uuid.UUID("6f0b5d1e-6c55-4f43-9a7e-2f0c8f3b8a10")

AD_EXTENSIONS = {".m4a", ".wav"}


@dataclass
class Asset:
    kind: str  # "fingerprint", "ad", or "sdh"
    path: Path
    language: str
    revision: int = 1


@dataclass
class Version:
    key: str  # stable identifier, used to derive the version ID
    label: str
    fps: float
    language: str
    duration_seconds: float
    assets: list[Asset] = field(default_factory=list)


@dataclass
class Film:
    key: str
    title: str
    versions: list[Version] = field(default_factory=list)


def stable_id(prefix: str, *parts: str) -> str:
    return f"{prefix}_{uuid.uuid5(NAMESPACE, '/'.join(parts))}"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _validate(asset: Asset) -> None:
    suffix = asset.path.suffix.lower()
    if asset.kind == "fingerprint":
        if suffix != ".csref":
            raise ValueError(f"{asset.path}: fingerprint must be .csref")
        validate_csref_file(asset.path)
    elif asset.kind == "sdh":
        validate_sdh_file(asset.path)
    elif asset.kind == "ad":
        if suffix not in AD_EXTENSIONS:
            raise ValueError(f"{asset.path}: AD must be .m4a or .wav")
    else:
        raise ValueError(f"Unknown asset kind {asset.kind!r}")


def write_static_catalog(out_dir: Path, films: list[Film]) -> dict:
    """Validate every asset, copy it into place, and write the catalogue JSON files."""
    out_dir = Path(out_dir)
    catalog_films = []
    for film in sorted(films, key=lambda f: f.title.casefold()):
        film_id = stable_id("film", film.key)
        versions = []
        for version in film.versions:
            kinds = [a.kind for a in version.assets]
            if kinds.count("fingerprint") != 1 or not ({"ad", "sdh"} & set(kinds)):
                raise ValueError(f"{film.title}: needs one fingerprint and an AD or SDH track")
            version_id = stable_id("ver", film.key, version.key)
            assets = []
            for asset in sorted(version.assets, key=lambda a: (a.kind, a.language)):
                _validate(asset)
                asset_id = stable_id("asset", film.key, version.key, asset.kind, asset.language)
                target = out_dir / "api" / "assets" / asset_id / "download"
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(asset.path, target)
                assets.append(
                    dict(
                        id=asset_id,
                        kind=asset.kind,
                        language=asset.language,
                        sha256=_sha256(target),
                        bytes=target.stat().st_size,
                        revision=asset.revision,
                        filename=f"{asset.kind}{asset.path.suffix.lower()}",
                        url=f"/api/assets/{asset_id}/download",
                    )
                )
            entry = dict(
                id=version_id,
                film_id=film_id,
                label=version.label,
                fps=version.fps,
                language=version.language,
                duration_seconds=round(version.duration_seconds, 3),
                state="published",
                assets=assets,
            )
            versions.append(entry)
            _write_json(
                out_dir / "api" / "versions" / version_id,
                {"schema": "in-kino.package.v1", "version": entry},
            )
        catalog_films.append(dict(id=film_id, title=film.title, versions=versions))
    catalog = {"schema": "in-kino.catalog.v1", "films": catalog_films}
    _write_json(out_dir / "api" / "catalog", catalog)
    _write_json(out_dir / "health", {"status": "ok", "static": True})
    # Stop GitHub Pages from running Jekyll over the output.
    (out_dir / ".nojekyll").write_text("")
    return catalog


def _write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
