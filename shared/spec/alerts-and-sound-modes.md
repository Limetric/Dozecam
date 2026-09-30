# Alerts and sound modes

## The detector

One detector per monitored camera, fed the level of every decoded audio buffer.

- **Level:** normalised RMS of the buffer, 0 to 1: the square root of the mean squared sample over full scale (a 16-bit sample divided by 32768), clamped to 1. An empty buffer is 0. A level is *loud* when it is at or above the threshold.
- **States:** armed → building → triggered.
  - Armed: a loud level starts building, timed from that sample.
  - Building: any level below the threshold goes back to armed (a single thud never wakes anyone). A loud level at least *sustain* after building started fires the trigger, once, and moves to triggered.
  - Triggered: re-arms only after the level has stayed below the threshold for *re-arm* straight, timed from the first quiet sample. Any loud level restarts that timer.
- Settings apply to the next level fed in.

| Setting | Default | User range |
|---|---|---|
| Threshold | 0.10 | 0.01 – 0.5 |
| Sustain | 1.5 s | 0.5 – 5 s |
| Re-arm (quiet) | 10 s | 2 – 30 s |

A level is only known once a buffer has been decoded on the current connection; before that the level is *unknown*, never 0, and a meter must not show one. A connection that stops being live forgets its level.

Android reference: `SoundDetector`, `PcmRms`, `DetectorSettings`, `CameraMonitorState.withConnection`.
Fixtures: [`shared/fixtures/sound-detector/`](../fixtures/sound-detector/).

