# Changelog

## 0.8.1

Android app:

- The player starts listening as soon as a film opens, and stops when you leave it or press
  Stop in the notification. It no longer has a start button.
- The player shows the status and film position, an audio description switch with its
  output and timing, and the subtitles with their timing. Sections for tracks a film doesn't
  have are hidden.
- The audio description switch silences only the description; synchronization and subtitles
  carry on. TalkBack reads it as a switch.
- Audio description and subtitles can be moved earlier or later in steps of 0.1 seconds: as
  a lasting default in Settings, and for a single screening in the player.
- Fullscreen subtitles have their own button besides the double tap, and keep the screen on.
  A single tap shows a small bar to adjust the subtitle timing and text size.
- The fullscreen text size goes up to 120 sp, and only shrinks when a long subtitle wouldn't
  fit. The subtitle box in the player keeps the standard size.
- The Manual Player opens a setup page first. It checks each chosen file and warns when the
  audio description or subtitles don't match the film's length.
- Bluetooth and USB headphones no longer need a confirmation checkbox. The player names the
  device the audio description plays through, and still mutes it on the phone speaker or
  when the headphones disconnect.
- Hearing aids that connect directly to the phone (Android 9 and later) count as headphones.
- Connection problems are explained in plain words, such as "No server is answering at this
  address", instead of internal error messages.
- Without a catalogue server, the Online Catalogue offers the demo catalogue in one tap, and
  Settings has the same button.

Demo and tooling:

- Pushing a version tag builds a signed APK and publishes it as a GitHub release; see
  `docs/releasing.md`. CI also builds the release variant on every push.
- The README has a "Try it" section for testers.
- The demo's README lists the public copies of Elephants Dream that match the fingerprint,
  and explains how to connect a phone by USB when it can't reach the computer's network.
- GitHub Actions are updated to their current versions.

## 0.8.0

Android app:

- New app icon (a waveform above subtitle lines), with themed and notification versions,
  and a matching launch screen on Android 12+.
- German translation. Android shows German on German phones and English elsewhere.
- Redesigned screens: content stays clear of the status and navigation bars, the home screen
  has a centred title, films appear as cards with their status, and labels are in sentence
  case.
- The player shows the synchronization state as a coloured status pill, and a short
  vibration confirms a new lock. The vibration can be turned off in Settings.
- New About screen with the license and demo film credits.
- Diagnostics only appear in debug builds.
- Catalogue server addresses may include a path, for static hosting.
- Fixed: every verified download failed, because the SHA-256 check hashed the whole buffer
  instead of the bytes read.

Demo and tooling:

- Elephants Dream demo package, built from Creative Commons sources, with an automatic
  timing check of the audio description against the soundtrack.
- Static catalogue export, so any static web host can serve a test catalogue.
- One `VERSION` file sets the version of the Android app and the server.
- Java tests are JUnit tests run by the Gradle wrapper (`./gradlew test`), Java code is
  formatted with google-java-format, and CI checks both.
- Errors that were silently ignored are now logged.
- Removed the synthetic `sample/` files. Tests generate their own audio, and the demo
  build checks the app's matcher against the real film.

## 0.7.2

Server and repository changes. The Android app's behaviour is unchanged.

- The server validates every `.csref` record, its duration, CRC, exact size, Android reader
  limits, and timestamp bounds before accepting a draft.
- SRT and VTT files are parsed cue by cue. A malformed block is rejected instead of being
  silently skipped on the phone.
- Before publishing, the server rechecks each stored asset's size, SHA-256, and format, so an
  old invalid draft or a file changed on disk cannot go live.
- Added negative tests for corrupted fingerprints, malformed subtitles, and tampered or
  missing assets.
- Released under the Apache License 2.0, copyright John-Thomas Eltringham and Felix Hildebrand. Added CI, linting, and reorganised documentation.

## 0.7.1

Android navigation and fullscreen subtitles.

- New home screen with My Films, Online Catalogue, Manual Player, and a menu with
  Settings.
- Settings store the catalogue server address, subtitle size, and the optional TalkBack cue
  announcement.
- Double-tap the subtitle display for a black, landscape-only fullscreen view. Double-tap
  again or press Back to leave it. Subtitles keep following the same film clock.

## 0.7.0

Smooth synchronization.

- Shared film clock with gradual correction. Confirmed offsets between 100 ms and 1.5 s are
  removed by changing speed by at most ±0.5%, with no audible jump.
- AD playback speed follows the clock through `PlaybackParams`, with pitch held at 1.0.
- Large jumps need two consistent observations before AD and SDH reposition together.
- Shared Android UI layer (`ui/CinemaUi.java`) with a high-contrast dark theme.

## 0.6.1

Continuous playback.

- After the first lock, AD and SDH keep running on the film clock when the microphone loses the
  signal (holdover), instead of stopping after a timeout.
- A foreground service with a notification and Stop action keeps matching alive with the
  screen off.

## 0.6.0

Publishing and catalogue.

- FastAPI catalogue server with draft, publish, and withdraw states, and SHA-256-verified
  downloads.
- Web publisher (fingerprint generated on the server) and local desktop publisher
  (fingerprint generated on the publisher's computer).
- Android catalogue, offline film library, and package verification.
