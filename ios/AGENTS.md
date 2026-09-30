# AGENTS.md — iOS

Guidance for coding agents working on the iPhone and iPad app in `ios/`. The repo-wide file (product, naming, layout, product rules) is `../AGENTS.md`. Paths below are relative to the repo root.

The iOS app is a native Swift/SwiftUI counterpart of the Android app. The product rules it must meet are in `shared/spec`, which wins over this file; where the spec leaves a detail open, the Android app is the reference. The plan and its order are in #56; the platform findings that shape the design are on #58 (background survival, alerts) and #59 (media stack).

## Commands

Requires Xcode (the project builds with Xcode 26.6 in CI and Xcode 27 locally) and `brew install xcodegen`.

```sh
ios/tools/generate.sh          # ios/Dozecam.xcodeproj from ios/project.yml (+ version xcconfig)
ios/tools/lint.sh              # swift-format, strict; --fix rewrites
ios/tools/test.sh              # unit tests on an iPhone and an iPad simulator; `iphone` or `ipad` for one
ios/tools/device.sh install    # dev build onto the paired iPhone/iPad (--prod for app.dozecam)
ios/tools/device.sh launch     # launch without the debugger (device must be unlocked)
```

The Xcode project is generated and never committed: it cannot be reviewed in a diff. Change `ios/project.yml` instead, then re-run `generate.sh` (the other scripts run it for you). `Info.plist`, the entitlements file and `Config/Version.xcconfig` are generated too.

Verifying changes end to end — unit tests, then the dev build on a simulator against `tools/testbed.sh`, and what only a real device can show — is the iOS section of the `test-app-changes` skill in `.claude/skills/`.

## Variants and versions

One app target, three configurations, two schemes:

- `Debug` / `Release` → `app.dozecam` ("Dozecam"), the app relied on at night; scheme `Dozecam`.
- `Dev` → `app.dozecam.dev` ("Dozecam Dev"), installs beside it for trying changes; scheme `Dozecam Dev`.

Builds are for personal use for now: installed from Xcode or `device.sh`, signed automatically with the Apple Development certificate of team `2NBQ276R8F`. TestFlight and the App Store are parked in #63 and #71.

Versions are never edited by hand. `generate.sh` writes `CURRENT_PROJECT_VERSION` from `git rev-list --count HEAD` and `MARKETING_VERSION` from the latest `ios-v*` tag, falling back to `0.1.0`. Plain `v*` tags are Android releases and are ignored.

## Project settings

