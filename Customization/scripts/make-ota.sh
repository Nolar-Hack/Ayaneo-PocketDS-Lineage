#!/usr/bin/env bash
# Builds the two update packages of the build that just finished, and prepares the next comparison.
#
#   make-ota.sh [--no-incremental] [--no-promote] [--base DIR] [--out DIR]
#
#   OTA/Full/<name>-full.zip                   complete package: new installation (recovery) or updater
#   OTA/Incremental/<name>-incremental.zip     only what changed since the previous build:
#                                              Settings > System > Updater > local update
#
# OTA/Work/ (Backup/ only holds the original, from the console as it was):
#   Current/    the build that just finished (target files, init_boot with root): filled here, it is
#               the source of the new packages
#   Compare/    the previous build, base of the incremental package. At the end, once the packages
#               are made, Current is copied into Compare (hard links, no extra space): it becomes
#               the base of the next build. --no-promote leaves Compare as it is.
#
# The target files hold the stock init_boot, without root: the one patched by build.sh replaces it
# in both packages. They are signed with the key of the build (test key) and made for Virtual A/B.
# An incremental package only applies to a device that runs exactly the Compare build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"
SRC_DIR="${SRC_DIR:-$REPO/../src}"
PRODUCT_OUT="$SRC_DIR/out/target/product/pocket_ds"
OTA_DIR="$REPO/../OTA"
COMPARE=""
INCREMENTAL=true
PROMOTE=true
# Devices the packages accept. The build is PocketDS, but a recovery reports the board name
# (kalama) and sideload refuses "package is for product PocketDS but expected kalama".
ASSERT_DEVICES="${OTA_ASSERT_DEVICES:-PocketDS|kalama}" # pocketds.conf

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-incremental) INCREMENTAL=false ;;
        --no-promote) PROMOTE=false ;;
        --base) COMPARE="$2"; shift ;;
        --out) OTA_DIR="$2"; shift ;;
        *) echo "Unknown option $1" >&2; exit 1 ;;
    esac
    shift
done

# OTA/Work: Current (this build) and Compare (the previous one) live next to the packages they make.
WORKROOT="$OTA_DIR/Work"
CURRENT="$WORKROOT/Current"
[[ -n "$COMPARE" ]] || COMPARE="$WORKROOT/Compare"

NEW_TARGET_FILES="$PRODUCT_OUT/obj/PACKAGING/target_files_intermediates/lineage_pocket_ds-target_files"
[[ -d "$NEW_TARGET_FILES" ]] || { echo "No target files of the build: $NEW_TARGET_FILES" >&2; exit 1; }
[[ -f "$PRODUCT_OUT/init_boot.img" ]] || { echo "No init_boot.img in $PRODUCT_OUT" >&2; exit 1; }

mkdir -p "$OTA_DIR/Full" "$OTA_DIR/Incremental" "$WORKROOT"
# The work files (zipped target files, payload blobs) take tens of GB: not in /tmp (small, in RAM),
# in a folder of the project disk that is emptied at the end.
export TMPDIR="$WORKROOT/tmp"
rm -rf "$TMPDIR"; mkdir -p "$TMPDIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# --- Current: the build that just finished
echo "== Current (this build)"
rm -rf "$CURRENT.new"; mkdir -p "$CURRENT.new"
rsync -a "$NEW_TARGET_FILES" "$CURRENT.new/"
mv "$CURRENT.new/lineage_pocket_ds-target_files" "$CURRENT.new/target_files"
cp "$PRODUCT_OUT/init_boot.img" "$CURRENT.new/init_boot.img"
(cd "$PRODUCT_OUT" && sha256sum boot.img init_boot.img vendor_boot.img dtbo.img vbmeta.img \
    vbmeta_system.img system.img system_ext.img product.img vendor.img odm.img vendor_dlkm.img \
    system_dlkm.img) > "$CURRENT.new/images.sha256"
{
    echo "build: $(sed -n 's/^LINEAGE_VERSION=//p' "$SRC_DIR/out/soong/build_config.txt" 2>/dev/null || true)"
    echo "date: $(date '+%Y-%m-%d %H:%M')"
    echo "fingerprint: $(sed -n 's/^ro.build.fingerprint=//p' "$PRODUCT_OUT/system/build.prop" 2>/dev/null)"
} > "$CURRENT.new/INFO"
rm -rf "$CURRENT"; mv "$CURRENT.new" "$CURRENT"

