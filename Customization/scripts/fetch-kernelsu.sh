#!/usr/bin/env bash
set -euo pipefail

# Download KernelSU Next (pinned release, checksums from the GitHub release) into
# packages/kernelsu/prebuilt/:
#   - the LKM for the Pocket DS kernel KMI (GKI android13-5.15)
#   - ksud for Linux, used by scripts/build.sh to patch init_boot on the PC
#   - the manager app, preinstalled in the ROM
# To update: change VERSION and the three SHA256 values (release page "digest").

VERSION="v3.4.2"
MANAGER="KernelSU_Next_${VERSION}_33355-release.apk"
declare -A FILES=(
    [aarch64_android13-5.15_kernelsu.ko]=d58d71d00e1ff49998a9c6d690fa2d8aa9567959ecff399a1b9ccc20199e68f0
    [ksud-x86_64-unknown-linux-musl]=4d22f89416e7eef8f684f1ed03d2bea19da33c7d41266546e94fd7d1f6cbeca9
    [$MANAGER]=774a5511f7034a4930e881114e202319cbdc34c5aaaa5c7c6b6d2902364e4c58
)
URL="https://github.com/KernelSU-Next/KernelSU-Next/releases/download/$VERSION"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/../packages/kernelsu/prebuilt"
mkdir -p "$OUT"

for f in "${!FILES[@]}"; do
    if [[ ! -f "$OUT/$f" ]] || ! echo "${FILES[$f]}  $OUT/$f" | sha256sum -c --quiet &>/dev/null; then
        echo "Downloading $f"
        curl -fsSL -o "$OUT/$f.part" "$URL/$f"
        echo "${FILES[$f]}  $OUT/$f.part" | sha256sum -c --quiet \
            || { rm -f "$OUT/$f.part"; echo "Checksum mismatch for $f" >&2; exit 1; }
        mv "$OUT/$f.part" "$OUT/$f"
    fi
done
chmod +x "$OUT/ksud-x86_64-unknown-linux-musl"
cp -f "$OUT/$MANAGER" "$OUT/KernelSUNext.apk"
echo "KernelSU Next $VERSION ready in $OUT"
