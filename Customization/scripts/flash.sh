#!/usr/bin/env bash
set -euo pipefail

# Flash script for LineageOS 24 — Ayaneo Pocket DS
# Supports fastboot (default) and EDL mode

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
die()   { error "$@"; exit 1; }
step()  { echo -e "${CYAN}[STEP]${NC} $*"; }

# --- Defaults ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE="fastboot"
DRY_RUN=false
# See the reboot step: fastbootd loses unflushed writes when it reboots.
SETTLE_SECS=90
WIPE_DATA=false
# Slot that receives the build: a | b | current (the active one, default).
TARGET_SLOT="current"
MIRROR_BOOT=false
# --update: flash only the images that changed since the last flash (no wipe, data kept).
UPDATE_ONLY=false
LAST_FLASHED="$ROOT_DIR/../OTA/Work/flashed.sha256"
IMAGE_DIR=""
STOCK_BACKUP=""

# --- Required tools ---
check_tool() {
    command -v "$1" &>/dev/null || die "Required tool not found: $1"
}

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Modes:
  (default)     Fastboot mode — flash LineageOS build output
  --edl         EDL mode — full reflash using QSaharaServer + fh_loader

Options:
  --image-dir DIR   Directory containing images to flash
                    (default: out/target/product/pocket_ds for fastboot,
                     stock-firmware/ufs for EDL)
  --stock-backup DIR  Backup made by scripts/backup-stock.sh (REQUIRED in
                    fastboot mode): the build is verified against it with
                    scripts/verify-build.py before anything is flashed
  --wipe            Wipe userdata (factory reset)
  --slot a|b|current  Slot that receives the build (default: the active one). The slot
                    is made active afterwards. Everything is written to that slot with
                    explicit _a/_b names, whatever the active slot was.
  --mirror-boot     Also write the boot chain (boot, init_boot, vendor_boot, dtbo, vbmeta,
                    vbmeta_system) of the same build to the other slot. The logical
                    partitions cannot be duplicated (see "super capacity" in the report).
  --update          Update without reinstalling: flashes only the images that changed since
                    the last flash (compared with the record of that flash), keeps userdata.
                    Same slot and safety rules as a full flash. The record is rewritten after
                    every successful flash (default OTA/Work/flashed.sha256).
  --last-flashed FILE  Record of the images on the device (sha256sum format)
  --dry-run         Print commands without executing
  --help            Show this help

Fastboot mode (everything is flashed from fastbootd — the Pocket DS
bootloader fastboot does not accept "flash"):
  vbmeta, vbmeta_system, boot, init_boot, vendor_boot, dtbo,
  system, system_ext, product, vendor, odm, vendor_dlkm, system_dlkm
The recovery partition is never touched: stock recovery holds fastbootd.

Before the first flash, back up the stock ROM: scripts/backup-stock.sh

EDL mode restores stock firmware using rawprogram/patch XML files.

Examples:
  $(basename "$0") --stock-backup ../Backup/Original/stock-backups/backup-XXXX         # Flash LineageOS
  $(basename "$0") --stock-backup ../Backup/Original/stock-backups/backup-XXXX --wipe  # First install
  $(basename "$0") --edl --image-dir stock-firmware/ufs  # Full EDL reflash
  $(basename "$0") --dry-run                    # Preview flash commands
EOF
    exit 0
}

# --- Parse Args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --edl)       MODE="edl" ;;
        --image-dir) IMAGE_DIR="$2"; shift ;;
        --wipe)      WIPE_DATA=true ;;
        --slot)      TARGET_SLOT="$2"; shift ;;
        --mirror-boot) MIRROR_BOOT=true ;;
        --update)    UPDATE_ONLY=true ;;
        --last-flashed) LAST_FLASHED="$2"; shift ;;
        --stock-backup) STOCK_BACKUP="$2"; shift ;;
        --dry-run)   DRY_RUN=true ;;
        --help)      usage ;;
        *)           die "Unknown option: $1 (use --help)" ;;
    esac
    shift
done

case "$TARGET_SLOT" in a|b|current) ;; *) die "--slot takes a, b or current" ;; esac

