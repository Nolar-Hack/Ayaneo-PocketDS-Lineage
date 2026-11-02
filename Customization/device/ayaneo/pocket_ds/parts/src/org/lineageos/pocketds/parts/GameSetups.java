/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game profiles: a named group of three settings (performance profile, controller profile, minimum
 * GPU speed) chosen for one or more apps. The settings are written to the per-app keys that the
 * rest of the system already reads (game_profile_, pad_app_, gpu_floor_), so changing a profile
 * changes every app that uses it and nothing else needs to know about game profiles.
 */
final class GameSetups {
    private static final String KEY_LIST = "setup_list";
    private static final String KEY_NEXT = "setup_next_id";
    private static final String KEY_APP = "setup_app_";
    private static final String KEY_MIGRATED = "setup_migrated";

    /** The three settings: perf is 0 to 4 or -1 for "no change"; pad is a controller profile id. */
    static final class Setup {
        String id;
        String name;
        int perf = -1;
        String pad;
        boolean gpu;
    }

    private GameSetups() {}

    static List<Setup> all(SharedPreferences prefs) {
        final List<Setup> out = new ArrayList<>();
        try {
            final JSONArray list = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < list.length(); i++) {
                final JSONObject o = list.getJSONObject(i);
                final Setup s = new Setup();
                s.id = o.getString("id");
                s.name = o.getString("name");
                s.perf = o.optInt("perf", -1);
                s.pad = o.isNull("pad") ? null : o.optString("pad", null);
                s.gpu = o.optBoolean("gpu", false);
                out.add(s);
            }
        } catch (JSONException e) {
            // an unreadable list is empty
        }
        return out;
    }

    private static void write(SharedPreferences prefs, List<Setup> list) {
        final JSONArray array = new JSONArray();
        try {
            for (Setup s : list) {
                array.put(new JSONObject().put("id", s.id).put("name", s.name)
                        .put("perf", s.perf).put("pad", s.pad == null ? JSONObject.NULL : s.pad)
                        .put("gpu", s.gpu));
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply();
    }

    static Setup find(SharedPreferences prefs, String id) {
        if (id == null) return null;
        for (Setup s : all(prefs)) if (s.id.equals(id)) return s;
        return null;
    }

    static Setup create(SharedPreferences prefs, String name) {
        final List<Setup> list = all(prefs);
        final int next = prefs.getInt(KEY_NEXT, 1);
        prefs.edit().putInt(KEY_NEXT, next + 1).apply();
        final Setup s = new Setup();
        s.id = "g" + next;
        s.name = name.trim().isEmpty() ? "Game profile " + next : name.trim();
        list.add(s);
        write(prefs, list);
        return s;
    }

    /** Saves a profile and gives its settings again to every app that uses it. */
    static void update(SharedPreferences prefs, Setup s) {
        final List<Setup> list = all(prefs);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) list.set(i, s);
        }
        write(prefs, list);
        for (String pkg : appsOf(prefs, s.id)) apply(prefs, pkg, s);
    }

    static void delete(SharedPreferences prefs, String id) {
        for (String pkg : appsOf(prefs, id)) assign(prefs, pkg, null);
        final List<Setup> list = all(prefs);
        list.removeIf(s -> s.id.equals(id));
        write(prefs, list);
    }

    static String assignedId(SharedPreferences prefs, String packageName) {
        return prefs.getString(KEY_APP + packageName, null);
    }

    /** Chooses the profile of an app, or none: the three settings of the app follow. */
    static void assign(SharedPreferences prefs, String packageName, String id) {
        final SharedPreferences.Editor editor = prefs.edit();
        if (id == null) editor.remove(KEY_APP + packageName);
        else editor.putString(KEY_APP + packageName, id);
        editor.apply();
        apply(prefs, packageName, find(prefs, id));
    }

    /** Writes the settings of a profile (or clears them when there is none) for one app. */
    private static void apply(SharedPreferences prefs, String pkg, Setup s) {
        final SharedPreferences.Editor editor = prefs.edit();
        if (s == null || s.perf < 0) editor.remove(GameProfiles.KEY_PREFIX + pkg);
        else editor.putString(GameProfiles.KEY_PREFIX + pkg, Integer.toString(s.perf));
        if (s != null && s.gpu) editor.putBoolean(GpuFloorController.KEY_PREFIX + pkg, true);
        else editor.remove(GpuFloorController.KEY_PREFIX + pkg);
        editor.apply();
        ControllerProfiles.assign(prefs, pkg, s == null ? null : s.pad);
    }

    static List<String> appsOf(SharedPreferences prefs, String id) {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_APP) && id.equals(e.getValue())) {
                out.add(e.getKey().substring(KEY_APP.length()));
            }
        }
        return out;
    }

    /** Apps that have a profile. */
    static List<String> assignedApps(SharedPreferences prefs) {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_APP) && e.getValue() instanceof String
                    && find(prefs, (String) e.getValue()) != null) {
                out.add(e.getKey().substring(KEY_APP.length()));
            }
        }
        return out;
    }

    /** "Gaming · Switch · GPU minimum": the three settings of a profile in a line. */
    static String summary(SharedPreferences prefs, Setup s) {
        final StringBuilder sb = new StringBuilder();
        sb.append(s.perf >= 0 && s.perf < PerformanceProfiles.NAMES.length
                ? PerformanceProfiles.NAMES[s.perf] : "Performance unchanged");
        final ControllerProfiles.Named pad = ControllerProfiles.find(prefs, s.pad);
        sb.append(" · ").append(pad == null ? "General controller" : pad.name);
        if (s.gpu) sb.append(" · GPU minimum");
        return sb.toString();
    }

    /**
     * Once: the apps that already had a performance profile, a controller profile or the minimum
     * GPU speed are grouped by identical settings into game profiles, so nothing is lost.
     */
    static void migrate(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return;
        final Map<String, int[]> perf = new LinkedHashMap<>();
        final Map<String, String> pads = new LinkedHashMap<>();
        final Map<String, Boolean> gpus = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            final String key = e.getKey();
            if (key.startsWith(GameProfiles.KEY_PREFIX)) {
                perf.put(key.substring(GameProfiles.KEY_PREFIX.length()),
                        new int[] {GameProfiles.profileOf(prefs,
                                key.substring(GameProfiles.KEY_PREFIX.length()))});
            } else if (key.startsWith("pad_app_") && e.getValue() instanceof String) {
                pads.put(key.substring("pad_app_".length()), (String) e.getValue());
            } else if (key.startsWith(GpuFloorController.KEY_PREFIX)
                    && Boolean.TRUE.equals(e.getValue())) {
                gpus.put(key.substring(GpuFloorController.KEY_PREFIX.length()), true);
            }
        }
        final java.util.Set<String> packages = new java.util.TreeSet<>();
        packages.addAll(perf.keySet());
        packages.addAll(pads.keySet());
        packages.addAll(gpus.keySet());
        final Map<String, Setup> bySettings = new LinkedHashMap<>();
        final SharedPreferences.Editor editor = prefs.edit();
        for (String pkg : packages) {
            final int p = perf.containsKey(pkg) ? perf.get(pkg)[0] : -1;
            final String pad = pads.get(pkg);
            final boolean gpu = gpus.containsKey(pkg);
            if (p < 0 && pad == null && !gpu) continue;
            final String settings = p + "|" + pad + "|" + gpu;
            Setup s = bySettings.get(settings);
            if (s == null) {
                final ControllerProfiles.Named named = ControllerProfiles.find(prefs, pad);
                final List<String> parts = new ArrayList<>();
                if (p >= 0 && p < PerformanceProfiles.NAMES.length) {
                    parts.add(PerformanceProfiles.NAMES[p]);
                }
                if (named != null) parts.add(named.name);
                if (gpu) parts.add("GPU minimum");
                s = create(prefs, String.join(" · ", parts));
                s.perf = p;
                s.pad = named == null ? null : named.id;
                s.gpu = gpu;
                final List<Setup> list = all(prefs);
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).id.equals(s.id)) list.set(i, s);
                }
                write(prefs, list);
                bySettings.put(settings, s);
            }
            editor.putString(KEY_APP + pkg, s.id);
        }
        editor.putBoolean(KEY_MIGRATED, true).apply();
    }
}
