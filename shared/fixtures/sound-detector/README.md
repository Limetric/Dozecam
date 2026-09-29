# Sound detector

Wake-on-sound: when a level counts as loud, how long it must stay loud to
trigger, and how long it must stay quiet to re-arm.

## `detector.json` — level timelines

Each case starts a fresh detector with `settings` and feeds it `samples` in
order.

- `settings`: `threshold` is the normalized level (0–1) at or above which a
  sample is loud; `sustainMs` is how long the level must stay loud before it
  triggers; `quietMs` is how long it must then stay below the threshold before
  the detector re-arms.
- Each sample: `atMs` (the sample's time, from the case's start) and `rms`
  (its normalized level, 0–1) are the input. `triggers` is whether that sample
  fires the trigger; it is checked on every sample. `phase`, when present, is
  the detector's state right after the sample: `"armed"` (waiting for a loud
  level), `"building"` (loud, not yet for long enough) or `"triggered"`
  (fired, waiting for quiet).
- `newSettings`, when present, replaces the settings just before that sample
  is fed, as changing them in the app does mid-run.

Levels and thresholds are 32-bit floats; compare them as such.

## `rms.json` — levels from PCM

How a buffer of audio becomes a level: the root mean square of signed 16-bit
PCM samples, divided by 32768 and clamped to 0–1. An empty buffer is 0.

- `samples`: the signed 16-bit sample values, in order. Each platform packs
  them into its own buffer format (16-bit little-endian on Android).
- `expected`: the level; `tolerance`: the largest allowed difference from it.
