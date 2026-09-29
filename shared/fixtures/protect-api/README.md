# Protect API responses

Successful response bodies from a UniFi Protect console, as the two clients
receive them, and what each client must make of them. Response files keep the
console's own field names and carry fields Dozecam ignores, so a client that
chokes on an unknown key fails here. Everything is anonymised: ids, names,
aliases, tokens and keys are made up, and addresses are private-range examples.

Error bodies and the login exchange (cookies and headers, not a body) stay
inline in the tests; they describe no cameras.

## The camera id rule

`public/cameras.json` and `legacy/bootstrap.json` describe the same three
cameras. `cameras.expected.json` lists them once, by `id`, with what each API
must yield for that camera under `publicApi` and `legacyApi`. Both clients
must produce exactly these ids in this order: a console that moves from the
legacy API to the public one then updates its camera entries rather than
duplicating them.

The cameras cover the cases the clients branch on:

| id | Public API | Legacy API |
|---|---|---|
| `cam1` | named, has a speaker | has a Medium channel, which is preferred |
| `cam2` | `name: null`, no speaker | `name: ""`, no Medium channel, so the first one is preferred |
| `cam3` | no `featureFlags`, so no speaker | no channels, so no preferred channel |

## Files

- `public/` — the Integration API (`/proxy/protect/integration/v1`, Protect
  5.3+), authenticated by API key.
  - `cameras.json` — `GET /cameras`.
  - `rtsps-stream.json` — `GET /cameras/{id}/rtsps-stream`; a quality with a
    null URL is inactive.
  - `rtsps-stream-created.json` — `POST /cameras/{id}/rtsps-stream`.
  - `talkback-session.json` — `POST /cameras/{id}/talkback-session`.
- `legacy/` — the private API (`/proxy/protect/api`), authenticated by a
  login session.
  - `bootstrap.json` — `GET /bootstrap`.
  - `livestream.json` — `GET /ws/livestream`, the WebSocket URL to open.
  - `camera-rtsp-enabled.json` — `PATCH /cameras/{id}`, the updated camera.
  - `api-key.json` — `POST /proxy/users/api/v2/user/self/keys`, the minted key.

## Expectations

`public/expected.json` and `legacy/expected.json` hold one case per other
response, keyed by what it covers. Each case has a `name`, the `response` file
it is about (relative to its own directory), and the values the client must
return. In `legacy/expected.json`, `livestream.url` assumes the console
answered on `127.0.0.1`: the client replaces the host the console advertises
with the one it reached.
