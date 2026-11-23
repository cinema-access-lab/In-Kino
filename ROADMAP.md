# Roadmap

Where In-Kino is headed, and what stands between the current prototype and something
audiences, producers, and archives can rely on.

## Direction

Audio description (AD) and subtitles for deaf and hard-of-hearing audiences (SDH) often
already exist for a film, but are hard to find, credit, preserve, or obtain for the next
screening. The main work ahead is an open, self-hostable catalogue and library for these
existing tracks: documenting them, keeping them safe, recording who may use them for what,
and delivering them to audiences where the rights allow.

The Android app, the desktop publisher, and the fingerprint engine are the working proof of
concept and the reference clients for that library. They need maintenance and real-world
testing, but a new synchronization method is not the goal. Neither is producing AD or SDH,
or distributing the films themselves.

## Current state

The fingerprint matching and AD playback have been tried on a real Android phone, including
Bluetooth headphones and large jumps in the film, with encouraging results. Device coverage
is still small. Subtitles, the catalogue and library, fullscreen subtitles, holdover, and
smooth corrections are covered by automated tests and the Elephants Dream demo, but have not
yet been tested on devices as a complete release.

## Catalogue and library

The library is the core of the next phase.

- An information model of film work → exact film version → AD or SDH track → file revision,
  with persistent identifiers and room for external ones.
- Film versions identified by cut and timing, not just title and runtime: exact frame rate
  as a timebase (24000/1001 is not 24 or 25), edit offsets, territory, and DCP CPL IDs where
  available. Never silently treat similar cuts as interchangeable.
- Credits for each track: script authors, narrators, subtitlers, editors, studios, and
  commissioning organizations, plus language and variant.
- Rights holders recorded separately for the film, the AD script, the AD recording, and the
  subtitles. A credit or an upload does not imply a right.
- Metadata-only records, so a track can be documented and found even when its files cannot
  be published.
- Distinct states for known, held elsewhere, deposited with restrictions, available for a
  specific use, and withdrawn.
- Provenance for every record: who supplied it, when, how it was checked, and its revision
  history. New masters must not overwrite old ones.
- Search by film, contributor, language, access service, and availability.
- Bulk import and export, deduplication, a correction workflow, and an open, documented
  schema with controlled vocabularies.

## Preservation

- Deposit of authorized masters with SHA-256 checksums, versioned storage, regular fixity
  checks, audit logs, and backups.
- A clear distinction between a preservation master and the compressed copy delivered to
  audiences.
- Withdrawals, embargoes, takedowns, and expiring rights recorded without erasing
  provenance or authorized holdings.
- A storage layer that works locally for development and with S3-compatible or institutional
  storage in deployment, with export and migration paths.
- This is preservation-ready asset management. Calling it a long-term archive would need
  agreed policies, partners, and operating funds.

## Publishing and permissions

- Permissions per track, film version, use (cinema, festival, education, home), territory,
  and time period. Permission to deposit is not permission to publish.
- Publisher accounts and roles (producer, curator, administrator) instead of one shared
  token, with a draft, review, publish, and withdraw workflow and an audit history.
- Three ways in, sharing one schema and one fingerprint engine: the desktop publisher, which
  never uploads the film's soundtrack; a web publisher that fingerprints on the server and
  deletes the soundtrack afterwards; and metadata-only registration.
- Checks that AD and SDH timing match the film version they are published for, and deeper
  validation of AD audio files.
- Resumable uploads and downloads and validation reports.
- Public discovery without listener accounts, and no download tracking beyond what is needed
  to run the service.
- Links and exports to existing archives and platforms rather than duplicating them.
- For an internet-facing server: TLS, upload limits and isolation, rate limits, logging,
  backups, temporary-file disposal, and a recovery plan.
- A packaged Windows installer for the desktop publisher, and an accessible web interface
  for publishing and searching.

## Android app

- Move microphone capture, matching, the film clock, and AD playback out of `MainActivity`
  into a foreground service, so a session survives screen-off, the Activity being destroyed,
  and process death.