- **iOS:** libVLC delivers 32-bit float samples (48 kHz mono, #59); the RMS is taken over them with ±1.0 as full scale, which is the same scale. The spike measured the testbed's noise at 0.35 on both platforms. While an AlarmKit alarm rings, the audio session is interrupted and the detectors hear nothing for its length (#58).

## The sound alert

A trigger raises the alert for that camera, named by its current name.

- **alertsEnabled gates the whole alert.** Off, the detector keeps running (meters move, the status line says a room is loud, the speaker still plays) but nothing reaches anyone: no card, no screen, no chime, no vibration. The ongoing status line says "Alerts off". Switching alerts off while an alert is up stops the alarm and removes the alert and failure cards. The viewer's alerts button and the Alerts settings' master switch are the same stored setting. Default on.
- **Order:** the alert card first, which wakes the screen and opens that camera over the lock screen; then the alarm.
- **The alarm is the whole audible surface.** The alert's notification is silent; the app plays the alarm itself.
  - Plays as an alarm, so a silent ringer and Do Not Disturb do not mute it. Its tone is the phone's alarm sound unless the user picks another, never a notification tone.
  - Ramp (default on): starts at 15 % of the ceiling and climbs to the full ceiling over 5 s. Off, every burst is at the ceiling.
  - Repeats a burst every *repeat interval*: default 8 s, user range 3 – 30 s. Chime and vibration are separate switches, both default on.
  - Ceiling: a fraction of the phone's alarm volume, default 100 %, user range 10 – 100 %. The app never changes the phone's own alarm volume.
  - **Latched:** the detector re-arming does not stop it. A person does: a touch or key press on the viewer, or dismissing the alert card. Otherwise it gives up **5 min after the latest trigger**, so a room still going off keeps it alive.
  - One alarm at a time. A trigger from another room while it sounds re-points it at that room without restarting the ramp.
- A camera leaving the monitored set takes its alert with it ([monitoring-lifecycle.md](monitoring-lifecycle.md#which-cameras-are-monitored)).
- **Bedtime test:** the real alert, down the same path, with only the wording changed. Refused while any room is crying (alarming, or its detector triggered). A real alert ends a test's alarm.

Android reference: `MonitoringService.raiseAlert`, `AlertSignaler`, `AlarmSchedule`, `AppSettings`.

- **Android** wakes the screen with a full-screen intent and plays on the alarm stream.
- **iOS** has no full-screen intent. The primary alert is an **AlarmKit** alarm scheduled 1 s ahead (AlarmKit rejects "now"), which rings full-screen through silent mode and Sleep Focus. When AlarmKit is not authorised, the fallback is the app's own tone through the running audio engine at media volume plus a time-sensitive notification, which is silent in silent mode and suppressed by Sleep Focus (#58). With AlarmKit (#68):
  - The card is posted as well, as a time-sensitive notification: it is what names the room and opens it when tapped.
  - The alarm is latched and one at a time, as above. The system's Stop is the acknowledgement, as are a touch on the viewer and opening or dismissing the card.
  - A trigger from another room replaces the alarm with one titled for that room, which rings again about a second later: AlarmKit cannot retitle a ringing alarm.
  - AlarmKit rings continuously at the system alarm volume with its own vibration, so ramp, repeat interval, ceiling and the chime and vibration switches do not apply to it.
  - The 5-minute give-up is the app stopping the alarm.
  - A room rings with the system alarm sound; a failure and the dead-man ring with the bundled failure tone.
  - The fallback keeps ramp, repeat and ceiling, applied under the media volume (so it is silent at zero), with a bundled room tone, since an app cannot play the system alarm sounds.
  - `alertWakesScreen` false (the room is the only one heard) posts the card without interrupting.

## Sound modes

One stored setting for the one speaker, shared by the viewer and the monitor. Default off, and remembered across launches, including all aloud: opening the app is the ask, and since there is no boot start, a reboot alone never broadcasts a room.

| Mode | Viewer on screen | Screen off / viewer closed |
|---|---|---|
| **Off** | Silent | Silent |
| **Rotating** | One tile at a time is audible, in grid order, 10 s each | Silent: rotation is the viewer's only |
| **All aloud** (listen mode) | Every tile plays, with the audible border and badge | The monitor plays every monitored room at once, mixed out of the one speaker |

- There is no room picker. A quiet room adds nothing to the mix, so what comes out follows whoever is making noise.
- Every surface that says a room is aloud reads what is actually audible (below), never the setting.
- The ongoing card offers "Stop playing aloud" while rooms are aloud. It sets the mode to off; monitoring carries on. Nothing on a notification can switch listen mode *on*.
- Nothing left to hear does not switch the mode off. Every enabled room paused stands listen mode down (and releases the speaker) without changing the setting, so resuming a room picks the mix back up.

Android reference: `SoundMode`, `SoundRotation`, `StopListeningReceiver`.

## The speaker (audio focus)

The app holds the speaker as one owner for the viewer and the monitor together; two requests from one app would each read the other as a loss.

- **Refused, or lost for good** (another app takes it permanently, headphones unplugged): the sound mode is written back to **off**, from the viewer and the monitor alike. A sound button that is on while the phone is silent is worse than one that did not take. The viewer does not jump back in when the other app is done.
- **Lost for a moment** (a call, a navigation prompt): the app goes silent and plays again when the speaker comes back; the setting is unchanged. Asked to duck, it goes silent instead, because a room faintly quiet under another app's audio reads as a settled room.
- The viewer holds the speaker only while it is on screen, its mode is not off, and there is an unpaused camera.

Android reference: `MediaAudioFocus`, `MainActivity` (viewer), `MonitoringService.stopListening`.

- **iOS** has no audio focus. The session mixes with other apps (`.mixWithOthers`, required to stay alive, see [monitoring-lifecycle.md](monitoring-lifecycle.md#staying-alive)), so another app's audio neither interrupts it nor is interrupted by it, and nothing asks it to duck (#67):
  - **Lost for a moment** is an audio-session interruption (a call, Siri, an alarm): silent until it ends, then the session is reactivated whether or not iOS suggests resuming, since monitoring lives only while it runs. A reactivation that fails is lost for good, and a failure for [failure-alerts.md](failure-alerts.md) (#68).
  - **Lost for good** is a route change with the old device gone (headphones unplugged, a Bluetooth speaker out of range).
  - **Refused** is the session failing to activate.
  - **The viewer plays through the monitor.** Its cameras' sound comes from the monitor's audio-only players, mixed out of the same engine, not from its video players: libVLC's own iOS audio output sets the app's session to playback *without* mixing whenever it runs and deactivates it when it stops, which would end monitoring the next time the app went to the background. So the app is one owner of the speaker in fact as well as in rule, and a camera the monitor cannot hear is silent in the viewer too.

## Listen mode: aloud and heard

**Aloud:** the rooms coming out of the speaker. It is every *audible* monitored room when all of these hold, and nothing otherwise:

- the mode is all aloud, and not every enabled room is paused;
- the speaker is granted;
- the viewer is **not audible** (on screen, with its mode not off and the speaker granted). Listen mode stands down while the viewer plays, since the two would play the same nursery a second apart, and the room somebody is looking at is the better one to hear. It comes back the moment the viewer goes to the background.

A monitored room is *audible* when it is live **and** has decoded at least one buffer on the current connection. A room connecting, reconnecting, offline, or on a transport that plays without ever yielding a sample is not claimed.

**Heard:** the aloud rooms, or nothing when the media volume is zero or muted, since the mix is then playing into nothing. The output route (speaker, Bluetooth, headphones) is not second-guessed.

Listen mode assumes an awake listener. For an alert from room *R*:

| Rule | Decision |
|---|---|
| `alertSounds` | The alarm sounds unless *R* is heard. |
| `alertWakesScreen` | The card wakes the screen unless *R* is the **only** room heard. With several rooms in the mix, the screen names the one the mix cannot. |
| `alertYields` | The alert is dropped altogether when *R* is heard **and** an alarm is sounding for a different room. There is one alert card, and dismissing it acknowledges the alarm; a heard room must not replace the card of a room nobody can hear. The alarming room itself may refresh its card. |

A room nobody can hear always alarms and wakes the screen. A test alert is never a heard room, and a test alarm never makes a real room's alert yield.

**Escalating a room no longer heard:** a room that was heard when its cry began had its alarm withheld, and its detector will not fire again until it re-arms. So whenever the heard set shrinks (the aloud set changes, the media volume changes, or the stream is muted), every room that dropped out of it while its detector is still triggered has its alert raised then, by the rules above.

Android reference: `ListenTarget` (`of`, `heard`, `alertSounds`, `alertWakesScreen`, `alertYields`), `MonitoringService.escalateUnheard`, `MonitoringState.listeningCameraIds`.
Fixtures: [`shared/fixtures/listen-target/`](../fixtures/listen-target/).

- **iOS:** the same truth tables (#67, #68). "Media volume zero" is the audio session's output volume. On iOS one AlarmKit alarm both sounds and takes the screen, so an alert that must wake the screen without sounding (several rooms heard) needs its own presentation; #68 decides it.
