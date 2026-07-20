# Releasing Dozecam

## One-time setup

1. Generate the upload keystore (keep it out of git; `.gitignore` already
   covers `*.keystore` and the properties file):

   ```sh
   keytool -genkeypair -v -keystore keystore_dozecam_upload.keystore \
     -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore_dozecam_upload.properties` in the repo root:

   ```properties
   storeFile=keystore_dozecam_upload.keystore
   storePassword=…
   keyAlias=upload
   keyPassword=…
   ```

   When this file exists, `:app:bundleRelease` and `:app:assembleRelease`
   produce upload-signed artifacts; without it they stay unsigned so any
   checkout still builds.

3. Publish `docs/privacy-policy.md` at a public URL and set it in the Play
   Console.

## Every release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:bundleRelease`
   — all green, no lint-vital errors.
3. Smoke-test on a real device against a real Protect console:
   - onboarding (fingerprint prompt on first connect, camera import),
   - live view latency and the LIVE/RECONNECTING/OFFLINE overlay
     (toggle Wi-Fi to exercise it),
   - an `rtsps://…?enableSrtp` camera in the live view: the registered libVLC
     `Dialog` callbacks auto-answer the self-signed-certificate questions;
     verify the handshake completes and video renders (wake-on-sound
     deliberately refuses rtsps — Media3 has no RTSP TLS),
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
