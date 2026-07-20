# Dozecam

A native Android baby monitor for UniFi Protect cameras: low-latency LAN streaming, wake-on-sound, always-on display, and aggressive reconnection.

See [unifi-babycam-project.md](unifi-babycam-project.md) for the design document and roadmap.

## Building

Requires the Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`).

```sh
./gradlew :app:assembleDebug        # build the dev APK (app.dozecam.dev)
./gradlew :app:testDebugUnitTest    # run unit tests (Robolectric + Compose)
```
