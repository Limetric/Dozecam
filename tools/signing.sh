#!/usr/bin/env bash
# Encrypts and decrypts the upload signing artifacts.
#
# Only the .enc files are committed; the plaintext keystore and properties are
# gitignored. CI decrypts them with the LIMETRIC_ENCRYPTION_SECRET org secret
# before building signed artifacts; see .github/workflows/android-release.yml.
set -euo pipefail

cd "$(dirname "$0")/.."

ARTIFACTS=(
	keystore_dozecam_upload.keystore
	keystore_dozecam_upload.properties
)

usage() {
	cat >&2 <<'USAGE'
Usage: tools/signing.sh <encrypt|decrypt>

  encrypt  Re-encrypt the plaintext signing artifacts into their .enc files
           (run after rotating the key, then commit the .enc files).
  decrypt  Restore the plaintext signing artifacts from their .enc files.

Requires LIMETRIC_ENCRYPTION_SECRET in the environment.
USAGE
	exit 64
}

require_secret() {
	if [ -z "${LIMETRIC_ENCRYPTION_SECRET:-}" ]; then
		echo "LIMETRIC_ENCRYPTION_SECRET is not set" >&2
		exit 1
	fi
}

crypt() {
	# $1: openssl direction flag ("-e" or "-d"), $2: input, $3: output
	openssl enc -aes-256-cbc -pbkdf2 -salt "$1" \
		-in "$2" \
		-out "$3" \
		-pass env:LIMETRIC_ENCRYPTION_SECRET
	chmod 600 "$3"
	echo "Wrote $3"
}

case "${1:-}" in
encrypt)
	require_secret
	for artifact in "${ARTIFACTS[@]}"; do
		if [ ! -f "$artifact" ]; then
			echo "Missing $artifact" >&2
			exit 1
		fi
		crypt -e "$artifact" "$artifact.enc"
	done
	;;
decrypt)
	require_secret
	for artifact in "${ARTIFACTS[@]}"; do
		if [ ! -f "$artifact.enc" ]; then
			echo "Missing $artifact.enc" >&2
			exit 1
		fi
		crypt -d "$artifact.enc" "$artifact"
	done
	;;
*)
	usage
	;;
esac
