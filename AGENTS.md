# AGENTS.md

This file provides guidance to coding agents working in this repository.

## What this is

Dozecam is a native Android baby monitor for UniFi Protect cameras: low-latency libVLC RTSP live view, a wake-on-sound foreground service, honest connection state, and Protect console onboarding. Everything is LAN-only — no cloud, no accounts.

Naming: the product is "Dozecam". Store copy must not lead with "UniFi" (Ubiquiti trademark) — describe compatibility as "for UniFi Protect cameras".

## Commands

Requires the Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`). Kotlin/Java target is 17.

```sh
./gradlew :app:testProductionDebugUnitTest   # all unit tests (Robolectric + Compose)
./gradlew :app:testProductionDebugUnitTest --tests "app.dozecam.audio.SoundDetectorTest"   # one test class
./gradlew :app:assembleDevDebug              # dev APK (app.dozecam.dev, installs beside the Play app)
./gradlew :app:bundleProductionRelease       # Play bundle, upload-signed
```

Signing: every build is signed with the upload key, which lives in the repo encrypted. Decrypt once per checkout with `LIMETRIC_ENCRYPTION_SECRET` in the environment: `./tools/signing.sh decrypt`. Without it, debug builds fall back to the default Android debug key and any release packaging task fails on purpose.

Verifying changes end-to-end needs no UniFi hardware: `tools/testbed.sh` serves synthetic RTSP cameras (mediamtx + ffmpeg) that the dev build plays on an emulator, including triggering wake-on-sound. The full workflow — unit tests first, then the testbed run — is the `test-app-changes` skill in `.claude/skills/`.

## Build variants

Single Gradle module `:app`. One flavor dimension, `environment`:

- `production` → `app.dozecam`, what Play ships (only ever `productionRelease`).
- `dev` → `app.dozecam.dev`, labelled "Dozecam Dev", versioned `-dev`.

Release-build unit tests are deliberately disabled (Compose-rule Robolectric tests need `ui-test-manifest`, which is debug-only); `testProductionDebugUnitTest` is the canonical test task. All tests live in `app/src/test` and run on the JVM — Robolectric with Android resources enabled, including Compose UI tests via `createComposeRule`.

For a release: bump `versionCode`/`versionName` in `app/build.gradle.kts`. The `create-github-release` and `play-store-changelog` skills in `.claude/skills/` handle GitHub releases and Play "What's new" copy.

## Architecture

Kotlin + Jetpack Compose, minSdk 31 / targetSdk 37, all under `app/src/main/java/app/dozecam/`. Three activities (`MainActivity` for the monitor, `OnboardingActivity`, `SettingsActivity`), each with a ViewModel in `ui/`.

Two independent media stacks consume the same cameras:

- **Live view (`player/`)** — `VideoPlayerController` is the abstraction; `VlcVideoPlayerController` (libVLC, tuned for sub-second latency) plays `rtsp://`/`rtsps://` URLs, and `LivestreamVideoPlayerController` + `LivestreamPipe`/`LivestreamConnection` play Protect's WebSocket livestream. `PlaybackWatchdog` detects stalls at the frame level and drives reconnection with capped backoff; `ConnectionState` is the LIVE / RECONNECTING / OFFLINE model. Core invariant: a frozen frame must never pretend to be live. rtsps self-signed-cert prompts are auto-answered via libVLC `Dialog` callbacks.
- **Wake-on-sound (`monitoring/` + `audio/`)** — `MonitoringService` is a foreground service (mediaPlayback type) that plays the monitored camera's audio track only, via Media3 (`CameraAudioMonitor`, transports in `MonitorTransports`). `SoundDetector` applies RMS threshold / sustain / re-arm logic (`PcmRms`); `AlertSignaler` + `MainActivity` wake the screen over the lock screen with a full-screen intent. Media3 has no RTSP TLS, so wake-on-sound deliberately refuses `rtsps://` sources.
  - **Always on** — monitoring has no switch. The viewer arms it on every resume (`MonitoringState.shouldAutoArm`), and it ends only when the app is exited: the viewer's exit button and the notification's "Exit" action (`ExitReceiver`, which stops the service and sets `MonitoringState.exitRequested` for the viewer to finish itself on). The viewer's only word about monitoring is the "Not monitoring" badge, shown when a start never landed; tapping it retries, asking for the local-network grant.
  - **Alerts** — `AppSettings.alertsEnabled` gates the whole alert: off, the detector still runs (meters move, the status line says the room is loud) but nothing wakes the screen, chimes, or vibrates, and the ongoing notification says "Alerts off". The viewer's alerts button and the Alerts settings' master switch are the same stored value.
  - **Sound modes** — `AppSettings.soundMode` is one persisted setting for the one speaker: `OFF`, `ROTATING` (the viewer's one-tile-at-a-time round, `SoundRotation`, viewer only), and `ALL_ALOUD`. All aloud is listen mode: every tile on screen plays with the audible border and badge, and the service carries the same mix on with the display off. Persisted on purpose — opening the app is the ask, and there is no boot start, so a reboot alone never broadcasts anything.
  - **Listen mode** — the monitor's decoding, turned up: every monitored camera plays aloud at once, mixed out of the one speaker, so the whole house stays audible with the display off. There is no picker — `ListenTarget` yields the whole monitored set or nothing; a quiet room adds nothing to the mix, so it follows whoever is making noise. The service reads `soundMode == ALL_ALOUD` as the switch; `MonitoringState.listeningCameraIds` is what is actually audible (live monitors with decoded audio only — a room that is offline, reconnecting, or on a transport that yields no samples is not claimed), and everything that discloses listen mode reads that. `MediaAudioFocus` is the app-wide focus owner shared by the viewer and the service, since two requests from one process arrive at each other as losses. Rules that are load-bearing: a refused or lost focus request writes the sound mode back to off (from the viewer and the service alike); listen mode stands down while the viewer is audible; and listen mode assumes an awake listener: an alert for a room that is playing aloud never sounds the alarm (`ListenTarget.alertSounds`), and it lights the screen only when several rooms are in the mix, to name the one the mix cannot (`ListenTarget.alertWakesScreen`). A room nobody can hear always alarms and wakes the screen — "heard" meaning aloud with the media stream above zero and unmuted (`ListenTarget.heard`); a room that stops being heard while its detector is still triggered has its withheld alarm raised then (`MonitoringService.escalateUnheard`, on aloud-set and volume changes); and a withheld alert never displaces the one alert card while an alarm sounds for another room (`ListenTarget.alertYields`).

Supporting layers:

- **`protect/`** — UniFi Protect console clients. `ProtectPublicApiClient` (public Integration API, Protect 5.3+) is preferred; `ProtectApiClient` (legacy private API) is the fallback. Both yield the same camera ids so a console switching APIs updates entries rather than duplicating them. `TofuTrust` does trust-on-first-use certificate pinning; credentials sit in encrypted storage (`SecurePrefs`, `ProtectCredentialsStore`). `ProtectLivestreamProvider`/`ProtectLivestreamSocket` feed the livestream player.
- **`data/`** — DataStore-backed repositories (`CameraRepository`, `AppSettingsRepository`, `DetectorSettingsRepository`) and `StreamUrlValidator` for manual URL entry.
- **`ui/monitor/`** — camera grid, the control row (exit, sound mode, alerts, keep screen awake, settings), status overlay, inactivity return.

Two dependency quirks are load-bearing comments in `app/build.gradle.kts`: libVLC pins an ancient `androidx.fragment` (a modern version is forced), and the release-unit-test disablement above.
