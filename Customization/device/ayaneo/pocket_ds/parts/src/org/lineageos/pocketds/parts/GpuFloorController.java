/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * The GPU floor, for the apps that ask for it (Profiles: "GPU floor"). The GPU governor of the
 * console climbs too late for a game with bursts of load (a custom driver such as Turnip never
 * makes it climb), so for an app that asked the minimum frequency of the GPU starts at the floor
 * that comes with the selected performance profile (85 % of its maximum) as soon as the app is in
 * front. Then the use of the GPU, measured at the frequency that is held, keeps it fair: with a
 * light use the minimum goes down one step at a time (an idle menu, a pause), with a heavy use it
 * goes back up. The load is not trusted at the lowest frequency, where the governor hides it, only
 * at a floor that is held; the minimum never goes above the maximum of the profile, and it is
 * lifted when the app leaves the front. Any other app, and any profile without a floor, runs as it
 * always did.
 */
final class GpuFloorController {
    static final String KEY_PREFIX = "gpu_floor_";
    private static final String TAG = "PocketDSGpuFloor";
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/";
    private static final long POLL_MS = 500;
    /** Polls averaged for the use of the GPU (3 seconds). */
    private static final int WINDOW = 6;
    /** Under this use (percent of the time busy) the minimum may go down. */
    private static final int LOW_USE = 20;
    /** From this use on the minimum goes up. */
    private static final int HIGH_USE = 50;
    /** From this use on it goes up by two steps. */
    private static final int VERY_HIGH_USE = 80;
    /** Polls of light use in a row before one step down (10 seconds). */
    private static final int DOWN_POLLS = 20;
    /** Polls of heavy use in a row before one step up (1 second). */
    private static final int UP_POLLS = 2;

    private final PerformanceProfiles mProfiles;
    private final android.content.Context mContext;
    private final HandlerThread mThread = new HandlerThread("PocketDSGpuFloor");
    private Handler mHandler;

    private int mProfile = -1;
    private int mApplied = -1;
    private String mPackage;
    private long[] mSteps = new long[0];
    private int mLevel = -1;            // index in mSteps of the minimum set, -1 = none yet
    private int mLight;
    private int mHeavy;
    private final int[] mUse = new int[WINDOW];
    private int mUseCount;

    GpuFloorController(android.content.Context context, PerformanceProfiles profiles) {
        mContext = context;
        mProfiles = profiles;
    }

    /** True when the app has asked for the GPU floor. */
    static boolean isOn(android.content.SharedPreferences prefs, String packageName) {
        return prefs.getBoolean(KEY_PREFIX + packageName, false);
    }

    void start() {
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
            } catch (RuntimeException e) {
                android.util.Log.w(TAG, "poll failed", e);
            } finally {
                mHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void poll() {
        final int mode = mProfiles.current();
        final PerformanceProfiles.Profile profile = mProfiles.get(mode);
        // A profile applied (switched, or saved again): the minimum was put back to the lowest
        // frequency, see PerformanceProfiles#apply.
        if (mode != mProfile || mApplied != PerformanceProfiles.sApplied) {
            mProfile = mode;
            mApplied = PerformanceProfiles.sApplied;
            reset();
        }
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        final android.app.ActivityManager.RunningTaskInfo app = watcher == null ? null
                : watcher.current();
        final String pkg = app == null ? null : app.baseActivity.getPackageName();
        final long[] steps = stepsOf(profile);
        final boolean wanted = pkg != null && !profile.gpuFixed && steps.length > 1
                && isOn(PartsPrefs.get(mContext), pkg);
        if (!wanted) {
            if (mLevel >= 0) set(lowestHz(), -1);
            reset();
            return;
        }
        if (!pkg.equals(mPackage) || steps.length != mSteps.length) {
            reset();
            mPackage = pkg;
            mSteps = steps;
        }
        if (mLevel < 0) {
            // the app just came to the front: the floor of the profile is there from the first
            // frame
            mLevel = indexOf(floorOf(profile));
            set(mSteps[mLevel], -1);
            return;
        }
        // Put back if the real minimum is not the one set (the system changed it).
        if (minFreqHz() != mSteps[mLevel]) set(mSteps[mLevel], -1);

        final int busy = busyPercent();
        if (busy < 0) return;
        mUse[mUseCount++ % WINDOW] = busy;
        if (mUseCount < WINDOW) return;
        int sum = 0;
        for (int sample : mUse) sum += sample;
        final int use = sum / WINDOW;
        final int top = mSteps.length - 1;
        if (use >= HIGH_USE) {
            mLight = 0;
            if (++mHeavy >= UP_POLLS && mLevel < top) {
                mHeavy = 0;
                mLevel = Math.min(top, mLevel + (use >= VERY_HIGH_USE ? 2 : 1));
                set(mSteps[mLevel], use);
            }
        } else if (use < LOW_USE) {
            mHeavy = 0;
            if (++mLight >= DOWN_POLLS && mLevel > 0) {
                mLight = 0;
                mLevel--;
                set(mSteps[mLevel], use);
            }
        } else {
            mLight = 0;
            mHeavy = 0;
        }
    }

    private void reset() {
        mPackage = null;
        mLevel = -1;
        mLight = 0;
        mHeavy = 0;
        mUseCount = 0;
        mSteps = new long[0];
    }

    /** The steps of the GPU from the lowest to the maximum of the profile. */
    private static long[] stepsOf(PerformanceProfiles.Profile profile) {
        final long[] all = PerformanceProfiles.gpuFrequencies();
        int count = 0;
        for (long hz : all) if (hz <= profile.gpuMaxHz) count++;
        final long[] steps = new long[count];
        int i = 0;
        for (long hz : all) if (hz <= profile.gpuMaxHz) steps[i++] = hz;
        java.util.Arrays.sort(steps);
        return steps;
    }

    private int indexOf(long hz) {
        int best = 0;
        for (int i = 0; i < mSteps.length; i++) if (mSteps[i] <= hz) best = i;
        return best;
    }

    /**
     * The floor a profile starts from: the highest GPU step under 85 % of its maximum (680 MHz
     * for the 794 MHz of Gaming, the value that kept a game at 60 fps in the tests).
     */
    private long floorOf(PerformanceProfiles.Profile profile) {
        final long limit = profile.gpuMaxHz * 85 / 100;
        long best = mSteps.length == 0 ? lowestHz() : mSteps[0];
        for (long hz : mSteps) if (hz <= limit && hz > best) best = hz;
        return best;
    }

    private static void set(long hz, int use) {
        android.util.Log.i(TAG, "GPU minimum " + hz / 1_000_000 + " MHz"
                + (use < 0 ? "" : ", use " + use + " %"));
        RootShell.run("echo " + hz + " > " + GPU + "devfreq/min_freq");
    }

    private static long lowestHz() {
        final long[] frequencies = PerformanceProfiles.gpuFrequencies();
        return frequencies.length == 0 ? 124_800_000L : frequencies[0];
    }

    private static long minFreqHz() {
        return readLong(GPU + "devfreq/min_freq");
    }

    private static int busyPercent() {
        try {
            final String text = new String(java.nio.file.Files.readAllBytes(
                    new File(GPU + "gpu_busy_percentage").toPath()), StandardCharsets.UTF_8);
            return Integer.parseInt(text.replaceAll("[^0-9]", ""));
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }

    private static long readLong(String path) {
        try {
            return Long.parseLong(new String(java.nio.file.Files.readAllBytes(
                    new File(path).toPath()), StandardCharsets.UTF_8).trim());
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }
}
