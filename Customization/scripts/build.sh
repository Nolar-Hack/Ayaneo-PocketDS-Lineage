#!/usr/bin/env bash
set -euo pipefail

# Build LineageOS 24.0 for the Ayaneo Pocket DS.
#
# This repo holds the device tree, the prebuilt kernel and the Ayaneo apps; the
# LineageOS sources live in a separate tree (default: ../src (next to Customization/)). Soong
# does not follow symlinked directories, so the repo is copied into the tree
# before every build.
#
# Phases (run in this order with --all):
#   --sync      repo init/sync of LineageOS 24.0 + MindTheGapps
#   --copy      copy device/, kernel/, packages/ into the source tree
#   --extract   extract blobs from a scripts/backup-stock.sh backup
#   --build     lunch lineage_pocket_ds-cp2a-<variant> && m bacon

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
die()   { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }
step()  { echo -e "${CYAN}[STEP]${NC} $*"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$REPO_DIR/../src"
STOCK_BACKUP=""
VARIANT="userdebug"
JOBS=8
SYNC_JOBS=8
# The build runs in a systemd user scope: past MEM_HIGH it is throttled and
# pushed to swap, past MEM_MAX the build (not the desktop session) gets killed.
# Soong analysis of LineageOS 24 peaks at 25+ GB on its own.
MEM_HIGH=20G
MEM_MAX=25G
DO_SYNC=false
DO_ANALYZE=false
BRINGUP=false
KERNELSU=true
MAKE_OTA=true
CHECK=true
FORCE_EXTRACT=false
# GApps in the image: mindthegapps (default) or none (room kept in product to flash NikGApps...)
GAPPS=mindthegapps
DO_COPY=false
DO_EXTRACT=false
DO_BUILD=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [PHASES] [OPTIONS]

Phases:
  --all                 --sync --copy --extract --build
  --sync                repo init + sync LineageOS 24.0 (+ MindTheGapps)
  --copy                copy this repo into the source tree
  --extract             extract proprietary blobs (needs --stock-backup)
  --analyze             run only the Soong/Kati analysis (m nothing): fast config check
  --build               lunch + m bacon

Options:
  --src DIR             LineageOS source tree (default: $SRC_DIR)
  --stock-backup DIR    backup made by scripts/backup-stock.sh
  --variant V           userdebug (default) | user | eng
  --jobs N              build parallelism (default: $JOBS)
  --sync-jobs N         parallel downloads for repo sync (default: 8; lower it if the server resets connections)
  --mem-high SIZE       throttle the build above SIZE (default: $MEM_HIGH)
  --mem-max SIZE        kill the build above SIZE (default: $MEM_MAX)
  --no-kernelsu         do not patch KernelSU Next into init_boot (root is on by default)
  --no-ota              do not make the full and incremental update packages (OTA/) at the end
  --skip-checks         do not check the prerequisites first (scripts/check-prereqs.sh)
  --force-extract       extract the blobs from --stock-backup even if they are already in place
  --gapps G             mindthegapps (default): MindTheGapps in the image, 1 GiB kept free in
                        product for the next builds | none: no Google apps in the image and
                        the large Lineage reserve (1.96 GB) in product, to flash NikGApps or
                        another package afterwards
  --bringup             debug build: adb without authorization, ro.debuggable=1
                        (WITH_ADB_INSECURE=true) — for first-boot diagnosis only
EOF
    exit 0
}

[[ $# -gt 0 ]] || usage
while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)          DO_SYNC=true; DO_COPY=true; DO_EXTRACT=true; DO_BUILD=true ;;
        --sync)         DO_SYNC=true ;;
        --copy)         DO_COPY=true ;;
        --extract)      DO_EXTRACT=true ;;
        --build)        DO_BUILD=true ;;
        --analyze)      DO_ANALYZE=true ;;
        --mem-high)     MEM_HIGH="$2"; shift ;;
        --mem-max)      MEM_MAX="$2"; shift ;;
        --bringup)      BRINGUP=true ;;
        --no-kernelsu)  KERNELSU=false ;;
        --no-ota)       MAKE_OTA=false ;;
        --skip-checks)  CHECK=false ;;
        --force-extract) FORCE_EXTRACT=true ;;
        --gapps)        GAPPS="$2"; shift ;;
        --src)          SRC_DIR="$2"; shift ;;
        --stock-backup) STOCK_BACKUP="$2"; shift ;;
        --variant)      VARIANT="$2"; shift ;;
        --jobs)         JOBS="$2"; shift ;;
        --sync-jobs)    SYNC_JOBS="$2"; shift ;;
        --help|-h)      usage ;;
        *)              die "Unknown option: $1" ;;
    esac
    shift
