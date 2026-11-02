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
 * The GPU floor, for the apps that ask for it (Profiles: "GPU floor"), driven by the frame rate.
 * The GPU governor of the console climbs too late for a game with bursts of load (a custom driver
 * such as Turnip never makes it climb), and the load of the GPU does not tell it (a game at 44
 * fps showed 35 % while a floor of 680 MHz made it run at 58). So the minimum frequency of the GPU
 * starts, for an app that asked, at the floor that comes with the selected performance profile
 * (85 % of its maximum) as soon as the app is in front. Then the frame rate decides: when it falls
 * under what the app reaches (the 75th percentile of the last 30 seconds) the minimum climbs one
 * step, two when it falls far; when it holds for a while the minimum goes down one step, never
 * under a step that was too low a minute ago. It never goes above the maximum of the profile.
 * When the app draws nothing for a while (a pause, a frozen game) the minimum is lifted, and it
 * starts again from the floor of the profile when the app draws again; it is lifted too when the
 * app leaves the front. Any other app, and any profile without a floor, runs as it always did.
 */
final class GpuFloorController {
    static final String KEY_PREFIX = "gpu_floor_";
    private static final String TAG = "PocketDSGpuFloor";
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/";
    private static final long POLL_MS = 1000;
    /** Seconds over which the frame rate the app reaches is taken. */
    private static final int PEAK_SECONDS = 30;
    /** Samples with a frame rate needed before the minimum is moved. */
    private static final int MIN_SAMPLES = 10;
    /** Below this share of the frame rate the app reaches, the minimum climbs. */
    private static final float LOW = 0.93f;
    /** Below this one it climbs by two steps. */
    private static final float VERY_LOW = 0.80f;
    /** From this share on, the frame rate is as good as the one the app reaches. */
    private static final float GOOD = 0.97f;
    /**
     * The GPU is kept between these busy shares (%): above BUSY_HIGH it is a long part of every
     * frame (the frame waits on it, seen: 80 % busy at 295 MHz), so the floor climbs; under
     * BUSY_LOW, with the frame rate holding, it goes down. A game held back by the processor
     * (seen: the GPU 27 % busy while the frame rate fell) does not get its GPU pushed for nothing.
     */
    private static final long BUSY_HIGH = 50;
    private static final long BUSY_LOW = 25;
    private static final long BUSY_VERY_HIGH = 80;
    /** Polls in a row under LOW before the minimum climbs. */
    private static final int UP_POLLS = 1;   // climbing is urgent: the very first busy poll
    /** Seconds in a row with the GPU little busy (and the frame rate holding) before the minimum
     * goes down one step: a few quiet seconds (a menu, a dialogue) are not a reason to drop. */
    private static final int DOWN_AFTER = 30;
    /** Seconds during which a step that was too low is not tried again. */
    private static final int AVOID_SECONDS = 180;
    /** Polls without a frame before the minimum is lifted. */
    private static final int NO_FRAMES_POLLS = 30;

    private final PerformanceProfiles mProfiles;
    private final android.content.Context mContext;
    private final HandlerThread mThread = new HandlerThread("PocketDSGpuFloor");
    private Handler mHandler;

