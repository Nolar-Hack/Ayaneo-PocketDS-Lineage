#!/usr/bin/env bash
# Before a build: is there a newer version of what the image carries from outside?
#
#   check-updates.sh
#
# KernelSU Next and Obtainium (scripts/fetch-*.sh), the TurnipZ GPU driver and the Obtainium
# Emulation Pack (assets of PocketDS Extra). Reads the latest GitHub releases (gh), changes nothing.
# LineageOS itself is updated by ./build.sh --all (repo sync).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS="$REPO/device/ayaneo/pocket_ds/parts/assets"
command -v gh >/dev/null || { echo "gh not found" >&2; exit 1; }

outdated=0
show() {   # <name> <ours> <latest> <where to change it>
    if [[ "$2" == "$3" ]]; then
        printf '  [ok]     %-24s %s\n' "$1" "$2"
    else
        printf '  [update] %-24s %s -> %s   (%s)\n' "$1" "$2" "$3" "$4"
        outdated=$((outdated + 1))
    fi
}
latest() { gh release view -R "$1" --json tagName -q .tagName 2>/dev/null || echo "?"; }

echo "External components"
show "KernelSU Next" "$(sed -n 's/^VERSION="\(.*\)"/\1/p' "$SCRIPT_DIR/fetch-kernelsu.sh")" \
    "$(latest KernelSU-Next/KernelSU-Next)" "scripts/fetch-kernelsu.sh: VERSION and the SHA256"
show "Obtainium" "$(sed -n 's/^VERSION="\(.*\)"/\1/p' "$SCRIPT_DIR/fetch-obtainium.sh")" \
    "$(latest ImranR98/Obtainium)" "scripts/fetch-obtainium.sh: VERSION and SHA256"
turnip="$(ls "$ASSETS"/TurnipZ-*.zip 2>/dev/null | sed 's/.*TurnipZ-\(.*\)\.zip/v\1/' | head -1)"
show "TurnipZ" "${turnip:-?}" "$(latest AlexSanz0902/turnip-sd865-z)" \
    "parts/assets/TurnipZ-*.zip and DRIVER/MARK in GpuDriverPack.java"

# the pack has no version inside: compare it with the file of the latest release
pack_tag="$(latest RJNY/Obtainium-Emulation-Pack)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
if gh release download "$pack_tag" -R RJNY/Obtainium-Emulation-Pack -p '*.json' -D "$tmp" >/dev/null 2>&1; then
    theirs="$(sha256sum "$tmp"/*.json | cut -d' ' -f1 | head -1)"
    ours="$(sha256sum "$ASSETS/obtainium-emulation-pack.json" | cut -d' ' -f1)"
    if [[ "$theirs" == "$ours" ]]; then
        show "Obtainium Emulation Pack" "$pack_tag" "$pack_tag" ""
    else
        show "Obtainium Emulation Pack" "older" "$pack_tag" \
            "replace parts/assets/obtainium-emulation-pack.json with $(basename "$tmp"/*.json)"
    fi
else
    show "Obtainium Emulation Pack" "?" "$pack_tag" "could not download it"
fi

echo
if (( outdated )); then echo "$outdated to update before the next build."; else echo "Everything is up to date."; fi
