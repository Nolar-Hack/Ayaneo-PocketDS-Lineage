/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * The GPU floor of a profile, for the apps that ask for it (Profiles: "GPU floor"). The GPU
 * governor of the console climbs too late for a game with bursts of load (a custom driver such as
 * Turnip never makes it climb). For an app that asked, the floor of the selected profile is raised
 * as soon as the app is in front and kept while the GPU works; after a few idle seconds (a menu, a
 * pause, the home) it falls to the lowest frequency again, and comes back with the load. Any other
 * app, and any profile without a floor, runs as it always did.
 */
final class GpuFloorController {
    static final String KEY_PREFIX = "gpu_floor_";
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/";
    private static final long POLL_MS = 1000;
    /** The GPU is working above this share of busy time. */
    private static final int BUSY_PERCENT = 15;
    /** Idle polls in a row before the floor is lowered. */
    private static final int IDLE_POLLS = 5;

    private final PerformanceProfiles mProfiles;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mIdle;
    private boolean mHigh;
    private int mProfile = -1;
    private int mApplied = -1;

    private final android.content.Context mContext;
    private boolean mWanted;

    GpuFloorController(android.content.Context context, PerformanceProfiles profiles) {
        mContext = context;
        mProfiles = profiles;
    }

    /** True when the app has asked for the GPU floor. */
    static boolean isOn(android.content.SharedPreferences prefs, String packageName) {
        return prefs.getBoolean(KEY_PREFIX + packageName, false);
    }

    void start() {
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
            } finally {
                mHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void poll() {
        final int mode = mProfiles.current();
        final PerformanceProfiles.Profile profile = mProfiles.get(mode);
        // A profile applied (switched, or saved with another floor): the GPU is at its lowest
        // frequency again, see PerformanceProfiles#apply.
        if (mode != mProfile || mApplied != PerformanceProfiles.sApplied) {
            mProfile = mode;
            mApplied = PerformanceProfiles.sApplied;
            mHigh = false;
            mWanted = false;
        }
        final long lowest = lowestHz();
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        final android.app.ActivityManager.RunningTaskInfo app = watcher == null ? null
                : watcher.current();
        final boolean wanted = app != null && !profile.gpuFixed && profile.gpuMinHz > lowest
                && isOn(PartsPrefs.get(mContext), app.baseActivity.getPackageName());
        if (!wanted) {
            if (mHigh) {
                setFloor(lowest);
                mHigh = false;
            }
            mWanted = false;
            return;
        }
        if (!mWanted) {
            // the app just came to the front: the floor is there from the first frame
            mWanted = true;
            mIdle = 0;
            if (!mHigh) {
                setFloor(profile.gpuMinHz);
                mHigh = true;
            }
            return;
        }
        final int busy = busyPercent();
        if (busy < 0) return;
        if (busy >= BUSY_PERCENT) {
            mIdle = 0;
            if (!mHigh) {
                setFloor(profile.gpuMinHz);
                mHigh = true;
            }
        } else if (mHigh && ++mIdle >= IDLE_POLLS) {
            setFloor(lowest);
            mHigh = false;
        }
    }

    private static void setFloor(long hz) {
        RootShell.run("echo " + hz + " > " + GPU + "devfreq/min_freq");
    }

    private static long lowestHz() {
        final long[] frequencies = PerformanceProfiles.gpuFrequencies();
        return frequencies.length == 0 ? 124_800_000L : frequencies[0];
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
}
