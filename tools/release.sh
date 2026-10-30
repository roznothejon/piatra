#!/usr/bin/env bash
# Builds a release APK and puts it in dist/ as piatra-<version>.apk, plus its SHA-256 checksum.
# Run tests first, check that the APK is signed with the release key (not the debug key), and
# stop on any error. Usage, from the repository root: tools/release.sh
# See docs/building.md, "Release build".
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
[ -n "$version" ] || { echo "release: no versionName in app/build.gradle.kts" >&2; exit 1; }

./gradlew --quiet testDebugUnitTest assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner | sort -V | tail -n 1)

# The debug key's certificate is always "CN=Android Debug".
signer=$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate DN: //p')
if [ "$signer" = "C=US, O=Android, CN=Android Debug" ]; then
    echo "release: signed with the debug key; set up the release key first (docs/building.md)" >&2
    exit 1
fi

mkdir -p dist
out=dist/piatra-$version.apk
cp "$apk" "$out"
(cd dist && sha256sum "piatra-$version.apk" > "piatra-$version.apk.sha256")
echo "$out  ($signer)"
cat "$out.sha256"
