#!/usr/bin/env bash
set -euo pipefail

# Publishes the packages of the last build as a GitHub release of RELEASE_REPO (pocketds.conf):
#   tag v<version>, OTA/Full/*<version>*full.zip, OTA/Incremental/*<version>*incremental.zip,
#   OTA/update.json and the notes. Asks before publishing; --yes skips the question.
#
#   publish-release.sh [--yes] [--repo owner/name] [--prerelease]
#
# Needs the gh command logged in with the account that owns the repository (gh auth status).
# The complete package holds the proprietary files of the console: publish it only where you mean to.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"
OTA_DIR="$REPO_DIR/../OTA"
ASK=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes|-y) ASK=false ;;
        --repo) RELEASE_REPO="$2"; shift ;;
        --prerelease) RELEASE_PRERELEASE=true ;;
        -h|--help) sed -n '3,12p' "$0"; exit 0 ;;
        *) echo "Unknown option $1" >&2; exit 1 ;;
    esac
    shift
done

die() { echo "[ERROR] $*" >&2; exit 1; }
[[ -n "${RELEASE_REPO:-}" ]] || die "RELEASE_REPO is empty (Customization/pocketds.conf)"
command -v gh >/dev/null || die "gh not found (sudo pacman -S github-cli, then gh auth login)"
# (gh auth status fails as soon as ANY saved account has an expired token: ask GitHub instead)
ACCOUNT="$(gh api user --jq .login 2>/dev/null)" || die "gh is not logged in (gh auth login)"

VERSION="$(tr -d ' \n' < "$REPO_DIR/device/ayaneo/pocket_ds/VERSION")"
TAG="v$VERSION"
FULL="$(ls -t "$OTA_DIR"/Full/PocketDS-"$VERSION"-*-full.zip 2>/dev/null | head -1 || true)"
# every incremental package of this version (from the previous build and from the older releases)
mapfile -t INCS < <(ls -t "$OTA_DIR"/Incremental/PocketDS-"$VERSION"-*-incremental.zip 2>/dev/null || true)
INC="${INCS[0]:-}"
JSON="$OTA_DIR/update.json"
[[ -f "$FULL" ]] || die "No complete package for version $VERSION in $OTA_DIR/Full (build it first)"
[[ -f "$JSON" ]] || die "No $JSON (make-ota.sh writes it)"
grep -q "\"version\": \"$VERSION\"" "$JSON" || die "update.json is not the one of version $VERSION"
grep -q "/$RELEASE_REPO/releases/download/$TAG/" "$JSON" \
    || die "update.json points to another repository than $RELEASE_REPO: run make-ota.sh again after changing RELEASE_REPO"
gh repo view "$RELEASE_REPO" >/dev/null 2>&1 || die "Repository $RELEASE_REPO not found for this gh account"
if gh release view "$TAG" -R "$RELEASE_REPO" >/dev/null 2>&1; then die "Release $TAG already exists in $RELEASE_REPO"; fi

# The notes: the file of pocketds.conf if it is there, else a short text made from the packages.
NOTES="$REPO_DIR/../${RELEASE_NOTES:-OTA/Patch Notes/release-notes-%VERSION%.md}"
NOTES="${NOTES//%VERSION%/$VERSION}"
[[ -f "$NOTES" ]] || [[ ! -f "$OTA_DIR/release-notes-$VERSION.md" ]] || NOTES="$OTA_DIR/release-notes-$VERSION.md"
TMP_NOTES=""
if [[ ! -f "$NOTES" ]]; then
    TMP_NOTES="$(mktemp)"
    NOTES="$TMP_NOTES"
    {
        echo "# PocketDS $VERSION"
        echo
        echo "Unofficial LineageOS 24 build for the Ayaneo Pocket DS. **Installing wipes the console.**"
        echo
        echo "| File | SHA-256 |"
        echo "|---|---|"
        for f in "$FULL" "${INCS[@]}"; do
            [[ -f "$f" ]] && echo "| \`$(basename "$f")\` | \`$(sha256sum "$f" | cut -d' ' -f1)\` |"
        done
    } > "$NOTES"
fi

echo "Account    : $ACCOUNT"
echo "Repository : $RELEASE_REPO ($(gh repo view "$RELEASE_REPO" --json visibility --jq .visibility))"
echo "Tag        : $TAG$( [[ "${RELEASE_PRERELEASE:-false}" == true ]] && echo ' (pre-release)')"
echo "Files      : $(basename "$FULL") ($(du -h "$FULL" | cut -f1))"
for f in "${INCS[@]}"; do echo "             $(basename "$f") ($(du -h "$f" | cut -f1))"; done
echo "             update.json"
echo "Notes      : $NOTES"
if $ASK; then
    read -r -p "Publish? [y/N] " answer
    [[ "$answer" == [yY]* ]] || { echo "Cancelled"; exit 1; }
fi

files=("$FULL" "$JSON")
for f in "${INCS[@]}"; do [[ -f "$f" ]] && files+=("$f"); done
gh release create "$TAG" "${files[@]}" -R "$RELEASE_REPO" -t "PocketDS $VERSION" -F "$NOTES" \
    $([[ "${RELEASE_PRERELEASE:-false}" == true ]] && echo --prerelease)
[[ -z "$TMP_NOTES" ]] || rm -f "$TMP_NOTES"
# This build is now a release: the next ones make an incremental package from it (make-ota.sh).
if [[ -d "$OTA_DIR/Work/History/$VERSION" ]]; then
    touch "$OTA_DIR/Work/History/$VERSION/.published"
else
    echo "[WARN] OTA/Work/History/$VERSION is missing: the next releases cannot update from $VERSION in one step" >&2
fi
