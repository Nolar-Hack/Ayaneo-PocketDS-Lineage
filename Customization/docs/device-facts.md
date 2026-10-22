# Ayaneo Pocket DS — facts verified on the device

Everything below was read on a running Pocket DS (stock `01.00_20261010`, Android 13,
slot B) on 2026-10-16. The device tree relies on these values; re-check them after a
stock OTA.

## Identity

| Property | Value |
|---|---|
| `ro.product.model` | `Pocket DS` |
| `ro.product.device` / `ro.build.product` | `PocketDS` |
| `ro.product.name` | `AYANEO_PocketDS` |
| `ro.product.brand` / `manufacturer` | `AYANEO` |
| Project code | `AR11` (panel `mdss_dsi_ar11_7inch_cmd`, flat build `AR11_FlatBuild_TurboX_C8550_*`) |

The Ayaneo apps detect the device through `AYANEO_PocketDS`, `Pocket DS`, `AR11`,
`ro.product.device` and `kalama` — `lineage_pocket_ds.mk` reproduces them.

## SoC

| | |
|---|---|
| SoC | Snapdragon G3x Gen 2, `ro.soc.model=SG8275`, `soc_id` **601** `SG_KALAMAP`, rev 2.0 |
| Platform | `kalama` (KalamaP die), XBL built for `KAILUA` (QCS8550 BSP line) |
| DTB | `qcom,msm-id = <600 v2>, <601 v2>`, `board-id 0x1000b 0x2` — "Kalama QRD, Graph-DVT" |
| CPU | 1× Cortex-X3 3.36 GHz, 4× (A715/A710) 2.80 GHz, 3× A510 2.02 GHz; AArch32 on A710/A510 |
| GPU | `AdrenoA32` |
| RAM / storage | 16 GB / UFS 4.0 (this unit: 1 TB), 6 LUNs `sda`–`sdf` |

### Reference trees

No official LineageOS device has `soc_id` 601. The Qualcomm-common parts follow
`LineageOS/android_device_ayn_qcs8550-common` (AYN Odin 2 / Portal / Thor, `soc_id` 603
QCS8550, same KalamaP die and Kailua BSP; 98 % of its blobs exist on the Pocket DS).
OnePlus 11 (`sm8550-common`, `soc_id` 519) was rejected: 84 % blob overlap, phone BSP.

Differences with AYN that matter:

| Topic | AYN | Pocket DS |
|---|---|---|
| Media XML variant | `_kalama_iot` (soc 603/604) | `_kalama` (`init.qti.media.sh`) |
| Wi-Fi MAC file | `/mnt/vendor/persist/wlan_mac.bin` | `/mnt/vendor/persist/kiwi_v2/wlan_mac.bin` (from `ro.boot.wifimac`) |
| BT MAC | `ro.vendor.bt.bdaddr_path` (Quectel file) | `ro.boot.btmac` |
| USB compositions | `/odm/etc/` | `/vendor/etc/` |
| dtbo partition | 12 MB | 24 MB |
| Display orientation | forced `ORIENTATION_90` | sensor rotation, no override |
| Lid / device states | lid controls sleep, 3 layouts | single `DEFAULT` state |
| Thermal engine | `thermal-engine-v2` | none (thermal HAL + kernel) |
| Charging control | `qcom-battery/charging_enabled` | `qcom-battery/restrict_chg` |
| Sound card | — | `kalama-qrd-dvt-snd-card` |

## Boot / partitions

| | |
|---|---|
| Kernel | `5.15.104-android13-8-g9ca6d3ae9820-dirty`, GKI, boot header v4, page 4096 |
| vendor_boot | kernel 0x8000, ramdisk 0x1000000, tags 0x100, dtb 0x1f00000, lz4 |
| Vendor cmdline | `video=vfb:640x400,bpp=32,memsize=3072000 qcom_geni_serial.con_enabled=0 nosoftlockup bootconfig` |
| Bootconfig | `androidboot.hardware=qcom androidboot.memcg=1 androidboot.usbcontroller=a600000.dwc3` |
| Sizes | boot / vendor_boot 96 MB, init_boot 8 MB, dtbo 24 MB, recovery 100 MB, vbmeta 64 KB |
| super | 6442450944, group `qti_dynamic_partitions` 6438256640, virtual A/B |
| Modules | 307 in vendor_boot, 287 in vendor_dlkm, zram + zsmalloc in system_dlkm (GKI-signed) |
| Recovery | header v4 **without kernel**: recovery/fastbootd use boot + vendor_boot |
| Bootloader fastboot | refuses `flash`; everything goes through fastbootd |

## Hardware

