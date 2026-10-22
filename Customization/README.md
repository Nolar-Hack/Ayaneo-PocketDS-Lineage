# LineageOS 24.0 for the Ayaneo Pocket DS

LineageOS 24.0 (Android 17) port for the Ayaneo Pocket DS (Snapdragon G3x Gen 2,
`kalama`, `soc_id` 601). **Not built or booted yet** — this is a bring-up tree.

* Qualcomm-common parts follow `LineageOS/android_device_ayn_qcs8550-common`.
* Kernel, DTB, DTBO and kernel modules are the stock ones, byte for byte.
* Everything device-specific was read on a real Pocket DS: see
  [docs/device-facts.md](docs/device-facts.md).

## Layout

```
device/ayaneo/pocket_ds/   device tree (BoardConfig, device.mk, init, sepolicy, configs...)
kernel/ayaneo/kalama/      stock prebuilt Image, kalama.dtb, dtbo.img, modules
local_manifests/           MindTheGapps (Android 17 branch "cinnamonbun")
scripts/                   backup / build / verify / flash / restore
```

The LineageOS sources live next to this repo, in `../src` (the LineageOS tree, fetched by `build.sh --sync`)
(~130 GB synced, ~150–200 GB more for a build).

**Memory:** the Soong analysis of LineageOS 24 alone needs ~47 GB (RAM + swap). On a
30 GB machine with zram only it gets OOM-killed; add a temporary disk swapfile first:

```bash
sudo fallocate -l 48G <project folder>/swapfile && sudo chmod 600 <project folder>/swapfile
sudo mkswap <project folder>/swapfile && sudo swapon --priority 10 <project folder>/swapfile
```

`scripts/build.sh` runs the build in a memory-limited systemd scope (`--mem-high`,
`--mem-max`, `GOGC=50`, `-j8`) so an overflow kills the build, not the desktop session.
`./scripts/build.sh --analyze` checks the configuration without compiling.

## Why flashing is done carefully

* The bootloader's fastboot rejects `flash`: everything is flashed from **fastbootd**.
* The stock **recovery has no kernel**: recovery/fastbootd boot with the kernel of `boot`
  and the DTB/modules/bootconfig of `vendor_boot`. A broken `boot`/`vendor_boot` would also
  kill fastbootd and leave only EDL.
* So `recovery` is never flashed, and `scripts/verify-build.py` refuses any build whose
  kernel, DTB, DTBO, vendor_boot modules/load lists, bootconfig or load addresses differ
  from the backup of the device.

## Steps

```bash
# 0. Save your data: the first install wipes the device.

# 1. Stock Android running, root (KernelSU) available: back up the device.
#    Keep the folder somewhere safe — persist/modemst*/fsg/fsc are unique to your unit.
./scripts/backup-stock.sh                 # → ../stock-backups/backup-<date>/

# 2. Sources, blobs, build
./scripts/build.sh --sync
./scripts/build.sh --copy --extract --build --stock-backup ../stock-backups/backup-<date>

# 3. Flash (verifies the build first, then goes to fastbootd; --wipe needed from stock)
./scripts/flash.sh --stock-backup ../stock-backups/backup-<date> --wipe
```

Root afterwards: patch LineageOS' `init_boot` with the KernelSU Next manager (LKM) and
flash it from fastbootd, as on stock (`init_boot` is not used in recovery mode).

### Back to stock

```bash
# bootloader (key combo) → fastboot reboot fastboot
./scripts/restore-stock.sh ../stock-backups/backup-<date>
```

### Last resort: EDL

Official flat build `AR11_FlatBuild_TurboX_C8550_…zip` (ayaneo.com/support/download):
identical partition table, firehose programmer signed with the same key chain as the
device. It is older than an OTA-updated device — unbrick only. `persist` is excluded.

```bash
./scripts/flash.sh --edl --image-dir <unzipped flat build>/ufs
```

## Signing

No Ayaneo app is shipped: PocketDS Extra (launcher, overlay, keyboard, profiles) and the
system patches replace them. PocketDS Extra runs as uid system with the platform key of the
build (test keys by default).

## Known gaps / TODO

* First boot: SELinux is permissive (`androidboot.selinux=permissive`) — collect denials,
  then remove it.
* The console buttons (swap, AYA window, home, profile, FPS) are handled by
  `patches/frameworks/base/0018`; LC / RC reach the games as gamepad keys.