done

# repo needs a git identity; keep it local to the source tree.
export GIT_CONFIG_GLOBAL="$SRC_DIR/.gitconfig-build"

phase_sync() {
    step "Syncing LineageOS 24.0 into $SRC_DIR"
    mkdir -p "$SRC_DIR/.repo/local_manifests"
    [[ -f "$GIT_CONFIG_GLOBAL" ]] || printf '[user]\n\tname = Pocket DS builder\n\temail = builder@localhost\n[color]\n\tui = false\n' > "$GIT_CONFIG_GLOBAL"
    cp "$REPO_DIR"/manifests/*.xml "$SRC_DIR/.repo/local_manifests/"
    (cd "$SRC_DIR" &&
        repo init -u https://github.com/LineageOS/android.git -b lineage-24.0 --git-lfs --depth=1 --no-clone-bundle &&
        repo sync -c -j"$SYNC_JOBS" --force-sync --no-tags --no-clone-bundle --optimized-fetch --retry-fetches=3)
    fetch_lfs
    ok "Sources synced"
}

# Large prebuilts (the WebView apk, 258 MB) are git-lfs files; a sync that ended with
# errors leaves 134-byte pointers instead of them, and the build fails after hours.
fetch_lfs() {
    step "Fetching git-lfs files (WebView...)"
    # our private GIT_CONFIG_GLOBAL replaces the user's one, which holds the lfs filters
    git lfs install --skip-repo >/dev/null
    (cd "$SRC_DIR" && repo forall -j4 -c 'git lfs pull' 2>&1 | grep -i -E 'error|fatal' | head -5) || true
    if grep -qs '^version https://git-lfs' "$SRC_DIR"/external/chromium-webview/prebuilt/*/webview.apk; then
        die "The WebView apk is still a git-lfs pointer: check the network and run --sync again"
    fi
}

