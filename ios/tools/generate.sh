#!/usr/bin/env bash
# Generates ios/Dozecam.xcodeproj from ios/project.yml, after writing the
# version into Config/Version.xcconfig (both gitignored). Run it after every
# pull or change to project.yml; build.sh and device.sh run it for you.
#
#   CURRENT_PROJECT_VERSION  git rev-list --count HEAD (monotonic, like Android)
#   MARKETING_VERSION        the latest ios-v* tag, else 0.1.0. Tags without
#                            the ios- prefix are Android releases (#63).
set -euo pipefail
cd "$(dirname "$0")/.."

command -v xcodegen >/dev/null || {
	echo "xcodegen not found (brew install xcodegen)" >&2
	exit 1
}

build="$(git rev-list --count HEAD 2>/dev/null || echo 1)"
tag="$(git describe --tags --abbrev=0 --match 'ios-v*' 2>/dev/null || true)"
marketing="${tag#ios-v}"
[[ "$marketing" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || marketing="0.1.0"

mkdir -p Config
cat >Config/Version.xcconfig <<XCCONFIG
// Written by ios/tools/generate.sh; do not edit or commit.
MARKETING_VERSION = $marketing
CURRENT_PROJECT_VERSION = $build
XCCONFIG

xcodegen generate --quiet --spec project.yml
echo "Generated ios/Dozecam.xcodeproj (version $marketing, build $build)"
