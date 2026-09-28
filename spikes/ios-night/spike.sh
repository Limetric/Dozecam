#!/usr/bin/env bash
# Build, install, launch, kill and read the log of the #58 night spike.
# DEVICE defaults to the first connected physical device.
set -euo pipefail
cd "$(dirname "$0")"
APP_ID=app.dozecam.dev
DEVICE="${DEVICE:-$(xcrun devicectl list devices 2>/dev/null | awk '/physical/ && /connected/ {for (i=1;i<=NF;i++) if ($i ~ /^[0-9A-F]{8}-[0-9A-F]{16}$/) {print $i; exit}}')}"

case "${1:-}" in
build)
	xcodegen generate --quiet
	xcodebuild -project NightSpike.xcodeproj -scheme NightSpike -destination 'generic/platform=iOS' \
		-allowProvisioningUpdates -derivedDataPath build build -quiet
	;;
install) xcrun devicectl device install app --device "$DEVICE" build/Build/Products/Debug-iphoneos/NightSpike.app ;;
launch) shift; xcrun devicectl device process launch --device "$DEVICE" --terminate-existing "$APP_ID" -- "$@" ;;
# Simulates iOS terminating the app (jetsam): SIGKILL, no willTerminate.
kill)
	pid="$(xcrun devicectl device info processes --device "$DEVICE" 2>/dev/null | awk '/NightSpike.app\/NightSpike/ {print $1; exit}')"
	[ -n "$pid" ] || { echo "NightSpike is not running" >&2; exit 1; }
	xcrun devicectl device process signal --device "$DEVICE" --pid "$pid" --signal SIGKILL
	echo "killed pid $pid at $(date '+%H:%M:%S')"
	;;
log)
	out="${2:-./spike.log}"
	xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer \
		--domain-identifier "$APP_ID" --source Documents/spike.log --destination "$out" >/dev/null
	cat "$out"
	;;
*)
	echo "usage: spike.sh build|install|launch|kill|log [out]" >&2
	exit 64
	;;
esac
