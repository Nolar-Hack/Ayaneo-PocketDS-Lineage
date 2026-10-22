#!/usr/bin/env bash
set -euo pipefail

# Copy the Ayaneo apps from a Pocket DS running the stock ROM (adb, no root
# needed) into packages/ayaneo-apps/prebuilt/. Takes the installed version,
# i.e. the latest update when one was installed over the system copy.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/../packages/ayaneo-apps/prebuilt"

declare -A APPS=(
    [com.ayaneo.home]=AYAHome
    [com.ayaneo.settings]=AYASettings
    [com.ayaneo.gamelauncher]=AYASpace
    [com.ayaneo.gamewindow]=AYAWindow
    [com.aya.gsset]=AyaGsSet
    [com.ayaneo.ayakeytester]=AyaKeyAR06Tester
)

command -v adb &>/dev/null || { echo "adb not found" >&2; exit 1; }
adb get-state &>/dev/null || { echo "No device over adb" >&2; exit 1; }
mkdir -p "$OUT"

for pkg in "${!APPS[@]}"; do
    name="${APPS[$pkg]}"
    paths="$(adb shell pm path "$pkg" | tr -d '\r' | sed 's/^package://')"
    if [[ -z "$paths" ]]; then
        echo "skip $pkg (not installed)"
        continue
    fi
    if [[ "$(wc -l <<<"$paths")" -ne 1 ]]; then
        echo "skip $pkg (split APK not supported)" >&2
        continue
    fi
    version="$(adb shell dumpsys package "$pkg" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)"
    adb pull "$paths" "$OUT/$name.apk" >/dev/null
    echo "$name.apk  <- $pkg $version"
done

# AYAHome stays the stock APK: the Keyboard tab (tools/ayahome-keyboard) was abandoned, the
# system keyboard (Gboard) on the second screen replaces it. Refuse an already patched APK.
if [[ -f "$OUT/AYAHome.apk" ]] && unzip -p "$OUT/AYAHome.apk" 'classes*.dex' | grep -aq 'Lorg/pocketds/kbd/KbAddon;'; then
    echo "AYAHome.apk has the abandoned Keyboard tab: use AYAHome.stock.apk instead" >&2
    cp "$OUT/AYAHome.stock.apk" "$OUT/AYAHome.apk"
fi
