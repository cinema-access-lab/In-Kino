# SPDX-License-Identifier: Apache-2.0
"""The static catalogue must look exactly like the server's public API to the app."""

import hashlib
import importlib
import json
import os
import sys
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "shared"))
sys.path.insert(0, str(ROOT / "server"))
os.environ.setdefault("INKINO_ADMIN_TOKEN", "local-test-private-token-1234")

from static_catalog import Asset, Film, Version, write_static_catalog  # noqa: E402
from synthetic_media import SUBTITLES_SRT, fingerprint_csref  # noqa: E402

AUTH = {"Authorization": "Bearer local-test-private-token-1234"}


def sample_film(folder):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "film.csref").write_bytes(fingerprint_csref())
    (folder / "sdh.srt").write_bytes(SUBTITLES_SRT)
    return Film(
        key="sample",
        title="Sample Film",
        versions=[
            Version(
                key="v1",
                label="Theatrical 24",
                fps=24.0,
                language="en",
                duration_seconds=30.0,
                assets=[
                    Asset("fingerprint", folder / "film.csref", "und"),
                    Asset("sdh", folder / "sdh.srt", "en"),
                ],
            )
        ],
    )


def keys(obj):
    """Nested key structure of the catalogue, ignoring values."""
    if isinstance(obj, dict):
        return {k: keys(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [keys(obj[0])] if obj else []
    return type(obj).__name__


def test_static_catalog_matches_server_shape(tmp_path, monkeypatch):
    out = tmp_path / "site"
    static = write_static_catalog(out, [sample_film(tmp_path / "media")])
    assert json.loads((out / "api" / "catalog").read_text()) == static

    monkeypatch.setenv("INKINO_DATA_DIR", str(tmp_path / "data"))
    sys.modules.pop("app", None)
    app = importlib.import_module("app")
    client = TestClient(app.app)
    response = client.post(
        "/api/admin/ingestions",
        headers=AUTH,
        data={
            "film_title": "Sample Film",
            "version_label": "Theatrical 24",
            "language": "en",
            "fps": "24",
            "mode": "local",
        },
        files={
            "fingerprint": ("f.csref", fingerprint_csref()),
            "sdh": ("sdh.srt", SUBTITLES_SRT),
        },
    )
    assert response.status_code == 200, response.text
    version_id = response.json()["version_id"]
    assert client.post(f"/api/admin/versions/{version_id}/publish", headers=AUTH).status_code == 200
    served = client.get("/api/catalog").json()
    assert keys(static) == keys(served)


def test_static_assets_are_verifiable_and_ids_stable(tmp_path):
    media = tmp_path / "media"
    first = write_static_catalog(tmp_path / "a", [sample_film(media)])
    second = write_static_catalog(tmp_path / "b", [sample_film(media)])
    assert first == second
    version = first["films"][0]["versions"][0]
    for asset in version["assets"]:
        assert asset["url"] == f"/api/assets/{asset['id']}/download"
        data = (tmp_path / "a" / asset["url"].lstrip("/")).read_bytes()
        assert len(data) == asset["bytes"]
        assert hashlib.sha256(data).hexdigest() == asset["sha256"]
    details = json.loads((tmp_path / "a" / "api" / "versions" / version["id"]).read_text())
    assert details == {"schema": "in-kino.package.v1", "version": version}
