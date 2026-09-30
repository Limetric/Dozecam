# Privacy

## LAN only

- Dozecam talks only to the user's own Protect console and cameras, over the network they are on. There is no Dozecam server, no cloud relay, no account, no analytics, no crash reporting and no advertising.
- Anything that leaves the device goes to the console or a camera: sign-in, discovery, streams, and talk-back audio.
- Reaching the cameras from away is the user's business, through their own tunnel home (a VPN counts as a network that can reach the LAN); Dozecam adds no remote access of its own.
- The platform's local-network permission is required; without it nothing connects and monitoring does not arm.
- Console credentials are sent only to the console they belong to, over its pinned TLS connection ([protect.md](protect.md#certificate-pinning-tofu)). A key or session minted for one console is never sent to another.

## What the device stores

Everything stays on the device, private to the app, and is not included in the platform's backups.

| Data | Protection | Why |
|---|---|---|
| Camera list: names, stream URLs, console identity, enabled | Encrypted | Stream URLs embed Protect's stream tokens, which grant access to the camera |
| Console credentials: address, username, password, API key | Encrypted | Secrets |
| Certificate pins (fingerprint per endpoint) | Not encrypted | Public values |
| Settings: alerts, sound mode, detector, display | Not encrypted | Not secret |

Not stored: video, audio, snapshots or recordings of any kind, and a history of alerts. Pauses and an exit in progress are held in memory only ([monitoring-lifecycle.md](monitoring-lifecycle.md)).

The microphone is used only while talk-back is held, and only while the viewer is on screen.

Android reference: `SecurePrefs`, `EncryptedCredentialsStore`, `CameraRepository`, `TofuTrustStore`, `android:allowBackup="false"`.

- **Android:** encryption uses an Android Keystore master key (AES-256-GCM). When the Keystore is unavailable or corrupted, which happens on some devices, the data falls back to a plain app-private file and is moved back into encrypted storage, and removed from the plain file, on the next healthy start.
- **iOS:** the same data, stored with the platform's equivalent protection; the storage is designed in #64.
