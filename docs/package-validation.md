# Package validation

The server checks every uploaded file twice: when it is uploaded and again before it is
published. It is stricter than the app on purpose, so that a published package never loses
subtitles or fingerprint data without anyone noticing.

## Fingerprints (`.csref` version 1)

The file is little-endian: the magic bytes `CSRF`, a 32-bit version (1), the film duration in
seconds as a 64-bit float, a 32-bit landmark count, that many pairs of 32-bit hash and 32-bit
frame, and a CRC32 of everything before it.

The server checks that:

- the duration is positive and under 12 hours,
- there are between 8 and 5,000,000 landmarks and the file length matches, staying under the
  app's limit of 40,000,024 bytes,
- the CRC32 is correct,
- hashes are sorted as unsigned numbers, and frames are not negative and not past the end of
  the film (duration × 11025 / 256, plus 150 frames of tolerance).

The app's reader (`FingerprintReference.java`) checks the same things except the last frame
limit and the minimum of 8 landmarks. Those two come from the exporter
(`shared/export_android.py`). If you change either side, change the other.

## Subtitles

The server accepts `.srt` and `.vtt` files in UTF-8, with or without a byte-order mark. Every
cue needs a valid time range that ends after it starts and at least one line of text. Files
are limited to 4,000,000 characters and 50,000 cues. Cue numbers don't have to be consecutive,
and overlapping cues are allowed. Nothing is rewritten.

A block the server doesn't recognise is rejected. The app would skip it silently, so this
catches subtitles that would otherwise go missing. WebVTT headers and `NOTE`, `STYLE`, and
`REGION` blocks are skipped as metadata.

## Before publishing

The server rechecks each stored file's size, SHA-256, and format. If anything has changed or
fails, the version stays a draft.

## What validation doesn't cover

The checks are about structure. They can't tell whether the wording, language, or timing is
right, whether the tracks match the film's cut and frame rate, whether the AD and SDH belong
together, or whether the rights have been cleared. The checks on WAV and M4A files are shallow
and don't guarantee playback on every device.
