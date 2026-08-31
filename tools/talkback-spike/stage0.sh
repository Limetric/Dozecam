#!/usr/bin/env bash
# Talk-back spike, stage 0: prove the Protect side and the wire format from a
# laptop, with no Android code involved.
#
# If this fails, nothing in issue #3 is buildable and no amount of MediaCodec
# work will change that. If it succeeds, the only question left is whether our
# own RTP framing is accepted where ffmpeg's is -- which is stage 1, on-device.
#
# Reads credentials from talkback-spike.properties beside this script; see
# talkback-spike.properties.example. That file is gitignored and never printed.

set -euo pipefail

# Steps 1-3 are silent; step 4 comes out of a speaker in someone's house.
# --probe-only stops before it, so the session shape and the camera's
# reachability can be established without waking anybody.
probe_only=false
[[ "${1:-}" == "--probe-only" ]] && probe_only=true

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
props="$here/talkback-spike.properties"

[[ -f "$props" ]] || {
  echo "missing $props -- copy talkback-spike.properties.example and fill it in" >&2
  exit 1
}

host=""; apiKey=""; cameraId=""; tone_seconds="3"
# shellcheck disable=SC1090
source "$props"

# Handy for trying one camera without editing the file.
cameraId="${CAMERA_ID:-$cameraId}"

[[ -n "$host"   ]] || { echo "host is unset in talkback-spike.properties" >&2; exit 1; }
[[ -n "$apiKey" ]] || { echo "apiKey is unset in talkback-spike.properties" >&2; exit 1; }

for tool in curl jq ffmpeg; do
  command -v "$tool" >/dev/null || { echo "need $tool on PATH" >&2; exit 1; }
done

api="https://$host/proxy/protect/integration/v1"
# Consoles serve a self-signed cert; the app pins it TOFU, curl just skips it.
curl_opts=(--silent --show-error --insecure --max-time 10 -H "X-API-KEY: $apiKey")

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1. Cameras, and which of them claim a speaker"
cameras="$(curl "${curl_opts[@]}" "$api/cameras")" || {
  echo "camera list failed -- wrong host, wrong key, or console unreachable" >&2
  exit 1
}
if jq -e 'has("error")' >/dev/null 2>&1 <<<"$cameras"; then
  echo "console rejected the key:" >&2
  jq -r '.error.message // .' <<<"$cameras" >&2
  exit 1
fi
# macOS column has no -N, so the header is just the first row.
{
  printf 'ID\tNAME\tHAS_SPEAKER\n'
  jq -r '.[] | [.id, (.name // "(unnamed)"),
                (.featureFlags.hasSpeaker // "absent" | tostring)] | @tsv' <<<"$cameras"
} | column -t -s "$(printf '\t')"

if [[ -z "$cameraId" ]]; then
  say "Set cameraId in talkback-spike.properties to one of the above and re-run."
  exit 0
fi

say "2. Talkback session for $cameraId"
# Documented from Protect 5.3.45. No request body.
response="$(curl "${curl_opts[@]}" -X POST -w '\n%{http_code}' \
  "$api/cameras/$cameraId/talkback-session")"
code="$(tail -n1 <<<"$response")"
body="$(sed '$d' <<<"$response")"

if [[ "$code" != "200" && "$code" != "201" ]]; then
  echo "talkback-session returned HTTP $code" >&2
  echo "$body" >&2
  echo >&2
  echo "404 here most likely means the console predates 5.3.45, or this camera" >&2
  echo "has no speaker. Either way it answers checklist item 1." >&2
  exit 1
fi

jq . <<<"$body"

url="$(jq -r '.url' <<<"$body")"
codec="$(jq -r '.codec' <<<"$body")"
rate="$(jq -r '.samplingRate' <<<"$body")"
camera_host="$(sed -E 's#^[a-z]+://([^:/]+).*#\1#' <<<"$url")"

say "3. Reachability of the camera itself ($camera_host)"
# This is decision 4's probe, by hand. The audio goes to the camera, not the
# console -- a camera on an isolated VLAN streams video perfectly and still
# refuses this.
if nc -z -G 3 "$camera_host" 443 2>/dev/null || nc -z -G 3 "$camera_host" 80 2>/dev/null; then
  echo "reachable over TCP -- the phone can route here too, if it shares this network"
else
  echo "NOT reachable over TCP from this machine."
  echo "Video would still work (it arrives via the console). Talk-back will not."
  echo "This is the VLAN-isolation risk, confirmed rather than theorised."
fi

if [[ "$probe_only" == true ]]; then
  say "Stopping before the tone (--probe-only)."
  echo "Re-run without the flag, in a room where a noise is welcome."
  exit 0
fi

say "4. Sending a ${tone_seconds}s 440 Hz tone as $codec at $rate Hz"
echo "Listen at the camera. Nothing below proves delivery -- UDP reports success"
echo "either way. Your ears are the only instrument here."
echo

case "$codec" in
  opus)
    # -re paces at wall-clock, which is the whole game. ffmpeg stamps a 48k
    # clock and payload type 97 regardless of $rate; whether the camera cares
    # is exactly what we are here to find out.
    ffmpeg -hide_banner -loglevel warning -re \
      -f lavfi -i "sine=frequency=440:duration=$tone_seconds" \
      -ar "$rate" -ac 1 -c:a libopus -b:a 32k -application voip \
      -f rtp "$url"
    ;;
  aac)
    ffmpeg -hide_banner -loglevel warning -re \
      -f lavfi -i "sine=frequency=440:duration=$tone_seconds" \
      -ar "$rate" -ac 1 -c:a aac -b:a 32k \
      -f rtp "$url"
    ;;
  vorbis)
    echo "Camera asked for vorbis. There is no MediaCodec vorbis encoder, so" >&2
    echo "this camera cannot be supported on Android without a new dependency." >&2
    exit 1
    ;;
  *)
    echo "Unknown codec '$codec' -- checklist item 2 just got more interesting." >&2
    exit 1
    ;;
esac

say "Done. Did the camera make a sound?"
cat <<'NOTES'
  yes -> the Protect side and the wire format are proven. Stage 1 is now worth
         building: the only remaining question is whether our own MediaCodec +
         hand-rolled RTP framing is accepted where ffmpeg's was.
  no  -> before blaming the framing, check in order:
         - step 3 above: is the camera reachable at all from here?
         - the camera's speaker volume in Protect (checklist item 6)
         - talkbackSettingsActive on the camera (checklist item 5)
         - whether the session expired between step 2 and step 4
NOTES
