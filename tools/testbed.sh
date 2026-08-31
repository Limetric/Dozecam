#!/usr/bin/env bash
# Local RTSP testbed: synthetic "cameras" for exercising Dozecam without a
# UniFi Protect console. mediamtx serves RTSP; ffmpeg publishes two streams:
#
#   nursery  animated test pattern with a running time counter; audio is
#            silence by default and a loud 660 Hz tone while "noise on" —
#            loud enough (RMS ~0.35) to trip the default wake-on-sound
#            threshold (0.10) once sustained.
#   porch    a second, always-silent camera so the grid and sound rotation
#            have more than one tile.
#
# Requires mediamtx and ffmpeg on PATH (brew install mediamtx ffmpeg).
# Runtime state lives in /tmp/dozecam-testbed (fixed, not per-session: the
# testbed is a machine-wide daemon that any agent session or human can start,
# inspect, and stop). See .claude/skills/test-app-changes for the workflow.
set -euo pipefail

RUNTIME_DIR="${DOZECAM_TESTBED_DIR:-/tmp/dozecam-testbed}"
# Everything below joins paths onto $RUNTIME_DIR from varying working
# directories, so a relative override must be pinned to the caller's cwd once.
case "$RUNTIME_DIR" in
/*) ;;
*) RUNTIME_DIR="$PWD/$RUNTIME_DIR" ;;
esac
# Not 8554: the Android emulator's gRPC service listens on 127.0.0.1:8554, and
# the guest's 10.0.2.2 alias resolves to host loopback — a testbed on 8554
# would be shadowed by the emulator itself.
PORT="${DOZECAM_TESTBED_PORT:-18554}"
APP_ID="app.dozecam.dev"
CAMERAS=(nursery porch)

usage() {
	cat >&2 <<'USAGE'
Usage: tools/testbed.sh <command>

  start            Start mediamtx and the synthetic camera publishers.
  stop             Stop everything and clean up runtime state.
  status           Report process health and probe each stream.
  noise on|off     Make the nursery camera loud (660 Hz tone) or silent.
  urls             Print the stream URLs for the emulator and this Mac's LAN IP.
  seed [-s SERIAL] [--host HOST]
                   Broadcast the testbed cameras into an installed dev build
                   (app.dozecam.dev) over adb. Defaults to the emulator's
                   host alias 10.0.2.2; pass --host for a physical device.

Environment: DOZECAM_TESTBED_PORT (default 18554), DOZECAM_TESTBED_DIR
(default /tmp/dozecam-testbed).
USAGE
	exit 64
}

require_tools() {
	for tool in "$@"; do
		command -v "$tool" >/dev/null || {
			echo "$tool not found on PATH (brew install $tool)" >&2
			exit 1
		}
	done
}

pid_file() { echo "$RUNTIME_DIR/$1.pid"; }

# $1: pid-file stem, $2: the launched pid. Records the pid together with the
# process start time so a recycled pid — even one recycled into another
# mediamtx/ffmpeg — is never mistaken for ours.
record_pid() {
	{
		echo "$2"
		ps -p "$2" -o lstart= 2>/dev/null || true
	} >"$(pid_file "$1")"
}

# $1: pid-file stem ("mediamtx" or "cam-<name>"). A stored pid counts only
# while it still belongs to the process we started — a recycled pid must never
# make status lie or, worse, let stop kill an unrelated process.
pid_alive() {
	local file pid recorded expected
	file="$(pid_file "$1")"
	[ -f "$file" ] || return 1
	pid="$(sed -n 1p "$file")"
	recorded="$(sed -n 2p "$file")"
	[ -n "$pid" ] || return 1
	case "$1" in
	mediamtx) expected=mediamtx ;;
	*) expected=ffmpeg ;;
	esac
	ps -p "$pid" -o comm= 2>/dev/null | grep -q "$expected" &&
		[ "$(ps -p "$pid" -o lstart= 2>/dev/null)" = "$recorded" ]
}

stop_process() {
	local file
	file="$(pid_file "$1")"
	if [ -f "$file" ]; then
		if pid_alive "$1"; then
			# Line 1 only: the file also carries the recorded start time.
			kill "$(sed -n 1p "$file")" 2>/dev/null || true
		fi
		rm -f "$file"
	fi
}

start_mediamtx() {
	cat >"$RUNTIME_DIR/mediamtx.yml" <<CONFIG
logLevel: info
api: no
metrics: no
pprof: no
playback: no
rtsp: yes
rtspAddress: :$PORT
rtspTransports: [tcp]
rtmp: no
hls: no
webrtc: no
srt: no
moq: no
paths:
  all_others:
CONFIG
	# cd: anything mediamtx generates next to itself (e.g. auto-created TLS
	# certs) must land in the runtime dir, not whatever cwd the caller had.
	(
		cd "$RUNTIME_DIR"
		nohup mediamtx "$RUNTIME_DIR/mediamtx.yml" >"$RUNTIME_DIR/mediamtx.log" 2>&1 &
		record_pid mediamtx $!
	)
	local tries=0
	until nc -z 127.0.0.1 "$PORT" 2>/dev/null; do
		# The port alone can lie during a rapid stop;start: the old, still
		# exiting daemon answers the probe while the new one failed its bind
		# and died. Our own process must stay alive throughout.
		if ! pid_alive mediamtx; then
			echo "mediamtx exited during startup (port $PORT taken?); see $RUNTIME_DIR/mediamtx.log" >&2
			exit 1
		fi
		tries=$((tries + 1))
		if [ "$tries" -gt 20 ]; then
			echo "mediamtx did not open port $PORT; see $RUNTIME_DIR/mediamtx.log" >&2
			exit 1
		fi
		sleep 0.25
	done
	sleep 0.3
	if ! pid_alive mediamtx; then
		echo "mediamtx exited right after startup (port $PORT taken?); see $RUNTIME_DIR/mediamtx.log" >&2
		exit 1
	fi
}

# $1: camera name, $2: "silent" or "noise"
start_publisher() {
	local name="$1" audio video
	case "$2" in
	noise) audio="sine=frequency=660:sample_rate=48000,volume=12dB" ;;
	*) audio="anullsrc=r=48000:cl=mono" ;;
	esac
	# testsrc renders a running time counter, so two screenshots taken a few
	# seconds apart prove the stream is actually live and not a frozen frame.
	case "$name" in
	nursery) video="testsrc=size=640x360:rate=15" ;;
	*) video="testsrc2=size=640x360:rate=15" ;;
	esac
	# 1s keyframe interval keeps join latency low for the player and the
	# watchdog; zerolatency avoids frame reordering delay.
	nohup ffmpeg -hide_banner -loglevel warning \
		-re -f lavfi -i "$video" \
		-re -f lavfi -i "$audio" \
		-c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p \
		-g 15 -b:v 500k \
		-c:a aac -b:a 64k \
		-f rtsp -rtsp_transport tcp "rtsp://127.0.0.1:$PORT/$name" \
		>"$RUNTIME_DIR/cam-$name.log" 2>&1 &
	record_pid "cam-$name" $!
	echo "$2" >"$RUNTIME_DIR/cam-$name.audio"
}

probe_stream() {
	ffprobe -v error -timeout 5000000 -rtsp_transport tcp \
		-select_streams v -show_entries stream=codec_name -of csv=p=0 \
		"rtsp://127.0.0.1:$PORT/$1" 2>/dev/null
}

wait_for_stream() {
	local tries=0
	until [ -n "$(probe_stream "$1")" ]; do
		tries=$((tries + 1))
		if [ "$tries" -gt 10 ]; then
			echo "stream /$1 never became readable; see $RUNTIME_DIR/cam-$1.log" >&2
			exit 1
		fi
		sleep 1
	done
}

cmd_start() {
	require_tools mediamtx ffmpeg ffprobe nc
	mkdir -p "$RUNTIME_DIR"
	# Atomic: two sessions racing into start must not both launch daemons and
	# overwrite each other's pid files. stop clears the lock (also the reset
	# for a lock left behind by a start that crashed partway).
	if ! mkdir "$RUNTIME_DIR/.start-lock" 2>/dev/null; then
		echo "testbed already running or starting (tools/testbed.sh status;" \
			"tools/testbed.sh stop resets a stale one)" >&2
		exit 1
	fi
	start_mediamtx
	start_publisher nursery silent
	start_publisher porch silent
	for cam in "${CAMERAS[@]}"; do
		wait_for_stream "$cam"
	done
	echo "testbed up on rtsp port $PORT"
	cmd_urls
}

cmd_stop() {
	for cam in "${CAMERAS[@]}"; do
		stop_process "cam-$cam"
		rm -f "$RUNTIME_DIR/cam-$cam.log" "$RUNTIME_DIR/cam-$cam.audio"
	done
	stop_process mediamtx
	rm -f "$RUNTIME_DIR/mediamtx.log" "$RUNTIME_DIR/mediamtx.yml"
	rmdir "$RUNTIME_DIR/.start-lock" 2>/dev/null || true
	# Only known files above, then a bare rmdir: a DOZECAM_TESTBED_DIR
	# override pointing at an existing directory must never be rm -rf'd.
	rmdir "$RUNTIME_DIR" 2>/dev/null || true
	echo "testbed stopped"
}

cmd_status() {
	local ok=0
	pid_alive mediamtx && echo "mediamtx: running (port $PORT)" ||
		{ echo "mediamtx: not running"; ok=1; }
	for cam in "${CAMERAS[@]}"; do
		local audio="?"
		[ -f "$RUNTIME_DIR/cam-$cam.audio" ] && audio="$(cat "$RUNTIME_DIR/cam-$cam.audio")"
		if pid_alive "cam-$cam"; then
			if [ -n "$(probe_stream "$cam")" ]; then
				echo "/$cam: publishing, readable (audio: $audio)"
			else
				echo "/$cam: publisher running but stream not readable"
				ok=1
			fi
		else
			echo "/$cam: not running"
			ok=1
		fi
	done
	return "$ok"
}

cmd_noise() {
	case "${1:-}" in
	on) local mode=noise ;;
	off) local mode=silent ;;
	*) usage ;;
	esac
	pid_alive mediamtx || { echo "testbed is not running" >&2; exit 1; }
	stop_process cam-nursery
	start_publisher nursery "$mode"
	wait_for_stream nursery
	echo "nursery audio: $mode"
}

lan_ip() { ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true; }

cmd_urls() {
	local ip
	ip="$(lan_ip)"
	for cam in "${CAMERAS[@]}"; do
		echo "emulator: rtsp://10.0.2.2:$PORT/$cam"
		# if, not &&: a Mac with no LAN address must not turn the missing
		# device line into a non-zero exit that start's set -e trips over.
		if [ -n "$ip" ]; then
			echo "device:   rtsp://$ip:$PORT/$cam"
		fi
	done
}

cmd_seed() {
	require_tools adb
	local host="10.0.2.2" serial=()
	while [ $# -gt 0 ]; do
		case "$1" in
		-s) serial=(-s "$2"); shift 2 ;;
		--host) host="$2"; shift 2 ;;
		*) usage ;;
		esac
	done
	local json="["
	local sep=""
	for cam in "${CAMERAS[@]}"; do
		json+="$sep{\"name\":\"Testbed $cam\",\"url\":\"rtsp://$host:$PORT/$cam\"}"
		sep=","
	done
	json+="]"
	adb "${serial[@]}" shell am broadcast \
		-a "$APP_ID.action.SEED_CAMERAS" \
		-n "$APP_ID/app.dozecam.dev.TestbedSeedReceiver" \
		--es cameras "'$json'"
}

case "${1:-}" in
start) cmd_start ;;
stop) cmd_stop ;;
status) cmd_status ;;
noise) shift; cmd_noise "$@" ;;
urls) cmd_urls ;;
seed) shift; cmd_seed "$@" ;;
*) usage ;;
esac
