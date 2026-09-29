# Stream URL

What a camera stream URL typed or pasted by hand may be. Each file has
`cases[]` of `name`, `url` (the raw input, surrounding whitespace included)
and `expected`.

- `valid.json` — whether the URL is accepted: after trimming whitespace, it
  parses, has a host, and its scheme is `rtsp` or `rtsps` in any case.
  `expected`: true or false.
- `monitorable.json` — whether wake-on-sound can listen to it: a valid URL
  whose scheme is plain `rtsp`. `expected`: true or false.
- `normalize.json` — the URL as it is stored. Protect's console shows an
  `rtsps` link on port 7441 that no player can open, but the same path plays
  on `rtsp` port 7447. So any `rtsps` URL becomes `rtsp` with the same host
  and path and no query, port 7441 becoming 7447 and any other port kept;
  anything else is only trimmed. `expected`: the stored URL.

The rejections of `rtsp://`, `rtsp:token` and a host with spaces are the rule,
not an accident of Java's URL parser, which the Android reference happens to
rely on. A platform whose parser accepts one of them must reject it anyway.
