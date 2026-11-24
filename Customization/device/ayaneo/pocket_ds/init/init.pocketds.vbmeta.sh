#!/vendor/bin/sh
# ro.boot.vbmeta.digest: the digest of the verified boot metadata (vbmeta), which the bootloader
# of this console does not pass when it is unlocked. Android and the apps expect it, and an empty
# one is a known sign of a modified system. It is computed here from the partitions, as avbtool
# calculate_vbmeta_digest does: SHA-256 of the vbmeta structure of vbmeta, then of the partitions it
# chains (only vbmeta_system on this console). The real value, right for every build.
[ -n "$(getprop ro.boot.vbmeta.digest)" ] && exit 0
S=$(getprop ro.boot.slot_suffix)

# The vbmeta structure at the start of a partition: header (256 bytes) + authentication block +
# auxiliary block, their sizes are big-endian 64-bit numbers at bytes 12 and 20 of the header.
vbmeta() {
    p=/dev/block/by-name/$1$S
    [ "$(head -c 4 "$p")" = "AVB0" ] || return 1
    auth=$((0x$(xxd -p -s 12 -l 8 "$p")))
    aux=$((0x$(xxd -p -s 20 -l 8 "$p")))
    head -c $((256 + auth + aux)) "$p"
}

T=/dev/pocketds_vbmeta
{ vbmeta vbmeta && vbmeta vbmeta_system; } > $T || { rm -f $T; exit 0; }
D=$(sha256sum $T | cut -d' ' -f1)
rm -f $T
[ ${#D} -eq 64 ] && setprop ro.boot.vbmeta.digest "$D"
