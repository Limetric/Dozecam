# Dozecam

A native Android baby monitor for UniFi Protect cameras: low-latency LAN
streaming, wake-on-sound, always-on display, and aggressive reconnection.
Everything stays on your local network — no cloud, no accounts.

- **Live view** — libVLC RTSP playback tuned for sub-second latency,
  fullscreen and kept awake, with a dim red night theme.
- **Wake on sound** — a foreground service monitors the camera's audio track
  (Media3, audio-only) and wakes the screen over the lock screen when the
  nursery gets loud; threshold, sustain, and re-arm times are tunable against
  a live level meter.
- **Honest connection state** — a frame-level watchdog reconnects with capped
  backoff and always shows LIVE / RECONNECTING / OFFLINE with the last-frame
  time; a frozen frame never pretends to be live.
- **Protect onboarding** — sign in to the console with a local account to
  discover cameras and enable their streams automatically, over UniFi's public
  Integration API where the console supports it and the legacy private API
  otherwise (trust-on-first-use certificate pinning, credentials encrypted at
  rest), or paste an `rtsp://` (or `rtsps://`) URL manually.
- **Multi-camera** — name and manage several cameras; pick which one the
  wake-on-sound monitor watches.
- **Bedtime check** — a checklist in settings that answers "will this actually
  wake me?", with a button to fix each thing it finds, and a test alert that
  fires the real notification, the real full-screen wake and the real alarm.

## Before the first night

A baby monitor is trusted before it is tested. Dozecam can be showing a perfect
live picture and still not wake you, for reasons the picture says nothing about
— and several of them are device settings that only you can change.

Open **Settings → Before the first night** and work down anything it flags.
The checks are:

- **Alerts can reach you** — notification permission, the "Sound alerts"
  category still switched on in Android, and (on Android 14 and later)
  full-screen-intent access, without which an alert cannot light a locked
  screen and quietly becomes a banner nobody asleep will see.
- **Alerts can be heard** — the alarm volume above zero, Do Not Disturb not set
  to total silence, and at least one of the chime and the vibration switched
  on. Dozecam plays its alert with alarm usage, so a silent ringer does not
  matter; the alarm stream does.
- **Monitoring will keep running** — the service armed, Dozecam allowed to run
  unrestricted rather than left to battery optimisation, and the phone charging
  or with enough battery for the night.
- **Every camera is being heard** — per camera, connected *and* producing
  decoded audio. A camera can stream video perfectly and never send a sound, in
  which case it is monitored in name only.

Then press **Test the alert**. It raises a real alert from the real monitoring
service — the same notification, the same full-screen wake, the same chime,
ramp and vibration — so whichever of the above is quietly failing fails in
daylight, where you can fix it. The alert says it is a test, and the viewer
says so again when it wakes the screen.

If a check starts failing later, Dozecam says so once, the next time you open
the viewer, and then leaves it to the card in settings.

## Building

Requires the Android SDK (`local.properties` with `sdk.dir`, or
`ANDROID_HOME`). Android 12+ (minSdk 31).

Two flavors: `production` (`app.dozecam`, what Play ships) and `dev`
(`app.dozecam.dev`, labelled "Dozecam Dev"), so a working build installs
alongside the released app.

Every build is signed with the upload key. It lives in the repo encrypted;
decrypt it once per checkout with `LIMETRIC_ENCRYPTION_SECRET` in your
environment:

```sh
./tools/signing.sh decrypt
```

Without it, debug builds fall back to the default Android debug key and release
builds refuse to run.

```sh
./gradlew :app:assembleDevDebug              # dev APK (app.dozecam.dev)
./gradlew :app:testProductionDebugUnitTest   # unit tests (Robolectric + Compose)
./gradlew :app:bundleProductionRelease       # Play bundle, upload-signed
```

Dozecam is an independent project, not affiliated with or endorsed by
Ubiquiti Inc. UniFi and UniFi Protect are trademarks of Ubiquiti Inc.
