# SPDX-License-Identifier: Apache-2.0
"""Fail-closed publication tests: both upload and draft->publish must validate."""

import importlib
import os
import struct
import sys
import zlib
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from synthetic_media import fingerprint_csref

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "shared"))
sys.path.insert(0, str(ROOT / "server"))
os.environ.setdefault("INKINO_ADMIN_TOKEN", "local-test-private-token-1234")

AUTH = {"Authorization": "Bearer local-test-private-token-1234"}
VALID_SRT = (
    b"1\n00:00:01,000 --> 00:00:03,000\n[FOOTSTEPS]\n\n"
    b"2\n00:00:02,500 --> 00:00:04,000\n<i>Come here.</i>\n"
)
VALID_VTT = b"WEBVTT\n\nNOTE a comment\n\nstart\n00:01.200 --> 00:03.700 align:start\n[APPLAUSE]\n"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("INKINO_DATA_DIR", str(tmp_path / "server-data"))
    sys.modules.pop("app", None)
    module = importlib.import_module("app")
    return TestClient(module.app), module


def csref():
    return fingerprint_csref()


def corrupt_record(mutator):
    data = bytearray(csref())
    mutator(data)
    struct.pack_into("<I", data, len(data) - 4, zlib.crc32(data[:-4]) & 0xFFFFFFFF)
    return bytes(data)


def create_draft(client, fingerprint=None, sdh=VALID_SRT, sdh_name="sdh.srt"):
    files = {
        "fingerprint": (
            "reference.csref",
            csref() if fingerprint is None else fingerprint,
            "application/octet-stream",
        )
    }
    if sdh is not None:
        files["sdh"] = (sdh_name, sdh, "text/plain")
    return client.post(
        "/api/admin/ingestions",
        headers=AUTH,
        data={
            "film_title": "Validation Film",
            "version_label": "24 fps theatrical",
            "language": "en",
            "fps": "24",
            "mode": "local",
        },
        files=files,
    )


def test_accept_srt_overlaps_and_webvtt(client):
    c, _ = client
    for data, name in ((VALID_SRT, "subs.srt"), (VALID_VTT, "subs.vtt")):
        response = create_draft(c, sdh=data, sdh_name=name)
        assert response.status_code == 200, response.text
        vid = response.json()["version_id"]
        assert c.post(f"/api/admin/versions/{vid}/publish", headers=AUTH).status_code == 200
    assert len(c.get("/api/catalog").json()["films"]) == 2


@pytest.mark.parametrize(
    "modify",
    [
        # Set first hash to the highest possible unsigned value, breaking ordering.
        lambda b: struct.pack_into("<I", b, 20, 0xFFFFFFFF),
        lambda b: struct.pack_into("<i", b, 24, -1),
        lambda b: struct.pack_into("<i", b, 24, 1_000_000),
        lambda b: struct.pack_into("<d", b, 8, float("nan")),
        lambda b: struct.pack_into("<I", b, 16, 5_000_001),
    ],
)
def test_reject_rechecksummed_invalid_fingerprints(client, modify):
    c, app = client
    response = create_draft(c, fingerprint=corrupt_record(modify))
    assert response.status_code == 422, response.text
    assert c.get("/api/catalog").json()["films"] == []
    assert not list(app.FILES.iterdir())


@pytest.mark.parametrize(
    "bad",
    [
        b"not a subtitle --> nope",
        b"1\n00:99:01,000 --> 00:99:02,000\n[WIND]",
        b"1\n00:00:03,000 --> 00:00:01,000\n[WIND]",
        b"1\n00:00:01,000 --> 00:00:03,000\n<i></i>",
        b"1\n00:00:01,000 --> 00:00:03,000\nvalid\n\n2\nnot --> a time\nmissing",
        b"1\n00:00:01,000 --> 00:00:03,000",
        b"\xff\xfe\x00\x01",
    ],
)
def test_reject_malformed_subtitle_blocks(client, bad):
    c, app = client
    response = create_draft(c, sdh=bad)
    assert response.status_code == 422, response.text
    assert not list(app.FILES.iterdir())
    assert c.get("/api/catalog").json()["films"] == []


def test_publish_revalidates_legacy_draft_and_preserves_draft_state(client):
    c, app = client
    result = create_draft(c)
    assert result.status_code == 200, result.text
    vid = result.json()["version_id"]
    with app.db() as conn:
        fp_row = conn.execute(
            "SELECT * FROM assets WHERE version_id=? AND kind=?", (vid, "fingerprint")
        ).fetchone()
    fp_path = app.FILES / (fp_row["id"] + ".csref")
    invalid = corrupt_record(lambda b: struct.pack_into("<I", b, 20, 0xFFFFFFFF))
    fp_path.write_bytes(invalid)
    # Simulate an older draft, imported with valid stored checksum but invalid
    # content. Publish must validate semantics, not only the metadata digest.
    with app.db() as conn:
        conn.execute(
            "UPDATE assets SET sha256=?,size=? WHERE id=?",
            (__import__("hashlib").sha256(invalid).hexdigest(), len(invalid), fp_row["id"]),
        )
    response = c.post(f"/api/admin/versions/{vid}/publish", headers=AUTH)
    assert response.status_code == 422, response.text
    assert c.get("/api/catalog").json()["films"] == []
    with app.db() as conn:
        assert (
            conn.execute("SELECT state FROM versions WHERE id=?", (vid,)).fetchone()["state"]
            == "draft"
        )


def test_publish_rejects_tampered_or_missing_asset(client):
    c, app = client
    for operation in ("tamper", "delete"):
        result = create_draft(c)
        assert result.status_code == 200, result.text
        vid = result.json()["version_id"]
        with app.db() as conn:
            row = conn.execute(
                "SELECT * FROM assets WHERE version_id=? AND kind=?", (vid, "sdh")
            ).fetchone()
        sdh_path = app.FILES / (row["id"] + ".srt")
        if operation == "tamper":
            sdh_path.write_bytes(b"1\n00:00:01,000 --> 00:00:02,000\nChanged\n")
        else:
            sdh_path.unlink()
        response = c.post(f"/api/admin/versions/{vid}/publish", headers=AUTH)
        assert response.status_code == 422, response.text
        assert c.get("/api/catalog").json()["films"] == []


def test_publish_rejects_legacy_draft_with_rechecksummed_invalid_sdh(client):
    c, app = client
    result = create_draft(c)
    assert result.status_code == 200, result.text
    vid = result.json()["version_id"]
    with app.db() as conn:
        row = conn.execute(
            "SELECT * FROM assets WHERE version_id=? AND kind=?", (vid, "sdh")
        ).fetchone()
    path = app.FILES / (row["id"] + ".srt")
    invalid = b"1\n00:00:10,000 --> 00:00:03,000\nBad reversed cue\n"
    path.write_bytes(invalid)
    with app.db() as conn:
        conn.execute(
            "UPDATE assets SET sha256=?,size=? WHERE id=?",
            (__import__("hashlib").sha256(invalid).hexdigest(), len(invalid), row["id"]),
        )
    response = c.post(f"/api/admin/versions/{vid}/publish", headers=AUTH)
    assert response.status_code == 422, response.text
    assert c.get("/api/catalog").json()["films"] == []
