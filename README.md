# Dozecam

A native Android baby monitor for UniFi Protect cameras: low-latency LAN
streaming, wake-on-sound, always-on display, and aggressive reconnection.
Everything stays on your local network — no cloud, no accounts, no analytics.

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

See [unifi-babycam-project.md](unifi-babycam-project.md) for the design
document and roadmap, [docs/releasing.md](docs/releasing.md) for the release
process, and [docs/privacy-policy.md](docs/privacy-policy.md) for the privacy
policy.

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
