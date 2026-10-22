#!/usr/bin/env bash
set -euo pipefail

# Download Obtainium (pinned upstream release, arm64-v8a, presigned with the upstream key) into
# packages/obtainium/prebuilt/. To update: change VERSION and SHA256.

VERSION="v1.7.1"
SHA256=04dfd02c56dae8d5271d2d402977e1b07c8eeffb313df054a13e71b75b8b8a26
URL="https://github.com/ImranR98/Obtainium/releases/download/$VERSION/app-arm64-v8a-release.apk"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/../packages/obtainium/prebuilt"
mkdir -p "$OUT"

if ! echo "$SHA256  $OUT/Obtainium.apk" | sha256sum -c --quiet &>/dev/null; then
    echo "Downloading Obtainium $VERSION"
    curl -fsSL -o "$OUT/Obtainium.apk.part" "$URL"
    echo "$SHA256  $OUT/Obtainium.apk.part" | sha256sum -c --quiet \
        || { rm -f "$OUT/Obtainium.apk.part"; echo "Checksum mismatch for Obtainium" >&2; exit 1; }
    mv "$OUT/Obtainium.apk.part" "$OUT/Obtainium.apk"
fi
echo "Obtainium $VERSION ready in $OUT"
