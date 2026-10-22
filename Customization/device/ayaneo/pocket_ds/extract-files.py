#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Ayaneo Pocket DS. Fixups follow LineageOS/android_device_ayn_qcs8550-common
# (same KalamaP/Kailua BSP); the WiFi Display ones are dropped because those
# blobs are not extracted here.
#
# Extract from a backup made by scripts/backup-stock.sh (mounted read-only):
#   ./extract-files.py <dir with system/ vendor/ product/ system_ext/ odm/>
# or straight from the device over adb (root):  ./extract-files.py
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)

from extract_utils.fixups_lib import (
    lib_fixups,
    lib_fixups_user_type,
)

from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/ayaneo/pocket_ds',
    'hardware/qcom-caf/sm8550',
    'hardware/qcom-caf/wlan',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/qcom/opensource/commonsys/display',
    'vendor/qcom/opensource/dataservices',
    'vendor/qcom/opensource/display',
]


def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_{partition}' if partition == 'vendor' else None


lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    (
        'vendor.qti.diaghal@1.0',
        'vendor.qti.hardware.fm@1.0',
        'vendor.qti.hardware.qccsyshal@1.0',
        'vendor.qti.hardware.qccsyshal@1.1',
        'vendor.qti.hardware.qccsyshal@1.2',
    ): lib_fixup_vendor_suffix,
}

blob_fixups: blob_fixups_user_type = {
    ('vendor/bin/hw/android.hardware.security.keymint-service-qti',
     'vendor/bin/hw/android.hardware.security.keymint-service-spu-qti',
     'vendor/lib64/libqtikeymint.so', 'vendor/lib64/libspukeymint.so'): blob_fixup()
        .add_needed('android.hardware.security.rkp-V3-ndk.so'),
    'vendor/etc/seccomp_policy/qwesd@2.0.policy': blob_fixup()
        .add_line_if_missing('gettid: 1')
        .add_line_if_missing('pipe2: 1'),
    'vendor/lib64/hw/audio.primary.kalama.so': blob_fixup()
        .binary_regex_replace(b'/sys/class/switch_legacy/hdmi_audio/state',
                              b'/sys/class/switch/hdmi_audio/state\x00\x00\x00\x00\x00\x00\x00'),
    # No HIDL definition for vendor.aks.gamepad in the tree, so host_init_verifier
    # rejects the lazy "interface" line. The service is "disabled" and was not
    # running on stock either; it just can no longer be started on demand.
    'vendor/etc/init/vendor.aks.gamepad@1.0-service.rc': blob_fixup()
        .regex_replace('    interface vendor.aks.gamepad@1.0::IGamepad default\n', ''),
    # FocalTech HAL module ships with DT_SONAME libfingerprint.focaltech.default.so
    'vendor/lib64/hw/fingerprint.default.so': blob_fixup()
        .fix_soname(),
    'vendor/lib64/libqcodec2_core.so': blob_fixup()
        .add_needed('libcodec2_shim.so'),
    (
        'vendor/lib64/libdpps.so',
        'vendor/lib64/libsnapdragoncolor-manager.so',
    ): blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
}  # fmt: skip

module = ExtractUtilsModule(
    'pocket_ds',
    'ayaneo',
    namespace_imports=namespace_imports,
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
