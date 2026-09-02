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
  - **Listen mode** — that same decoding, turned up: one monitor at a time may play its camera aloud so the nursery stays audible with the display off. `ListenTarget` decides which (one camera, chosen — never rotated, never substituted), `MonitoringState.listenRequest` holds the ask *and* its camera in one in-memory value — deliberately one value, so the switch cannot reach the service ahead of the room it names; `AppSettings.listenCameraId` remembers the choice for the picker, and nothing else. `MediaAudioFocus` is the app-wide focus owner shared by the viewer and the service, since two requests from one process arrive at each other as losses. Rules that are load-bearing: a refused focus request switches the setting back off; listen mode stands down while the viewer is audible; and an alert for the room already playing aloud chimes without lighting the screen.

Supporting layers:

- **`protect/`** — UniFi Protect console clients. `ProtectPublicApiClient` (public Integration API, Protect 5.3+) is preferred; `ProtectApiClient` (legacy private API) is the fallback. Both yield the same camera ids so a console switching APIs updates entries rather than duplicating them. `TofuTrust` does trust-on-first-use certificate pinning; credentials sit in encrypted storage (`SecurePrefs`, `ProtectCredentialsStore`). `ProtectLivestreamProvider`/`ProtectLivestreamSocket` feed the livestream player.
- **`data/`** — DataStore-backed repositories (`CameraRepository`, `AppSettingsRepository`, `DetectorSettingsRepository`) and `StreamUrlValidator` for manual URL entry.
- **`ui/monitor/`** — camera grid with rotating viewer sound (one audible tile at a time, `SoundRotation`), the listen-mode control and its camera picker, status overlay, inactivity return.

Two dependency quirks are load-bearing comments in `app/build.gradle.kts`: libVLC pins an ancient `androidx.fragment` (a modern version is forced), and the release-unit-test disablement above.