- **iOS 26.1 minimum.** AlarmKit is in iOS 26, but the `AlarmPresentation.Alert` initialiser the alert path uses starts at 26.1.
- **iPhone and iPad**, every orientation on iPad, so Split View and Stage Manager can resize the app; one scene.
- **Swift 6 language mode, strict concurrency.** The module's default actor isolation is deliberately *not* MainActor. Models are `@MainActor @Observable` classes, the counterpart of Android's ViewModels. Realtime audio render blocks and libVLC callbacks run on other threads, and a closure written inside MainActor code inherits that isolation and traps at runtime (#58). Create them in `nonisolated` or file-scope functions.
- **Info.plist:** `UIBackgroundModes: [audio]`, plus the local network, microphone (talk-back) and AlarmKit usage descriptions.
- **App Transport Security:** `NSAllowsArbitraryLoads`, because consoles present self-signed certificates under any name the user reaches them by (IP, `.local`, or a qualified DNS name, which `NSAllowsLocalNetworking` does not cover), and identity comes from pinning, not a CA. HTTPS is enforced in code instead: `ProtectApiClient.baseURL` rejects every other scheme. App Review wants a justification for this key; that belongs to #71.
- **Entitlements:** Time Sensitive Notifications only. Critical Alerts needs Apple's approval and comes with a public release (#71). Nothing is ticked by hand in the developer portal: automatic signing (`-allowProvisioningUpdates`) syncs the App ID from the entitlements file.

## Layout

- `ios/Dozecam/App/` — the `App`, `AppModel` (which destination is showing), `RootView`, `BuildInfo`.
- `ios/Dozecam/Monitor/`, `Onboarding/`, `Settings/` — the three destinations, the counterparts of Android's `MainActivity`, `OnboardingActivity` and `SettingsActivity`. Monitor is the viewer; settings is presented as a sheet over it. They are stubs until #64–#69. Debug builds accept `-startOn monitor|settings` as a launch argument, so agents can reach a destination without tapping.
- `ios/Dozecam/Protect/` — the console: `Trust/` is TOFU certificate pinning (`TofuTrustStore`, and `PinnedSessionFactory` for the pinned `URLSession`s the clients take); `ProtectCredentialsStore` keeps the sign-in in the Keychain.
- `ios/Dozecam/Storage/` — `Keychain`, a generic-password wrapper; every item is `AfterFirstUnlockThisDeviceOnly`.
- `ios/Dozecam/Monitoring/` — wake-on-sound (#67): `MonitoringService` (the counterpart of Android's `MonitoringService`, owned by `AppModel` so it outlives the viewer) runs a `CameraAudioMonitor` per monitored room over its transports (`MonitorTransports`, `TransportFallback`), feeds each room's `SoundDetector`, and decides what the speaker plays (`ListenTarget`). The pure rules (`Arming`, `MonitorPlan`, `ListenTarget`, `SoundDetector`) pass the shared fixtures.
- `ios/Dozecam/Audio/` — the speaker: `Speaker` owns the `AVAudioSession` and the always-running `AVAudioEngine` behind a `SpeakerHardware` seam, and mixes each room's `SpeakerSink`; `AudioOnlyPlayer` is the monitor's audio-only libVLC player (PCM through `libvlc_audio_set_callbacks`); `PcmRms` is the level.
- `ios/Dozecam/Player/` — live video: `VideoPlayerController` and its two implementations on one `VLCMediaPlayer` core (`VlcPlayerCore`): `VlcVideoPlayerController` for RTSP and `LivestreamVideoPlayerController` for Protect's livestream (WebSocket fMP4 → `LivestreamPipe` → `libvlc_media_new_callbacks`). `VlcRuntime` is the shared `VLCLibrary`; `LivePlayers` picks the controller for a `StreamSource`. `libvlc/` holds libVLC's C headers, vendored from the pinned VLCKit binary for the bridging header (VLCKit ships them outside its module map). Debug builds accept `-debugPlayURL rtsp://…` to show one player full-screen with its events overlaid (`Debug/DebugPlayerView.swift`).
- `ios/DozecamTests/` — Swift Testing, hosted by the app, run on simulators. `Support/Fixtures.swift` loads the golden vectors in `shared/fixtures` (#61) straight from the checkout: the simulator sees the host's file system. `Resources/` holds test-only files copied into the test bundle (the test certificates; `Support/TestCertificates.swift` says how they were made).

## iOS mechanics behind the product rules

These are the decisions from the spikes; the rules themselves are in `shared/spec`.

- **Always-on monitoring** stays alive with the screen locked through an `AVAudioSession` in `.playback` **with `.mixWithOthers`** and a running `AVAudioEngine`. Without mixing, the session cannot be reactivated from the background, so any alarm or a relaunch in the background ends monitoring (#58).
- **Waking the parent:** AlarmKit is the only path that rings through silent mode and Sleep Focus, so it is the primary alert. The fallback is the app's own tone at media volume plus a time-sensitive notification (#58).
- **Fail loud when the app dies:** iOS can terminate the app and nothing restarts it. A dead-man AlarmKit alarm a few minutes out, pushed back on every heartbeat, rings if the heartbeats stop. A time-sensitive notification is the backup (#58).
- **Media:** VLCKit 4 through SPM for live view, and audio-only libVLC players with `libvlc_audio_set_callbacks` for wake-on-sound. VLCKit's live555 has no TLS, so `rtsps://` would need an in-app TLS proxy, which would also be where TOFU pinning lives (#59); it is not built, so Protect cameras play their plain `rtsp://…:7447` link and hand-entered `rtsps://` cameras are not monitorable.
- **One speaker owner:** the viewer's video players open every camera with `:no-audio`, and the viewer's sound is the monitor's mix (`MonitoringService.setViewerAloud`). libVLC's own iOS audio output sets the session to playback without mixing and deactivates it when it stops, which would end monitoring in the background (#67).
- **Frame liveness** is the count of pictures VLC has displayed (`media.statistics.displayedPictures`, polled every 250 ms), not VLCKit's `mediaPlayerTimeChanged`: in VLCKit 4 that is a once-a-second main-run-loop timer extrapolating the last clock point, and it keeps ticking over a frozen picture. VLCKit 4 calls its delegate synchronously on libVLC's threads; `VlcPlayerCore` relays to the main queue.
- A Live Activity cannot be the ongoing "monitoring" card: it ends after 8 h and cannot be restarted from the background (#58).

## Tooling notes

- **Packages** are declared in `project.yml`. VLCKit 4 is **LGPL-2.1**, as libVLC already is on Android; it bundles live555, FFmpeg's libavcodec, dav1d and libopus. VideoLAN tags no SPM version, so it is pinned by revision: update the revision and re-copy `Dozecam/Player/libvlc` from the new binary together. Its binary target is a ~920 MB zip, cached by SPM per machine (`~/Library/Caches/org.swift.swiftpm`). Dependabot does not cover it: it reads only `Package.swift` manifests.
- CI: `.github/workflows/ios-ci.yml` runs lint and `test.sh` on `macos-26` for PRs touching `ios/`, `shared/` or its own workflow. It needs no signing secrets.
- Simulator builds from the command line are signed ad hoc (`CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM=`), as `test.sh` does, never with `CODE_SIGNING_ALLOWED=NO`: an unsigned app has no entitlements, and the Keychain refuses it with `errSecMissingEntitlement` (-34018).
