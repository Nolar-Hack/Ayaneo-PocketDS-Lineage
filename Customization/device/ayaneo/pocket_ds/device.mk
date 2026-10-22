#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Ayaneo Pocket DS. Qualcomm-common parts follow android_device_ayn_qcs8550-common;
# everything marked "stock" was taken from the device itself.
#

# Add common definitions for Qualcomm
$(call inherit-product, hardware/qcom-caf/common/common.mk)

# A/B
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/launch_with_vendor_ramdisk.mk)

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_system=true \
    POSTINSTALL_PATH_system=system/bin/otapreopt_script \
    FILESYSTEM_TYPE_system=ext4 \
    POSTINSTALL_OPTIONAL_system=true

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_vendor=true \
    POSTINSTALL_PATH_vendor=bin/checkpoint_gc \
    FILESYSTEM_TYPE_vendor=ext4 \
    POSTINSTALL_OPTIONAL_vendor=true

PRODUCT_PACKAGES += \
    checkpoint_gc \
    otapreopt_script

# AAPT
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := xxhdpi

# Audio — source-built QTI HAL + stock configs (card kalama-qrd-dvt-snd-card)
PRODUCT_PACKAGES += \
    android.hardware.audio@7.1-impl \
    android.hardware.audio.effect@7.0-impl \
    android.hardware.audio.service \
    android.hardware.bluetooth.audio-impl \
    android.hardware.soundtrigger@2.3-impl \
    audio.bluetooth.default \
    audio.r_submix.default \
    audio.usb.default \
    audioadsprpcd \
    libagm_compress_plugin \
    libagm_mixer_plugin \
    libagm_pcm_plugin \
    libbatterylistener \
    libfmpal \
    libhfp_pal \
    libqcompostprocbundle \
    libqcomvisualizer \
    libqcomvoiceprocessing \
    libtinycompress \
    libvolumelistener \
    sound_trigger.primary.kalama

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/audio/audio_effects.conf:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_kalama/audio_effects.conf \
    $(LOCAL_PATH)/configs/audio/audio_effects.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_kalama/audio_effects.xml \
    $(LOCAL_PATH)/configs/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    $(LOCAL_PATH)/configs/audio/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    $(LOCAL_PATH)/configs/audio/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml \
    $(LOCAL_PATH)/configs/audio/card-defs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/card-defs.xml \
    $(LOCAL_PATH)/configs/audio/microphone_characteristics.xml:$(TARGET_COPY_OUT_VENDOR)/etc/microphone_characteristics.xml \
    $(LOCAL_PATH)/configs/audio/mixer_paths_kalama_qrd.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_kalama/mixer_paths_kalama_qrd.xml \
    $(LOCAL_PATH)/configs/audio/mixer_paths_kalama_qrd_dvt.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_kalama/mixer_paths_kalama_qrd_dvt.xml \
    $(LOCAL_PATH)/configs/audio/resourcemanager_kalama_qrd.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_kalama/resourcemanager_kalama_qrd.xml \
    $(LOCAL_PATH)/configs/audio/usecaseKvManager.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usecaseKvManager.xml

PRODUCT_COPY_FILES += \
    frameworks/av/services/audiopolicy/config/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
    frameworks/av/services/audiopolicy/config/bluetooth_audio_policy_configuration_7_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_audio_policy_configuration_7_0.xml \
    frameworks/av/services/audiopolicy/config/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml \
    frameworks/native/data/etc/android.hardware.audio.low_latency.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.low_latency.xml \
    frameworks/native/data/etc/android.hardware.audio.pro.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.pro.xml \
    frameworks/native/data/etc/android.software.midi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.midi.xml

$(call soong_config_set_bool,android_hardware_audio,skip_speaker_layout_channel_mask_field,true)

# Audio stack 64-bit only (stock runs android.hardware.audio.service_64); the CAF
# sources are not 32-bit clean. 32-bit apps/games are unaffected.
$(call soong_config_set_bool,android_hardware_audio,run_64bit,true)

# No Ayaneo app is shipped any more: PocketDS Extra provides the homes, the overlay, the
# profiles, the keyboard and the controller settings, the system handles the console buttons
# (patches/frameworks/base/0018).

# Gboard, the stock keyboard (default keyboard, see init/pocketds-firstboot.sh).
# See packages/gboard.
PRODUCT_PACKAGES += \
    LatinImeGoogle

# KernelSU profile of PocketDS Extra (root for the performance engine), set on the first boot.
PRODUCT_COPY_FILES += \
    device/ayaneo/pocket_ds/configs/ksu/pocketds-parts-profile.json:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/pocketds/pocketds-parts-profile.json

