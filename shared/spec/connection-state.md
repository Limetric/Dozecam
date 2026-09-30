# Connection state

Honest connection state: **a frozen frame never pretends to be live.** Every camera session, in the viewer and in the monitor, has its own watchdog that decides its state from evidence, not from what the player claims.

Android reference: `PlaybackWatchdog`, `ConnectionState`.
Fixtures: [`shared/fixtures/playback-watchdog/`](../fixtures/playback-watchdog/).

## States

| State | Meaning | Shown as |
|---|---|---|
| **Connecting** | Starting; nothing received yet on this session | Connecting |
| **Live** | Frames are arriving | Live |
| **Reconnecting** (attempt *n*) | The stream failed or stalled; attempt *n* is waiting out its backoff or in flight | Reconnecting, with the attempt number |
| **Offline** | The phone has no network; retrying is pointless until it returns | Offline |

A tile that is not live shows how long ago its last frame arrived, so a frozen picture always carries its age.

## Rules

- **Only a frame makes a session live**: the player reporting playing, or its clock advancing. Buffering events and changes in picture shape are not frames, and never move a deadline.
- **Stall:** live with no frame for **2.5 s** → reconnect.
- **Connect timeout:** **5 s** for the first frame of a new session, of every reconnect attempt, and after a hidden picture is shown again (it has to wait for a keyframe).
- **Errors:** a player error, or the player stopping on its own, → reconnect. The stop that the watchdog's own restart causes is ignored while it waits for that restart to deliver.
- **Backoff:** reconnect attempt *n* first waits `min(500 ms × 2^(n−1), 4 s)`: 0.5 s, 1 s, 2 s, 4 s, 4 s, … Frames returning during the wait cancel the restart and make the session live again; the network dropping during the wait cancels it and goes offline. Reaching live resets the count.
- **Network lost:** offline at once, no retries. Frames that trickle in from buffers while offline update the last-frame age but never make the session live.
- **Network back:** reconnect at once, with no backoff and the attempt count reset, unless the session is live or still connecting. "Network" means the phone has a default network at all; mobile data counts, and is warned about separately because it cannot reach a LAN console.
- **Clocks:** every deadline is on a monotonic clock, so changing the phone's clock moves none of them. The last-frame age is wall-clock time, for display.
- **A camera kept connected but not shown** (warm, with its video track dropped) is not timed: with no picture expected there is no frame to wait for, and audio ticking on proves nothing about the picture. Errors and network changes during that time are remembered, not acted on, and repaired with one immediate reconnect when the picture is wanted again. When it is shown again, a live session drops back to connecting until a frame actually paints.
- A session started again after being stopped begins in connecting, and events queued while it was stopped are discarded.

## The monitor

The monitor's sessions follow the same rules, with decoded **audio** buffers as the frames: a room whose audio stops arriving is not live, and after the grace period that is a failure ([failure-alerts.md](failure-alerts.md)).

A player can report playing before any sample is decoded, so being live is not enough to call a room *audible* or to show a level; that also needs a buffer decoded on the current connection ([alerts-and-sound-modes.md](alerts-and-sound-modes.md#the-detector)).

## The monitor's transports

A stream that cannot be decoded does not say so: it looks exactly like a quiet room, and the reconnect loop it causes looks like a flaky network. So the monitor keeps a list of ways to listen to each camera, best first, and moves on from one that never yields a sample.

- **Order:**
  1. the camera's plain RTSP stream, audio track only: a few kilobits a second, affordable all night on battery;
  2. the Protect livestream, only for a camera whose console is the one currently signed in. It carries video whether or not it is watched, so it comes second. It carries every codec, where RTSP may not (see below).
- A camera with no way in is not monitored, and the app says so on screen rather than leaving a room silently uncovered. A camera counts as monitorable only if the monitor could actually listen to it; the arming checks and the monitor use the same answer.
- **Fallback:** restarts are counted per transport. After **3** restarts on a transport that has never decoded a single audio buffer, the monitor moves to the next transport, cycling back to the first after the last (a fallback can be just as broken, and the first may recover). With one transport it stays.
- **Kept for good:** once any buffer decodes on a transport, it is never abandoned; later trouble is the network, and the others would fare no better.
- The decision is made as a restart is actually made, not when one is scheduled: a stream that recovers during its backoff stays on the transport it is on. Buffers still queued from an abandoned transport are discarded, so they cannot vouch for its replacement or raise an alert.
- When the transports available to a camera change (another console signed in), its monitor is rebuilt.

Android reference: `MonitorTransports`, `TransportFallback`, `CameraAudioMonitor`.
Fixtures: [`shared/fixtures/transport-fallback/`](../fixtures/transport-fallback/).

- **Android:** the monitor plays through Media3, whose RTSP stack has no TLS and cannot depayload the Opus that Protect sends. An Opus or rtsps-only camera is monitored over the livestream; that is why the livestream is in the list.
- **iOS:** the monitor is one audio-only libVLC player per room, which plays `rtsps://` through the in-app TLS proxy and is expected to play Opus (#59, untested). The fallback rule still applies to whatever transports iOS has (#67). A libVLC player whose publisher restarts goes to its error state and stays there, so the reconnect rules above are what bring it back (#59).
- **iOS live view** is libVLC (VLCKit 4) with the same watchdog rules (#66).