# --- Helpers ---
# Hard guards (see CLAUDE.md "never flash recovery"; a set_active on a slot that is not fully
# flashed strands the console in the bootloader, whose fastboot cannot flash or switch back).
SLOT_READY=false
run_cmd() {
    case "$*" in
        *"flash recovery"*|*"flash "*"recovery_"*|*"erase recovery"*)
            die "Refusing to touch the recovery partition: stock recovery holds the only known-good fastbootd." ;;
        *"set_active"*|*"set-active"*)
            [[ "$SLOT_READY" == true ]] || die "Refusing set_active: this run has not finished flashing the target slot." ;;
    esac
    if [[ "$DRY_RUN" == true ]]; then
        echo -e "  ${YELLOW}[DRY-RUN]${NC} $*"
    else
        "$@"
    fi
}

# --update: whether the image of a partition differs from the one recorded at the last flash.
image_changed() {
    local part="$1" new old
    new="$(sha256sum "$IMAGE_DIR/$part.img" | cut -d' ' -f1)"
    old="$(awk -v n="$part.img" '$2 == n {print $1}' "$LAST_FLASHED")"
    [[ "$new" != "$old" ]]
}

# Records what is on the device now, for the next --update (not in a dry run).
record_flashed() {
    [[ "$DRY_RUN" == true ]] && return 0
    mkdir -p "$(dirname "$LAST_FLASHED")"
    (cd "$IMAGE_DIR" && sha256sum boot.img init_boot.img vendor_boot.img dtbo.img vbmeta.img \
        vbmeta_system.img system.img system_ext.img product.img vendor.img odm.img \
        vendor_dlkm.img system_dlkm.img) > "$LAST_FLASHED.new" && mv "$LAST_FLASHED.new" "$LAST_FLASHED"
    info "Record of the flashed images updated: $LAST_FLASHED"
}

confirm() {
    if [[ "$DRY_RUN" == true ]]; then
        return 0
    fi
    echo ""
    warn "$1"
    read -rp "Continue? [y/N] " response
    [[ "$response" =~ ^[Yy]$ ]] || die "Aborted by user"
}

wait_for_device() {
    local tool="$1"
    local timeout=120
    info "Waiting for device in $tool mode (${timeout}s timeout)..."
    local i=0
    while [[ $i -lt $timeout ]]; do
        case "$tool" in
            fastboot)
                if fastboot devices 2>/dev/null | grep -q 'fastboot'; then
                    ok "Device found in fastboot mode"
                    return 0
                fi
                ;;
            adb)
                if adb devices 2>/dev/null | grep -q 'device$'; then
                    ok "Device found via ADB"
                    return 0
                fi
                ;;
        esac
        sleep 1
        ((i++))
    done
    die "Device not found in $tool mode after ${timeout}s"
}

# =============================================================================
# Fastboot Mode
# =============================================================================
# The Pocket DS bootloader fastboot rejects "flash" ("unknown command"), so
# every partition — including boot/vendor_boot/dtbo/init_boot/vbmeta — is
# flashed from userspace fastboot (fastbootd).
ensure_fastbootd() {
    if [[ "$DRY_RUN" == true ]]; then
        run_cmd fastboot reboot fastboot
        return 0
    fi

    if adb devices 2>/dev/null | grep -q 'device$'; then
        step "Rebooting from Android to fastbootd..."
        adb reboot fastboot </dev/null
        sleep 5
    fi

    wait_for_device fastboot

    if ! fastboot getvar is-userspace 2>&1 | grep -q 'is-userspace: yes'; then
        step "Bootloader fastboot detected — switching to fastbootd..."
        fastboot reboot fastboot
        sleep 5
        wait_for_device fastboot
        fastboot getvar is-userspace 2>&1 | grep -q 'is-userspace: yes' \
            || die "Device did not enter fastbootd"
    fi
    ok "Device is in fastbootd"
}

# Sizes of the logical partitions once flashed (sparse images are expanded by fastbootd).
# Prints "<name> <bytes>" per image.
logical_image_sizes() {
    python3 -I - "$IMAGE_DIR" "$@" <<'PYEOF'
import os, struct, sys
d = sys.argv[1]
for name in sys.argv[2:]:
    fn = os.path.join(d, name + ".img")
    with open(fn, "rb") as f:
        h = f.read(28)
    if struct.unpack("<I", h[:4])[0] == 0xED26FF3A:  # android sparse image
        size = struct.unpack("<I", h[12:16])[0] * struct.unpack("<I", h[16:20])[0]
    else:
        size = os.path.getsize(fn)
    print(name, size)
PYEOF
}

