/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The five performance profiles (ECO, Balanced, Streaming, Gaming, Max): a maximum frequency for
 * each CPU core, a window for the GPU, a fan mode. The numbers are the ones of the profile file
 * (assets/performance_profiles.json, same format as the AYA one, tuned by the user); a copy in the
 * files of the app, if any, wins.
 *
 * Applying a profile writes the files of the kernel: the maximum frequency of the cores
 * (scaling_max_freq, the governor stays walt, see the user's CPU policy) and the kgsl clocks of the
 * GPU. The fan follows its mode with {@link FanController}.
 */
final class PerformanceProfiles {
    private static final String TAG = "PocketDSPerf";
    static final String[] NAMES = {"ECO", "Balanced", "Streaming", "Gaming", "Max"};
    static final String FILE = "performance_profiles.json";

    static final String FAN_OFF = "FAN_MODE_OFF";
    static final String FAN_MUTE = "FAN_MODE_MUTE";
    static final String FAN_BALANCE = "FAN_MODE_BALANCE";
    static final String FAN_TURBO = "FAN_MODE_TURBO";

    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/";

    static final class Profile {
        final int[] cpuKhz = new int[8];
        String scheduler = "BALANCED";
        String fan = FAN_MUTE;
        long gpuMinHz = 124_800_000L;
        long gpuMaxHz = 124_800_000L;
        boolean gpuFixed;
    }

    /** Counts the profiles applied: the GPU floor then is the one of the profile again. */
    static volatile int sApplied;

    private final Context mContext;
    private final Profile[] mProfiles = new Profile[NAMES.length];

    PerformanceProfiles(Context context) {
        mContext = context;
        load();
    }

    Profile get(int mode) {
        return mProfiles[Math.max(0, Math.min(NAMES.length - 1, mode))];
    }

    int current() {
        return PartsPrefs.getInt(PartsPrefs.get(mContext), PartsPrefs.PERF_PROFILE, 1);
    }

    /** Switches to a profile and applies it. */
    void select(int mode) {
        PartsPrefs.get(mContext).edit().putString(PartsPrefs.PERF_PROFILE,
                Integer.toString(mode)).apply();
        apply(mode);
    }

    /** Writes the frequencies of a profile (CPU: allowed to the app, GPU: root). */
    void apply(int mode) {
        sApplied++;
        final Profile profile = get(mode);
        final StringBuilder gpu = new StringBuilder();
        for (int cpu = 0; cpu < profile.cpuKhz.length; cpu++) {
            final String file = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_max_freq";
            if (!write(file, Integer.toString(profile.cpuKhz[cpu]))) {
                RootShell.run("echo " + profile.cpuKhz[cpu] + " > " + file);
            }
        }
        // Same order as the AYA code: idle timer, ceiling, then the devfreq window.
        gpu.append("echo ").append(profile.gpuFixed ? 10_000_000 : 80).append(" > ")
                .append(GPU).append("idle_timer\n");
        gpu.append("echo ").append(profile.gpuMaxHz).append(" > ").append(GPU).append("max_gpuclk\n");
        gpu.append("echo ").append(profile.gpuMaxHz).append(" > ").append(GPU)
                .append("devfreq/max_freq\n");
        // The floor of the profile (see GpuFloorController) is raised only for the apps that ask
        // for it: here the GPU may fall to its lowest frequency, as it always did.
        final long[] gpuSteps = gpuFrequencies();
        gpu.append("echo ").append(profile.gpuFixed ? profile.gpuMaxHz
                : gpuSteps.length == 0 ? profile.gpuMinHz : gpuSteps[0])
                .append(" > ").append(GPU).append("devfreq/min_freq");
        RootShell.run(gpu.toString().split("\n"));
        Log.i(TAG, "Profile " + NAMES[Math.max(0, Math.min(NAMES.length - 1, mode))] + " applied");
    }

    private static boolean write(String path, String value) {
        try (java.io.FileWriter writer = new java.io.FileWriter(path)) {
            writer.write(value);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- editing (the overlay lets the user change each profile)

    /** The cores that share a clock: little (0-2), big (3-6) and the prime one (7). */
    static final int[][] CLUSTERS = {{0, 1, 2}, {3, 4, 5, 6}, {7}};

    /** The frequencies (kHz) a core can run at, lowest first; empty when unreadable. */
    static int[] cpuFrequencies(int cpu) {
        final String text = readText("/sys/devices/system/cpu/cpu" + cpu
                + "/cpufreq/scaling_available_frequencies");
        if (text == null) return new int[0];
        final String[] parts = text.trim().split("\\s+");
        final int[] out = new int[parts.length];
        int n = 0;
        for (String part : parts) {
            try {
                out[n++] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                // skipped
            }
        }
        final int[] sorted = java.util.Arrays.copyOf(out, n);
        java.util.Arrays.sort(sorted);
        return sorted;
    }

    /** The frequencies (Hz) of the GPU, lowest first; empty when unreadable. */
    static long[] gpuFrequencies() {
        final String text = readText(GPU + "gpu_available_frequencies");
        if (text == null) return new long[0];
        final String[] parts = text.trim().split("\\s+");
        final long[] out = new long[parts.length];
        int n = 0;
        for (String part : parts) {
            try {
                out[n++] = Long.parseLong(part);
            } catch (NumberFormatException e) {
                // skipped
            }
        }
        final long[] sorted = java.util.Arrays.copyOf(out, n);
        java.util.Arrays.sort(sorted);
        return sorted;
    }

    private static String readText(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(new File(path).toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Sets the maximum frequency of a cluster in a profile (kHz). */
    void setCluster(int mode, int cluster, int khz) {
        for (int cpu : CLUSTERS[cluster]) get(mode).cpuKhz[cpu] = khz;
    }

    void setGpuMax(int mode, long hz) {
        final Profile profile = get(mode);
        profile.gpuMaxHz = hz;
        if (profile.gpuMinHz > hz || profile.gpuFixed) profile.gpuMinHz = hz;
    }

    /** Writes the profiles to the file of the app (the built-in ones stay as they are). */
    boolean save() {
        try {
            final JSONObject root = new JSONObject(currentJson());
            final JSONObject modes = root.getJSONObject("modeConfigurations");
            for (int i = 0; i < mProfiles.length; i++) {
                final JSONObject mode = modes.getJSONObject(Integer.toString(i));
                final JSONArray cpus = mode.getJSONArray("cpuFrequencies");
                for (int k = 0; k < cpus.length(); k++) {
                    final JSONObject cpu = cpus.getJSONObject(k);
                    final int id = cpu.getInt("cpuId");
                    if (id >= 0 && id < mProfiles[i].cpuKhz.length) {
                        cpu.put("selectedFrequency", mProfiles[i].cpuKhz[id]);
                    }
                }
                mode.put("fanMode", mProfiles[i].fan);
                final JSONObject gpu = mode.getJSONObject("gpuFrequency");
                gpu.put("maxFrequency", mProfiles[i].gpuMaxHz);
                gpu.put("minFrequency", mProfiles[i].gpuMinHz);
            }
            java.nio.file.Files.write(new File(mContext.getFilesDir(), FILE).toPath(),
                    root.toString(2).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (JSONException | IOException | RuntimeException e) {
            Log.e(TAG, "Profiles not saved", e);
            return false;
        }
    }

    /** Back to the built-in profiles. */
    void reset() {
        new File(mContext.getFilesDir(), FILE).delete();
        load();
    }

    private String currentJson() throws IOException {
        final File own = new File(mContext.getFilesDir(), FILE);
        try (InputStream in = own.exists() ? new FileInputStream(own)
                : mContext.getAssets().open(FILE)) {
            return read(in);
        }
    }

    // ---- profile file

    private void load() {
        String json = null;
        final File own = new File(mContext.getFilesDir(), FILE);
        try {
            if (own.exists()) {
                try (InputStream in = new FileInputStream(own)) {
                    json = read(in);
                }
            } else {
                try (InputStream in = mContext.getAssets().open(FILE)) {
                    json = read(in);
                }
            }
            final JSONObject modes = new JSONObject(json).getJSONObject("modeConfigurations");
            for (int i = 0; i < mProfiles.length; i++) {
                mProfiles[i] = parse(modes.getJSONObject(Integer.toString(i)));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.e(TAG, "Profile file not readable, built-in values used", e);
            for (int i = 0; i < mProfiles.length; i++) mProfiles[i] = new Profile();
        }
    }

    private static Profile parse(JSONObject mode) throws JSONException {
        final Profile profile = new Profile();
        final JSONArray cpus = mode.getJSONArray("cpuFrequencies");
        for (int i = 0; i < cpus.length(); i++) {
            final JSONObject cpu = cpus.getJSONObject(i);
            final int id = cpu.getInt("cpuId");
            if (id >= 0 && id < profile.cpuKhz.length) {
                profile.cpuKhz[id] = cpu.getInt("selectedFrequency");
            }
        }
        profile.scheduler = mode.optString("cpuSchedulerMode", profile.scheduler);
        profile.fan = mode.optString("fanMode", profile.fan);
        final JSONObject gpu = mode.getJSONObject("gpuFrequency");
        profile.gpuFixed = gpu.optBoolean("isFixed", false);
        profile.gpuMaxHz = gpu.getLong("maxFrequency");
        profile.gpuMinHz = gpu.getLong("minFrequency");
        return profile;
    }

    private static String read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- refresh rate of the main screen

    /** "auto" or a number of hertz; the system settings drive the main (OLED) panel. */
    static void setTopRefresh(Context context, String hz) {
        final android.content.ContentResolver resolver = context.getContentResolver();
        if ("auto".equals(hz)) {
            Settings.System.putString(resolver, "min_refresh_rate", "60");
            Settings.System.putString(resolver, "peak_refresh_rate", "165");
        } else {
            Settings.System.putString(resolver, "min_refresh_rate", hz);
            Settings.System.putString(resolver, "peak_refresh_rate", hz);
        }
    }

    /** The current choice for the main screen: "auto" or the number of hertz. */
    static String topRefresh(Context context) {
        final android.content.ContentResolver resolver = context.getContentResolver();
        final String min = Settings.System.getString(resolver, "min_refresh_rate");
        final String peak = Settings.System.getString(resolver, "peak_refresh_rate");
        if (peak == null || peak.isEmpty() || !peak.equals(min)) return "auto";
        return peak.contains(".") ? peak.substring(0, peak.indexOf('.')) : peak;
    }
}