| Part | Detail |
|---|---|
| Main display | 1080×1920 OLED, 60/90/120/144/165 Hz, HDR, 786 nits, density 360 |
| Second display | 768×1024 LCD 60 Hz, density 227, own content (`vendor.display.builtin_presentation=1`) |
| Touch | main FocalTech `fts_ts`, second Goodix `goodix-ts` (touchpad mode) |
| Controller | "AYANEO Controller" USB 4001:0428 (Generic.kl), "AYANEO DEVICE" 1c4f:0002 (BTN_1..7), rumble "aya_haptic" |
| Buttons | `gpio-keys`: KEY_EQUAL (Ayaneo), KEY_A (Space), KEY_LEFT/RIGHT/RIGHTSHIFT (LC/RC), KEY_YELLOW (perf), VOLUMEUP; volume down on `pmic_resin` |
| Fingerprint | FocalTech in the power button, HIDL 2.1 + `vendor.focaltech...IFocalFingerprint` |
| Sensors | ICM4x6xx accel + gyro, AYANEO virtual magnetometer, QTI virtual sensors. No light/proximity/baro/humidity |
| Camera | none (stock wrongly declares the feature) |
| LED | RGB (`red`, `green`, `blue`) — `vendor.qti.hardware.lights.service` |
| Fan | kernel `pwm-fan` (`/sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon0/pwm1`, cooling device), speed kept in tsnv |
| Wi-Fi / BT | WCN7850 "Kiwi v2" / `hamilton` |
| Modem | none (`androidboot.baseband=apq`) |

## Stock framework changes by Ayaneo (not available on LineageOS)

* `SystemProperties.get()` property hooker (`persist.sys.system.property.hooker.*`,
  `persist.sys.soc.model`): per-game spoofing of SoC / Android version.
* `SystemServer` forces `com.ayaneo.home` as HOME role on every boot → pick AYAHome as
  launcher once.
* `InputManagerService` / `UsbDeviceManager` hide notifications for devices named "AYANEO".
* Extra keycodes `KEYCODE_AYA_*` (used by stock `gpio-keys.kl`) → replaced by HOME and
  `MACRO_1..4` in `keylayout/gpio-keys.kl`.

## Ayaneo apps

AYAHome, AYASettings, AYASpace (`com.ayaneo.gamelauncher`), AYAWindow
(`com.ayaneo.gamewindow`), AyaGsSet, AyaKeyAR06Tester: uid 1000, signed with the **AOSP
platform test key** (SHA-1 `27:19:6E:38:…`), checked again by their `libsecurity.so`.
They only use AOSP framework classes (some hidden APIs). Requires a test-keys build.

Left out of the ROM: `com.adups.fota` (stock OTA client), `/product/bin/xsud` (root daemon,
socket mode 0666).

## Kernel risk for Android 17

Stock kernel is 5.15.104 (2023); LineageOS 24 kalama devices ship 5.15.220. Required
features are present (PSI, BPF, binderfs, MGLRU, dm-user, fuse-bpf, incfs) but binder
only advertises `oneway_spam_detection`. Option later: a newer Google GKI
`android13-5.15` boot image, KMI-compatible with the stock modules.

## Audit of the first build (2026-10-17)

* `scripts/verify-build.py`: all checks pass (kernel, DTB, DTBO, vendor_boot modules/load
  lists, cmdline, bootconfig, load addresses, sizes, vbmeta flags).
* Identity props match stock for system/system_ext/product (`AYANEO_PocketDS`, `PocketDS`,
  `Pocket DS`); `ro.build.characteristics=device` like stock. vendor/odm report `PocketDS`
  where stock reports `kalama` (apps read the product value).
* Features aligned with stock: Vulkan 1.3, dEQP 2022-03-01, Wi-Fi Aware + RTT; no
  `location.gps`, no step sensors, no camera.
* GNSS: stock runs a QTI GNSS HAL but reports only `TOTAL_POWER` and zero fixes over 12 h,
  and declares no `location.gps` → no usable GPS; not shipped.
* Android 13 → 17: all 384 non-DSP blobs resolve every NEEDED library and every undefined
  symbol against the built Android 17 libraries (only `eglSubDriverAndroid.so` resolves its
  `EglSubDriverHelper*` symbols from `libEGL_adreno.so` at runtime, as on stock).
  `vendor/lib/rfsa/adsp/*` are Hexagon DSP objects, not loaded by Android.
* Fingerprint: stock framework uses the HIDL 2.1 path (`provider: Fingerprint21`); the AIDL
  `IFingerprint` seen on stock comes from QTI `qfp-daemon` and is unused.
* A/B firmware: all firmware partitions are identical on both slots **except `modem`**
  (NON-HLOS: also carries ADSP/CDSP/Wi-Fi firmware). Before relying on LineageOS OTAs
  (which switch slots), copy `modem_b` to `modem_a` ("copy-partitions").
