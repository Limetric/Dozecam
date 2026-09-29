# Transport fallback

When wake-on-sound can reach a camera over more than one transport (on
Android: RTSP first, the Protect livestream in reserve), these are the rules
for when it gives up on one and tries the next.

`fallback.json`:

- `restartsBeforeFallback` — how many failed restarts a transport gets before
  the next one is tried. Counted by the fallback itself, per transport.
- `cases[]` — each drives one fallback from a fresh start:
  - `name` — what the case proves.
  - `transportCount` — how many transports the camera has, in order of
    preference.
  - `steps[]`, applied in order:
    - `event` — `restart` (a restart is being made) or `audioDecoded` (audio
      arrived on the current transport).
    - `times` — how many times the event happens; default 1.
    - `movesOn` — when given, what every one of those restarts must report:
      `true` when it moved to another transport, `false` when it stayed.
    - `index` — when given, the transport in use after the step, 0-based in
      order of preference.
