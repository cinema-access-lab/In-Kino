# SPDX-License-Identifier: Apache-2.0
"""Local-only fingerprint generation, followed by explicit uploads of derived assets.
The reference WAV is never attached to, or sent via, an HTTP request.
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "shared"))
from export_android import convert
from fingerprint_lab import fingerprint_wav, write_fingerprint


class PublisherError(RuntimeError):
    pass


def endpoint(server):
    u = urlparse(server.strip().rstrip("/"))
    if (
        u.scheme not in ("https", "http")
        or not u.netloc
        or u.username
        or u.password
        or u.query
        or u.fragment
        or u.path not in ("", "/")
    ):
        raise PublisherError("Enter a server origin such as https://catalog.example.org")
    if u.scheme == "http" and u.hostname not in ("127.0.0.1", "localhost", "::1"):
        # Local network testing must be explicitly opted into; never silently send token over HTTP.
        raise PublisherError("Use HTTPS, or localhost HTTP for local testing")
    return server.strip().rstrip("/")


def generate(reference_wav: Path, destination: Path):
    reference_wav = Path(reference_wav)
    if not reference_wav.is_file() or reference_wav.suffix.lower() != ".wav":
        raise PublisherError("The reference soundtrack must be a local PCM WAV")
    with tempfile.TemporaryDirectory(prefix="in-kino-local-") as folder:
        csfp = Path(folder) / "temp.csfp"
        write_fingerprint(str(csfp), fingerprint_wav(str(reference_wav)))
        return convert(csfp, destination)


def encode_multipart(fields: dict, files: dict) -> tuple[bytes, str]:
    """Encodes form fields and files as multipart/form-data.

    files maps a field name to (filename, content bytes). Returns the body and its
    Content-Type header value.
    """
    boundary = "in-kino-" + uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
            + str(value).encode("utf-8")
            + b"\r\n"
        )
    for name, (filename, content) in files.items():
        safe_name = filename.replace('"', "%22").replace("\r", "").replace("\n", "")
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"; filename="{safe_name}"\r\n'
                "Content-Type: application/octet-stream\r\n\r\n"
            ).encode("utf-8")
            + content
            + b"\r\n"
        )
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def post(url: str, token: str, timeout: float, fields=None, files=None) -> tuple[int, str]:
    """POSTs to the server with Python's standard library. Returns (status, response text).

    HTTPS certificates are checked against the operating system's trust store.
    """
    headers = {"Authorization": "Bearer " + token}
    body = b""
    if fields or files:
        body, headers["Content-Type"] = encode_multipart(fields or {}, files or {})
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except urllib.error.URLError as error:
        raise PublisherError(f"Cannot reach the server: {error.reason}") from error


def upload(
    server,
    token,
    title,
    label,
    fps,
    language,
    ref_wav,
    ad=None,
    sdh=None,
    film_id=None,
    publish=False,
):
    server = endpoint(server)
    if not ad and not sdh:
        raise PublisherError("Choose AD or SDH, or both")
    with tempfile.TemporaryDirectory(prefix="in-kino-publisher-") as tmp:
        target = Path(tmp) / "fingerprint.csref"
        generate(Path(ref_wav), target)
        files = {"fingerprint": ("reference.csref", target.read_bytes())}
        if ad:
            files["ad"] = (Path(ad).name, Path(ad).read_bytes())
        if sdh:
            files["sdh"] = (Path(sdh).name, Path(sdh).read_bytes())
    fields = dict(
        mode="local",
        film_title=title,
        version_label=label,
        fps=str(fps),
        language=language,
        ad_language=language,
        sdh_language=language,
    )
    if film_id:
        fields["film_id"] = film_id
    status, text = post(
        server + "/api/admin/ingestions", token, timeout=600, fields=fields, files=files
    )
    try:
        body = json.loads(text)
    except ValueError:
        raise PublisherError(f"Server returned non-JSON HTTP {status}") from None
    if status >= 400:
        raise PublisherError(f"Upload rejected ({status}): {body.get('detail', body)}")
    if publish:
        status, text = post(
            server + "/api/admin/versions/" + body["version_id"] + "/publish", token, timeout=60
        )
        if status >= 400:
            raise PublisherError("Draft uploaded, but publish failed: " + text)
        body["state"] = "published"
    return body


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Generate a fingerprint locally; upload only the derived fingerprint"
            " and accessibility assets"
        )
    )
    parser.add_argument("--server", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--fps", type=float, default=24)
    parser.add_argument("--language", default="de")
    parser.add_argument(
        "--ref", required=True, help="LOCAL reference soundtrack; NEVER sent to server"
    )
    parser.add_argument("--ad")
    parser.add_argument("--sdh")
    parser.add_argument("--film-id")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    print(
        upload(
            args.server,
            args.token,
            args.title,
            args.version,
            args.fps,
            args.language,
            args.ref,
            args.ad,
            args.sdh,
            args.film_id,
            args.publish,
        )
    )


if __name__ == "__main__":
    main()
