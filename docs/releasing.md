# Releasing Dozecam

## Build flavors

`production` builds `app.dozecam` — the Play app. `dev` builds
`app.dozecam.dev`, labelled "Dozecam Dev" and versioned `…-dev`, so a working
build installs beside the released one. Variants combine the flavor with the
build type: `devDebug`, `productionDebug`, `devRelease`, `productionRelease`.
Play only ever gets `productionRelease`.

## Signing

One upload key signs everything, debug included: a 4096-bit RSA key aliased
`dozecam-upload`, `CN=Dozecam Upload, OU=Limetric, O=Limetric`, SHA-256
fingerprint

```
28:E8:A7:F8:17:B8:44:89:43:D7:8F:10:55:78:17:A0:07:AC:F6:90:98:7A:14:31:22:FA:0B:E4:87:E9:B5:CB
```

Check an artifact against it with `apksigner verify --print-certs` (APK) or
`keytool -printcert -jarfile` (AAB). The keystore and its
passwords are committed encrypted — `keystore_dozecam_upload.keystore.enc` and
`keystore_dozecam_upload.properties.enc` — and the plaintext is gitignored.

```sh
export LIMETRIC_ENCRYPTION_SECRET=…   # the Limetric org secret
./tools/signing.sh decrypt            # writes the plaintext keystore + properties
```

Without the plaintext files a debug build falls back to the default Android
debug key, and any release packaging task fails with a pointer to that command
rather than producing an unsigned artifact. `.github/workflows/android-release.yml`
runs the same decrypt with the `LIMETRIC_ENCRYPTION_SECRET` repository secret.

To rotate the key: generate a new keystore, rewrite the properties file, run
`./tools/signing.sh encrypt`, and commit the two `.enc` files. A rotated key
only reaches Play through Play App Signing — the upload certificate has to be
re-registered there before the next upload.

## One-time setup

1. Publish `docs/privacy-policy.md` at a public URL and set it in the Play
   Console.
2. Register the upload certificate with Play App Signing.

## Every release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:testProductionDebugUnitTest :app:assembleDevDebug :app:bundleProductionRelease`
   — all green.
3. Smoke-test on a real device against a real Protect console:
   - onboarding (fingerprint prompt on first connect, camera import),
   - live view latency and the LIVE/RECONNECTING/OFFLINE overlay
     (toggle Wi-Fi to exercise it),
   - an `rtsps://…?enableSrtp` camera in the live view: the registered libVLC
     `Dialog` callbacks auto-answer the self-signed-certificate questions;
     verify the handshake completes and video renders (wake-on-sound
     deliberately refuses rtsps — Media3 has no RTSP TLS),
   - viewer sound: switch it on, confirm one grid tile at a time is audible
     and carries the speaker badge, that the badge and the sound move on
     together every 10 s, and that a camera opened on its own keeps it. Check
     the media volume first — a muted STREAM_MUSIC looks exactly like broken
     audio. `adb logcat | grep Dozecam` reports what a Protect livestream
     camera offered and what the player selected, which is the difference
     between a camera with no microphone and audio that failed to decode,
   - audio focus: with sound on, start music in another app and confirm the
     cameras go quiet and the switch turns itself off,
   - wake-on-sound end to end with the screen off (grant full-screen-intent
     special access when prompted on Android 14+),
   - battery-optimization exemption flow on at least one aggressive OEM
     (Samsung or Xiaomi) for an overnight run.
4. Upload the bundle, fill the release notes.
5. Review the pre-launch report before promoting past internal testing.

## Policy checkpoints (re-verify each submission)

- **Full-screen intent** (Android 14+): Dozecam requests the special access
  via the system settings screen; confirm current Play policy still permits
  alarm-style use for monitoring apps.
- **Battery optimization**: the app deep-links to the exemption settings
  screen and does not use the restricted direct-request permission.
- **Foreground service** (`mediaPlayback`): the Play Console FGS declaration
  must describe continuous audio monitoring of a local camera stream.
- **Data safety**: no collection, no sharing (see `store-listing/listing.md`).
- **Trademarks**: listing and graphics must not lead with "UniFi"; keep the
  non-affiliation disclaimer in the full description.