# Obtainium: app updater/installer (APK from GitHub), see packages/obtainium. Its emulation
# app list is copied to Downloads by PocketDS Extra.
PRODUCT_PACKAGES += \
    Obtainium


# Bluetooth (WCN7850 "hamilton")
PRODUCT_PACKAGES += \
    lib_bt_aptx \
    lib_bt_ble \
    lib_bt_bundle

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml

# Boot control
PRODUCT_PACKAGES += \
    android.hardware.boot-service.qti \
    android.hardware.boot-service.qti.recovery

# Camera — none on the Pocket DS (stock reports 0 camera devices)
PRODUCT_NO_CAMERA := true

# Dalvik — 16 GB device
$(call inherit-product, frameworks/native/build/phone-xhdpi-6144-dalvik-heap.mk)

# Bring-up (build with scripts/build.sh --bringup, i.e. WITH_ADB_INSECURE=true):
# adb without authorization as early as possible, ro.debuggable=1 (adb root,
# androidboot.selinux=permissive honoured). TODO: drop once the device boots.
ifeq ($(WITH_ADB_INSECURE),true)
PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.usb.config=mtp,adb

# Dump dmesg/logcat/ps/props to /metadata/bootlog during boot (read after a hang)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/bringup/bootlog.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/bin/bootlog.sh \
    $(LOCAL_PATH)/bringup/bootlog.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/bootlog.rc \
    $(LOCAL_PATH)/bringup/pstore-dump.rc:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/system/etc/init/pstore-dump.rc
endif

# DebugFS
PRODUCT_SET_DEBUGFS_RESTRICTIONS := true

# Display — main 1080x1920 OLED up to 165 Hz, secondary 768x1024 LCD
PRODUCT_PACKAGES += \
    android.hardware.graphics.mapper@4.0-impl-qti-display \
    init.qti.display_boot.rc \
    init.qti.display_boot.sh \
    vendor.qti.hardware.display.allocator-service \
    vendor.qti.hardware.display.composer-service \
    vendor.qti.hardware.display.demura-service

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.touchscreen.multitouch.jazzhand.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.touchscreen.multitouch.jazzhand.xml

# DRM
PRODUCT_PACKAGES += \
    android.hardware.drm-service.clearkey

# Enforce generic ramdisk allow list
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)

# Fingerprint — FocalTech sensor in the power button: AOSP 2.1 service (same as
# stock) loading the FocalTech fingerprint.default.so blob
PRODUCT_PACKAGES += \
    android.hardware.biometrics.fingerprint@2.1-service

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.fingerprint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.fingerprint.xml

# Fstab — stock fstab.qcom (verified identical by scripts/verify-build.py)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/fstab.qcom:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.qcom \
    $(LOCAL_PATH)/rootdir/fstab.qcom:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.qcom

# GApps (MindTheGapps, Android 17 branch "cinnamonbun"), unless built with --gapps none: the
# image then has none and keeps room in product for another package (NikGApps...), see
# BoardConfig.mk.
ifneq ($(POCKETDS_GAPPS),none)
$(call inherit-product-if-exists, vendor/gapps/arm64/arm64-vendor.mk)
endif

# GPS — stock declares no android.hardware.location.gps (network location only)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/gps/gps.conf:$(TARGET_COPY_OUT_VENDOR)/etc/gps.conf

# Graphics (Adreno "A32") — same features as stock: Vulkan 1.3, dEQP level 2022-03-01
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml \
    frameworks/native/data/etc/android.hardware.vulkan.compute-0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.compute-0.xml \
    frameworks/native/data/etc/android.hardware.vulkan.level-1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.level-1.xml \
    frameworks/native/data/etc/android.hardware.vulkan.version-1_3.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.version-1_3.xml \
    frameworks/native/data/etc/android.software.opengles.deqp.level-2022-03-01.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.opengles.deqp.level.xml \
    frameworks/native/data/etc/android.software.vulkan.deqp.level-2022-03-01.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.vulkan.deqp.level.xml

# Handheld: tablet hardware profile, gamepad
PRODUCT_CHARACTERISTICS := device
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/tablet_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/tablet_core_hardware.xml \
    frameworks/native/data/etc/android.hardware.gamepad.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.gamepad.xml

# Health
PRODUCT_PACKAGES += \
    android.hardware.health-service.qti

# Init — stock Thundercomm/Ayaneo scripts (Wi-Fi MAC from ro.boot.wifimac, sensors...)
PRODUCT_PACKAGES += \
    init.class_main.sh \
    init.qcom.early_boot.sh \
    init.qcom.rc \
    init.qcom.recovery.rc \
    init.qcom.sh \
    init.qcom.usb.rc \
    init.qcom.usb.sh \
    init.target.rc \
    init.pocketds.fan.sh \
    ueventd.qcom.rc

