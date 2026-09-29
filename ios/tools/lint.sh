#!/usr/bin/env bash
# Lints (default) or formats the iOS sources with the toolchain's
# swift-format and ios/.swift-format. CI runs the lint.
#
#   ios/tools/lint.sh         fail on any finding
#   ios/tools/lint.sh --fix   rewrite files in place
set -euo pipefail
cd "$(dirname "$0")/.."

sources=(Dozecam DozecamTests)
if [ "${1:-}" = "--fix" ]; then
	xcrun swift-format format --in-place --recursive --parallel "${sources[@]}"
else
	xcrun swift-format lint --strict --recursive --parallel "${sources[@]}"
fi
