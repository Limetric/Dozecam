# Dozecam — Project Design Document

A native Android baby monitor app built on UniFi Protect G6 Instant cameras, providing the monitor-specific features the official Protect app lacks: always-on display, wake-on-sound, auto-reconnect, and low-latency LAN streaming.

**Product name:** Dozecam. The store listing must not lead with "UniFi" (Ubiquiti trademark); describe compatibility as "for UniFi Protect cameras" instead.

## Product Decisions

Settled 2026-07-20:

- **Play Store from day one.** Dozecam is a store product, not a household sideload. Permissions, onboarding, and release plumbing are designed store-safe from the start.
- **Protect API onboarding** (§4.4). Users sign in to their Protect console with a local account; the app discovers cameras and manages RTSP streams itself. Manual `rtsp://` URL entry remains as an escape hatch.
- **minSdk 31, targetSdk 37.** Matches CloudMount conventions and testing surface. Accepted tradeoff: excludes pre-Android-12 tablets, a real slice of the repurposed-old-tablet audience.
- **Free at launch.** No billing code in v1. Keep multi-camera and parent-device mode modular — they are the natural paid hooks if a paid tier ever lands.

## 1. Background & Motivation

UniFi Protect is an excellent surveillance platform, but its mobile app is designed for security review, not nursery monitoring. Missing features that matter for a babycam:

- **Always-on / kiosk display** — the Protect app lets the device sleep and offers no wake lock.
- **Wake on sound** — no way to sleep the display and wake it when the baby cries.
- **Auto-rotation / fullscreen-first UX** — the app is dashboard-oriented, not "one stream, full screen, forever."
- **Tunable audio alerting** — no user-adjustable sound thresholds, hysteresis, or alert behavior.
- **Aggressive reconnection** — stream stalls require manual intervention.

The G6 Instant exposes a per-camera RTSP stream via Protect, which makes a custom client entirely feasible.

## 2. Goals & Non-Goals

### Goals
- Play Store distribution: store-safe permissions and an onboarding flow that works for arbitrary Protect setups, not just the developer's.
- Sub-second (~300–500 ms) live video on the local network.
- Screen sleeps during quiet periods; wakes automatically on sustained sound.
- Continuous background audio monitoring via a foreground service, even with the display off.
- Automatic, fast stream recovery after Wi-Fi drops, camera reboots, or decoder stalls.
- Multi-camera support (switch between G6 Instants; optional grid later).
- LAN-only operation. No cloud dependency, no internet exposure.

### Non-Goals (v1)
- Remote access over the internet (use WireGuard/Tailscale at the network level if needed).
- Recording/playback — Protect already does this well.
- Two-way audio (G6 Instant talk-back) — possible later, out of scope initially.
- iOS support.
- Monetization — free at launch, no billing integration in v1.

## 3. System Architecture

```
┌──────────────┐   RTSP (port 7447, LAN)   ┌─────────────────────────────┐
│ UniFi Protect │ ─────────────────────────▶ │        Android App          │
│   Console     │                            │                             │
│  (G6 Instant  │                            │  ┌───────────────────────┐  │
│   cameras)    │                            │  │  Foreground Service   │  │
└──────────────┘                            │  │  - RTSP audio client  │  │
                                            │  │  - MediaCodec decode  │  │
        Optional:                           │  │  - RMS level detector │  │
┌──────────────┐                            │  │  - PARTIAL_WAKE_LOCK  │  │
│    go2rtc     │  normalized RTSP :8554     │  └──────────┬────────────┘  │
│ (restreamer)  │ ──────────────────────────▶│             │ wake intent   │
└──────────────┘                            │  ┌──────────▼────────────┐  │
                                            │  │   Monitor Activity    │  │
                                            │  │  - libVLC video view  │  │
                                            │  │  - setTurnScreenOn    │  │
                                            │  │  - fullscreen UI      │  │
                                            │  └───────────────────────┘  │
                                            └─────────────────────────────┘
```

