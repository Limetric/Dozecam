# Playback watchdog

The timings the live-view watchdog judges a stream by and retries it on. A
frozen picture must be caught within these bounds, and a failing camera is
retried on this schedule. The tests run the watchdog with its built-in
defaults and derive their expected reconnect times from this file, so the
defaults and the file cannot drift apart.

`timings.json`:

- `stallTimeoutMs` — how long a live stream may go without a frame before it
  counts as stalled. Buffering notices do not extend it.
- `connectTimeoutMs` — how long a connect or reconnect attempt may take to
  produce its first frame. Also the allowance for a camera coming back into
  view, whose decoder has to wait for a keyframe.
- `backoffMs` — the wait before each reconnect attempt: entry `n - 1` is the
  wait before attempt `n`. It doubles from the first entry up to a cap, and
  the last entry holds for every attempt after the list. A frame reaching the
  screen resets the count to attempt 1.