* Not ported: Ayaneo's framework property hooker (per-game spoofing), forced AYAHome as
  default launcher, hidden notifications for the internal controller.
* Miracast (WiFi Display) blobs are not shipped yet.
* Stock kernel is 5.15.104: Android 17 compatibility to be confirmed on first boot.

## Prerequisites

`./build.sh` checks them first (`Customization/scripts/check-prereqs.sh`, skipped with `--skip-checks`) and says
how to install what is missing:

- tools: `repo` (the launcher, /usr/bin/repo), `git`, `git-lfs`, `rsync`, `python3`, `curl`, `zip`, `unzip`, a C
  toolchain (`make`, `gcc`), `bc`, `bison`, `flex`, `lz4`, `xsltproc`; `adb` and `fastboot` only to flash;
  `sudo` only to extract the blobs from a stock backup.
- memory: RAM + swap >= 90 GB (the analysis phase peaks near 66 GB; a 64 GB swap file is enough on a 32 GB machine).
- disk: about 320 GB free (100 GB of sources, 140 GB of build, 30 GB of OTA and work files).
- only to rebuild the patched AYAHome (`tools/`): an Android SDK (platform 36 + cmdline-tools), found through `ANDROID_SDK`, `ANDROID_HOME` or `~/Android/Sdk`.

## Proprietary files

`./build.sh --all` downloads everything that is public (LineageOS, MindTheGapps, KernelSU Next, Obtainium).
Ayaneo's files are not redistributable and come from **your own console**:
- `vendor/ayaneo/pocket_ds/proprietary` (blobs): `./build.sh --all --stock-backup <backup>`
- `kernel/ayaneo/kalama/` (stock kernel, DTB, DTBO, modules) and `packages/gboard/prebuilt/LatinImeGoogle.apk`: from the same backup.

The proprietary blobs are not in the repository: put them in `Customization/vendor/ayaneo/pocket_ds/`, or let
`./build.sh --extract --stock-backup <backup>` extract them from a backup of the console
(`scripts/backup-stock.sh`). Once they are in `src/`, `--extract` is skipped (`--force-extract` to redo it).

## Versions

The image has a CalVer version `YEAR.MONTH.BUILD` (file `device/ayaneo/pocket_ds/VERSION`, e.g. `2026.10.3`:
the third build of October 2026). `scripts/release.sh next` bumps it, run it before `./build.sh`.
It ends up in `ro.pocketds.version` and `ro.pocketds.version.code` (YEAR*10000 + MONTH*100 + BUILD),
in the package names (`PocketDS-<version>-lineage-24.0-<date>-full.zip`) and in `OTA/update.json`
(full package, and the incremental one with the build id it applies to), made by `make-ota.sh`.

### Updates in the console

PocketDS Extra → System update (and the "Update" button of the launcher) reads `update.json` from
`https://github.com/Nolar-Hack/Ayaneo-PocketDS-Lineage-Releases/releases/latest/download/update.json`
(changeable in the app, System update → Update source: `owner/repo` or an https address; `persist.pocketds.update_url` is the system-wide default). Each GitHub release `v<version>` must carry, as assets,
the packages and the `OTA/update.json` made by `make-ota.sh`. If the installed build is the `from` of the
incremental package that one is downloaded, else the complete one (also when the incremental fails).
Installation goes through update_engine in the background; the console only restarts on the user's order.
With SELinux enforcing, update_engine and the app need their own policy rules.

## Settings and releases

`Customization/pocketds.conf` holds the options of the build (`VARIANT`, `JOBS`, memory limits, `KERNELSU`,
`GAPPS`, `BUMP`...) and where releases go (`RELEASE_REPO`, `UPDATE_URL`, `RELEASE_PRERELEASE`, `RELEASE_NOTES`).
Order: that file, then `Customization/pocketds.local.conf` (yours, not in git), then the command line.
`RELEASE_REPO` also sets the update address built into the image (`ro.pocketds.update_url`) and the
addresses written in `update.json`; to publish somewhere else, change it (or override it in
`pocketds.local.conf`) before the build.

`scripts/publish-release.sh` publishes the last build as the release `v<version>` of `RELEASE_REPO`
(complete package, incremental package, `update.json`, notes). It shows what it is about to do and asks first.
