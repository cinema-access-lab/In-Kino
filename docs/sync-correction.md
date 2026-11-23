# Synchronization correction policy

The film clock (`core/SyncClock.java`) takes positions from the fingerprint matcher and keeps
AD and SDH on a single shared timeline. Any future position source, such as the inaudible
markers planned for silent films, would feed the same clock.

## Rules

| Situation | What the clock does |
| --- | --- |
| Not locked yet | Waits for two matches that agree before starting AD |
| The microphone loses the film | Keeps running on its own, indefinitely. AD and SDH continue |
| A single match disagrees | Ignores it until another match confirms it |
| Error up to 100 ms | Treats it as measurement noise and does nothing |
| Confirmed error between 100 ms and 1.5 s | Speeds up or slows down by at most 0.5% until caught up |
| Confirmed error above 1.5 s | Jumps to the new position, moving AD and SDH together |
| AD playback more than 5 s off for 3 s | Resynchronizes the player to the clock |

AD speed changes keep the pitch where the device supports it, and the subtitles read the same
clock, so the two can't drift apart.

Listeners can also move AD and subtitles earlier or later by hand, in steps of 0.1 seconds: a
lasting default in Settings, for example for slow Bluetooth headphones, plus a temporary shift
on the player for one screening. The shift is added after all clock calculations, and the
automatic correction never changes it.

At 0.5%, catching up one second takes about 200 seconds. That is deliberate: a slow, inaudible
correction is better than a jump when it's unclear what happened.

These thresholds are starting points for testing, not measured limits of what audiences notice.

## What the clock can't know

- Matches only show where the film is, not what the projectionist did. If the projector stops
  while the microphone hears nothing, the clock can't see the pause. When the film resumes, it
  looks like a jump backwards. The clock corrects the position but can't tell the cause.
- Large jumps are only accepted after a second match confirms them.
- Microphone and room delay and Bluetooth headphone latency are separate from drift. A
  microphone-based offset shouldn't be used to cover an unmeasured headphone delay.
- Each analysis window is 8 seconds, so the first lock takes at least two windows.

## Testing

`SyncClockTest` in `android/app/src/test/` covers the rules above. The checks that need a real
phone are in the [device testing checklist](device-testing.md#synchronization).
