# Failure alerts

The monitor fails loud: every way Dozecam can stop being a baby monitor while it is armed is announced, once, after a grace period. An alarm for every brief reconnect would train people to ignore it, which is worse than no alarm, so two rules are the whole design:

1. **Grace:** nothing counts until it has lasted the grace period.
2. **Once:** what counts is announced exactly once for as long as it lasts.

Android reference: `FailureLedger`, `MonitoringService.judge`.
Fixtures: [`shared/fixtures/failure-ledger/`](../fixtures/failure-ledger/).

## Causes

Judged together, on every change in any input and on a heartbeat, since crossing the grace period is an event of its own. Grants can be withdrawn in system settings without the app being told, so they are asked afresh on every judgement.

| Cause | Counts while | Identity |
|---|---|---|
| **Camera not live** | A monitored camera's connection is anything but live (connecting, reconnecting, offline). When the phone has no network, the reason is "no network" rather than the camera, and the wording says so. | One per camera |
| **Battery low** | Not on a charger, and at or below **25 %**. Hysteresis: once low, it stays low until the battery reaches **30 %** (25 + 5) or a charger is connected, so a reading hovering on the line cannot raise it again and again. No battery reading yet is not a failure. | One |
| **Notifications blocked** | The app may not post notifications, so no alert card can be shown. | One |
| **Screen wake withdrawn** | The app may not wake the screen with an alert. | One |

- A cause that clears and comes back is a new failure, with a new grace period and a new announcement. A camera that drops, returns and drops again is announced twice.
- While a failure lasts, its details follow along (a renamed camera, the current battery percentage), but its start time does not move.
- Paused and switched-off cameras are not monitored, so they are never failures.

## Grace and announcement

- **Grace period:** default **60 s**, user range **30 s – 5 min** (Alerts settings). One setting for every cause. Timed on a monotonic clock, so setting the phone's clock moves no deadline; the "since" shown to the user is wall-clock time.
- A failure that clears inside its grace period never happened, as far as anyone is told: no announcement, no note.
- When one or more failures cross the grace period, the announcement lists **everything** currently past grace, not only the new ones.
- **Gated by alertsEnabled**, like any alert. With alerts off, nothing wakes anyone, but the failure is still shown (below). A failure that crossed its grace period with alerts off, or whose card was removed by switching alerts off, is announced when alerts come back on, if it still stands.

## How it is said

- The same delivery as a sound alert: its own card, which wakes the screen and opens the viewer on its failure notice (no camera), and the latched alarm ([alerts-and-sound-modes.md](alerts-and-sound-modes.md#the-sound-alert)).
- Its **own bundled tone** and its **own wording**, so it is never mistaken for a room getting loud. The wording is one set of strings used everywhere a failure is named: the card, the ongoing status line and the viewer's notice.
- **A room outranks the monitor.** A failure announced while a room's alarm is sounding leaves that alarm, and its identity, as they are; the failure card still appears. A room's trigger while the failure tone is sounding replaces it with the room's alarm.
- Independently of alerts, every failure past grace is always on the ongoing status line and the viewer's notice, with its start time.

Android reference: `MonitoringNotifications.postFailure`, `FailureWording`, `AlertSignaler.MONITORING_FAILURE`, `res/raw/monitoring_failure.wav`.

## Recovery

- When a failure that was announced (past grace) clears, it leaves a note: the most recent one is shown on the status line as "earlier: …, cleared at …", so a camera gone for twenty minutes at 3 am is known about in the morning.
- A camera that stops being monitored (paused, switched off, deleted) ends its failure without a note: "back" said of a room that may still be dark is the wrong reassurance.
- When some failures clear and others remain, the failure card is updated to list what is left, without waking the screen or sounding again.
- When none remain, the failure card is removed, and the alarm is stopped **only if it is the failure's own**. A room's alarm is never silenced by a camera coming back or a charger going in.

## Unplugging

Pulling the charger while armed (plugged, then unplugged, between two judgements) posts a milder notice on the quiet status channel: no screen, no sound. It names the battery level and the level at which the battery alarm will sound (25 %). Exit removes it with every other card.

Android reference: `FailureLedger.Update.unplugged`, `MonitoringNotifications.postUnplugged`.

## Platform differences

- **Screen wake withdrawn** is Android's full-screen-intent access (Android 14 and later; before that it comes with the notification permission). **iOS:** the nearest equivalent is AlarmKit authorisation, the grant that decides whether an alert can ring through silent mode and Sleep Focus; notification authorisation stands in for "notifications blocked" (#58, #68, #69).
- **The app itself dying** is a cause the ledger cannot see, since it runs inside the app. Android is restarted by the system (see [monitoring-lifecycle.md](monitoring-lifecycle.md#staying-alive)). **iOS:** nothing restarts it, so the dead-man AlarmKit alarm is the announcement, and its lead time (3 min tested) is its grace period (#58, #68).
- **Battery:** iPadOS reports the battery in 5 % steps (#58). The thresholds are unchanged.
- **iOS:** the failure alert uses the same alert path as the sound alert on iOS (AlarmKit, with the fallback), with its own sound and wording (#68).