### Stream source
- Enable the RTSP stream per camera in Protect (Camera → Settings → Advanced). Use the **medium quality** channel — sufficient for a nursery view, lighter on decode and Wi-Fi.
- Consume the **plain RTSP stream on port 7447** (`rtsp://<console-ip>:7447/<token>`). ExoPlayer/Media3 does not handle Protect's RTSPS (7441), and on a trusted LAN the unencrypted stream is acceptable.
- **Optional go2rtc layer:** run go2rtc on the network and point the app at its restreamed RTSP output (port 8554). Benefits: normalized/stable stream behavior, per-camera config outside the app, future support for non-UniFi cameras, and transcoding if ever needed. Trade-off: one more moving part. v1 can go direct-to-Protect.

## 4. Core Components

### 4.1 Monitoring Foreground Service
The heart of the app. Runs continuously while monitoring is active.

- Declared with foreground service type `mediaPlayback` (required for Android 14+).
- Holds a `PARTIAL_WAKE_LOCK` so CPU and network stay alive with the screen off.
- Maintains a lightweight RTSP session consuming **audio only** while the display sleeps (no video decode → minimal battery/CPU). Options: connect to the camera's low-res stream, or request only the audio track.
- Decodes AAC audio with `MediaCodec` and feeds PCM into the sound detector.
- Posts a persistent notification with current status (monitoring / alerting / reconnecting).

### 4.2 Sound Detector
Simple, tunable, and deliberately boring:

- Compute RMS (and peak) levels over a sliding window (e.g., 250 ms frames).
- **Trigger:** level above threshold for a sustained period (default ~1.5 s) — prevents a single thud or dropped pacifier from waking the room.
- **Reset:** level below threshold for a quiet period (default ~10 s) before re-arming.
- All three parameters user-adjustable, because every nursery (and white noise machine) is different.
- On trigger: wake the display, surface the Monitor Activity, optionally play a chime / vibrate / fire a full-screen intent notification.

### 4.3 Monitor Activity (video UI)
- **Player:** libVLC (LibVLC for Android). More battle-tested against finicky RTSP sources than Media3's RTSP module, with aggressive low-latency tuning (`--network-caching=150` or similar). Media3/ExoPlayer is a fallback if tighter Jetpack Compose integration is preferred later.
- Fullscreen, immersive mode, sensor-based auto-rotation (or user-locked orientation).
- `setShowWhenLocked(true)` + `setTurnScreenOn(true)` so the activity can appear over the lock screen when the detector fires.
- While in the foreground: `FLAG_KEEP_SCREEN_ON` (always-on mode) or a dimming schedule.
- On-screen live audio level meter — invaluable for tuning thresholds.

### 4.4 Protect API Onboarding
Store users can't be asked to copy RTSP token URLs out of the Protect UI, so camera setup goes through the console's local HTTP API:

