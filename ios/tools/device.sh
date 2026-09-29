#!/usr/bin/env bash
# Builds a variant and installs it on a paired iPhone or iPad, over the
# network once wireless installs are set up (Xcode → Devices and Simulators →
# Connect via network). Signing is automatic; -allowProvisioningUpdates lets
# xcodebuild register the device and sync the App ID's capabilities.
#
#   ios/tools/device.sh list                    paired devices
#   ios/tools/device.sh install [--prod]        build and install (dev by default)
#   ios/tools/device.sh launch  [--prod] [-- ARGS]
#                                               launch without the debugger, so the
#                                               app is suspended like a normal launch
#
# DEVICE picks the device (UDID or name). Without it: the only paired
# physical device, or the one whose connection is up; with several and no
# clear choice, the script asks for DEVICE. Launching needs the device unlocked.
set -euo pipefail
cd "$(dirname "$0")/.."

command="${1:-}"
shift || true
config=Dev
bundle=app.dozecam.dev
if [ "${1:-}" = "--prod" ]; then
	config=Release
	bundle=app.dozecam
	shift
fi
[ "${1:-}" = "--" ] && shift

device() {
	if [ -n "${DEVICE:-}" ]; then
		echo "$DEVICE"
		return
	fi
	local json
	json="$(mktemp)"
	xcrun devicectl list devices --json-output "$json" >/dev/null
	trap 'rm -f "$json"' RETURN
	python3 - "$json" <<'PY'
import json, sys
devices = [
    d for d in json.load(open(sys.argv[1]))["result"]["devices"]
    if d.get("hardwareProperties", {}).get("reality") == "physical"
    and d.get("connectionProperties", {}).get("pairingState") == "paired"
]
if not devices:
    sys.exit("no paired physical device; pair one in Xcode → Devices and Simulators")
# A device reachable over the network often reports its tunnel as
# disconnected until devicectl reconnects it, so "connected" only breaks ties.
connected = [d for d in devices if d.get("connectionProperties", {}).get("tunnelState") == "connected"]
pick = connected if len(connected) == 1 else devices
if len(pick) > 1:
    names = ", ".join(f'{d["deviceProperties"]["name"]} ({d["hardwareProperties"]["udid"]})' for d in pick)
    sys.exit(f"several paired devices, set DEVICE to one of: {names}")
print(pick[0]["hardwareProperties"]["udid"])
PY
}

case "$command" in
list)
	xcrun devicectl list devices
	;;
install)
	tools/generate.sh
	xcodebuild build -project Dozecam.xcodeproj -scheme Dozecam -configuration "$config" \
		-destination 'generic/platform=iOS' -derivedDataPath build/DerivedData \
		-allowProvisioningUpdates -quiet
	# Assigned first: a failed lookup inside an argument would not stop set -e.
	udid="$(device)"
	xcrun devicectl device install app --device "$udid" \
		"build/DerivedData/Build/Products/$config-iphoneos/Dozecam.app"
	;;
launch)
	udid="$(device)"
	xcrun devicectl device process launch --device "$udid" --terminate-existing "$bundle" -- "$@"
	;;
*)
	sed -n '2,15p' "$0" >&2
	exit 64
	;;
esac