    private int mProfile = -1;
    private int mApplied = -1;
    private String mPackage;
    private long[] mSteps = new long[0];
    private int mLevel = -1;            // index in mSteps of the minimum set, -1 = none yet
    private int mAvoidLevel = -1;       // a step under it was too low
    private long mAvoidUntil;
    private int mGood;
    private int mBelow;
    private int mNoFrames;
    private boolean mDropped;
    private final int[] mHistory = new int[PEAK_SECONDS];
    private int mHistoryIndex;
    private int mLastFps;
    private int mLastPeak;

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
            if (mLevel >= 0) set(lowestHz());
            reset();
            return;
        }
        if (!pkg.equals(mPackage) || steps.length != mSteps.length) {
            reset();
            mPackage = pkg;
            mSteps = steps;
        }
        final long now = System.nanoTime() / 1_000_000_000L;
        if (mLevel < 0) {
            // the app just came to the front: the floor of the profile is there from the first
            // frame, the frame rate takes over from there
            mLevel = indexOf(floorOf(profile));
            set(mSteps[mLevel]);
            return;
        }
        // Put back if the real minimum is not the one set (the system changed it).
        if (minFreqHz() != mSteps[mLevel]) set(mSteps[mLevel]);

        final PerfOverlayController perf = PerfOverlayController.sInstance;
        final int fps = perf == null ? -1 : perf.foregroundFps();
        if (fps < 0) return;
        mHistory[mHistoryIndex++ % PEAK_SECONDS] = fps;
        if (fps == 0) {
            // not drawing (paused, a frozen game, a black screen): after a while no floor is
            // held, and it starts again from the profile floor when the app draws again
            if (++mNoFrames == NO_FRAMES_POLLS && mLevel > 0) {
                mLevel = 0;
                set(mSteps[0]);
                mDropped = true;
            }
            return;
        }
        mNoFrames = 0;
        if (mDropped) {
            mDropped = false;
            reset();
            return;
        }
        // The frame rate the app reaches: the 75th percentile of the seconds that had frames, so
        // that one spike (a loading screen, a menu at 120) does not become the target.
        final int[] drawn = new int[PEAK_SECONDS];
        int count = 0;
        for (int sample : mHistory) if (sample > 0) drawn[count++] = sample;
        if (count < MIN_SAMPLES) return;
        java.util.Arrays.sort(drawn, 0, count);
        final int peak = drawn[Math.min(count - 1, count * 3 / 4)];
        mLastFps = fps;
        mLastPeak = peak;
        final int top = mSteps.length - 1;
        final long busy = PerfOverlayController.readLong(GPU + "gpu_busy_percentage", 0);
        if (busy > BUSY_HIGH) {
            mGood = 0;
            if (++mBelow >= UP_POLLS) {
                mBelow = 0;
                // the step we came from was too low: not under it again for a while
                mAvoidLevel = mLevel;
                mAvoidUntil = now + AVOID_SECONDS;
                final boolean far = busy > BUSY_VERY_HIGH || fps < peak * VERY_LOW;
                // Up fast (a frame waiting on the GPU is a stutter now), down slowly.
                final int next = Math.min(top, mLevel + (far ? 3 : 2));
                if (next != mLevel) {
                    mLevel = next;
                    set(mSteps[mLevel]);
                }
            }
        } else if (busy < BUSY_LOW && fps >= peak * GOOD) {
            mBelow = 0;
            if (++mGood >= DOWN_AFTER) {
                mGood = 0;
                final int lower = mLevel - 1;
                if (lower >= 0 && !(now < mAvoidUntil && lower < mAvoidLevel)) {
                    mLevel = lower;
                    set(mSteps[mLevel]);
                }
            }
        } else {
            // in the band (or the frame rate not holding with the GPU not busy: the processor is
            // the limit): neither climbs nor counts towards going down
            mBelow = 0;
            mGood = 0;
        }
    }

    private void reset() {
        mPackage = null;
        mLevel = -1;
        mAvoidLevel = -1;
        mAvoidUntil = 0;
        mGood = 0;
        mBelow = 0;
        mNoFrames = 0;
        mDropped = false;
        mHistoryIndex = 0;
        java.util.Arrays.fill(mHistory, 0);
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
     * for the 794 MHz of Gaming, the value that kept a game at 58 fps in the tests).
     */
    private long floorOf(PerformanceProfiles.Profile profile) {
        final long limit = profile.gpuMaxHz * 85 / 100;
        long best = mSteps.length == 0 ? lowestHz() : mSteps[0];
        for (long hz : mSteps) if (hz <= limit && hz > best) best = hz;
        return best;
    }

    private void set(long hz) {
        android.util.Log.i(TAG, "GPU minimum " + hz / 1_000_000 + " MHz, fps " + mLastFps
                + " of " + mLastPeak);
        RootShell.run("echo " + hz + " > " + GPU + "devfreq/min_freq");
    }

    private static long lowestHz() {
        final long[] frequencies = PerformanceProfiles.gpuFrequencies();
        return frequencies.length == 0 ? 124_800_000L : frequencies[0];
    }

    private static long minFreqHz() {
        try {
            return Long.parseLong(new String(java.nio.file.Files.readAllBytes(
                    new File(GPU + "devfreq/min_freq").toPath()), StandardCharsets.UTF_8).trim());
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }
}
