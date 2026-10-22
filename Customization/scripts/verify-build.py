#!/usr/bin/env python3
"""Pre-flash safety checks for the Ayaneo Pocket DS.

On this device the stock recovery image has no kernel: recovery and fastbootd
boot with the kernel from `boot` and the DTB/modules/bootconfig from
`vendor_boot` of the active slot. The bootloader's own fastboot refuses
`flash`. So if a flashed boot or vendor_boot is broken, fastbootd is gone too
and only EDL is left.

This script makes sure the build output keeps everything fastbootd depends on
identical to the stock firmware that currently runs on the device (as saved by
scripts/backup-stock.sh), and that every image fits its partition.

Usage: verify-build.py --out <out/target/product/pocket_ds> --stock <backup dir>
Exit code 0 only if every check passes.
"""

import argparse
import hashlib
import os
import struct
import subprocess
import sys

PAGE = 4096
PARTITION_SIZES = {
    "boot": 100663296,
    "vendor_boot": 100663296,
    "init_boot": 8388608,
    "dtbo": 25165824,
    "vbmeta": 65536,
    "vbmeta_system": 65536,
}
LOGICAL = ["system", "system_ext", "product", "vendor", "odm", "vendor_dlkm", "system_dlkm"]
DYNAMIC_GROUP_MAX = 6438256640  # qti_dynamic_partitions, from stock lpdump

failures = []


def check(ok, msg):
    print(("  [OK]   " if ok else "  [FAIL] ") + msg)
    if not ok:
        failures.append(msg)
    return ok


def read(path):
    with open(path, "rb") as f:
        return f.read()


def pad(n):
    return (n + PAGE - 1) // PAGE * PAGE


def parse_boot(data):
    if data[:8] != b"ANDROID!":
        raise ValueError("not an Android boot image")
    kernel_size, ramdisk_size, _os, _hdr = struct.unpack_from("<4I", data, 8)
    header_version = struct.unpack_from("<I", data, 40)[0]
    kernel = data[PAGE:PAGE + kernel_size]
    return {"header_version": header_version, "kernel": kernel,
            "ramdisk_size": ramdisk_size}


def parse_vendor_boot(data):
    if data[:8] != b"VNDRBOOT":
        raise ValueError("not a vendor_boot image")
    (hv, page, kaddr, raddr, rsize) = struct.unpack_from("<5I", data, 8)
    cmdline = data[28:28 + 2048].split(b"\0", 1)[0].decode()
    tags = struct.unpack_from("<I", data, 2076)[0]
    hsize, dtb_size = struct.unpack_from("<2I", data, 2096)
    dtb_addr = struct.unpack_from("<Q", data, 2104)[0]
    tbl_size, _n, _esz, bc_size = struct.unpack_from("<4I", data, 2112)
    off = pad(hsize)
    ramdisk = data[off:off + rsize]
    off += pad(rsize)
    dtb = data[off:off + dtb_size]
    off += pad(dtb_size) + pad(tbl_size)
    bootconfig = data[off:off + bc_size].decode(errors="replace")
    return {"header_version": hv, "page": page, "kernel_addr": kaddr,
            "ramdisk_addr": raddr, "tags_addr": tags, "dtb_addr": dtb_addr,
            "cmdline": cmdline, "ramdisk": ramdisk, "dtb": dtb,
            "bootconfig": bootconfig}


def decompress(blob):
    """Vendor ramdisk(s): lz4 legacy (stock) or gzip, possibly concatenated."""
    for cmd in (["lz4", "-dc"], ["gzip", "-dc"]):
        try:
            r = subprocess.run(cmd, input=blob, capture_output=True, check=True)
            return r.stdout
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue
    raise ValueError("cannot decompress vendor ramdisk (need lz4 or gzip)")


def cpio_files(blob):
    """Parse newc cpio archives (several may be concatenated)."""
    files, i = {}, 0
    while i + 110 <= len(blob):
        if blob[i:i + 6] not in (b"070701", b"070702"):
            i += 1  # padding between concatenated archives
            continue
        f = [int(blob[i + 6 + 8 * k:i + 14 + 8 * k], 16) for k in range(13)]
        mode, filesize, namesize = f[1], f[6], f[11]
        name = blob[i + 110:i + 110 + namesize - 1].decode()
        i = (i + 110 + namesize + 3) & ~3
        content = blob[i:i + filesize]
        i = (i + filesize + 3) & ~3
        if name == "TRAILER!!!":
            continue
        if (mode & 0o170000) == 0o100000:
            files[name.lstrip("./")] = content
    return files


def modules_view(files):
    view = {}
    for name, content in files.items():
        if "lib/modules/" in name:
            view[os.path.basename(name)] = content
    return view


