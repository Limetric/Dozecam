---
name: test-app-changes
description: Verify Dozecam changes without a UniFi Protect console. Use when asked to test, verify, smoke-test, or demonstrate app changes end-to-end, or to see the app running — unit tests first, then a live run against the local RTSP testbed (tools/testbed.sh) for changes that affect runtime behavior: an Android emulator for android/, an iOS simulator for ios/.
---

# Test App Changes

The standard verification ladder for this repository. No UniFi hardware is
required at any rung; the parts that would need a real Protect console are
listed at the end so their absence is stated rather than discovered.

The gates below cover the Android app; the iOS app has its own path in
"iOS" further down. Commands run from the repo root; Gradle runs inside
`android/`.

## Gate 1 — unit tests (always)

```sh
(cd android && ./gradlew :app:testProductionDebugUnitTest)
```

When sources under `android/app/src/dev` or `android/app/src/testDev` changed, also:

```sh
(cd android && ./gradlew :app:testDevDebugUnitTest --tests "app.dozecam.dev.*")
```

Stop here for changes with no runtime surface (docs, build plumbing,
pure-logic refactors already covered by tests).

## Gate 2 — live run against the RTSP testbed

For changes touching the player, monitoring, connection state, or monitor UI.
`tools/testbed.sh` serves two synthetic cameras over RTSP (mediamtx + ffmpeg,
`brew install mediamtx ffmpeg`): `nursery` shows a running counter and has
switchable audio (silence ↔ loud 660 Hz tone that trips the default
wake-on-sound threshold); `porch` is always silent. Full command list:
`tools/testbed.sh` with no arguments.

Target an **emulator**, never a physical device that happens to be attached —
always pass an explicit serial (`-s emulator-5554`) to every adb command.

```sh
tools/testbed.sh start                # prints the rtsp:// URLs (port 18554)

# -port pins the serial: without it, a second emulator silently takes the next
# free port and every later command would hit whatever already owns 5554.
# If this fails with "port in use", pick another even port and use its serial.
emulator -avd Pixel_8a -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot &
adb -s emulator-5554 wait-for-device
# poll until: adb -s emulator-5554 shell getprop sys.boot_completed → 1

(cd android && ./gradlew :app:assembleDevDebug)
adb -s emulator-5554 install -r android/app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb -s emulator-5554 shell pm grant app.dozecam.dev android.permission.POST_NOTIFICATIONS
# API 37+ images gate LAN access behind a runtime permission; without this the
# streams never connect. Older images (≤36) reject the grant — that's fine.
adb -s emulator-5554 shell pm grant app.dozecam.dev android.permission.ACCESS_LOCAL_NETWORK || true

tools/testbed.sh seed -s emulator-5554      # adds both cameras via TestbedSeedReceiver
adb -s emulator-5554 shell am start -n app.dozecam.dev/app.dozecam.MainActivity
```

### Verify live video

Wait ~10 s, then take two screenshots a few seconds apart
(`adb -s emulator-5554 exec-out screencap -p > shot.png`). Both tiles must
show LIVE, and the nursery counter / porch timecode must have advanced between
the shots — an unchanged frame means the stream is not actually playing.
The status pill should read "Watching for sound" (monitoring auto-arms on resume).

### Verify wake-on-sound

```sh
tools/testbed.sh noise on
sleep 12   # 1.5 s sustain + decode pipeline + margin
adb -s emulator-5554 shell dumpsys notification --noredact |
  grep -E "NotificationRecord.*app\.dozecam\.dev.*sound_alerts_2"
adb -s emulator-5554 exec-out screencap -p > alert.png
tools/testbed.sh noise off
```

Success: an active `NotificationRecord` (not merely the channel definition —
`sound_alerts_2` appears in dumpsys as soon as monitoring starts, so a bare
channel grep passes even when detection is broken) with id 2 on channel
`sound_alerts_2`, and the screen shows the "Sound detected — Testbed nursery"
alert.

### Teardown

```sh
tools/testbed.sh stop
adb -s emulator-5554 emu kill
```

## Troubleshooting

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the emulator has a build signed with
  the other key (upload vs. debug). `adb -s emulator-5554 uninstall
  app.dozecam.dev`, then install again.
- Tiles stuck RECONNECTING: run `tools/testbed.sh status`. If someone
  overrode `DOZECAM_TESTBED_PORT`, never pick 8554 — the emulator's own gRPC
  service listens there and shadows the testbed from inside the guest.
- Seeding silently does nothing: check `adb -s emulator-5554 logcat -d -s
  TestbedSeed`; the receiver logs what it seeded or why it refused.
- Re-seeding after a testbed restart is safe: camera ids are stable, so
  entries update in place instead of duplicating.

## iOS

Needs Xcode and `brew install xcodegen`. Details: `ios/AGENTS.md`.

### Gate 1 — lint and unit tests (always)

```sh
ios/tools/lint.sh                 # --fix rewrites
ios/tools/test.sh                 # iPhone and iPad simulators; `iphone` or `ipad` for one
```

`test.sh` prints little on success; a failure names the test. Swift Testing
results land in `ios/build/DerivedData/Logs/Test/*.xcresult`
(`xcrun xcresulttool get test-results summary --path <bundle>`).

### Gate 2 — the dev build on a simulator

The simulator shares the Mac's network, so the testbed is at
`rtsp://127.0.0.1:18554/<camera>` with no port mapping.

```sh
tools/testbed.sh start
SIM=$(xcrun simctl list devices available | grep -m1 'iPad Pro' | grep -oE '[0-9A-F-]{36}')
xcrun simctl boot "$SIM" 2>/dev/null; open -a Simulator
xcodebuild build -project ios/Dozecam.xcodeproj -scheme "Dozecam Dev" \
  -destination "id=$SIM" -derivedDataPath ios/build/DerivedData -quiet CODE_SIGNING_ALLOWED=NO
xcrun simctl install "$SIM" "ios/build/DerivedData/Build/Products/Dev-iphonesimulator/Dozecam.app"
xcrun simctl launch --terminate-running-process "$SIM" app.dozecam.dev
xcrun simctl io "$SIM" screenshot shot.png
tools/testbed.sh stop
```

(`ios/tools/test.sh` has already generated the project.) Launch arguments
after the bundle id reach the app's `UserDefaults`. Nothing can tap in a
simulator, so debug builds (`Dev`, `Debug`) take `-startOn monitor` or
`-startOn settings` to open a destination directly; onboarding is the default.

### Only a real device shows

Background audio and suspension with the screen locked, AlarmKit alarms,
time-sensitive notifications, the local-network permission prompt, and
hardware video decoding. The simulator decodes video in software, so it also
hides that **VideoToolbox on a device rejects the testbed's x264 streams**
(`kVTVideoDecoderBadDataErr`); device tests of live view need camera-like
streams (`spikes/ios-media/tools/streams.sh` publishes some from the Mac's
hardware encoder). Install on a device with `ios/tools/device.sh install`,
launch with `ios/tools/device.sh launch` (the device must be unlocked), and
say in the report which of these went untested.

## What this cannot verify

Needs a real Protect console, so it stays covered by MockWebServer unit tests
(`android/app/src/test/java/app/dozecam/protect/`) instead: console onboarding and
credential flows, the Protect livestream WebSocket player, rtsps/TLS with
TOFU certificate pinning, and real camera codecs/latency. Say so in the
verification report rather than implying full coverage.
