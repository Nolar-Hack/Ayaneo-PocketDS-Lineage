/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A controller setup for each app: swapped face buttons, dead zone of the sticks and button
 * remapping, switched on when the app comes to the front. What was set for every app comes back
 * when another app is used. The input reader reads the same system properties as the Controller
 * page of the overlay (frameworks/native patches 0003 and 0004).
 */
final class ControllerProfiles implements ForegroundWatcher.Listener {
    private static final String KEY_LIST = "pad_profiles";
    private static final String KEY_NEXT = "pad_next_id";
    private static final String KEY_APP = "pad_app_";
    private static final String KEY_RESTORE = "pad_restore";

    static final String PROP_AB = "persist.sys.pocketds.swap_ab";
    static final String PROP_XY = "persist.sys.pocketds.swap_xy";
    static final String PROP_ZONE = "persist.sys.pocketds.deadzone";
    static final String PROP_MAP = "persist.sys.pocketds.keymap";

    /** What a button can be made to send (names and Android key codes), "Nothing" disables it. */
    static final String[] TARGET_NAMES = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3",
        "Start", "Select", "Mode", "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Back",
        "Home", "Volume up", "Volume down", "Nothing"};
    static final int[] TARGET_CODES = {96, 97, 99, 100, 102, 103, 104, 105, 106, 107, 108, 109,
        110, 19, 20, 21, 22, 4, 3, 24, 25, 0};

    /** "scancode:keycode,..." as read by the input reader. */
    static android.util.SparseIntArray parseMap(String text) {
        final android.util.SparseIntArray map = new android.util.SparseIntArray();
        if (text == null) return map;
        for (String pair : text.split(",")) {
            final String[] parts = pair.split(":");
            if (parts.length != 2) continue;
            try {
                map.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                // skipped
            }
        }
        return map;
    }

    static String encodeMap(android.util.SparseIntArray map) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < map.size(); i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(map.keyAt(i)).append(':').append(map.valueAt(i));
        }
        return sb.toString();
    }

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
        sInstance = this;
    }

    void start() {
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        if (watcher == null) return;
        watcher.add(this);
        // After a restart the properties may still hold the setup of the last game.
        onForeground(watcher.current());
    }

    /** A setup the user named ("Switch layout"), chosen for one or more apps. */
    static final class Named {
        String id;
        String name;
        Profile profile = new Profile();
    }

    private static ControllerProfiles sInstance;

    static List<Named> all(SharedPreferences prefs) {
        final List<Named> out = new ArrayList<>();
        try {
            final JSONArray list = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < list.length(); i++) {
                final JSONObject o = list.getJSONObject(i);
                final Named n = new Named();
                n.id = o.getString("id");
                n.name = o.getString("name");
                n.profile = Profile.parse(o.optString("data", ""));
                out.add(n);
            }
        } catch (JSONException e) {
            // an unreadable list is empty
        }
        return out;
    }

    private static void write(SharedPreferences prefs, List<Named> list) {
        final JSONArray array = new JSONArray();
        try {
            for (Named n : list) {
                array.put(new JSONObject().put("id", n.id).put("name", n.name)
                        .put("data", n.profile.encode()));
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply();
    }

    static Named find(SharedPreferences prefs, String id) {
        if (id == null) return null;
        for (Named n : all(prefs)) if (n.id.equals(id)) return n;
        return null;
    }

    static Named create(SharedPreferences prefs, String name) {
        final List<Named> list = all(prefs);
        final int next = prefs.getInt(KEY_NEXT, 1);
        prefs.edit().putInt(KEY_NEXT, next + 1).apply();
        final Named n = new Named();
        n.id = "p" + next;
        n.name = name.trim().isEmpty() ? "Profile " + next : name.trim();
        list.add(n);
        write(prefs, list);
        return n;
    }

    /** Saves a profile (name and setup) and applies it at once if its app is in front. */
    static void update(SharedPreferences prefs, Named n) {
        final List<Named> list = all(prefs);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(n.id)) list.set(i, n);
        }
        write(prefs, list);
        reapply();
    }

    static void delete(SharedPreferences prefs, String id) {
        final List<Named> list = all(prefs);
        list.removeIf(n -> n.id.equals(id));
        write(prefs, list);
        final SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_APP) && id.equals(prefs.getString(key, null))) {
                editor.remove(key);
            }
        }
        editor.apply();
        reapply();
    }

    static String assignedId(SharedPreferences prefs, String packageName) {
        return prefs.getString(KEY_APP + packageName, null);
    }

    static void assign(SharedPreferences prefs, String packageName, String id) {
        final SharedPreferences.Editor editor = prefs.edit();
        if (id == null) editor.remove(KEY_APP + packageName);
        else editor.putString(KEY_APP + packageName, id);
        editor.apply();
        reapply();
    }

    /** How many apps use a profile. */
    static int usedBy(SharedPreferences prefs, String id) {
        int count = 0;
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_APP) && id.equals(e.getValue())) count++;
        }
        return count;
    }

    /** The setup chosen for an app: empty when it has none. */
    static Profile forApp(SharedPreferences prefs, String packageName) {
        final Named n = find(prefs, assignedId(prefs, packageName));
        return n == null ? new Profile() : n.profile;
    }

    private static void reapply() {
        if (sInstance == null) return;
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        if (watcher != null) sInstance.onForeground(watcher.current());
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
                : forApp(mPrefs, app.baseActivity.getPackageName());
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