# ota_from_target_files wants zips; the stock init_boot of the target files is replaced by the one
# with root.
prepare() {
    local dir="$1" dst="$2"
    (cd "$dir/target_files" && zip -qry -1 "$dst" .)
    mkdir -p "$WORK/inj/IMAGES"
    cp "$dir/init_boot.img" "$WORK/inj/IMAGES/init_boot.img"
    (cd "$WORK/inj" && zip -q "$dst" IMAGES/init_boot.img)
    # accept the board name too: a recovery answers "kalama", not PocketDS (the key read by releasetools)
    mkdir -p "$WORK/inj/META"
    unzip -p "$dst" META/misc_info.txt | grep -v '^ota_override_device=' > "$WORK/inj/META/misc_info.txt"
    echo "ota_override_device=$ASSERT_DEVICES" >> "$WORK/inj/META/misc_info.txt"
    (cd "$WORK/inj" && zip -q "$dst" META/misc_info.txt)
}
echo "== target files"
prepare "$CURRENT" "$WORK/new.zip"
KEY="$(unzip -p "$WORK/new.zip" META/misc_info.txt | sed -n 's/^default_system_dev_certificate=//p')"
[[ -n "$KEY" ]] || KEY=build/make/target/product/security/testkey
VERSION="$(tr -d ' \n' < "$REPO/device/ayaneo/pocket_ds/VERSION")"
VERSION_CODE="$(awk -F. '{printf "%d", $1*10000+$2*100+$3}' <<<"$VERSION")"
NAME="PocketDS-$VERSION-lineage-24.0-$(date +%Y%m%d)"
# Where the packages are published (GitHub release v<version>): base of the urls in update.json.
RELEASE_URL="${POCKETDS_RELEASE_URL:-https://github.com/${RELEASE_REPO:-Nolar-Hack/Ayaneo-PocketDS-Lineage-Releases}/releases/download}"

cd "$SRC_DIR"
# The signing step of the package runs java (SignApk): the JDK of the Lineage tree.
JAVA_BIN="$(ls -d "$SRC_DIR"/prebuilts/jdk/jdk*/linux-x86/bin 2>/dev/null | sort -V | tail -1)"
export PATH="$SRC_DIR/out/host/linux-x86/bin:$JAVA_BIN:$PATH"
command -v java >/dev/null || { echo "java not found (looked in $JAVA_BIN)" >&2; exit 1; }

echo "== full package (key $KEY)"
ota_from_target_files -k "$KEY" "$WORK/new.zip" "$OTA_DIR/Full/$NAME-full.zip"
(cd "$OTA_DIR/Full" && sha256sum "$NAME-full.zip" > "$NAME-full.zip.sha256sum")

if [[ "$INCREMENTAL" == true && -d "$COMPARE/target_files" && -f "$COMPARE/init_boot.img" ]]; then
    echo "== incremental package (from $(grep '^date:' "$COMPARE/INFO" 2>/dev/null || echo "$COMPARE"))"
    prepare "$COMPARE" "$WORK/base.zip"
    ota_from_target_files -k "$KEY" -i "$WORK/base.zip" "$WORK/new.zip" \
        "$OTA_DIR/Incremental/$NAME-incremental.zip"
    (cd "$OTA_DIR/Incremental" && sha256sum "$NAME-incremental.zip" > "$NAME-incremental.zip.sha256sum")
elif [[ "$INCREMENTAL" == true ]]; then
    echo "== no incremental package: Compare is empty ($COMPARE)"
fi

# --- update.json: what PocketDS Extra reads to offer the update (publish it next to the release).
# "incremental" only applies to a device whose ro.build.version.incremental equals its "from".
meta() { unzip -p "$1" META-INF/com/android/metadata | sed -n "s/^$2=//p"; }
FULL="$OTA_DIR/Full/$NAME-full.zip"
INC="$OTA_DIR/Incremental/$NAME-incremental.zip"
export NAME VERSION VERSION_CODE RELEASE_URL FULL INC
# "notes" of update.json: the "- " lines of OTA/release-notes-<version>.md, shown in PocketDS Extra.
NOTES_FILE="$OTA_DIR/release-notes-$VERSION.md"
export NOTES_TEXT="$( [[ -f "$NOTES_FILE" ]] && sed -n 's/^- //p' "$NOTES_FILE" | sed 's/^/• /' || true )"
export BUILD_ID="$(meta "$FULL" post-build-incremental)"
[[ -f "$INC" ]] && export FROM_ID="$(meta "$INC" pre-build-incremental)" || export FROM_ID=""
python3 - <<'PY' > "$OTA_DIR/update.json"
import json, os, hashlib
def entry(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 24), b''): h.update(chunk)
    return {"file": os.path.basename(path),
            "url": "%s/v%s/%s" % (os.environ["RELEASE_URL"], os.environ["VERSION"], os.path.basename(path)),
            "size": os.path.getsize(path), "sha256": h.hexdigest()}
e = os.environ
d = {"version": e["VERSION"], "versionCode": int(e["VERSION_CODE"]), "buildId": e["BUILD_ID"],
     "date": __import__("datetime").date.today().isoformat(), "notes": e["NOTES_TEXT"], "full": entry(e["FULL"]), "incremental": None}
if e["FROM_ID"] and os.path.isfile(e["INC"]):
    d["incremental"] = dict(entry(e["INC"]), **{"from": e["FROM_ID"]})
print(json.dumps(d, indent=2))
PY
echo "update.json: $OTA_DIR/update.json (version $VERSION, code $VERSION_CODE)"

# --- Compare: this build becomes the base of the next one (hard links: no extra space)
if [[ "$PROMOTE" == true ]]; then
    echo "== Compare := Current"
    rm -rf "$WORKROOT/Compare.new"
    cp -al "$CURRENT" "$WORKROOT/Compare.new"
    rm -rf "$WORKROOT/Compare"
    mv "$WORKROOT/Compare.new" "$WORKROOT/Compare"
fi
echo "== done"
ls -la "$OTA_DIR/Full" "$OTA_DIR/Incremental"