# Super capacity: two complete sets of logical partitions do not fit in super, so only the
# target slot is populated; the other one is emptied (size 0) when the space is missing.
# Fills FREE_OTHER with the partitions of the other slot to resize to 0.
FREE_OTHER=()
capacity_report() {
    local slot="$1" other="$2"
    local super_size group_max need=0 other_used=0 line name size

    super_size="$(sed -n 's/^BOARD_SUPER_PARTITION_SIZE *:= *//p' "$ROOT_DIR/device/ayaneo/pocket_ds/BoardConfig.mk")"
    group_max="$(sed -n 's/^BOARD_QTI_DYNAMIC_PARTITIONS_SIZE *:= *//p' "$ROOT_DIR/device/ayaneo/pocket_ds/BoardConfig.mk")"
    [[ -n "$super_size" && -n "$group_max" ]] || die "Cannot read the super sizes from BoardConfig.mk"
    if fastboot devices 2>/dev/null | grep -q fastboot; then
        local live
        live="$(fastboot getvar partition-size:super 2>&1 | awk '/partition-size:super:/ {print $2}')"
        [[ -n "$live" ]] && super_size=$((live))
    fi

    echo ""
    step "Super capacity (device super: $super_size bytes, group max: $group_max bytes)"
    while read -r name size; do
        printf '  %-12s %12d bytes (image, once flashed)\n' "$name" "$size"
        need=$((need + size))
    done < <(logical_image_sizes "${logical_parts[@]}")
    printf '  %-12s %12d bytes (%s GiB) for one slot\n' "TOTAL" "$need" "$(python3 -I -c "print(round($need/2**30,3))")"
    printf '  %-12s %12d bytes for both slots\n' "TWO SLOTS" "$((2 * need))"

    [[ $need -le $group_max ]] || die "The build needs $need bytes, the super group holds $group_max: cut the images"

    if fastboot devices 2>/dev/null | grep -q fastboot; then
        for name in "${logical_parts[@]}"; do
            size="$(fastboot getvar "partition-size:${name}_$other" 2>&1 | awk -v k="partition-size:${name}_$other:" '$1==k {print $2}')"
            size=$((${size:-0}))
            other_used=$((other_used + size))
            [[ $size -gt 0 ]] && FREE_OTHER+=("$name")
        done
        info "Slot $other currently uses $other_used bytes of super"
    else
        warn "No device in fastbootd: the other slot's usage is unknown, assuming it must be freed"
        FREE_OTHER=("${logical_parts[@]}")
        other_used=0
    fi

    if [[ $((need + other_used)) -le $super_size ]]; then
        info "The build fits next to slot $other as it is: slot ${other^^} is left untouched"
        FREE_OTHER=()
    else
        warn "Super is too small for both slots ($((need + other_used)) > $super_size bytes)."
        warn "Slot ${other^^}'s logical partitions will be resized to 0 before flashing slot ${slot^^};"
        warn "flash slot ${other^^} later with --slot $other (that empties slot ${slot^^} in turn)."
    fi
    echo ""
}

