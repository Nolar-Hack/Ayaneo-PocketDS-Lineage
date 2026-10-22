#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Ayaneo Pocket DS (AR11) — Snapdragon G3x Gen 2 (SG8275, soc_id 601, "KalamaP SG").
# Qualcomm-common parts follow LineageOS/android_device_ayn_qcs8550-common
# (same KalamaP die, Kailua BSP). Every device-specific value below was read on a
# running Pocket DS (stock 01.00_20261010); see docs/device-facts.md.
#

DEVICE_PATH := device/ayaneo/pocket_ds
KERNEL_PATH := kernel/ayaneo/kalama

# A/B
# `recovery` is deliberately absent: neither flash.sh nor an OTA may ever rewrite it. The stock
# recovery holds the only known-good fastbootd (it has no kernel of its own, see below).
AB_OTA_PARTITIONS += \
    boot \
    dtbo \
    init_boot \
    odm \
    product \
    system \
    system_dlkm \
    system_ext \
    vbmeta \
    vbmeta_system \
    vendor \
    vendor_boot \
    vendor_dlkm

# Architecture — stock is zygote64_32 (A710/A510 still run AArch32): keep 32-bit
# apps working, older games and emulators rely on them.
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-2a-dotprod
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := kryo300

TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-2a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := generic
TARGET_2ND_CPU_VARIANT_RUNTIME := cortex-a75

# Audio (QTI AR/PAL stack, same as stock: card "kalama-qrd-dvt-snd-card")
AUDIO_FEATURE_ENABLED_DLKM := true
AUDIO_FEATURE_ENABLED_EXTENDED_COMPRESS_FORMAT := true
AUDIO_FEATURE_ENABLED_GEF_SUPPORT := true
AUDIO_FEATURE_ENABLED_GKI := true
AUDIO_FEATURE_ENABLED_INSTANCE_ID := true
AUDIO_FEATURE_ENABLED_AGM_HIDL := true
AUDIO_FEATURE_ENABLED_PAL_HIDL := true
AUDIO_FEATURE_ENABLED_PROXY_DEVICE := true
AUDIO_FEATURE_ENABLED_SSR := true
AUDIO_FEATURE_ENABLED_SVA_MULTI_STAGE := true
BOARD_SUPPORTS_OPENSOURCE_STHAL := true
BOARD_SUPPORTS_SOUND_TRIGGER := true
BOARD_USES_ALSA_AUDIO := true
TARGET_PROVIDES_AUDIO_HAL := true
TARGET_PROVIDES_LIBAGM := true
TARGET_PROVIDES_LIBAR_PAL := true
TARGET_USES_QCOM_MM_AUDIO := true

# Bootloader
TARGET_BOOTLOADER_BOARD_NAME := kalama
TARGET_NO_BOOTLOADER := true

# Platform
BOARD_USES_QCOM_HARDWARE := true
TARGET_BOARD_PLATFORM := kalama

# Display
TARGET_SCREEN_DENSITY := 360

# Filesystem
TARGET_FS_CONFIG_GEN := $(DEVICE_PATH)/config.fs

# ---------------------------------------------------------------------------
# Kernel — PREBUILT stock kernel, DTB, DTBO and modules, all from the same OTA
# that runs on the device. Stock recovery has no kernel: recovery/fastbootd boot
# with this boot + vendor_boot, so they must stay identical to stock
# (scripts/verify-build.py enforces it before any flash).
# ---------------------------------------------------------------------------
BOARD_BOOT_HEADER_VERSION := 4
BOARD_INIT_BOOT_HEADER_VERSION := 4
BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --dtb_offset 0x01f00000 --tags_offset 0x00000100
BOARD_MKBOOTIMG_INIT_ARGS += --header_version $(BOARD_INIT_BOOT_HEADER_VERSION)
BOARD_RAMDISK_USE_LZ4 := true

BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_PAGESIZE := 4096
BOARD_KERNEL_OFFSET := 0x00008000
BOARD_RAMDISK_OFFSET := 0x01000000
BOARD_KERNEL_IMAGE_NAME := Image
BOARD_USES_GENERIC_KERNEL_IMAGE := true

TARGET_KERNEL_VERSION := 5.15
TARGET_PREBUILT_KERNEL := $(KERNEL_PATH)/Image

