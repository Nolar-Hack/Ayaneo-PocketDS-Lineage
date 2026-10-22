#!/system/bin/sh
# One-time setup on the first boot of a fresh install (flag:
# persist.sys.pocketds.firstboot). Run by init once boot is completed.
#  - KernelSU Next manager: root itself is in init_boot, the app cannot be a
#    preinstalled system app (compressed native libraries), install it as a user app.
#  - Ayaneo apps enabled (standalone AYAWindow left to AYAHome); key service on.
#  - AYA performance profiles (CPU / GPU frequencies, fan) of the user.
#  - Root for PocketDS Extra (KernelSU profile, retried at each boot until stored).
#  - Advanced restart enabled (power menu: recovery, bootloader).
#  - AYAHome as the home screen (HOME role), like stock. Trebuchet must stay enabled:
#    it is the recents / gestures provider (config_recentsComponentName) and its
#    TouchInteractionService crashes when its own launcher activity is disabled.
# Root for PocketDS Extra (KernelSU Next app profile: uid 1000, su allowed). ksud is the
# libksud.so of the manager app: /data/adb/ksud only exists once somebody opened the manager.
# Retried at every boot until the profile is stored.
ksu_profile() {
    PROFILE=/system_ext/etc/pocketds/pocketds-parts-profile.json
    PKG=org.lineageos.pocketds.parts
    KSUD=/data/adb/ksud
    if [ ! -x "$KSUD" ]; then
        APPDIR=$(pm path com.rifsxd.ksunext 2>/dev/null | sed 's/^package://; s|/base.apk$||')
        KSUD="$APPDIR/lib/arm64/libksud.so"
    fi
    [ -x "$KSUD" ] || return
    # What is stored is checked, not only a flag: after an update or a reinstall the profile may be
    # missing or su not allowed, and the app then has no root (fan, touch, per-app volume).
    if "$KSUD" profile get "$PKG" 2>/dev/null | grep -q '"allow_su": true'; then
        setprop persist.sys.pocketds.ksuprofile 1
        return
    fi
    setprop persist.sys.pocketds.ksuprofile 0
    if [ -f "$PROFILE" ] && "$KSUD" profile set "$PROFILE"; then
        setprop persist.sys.pocketds.ksuprofile 1
    fi
}

# PocketDS Extra runs the performance overlay (central button), the screen-off animation and the
# other controllers from its Application. On a fresh install nobody has launched it and Android
# does not send it BOOT_COMPLETED: start its keep-alive service, which also lifts the "stopped" state.
start_parts() {
    am start-foreground-service --user 0 -n org.lineageos.pocketds.parts/.KeepAliveService >/dev/null 2>&1
}

[ "$(getprop persist.sys.pocketds.firstboot)" = "1" ] && { ksu_profile; start_parts; exit 0; }

APK=/system_ext/etc/pocketds/KernelSUNext.apk
if [ -f "$APK" ] && ! pm path com.rifsxd.ksunext >/dev/null 2>&1; then
    pm install -r "$APK"
fi
ksu_profile
start_parts

# PocketDS Extra provides the homes (main screen wallpaper, bottom screen list of apps), the
# overlay and the profiles; the console buttons are handled by the system. No Ayaneo app is shipped.
cmd role add-role-holder --user 0 android.app.role.HOME org.lineageos.pocketds.parts 0

# Keyboards: Gboard stays enabled as a fallback, the PocketDS keyboard (full bottom screen,
# colours of the overlay) is the default; Gboard is the default if ours is missing.
GBOARD=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
POCKETDS_IME=org.lineageos.pocketds.parts/.PocketKeyboardService
ime enable "$GBOARD"
if ime enable "$POCKETDS_IME"; then ime set "$POCKETDS_IME"; else ime set "$GBOARD"; fi

# File transfer (MTP) as the default USB mode once the screen is unlocked. Android otherwise
# stays on "charging only" after a wipe until the user picks File transfer by hand; the choice
# is stored per user (Settings > Connected devices > USB > Default USB configuration).
svc usb setScreenUnlockedFunctions mtp

# System vibrations (touch, keyboard, notifications) go to the input devices that have a motor.
# The real motor of the console is the one of the built-in controller ("AYANEO Controller
# aya_haptic"); the PMIC haptics (qcom-hv-haptics) that Android uses by default drives nothing.
settings put system vibrate_input_devices 1

# Advanced restart (recovery / bootloader in the power menu), a LineageOS setting
content insert --uri content://lineagesettings/secure \
    --bind name:s:advanced_reboot --bind value:s:1

setprop persist.sys.pocketds.firstboot 1
