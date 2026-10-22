#!/usr/bin/env bash
set -euo pipefail

# Version of the image: CalVer YEAR.MONTH.BUILD in device/ayaneo/pocket_ds/VERSION
# (BUILD counts the releases of the month, 1 the first; it restarts at 1 each month).
#
#   release.sh            show the current version
#   release.sh next       bump: BUILD+1, or YEAR.MONTH.1 when the month changed
#   release.sh set X.Y.Z  set it by hand
#
# Run it before ./build.sh, then commit VERSION. versionCode = YEAR*10000 + MONTH*100 + BUILD.

F="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/device/ayaneo/pocket_ds/VERSION"
cur="$(tr -d ' \n' < "$F")"
code() { awk -F. '{printf "%d", $1*10000+$2*100+$3}' <<<"$1"; }

case "${1:-show}" in
    show) ;;
    next)
        ym="$(date +%Y.%m | sed 's/\.0*/./')"   # 2026.10 (month without leading zero)
        if [[ "${cur%.*}" == "$ym" ]]; then
            new="$ym.$(( ${cur##*.} + 1 ))"
        else
            new="$ym.1"
        fi
        echo "$new" > "$F"; cur="$new" ;;
    set)
        [[ "${2:-}" =~ ^[0-9]{4}\.[0-9]{1,2}\.[0-9]{1,2}$ ]] || { echo "Format: YEAR.MONTH.BUILD" >&2; exit 1; }
        echo "$2" > "$F"; cur="$2" ;;
    *) echo "Usage: release.sh [show|next|set X.Y.Z]" >&2; exit 1 ;;
esac
echo "version $cur  (versionCode $(code "$cur"))"