# Kernel headers: hardware/qcom-caf needs the kernel + QTI (audio, display, video)
# UAPI headers, generated from a kernel source tree. The AYN QCS8550 kernel (same
# KalamaP die, android13-5.15; see local_manifests/kernel-headers.xml) is used for
# that ONLY — TARGET_FORCE_PREBUILT_KERNEL keeps the stock Image above as the kernel.
TARGET_KERNEL_SOURCE := kernel/ayn/qcs8550
TARGET_KERNEL_CONFIG := gki_defconfig vendor/kalama_GKI.config
TARGET_FORCE_PREBUILT_KERNEL := true

BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_PREBUILT_DTBIMAGE_DIR := $(KERNEL_PATH)
BOARD_PREBUILT_DTBOIMAGE := $(KERNEL_PATH)/dtbo.img

# Stock vendor_boot cmdline. With boot header v4, BOARD_KERNEL_CMDLINE is what
# ends up in vendor_boot ("bootconfig" is appended by the build itself).
BOARD_KERNEL_CMDLINE := \
    video=vfb:640x400,bpp=32,memsize=3072000 \
    qcom_geni_serial.con_enabled=0 \
    nosoftlockup

# Bring-up builds (WITH_ADB_INSECURE): turn a kernel panic or a task blocked for
# 120 s into a warm reboot, so the log survives in pstore (ramoops) and can be
# read from recovery. TODO: drop once the device boots.
ifeq ($(WITH_ADB_INSECURE),true)
BOARD_KERNEL_CMDLINE += panic=5 hung_task_panic=1 hung_task_timeout_secs=120
endif

# Stock bootconfig
BOARD_BOOTCONFIG := \
    androidboot.hardware=qcom \
    androidboot.memcg=1 \
    androidboot.usbcontroller=a600000.dwc3

# Bring-up: vendor sepolicy is rebuilt from source; boot permissive so denials
# cannot bootloop the first build. userdebug/eng only. TODO: remove.
BOARD_BOOTCONFIG += androidboot.selinux=permissive

# Modules: shipped byte-identical to stock (no stripping — zram/zsmalloc carry
# the GKI signature, and fastbootd loads the vendor_boot ones).
BOARD_DO_NOT_STRIP_VENDOR_RAMDISK_MODULES := true
BOARD_DO_NOT_STRIP_VENDOR_KERNEL_RAMDISK_MODULES := true
BOARD_DO_NOT_STRIP_RECOVERY_MODULES := true
BOARD_DO_NOT_STRIP_VENDOR_MODULES := true
BOARD_DO_NOT_STRIP_SYSTEM_MODULES := true