# Input — internal controller (USB 4001:0428, Generic.kl), "AYANEO DEVICE" extra
# buttons (1c4f:0002) and gpio-keys rewritten with AOSP keycodes (stock uses
# Ayaneo-only AYA_* keycodes that do not exist outside their framework).
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/idc/Vendor_4001_Product_0428.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/Vendor_4001_Product_0428.idc \
    $(LOCAL_PATH)/idc/Vendor_1c4f_Product_0002.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/Vendor_1c4f_Product_0002.idc \
    $(LOCAL_PATH)/idc/fts_ts.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/fts_ts.idc \
    $(LOCAL_PATH)/idc/goodix-ts.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/goodix-ts.idc \
    $(LOCAL_PATH)/configs/display_settings.xml:$(TARGET_COPY_OUT_VENDOR)/etc/display_settings.xml \
    $(LOCAL_PATH)/keylayout/gpio-keys.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/gpio-keys.kl

# Kernel — system_dlkm blocklist from stock vendor_dlkm
PRODUCT_COPY_FILES += \
    kernel/ayaneo/kalama/vendor_dlkm/system_dlkm.modules.blocklist:$(TARGET_COPY_OUT_VENDOR_DLKM)/lib/modules/system_dlkm.modules.blocklist

# Keymint — declare exactly what stock declares: KeyMint 2 in the TEE (hardware_keystore
# 200, AOSP's versioned file, not the unversioned one that declares the latest KeyMint)
# and no app_attest_key / device_id_attestation, which the SPU StrongBox cannot do
# (attestation failed with -10003).
PRODUCT_PACKAGES += \
    android.hardware.hardware_keystore_V2.xml

# PocketDS Parts: second screen brightness / keyboard, touch tuning (parts/)
PRODUCT_PACKAGES += \
    PocketDSParts

# First boot: KernelSU Next manager, PocketDS Extra as home screen (init/pocketds-firstboot.sh)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/init/pocketds-firstboot.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/bin/pocketds-firstboot.sh \
    $(LOCAL_PATH)/init/pocketds-firstboot.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/pocketds-firstboot.rc

# Walt governor on every CPU cluster, the performance profiles only set the frequency range
# (init/pocketds-cpugov.sh)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/init/pocketds-cpugov.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/bin/pocketds-cpugov.sh \
    $(LOCAL_PATH)/init/pocketds-cpugov.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/pocketds-cpugov.rc
ifneq ($(wildcard packages/kernelsu/prebuilt/KernelSUNext.apk),)
PRODUCT_PACKAGES += KernelSUNext.apk.firstboot
endif

# Lineage Health — charge limit through the stock qcom-battery node
PRODUCT_PACKAGES += \
    vendor.lineage.health-service.default

$(call soong_config_set,lineage_health,charging_control_charging_path,/sys/class/qcom-battery/restrict_chg)
$(call soong_config_set,lineage_health,charging_control_charging_enabled,0)
$(call soong_config_set,lineage_health,charging_control_charging_disabled,1)

# LiveDisplay
PRODUCT_PACKAGES += \
    vendor.lineage.livedisplay-service.sdm

$(call soong_config_set_bool,livedisplay_sdm,enable_dm,false)

# Media — stock picks the "_kalama" variant for soc_id 601 (AYN's 603 uses _kalama_iot)
PRODUCT_COPY_FILES += \
    hardware/qcom-caf/sm8550/audio/primary-hal/configs/common/codec2/media_codecs_c2_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_c2_audio.xml \
    hardware/qcom-caf/sm8550/audio/primary-hal/configs/common/codec2/service/1.0/c2audio.vendor.base-arm64.policy:$(TARGET_COPY_OUT_VENDOR)/etc/seccomp_policy/c2audio.vendor.base-arm64.policy \
    hardware/qcom-caf/sm8550/audio/primary-hal/configs/common/codec2/service/1.0/c2audio.vendor.ext-arm64.policy:$(TARGET_COPY_OUT_VENDOR)/etc/seccomp_policy/c2audio.vendor.ext-arm64.policy \
    $(LOCAL_PATH)/configs/media/media_codecs_kalama.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_kalama.xml \
    $(LOCAL_PATH)/configs/media/media_codecs_kalama_vendor.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_kalama_vendor.xml \
    $(LOCAL_PATH)/configs/media/media_codecs_performance_kalama.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance_kalama.xml \
    $(LOCAL_PATH)/configs/media/media_codecs_performance_kalama_vendor.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance_kalama_vendor.xml \
    $(LOCAL_PATH)/configs/media/media_profiles_kalama.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_kalama.xml \
    $(LOCAL_PATH)/configs/media/video_system_specs_kalama.json:$(TARGET_COPY_OUT_VENDOR)/etc/media_kalama/video_system_specs.json

