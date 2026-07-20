# Dozecam Privacy Policy

_Last updated: 2026-07-20_

Dozecam is a baby monitor app that connects to UniFi Protect cameras on your
own local network. It is designed so that your data never leaves your devices.

## What Dozecam collects

Nothing. Dozecam has no accounts, no analytics, no advertising, no crash
reporting, and no servers. We (the developer) receive no data from the app.

## What stays on your device

- **Console credentials.** If you use console sign-in, the address, username,
  and password of your UniFi Protect console are stored on the device,
  encrypted with a key held in the Android Keystore. They are sent only to
  your own console, over TLS, on your local network.
- **Camera stream addresses.** RTSP stream URLs (which embed access tokens
  issued by your console) are stored on the device, encrypted the same way.
- On the rare device where the Android Keystore is unavailable or corrupted,
  Dozecam falls back to storing these values in ordinary app-private storage
  (readable only by the app, still never leaving the device) and folds them
  back into encrypted storage once the Keystore recovers.
- **Settings.** Detector tuning, theme, and alert preferences are stored on
  the device.
- **Certificate fingerprints.** The TLS certificate fingerprint of your
  console, pinned on first connection so a changed certificate is detected.

Android's backup and device-transfer mechanisms are disabled for Dozecam, so
none of the above is copied off the device by the system.

## Audio and video

Live camera audio and video are received from your console over your local
network and processed entirely on the device — video for display, audio to
compute a loudness level for wake-on-sound alerts. Streams are never recorded,
stored, or transmitted anywhere by Dozecam.

## Third parties

Dozecam communicates only with the UniFi Protect console you configure. There
are no third-party services, SDKs that phone home, or data processors.

## Deleting your data

Clear the app's storage (or uninstall it). Everything Dozecam stores is on the
device.

## Changes

If this policy changes, the updated version ships with the app and is
published at the same location as this document.

## Contact

Questions about this policy: open an issue at
https://github.com/Limetric/Dozecam/issues.