BOARD_VENDOR_RAMDISK_KERNEL_MODULES := \
    $(wildcard $(KERNEL_PATH)/vendor_ramdisk/*.ko)
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat $(KERNEL_PATH)/vendor_ramdisk/modules.load))
BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat $(KERNEL_PATH)/vendor_ramdisk/modules.load.recovery))
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_BLOCKLIST_FILE := \
    $(KERNEL_PATH)/vendor_ramdisk/modules.blocklist

BOARD_VENDOR_KERNEL_MODULES := \
    $(wildcard $(KERNEL_PATH)/vendor_dlkm/*.ko)
BOARD_VENDOR_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat $(KERNEL_PATH)/vendor_dlkm/modules.load))
BOARD_VENDOR_KERNEL_MODULES_BLOCKLIST_FILE := \
    $(KERNEL_PATH)/vendor_dlkm/modules.blocklist

BOARD_SYSTEM_KERNEL_MODULES := \
    $(wildcard $(KERNEL_PATH)/system_dlkm/*.ko)
BOARD_SYSTEM_KERNEL_MODULES_LOAD := \
    $(strip $(shell cat $(KERNEL_PATH)/system_dlkm/modules.load))

# Metadata
BOARD_USES_METADATA_PARTITION := true

# Partitions — sizes read on the device (blockdev --getsize64)
BOARD_PRODUCTIMAGE_MINIMAL_PARTITION_RESERVED_SIZE := false
# Free space kept in product. With MindTheGapps in the image it is only the room for the growth
# of the next builds (OTA): 1 GiB. Lineage reserves 1.96 GB there (room to flash GApps
# afterwards), a third of the 6 GiB super. Built with --gapps none (POCKETDS_GAPPS=none), the
# Lineage reserve stays: that room is for NikGApps or another package flashed afterwards.
ifneq ($(POCKETDS_GAPPS),none)
BOARD_PRODUCTIMAGE_PARTITION_RESERVED_SIZE := 1073741824
endif
-include vendor/lineage/config/BoardConfigReservedSize.mk

BOARD_FLASH_BLOCK_SIZE := 262144
BOARD_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_DTBOIMG_PARTITION_SIZE := 25165824
BOARD_INIT_BOOT_IMAGE_PARTITION_SIZE := 8388608
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 104857600
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 100663296

BOARD_ODMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEM_DLKMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := ext4

# Super — stock lpdump: 6 GiB, group qti_dynamic_partitions, virtual A/B
BOARD_SUPER_PARTITION_SIZE := 6442450944
BOARD_SUPER_PARTITION_GROUPS := qti_dynamic_partitions
BOARD_QTI_DYNAMIC_PARTITIONS_SIZE := 6438256640
BOARD_QTI_DYNAMIC_PARTITIONS_PARTITION_LIST := odm product system system_dlkm system_ext vendor vendor_dlkm

TARGET_COPY_OUT_ODM := odm
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm

# Properties
TARGET_ODM_PROP += $(DEVICE_PATH)/properties/odm.prop
TARGET_PRODUCT_PROP += $(DEVICE_PATH)/properties/product.prop
TARGET_SYSTEM_EXT_PROP += $(DEVICE_PATH)/properties/system_ext.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/properties/vendor.prop

# Recovery — the recovery partition is NEVER flashed by scripts/flash.sh (stock
# recovery holds the known-good fastbootd). The image is still built for OTAs.
BOARD_EXCLUDE_KERNEL_FROM_RECOVERY_IMAGE := true
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/rootdir/fstab.qcom
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := true

# Security patch level of the stock vendor/boot images
BOOT_SECURITY_PATCH := 2023-06-05
VENDOR_SECURITY_PATCH := $(BOOT_SECURITY_PATCH)

# SEPolicy
include device/qcom/sepolicy_vndr/SEPolicy.mk
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor

# Verified Boot — disabled (unlocked bootloader); vbmeta flags 3.
BOARD_AVB_ENABLE := true
ifneq (,$(AVB_CUSTOM_KEY_PATH))
BOARD_AVB_ALGORITHM := $(AVB_CUSTOM_ALGORITHM)
BOARD_AVB_KEY_PATH := $(AVB_CUSTOM_KEY_PATH)
else
AVB_CUSTOM_ALGORITHM := SHA256_RSA4096
AVB_CUSTOM_KEY_PATH := external/avb/test/data/testkey_rsa4096.pem
endif
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3
BOARD_AVB_VBMETA_SYSTEM := system system_ext product
BOARD_AVB_VBMETA_SYSTEM_ALGORITHM := $(AVB_CUSTOM_ALGORITHM)
BOARD_AVB_VBMETA_SYSTEM_KEY_PATH := $(AVB_CUSTOM_KEY_PATH)
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX := $(PLATFORM_SECURITY_PATCH_TIMESTAMP)
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX_LOCATION := 2

# VINTF
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE := \
    hardware/qcom-caf/common/vendor_framework_compatibility_matrix.xml \
    $(DEVICE_PATH)/vintf/device_framework_matrix.xml
DEVICE_MATRIX_FILE := hardware/qcom-caf/common/compatibility_matrix.xml
DEVICE_MANIFEST_FILE := \
    hardware/qcom-caf/sm8550/audio/primary-hal/configs/common/manifest_non_qmaa.xml \
    hardware/qcom-caf/sm8550/audio/primary-hal/configs/common/manifest_non_qmaa_extn.xml \
    $(DEVICE_PATH)/vintf/manifest_kalama.xml

# WiFi — WCN7850 (Kiwi), same as AYN
BOARD_WLAN_DEVICE := qcwcn
BOARD_HOSTAPD_DRIVER := NL80211
BOARD_HOSTAPD_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
BOARD_WPA_SUPPLICANT_DRIVER := $(BOARD_HOSTAPD_DRIVER)
BOARD_WPA_SUPPLICANT_PRIVATE_LIB := $(BOARD_HOSTAPD_PRIVATE_LIB)
BOARD_WPA_SUPPLICANT_PRIVATE_LIB_EVENT := "ON"
WIFI_DRIVER_STATE_CTRL_PARAM := "/dev/wlan"
WIFI_DRIVER_STATE_OFF := "OFF"
WIFI_DRIVER_STATE_ON := "ON"
WIFI_HIDL_FEATURE_DUAL_INTERFACE := true
WIFI_HIDL_UNIFIED_SUPPLICANT_SERVICE_RC_ENTRY := true
WPA_SUPPLICANT_VERSION := VER_0_8_X

# Include the proprietary files BoardConfig.
include vendor/ayaneo/pocket_ds/BoardConfigVendor.mk
