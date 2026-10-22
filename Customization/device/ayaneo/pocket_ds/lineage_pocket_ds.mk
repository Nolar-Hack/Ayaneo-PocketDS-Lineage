#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
# 64-bit only (no 32-bit zygote, unlike stock): every Ayaneo app is arm64, the vendor
# blobs taken from the AYN reference have no 32-bit Adreno drivers, and Play hides
# 32-bit-only apps. The 32-bit graphics blobs are kept, commented, in
# proprietary-files.txt should 32-bit apps ever be needed again.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)

# Inherit from the device
$(call inherit-product, device/ayaneo/pocket_ds/device.mk)

# Inherit some common Lineage stuff (tablet: no telephony, the device has no modem)
$(call inherit-product, vendor/lineage/config/common_full_tablet_wifionly.mk)

PRODUCT_NAME := lineage_pocket_ds
PRODUCT_DEVICE := pocket_ds
PRODUCT_BRAND := AYANEO
PRODUCT_MODEL := Pocket DS
PRODUCT_MANUFACTURER := AYANEO
PRODUCT_SYSTEM_DEVICE := PocketDS
PRODUCT_SYSTEM_NAME := AYANEO_PocketDS

# Ayaneo apps identify the device through these exact stock values
# (ro.product.name / ro.product.device and their partition variants).
PRODUCT_BUILD_PROP_OVERRIDES += \
    DeviceName=PocketDS \
    DeviceProduct=AYANEO_PocketDS

# The Ayaneo dual screen support keys on the stock model code (framework, see
# patches/frameworks/base/0003-ayaneo-dual-screen.patch) and on a "_YYYYMMDD_" system
# date in ro.build.display.id (stock: 01.00_20261010_private_user): AYAHome enables its
# current dual screen features (bottom-screen-only as a display swap, focus lock) from
# the date 20260819 on. The build info block of each partition sets a default build
# display id: the product one is loaded last and wins, so it is overridden there too.
POCKETDS_BUILD_DISPLAY_ID := lineage-24.0_$(shell date -u +%Y%m%d)_pocket_ds
PRODUCT_SYSTEM_PROPERTIES += \
    ro.product.device.type=AR11 \
    ro.build.display.id=$(POCKETDS_BUILD_DISPLAY_ID)
PRODUCT_PRODUCT_PROPERTIES += \
    ro.build.display.id=$(POCKETDS_BUILD_DISPLAY_ID)

PRODUCT_GMS_CLIENTID_BASE := android-ayaneo