# Memtrack
PRODUCT_PACKAGES += \
    vendor.qti.hardware.memtrack-service

# Overlays
DEVICE_PACKAGE_OVERLAYS += $(LOCAL_PATH)/overlay-lineage
PRODUCT_ENFORCE_RRO_TARGETS := *
PRODUCT_PACKAGES += \
    FrameworksResPocketDS \
    SettingsProviderPocketDS

# Partitions
PRODUCT_PACKAGES += \
    vendor_bt_firmware_mountpoint \
    vendor_dsp_mountpoint \
    vendor_firmware_mnt_mountpoint

PRODUCT_USE_DYNAMIC_PARTITIONS := true

# Perf / power — stock tuning
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/perf/perfconfigstore.xml:$(TARGET_COPY_OUT_VENDOR)/etc/perf/perfconfigstore.xml \
    $(LOCAL_PATH)/configs/perf/powerhint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/powerhint.xml

PRODUCT_PACKAGES += \
    android.hardware.power-service-qti

# The perf HAL learning module (app launch and fling boosts, see proprietary-files.txt)
# needs sqlite: it came from the VNDK on stock, a vendor variant since Android 17.
PRODUCT_PACKAGES += \
    libsqlite.vendor \
    vendor.qti.hardware.servicetracker@1.0.vendor \
    vendor.qti.hardware.servicetracker@1.1.vendor \
    vendor.qti.hardware.servicetracker@1.2.vendor

# Sensors — ICM4x6xx accel + gyro, Ayaneo virtual magnetometer, QTI virtual
# sensors. No light / proximity / barometer / humidity on this device.
PRODUCT_PACKAGES += \
    android.hardware.sensors-service.multihal \
    sensors.dynamic_sensor_hal

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/sensors/hals.conf:$(TARGET_COPY_OUT_VENDOR)/etc/sensors/hals.conf \
    frameworks/native/data/etc/android.hardware.sensor.accelerometer.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.accelerometer.xml \
    frameworks/native/data/etc/android.hardware.sensor.compass.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.compass.xml \
    frameworks/native/data/etc/android.hardware.sensor.gyroscope.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.gyroscope.xml

# Shipping API — stock launched with Android 13
BOARD_SHIPPING_API_LEVEL := 33
PRODUCT_SHIPPING_API_LEVEL := $(BOARD_SHIPPING_API_LEVEL)

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Security
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/ssg/sec_channel.rc:$(TARGET_COPY_OUT_VENDOR)/etc/sec_channel.rc

# Storage
$(call inherit-product, $(SRC_TARGET_DIR)/product/emulated_storage.mk)

# Thermal
PRODUCT_PACKAGES += \
    android.hardware.thermal-service.qti

# Update engine
PRODUCT_PACKAGES += \
    update_engine \
    update_engine_sideload \
    update_verifier

# USB
PRODUCT_PACKAGES += \
    android.hardware.usb-service.qti \
    android.hardware.usb.gadget-service.qti \
    usb_compositions.conf

PRODUCT_SOONG_NAMESPACES += \
    vendor/qcom/opensource/usb/etc

# usb_compositions.conf comes from vendor/qcom/opensource/usb/hal (superset of stock: +6 QC debug compositions)
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml \
    frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml

# Vendor service manager
PRODUCT_PACKAGES += \
    vndservicemanager

# Verified Boot
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.verified_boot.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.verified_boot.xml

# Vibrator — qcom-hv-haptics (stock excluded-input-devices keeps the controller's
# own "aya_haptic" rumble device out of the system vibrator)
PRODUCT_PACKAGES += \
    vendor.qti.hardware.vibrator.service

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/excluded-input-devices.xml:$(TARGET_COPY_OUT_VENDOR)/etc/excluded-input-devices.xml

# WiFi — WCN7850 (Kiwi v2)
PRODUCT_PACKAGES += \
    android.hardware.wifi-service \
    hostapd \
    libwpa_client \
    libwifi-hal-ctrl \
    libwifi-hal-qcom \
    wpa_supplicant \
    wpa_supplicant.conf

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.wifi.aware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.aware.xml \
    frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
    frameworks/native/data/etc/android.hardware.wifi.rtt.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.rtt.xml \
    frameworks/native/data/etc/android.hardware.wifi.passpoint.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.passpoint.xml \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.software.ipsec_tunnels.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.ipsec_tunnels.xml

PRODUCT_PACKAGES += \
    firmware_wlanmdsp.otaupdate_symlink \
    firmware_wlan_mac.bin_symlink \
    firmware_WCNSS_qcom_cfg.ini_symlink

# Inherit from the proprietary files makefile.
$(call inherit-product, vendor/ayaneo/pocket_ds/pocket_ds-vendor.mk)
