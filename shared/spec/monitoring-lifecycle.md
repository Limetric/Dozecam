# Monitoring lifecycle

Monitoring means listening to every monitored camera's audio, with the screen on or off, so that a room getting loud raises an alert ([alerts-and-sound-modes.md](alerts-and-sound-modes.md)) and a monitor that stops working says so ([failure-alerts.md](failure-alerts.md)).

## Always on

- Monitoring has no switch. It arms whenever the viewer comes to the front, and it ends only when the app is exited.
- The viewer arms on **every** resume, not only on launch. Arming is skipped when:
  - there is nothing to listen to (no enabled, unpaused camera with any way to hear it; see [connection-state.md](connection-state.md#the-monitors-transports));
  - monitoring is already running;
  - an exit is in progress (see below);
  - local-network access is not granted, since every connection would fail. Arming resumes on the next resume after the grant.
- Onboarding (on completion) and settings (when a camera is switched on or added) arm it too, under the same conditions, since only they know the set stopped being empty.
- Settings stops the monitor when it leaves no enabled camera that can be listened to. Otherwise a monitor with nothing to listen to (every camera paused, say) stays running but idle, holding nothing that costs battery, so a camera switched straight back on is picked up rather than landing on a monitor on its way out.
- Automatic arming shows no permission prompts and no setup dialogs. A missing alert grant never blocks detection; it is reported by the night checklist and by the failure alerts.

Android reference: `MonitoringState.shouldAutoArm`, `shouldArmMonitoring`, `MonitoringStarter`.

## Which cameras are monitored

- Every enabled camera that is not paused, each independently: one camera reconnecting is never "monitoring is down".
- **Enabled** is the durable per-camera setting. **Paused** is a viewer action for tonight ("this child is still up"): a paused camera has no picture, no sound, no detector, no alert and no failure. Pauses live in memory only; exiting the app, or the process dying, brings every room back.
- A camera switched off, paused or deleted takes its alert with it: its alarm stops and its alert card is removed. It leaves no "recovered" note (see [failure-alerts.md](failure-alerts.md#recovery)).
- Changes to the set (enable, pause, rename, a different console signed in) apply to the running monitor without restarting it.

Android reference: `MonitoringState.pausedCameraIds`, `MonitorPlan`, `MonitoringService.reconcile`.

## Exit

Exit is the one way to stop monitoring, and it means *leave Dozecam*:

- The monitor stops, and **everything monitoring posted is taken down**: the ongoing card, the sound alert card, the failure card, the unplugged notice, and any alarm. Nothing it posted may outlive the app.
- The speaker is released. The sound mode setting is left as it was, as are all other settings.
- Every Dozecam screen still open closes itself. While the exit is in progress nothing re-arms, even a settings screen that sees the monitor go.
- Pauses are cleared, so the next open watches every room.
- The next time the viewer opens, it clears the exit and arms again.

Ways out: the viewer's exit button (after a confirmation) and the ongoing card's "Exit" action. Both run the same exit, so neither leaves behind a card the other would have removed.

Android reference: `MonitoringService.exit`, `ExitReceiver`, `MonitoringNotifications.cancelAll`, `MonitoringState.exitRequested`.

- **iOS:** there is no ongoing card, so exit is the viewer's button only. Exit also cancels the dead-man alarm (below), since an alarm for an app the user closed would be a false alarm (#68).

## The "Not monitoring" badge

The viewer says nothing about monitoring while it runs. It speaks only when a start never landed:

- Shown when monitoring is not running although there is something to monitor, and only after **3 s** of the viewer being open, so a normal cold start (permission check, settings read, service launch) never flashes it.
- Styled as an error, not as an idle state.
- Tapping it retries. If local-network access is what is missing, it opens the night checklist instead.
- Not shown when there is nothing that could be monitored; the empty state and the unmonitorable-camera notice explain that.

Android reference: `NotMonitoringBadge` in `ui/monitor/MonitorScreen.kt`.

## Staying alive

Monitoring must survive the screen being off and the app being in the background all night. Each platform does whatever that takes, and must make its death loud when it cannot prevent it.

- There is no start at boot: after a reboot, nothing monitors until the app is opened. Opening the app is the ask.
- While monitoring, an ongoing status line shows what the monitor is doing (rooms listened to, rooms aloud, alerts off, failures, the last recovered failure). While healthy it carries proof of life: the loudest room's level in 10 coarse steps (full scale at RMS 0.5) and the minute it was last posted. It reposts on any text change at once, on level changes at most every **2.5 s**, and at least once a minute from a heartbeat, so a wedged process visibly goes stale instead of looking healthy. Unhealthy states carry no timestamp, so "offline" never looks freshly confirmed.

Android reference: `MonitoringService`, `StatusHeartbeat`, `MonitoringStatus`.

| | Android | iOS |
|---|---|---|
| Keep running with the screen off | Foreground service of type `mediaPlayback`, a partial wake lock held only while there is a camera to decode | `AVAudioSession` `.playback` **with `.mixWithOthers`** and an always-running `AVAudioEngine`. Without mixing, the session cannot be reactivated from the background after an interruption (such as an alarm), and monitoring ends (#58) |
| After the process is killed | The system restarts the service (sticky) with the same enabled cameras; pauses are lost, which is the safe direction | Nothing restarts the app. A **dead-man AlarmKit alarm** a few minutes ahead (3 min tested) is pushed back on every heartbeat, so it rings full-screen, through silent mode and Sleep Focus, when the heartbeats stop; a time-sensitive notification is the backup (#58, #68) |
| Ongoing status | The foreground service's ongoing notification, with "Exit" and, while rooms are aloud, "Stop playing aloud" | No ongoing card. A Live Activity ends after 8 h and cannot be restarted from the background, so it cannot carry the guarantee (#58). The status line lives in the viewer; the dead-man alarm is what makes a dead monitor loud |