# Third-party binaries that are not in the repository (pinned versions, checked by sha256), then the
# files that only a stock backup of the console can provide.
phase_fetch() {
    step "Downloading KernelSU Next and Obtainium"
    "$SCRIPT_DIR/fetch-kernelsu.sh"
    "$SCRIPT_DIR/fetch-obtainium.sh"
    local missing=()
    [[ -f "$REPO_DIR/kernel/ayaneo/kalama/Image" ]] || missing+=("kernel/ayaneo/kalama (stock kernel, DTB, modules)")
    [[ -f "$REPO_DIR/packages/gboard/prebuilt/LatinImeGoogle.apk" ]] || missing+=("packages/gboard/prebuilt/LatinImeGoogle.apk (stock Gboard)")
    [[ -d "$REPO_DIR/vendor/ayaneo/pocket_ds/proprietary" || -d "$SRC_DIR/vendor/ayaneo/pocket_ds/proprietary" || -n "$STOCK_BACKUP" ]] \
        || missing+=("vendor/ayaneo/pocket_ds/proprietary (blobs: --stock-backup <dir>)")
    if (( ${#missing[@]} )); then
        printf '  missing: %s\n' "${missing[@]}" >&2
        die "Proprietary files of your own console are missing (Ayaneo's, not redistributable): see README, \"Proprietary files\""
    fi
}

phase_copy() {
    [[ -d "$SRC_DIR/build/make" ]] || die "No LineageOS tree in $SRC_DIR (run --sync)"
    step "Copying device tree and kernel into the source tree"
    for d in device/ayaneo/pocket_ds kernel/ayaneo/kalama packages/kernelsu packages/gboard packages/obtainium vendor/ayaneo; do
        # vendor/ayaneo (proprietary blobs, extracted once from the console) is kept in Customization/vendor
        [[ -d "$REPO_DIR/$d" ]] || continue
        mkdir -p "$SRC_DIR/$d"
        rsync -a --delete "$REPO_DIR/$d/" "$SRC_DIR/$d/"
    done
    apply_patches
    ok "Copied"
}

# Source patches kept in this repo (survive a repo sync): patches/<project path>/*.patch,
# applied once to the matching LineageOS project; already-applied patches are skipped.
apply_patches() {
    local dir proj p i top
    local -a list
    while IFS= read -r dir; do
        proj="${dir#"$REPO_DIR/patches/"}"
        list=()
        while IFS= read -r -d '' p; do list+=("$p"); done \
            < <(find "$dir" -maxdepth 1 -name '*.patch' -print0 | sort -z)
        # Patches of a project build on each other (0013 changes lines of 0003): a patch can no
        # longer be reversed once a later one touched the same lines. The last patch that can be
        # reversed is the last applied one, and every patch before it is applied too.
        top=-1
        for ((i = ${#list[@]} - 1; i >= 0; i--)); do
            if git -C "$SRC_DIR/$proj" apply --reverse --check "${list[i]}" &>/dev/null; then
                top=$i
                break
            fi
        done
        for i in "${!list[@]}"; do
            p="${list[i]}"
            if (( i <= top )); then
                info "Already applied: $proj/$(basename "$p")"
            elif git -C "$SRC_DIR/$proj" apply --check "$p" &>/dev/null; then
                if [[ -n "${DRY_RUN:-}" ]]; then
                    info "Would apply: $proj/$(basename "$p")"
                else
                    git -C "$SRC_DIR/$proj" apply "$p"
                    ok "Applied: $proj/$(basename "$p")"
                fi
            else
                die "Patch does not apply: $proj/$(basename "$p")"
            fi
        done
    done < <(find "$REPO_DIR/patches" -name '*.patch' -printf '%h\n' 2>/dev/null | sort -u)
}

# Mount the raw images of the backup read-only, laid out like a device
# (system-as-root), and point extract-files.py at it.
phase_extract() {
    # The proprietary blobs are extracted from the console once; Customization/vendor/ keeps them and
    # --copy puts them into the tree, so --all does not need the stock backup (nor sudo) again.
    if ! $FORCE_EXTRACT && [[ -d "$SRC_DIR/vendor/ayaneo/pocket_ds/proprietary" ]]; then
        info "Vendor blobs already in place ($SRC_DIR/vendor/ayaneo/pocket_ds): extraction skipped (--force-extract to redo it)"
        return 0
    fi
    [[ -n "$STOCK_BACKUP" && -d "$STOCK_BACKUP" ]] || die "--stock-backup <dir> is required"
    (cd "$STOCK_BACKUP" && sha256sum --quiet -c SHA256SUMS) || die "Backup checksum mismatch"
    [[ -x "$SRC_DIR/device/ayaneo/pocket_ds/extract-files.py" ]] || die "Run --copy first"

    local mnt
    mnt="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "sudo umount '$mnt'/mnt/* 2>/dev/null || true; rm -rf '$mnt'" EXIT
    mkdir -p "$mnt/mnt" "$mnt/dump"

    for p in system vendor product system_ext odm; do
        mkdir -p "$mnt/mnt/$p"
        info "Mounting $p.img (read-only)"
        sudo mount -o ro,loop "$STOCK_BACKUP/$p.img" "$mnt/mnt/$p"
    done
    ln -s ../mnt/system/system "$mnt/dump/system"
    for p in vendor product system_ext odm; do
        ln -s "../mnt/$p" "$mnt/dump/$p"
    done

    step "Extracting blobs"
    (cd "$SRC_DIR/device/ayaneo/pocket_ds" && ./extract-files.py "$mnt/dump")

    for p in system vendor product system_ext odm; do
        sudo umount "$mnt/mnt/$p"
    done
    rm -rf "$mnt"
    trap - EXIT
    ok "Blobs extracted to $SRC_DIR/vendor/ayaneo/pocket_ds"
}

# Run "m <targets>" for lineage_pocket_ds inside a memory-limited scope. The analysis of Soong needs
# more than 26 GB (peak seen: 26 GB of RAM + 25 GB of swap): with 30 GB of RAM and only the zram
# swap, systemd-oomd killed the build (swap over 90 %). Activate a big swap file before building
# (sudo swapon <file>, 64 GB). The limits below still apply.
run_m() {
    local targets="$*"
    (
        cd "$SRC_DIR"
        systemd-run --user --scope --quiet \
            -p MemoryHigh="$MEM_HIGH" -p MemoryMax="$MEM_MAX" \
            -p ManagedOOMSwap=auto -p ManagedOOMMemoryPressure=auto \
            env GOGC=50 GIT_CONFIG_GLOBAL="$GIT_CONFIG_GLOBAL" \
                $($BRINGUP && echo WITH_ADB_INSECURE=true) \
                POCKETDS_GAPPS="$GAPPS" \
            bash -c "source build/envsetup.sh >/dev/null && \
                     lunch lineage_pocket_ds-cp2a-$VARIANT >/dev/null && \
                     m -j$JOBS $targets"
    )
}

phase_analyze() {
    [[ -d "$SRC_DIR/vendor/ayaneo/pocket_ds" ]] || die "No vendor blobs: put them in Customization/vendor/ and run --copy, or run --extract --stock-backup <dir>"
    step "Analysis only (m nothing), memory limited to $MEM_MAX"
    run_m nothing
    ok "Analysis passed"
}

# Root by default: patch the built init_boot with the KernelSU Next LKM (GKI
# android13-5.15), like the manager app would on the device. The unpatched image
# is kept as init_boot.nosu.img. Assets: scripts/fetch-kernelsu.sh.
patch_kernelsu() {
    local k="$REPO_DIR/packages/kernelsu/prebuilt"
    local out="$SRC_DIR/out/target/product/pocket_ds"
    local ksud="$k/ksud-x86_64-unknown-linux-musl" lkm="$k/aarch64_android13-5.15_kernelsu.ko"
    if [[ ! -x "$ksud" || ! -f "$lkm" ]]; then
        warn "KernelSU Next assets missing (run scripts/fetch-kernelsu.sh): init_boot left unrooted"
        return
    fi
    step "Patching init_boot with KernelSU Next"
    cp -f "$out/init_boot.img" "$out/init_boot.nosu.img"
    local tmp
    tmp="$(mktemp -d)"
    (cd "$tmp" && "$ksud" boot-patch -b "$out/init_boot.nosu.img" -m "$lkm" \
        --kmi android13-5.15 -o "$out" --out-name init_boot.img) \
        || { rm -rf "$tmp"; cp -f "$out/init_boot.nosu.img" "$out/init_boot.img"; die "KernelSU patch failed"; }
    rm -rf "$tmp"
    ok "init_boot patched (unrooted copy: init_boot.nosu.img)"
}

phase_build() {
    [[ -d "$SRC_DIR/vendor/ayaneo/pocket_ds" ]] || die "No vendor blobs (run --extract)"
    grep -qs '^version https://git-lfs' "$SRC_DIR"/external/chromium-webview/prebuilt/*/webview.apk \
        && die "WebView apk is a git-lfs pointer: run --sync (or: cd src && repo forall -c 'git lfs pull')"
    step "Building lineage_pocket_ds-cp2a-$VARIANT (-j$JOBS, memory limited to $MEM_MAX)"
    # The recovery ramdisk rule rsyncs out/.../root without depending on what
    # creates it (installing init.environ.rc): build that first to avoid the race.
    run_m init.environ.rc
    run_m bacon
    $KERNELSU && patch_kernelsu
    ok "Images: $SRC_DIR/out/target/product/pocket_ds"
    # Full + incremental update packages in OTA/, then this build becomes the Compare base.
    $MAKE_OTA && "$SCRIPT_DIR/make-ota.sh"
    info "Next: scripts/flash.sh --image-dir $SRC_DIR/out/target/product/pocket_ds --stock-backup <backup> --wipe"
}

# Prerequisites of this computer (tools, memory, disk) before anything long starts.
if $CHECK && { $DO_SYNC || $DO_COPY || $DO_BUILD; }; then
    "$SCRIPT_DIR/check-prereqs.sh" || die "Prerequisites missing (see above). Fix them, or use --skip-checks"
fi

$DO_SYNC    && phase_sync
$DO_COPY    && phase_fetch
$DO_COPY    && phase_copy
$DO_EXTRACT && phase_extract
$DO_ANALYZE && phase_analyze
$DO_BUILD   && phase_build
exit 0
