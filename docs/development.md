# Development

## Python (server, publisher, and shared code)

```sh
python -m venv .venv && . .venv/bin/activate
pip install -r server/requirements.txt -r desktop-publisher/requirements.txt pytest httpx ruff
python -m pytest -q          # server and publisher tests
ruff check . && ruff format --check .
```

On Windows, activate with `.venv\Scripts\activate`. The tests set their own throwaway admin
token and use a temporary data directory.

## Android app and Java tests

Open `android/` in Android Studio (JDK 17, Android SDK 36), or use the Gradle wrapper:

```sh
cd android
./gradlew test               # JUnit tests
./gradlew assembleDebug      # debug APK in app/build/outputs/apk/debug/
```

On Windows, use `gradlew.bat`. The app has no third-party runtime dependencies.

The JUnit tests in `android/app/src/test/` cover the parts that don't need a phone:

- `LandmarkMatcherTest` matches degraded "microphone" excerpts at 11,025 Hz and 44,100 Hz
  against a fingerprint, and checks that a different soundtrack doesn't match.
- `SyncClockTest` covers lock, seek, holdover, and gradual drift correction.
- `SubtitleTimelineTest` covers SRT and WebVTT parsing.
- `AudioRingTest` and `FingerprintReferenceTest` cover the microphone buffer and the
  `.csref` checksum.

Audio routing, lifecycle, and accessibility need a real phone. The
[device testing checklist](device-testing.md) covers them.

### Java formatting

Java code follows [google-java-format](https://github.com/google/google-java-format) in AOSP
style (4-space indent), and CI rejects unformatted code. Android Studio's
google-java-format plugin, set to AOSP style, formats on save.

The tests need no audio files. They generate a deterministic soundtrack of tone bursts
(`SyntheticSoundtrack` in Java, `server/tests/synthetic_media.py` in Python). The demo
build checks the real thing: the app's Java matcher must find excerpts of Elephants Dream in
the fingerprint the Python publisher made (`demo/elephants-dream/AppMatcherCheck.java`).

## Keeping formats in sync

`.csref` and subtitle parsing exist in both Python (server validation) and Java (the app). If
you change either side, update the other and the tests on both sides. The server is
deliberately [stricter than the app](package-validation.md).

## Versions and releases

The `VERSION` file sets the version everywhere: the Android `versionName`, the
`versionCode` (major × 10000 + minor × 100 + patch), and the server's `/health` response. To
release, change `VERSION` and add a matching `## x.y.z` section to `CHANGELOG.md`. CI fails
if the changelog has no entry for the current version. Pushing a `v` tag publishes a signed
APK, as described in [releasing the app](releasing.md).

## Dependencies

Python dependencies use bounded version ranges, not a lockfile. Pin and audit them before a
production deployment. Their licenses are listed under [third-party software](third-party.md).
