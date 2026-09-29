#!/usr/bin/env bash
# Extra streams for the #59 media spike, on top of tools/testbed.sh (which
# must be running): clock streams for glass-to-glass latency, and an rtsps
# mirror with a self-signed certificate, as Protect consoles serve.
#
#   rtsp://HOST:18554/clock       1920x1080@30, H.264 4 Mb/s, wall-clock ms burned in
#   rtsp://HOST:18554/clock-low   640x360@15, H.264 500 kb/s, same clock
#   rtsp://HOST:18554/clock-vt    1920x1080@30, H.264 main from the Mac's hardware encoder, no audio
#   rtsp://HOST:18554/clock-vt-360  640x360@15, the same encoder, for grid load tests
#   rtsps://HOST:18323/<path>     RTSP over TLS with plain RTP, as Protect serves it (tlsrelay.py)
#   rtsps://HOST:18322/nursery    mediamtx RTSPS mirror; mediamtx forces SRTP, which live555 cannot play
#   rtsps://HOST:18322/clock      the same for the clock
#   http://HOST:18580/            this Mac's clock (epoch seconds), for clock-offset correction
set -euo pipefail
cd "$(dirname "$0")"

TESTBED_PORT=18554
RTSPS_PORT=18322
RUN=/tmp/dozecam-media-spike

publish_clock() { # name width height fps bitrate [x264 preset/profile/params]
	(
		python3 -u clock.py "$2" "$3" "$4" |
			ffmpeg -hide_banner -loglevel warning \
				-f rawvideo -pixel_format gray -video_size "$2x$3" -framerate "$4" \
				-i - \
				-f lavfi -i "anullsrc=r=48000:cl=mono" \
				${6:--c:v libx264 -preset ultrafast -tune zerolatency} -pix_fmt yuv420p \
				-g "$4" -b:v "$5" -c:a aac -b:a 64k -shortest \
				-f rtsp -rtsp_transport tcp "rtsp://127.0.0.1:$TESTBED_PORT/$1"
	) >"$RUN/$1.log" 2>&1 &
}

start() {
	nc -z 127.0.0.1 "$TESTBED_PORT" 2>/dev/null || {
		echo "testbed not running: tools/testbed.sh start" >&2
		exit 1
	}
	mkdir -p "$RUN"
	publish_clock clock 1920 1080 30 4M
	publish_clock clock-low 640 360 15 500k
	# What a camera sends: a hardware encoder's H.264 (Apple's, here), main
	# profile. x264's zerolatency output splits frames into slices, which
	# VideoToolbox on the iPad rejected (kVTVideoDecoderBadDataErr).
	publish_clock clock-vt 1920 1080 30 4M "-c:v h264_videotoolbox -realtime 1 -profile:v main -an"
	publish_clock clock-vt-360 640 360 15 500k "-c:v h264_videotoolbox -realtime 1 -profile:v main -an"

	[ -f "$RUN/server.crt" ] || openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
		-subj "/CN=dozecam-media-spike" -keyout "$RUN/server.key" -out "$RUN/server.crt" 2>/dev/null
	cat >"$RUN/mediamtx.yml" <<CONFIG
logLevel: info
api: no
rtsp: yes
rtspTransports: [tcp]
rtspEncryption: "strict"
rtspAddress: :18556
rtspsAddress: :$RTSPS_PORT
rtspServerKey: $RUN/server.key
rtspServerCert: $RUN/server.crt
rtmp: no
hls: no
webrtc: no
srt: no
moq: no
paths:
  nursery:
    source: rtsp://127.0.0.1:$TESTBED_PORT/nursery
  clock:
    source: rtsp://127.0.0.1:$TESTBED_PORT/clock
CONFIG
	(cd "$RUN" && nohup mediamtx "$RUN/mediamtx.yml" >"$RUN/mediamtx.log" 2>&1 &)
	nohup python3 -u timeserver.py >"$RUN/timeserver.log" 2>&1 &
	nohup python3 -u tlsrelay.py "$RUN/server.crt" "$RUN/server.key" >"$RUN/tlsrelay.log" 2>&1 &
	sleep 2
	status
}

stop() {
	# Patterns anchored on the processes themselves, so they can never match
	# the shell that runs this script.
	pkill -f "Python -u (clock|timeserver|tlsrelay)\.py" 2>/dev/null || true
	pkill -f "^ffmpeg .*rtsp://127\.0\.0\.1:$TESTBED_PORT/clock" 2>/dev/null || true
	pkill -f "^mediamtx $RUN/mediamtx\.yml" 2>/dev/null || true
	echo stopped
}

status() {
	for url in "rtsp://127.0.0.1:$TESTBED_PORT/clock" "rtsp://127.0.0.1:$TESTBED_PORT/clock-low" "rtsp://127.0.0.1:$TESTBED_PORT/clock-vt" \
		"rtsps://127.0.0.1:$RTSPS_PORT/nursery" "rtsps://127.0.0.1:$RTSPS_PORT/clock" \
		"rtsps://127.0.0.1:18323/nursery" "rtsps://127.0.0.1:18323/clock"; do
		if codecs=$(ffprobe -v error -timeout 5000000 -rtsp_transport tcp -tls_verify 0 \
			-show_entries stream=codec_name,width,height -of csv=p=0 "$url" 2>&1 | paste -sd' ' -); then
			echo "ok   $url  $codecs"
		else
			echo "FAIL $url  $codecs"
		fi
	done
	curl -fsS --max-time 2 http://127.0.0.1:18580/ >/dev/null && echo "ok   http://127.0.0.1:18580/ (time)" || echo "FAIL time server"
}

case "${1:-}" in
start) start ;;
stop) stop ;;
status) status ;;
*)
	echo "usage: streams.sh start|stop|status" >&2
	exit 64
	;;
esac
