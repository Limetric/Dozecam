# Listen target

Listen mode plays every room the monitor can hear, mixed out of one speaker.
These truth tables say what plays and how an alert behaves while it does. One
file per rule; each has `cases[]`, and camera ids are opaque strings. Sets are
JSON arrays whose order does not matter.

- `aloud.json` — which cameras play. Inputs: `requested` (listen mode is
  switched on), `speakerGranted` (the app holds the speaker, i.e. audio
  focus), `viewerAudible` (the viewer is itself playing sound), `monitored`
  (the cameras with a live stream to turn up). `expected`: the set played.
- `alert-wakes-screen.json` — whether an alert for `cameraId` lights the
  screen while `aloud` (the set playing) plays. `expected`: true or false.
- `alert-sounds.json` — whether an alert for `cameraId` sounds the alarm
  (chime, ramp, vibration) while `aloud` plays. `expected`: true or false.
- `heard.json` — which of `aloud` anyone actually hears, given
  `mediaSilenced` (the media volume is at zero or muted). `expected`: the set.
- `alert-yields.json` — whether an alert for `cameraId` is withheld entirely
  because an alarm is already sounding for `alarmingCameraId` (`null` when no
  alarm is sounding), while `aloud` plays. `expected`: true or false.
