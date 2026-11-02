/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Keys and values of the settings of PocketDS Extra (default shared preferences). */
final class PartsPrefs {
    // Smart Pixels: switches off a share of the pixels of a screen in a checkerboard pattern and
    // moves it slowly, to save power and slow down burn-in of the OLED (main screen).
    static final String SMART_PIXELS = "smart_pixels";
    static final String SMART_PIXELS_PERCENT = "smart_pixels_percent";
    static final String SMART_PIXELS_SHIFT_MIN = "smart_pixels_shift_min";
    static final String SMART_PIXELS_SCREEN = "smart_pixels_screen";
    static final String SMART_PIXELS_ON_SAVER = "smart_pixels_on_power_save";
    static final String SCREEN_MAIN = "main";
    static final String SCREEN_SECOND = "second";
    static final String SCREEN_BOTH = "both";

    // Battery: what is switched off some minutes after the screens went off.
    static final String SUSPEND_DELAY_MIN = "suspend_delay_min";
    static final String SUSPEND_WIFI = "suspend_wifi";
    static final String SUSPEND_BLUETOOTH = "suspend_bluetooth";
    static final String SUSPEND_ONLY_ON_BATTERY = "suspend_only_on_battery";

    // Bluetooth off after N minutes without any connected device.
    static final String BT_IDLE_OFF_MIN = "bt_idle_off_min";

    // Screen off animation like a CRT television.
    static final String CRT_SCREEN_OFF = "crt_screen_off";

    // Performance overlay (frames per second, GPU, CPU...), off after every boot.
    static final String PERF_OVERLAY = "perf_overlay";
    static final String PERF_OVERLAY_X = "perf_overlay_x";
    static final String PERF_OVERLAY_Y = "perf_overlay_y";

    // Power off: some minutes after the lid was closed / the screen went off.
    static final String POWEROFF_LID_MIN = "poweroff_lid_min";
    static final String POWEROFF_SCREEN_MIN = "poweroff_screen_min";
    static final String POWEROFF_ONLY_ON_BATTERY = "poweroff_only_on_battery";

    // Performance: profile in use (0 ECO .. 4 Max) and the fan mode ("profile" = the one of the
    // profile, else a PerformanceProfiles.FAN_* value).
    static final String PERF_PROFILE = "perf_profile";
    static final String FAN_MODE = "fan_mode";
    static final String BRIGHTNESS_LINK = "brightness_link";

    // Screens: which screen keeps the controller focus (auto / top / bottom).
    static final String FOCUS_LOCK = "focus_lock";
    // The overlay is only touched: the controller stays with the game while it is open.
    static final String OVERLAY_GAME_FOCUS = "overlay_game_focus";
    // Launcher of the bottom screen: icons per row, rows per page.
    static final String LAUNCHER_COLUMNS = "launcher_columns";
    static final String LAUNCHER_ROWS = "launcher_rows";
    // Detection threshold of the main screen touch (register 0x80): lower = more sensitive.
    static final String TOUCH_THRESHOLD = "touch_threshold";
    // Swipe from the left / right edge of the second screen = Back (the system gestures only work
    // on the default display).
    static final String SECOND_GESTURES = "second_screen_gestures";
    // The USB port goes back to the power sink role when it feeds a cable with no device on it.
    static final String USB_POWER_GUARD = "usb_power_guard";
    // The TurnipZ driver is put in the driver folder of the Switch emulators.
    static final String GPU_DRIVER_PACK = "gpu_driver_pack";
    static final String USB_POWER_GUARD_LAST = "usb_power_guard_last";

    static final String MAIN_SCREEN_ID = "local:4630946412288502659";
    static final String SECOND_SCREEN_ID = "local:4630946708815090308";

    private PartsPrefs() {}

    static SharedPreferences get(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** A number stored as a string (list preferences), with a default. */
    static int getInt(SharedPreferences prefs, String key, int fallback) {
        try {
            final Object value = prefs.getAll().get(key);
            if (value instanceof Number) return ((Number) value).intValue();
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