- Sign in with a **local Protect account** (console IP/hostname + username + password; document that a dedicated view-only local account is the recommended setup). Cookie + CSRF-token session handling; re-authenticate transparently when the session expires.
- **Prefer the public Integration API** (`/proxy/protect/integration/v1`, Protect 5.3+) — the one Ubiquiti documents and Home Assistant migrated its stream URLs to. The sign-in above mints a console API key (`POST /proxy/users/api/v2/user/self/keys`), which is stored and reused; cameras come from `GET /v1/cameras` and streams from `GET`/`POST /v1/cameras/{id}/rtsps-stream`.
- **Fall back to the private API** (bootstrap + `PATCH /cameras/{id}`) when the console cannot serve the public one: pre-5.3 firmware has no such endpoints, and minting a key needs owner rights. Both paths produce the same camera ids, so a console that changes API between runs updates its entries rather than duplicating them.
- Either way, derive the `rtsp://<console>:7447/<alias>` URL automatically. The public API returns `rtsps://<console>:7441/<alias>?enableSrtp`; only the alias is portable — the host it advertises need not be the address the user reached (Home Assistant core#176487), and the RTSPS port is not a stream the players here can open.
- **Risk:** the private fallback is unofficial and shifts between Protect releases; the public API is versioned but lags in coverage. Mitigations: fail with actionable errors, and keep **manual RTSP URL entry** as a permanent escape hatch.
- Credentials and token URLs live in app-private encrypted storage (§6).

### 4.5 Connection Watchdog
- Detect stalls at the frame level: if no video/audio frames arrive within N hundred ms, tear down and reconnect with exponential backoff (capped at a few seconds).
- Subscribe to `ConnectivityManager` network callbacks; reconnect immediately when Wi-Fi returns rather than waiting for a timeout.
- Surface state honestly in the UI (LIVE / RECONNECTING / OFFLINE + timestamp of last frame) — a frozen frame silently pretending to be live is the worst failure mode for a baby monitor.

## 5. Key Android Gotchas

| Issue | Mitigation |
|---|---|
| Doze mode kills network during long sleeps on some OEM builds | Play policy restricts the direct `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt; deep-link users to the battery-optimization settings screen with an in-app explanation instead (verify current policy before store submission); document per-OEM quirks (Samsung, Xiaomi are aggressive) |
| Android 14+ foreground service restrictions | Declare `mediaPlayback` service type and request the runtime notification permission |
| Waking the screen from background | Full-screen intent notification + `setTurnScreenOn`/`setShowWhenLocked` on the activity |
| RTSPS (port 7441) unsupported by common players | Use plain RTSP on 7447 (LAN-only), or terminate TLS in go2rtc |
| Display device battery | Assume the monitor tablet/phone is plugged in; optionally enable "stay awake while charging" behavior |

## 6. Security Posture

- Everything stays on the LAN. RTSP ports are never exposed to the internet.
- Remote viewing, if ever needed, goes through a VPN (WireGuard/Tailscale), not port forwarding.
- The RTSP token URLs from Protect are stored in app-private encrypted preferences.
- Protect local-account credentials (for API onboarding, §4.4) are stored the same way and sent only to the user's own console over the LAN. Recommend a dedicated view-only local account in onboarding copy.

## 7. Roadmap

### v0.1 — Prototype (a weekend)
- Hardcoded RTSP URL, libVLC fullscreen playback, keep-screen-on. Validate latency and stability.

### v0.2 — Reliability
- Connection watchdog, reconnect logic, honest connection-state UI.

### v0.3 — Wake on sound (the point of the project)
- Foreground service, audio-only monitoring, RMS detector, screen wake, threshold tuning UI with live level meter.

### v0.4 — Polish
- Multi-camera selection, settings screen (thresholds, quality, orientation lock), night-friendly dim red UI theme, chime/vibration alert options.

### v0.5 — Protect API onboarding
- Console sign-in with a local account, camera discovery, automatic RTSP enable/URL retrieval, manual-URL escape hatch. The internal milestones above can run on hardcoded/manual URLs; this gate exists because the store product can't.

### v1.0 — Play Store launch
- Store listing (Dozecam branding, "for UniFi Protect" compatibility phrasing), privacy policy, signed release pipeline, pre-launch report pass, policy check on the battery-optimization flow.

### Later / maybe
- Parent-device mode: second phone receives alerts (local push via the service device, or MQTT).
- go2rtc integration profile for mixed camera fleets.
- Cry-vs-noise classification (small on-device audio model) if RMS thresholds prove too crude.

## 8. Effort Estimate

| Phase | Effort |
|---|---|
| Playback prototype | 1–2 evenings |
| Reliability layer | 2–3 evenings |
| Wake-on-sound service | ~1 week of evenings (the audio pipeline + Android wake plumbing is the bulk of the project) |
| Polish | ongoing |
| Protect API onboarding | ~1 week of evenings (auth/session handling + camera discovery + failure UX against an unofficial API) |
| Store launch | 2–3 evenings (listing, policy passes, release pipeline reuse from CloudMount) |

The stack is well-trodden — nothing exotic, just more scaffolding than a web app. The real engineering time goes into reconnection edge cases and tuning sound detection so it triggers on crying but not on the white noise machine.
