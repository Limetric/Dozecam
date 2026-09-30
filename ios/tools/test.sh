#!/usr/bin/env bash
# Generates the project, then builds and runs the unit tests on an iPhone and
# an iPad simulator (the newest runtime's first iPhone and iPad). CI runs
# exactly this.
#
# The build is signed ad hoc ("-"), which needs no certificate or team, rather
# than left unsigned: an unsigned app has no application-identifier
# entitlement, and the Keychain refuses it (errSecMissingEntitlement, -34018),
# so the Keychain tests could not run.
#
#   ios/tools/test.sh              both simulators
#   ios/tools/test.sh iphone|ipad  one of them
set -euo pipefail
cd "$(dirname "$0")/.."

tools/generate.sh

# Prints the UDID of the first available simulator whose name starts with $1,
# on the newest iOS runtime installed.
simulator() {
	xcrun simctl list devices available -j | python3 -c '
import json, sys
prefix = sys.argv[1]
runtimes = json.load(sys.stdin)["devices"]
ios = sorted((r for r in runtimes if ".SimRuntime.iOS-" in r),
             key=lambda r: [int(p) for p in r.rsplit("iOS-", 1)[1].split("-")])
for runtime in reversed(ios):
    for device in runtimes[runtime]:
        if device["name"].startswith(prefix):
            print(device["udid"]); sys.exit(0)
sys.exit("no available " + prefix + " simulator")
' "$1"
}

case "${1:-both}" in
iphone) families=(iPhone) ;;
ipad) families=(iPad) ;;
both) families=(iPhone iPad) ;;
*)
	echo "usage: test.sh [iphone|ipad]" >&2
	exit 64
	;;
esac

for family in "${families[@]}"; do
	udid="$(simulator "$family")"
	echo "== $family simulator $udid"
	xcodebuild test -project Dozecam.xcodeproj -scheme "Dozecam Dev" \
		-destination "platform=iOS Simulator,id=$udid" \
		-derivedDataPath build/DerivedData -quiet \
		CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM=
done
