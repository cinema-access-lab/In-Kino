# Architecture

## Identifiers

The server assigns all identifiers when it accepts an upload:

- `film_<UUID>` is a film title, independent of any particular cut.
- `ver_<UUID>` is one version of that film: a specific cut, frame rate, and reference
  language. A new version can be added to an existing film by passing its film ID.
- `asset_<UUID>` is one file. Assets are never changed in place. Replacing a file will need a
  new asset and revision, which isn't implemented yet.

## Data model

The server keeps three SQLite tables, `films`, `versions`, and `assets`, and stores files under
`INKINO_DATA_DIR/assets/`. A version is a draft, published, or withdrawn.

In this prototype a version has exactly one fingerprint and at most one AD track and one SDH
track. The [roadmap](../ROADMAP.md) describes the richer model the library needs.

## API

Public endpoints, no login:

| Endpoint | Returns |
| --- | --- |
| `GET /health` | Server status |
| `GET /api/catalog` | All published films and versions (`in-kino.catalog.v1`) |
| `GET /api/versions/{version_id}` | One version and its assets (`in-kino.package.v1`) |
| `GET /api/assets/{asset_id}/download` | The file, only if its version is published |

Publisher endpoints, which need the admin token:

| Endpoint | Does |
| --- | --- |
| `POST /api/admin/ingestions` | Creates a draft from an upload (see below) |
| `POST /api/admin/versions/{version_id}/publish` | Re-validates the files and publishes |
| `POST /api/admin/versions/{version_id}/withdraw` | Withdraws a published version |
| `GET /api/admin/films` | Lists everything, including drafts |

An ingestion is a multipart upload with the title, version label, frame rate, and language,
an optional AD and SDH file, and either a ready-made fingerprint (`mode=local`) or the
reference soundtrack to fingerprint on the server (`mode=online`).

Each asset in a package lists its kind, language, SHA-256, size, revision, filename, and a
download URL relative to the server. The app checks the size, the checksum, and that the URL
points to the same server, then moves the finished download into its offline library in one
step.

The same API can be served as static files, which is how the Elephants Dream demo runs on
GitHub Pages (`shared/static_catalog.py`).

## Privacy

| Publisher | Reference soundtrack | Fingerprint | AD and SDH |
| --- | --- | --- | --- |
| Desktop publisher | Stays on the computer | Uploaded | Uploaded |
| Web publisher | Uploaded, fingerprinted, then deleted | Created on the server | Uploaded |

The desktop publisher only ever sends the fingerprint. It still needs a connection to upload,
but the soundtrack is processed locally.

With the web publisher, the soundtrack can pass through temporary files. They are deleted
afterwards, but ordinary deletion doesn't securely erase data on SSDs. A production server
should use encrypted temporary storage, strict file permissions, and no backups of scratch
space.

## Security

The prototype uses a single shared token for publishing, with no user accounts. Uploads are
limited in size and type, validated, and only served once published. The server never fetches
URLs on anyone's behalf. It is meant for a trusted local network; [SECURITY.md](../SECURITY.md)
explains how to test it safely.

## The app

- Fingerprints use `.csref` version 1 (see [package validation](package-validation.md)).
- Films from the catalogue open straight in the player. In the Manual Player, files are
  chosen and checked on a setup page (`ManualSetupActivity`) first, so the player itself has
  no file controls.
- The app downloads and plays every track of a film version. With several languages per
  version, it will need a track selector and should only download the chosen tracks; see the
  [roadmap](../ROADMAP.md).
- Audio description only plays on personal outputs: wired, USB, and Bluetooth headphones,
  LE audio, and hearing aids (`HeadphoneOutputs.java`). It starts muted and becomes audible
  once Android confirms it is really playing there. Android can't tell Bluetooth headphones
  from Bluetooth speakers, so the player shows the device's name instead.
- A foreground service keeps matching running with the screen off, but the session itself
  still lives in `MainActivity`. It does not survive the Activity being destroyed or the
  process being killed.
