/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.media.AudioManager;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

/**
 * What a rule can do. apply() changes something and returns what it was (null when nothing was
 * changed or nothing can be undone), revert() puts it back. Every action works on its own: one
 * failing (a permission, an app that is gone) never stops the others.
 */
final class ActionRunner {
    private static final String TAG = "PocketDSRules";
    private static final String SETTING_PEAK_REFRESH = "peak_refresh_rate";
    private static final String SETTING_DUAL_MODE = "dual_display_mode";
    private static final String OP_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background";

    private ActionRunner() {}

    private static boolean on(String value) {
        return "on".equals(value);
    }

    static String apply(Context context, Rule.Action action) {
        final ContentResolver resolver = context.getContentResolver();
        try {
            switch (action.type) {
                case Rule.A_WIFI: {
                    final WifiManager wifi = context.getSystemService(WifiManager.class);
                    final boolean was = wifi.isWifiEnabled();
                    if (was == on(action.value)) return null;
                    wifi.setWifiEnabled(on(action.value));
                    return was ? "1" : "0";
                }
                case Rule.A_BLUETOOTH: {
                    final BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
                    if (bluetooth == null) return null;
                    final boolean was = bluetooth.isEnabled();
                    if (was == on(action.value)) return null;
                    if (on(action.value)) bluetooth.enable(); else bluetooth.disable();
                    return was ? "1" : "0";
                }
                case Rule.A_LOCATION: {
                    final LocationManager location = context.getSystemService(
                            LocationManager.class);
                    final boolean was = location.isLocationEnabled();
                    if (was == on(action.value)) return null;
                    location.setLocationEnabledForUser(on(action.value),
                            UserHandle.of(ActivityManager.getCurrentUser()));
                    return was ? "1" : "0";
                }
                case Rule.A_SYNC: {
                    final boolean was = ContentResolver.getMasterSyncAutomatically();
                    if (was == on(action.value)) return null;
                    ContentResolver.setMasterSyncAutomatically(on(action.value));
                    return was ? "1" : "0";
                }
                case Rule.A_BATTERY_SAVER: {
                    final PowerManager power = context.getSystemService(PowerManager.class);
                    final boolean was = power.isPowerSaveMode();
                    if (was == on(action.value)) return null;
                    power.setPowerSaveModeEnabled(on(action.value));
                    return was ? "1" : "0";
                }
                case Rule.A_SMART_PIXELS: {
                    final boolean was = PartsPrefs.get(context).getBoolean(
                            PartsPrefs.SMART_PIXELS, false);
                    if (was == on(action.value)) return null;
                    PartsPrefs.get(context).edit().putBoolean(PartsPrefs.SMART_PIXELS,
                            on(action.value)).apply();
                    return was ? "1" : "0";
                }
                case Rule.A_REFRESH: {
                    final String was = Settings.System.getString(resolver, SETTING_PEAK_REFRESH);
                    Settings.System.putString(resolver, SETTING_PEAK_REFRESH,
                            Float.parseFloat(action.value) + "");
                    return was == null ? "" : was;
                }
                case Rule.A_SCREEN_MODE: {
                    final String was = Settings.Global.getString(resolver, SETTING_DUAL_MODE);
                    if (action.value.equals(was)) return null;
                    Settings.Global.putString(resolver, SETTING_DUAL_MODE, action.value);
                    return was == null ? "1" : was;
                }
                case Rule.A_TIMEOUT: {
                    final int was = Settings.System.getInt(resolver,
                            Settings.System.SCREEN_OFF_TIMEOUT, 60000);
                    Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT,
                            Integer.parseInt(action.value));
                    return Integer.toString(was);
                }
                case Rule.A_DARK_MODE: {
                    final UiModeManager ui = context.getSystemService(UiModeManager.class);
                    final int was = ui.getNightMode();
                    ui.setNightMode(on(action.value) ? UiModeManager.MODE_NIGHT_YES
                            : UiModeManager.MODE_NIGHT_NO);
                    return Integer.toString(was);
                }
                case Rule.A_ROTATION: {
                    final int was = Settings.System.getInt(resolver,
                            Settings.System.ACCELEROMETER_ROTATION, 1);
                    Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION,
                            on(action.value) ? 1 : 0);
                    return Integer.toString(was);
                }
                case Rule.A_BRIGHTNESS: {
                    final int mode = Settings.System.getInt(resolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                    final int level = Settings.System.getInt(resolver,
                            Settings.System.SCREEN_BRIGHTNESS, 128);
                    final int percent = Math.max(1, Math.min(100,
                            Integer.parseInt(action.value)));
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                            Math.max(1, percent * 255 / 100));
                    return mode + ":" + level;
                }
                case Rule.A_SOUND: {
                    final AudioManager audio = context.getSystemService(AudioManager.class);
                    final int was = audio.getRingerModeInternal();
                    audio.setRingerModeInternal(Integer.parseInt(action.value));
                    return Integer.toString(was);
                }
                case Rule.A_FORCE_STOP: {
                    final ActivityManager manager = context.getSystemService(
                            ActivityManager.class);
                    for (String pkg : packages(action.value)) {
                        manager.forceStopPackageAsUser(pkg, ActivityManager.getCurrentUser());
                    }
                    return null;
                }
                case Rule.A_RESTRICT_BG: {
                    final AppOpsManager ops = context.getSystemService(AppOpsManager.class);
                    final PackageManager packages = context.getPackageManager();
                    final StringBuilder was = new StringBuilder();
                    for (String pkg : packages(action.value)) {
                        try {
                            final int uid = packages.getPackageUid(pkg, 0);
                            if (was.length() > 0) was.append(',');
                            was.append(pkg).append(':').append(
                                    ops.unsafeCheckOpRaw(OP_RUN_ANY_IN_BACKGROUND, uid, pkg));
                            ops.setMode(OP_RUN_ANY_IN_BACKGROUND, uid, pkg,
                                    AppOpsManager.MODE_IGNORED);
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.w(TAG, "App not installed: " + pkg);
                        }
                    }
                    return was.length() == 0 ? null : was.toString();
                }
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Action " + action.type + " failed", e);
            return null;
        }
    }

    static void revert(Context context, Rule.Action action, String was) {
        if (was == null) return;
        final ContentResolver resolver = context.getContentResolver();
        try {
            switch (action.type) {
                case Rule.A_WIFI:
                    context.getSystemService(WifiManager.class).setWifiEnabled("1".equals(was));
                    break;
                case Rule.A_BLUETOOTH: {
                    final BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
                    if (bluetooth != null) {
                        if ("1".equals(was)) bluetooth.enable(); else bluetooth.disable();
                    }
                    break;
                }
                case Rule.A_LOCATION:
                    context.getSystemService(LocationManager.class).setLocationEnabledForUser(
                            "1".equals(was), UserHandle.of(ActivityManager.getCurrentUser()));
                    break;
                case Rule.A_SYNC:
                    ContentResolver.setMasterSyncAutomatically("1".equals(was));
                    break;
                case Rule.A_BATTERY_SAVER:
                    context.getSystemService(PowerManager.class).setPowerSaveModeEnabled(
                            "1".equals(was));
                    break;
                case Rule.A_SMART_PIXELS:
                    PartsPrefs.get(context).edit().putBoolean(PartsPrefs.SMART_PIXELS,
                            "1".equals(was)).apply();
                    break;
                case Rule.A_REFRESH:
                    Settings.System.putString(resolver, SETTING_PEAK_REFRESH,
                            was.isEmpty() ? null : was);
                    break;
                case Rule.A_SCREEN_MODE:
                    Settings.Global.putString(resolver, SETTING_DUAL_MODE, was);
                    break;
                case Rule.A_TIMEOUT:
                    Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT,
                            Integer.parseInt(was));
                    break;
                case Rule.A_DARK_MODE:
                    context.getSystemService(UiModeManager.class).setNightMode(
                            Integer.parseInt(was));
                    break;
                case Rule.A_ROTATION:
                    Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION,
                            Integer.parseInt(was));
                    break;
                case Rule.A_BRIGHTNESS: {
                    final String[] parts = was.split(":");
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                            Integer.parseInt(parts[1]));
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Integer.parseInt(parts[0]));
                    break;
                }
                case Rule.A_SOUND:
                    context.getSystemService(AudioManager.class).setRingerModeInternal(
                            Integer.parseInt(was));
                    break;
                case Rule.A_RESTRICT_BG: {
                    final AppOpsManager ops = context.getSystemService(AppOpsManager.class);
                    final PackageManager packages = context.getPackageManager();
                    for (String entry : was.split(",")) {
                        final int colon = entry.lastIndexOf(':');
                        if (colon < 0) continue;
                        final String pkg = entry.substring(0, colon);
                        try {
                            ops.setMode(OP_RUN_ANY_IN_BACKGROUND, packages.getPackageUid(pkg, 0),
                                    pkg, Integer.parseInt(entry.substring(colon + 1)));
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.w(TAG, "App not installed: " + pkg);
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Undo of " + action.type + " failed", e);
        }
    }

    private static String[] packages(String list) {
        return list.isEmpty() ? new String[0] : list.split(",");
    }
}
