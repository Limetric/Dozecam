# Media spike (#59)

Throwaway app that picks the iOS media stack for live view and wake-on-sound.
It installs as `app.dozecam.dev` ("Media Spike") and logs to
`Documents/spike.log`.

- **Live view**: a grid of VLCKit 4 players (SPM, pinned to 4.0.0a24) with the
  Android flags. Latency is measured automatically: snapshots of a tile,
  seven-segment clock read from the pixels, corrected by the Mac's clock
  offset from a tiny time server.
- **Wake-on-sound input**: one audio-only libVLC player per room, PCM from
  `libvlc_audio_set_callbacks` into a level meter and an `AVAudioEngine` mix.
  libVLC's C headers are vendored in `App/libvlc` from the same binary.
- **rtsps**: VLCKit's live555 has no TLS, so `TLSProxy` terminates TLS in the
  app (Network.framework, certificate fingerprint logged for TOFU) and
  rewrites the RTSP base URLs.

```sh
tools/testbed.sh start && spikes/ios-media/tools/streams.sh start
spikes/ios-media/spike.sh build && spikes/ios-media/spike.sh install
spikes/ios-media/spike.sh launch -stream clock-vt -tiles 1 -autoplay YES -automeasure YES
spikes/ios-media/spike.sh launch -autoaudio YES          # rooms: nursery (rtsp, rtsps), porch
spikes/ios-media/spike.sh launch -url http://HOST:18581/av1-1080p.mp4 -autoplay YES
spikes/ios-media/spike.sh log
```

Launch arguments override settings: `-host`, `-stream`, `-tiles`, `-path`
(any testbed path), `-url`, `-autoplay`, `-autoaudio`, `-automeasure`,
`-vlcLogLevel 0` (libVLC debug log). The iPad must be unlocked to launch.

`tools/streams.sh` adds, on top of the testbed: clock streams (`clock`,
`clock-low` from x264; `clock-vt`, `clock-vt-360` from the Mac's hardware
encoder), an RTSP-over-TLS relay on 18323 (`tlsrelay.py`, plain RTP inside
TLS as Protect serves it), mediamtx RTSPS on 18322 (SRTP, which live555
cannot play), and the time server on 18580.

Findings: see #59.