- TalkBack as a release gate for every screen, not just playback: focus order, announcements
  that don't talk over the AD, large targets, scalable text, and accessible download errors.
- Library downloads with space estimates, resume, and updates, plus clear handling of
  withdrawn or revised packages.
- A warning before playing tracks published for a different film version.
- Multiple AD and SDH tracks per film version (languages, easy-read variants), with a
  selector for the tracks to play and default choices in Settings, such as English AD or
  French subtitles. Only the chosen tracks are downloaded. The fingerprint is always needed,
  because both AD and subtitles follow the film clock it provides. For now, the app
  downloads and plays every track of a film version.
- Show the headphone check and the subtitles only to the people who use them, based on
  those choices.
- Credits and license information per film package, shown in the app. For now, the demo
  film's credits are on the About screen.
- A UI-only move to Jetpack Compose is worth evaluating if it brings a measurable benefit.
  The synchronization engine stays in Java.

## Synchronization

- Faster initial lock than the current two 8-second analysis windows, without more false
  locks.
- Tell apart a small steady offset, real drift, a false match, a pause, and a real jump.
  Estimate drift from several observations with confidence tracking, instead of the current
  phase-only slew.
- Tune the 100 ms and 1.5 s thresholds and the speed-correction limits with listening tests,
  and make them versioned configuration.
- Separate the film-clock estimate from microphone, decoding, and headphone latency, with an
  accessible, persistent timing adjustment.
- `.csref` v2 with lossless compression, with the exporter and the Android reader updated
  together.

## Silent films

Fingerprinting needs a soundtrack. Silent films, and quiet stretches in any film, need a
different source of position: an inaudible synchronization marker embedded in the film's
audio.

- Design and test a protocol of sparse position markers, near-ultrasonic or psychoacoustically
  masked, so the audience cannot hear them. An optional short audible signature at the start
  can be compared against a version without one.
- Feed the markers into the same film clock, holdover, and correction as fingerprint matches.
- Test with real cinema sound systems and phone microphones: frequency response, whether
  anyone can hear the markers, and how reliably they are detected at different seats.
- Try authoring the markers into a DCP or a festival master before recommending them to
  anyone.

## Testing and user involvement

- A device matrix covering several manufacturers, Android versions, and price ranges, with
  Bluetooth, wired, and USB-C output. Keep a test report for each device and build,
  based on the [device testing checklist](docs/device-testing.md).
- Measure time to first lock, false locks, recovery after jumps and dropouts, sync error,
  and audible AD latency, including long sessions.
- Real conditions: auditoriums and home viewing, quiet and loud passages, different seats,
  the phone in a pocket, background noise, calls and notifications, and battery and heat.
- Interviews with film archives, producers, distributors, festivals, AD and SDH
  professionals, and blind and deaf audiences, about their holdings, rights, credits, and
  workflows.
- Co-design of the catalogue and its accessibility with those groups, and paid usability
  testing with blind, deaf, and hard-of-hearing users where possible. Keep a record of what
  each round of feedback changed.
- A pilot collection of rights-cleared tracks and metadata, including entries that can only
  link to an archive.

## Release and legal

- A professional freedom-to-operate [patent search](docs/patents.md) before a public
  release.
- Pinned dependency versions and a software bill of materials (SBOM) with
  [license data](docs/third-party.md) before a production release.
- Reproducible, signed Android release builds.
- A self-hosting guide, API and schema versioning, and issue templates.

## Later

- An iOS client with VoiceOver support.
- Sign-language tracks and other access services.

## Guardrails

- No distribution of films, third-party media, or original soundtracks without explicit
  permission. A fingerprint, AD, and SDH package is not a license for the film itself.
- No mandatory accounts for audiences to find public information or use downloaded packages.
- No proprietary fingerprint SDK or per-film fingerprint fees.
- Automated tests are not evidence of accessibility or device compatibility. Only tests with
  real devices and users are.