flash_fastboot() {
    check_tool fastboot
    check_tool adb

    # Determine image directory
    if [[ -z "$IMAGE_DIR" ]]; then
        IMAGE_DIR="$ROOT_DIR/../src/out/target/product/pocket_ds"
    fi

    if [[ ! -d "$IMAGE_DIR" ]]; then
        die "Image directory not found: $IMAGE_DIR"
    fi

    info "=== Fastboot Flash Mode (fastbootd) ==="
    info "Image directory: $IMAGE_DIR"
    echo ""

    local vbmeta_imgs=(vbmeta vbmeta_system)
    # recovery is deliberately NOT flashed: the stock recovery ramdisk holds a
    # known-good fastbootd, which is the only way back short of EDL.
    local raw_parts=(boot init_boot vendor_boot dtbo)
    local logical_parts=(system system_ext product vendor odm vendor_dlkm system_dlkm)

    local missing=()
    for part in "${vbmeta_imgs[@]}" boot init_boot vendor_boot dtbo "${logical_parts[@]}"; do
        if [[ -f "$IMAGE_DIR/$part.img" ]]; then
            info "  Found: $part.img ($(du -h "$IMAGE_DIR/$part.img" | cut -f1))"
        else
            missing+=("$part")
        fi
    done
    # All of these must come from the same build: the kernel in boot.img and the
    # modules in vendor_boot/vendor_dlkm/system_dlkm have to match.
    [[ ${#missing[@]} -eq 0 ]] || die "Missing images: ${missing[*]}"

    # Safety gate: boot/vendor_boot/dtbo must keep everything stock
    # recovery/fastbootd depends on identical to the firmware on the device.
    [[ -n "$STOCK_BACKUP" && -d "$STOCK_BACKUP" ]] \
        || die "--stock-backup <dir> is required (run scripts/backup-stock.sh first)"
    check_tool python3
    step "Verifying build against stock backup..."
    python3 -I "$SCRIPT_DIR/verify-build.py" --out "$IMAGE_DIR" --stock "$STOCK_BACKUP" \
        || die "Build verification failed — refusing to flash"

    # The backup must describe the firmware currently on the device.
    if adb devices 2>/dev/null | grep -q 'device$'; then
        local fp backup_fp
        fp="$(adb shell getprop ro.build.fingerprint </dev/null | tr -d '\r')"
        backup_fp="$(sed -n 's/^fingerprint=//p' "$STOCK_BACKUP/backup-info.txt")"
        if [[ "$fp" == "$backup_fp" ]]; then
            ok "Backup matches the device firmware"
        elif [[ "$fp" == *lineage_pocket_ds* ]]; then
            ok "Device runs a previous build of this ROM ($fp)"
        else
            die "Device fingerprint ($fp) differs from backup ($backup_fp) — make a fresh backup"
        fi
    else
        warn "Device not reachable over adb — cannot check the backup matches the device firmware"
    fi

    if [[ "$WIPE_DATA" != true ]]; then
        warn "Coming from the stock ROM, a data wipe is REQUIRED (different"
        warn "encryption keys / framework). Re-run with --wipe for a first install."
    fi

    ensure_fastbootd

    # --- Target slot ---
    local slot other
    slot="$TARGET_SLOT"
    if [[ "$slot" == current ]]; then
        if [[ "$DRY_RUN" == true ]] && ! fastboot devices 2>/dev/null | grep -q fastboot; then
            slot=b
            warn "No device: dry run assumes slot b (use --slot a|b to be explicit)"
        else
            slot="$(fastboot getvar current-slot 2>&1 | awk '/current-slot:/ {print $2}')"
            [[ "$slot" == a || "$slot" == b ]] || die "Cannot read the current slot (got '${slot:-nothing}')"
        fi
    fi
    other=a; [[ "$slot" == a ]] && other=b
    info "Target slot: $slot (other slot: $other)"

    capacity_report "$slot" "$other"

    if [[ "$WIPE_DATA" == true ]]; then
        confirm "This will flash LineageOS on slot ${slot^^}, make it active and ERASE all user data."
    else
        confirm "This will flash LineageOS on slot ${slot^^} and make it active."
    fi

    # Virtual A/B: drop any pending OTA snapshot so logical partitions can be
    # rewritten and resized (otherwise fastbootd refuses or super runs out of space).
    step "Cancelling pending snapshot-update (if any)..."
    run_cmd fastboot snapshot-update cancel || true

    # Free the other slot's logical partitions when super cannot hold both (see the report):
    # resized to 0 they keep their entry in the metadata, like the unused slot of the stock.
    if [[ ${#FREE_OTHER[@]} -gt 0 ]]; then
        for part in "${FREE_OTHER[@]}"; do
            step "Freeing ${part}_$other in super (slot ${other^^} will not boot until flashed)..."
            run_cmd fastboot resize-logical-partition "${part}_$other" 0
        done
    fi

    if [[ "$UPDATE_ONLY" == true ]]; then
        [[ "$WIPE_DATA" == false ]] || die "--update keeps the data: it cannot be combined with --wipe"
        [[ -f "$LAST_FLASHED" ]] || die "No record of the last flash ($LAST_FLASHED): do a full flash first"
        local kept_raw=() kept_logical=() p
        for p in "${raw_parts[@]}"; do
            image_changed "$p" && kept_raw+=("$p") || info "  Unchanged: $p"
        done
        for p in "${logical_parts[@]}"; do
            image_changed "$p" && kept_logical+=("$p") || info "  Unchanged: $p"
        done
        raw_parts=("${kept_raw[@]}")
        logical_parts=("${kept_logical[@]}")
        if [[ $(( ${#raw_parts[@]} + ${#logical_parts[@]} )) -eq 0 ]]; then
            ok "Nothing changed since the last flash: nothing to do."
            return 0
        fi
        info "Update: ${raw_parts[*]} ${logical_parts[*]}"
    fi

    local boot_slots=("$slot")
    $MIRROR_BOOT && boot_slots+=("$other")
    local s
    for s in "${boot_slots[@]}"; do
        for part in "${vbmeta_imgs[@]}"; do
            step "Flashing ${part}_$s (verity + verification disabled)..."
            run_cmd fastboot flash "${part}_$s" --disable-verity --disable-verification "$IMAGE_DIR/$part.img"
        done
        for part in "${raw_parts[@]}"; do
            if [[ -f "$IMAGE_DIR/$part.img" ]]; then
                step "Flashing ${part}_$s..."
                run_cmd fastboot flash "${part}_$s" "$IMAGE_DIR/$part.img"
            fi
        done
    done

    # The slot's current logical partitions (stock or an older build) have other sizes than the new
    # ones: growing one while the others still hold their old size fails with "Not enough space to
    # resize partition" and leaves the slot half-flashed. Give all of its space back first.
    for part in "${logical_parts[@]}"; do
        step "Freeing logical partition ${part}_$slot..."
        run_cmd fastboot resize-logical-partition "${part}_$slot" 0 || warn "${part}_$slot could not be freed (will be resized by the flash)"
    done

    for part in "${logical_parts[@]}"; do
        step "Flashing logical partition ${part}_$slot..."
        run_cmd fastboot flash "${part}_$slot" "$IMAGE_DIR/$part.img"
    done

    record_flashed

    # fastbootd refuses set_active on the slot that is already active ("Unable to set slot").
    local active_now
    active_now="$(fastboot getvar current-slot 2>&1 | awk '/current-slot:/ {print $2}')"
    if [[ "$DRY_RUN" == true || "$active_now" != "$slot" ]]; then
        step "Making slot $slot the active one..."
        SLOT_READY=true   # every partition of $slot was flashed above, in this run
        run_cmd fastboot set_active "$slot"
    else
        info "Slot $slot is already the active one"
    fi

    if [[ "$WIPE_DATA" == true ]]; then
        step "Wiping userdata + metadata..."
        run_cmd fastboot erase userdata
        # Stock fastbootd mounts /metadata as soon as it holds a file system and then
        # refuses to erase it: finish with a factory reset from recovery in that case.
        if ! run_cmd fastboot erase metadata; then
            warn "metadata is in use by fastbootd: rebooting to recovery instead of Android."
            warn "Run 'Factory data reset' there (formats /data and /metadata), then reboot."
            REBOOT_RECOVERY=true
        fi
    fi

    # fastbootd on this device does not flush its writes before rebooting: images
    # written in the last seconds before "fastboot reboot" are lost (verified on
    # vendor_boot). Give the kernel write-back time to reach the UFS first.
    step "Waiting ${SETTLE_SECS}s for the writes to reach storage..."
    $DRY_RUN || sleep "$SETTLE_SECS"

    step "Rebooting device..."
    if [[ "${REBOOT_RECOVERY:-false}" == true ]]; then
        run_cmd fastboot reboot recovery
    else
        run_cmd fastboot reboot
    fi

    echo ""
    ok "=== Flash Complete ==="
    info "Device is rebooting. First boot may take several minutes."
    info "If it does not boot: hold the key combo for fastboot, then"
    info "  fastboot reboot fastboot   (stock fastbootd, kernel/vendor_boot verified identical)"
    info "  scripts/restore-stock.sh $STOCK_BACKUP"
}

# =============================================================================
# EDL Mode (Emergency Download)
# =============================================================================
flash_edl() {
    if [[ -z "$IMAGE_DIR" ]]; then
        IMAGE_DIR="$ROOT_DIR/stock-firmware/ufs"
    fi

    if [[ ! -d "$IMAGE_DIR" ]]; then
        die "Firmware directory not found: $IMAGE_DIR"
    fi

    info "=== EDL Flash Mode ==="
    info "Firmware directory: $IMAGE_DIR"
    echo ""

    # Check for EDL tools
    local qsahara=""
    local fh_loader=""

    for tool_path in "$IMAGE_DIR/QSaharaServer" "$IMAGE_DIR/../QSaharaServer" "/usr/bin/QSaharaServer" "$(command -v QSaharaServer 2>/dev/null || true)"; do
        if [[ -x "$tool_path" ]]; then
            qsahara="$tool_path"
            break
        fi
    done

    for tool_path in "$IMAGE_DIR/fh_loader" "$IMAGE_DIR/../fh_loader" "/usr/bin/fh_loader" "$(command -v fh_loader 2>/dev/null || true)"; do
        if [[ -x "$tool_path" ]]; then
            fh_loader="$tool_path"
            break
        fi
    done

    if [[ -z "$qsahara" ]]; then
        die "QSaharaServer not found. Place it in $IMAGE_DIR/ or install to PATH."
    fi
    if [[ -z "$fh_loader" ]]; then
        die "fh_loader not found. Place it in $IMAGE_DIR/ or install to PATH."
    fi

    ok "QSaharaServer: $qsahara"
    ok "fh_loader: $fh_loader"

    # Find rawprogram and patch XML files
    local rawprogram_files=()
    local patch_files=()

    while IFS= read -r -d '' f; do
        rawprogram_files+=("$f")
    done < <(find "$IMAGE_DIR" -name 'rawprogram*.xml' -print0 | sort -z)

    while IFS= read -r -d '' f; do
        patch_files+=("$f")
    done < <(find "$IMAGE_DIR" -name 'patch*.xml' -print0 | sort -z)

    if [[ ${#rawprogram_files[@]} -eq 0 ]]; then
        die "No rawprogram*.xml files found in $IMAGE_DIR"
    fi

    info "Found ${#rawprogram_files[@]} rawprogram XML files"
    info "Found ${#patch_files[@]} patch XML files"

    # Find EDL port
    local edl_port=""
    for port in /dev/ttyUSB0 /dev/ttyUSB1 /dev/ttyHS0; do
        if [[ -c "$port" ]]; then
            edl_port="$port"
            break
        fi
    done

    if [[ -z "$edl_port" && "$DRY_RUN" != true ]]; then
        warn "No EDL serial port detected."
        warn "Put device in EDL mode: power off, hold Vol- + power, connect USB"
        die "No EDL port found at /dev/ttyUSB*"
    fi
    edl_port="${edl_port:-/dev/ttyUSB0}"  # Default for dry-run

    info "EDL port: $edl_port"

    warn "EDL reflash writes EVERY partition of the flat build, including the"
    warn "bootloaders, which are OLDER than an OTA-updated device's. Use it only"
    warn "to unbrick. persist (device-unique calibration) is excluded below."
    confirm "This will perform a FULL REFLASH via EDL. ALL data will be erased."

    # Step 1: Load programmer via Sahara protocol (same as turbox_flat_flash.sh)
    local programmer=""
    for candidate in "$IMAGE_DIR/xbl_s_devprg_ns.melf" \
                     $(find "$IMAGE_DIR" -name 'prog_firehose_*' 2>/dev/null); do
        if [[ -f "$candidate" ]]; then
            programmer="$candidate"
            break
        fi
    done
    [[ -n "$programmer" ]] || die "Firehose programmer not found in $IMAGE_DIR"

    # Never overwrite persist: it holds this unit's sensor/Wi-Fi/BT calibration,
    # the flat build only carries a generic one.
    local xml_dir
    xml_dir="$(mktemp -d)"
    local xmls=()
    for f in "${rawprogram_files[@]}"; do
        grep -v 'label="persist"' "$f" > "$xml_dir/$(basename "$f")"
        xmls+=("$xml_dir/$(basename "$f")")
    done
    grep -q 'label="persist"' "${xmls[@]}" && die "Failed to strip persist from rawprogram XMLs"
    xmls+=("${patch_files[@]}")
    local sendxml
    sendxml="$(IFS=,; echo "${xmls[*]}")"

    step "Loading firehose programmer via Sahara..."
    run_cmd "$qsahara" -p "$edl_port" -s "13:$programmer"

    # Step 2: Flash all rawprogram + patch XMLs in one session, then reset
    step "Flashing (persist excluded)..."
    run_cmd "$fh_loader" --port="$edl_port" --sendxml="$sendxml" --search_path="$IMAGE_DIR" \
        --memoryname=UFS --noprompt --showpercentagecomplete --zlpawarehost=1 --reset
    rm -rf "$xml_dir"

    echo ""
    ok "=== EDL Flash Complete ==="
    info "Device will boot into stock firmware."
    info "To flash LineageOS, boot to fastboot and run: $(basename "$0")"
}

# =============================================================================
# Main
# =============================================================================
case "$MODE" in
    fastboot)  flash_fastboot ;;
    edl)       flash_edl ;;
    *)         die "Unknown mode: $MODE" ;;
esac
