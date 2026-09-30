# Protect consoles

Dozecam onboards cameras from a UniFi Protect console on the LAN, or takes a stream URL typed in by hand. One console is signed in at a time.

## Signing in and discovery

- The user gives the console address, a username and a password. Sign-in goes through the console's own (legacy, private) login, which every Protect version answers.
- **The public Integration API is preferred** (Protect 5.3 and later). It needs an API key: a stored key for the same console and user is tried first, and a new key, named "Dozecam", is created only when there is none or the console no longer accepts it, so re-running onboarding does not litter the console with keys.
- **The legacy private API is the fallback**, used when the public one cannot be used: older firmware, or an account without the rights to create a key. Neither case is an error.
- Discovery lists the cameras; none are pre-selected. Importing a camera reuses a stream the console already serves and enables one only when there is none, so re-running onboarding does not churn the console's stream settings. A session that expires while the list is open is renewed once with the stored credentials.
- Without local-network access, a failed connection is reported as that, not as a timeout.

Android reference: `OnboardingViewModel`, `ProtectPublicApiClient`, `ProtectApiClient`.
Fixtures: [`shared/fixtures/protect-api/`](../fixtures/protect-api/) (anonymised public and legacy responses, and the camera ids expected from them).

## Camera ids

- A Protect camera's id is `protect-<console camera id>-<channel>`, where the channel is the quality channel: Protect numbers them High, Medium, Low, and Dozecam uses **Medium, channel 1**. The public API names the quality ("medium"); the legacy API takes the channel called Medium, or the first channel if there is none.
- **The same camera gets the same id from both APIs**, so a console that switches API between runs updates its existing entries and never duplicates them.
  - **Known exception:** a camera with no Medium channel. The legacy API falls back to its first channel (High, channel 0) and stores `protect-<id>-0`, while the public API always asks for Medium and stores `protect-<id>-1`, so a console switching API duplicates such a camera. Both platforms behave this way today, and the `cam2` fixture in [`shared/fixtures/protect-api/`](../fixtures/protect-api/) pins it; changing it is a rule change for both apps.
- Re-importing a camera updates its entry (name, stream URL) and keeps its enabled setting: enabled is the user's choice, not the console's. A camera new to the list arrives enabled.
- Each Protect camera records the console that issued it. A camera from a console that is not the one signed in is played over its own RTSP URL, never through a livestream negotiated with the wrong console.
- A camera added by hand gets a random id and has no console.

## Stream URLs

- Every stored stream URL is plain `rtsp://`.
- The public API hands back `rtsps://<console>:7441/<alias>?enableSrtp`. Only the alias is used: it is re-pointed at the address the user reached the console on (which may differ from the address the console believes it has) on the plain RTSP port **7447**. The legacy API gives the alias directly, served on the same port.
- A URL entered by hand must be `rtsp://` or `rtsps://` with a host. An `rtsps://` URL is rewritten to `rtsp://` before it is saved: port 7441 becomes 7447, any other port is kept, and the query is dropped. Protect's `rtsps://` link is not a stream common players can open, while the same alias plays on the plain port.
- Only a plain `rtsp://` URL can be monitored over RTSP; a stale `rtsps://` entry from before this rewrite needs the livestream ([connection-state.md](connection-state.md#the-monitors-transports)).

Android reference: `StreamUrlValidator`, `ProtectPublicApiClient.streamUrlFor`.
Fixtures: [`shared/fixtures/stream-url/`](../fixtures/stream-url/).

- **iOS:** VLCKit's RTSP stack has no TLS, but an in-app TLS proxy plays `rtsps://` for live view and wake-on-sound alike (#59). Whether iOS keeps Protect's `rtsps://` link rather than rewriting it depends on confirming on a real console that its port 7441 carries plain RTP inside TLS; the public API's `?enableSrtp` suggests SRTP, which live555 cannot play (#59, #64). Until then the rule above holds on both platforms.

## Certificate pinning (TOFU)

Consoles use self-signed certificates, so identity is the certificate the user confirmed, not a CA chain or a hostname.

- The pin is the SHA-256 fingerprint of the leaf certificate. Hostname verification is off: a self-signed certificate never matches the console's LAN address.
- **First contact:** the connection is refused and the user is shown the fingerprint to confirm. The pin is stored only after a sign-in succeeds behind it, so a wrong password never leaves a console pinned.
- **A changed certificate** is asked about again, showing the pinned and the presented fingerprints, rather than refused outright: consoles reissue certificates for ordinary reasons (firmware update, factory reset), and refusing would leave the console unreachable from every screen.
- **Pins are per endpoint** (`host:port`), not per host: the console's UI and its media ports present different certificates.
- **Media endpoints** are learned on first use, without a prompt: the URL that leads there was minted by the already-pinned console over its verified connection, so the console vouches for it. From then on they are pinned. A media endpoint whose certificate changes is forgotten and learned again on the next negotiation, still only by way of the pinned console. Confirming a new certificate for the console forgets the media pins learned on it; an ordinary sign-in keeps them.

Android reference: `TofuTrustManager`, `TofuTrustStore`, `ProtectLivestreamProvider`.

- **Android:** a stale `rtsps://` stream played by libVLC has its certificate prompt answered yes automatically; no pin applies there.
- **iOS:** the in-app TLS proxy sees the stream's leaf certificate, so TOFU pinning for `rtsps://` media lives in the proxy (#59, #64).

## The livestream

Protect's WebSocket livestream (fragmented MP4) is how a signed-in console's cameras are watched, and the monitor's second transport.

- Each negotiation mints a single-use URL, so every reconnect negotiates again. The login session behind it is reused until the console rejects it, then renewed once.
- The wire format is a 1-byte frame type, a 3-byte big-endian length, then the payload. WebSocket message boundaries mean nothing: a frame can straddle messages, and one message can carry several. Bytes not yet decodable are carried forward. A fragment's parts are reassembled in `moof`, `mdat`, video, audio order, whatever order they arrived in. An unknown frame type means the stream is out of step and is an error.
- An AV1 initialisation segment whose `av1C` record has no config OBUs gets one empty temporal-delimiter OBU appended, which the record allows and Media3's parser needs; any other segment passes through unchanged.

Android reference: `LivestreamDecoder`, `Av1ConfigRepair`, `ProtectLivestreamProvider`.
Fixtures: [`shared/fixtures/livestream/`](../fixtures/livestream/).

- **iOS:** the same bytes go into libVLC through media callbacks (`libvlc_media_new_callbacks`) and its MP4 demuxer, then VideoToolbox, or dav1d for AV1. libVLC plays an `av1C` with no config OBUs as it is; the repair is applied anyway, since it is spec-valid and keeps the two platforms feeding identical bytes (#66). Proven against synthetic streams only, not yet against a console.

## Credentials

The console address, username, password and API key are stored encrypted, and are sent only to that console. See [privacy.md](privacy.md#what-the-device-stores).
