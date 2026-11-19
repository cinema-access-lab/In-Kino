# Third-party software and licenses

In-Kino's own code is licensed under the Apache License 2.0 (see `LICENSE` and
`NOTICE`). This page lists the third-party software the project uses, what it is used for,
and its license. It is a record kept by the maintainers, not legal advice.

## Android app

The app has no third-party runtime libraries. It uses only the Android platform APIs.

| Component | Used for | License |
| --- | --- | --- |
| Android SDK, build tools, and Android Gradle Plugin | Building the app | Android Software Development Kit License Agreement |
| Gradle and the Gradle wrapper (included in `android/`) | Building the app | Apache 2.0 |
| JUnit 4.13.2 (with Hamcrest) | Unit tests only; not part of the app | EPL 1.0 (Hamcrest: BSD 3-Clause) |

## Server and desktop publisher

These are installed by users with `pip`, not shipped with the project.

| Component | Used by | License |
| --- | --- | --- |
| FastAPI (with Starlette and Pydantic) | Server | MIT / BSD 3-Clause / MIT |
| Uvicorn | Server | BSD 3-Clause |
| python-multipart | Server | Apache 2.0 |
| NumPy | Server, publisher | BSD 3-Clause; wheels bundle further libraries (see below) |
| SciPy | Server, publisher | BSD 3-Clause; wheels bundle further libraries (see below) |
| Python standard library and Tkinter | Publisher | Python Software Foundation License / Tcl/Tk license |

The publisher uploads with the Python standard library (`urllib`), so it needs no HTTP
client package. HTTPX is used only by the server tests.

### Libraries bundled inside NumPy and SciPy wheels

The prebuilt NumPy and SciPy wheels include compiled numerical libraries:

| Library | License |
| --- | --- |
| OpenBLAS | BSD 3-Clause |
| LAPACK | BSD 3-Clause (Open MPI variant) |
| GCC runtime (libgfortran) | GPL 3.0 or later with the GCC Runtime Library Exception 3.1 |
| libquadmath (SciPy on Linux) | LGPL 2.1 or later |

The GCC Runtime Library Exception lets programs linked against the runtime use any license,
so these libraries place no conditions on this project's code. Users install the wheels
from PyPI themselves, and the license texts ship inside the wheels.

## License audit

Last run on 2026-11-19 against version 0.8.0, on Linux and Windows, in clean virtual
environments with only the runtime requirements installed:

- All installed Python packages have permissive licenses: annotated-doc, annotated-types,
  anyio, FastAPI, h11, Pydantic, pydantic-core, and typing-inspection (MIT); click, idna,
  Starlette, and Uvicorn (BSD 3-Clause); NumPy (BSD 3-Clause with MIT, Zlib, 0BSD, and CC0
  parts); SciPy (BSD 3-Clause); opentelemetry-api (a FastAPI dependency) and python-multipart
  (Apache 2.0); and typing-extensions (PSF 2.0). The only copyleft code is the bundled runtime
  libraries listed above.
- The release APK contains only the project's own classes (`org.cinemaaccesslab.inkino`), and the
  release runtime classpath has no dependencies.
- A ScanCode Toolkit 32.5.0 scan found Apache 2.0 notices on all source files and the expected
  Creative Commons notices for the demo media. The only other copyright holders it found are
  Brightcove, Inc. (the demo captions) and the Gradle authors (the wrapper scripts).
- A gitleaks scan of the full Git history found no credentials.

## Development and CI tools

Used to test, lint, and build. None of them become part of the app or the server.

| Tool | Used for | License |
| --- | --- | --- |
| pytest, HTTPX | Server tests | MIT, BSD 3-Clause |
| Ruff | Python linting and formatting | MIT |
| google-java-format | Java formatting check | Apache 2.0 |
| FFmpeg and FFprobe | Converting audio in the demo build only | LGPL 2.1+ or GPL, depending on the build |
| GitHub Actions and GitHub Pages | CI and demo hosting | GitHub's terms of service |

## Media

The Elephants Dream demo uses Creative Commons media, licensed separately from the code.
The credits and licenses are in the [demo's README](../demo/elephants-dream/README.md).

## Before a production release

Dependency versions are ranges, not pinned. Before a production release, pin exact versions
(a lockfile) and generate a software bill of materials (SBOM) with license data for every
installed package, including libraries bundled inside NumPy and SciPy wheels. Bundling
Python, its packages, or FFmpeg into an installer would also mean shipping their license
texts.
