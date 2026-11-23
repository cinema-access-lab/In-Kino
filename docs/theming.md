# Android theming

The look of the app is defined in one place,
`android/app/src/main/java/org/cinemaaccesslab/inkino/ui/CinemaUi.java`. The audio engine in `core/`
doesn't depend on it.

## Palette

| Role | Hex colour | Usage |
|---|---|---|
| Background | `#091620` | Night-blue canvas |
| Surface | `#132532` | Cards, inputs, film rows |
| Raised surface | `#1C3342` | Secondary buttons |
| Text | `#F5FAFC` | Labels |
| Muted text | `#B4C6D0` | Guidance and details |
| Accent | `#69E4D0` | Primary buttons, status, focus |
| On accent | `#061D21` | Text on accent |
| Border | `#33505E` | Outlines |
| Warning | `#F5C26B` | Status: signal lost, checking a jump |
| Error | `#FF9A8F` | Status: errors |

## App icon

The launcher icon is an adaptive icon: a waveform above two subtitle lines, on the background
colour. The vectors in `res/drawable/` are generated to keep the mark inside Android's safe
zone, so it survives circle, squircle, and rounded-square masks. `ic_launcher_monochrome`
supplies the themed icon on Android 13+, and `ic_stat_sync` is the notification icon. On
Android 12+ the same mark is the launch screen (`res/values-v31/styles.xml`).

## Building blocks

- `edgeToEdge(activity, root)` pads a screen's outermost view to keep content clear of the
  status bar, navigation bar, display cutout, and keyboard. Every screen must call it. Android 15
  and later always draw apps edge to edge.
- Text styles: `title`, `sectionHeading`, `body`, and `caption`. Titles and section headings are
  marked as accessibility headings.
- Layout: `column` (standard screen margins), `stacked` (full-width button with standard gap),
  `pair` (two buttons side by side), and `listItem` (list entry with standard gap).
- Controls: `button` (primary or secondary, flat, at least 56dp tall), `setPrimary` (switches
  a button between the two looks, for on/off toggles), `iconButton` (a single symbol with a
  spoken label), `styleInput`, and `styleCheckBox`.
- Timing: `ShiftControl` moves a track earlier or later in 0.1-second steps and announces the
  new value; `SubtitleControlBar` is the small bar over fullscreen subtitles, with timing and
  text size.
- Lists: `filmRow` (title, detail, and status label in one tappable card with a single
  spoken description), and `emptyState`.
- Screens: `screenHeader` (back button and title for every screen except Home).
- Status: `statusPill` and `setStatus` with a tone (neutral, active, OK, warning, error).
  The text always states the status too, so colour is never the only cue.

## Text and languages

All user-facing text lives in `res/values/strings.xml` (English, the fallback) and
`res/values-de/strings.xml` (German, formal "Sie"). Android picks the language from the
phone's settings. Add a language by adding `res/values-<code>/strings.xml`. The diagnostics
panel, which only appears in debug builds, stays in English.

Text sizes use sp, so they scale with the system font size. Status changes avoid continuous
spoken announcements. None of this has been formally tested with TalkBack, Switch Access, or
large fonts yet. The [device testing checklist](device-testing.md) includes these tests.

## Restyling

- Change colours, shapes, and spacing in `CinemaUi.java`, and the matching window and accent
  colours in `android/app/src/main/res/values/styles.xml`.
- Which controls appear on a screen, and in what order, is still decided in each Activity.
- Don't change `core/`, `subtitles/`, `SessionForegroundService`, or the headphone routing logic
  for visual changes.

## Dependencies

The app has no runtime dependencies beyond the Android platform. It deliberately doesn't add
Material Components for Views just for colours and buttons. A later move to Jetpack Compose
(Material 3) could call the existing Java engine through a small state interface.
