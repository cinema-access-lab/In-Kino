# SPDX-License-Identifier: Apache-2.0
import hashlib
import importlib
import io
import os
import sys
import wave
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "shared"))
sys.path.insert(0, str(ROOT / "server"))

os.environ.setdefault("INKINO_ADMIN_TOKEN", "local-test-private-token-1234")


def make_wav(seconds=15):
    sr = 22050
    rng = np.random.default_rng(712)
    # Some random pitched impulses make a characteristic acoustic fingerprint.
    frames = np.arange(sr * seconds)
    wave_data = (
        0.2 * np.sin(2 * np.pi * (380 + 90 * np.sin(frames / (sr * 1.7))) * frames / sr)
        + 0.08 * np.sin(2 * np.pi * 810 * frames / sr)
        + 0.03 * rng.standard_normal(len(frames))
    )
    v = (np.clip(wave_data, -0.8, 0.8) * 32767).astype("<i2")
    stream = io.BytesIO()
    with wave.open(stream, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(v.tobytes())
    return stream.getvalue()


def test_server_local_online_and_privacy(tmp_path, monkeypatch):
    monkeypatch.setenv("INKINO_DATA_DIR", str(tmp_path / "data"))
    # server module initializes data/DB on import
    sys.modules.pop("app", None)
    app = importlib.import_module("app")
    c = TestClient(app.app)
    auth = {"Authorization": "Bearer local-test-private-token-1234"}
    wav = make_wav()
    srt = b"1\n00:00:01,000 --> 00:00:03,000\n[Sound of footsteps]\n"
    response = c.post(
        "/api/admin/ingestions",
        headers=auth,
        data={
            "film_title": "Sample Film",
            "version_label": "Theatrical 24",
            "language": "de",
            "fps": "24",
            "mode": "online",
        },
        files={
            "reference_audio": ("reference.wav", wav, "audio/wav"),
            "sdh": ("sdh.srt", srt, "text/plain"),
        },
    )
    assert response.status_code == 200, response.text
    online = response.json()
    assert online["film_id"].startswith("film_")
    assert online["version_id"].startswith("ver_")
    assert online["state"] == "draft"
    assert online["reference_audio_persisted"] is False
    assert c.get("/api/catalog").json()["films"] == []
    assert all(not f.name.endswith(".wav") for f in (tmp_path / "data").rglob("*") if f.is_file())
    assert len(list((tmp_path / "data" / "assets").iterdir())) == 2
    assert (
        c.post("/api/admin/versions/" + online["version_id"] + "/publish", headers=auth).status_code
        == 200
    )
    published = c.get("/api/catalog").json()
    assert len(published["films"]) == 1
    version = published["films"][0]["versions"][0]
    assets = version["assets"]
    assert {a["kind"] for a in assets} == {"fingerprint", "sdh"}
    for a in assets:
        res = c.get(a["url"])
        assert res.status_code == 200
        assert hashlib.sha256(res.content).hexdigest() == a["sha256"]
        assert len(res.content) == a["bytes"]
    fp = next(a for a in assets if a["kind"] == "fingerprint")
    reference_bytes = c.get(fp["url"]).content
    # The server and desktop path run the exact same algorithm and exporter.
    from export_android import convert
    from fingerprint_lab import fingerprint_wav, write_fingerprint

    sample_wav = tmp_path / "same_reference.wav"
    sample_wav.write_bytes(wav)
    sample_fp = tmp_path / "local.csfp"
    sample_ref = tmp_path / "local.csref"
    write_fingerprint(str(sample_fp), fingerprint_wav(str(sample_wav)))
    convert(sample_fp, sample_ref)
    assert sample_ref.read_bytes() == reference_bytes
    # The local publisher uploads a .csref created on Windows, no original soundtrack attached.
    denied = c.post(
        "/api/admin/ingestions",
        headers=auth,
        data={
            "film_title": "Private Film",
            "version_label": "24 fps",
            "language": "de",
            "fps": "24",
            "mode": "local",
        },
        files={
            "reference_audio": ("reference.wav", wav, "audio/wav"),
            "fingerprint": ("test.csref", reference_bytes, "application/octet-stream"),
            "sdh": ("sdh.srt", srt, "text/plain"),
        },
    )
    assert denied.status_code == 422
    local = c.post(
        "/api/admin/ingestions",
        headers=auth,
        data={
            "film_title": "Sample Film",
            "film_id": online["film_id"],
            "version_label": "Alternate cut",
            "language": "de",
            "fps": "25",
            "mode": "local",
        },
        files={
            "fingerprint": ("test.csref", reference_bytes, "application/octet-stream"),
            "sdh": ("sdh.srt", srt, "text/plain"),
        },
    )
    assert local.status_code == 200, local.text
    assert local.json()["film_id"] == online["film_id"]
    assert local.json()["version_id"] != online["version_id"]
    assert (
        c.get("/api/admin/films").status_code == 403 or c.get("/api/admin/films").status_code == 401
    )
    assert c.get("/api/assets/unknown/download").status_code == 404
    assert (
        c.post(
            "/api/admin/versions/" + online["version_id"] + "/withdraw", headers=auth
        ).status_code
        == 200
    )
    assert c.get(fp["url"]).status_code == 404
    assert c.get("/api/catalog").json()["films"] == []


def test_reject_bad_fingerprint_and_audio(tmp_path, monkeypatch):
    monkeypatch.setenv("INKINO_DATA_DIR", str(tmp_path / "data"))
    sys.modules.pop("app", None)
    app = importlib.import_module("app")
    client = TestClient(app.app)
    response = client.post(
        "/api/admin/ingestions",
        headers={"Authorization": "Bearer local-test-private-token-1234"},
        data={
            "film_title": "Broken",
            "version_label": "24fps",
            "language": "de",
            "fps": "24",
            "mode": "local",
        },
        files={
            "fingerprint": ("corrupt.csref", b"CSRF-invalid", "application/octet-stream"),
            "sdh": ("sdh.srt", b"1\n00:00:01,000 --> 00:00:02,000\n[applause]", "text/plain"),
        },
    )
    assert response.status_code == 422
    assert not list((tmp_path / "data" / "assets").iterdir())


def test_local_and_server_fingerprint_bytes_identical(tmp_path):
    from export_android import convert
    from fingerprint_lab import fingerprint_wav, write_fingerprint

    wav_file = tmp_path / "reference.wav"
    wav_file.write_bytes(make_wav())
    csfp = tmp_path / "reference.csfp"
    csref = tmp_path / "reference.csref"
    write_fingerprint(str(csfp), fingerprint_wav(str(wav_file)))
    result = convert(csfp, csref)
    assert result["landmarks"] > 8
    assert csref.read_bytes()[:4] == b"CSRF"
    assert b"RIFF" not in csref.read_bytes()[:1024]
    # local publisher must not include film source in HTTP uploads
    sys.path.insert(0, str(ROOT / "desktop-publisher"))
    import publish

    assert publish.endpoint("http://localhost:8765") == "http://localhost:8765"
    import pytest

    with pytest.raises(publish.PublisherError):
        publish.endpoint("http://my-public-server.com:8765")


def test_publisher_transmits_only_derived_assets(tmp_path, monkeypatch):
    wav_file = tmp_path / "reference.wav"
    wav_file.write_bytes(make_wav())
    srt_file = tmp_path / "sdh.srt"
    srt_file.write_text("1\n00:00:01,000 --> 00:00:02,000\n[wind]\n")
    sys.path.insert(0, str(ROOT / "desktop-publisher"))
    import publish

    posted = {}

    def mockpost(url, token, timeout, fields=None, files=None):
        assert url.endswith("/api/admin/ingestions")
        posted["names"] = set(files)
        posted["upload"] = files["fingerprint"][1]
        posted["fields"] = fields
        return 200, '{"film_id": "film_test", "version_id": "ver_test", "state": "draft"}'

    monkeypatch.setattr(publish, "post", mockpost)
    output = publish.upload(
        "http://localhost:8765", "dummy", "Example", "Theatrical", 24, "de", wav_file, sdh=srt_file
    )
    assert output["state"] == "draft"
    assert posted["names"] == {"fingerprint", "sdh"}
    assert posted["upload"].startswith(b"CSRF")
    assert b"reference.wav" not in repr(posted["fields"]).encode()
    assert "reference_audio" not in posted["names"]


def test_publisher_multipart_encoding_is_accepted_by_server(tmp_path, monkeypatch):
    """The standard-library multipart encoder must produce uploads the server accepts."""
    sys.path.insert(0, str(ROOT / "desktop-publisher"))
    import publish

    from synthetic_media import SUBTITLES_SRT, fingerprint_csref

    monkeypatch.setenv("INKINO_DATA_DIR", str(tmp_path / "data"))
    sys.modules.pop("app", None)
    app = importlib.import_module("app")
    client = TestClient(app.app)
    body, content_type = publish.encode_multipart(
        {
            "mode": "local",
            "film_title": "Grüße aus dem Kino",
            "version_label": "Theatrical",
            "fps": "24",
            "language": "de",
            "sdh_language": "de",
        },
        {
            "fingerprint": ("reference.csref", fingerprint_csref()),
            "sdh": ('untertitel "final".srt', SUBTITLES_SRT),
        },
    )
    response = client.post(
        "/api/admin/ingestions",
        content=body,
        headers={
            "Authorization": "Bearer local-test-private-token-1234",
            "Content-Type": content_type,
        },
    )
    assert response.status_code == 200, response.text
    assert response.json()["state"] == "draft"
