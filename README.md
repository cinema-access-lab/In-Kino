# In-Kino

Audio description and subtitles for any cinema screening, on the viewer's own phone.

Blind and visually impaired viewers need audio description (AD). Deaf and hard-of-hearing
viewers need subtitles for the deaf and hard of hearing (SDH). In-Kino delivers both
without any equipment in the cinema. The phone listens to the film through its microphone,
works out where in the film it is by acoustic fingerprinting, and then plays the AD track
through headphones or shows SDH in sync with the screen.

Once a film package is downloaded, everything runs offline on the phone. There is no
proprietary SDK, user account, or cloud service involved, and the publishing tools
and catalogue server can be self-hosted.

## How it works

1. A publisher fingerprints the film's reference soundtrack and uploads the fingerprint with
   an AD track, an SDH file, or both.
2. The server validates the package. Once it's published, it appears in the catalogue.
3. The viewer downloads the package in the app before the screening.
4. In the cinema, the app matches microphone audio against the fingerprint and drives a
   single film clock that both AD and SDH follow.

Some details:

- The fingerprint is a set of spectral landmark hashes taken from the film's reference
  soundtrack (`shared/fingerprint_lab.py`). The phone uses a Java port of the same algorithm
  (`android/.../core/`).
- With the desktop publisher, the reference soundtrack never leaves the publisher's computer.
  Only the fingerprint, AD, and SDH are uploaded.
- The phone locks onto the film only after two matches agree. It keeps
  running on its own clock when the microphone loses the signal, and corrects small drift by
  changing playback speed by at most ±0.5% instead of jumping. The rules are in the
  [synchronization correction policy](docs/sync-correction.md).
- AD stays muted until Android confirms the audio is going to
  headphones or a hearing aid, so description never plays over the phone speaker in an
  auditorium.

## Components

| Directory | What it is |
| --- | --- |
| `android/` | Android app: film catalogue, offline library, acoustic matching, AD playback, and SDH display |
| `server/` | FastAPI catalogue and publishing API with an accessible web publisher |
| `desktop-publisher/` | Tkinter GUI and CLI that fingerprint locally and upload only derived files |
| `shared/` | Fingerprint generator, `.csref` exporter, and package validators shared by server and publisher |
| `android/app/src/test/`, `server/tests/` | JUnit tests for the matcher, clock, and subtitle parser; pytest tests for the server and publisher |
| `demo/` | The Elephants Dream demo package: build script, subtitles, and attribution |

## Status

In-Kino is a working research prototype. The current version is in [VERSION](VERSION)
and the changes are in [CHANGELOG.md](CHANGELOG.md). The core pipeline works end to end:
publishing, catalogue, download with SHA-256 verification, matching, holdover, and AD/SDH
playback.

It is not ready for audiences yet. It has not been tested in real auditoriums or with
TalkBack users, the server has a single shared publisher token, and the Android playback
session still lives in an Activity rather than a service. [ROADMAP.md](ROADMAP.md) lists what
comes next, and [SECURITY.md](SECURITY.md) explains why the server must not be exposed to
the internet yet.

## Try it

You need an Android phone (Android 8 or newer), headphones, and a second screen with
speakers to play the film on, such as a laptop.

1. On the phone, download the `.apk` file from the latest
   [release](https://github.com/cinema-access-lab/In-Kino/releases) and open it to install
   the app. Android asks once whether to allow installs from your browser.
2. In the app, open **Online Catalogue**, tap **Use the demo catalogue**, and download
   *Elephants Dream*.
3. On the other screen, open
   [Elephants Dream on the Internet Archive](https://archive.org/details/ElephantsDream).
4. In the app, open the film from **My Films** and put on your headphones. The app starts
   listening straight away. Then play the film with the sound on.

Within about 20 seconds the phone recognizes the film and the audio description starts in
your headphones. Try skipping forward or back in the film: the description follows. Please
report problems, with the phone model, under
[Issues](https://github.com/cinema-access-lab/In-Kino/issues).

## Getting started

To run the server, the desktop publisher, and the app on a phone, follow the
[getting started guide](docs/getting-started.md).

To run the tests (Python 3.12+, JDK 17+, and the Android SDK):

```sh
python -m venv .venv && . .venv/bin/activate
pip install -r server/requirements.txt -r desktop-publisher/requirements.txt pytest httpx
python -m pytest -q
(cd android && ./gradlew test)
```

More detail is in the [development guide](docs/development.md).

## Documentation

- [Getting started](docs/getting-started.md)
- [Architecture and API](docs/architecture.md)
- [Synchronization correction policy](docs/sync-correction.md)
- [Package validation rules](docs/package-validation.md)
- [On-device test checklist](docs/device-testing.md)
- [Android theming](docs/theming.md)
- [Development](docs/development.md)
- [Patents and prior art](docs/patents.md)
- [Third-party software and licenses](docs/third-party.md)
- [Changelog](CHANGELOG.md)

## How this project is built

In-Kino is designed and maintained by John-Thomas Eltringham and Felix Hildebrand.
We decide the design and the requirements and are responsible for all the code. We use AI
coding assistants for parts of the implementation.

All changes are tested and reviewed.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright 2026 John-Thomas Eltringham and Felix Hildebrand. Licensed under the Apache License
2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
