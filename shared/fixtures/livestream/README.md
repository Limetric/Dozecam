# Livestream

Byte vectors for Protect's WebSocket livestream: the frame decoder, and the
repair that makes a Protect AV1 init segment parseable. File paths in the
JSON are relative to this directory.

## Wire format

Each frame is a 1-byte type, a 3-byte big-endian payload length, then the
payload. Types: 247 timestamp, 248 codec information (UTF-8 codec string),
249 begin segment, 250 init segment, 251 moof, 252 video, 253 audio, 254 mdat,
255 end segment. Anything else means the stream is out of sync.

WebSocket message boundaries mean nothing: a frame may straddle messages and a
message may carry several frames. Between begin and end segment, a fragment's
boxes may arrive in any order and any box as several chunks; the fragment is
emitted as moof, mdat, video, audio, chunks in arrival order within each box.

## `decoder.json`

`cases[]`, each fed to one fresh decoder:

- `name` — what the case proves.
- `messages[]`, fed in order, one WebSocket message each:
  - `file` — the message's raw bytes (`decoder/<case>/message-<n>.bin`).
  - `segments` — everything decoding that message must emit, in order. Each
    has `type` (`init` or `media`) and its bytes as either `text` (UTF-8) or
    `file`; an `init` may give `codec`, the codec string it must carry.
  - `error` — instead of `segments`: `protocol` when decoding the message must
    fail as out of sync.

## `av1-config-repair.json`

Protect writes an `av1C` box holding only the 4-byte AV1 config record, with
no `configOBUs`; Media3 reads past it and throws. The repair appends a
zero-length temporal delimiter OBU and grows every enclosing box to match.

- `repair` — `input` must become exactly `output`. `av1cSizeBefore` and
  `av1cSizeAfter` are the `av1C` box's declared sizes; `appendedHex` is the
  bytes appended at the end of `av1C`; each box in `grownBoxes` (the first of
  that type in the file: the video track's chain) grows by that many bytes,
  and each in `untouchedBoxes` keeps its size.
- `unchanged[]` — `input` must come back byte for byte.

## Where the bytes came from

- `av1-config-repair/g6-init.bin` is the real init segment a UniFi G6 camera
  sent over the livestream (1143 bytes: `ftyp`, then `moov` with an AV1 video
  track and an AAC audio track). `g6-init-truncated.bin` is its first 40
  bytes; `g6-init-repaired.bin` is the Android repair's output for it.
  `ftyp-only.bin` is a lone 12-byte `ftyp` header claiming 16 bytes.
- `decoder/**` are synthetic: frames with short ASCII payloads (`M1`, `D1`,
  `chunk1`, …) standing in for boxes, written out from the Android decoder
  tests' frame builder. `payload-over-16-bits` carries a 70000-byte payload of
  `i % 251`, which needs all three length bytes; `init.bin` is that payload.
