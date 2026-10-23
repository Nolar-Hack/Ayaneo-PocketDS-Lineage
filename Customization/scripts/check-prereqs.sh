#!/usr/bin/env bash
# Checks what the build needs on this computer and says how to get what is missing.
#   scripts/check-prereqs.sh            exit code 0 = ready, 1 = something required is missing
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
missing=0

ok()   { printf '  [ok]      %s\n' "$1"; }
bad()  { printf '  [MISSING] %s\n            -> %s\n' "$1" "$2"; missing=1; }
warn() { printf '  [warning] %s\n            -> %s\n' "$1" "$2"; }

need() {  # need <command> <what it is for> <how to get it>
    if command -v "$1" >/dev/null 2>&1; then ok "$1  ($2)"; else bad "$1  ($2)" "$3"; fi
}

echo "Tools to fetch and build LineageOS"
need repo     "downloads LineageOS: the 'repo' launcher, usually /usr/bin/repo" \
              "Arch: yay -S repo | Debian/Ubuntu: sudo apt install repo (or curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo; chmod +x)"
need git      "version control"                       "sudo apt install git | sudo pacman -S git"
need git-lfs  "large files of some LineageOS repositories" "sudo apt install git-lfs | sudo pacman -S git-lfs"
need rsync    "copies the project into src/"          "sudo apt install rsync | sudo pacman -S rsync"
need python3  "scripts of the device tree and of the build" "sudo apt install python3 | sudo pacman -S python"
need curl     "downloads"                             "sudo apt install curl | sudo pacman -S curl"
need zip      "update packages"                       "sudo apt install zip | sudo pacman -S zip"
need unzip    "update packages"                       "sudo apt install unzip | sudo pacman -S unzip"
need make     "Android build"                         "sudo apt install build-essential | sudo pacman -S base-devel"
need gcc      "host tools of the Android build"       "sudo apt install build-essential | sudo pacman -S base-devel"
need bc       "Android build"                         "sudo apt install bc | sudo pacman -S bc"
need bison    "Android build"                         "sudo apt install bison | sudo pacman -S bison"
need flex     "Android build"                         "sudo apt install flex | sudo pacman -S flex"
need lz4      "Android build"                         "sudo apt install lz4 | sudo pacman -S lz4"
need xsltproc "Android build"                         "sudo apt install xsltproc | sudo pacman -S libxslt"
echo "Optional (not needed by our builds so far, but listed in the usual Android build requirements)"
command -v gperf >/dev/null 2>&1 && ok "gperf" || warn "gperf (not required: the builds worked without it)" "sudo apt install gperf | sudo pacman -S gperf"
echo "Only to flash / to extract from the console"
for t in adb fastboot; do
    command -v "$t" >/dev/null 2>&1 && ok "$t" || warn "$t (flashing and backing up the console)" "sudo apt install android-tools-adb android-tools-fastboot | sudo pacman -S android-tools"
done
command -v sudo >/dev/null 2>&1 && ok "sudo (mounting the stock images, --extract only)" \
    || warn "sudo (--extract only)" "not needed when Customization/vendor/ already holds the blobs"

echo "Memory"
ram_gb=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1048576 ))
swap_gb=$(( $(awk '/SwapTotal/ {print $2}' /proc/meminfo) / 1048576 ))
echo "  RAM ${ram_gb} GB, swap ${swap_gb} GB"
if (( ram_gb + swap_gb >= 90 )); then ok "RAM + swap >= 90 GB (the analysis phase peaks near 66 GB)"
else
    add_gb=$(( 90 - ram_gb - swap_gb )); (( add_gb < 16 )) && add_gb=16
    SWAPFILE="$ROOT/swapfile"
    manual="sudo fallocate -l ${add_gb}G $SWAPFILE && sudo chmod 600 $SWAPFILE && sudo mkswap $SWAPFILE && sudo swapon $SWAPFILE"
    if [[ -t 0 && -t 1 && -z "${CHECK_PREREQS_NO_PROMPT:-}" ]] && command -v sudo >/dev/null 2>&1; then
        printf '  [MISSING] RAM + swap = %s GB, 90 GB are needed (the build is killed without it)
' "$((ram_gb + swap_gb))"
        # The file survives a reboot but swapon does not: an existing swap file is only switched on again.
        reuse=false
        if [[ -f "$SWAPFILE" ]] && [[ "$(sudo file -s "$SWAPFILE" 2>/dev/null)" == *"swap file"* ]]; then
            reuse=true
        fi
        if $reuse; then
            printf '            The swap file %s exists but is not active: switch it on (needs sudo)? [y/N] ' "$SWAPFILE"
        else
            printf '            Create a %s GB swap file in %s now (needs sudo, removed later with: sudo swapoff %s && rm %s)? [y/N] ' \
                   "$add_gb" "$SWAPFILE" "$SWAPFILE" "$SWAPFILE"
        fi
        read -r answer
        if [[ "$answer" == [yYoO]* ]]; then
            if $reuse; then
                sudo swapon "$SWAPFILE"
            elif [[ "$(stat -f -c %T "$ROOT")" == btrfs ]]; then
                sudo btrfs filesystem mkswapfile --size "${add_gb}G" "$SWAPFILE" && sudo swapon "$SWAPFILE"
            else
                { sudo fallocate -l "${add_gb}G" "$SWAPFILE" || sudo dd if=/dev/zero of="$SWAPFILE" bs=1M count=$((add_gb * 1024)) status=none; } \
                    && sudo chmod 600 "$SWAPFILE" && sudo mkswap "$SWAPFILE" >/dev/null && sudo swapon "$SWAPFILE"
            fi
            swap_gb=$(( $(awk '/SwapTotal/ {print $2}' /proc/meminfo) / 1048576 ))
            if (( ram_gb + swap_gb >= 90 )); then ok "swap file ready: RAM ${ram_gb} GB + swap ${swap_gb} GB"
            else bad "the swap file could not be activated" "$manual"; fi
        else
            bad "RAM + swap = $((ram_gb + swap_gb)) GB, 90 GB are needed" "$manual"
        fi
    else
        bad "RAM + swap = $((ram_gb + swap_gb)) GB, 90 GB are needed" "create a swap file next to the project: $manual"
    fi
fi

echo "Disk"
free_gb=$(df -BG --output=avail "$ROOT" | tail -1 | tr -dc '0-9')
need_gb=320
# what is already downloaded/built in src/ no longer has to be found
if [[ -d "$ROOT/src" ]]; then
    used_gb=$(du -sBG "$ROOT/src" 2>/dev/null | cut -f1 | tr -dc '0-9')
    need_gb=$(( need_gb - ${used_gb:-0} ))
    (( need_gb < 60 )) && need_gb=60
fi
if (( free_gb >= need_gb )); then ok "${free_gb} GB free (need about ${need_gb} GB more: sources 100 + build 140 + OTA and work files 30, minus what src/ already holds)"
else bad "${free_gb} GB free, about ${need_gb} GB are needed" "free some space on the disk of $ROOT"
fi

echo
if (( missing )); then echo "Something required is missing: install it and run again."; exit 1; fi
echo "Everything required is there."
