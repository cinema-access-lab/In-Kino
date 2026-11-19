# SPDX-License-Identifier: Apache-2.0
"""In-Kino publishing and catalogue API.

Prototype: one shared publisher token, no per-user accounts. See SECURITY.md before
exposing it beyond a trusted test network.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import shutil
import sqlite3
import sys
import tempfile
import uuid
import wave
from contextlib import contextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "shared"))
# The shared/ modules are plain scripts, not an installed package.
from export_android import convert as to_csref  # noqa: E402
from fingerprint_lab import fingerprint_wav, write_fingerprint  # noqa: E402
from package_validation import (  # noqa: E402
    PackageValidationError,
    validate_csref_file,
    validate_sdh_file,
)

DATA = Path(os.getenv("INKINO_DATA_DIR", str(ROOT / "server-data"))).resolve()
FILES = DATA / "assets"
DB = DATA / "catalog.sqlite3"
ADMIN_TOKEN = os.getenv("INKINO_ADMIN_TOKEN", "")
if not ADMIN_TOKEN or len(ADMIN_TOKEN) < 16:
    raise RuntimeError("Set INKINO_ADMIN_TOKEN (at least 16 characters). See README.")
MAX_REFERENCE = 4 * 1024 * 1024 * 1024
MAX_AD = 600 * 1024 * 1024
MAX_FP = 40_000_024  # Android FingerprintReference v1 reader cap
MAX_SDH = 12 * 1024 * 1024
APP_VERSION = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
DATA.mkdir(parents=True, exist_ok=True)
FILES.mkdir(parents=True, exist_ok=True)
app = FastAPI(title="In-Kino Publishing API", version=APP_VERSION, docs_url=None, redoc_url=None)
auth_scheme = HTTPBearer(auto_error=False)


@contextmanager
def db():
    conn = sqlite3.connect(str(DB))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


with db() as c:
    c.executescript("""
    CREATE TABLE IF NOT EXISTS films(
        id         TEXT PRIMARY KEY,
        title      TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
    CREATE TABLE IF NOT EXISTS versions(
        id         TEXT PRIMARY KEY,
        film_id    TEXT NOT NULL REFERENCES films(id),
        label      TEXT NOT NULL,
        fps        REAL NOT NULL,
        language   TEXT NOT NULL,
        duration   REAL NOT NULL,
        state      TEXT NOT NULL CHECK(state IN ('draft','published','withdrawn')),
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
    CREATE TABLE IF NOT EXISTS assets(
        id         TEXT PRIMARY KEY,
        version_id TEXT NOT NULL REFERENCES versions(id),
        kind       TEXT NOT NULL CHECK(kind IN ('fingerprint','ad','sdh')),
        language   TEXT NOT NULL,
        filename   TEXT NOT NULL,
        sha256     TEXT NOT NULL,
        size       INTEGER NOT NULL,
        revision   INTEGER NOT NULL DEFAULT 1
    );
    """)


def check_token(token: str):
    if not hmac.compare_digest(token, ADMIN_TOKEN):
        raise HTTPException(401, "Invalid publisher credentials")


def auth_header(credentials: HTTPAuthorizationCredentials | None = Depends(auth_scheme)):
    check_token(credentials.credentials if credentials else "")


def safe_text(v: str, lim: int = 160) -> str:
    v = " ".join(v.strip().split())
    if not v or len(v) > lim or any(ord(ch) < 32 for ch in v):
        raise HTTPException(422, "Invalid required text")
    return v


def validate_csref(p: Path):
    try:
        return validate_csref_file(p)
    except PackageValidationError as exc:
        raise HTTPException(422, f"Invalid fingerprint: {exc}") from exc


async def save_upload(upload: UploadFile | None, dest: Path, limit: int, kind: str):
    if upload is None:
        return None
    size = 0
    sha = hashlib.sha256()
    try:
        with dest.open("xb") as out:
            while True:
                chunk = await upload.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > limit:
                    raise HTTPException(413, f"{kind} exceeds size limit")
                out.write(chunk)
                sha.update(chunk)
        if not size:
            raise HTTPException(422, f"{kind} is empty")
        return (size, sha.hexdigest())
    except BaseException:
        dest.unlink(missing_ok=True)
        raise
    finally:
        await upload.close()


def validate_audio(p: Path, filename: str):
    extension = Path(filename).suffix.lower()
    if extension == ".wav":
        try:
            with wave.open(str(p), "rb") as wf:
                if wf.getnframes() < 1 or wf.getframerate() < 8000:
                    raise ValueError("Empty or invalid audio")
        except Exception as ex:
            raise HTTPException(422, f"Invalid PCM WAV: {ex}") from ex
    elif extension == ".m4a":
        with p.open("rb") as f:
            header = f.read(32)
        if b"ftyp" not in header[4:16]:
            raise HTTPException(422, "Invalid M4A container")
    else:
        raise HTTPException(422, "Expected .wav or .m4a audio")


def validate_sdh(p: Path):
    try:
        return validate_sdh_file(p)
    except PackageValidationError as exc:
        raise HTTPException(422, f"Invalid subtitles: {exc}") from exc


def stream_sha256(p: Path) -> str:
    digest = hashlib.sha256()
    with p.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_suffix(kind: str, filename: str):
    ext = Path(filename).suffix.lower()
    allowed = {"fingerprint": {".csref"}, "ad": {".wav", ".m4a"}, "sdh": {".srt", ".vtt"}}
    if ext not in allowed[kind]:
        raise HTTPException(422, f"Unsupported {kind} extension: {ext}")
    return ext


def uid(prefix):
    return prefix + "_" + str(uuid.uuid4())


def catalog_data():
    with db() as c:
        films = c.execute(
            "SELECT f.* FROM films f"
            " WHERE EXISTS (SELECT 1 FROM versions v WHERE v.film_id=f.id AND v.state='published')"
            " ORDER BY f.title COLLATE NOCASE"
        ).fetchall()
        result = []
        for film in films:
            versions = c.execute(
                "SELECT * FROM versions WHERE film_id=? AND state='published'"
                " ORDER BY created_at DESC, id",
                (film["id"],),
            ).fetchall()
            result.append(
                dict(
                    id=film["id"],
                    title=film["title"],
                    versions=[make_version(c, v) for v in versions],
                )
            )
        return result


def make_version(c, v):
    assets = c.execute(
        "SELECT * FROM assets WHERE version_id=? ORDER BY kind,language", (v["id"],)
    ).fetchall()
    return dict(
        id=v["id"],
        film_id=v["film_id"],
        label=v["label"],
        fps=v["fps"],
        language=v["language"],
        duration_seconds=v["duration"],
        state=v["state"],
        assets=[
            dict(
                id=a["id"],
                kind=a["kind"],
                language=a["language"],
                sha256=a["sha256"],
                bytes=a["size"],
                revision=a["revision"],
                filename=a["filename"],
                url=f"/api/assets/{a['id']}/download",
            )
            for a in assets
        ],
    )


@app.get("/health")
def health():
    return {"status": "ok", "version": APP_VERSION}


@app.get("/api/catalog")
def catalog():
    return {"schema": "in-kino.catalog.v1", "films": catalog_data()}


@app.get("/api/versions/{version_id}")
def version_details(version_id: str):
    with db() as c:
        v = c.execute(
            "SELECT * FROM versions WHERE id=? AND state=?", (version_id, "published")
        ).fetchone()
        if not v:
            raise HTTPException(404, "Not published")
        return {"schema": "in-kino.package.v1", "version": make_version(c, v)}


@app.get("/api/assets/{asset_id}/download")
def download(asset_id: str):
    with db() as c:
        a = c.execute(
            "SELECT a.*, v.state FROM assets a JOIN versions v ON v.id=a.version_id WHERE a.id=?",
            (asset_id,),
        ).fetchone()
        if not a or a["state"] != "published":
            raise HTTPException(404, "Unavailable")
        p = FILES / (a["id"] + Path(a["filename"]).suffix.lower())
        if not p.is_file():
            raise HTTPException(503, "Stored file missing")
        return FileResponse(
            p,
            filename=a["filename"],
            headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "public, max-age=300"},
        )


@app.post("/api/admin/ingestions", dependencies=[Depends(auth_header)])
async def ingest(
    film_title: str = Form(...),
    version_label: str = Form(...),
    fps: float = Form(...),
    language: str = Form("de"),
    mode: str = Form(...),
    film_id: str | None = Form(None),
    fingerprint: UploadFile | None = File(None),
    reference_audio: UploadFile | None = File(None),
    ad: UploadFile | None = File(None),
    sdh: UploadFile | None = File(None),
    ad_language: str = Form("de"),
    sdh_language: str = Form("de"),
):
    if mode not in ("local", "online"):
        raise HTTPException(422, "mode must be local or online")
    if not (12 <= fps <= 120):
        raise HTTPException(422, "Unusual frame rate")
    if (mode == "local" and (fingerprint is None or reference_audio is not None)) or (
        mode == "online" and (reference_audio is None or fingerprint is not None)
    ):
        raise HTTPException(
            422, "Local ingestion requires fingerprint only; online requires reference audio only"
        )
    if not (ad or sdh):
        raise HTTPException(422, "At least one AD or SDH asset required")
    film_title = safe_text(film_title)
    version_label = safe_text(version_label)
    language = safe_text(language, 20)
    ad_language = safe_text(ad_language, 20)
    sdh_language = safe_text(sdh_language, 20)
    if film_id:
        with db() as c:
            row = c.execute("SELECT id,title FROM films WHERE id=?", (film_id,)).fetchone()
            if not row:
                raise HTTPException(404, "Existing film ID not found")
            if row["title"] != film_title:
                raise HTTPException(409, "Film title must match existing film")
    else:
        film_id = uid("film")
    vid = uid("ver")
    placed = []
    with tempfile.TemporaryDirectory(prefix="csl-ingest-") as temp:
        temp = Path(temp)
        fp = temp / "fingerprint.csref"
        try:
            if mode == "local":
                if file_suffix("fingerprint", fingerprint.filename or "") != ".csref":
                    raise HTTPException(422, "Use .csref")
                await save_upload(fingerprint, fp, MAX_FP, "fingerprint")
            else:
                if not (reference_audio.filename or "").lower().endswith(".wav"):
                    raise HTTPException(422, "Online reference input is PCM WAV in this prototype")
                source = temp / "reference.wav"
                await save_upload(reference_audio, source, MAX_REFERENCE, "reference WAV")
                validate_audio(source, "reference.wav")
                # Same Python landmark engine and .csref converter as local Windows publisher.
                raw = temp / "fingerprint.csfp"
                fingerprint = await run_in_threadpool(fingerprint_wav, str(source))
                await run_in_threadpool(write_fingerprint, str(raw), fingerprint)
                to_csref(raw, fp)
                source.unlink()  # no original reference soundtrack in persistent storage
                raw.unlink()
            duration = validate_csref(fp)
            incoming = [("fingerprint", fp, "fingerprint.csref", language)]
            for kind, upload, lim, lang in (
                ("ad", ad, MAX_AD, ad_language),
                ("sdh", sdh, MAX_SDH, sdh_language),
            ):
                if upload:
                    name = upload.filename or ""
                    ext = file_suffix(kind, name)
                    path = temp / ("asset-" + kind + ext)
                    await save_upload(upload, path, lim, kind)
                    if kind == "ad":
                        validate_audio(path, name)
                    else:
                        validate_sdh(path)
                    incoming.append((kind, path, kind + ext, lang))
            entries = []
            for kind, path, name, lang in incoming:
                aid = uid("asset")
                newname = aid + Path(name).suffix
                target = FILES / newname
                shutil.copyfile(path, target)
                placed.append(target)
                entries.append(
                    (aid, vid, kind, lang, name, stream_sha256(target), target.stat().st_size)
                )
            with db() as c:
                if not c.execute("SELECT 1 FROM films WHERE id=?", (film_id,)).fetchone():
                    c.execute("INSERT INTO films(id,title) VALUES (?,?)", (film_id, film_title))
                c.execute(
                    "INSERT INTO versions(id, film_id, label, fps, language, duration, state)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (vid, film_id, version_label, fps, language, duration, "draft"),
                )
                c.executemany(
                    "INSERT INTO assets(id, version_id, kind, language, filename, sha256, size)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    entries,
                )
            return {
                "film_id": film_id,
                "version_id": vid,
                "state": "draft",
                "duration_seconds": duration,
                "files_uploaded": [e[2] for e in entries],
                "reference_audio_persisted": False,
                "mode": mode,
            }
        except BaseException:
            for p in placed:
                p.unlink(missing_ok=True)
            raise


@app.post("/api/admin/versions/{vid}/publish", dependencies=[Depends(auth_header)])
def publish(vid: str):
    with db() as c:
        row = c.execute("SELECT state FROM versions WHERE id=?", (vid,)).fetchone()
        if not row:
            raise HTTPException(404, "Version missing")
        assets = c.execute("SELECT * FROM assets WHERE version_id=?", (vid,)).fetchall()
        kinds = {a["kind"] for a in assets}
        if "fingerprint" not in kinds or not ("ad" in kinds or "sdh" in kinds):
            raise HTTPException(422, "Incomplete package")
        # Revalidate draft contents at publication: earlier drafts or files
        # altered on disk must never become discoverable in the catalogue.
        # All checks complete before the state transition in this transaction.
        for asset in assets:
            stored = FILES / (asset["id"] + Path(asset["filename"]).suffix.lower())
            if not stored.is_file():
                raise HTTPException(422, "Package asset is missing")
            if stored.stat().st_size != asset["size"] or stream_sha256(stored) != asset["sha256"]:
                raise HTTPException(422, "Package asset integrity mismatch")
            if asset["kind"] == "fingerprint":
                validate_csref(stored)
            elif asset["kind"] == "sdh":
                validate_sdh(stored)
            elif asset["kind"] == "ad":
                validate_audio(stored, asset["filename"])
        c.execute("UPDATE versions SET state='published' WHERE id=?", (vid,))
        return {"version_id": vid, "state": "published"}


@app.post("/api/admin/versions/{vid}/withdraw", dependencies=[Depends(auth_header)])
def withdraw(vid: str):
    with db() as c:
        if c.execute("UPDATE versions SET state='withdrawn' WHERE id=?", (vid,)).rowcount == 0:
            raise HTTPException(404, "Version missing")
        return {"version_id": vid, "state": "withdrawn"}


@app.get("/api/admin/films", dependencies=[Depends(auth_header)])
def admin_films():
    with db() as c:
        return {
            "films": [
                dict(r)
                for r in c.execute("SELECT * FROM films ORDER BY created_at DESC").fetchall()
            ],
            "versions": [
                dict(r)
                for r in c.execute("SELECT * FROM versions ORDER BY created_at DESC").fetchall()
            ],
        }


CSP = (
    "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; "
    "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
)


@app.get("/", response_class=HTMLResponse)
def portal(request: Request):
    html = (HERE / "static" / "index.html").read_text(encoding="utf-8")
    return HTMLResponse(
        html,
        headers={
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
            "Content-Security-Policy": CSP,
        },
    )


@app.get("/ui.css")
def css():
    return FileResponse(HERE / "static" / "ui.css", media_type="text/css")


@app.get("/ui.js")
def js():
    return FileResponse(HERE / "static" / "ui.js", media_type="text/javascript")
