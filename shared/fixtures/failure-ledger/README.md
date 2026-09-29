# Failure ledger

Fail loud without crying wolf: a way the monitor has stopped working counts
only once it has lasted the grace period, and is announced exactly once for as
long as it lasts. Each case in `timelines.json` is a timeline of the monitor's
health, evaluated step by step by one fresh ledger.

## Schema

- `graceMs`: the grace period every evaluation uses.
- `cases[].steps[]`: evaluated in order. `atMs` is when, from the case's start;
  the monotonic clock (for deadlines) and the wall clock (for "since") both
  read case start + `atMs`. Two steps may share a time. Tests start both clocks
  at arbitrary non-zero values, so nothing may assume they start at 0.
- `health`: what the ledger is shown.
  - `cameras[]`: the monitored cameras. `id` identifies one across steps;
    `name` is its current display name; `connection` is `"connecting"`,
    `"live"`, `"reconnecting"` (with `reconnectAttempt`, the attempt number)
    or `"offline"`. Anything but `"live"` is a camera the monitor cannot hear.
  - `networkOnline`: whether the phone has a network.
  - `battery`: `percent` (0–100) and `plugged` (on a charger), or `null` when
    no reading has arrived yet.
  - `notificationsAllowed`, `screenWakeAllowed`: whether the app may post
    notifications and wake the screen over the lock screen.
- `expect`, when present, is what that evaluation must return. A field that is
  absent is not checked; a list that is present must match exactly, in order.
  - `active`: every failure past its grace period, oldest first.
  - `announce`: the failures that crossed the grace period on this step.
  - `recovered`: the counted failures that cleared on this step.
  - `unplugged`: whether the charger was pulled since the previous step.

A failure is `reason` plus its details and `sinceMs`, when it started (from the
case's start); a recovered one adds `clearedAtMs`. Reasons:

- `"cameraUnreachable"`: `cameraId`, `name` (its current name) and
  `networkDown` (the phone had no network, so the network is to blame).
- `"lowBattery"`: `percent`, the latest reading. Low is 25% or less on no
  charger; once low it stays low until 30% or a charger.
- `"notificationsBlocked"`, `"screenWakeBlocked"`: no details.
