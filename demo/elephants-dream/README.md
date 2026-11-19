# Elephants Dream demo package

A complete, redistributable test package for In-Kino, built from the 2006 open movie
*Elephants Dream*: a fingerprint of the real soundtrack, a recorded audio description, and
English subtitles.

The film and the large source files are not stored in this repository. `build.py`
downloads them, builds the package, and writes a static catalogue that the app can download
from. The GitHub Actions workflow `.github/workflows/demo.yml` does the same on every change.

## Sources and licenses

| Part | Source | License |
| --- | --- | --- |
| Film and soundtrack | © 2006 Blender Foundation / Netherlands Media Art Institute / [www.elephantsdream.org](https://orange.blender.org). Stereo master `ED-CM-St-16bit.flac` from [Xiph.Org](https://media.xiph.org/) | [CC BY 2.5](https://creativecommons.org/licenses/by/2.5/) |
| Audio description | By [JJ Hunt](http://www.jjhunt.com/), from [OwenEdwards/ElephantsDreamAudioDescription](https://github.com/OwenEdwards/ElephantsDreamAudioDescription) at commit `290365c`. The repository README and the description file itself (`ed-description-jjhunt.vtt`, which names JJ Hunt as author) both state the license | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| Subtitles (`subtitles.en.vtt`) | [`docs/examples/elephantsdream/captions.en.vtt`](https://github.com/videojs/video.js/blob/v8.21.0/docs/examples/elephantsdream/captions.en.vtt) from Video.js v8.21.0, renamed but otherwise unchanged. Copyright Brightcove, Inc. The file itself names no author | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |

These licenses are separate from the project's Apache 2.0 license and stay with the files:

- The `.csref` fingerprint is derived from the soundtrack, so it carries the CC BY 2.5
  attribution above.
- The published AD file is JJ Hunt's track transcoded to AAC, and remains under CC BY-SA 4.0.
  Any edited or re-timed version must also be released under CC BY-SA 4.0.
- Use the Xiph film master, not the separate score downloads on the Blender website. Those
  are licensed CC BY-NC-ND.
- The build converts audio with FFmpeg on the build machine. FFmpeg is not distributed with
  the project, and its license does not extend to the files it produces.

The generated catalogue includes an `index.html` page with this attribution.

## What the build checks

- The AD track and the soundtrack master share one timeline. Excerpts of JJ Hunt's "film
  with audio description" video are matched against the master's fingerprint, and the AD
  track is cross-correlated with the same video. The build fails if the film offset (median
  of several excerpts) exceeds 0.15 s or any AD offset exceeds 0.05 s.
- The Android app's own matcher (`AppMatcherCheck.java`) finds excerpts of the real film at
  the right positions in the fingerprint made by the Python publisher.
- The fingerprint and subtitles pass the server's package validation.
- Every download must match the SHA-256 pinned in `SOURCES`.

On the first reviewed build, the AD track lined up with the film exactly (cross-correlation
0.85 to 0.96, zero offset), and the film matched the master within 0.1 s. The master runs
658 s, about 4 s longer than the AD track.

## Known gaps

- The subtitles are dialogue only. Proper SDH adds speaker labels and sound descriptions
  such as `[machine whirring]`. Writing them needs someone to watch the film. Edit
  `subtitles.en.vtt`, keep the Video.js attribution, and note your changes.
- The subtitle timing was not checked automatically against the master.
- The AD is in English only.

## Run it locally

You need Python 3.12+, `ffmpeg`, and the desktop publisher's requirements:

```sh
pip install -r desktop-publisher/requirements.txt
python demo/elephants-dream/build.py --out build/demo-site
python -m http.server --directory build/demo-site 8000
```

Then set the app's catalogue server to `http://<computer-ip>:8000` (debug builds only allow
plain HTTP).

To publish the same package on your own catalogue server instead, use the desktop publisher
with the master WAV, the AD file, and the subtitles. It fingerprints locally and uploads only
the derived files.

## GitHub Pages

The workflow can publish the catalogue on GitHub Pages:

1. The repository must be public, or on a GitHub plan that includes Pages for private
   repositories.
2. In the repository settings, under **Pages**, set the source to **GitHub Actions**.
3. Run the **Demo catalogue** workflow by hand with **deploy** ticked.

The app then uses `https://cinema-access-lab.github.io/In-Kino` as its catalogue server.
