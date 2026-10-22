/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;

/**
 * A controller setup for each app: swapped face buttons, dead zone of the sticks and button
 * remapping, switched on when the app comes to the front. What was set for every app comes back
 * when another app is used. The input reader reads the same system properties as the Controller
 * page of the overlay (frameworks/native patches 0003 and 0004).
 */
final class ControllerProfiles implements ForegroundWatcher.Listener {
    static final String KEY_PREFIX = "pad_profile_";
    private static final String KEY_RESTORE = "pad_restore";

    static final String PROP_AB = "persist.sys.pocketds.swap_ab";
    static final String PROP_XY = "persist.sys.pocketds.swap_xy";
    static final String PROP_ZONE = "persist.sys.pocketds.deadzone";
    static final String PROP_MAP = "persist.sys.pocketds.keymap";

    /** What an app changes; a field left at -1 (null for the remap) keeps the general setting. */
    static final class Profile {
        int swapAb = -1;
        int swapXy = -1;
        int zone = -1;
        String map;

        boolean empty() {
            return swapAb < 0 && swapXy < 0 && zone < 0 && map == null;
        }

        String encode() {
            final StringBuilder sb = new StringBuilder();
            if (swapAb >= 0) sb.append("ab=").append(swapAb).append(';');
            if (swapXy >= 0) sb.append("xy=").append(swapXy).append(';');
            if (zone >= 0) sb.append("dz=").append(zone).append(';');
            if (map != null) sb.append("map=").append(map).append(';');
            return sb.toString();
        }

        static Profile parse(String text) {
            final Profile p = new Profile();
            if (text == null) return p;
            for (String field : text.split(";")) {
                final int eq = field.indexOf('=');
                if (eq < 0) continue;
                final String value = field.substring(eq + 1);
                try {
                    switch (field.substring(0, eq)) {
                        case "ab": p.swapAb = Integer.parseInt(value); break;
                        case "xy": p.swapXy = Integer.parseInt(value); break;
                        case "dz": p.zone = Integer.parseInt(value); break;
                        case "map": p.map = value; break;
                        default: break;
                    }
                } catch (NumberFormatException e) {
                    // the field is ignored
                }
            }
            return p;
        }

        /** Short text for the list of games. */
        String summary() {
            if (empty()) return "Same as everywhere";
            final StringBuilder sb = new StringBuilder();
            if (swapAb == 1) add(sb, "A↔B");
            if (swapAb == 0) add(sb, "A/B normal");
            if (swapXy == 1) add(sb, "X↔Y");
            if (swapXy == 0) add(sb, "X/Y normal");
            if (zone >= 0) add(sb, zone == 0 ? "no dead zone" : "dead zone " + zone + " %");
            if (map != null) {
                final int n = map.isEmpty() ? 0 : map.split(",").length;
                add(sb, n == 0 ? "no remap" : n + (n == 1 ? " remap" : " remaps"));
            }
            return sb.toString();
        }

        private static void add(StringBuilder sb, String part) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(part);
        }
    }

    private final SharedPreferences mPrefs;

    ControllerProfiles(Context context) {
        mPrefs = PartsPrefs.get(context);
    }

    void start() {
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        if (watcher == null) return;
        watcher.add(this);
        // After a restart the properties may still hold the setup of the last game.
        onForeground(watcher.current());
    }

    static Profile of(SharedPreferences prefs, String packageName) {
        return Profile.parse(prefs.getString(KEY_PREFIX + packageName, null));
    }

    static void save(SharedPreferences prefs, String packageName, Profile profile) {
        final SharedPreferences.Editor editor = prefs.edit();
        if (profile.empty()) editor.remove(KEY_PREFIX + packageName);
        else editor.putString(KEY_PREFIX + packageName, profile.encode());
        editor.apply();
    }

    /** The setup in use now, as the general one when no profile is on. */
    static Profile currentSetup() {
        final Profile p = new Profile();
        p.swapAb = SystemProperties.getBoolean(PROP_AB, false) ? 1 : 0;
        p.swapXy = SystemProperties.getBoolean(PROP_XY, false) ? 1 : 0;
        p.zone = Math.max(0, SystemProperties.getInt(PROP_ZONE, 0));
        p.map = SystemProperties.get(PROP_MAP, "");
        return p;
    }

    private static void write(Profile p) {
        SystemProperties.set(PROP_AB, p.swapAb == 1 ? "true" : "false");
        SystemProperties.set(PROP_XY, p.swapXy == 1 ? "true" : "false");
        SystemProperties.set(PROP_ZONE, Integer.toString(Math.max(0, p.zone)));
        SystemProperties.set(PROP_MAP, p.map == null ? "" : p.map);
    }

    @Override
    public void onForeground(ActivityManager.RunningTaskInfo app) {
        final Profile wanted = app == null || app.baseActivity == null ? new Profile()
                : of(mPrefs, app.baseActivity.getPackageName());
        final String saved = mPrefs.getString(KEY_RESTORE, null);
        if (wanted.empty()) {
            if (saved != null) {
                write(Profile.parse(saved));
                mPrefs.edit().remove(KEY_RESTORE).apply();
            }
            return;
        }
        // Keep the general setup once, then put the app's fields over it.
        final Profile general = saved != null ? Profile.parse(saved) : currentSetup();
        if (saved == null) mPrefs.edit().putString(KEY_RESTORE, general.encode()).apply();
        final Profile effective = new Profile();
        effective.swapAb = wanted.swapAb >= 0 ? wanted.swapAb : general.swapAb;
        effective.swapXy = wanted.swapXy >= 0 ? wanted.swapXy : general.swapXy;
        effective.zone = wanted.zone >= 0 ? wanted.zone : general.zone;
        effective.map = wanted.map != null ? wanted.map : general.map;
        write(effective);
    }
}