def real_size(path):
    with open(path, "rb") as f:
        head = f.read(28)
    if struct.unpack_from("<I", head, 0)[0] == 0xED26FF3A:  # sparse image
        blk_sz, total_blks = struct.unpack_from("<2I", head, 12)
        return blk_sz * total_blks
    return os.path.getsize(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="build output dir (out/target/product/pocket_ds)")
    ap.add_argument("--stock", required=True, help="backup dir from scripts/backup-stock.sh")
    ap.add_argument("--repo", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    a = ap.parse_args()

    out = lambda n: os.path.join(a.out, n + ".img")
    stock = lambda n: os.path.join(a.stock, n + ".img")
    kernel_dir = os.path.join(a.repo, "kernel", "ayaneo", "kalama")

    print("== Images present and within partition sizes")
    for name, limit in PARTITION_SIZES.items():
        p = out(name)
        if check(os.path.isfile(p), f"{name}.img exists"):
            check(os.path.getsize(p) <= limit, f"{name}.img {os.path.getsize(p)} <= {limit}")
    total = 0
    for name in LOGICAL:
        p = out(name)
        if check(os.path.isfile(p), f"{name}.img exists"):
            total += real_size(p)
    check(total <= DYNAMIC_GROUP_MAX,
          f"logical partitions total {total} <= dynamic group {DYNAMIC_GROUP_MAX}")
    if failures:
        return finish()

    print("== boot.img (kernel used by Android AND by recovery/fastbootd)")
    b = parse_boot(read(out("boot")))
    sb = parse_boot(read(stock("boot")))
    check(b["header_version"] == 4, f"header version {b['header_version']} == 4")
    check(b["kernel"] == read(os.path.join(kernel_dir, "Image")),
          "kernel == kernel/ayaneo/kalama/Image")
    check(b["kernel"] == sb["kernel"], "kernel identical to stock kernel on the device")

    print("== init_boot.img")
    ib = parse_boot(read(out("init_boot")))
    check(ib["header_version"] == 4 and not ib["kernel"], "header v4, no kernel")

    print("== vendor_boot.img (DTB, modules, bootconfig used by recovery/fastbootd)")
    v = parse_vendor_boot(read(out("vendor_boot")))
    sv = parse_vendor_boot(read(stock("vendor_boot")))
    for key in ("header_version", "page", "kernel_addr", "ramdisk_addr", "tags_addr", "dtb_addr"):
        check(v[key] == sv[key], f"{key} {v[key]:#x} == stock {sv[key]:#x}")
    check(v["dtb"] == sv["dtb"], "DTB identical to stock")
    stock_args = {t for t in sv["cmdline"].split() if not t.startswith("buildvariant=")}
    missing = stock_args - set(v["cmdline"].split())
    check(not missing, f"vendor cmdline keeps stock args {sorted(missing) if missing else ''}")
    bc = {l.strip() for l in v["bootconfig"].splitlines() if l.strip()}
    sbc = {l.strip() for l in sv["bootconfig"].splitlines() if l.strip()}
    check(sbc <= bc, f"bootconfig keeps stock entries {sorted(sbc - bc) if sbc - bc else ''}")

    files = cpio_files(decompress(v["ramdisk"]))
    sfiles = cpio_files(decompress(sv["ramdisk"]))
    mods, smods = modules_view(files), modules_view(sfiles)
    sko = {k for k in smods if k.endswith(".ko")}
    ko = {k for k in mods if k.endswith(".ko")}
    check(ko == sko, f"same {len(sko)} ramdisk modules as stock"
          + (f" (missing {sorted(sko - ko)[:5]}, extra {sorted(ko - sko)[:5]})" if ko != sko else ""))
    diff = [k for k in sko & ko if hashlib.sha256(mods[k]).digest() != hashlib.sha256(smods[k]).digest()]
    check(not diff, f"ramdisk modules byte-identical to stock {diff[:5] if diff else ''}")
    for lst in ("modules.load", "modules.load.recovery"):
        mine = mods.get(lst, b"").split()
        theirs = smods.get(lst, b"").split()
        check(mine == theirs, f"{lst} identical to stock ({len(theirs)} entries)")
    fstab = files.get("first_stage_ramdisk/fstab.qcom")
    check(fstab is not None, "first_stage_ramdisk/fstab.qcom present")
    if fstab is not None:
        check(fstab == read(os.path.join(a.repo, "device/ayaneo/pocket_ds/rootdir/fstab.qcom")),
              "first-stage fstab == device/ayaneo/pocket_ds/rootdir/fstab.qcom")

    print("== dtbo.img")
    d, sd = read(out("dtbo")), read(stock("dtbo"))
    stock_len = struct.unpack_from(">I", sd, 4)[0]  # dt_table_header.total_size
    check(d[:stock_len] == sd[:stock_len], "DTBO identical to stock")

    print("== vbmeta.img")
    vb = read(out("vbmeta"))
    check(vb[:4] == b"AVB0", "AVB magic")
    flags = struct.unpack_from(">I", vb, 120)[0]
    check(flags & 3 == 3, f"flags {flags} disable verity + verification")

    return finish()


def finish():
    print()
    if failures:
        print(f"VERIFY FAILED: {len(failures)} check(s). Do NOT flash this build.")
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
