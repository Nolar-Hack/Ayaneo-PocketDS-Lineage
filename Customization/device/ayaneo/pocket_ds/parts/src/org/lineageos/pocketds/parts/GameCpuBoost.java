/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;

/**
 * The lowest frequency of the little and big cores while a game is in front. The governors alone
 * leave the cores slow in a game whose threads wait on each other (35 fps where 60 is possible),
 * so the minimum starts at the top frequency and goes down one step at a time for as long as the
 * frame rate holds. When it falls the minimum climbs again and the step that was too low is not
 * tried for a while. When the game draws nothing (a menu, a pause) the minimum is given back.
 *
 * The frame rate is the one the overlay measures for the app in front. The minimum is a lease of
 * the performance service, renewed while it is wanted, so it lapses if this process dies.
 */
final class GameCpuBoost {
    private static final String TAG = "PocketDSGameCpu";
    private static final long POLL_MS = 1000;
    /** Steps from the top (0) down to 30 % of the cap of the profile, 5 % each. */
    private static final int LEVELS = 15;
    private static final int PEAK_SECONDS = 30;
    private static final int MIN_SAMPLES = 10;
    /** Under this share of the frame rate the game reaches, the minimum climbs. */
    private static final float LOW = 0.90f;
    private static final float VERY_LOW = 0.75f;
    /** From this share on the frame rate is as good as the game reaches. */
    private static final float GOOD = 0.96f;
    private static final int UP_POLLS = 2;
    private static final int DOWN_AFTER = 10;
    private static final int AVOID_SECONDS = 60;
    private static final int NO_FRAMES_POLLS = 15;
    private static final int OP_BIG_MIN = 0x40800000;
    private static final int OP_LITTLE_MIN = 0x40800100;

    private static HandlerThread sThread;
    private static Handler sHandler;
    // Everything below belongs to the thread above.
    private static boolean sActive;
    private static String sPackage;     // the game whose frame rate is followed
    private static int sLevel;          // 0 = top, LEVELS = no minimum held
    private static int sHandle;
    private static int sProfile = -1;
    private static int sApplied = -1;
    private static int sRef;
    private static int sGood;
    private static int sBelow;
    private static int sNoFrames;
    private static int sAvoidLevel = LEVELS;
    private static long sAvoidUntil;
    private static boolean sDropped;
    private static final int[] sHistory = new int[PEAK_SECONDS];
    private static int sHistoryIndex;

    private GameCpuBoost() {}

    private static android.content.SharedPreferences sPrefs;
    private static final String KEY_LEVEL = "game_cpu_level_";
    private static final String KEY_REF = "game_cpu_ref_";

    /** Where the level each game settled at is kept, to start near it next time. */
    static void init(android.content.Context context) {
        sPrefs = PartsPrefs.get(context);
    }

    private static synchronized Handler handler() {
        if (sHandler == null) {
            sThread = new HandlerThread("PocketDSGameCpu");
            sThread.start();
            sHandler = new Handler(sThread.getLooper());
        }
        return sHandler;
    }

    static void start(String pkg) {
        handler().post(() -> {
            if (sActive && pkg.equals(sPackage)) return;
            final boolean was = sActive;
            sActive = true;
            sPackage = pkg;
            if (was) sHandler.removeCallbacks(sPoll);
            reset();
            final PerformanceController engine = PerformanceController.get();
            if (engine != null) {
                sProfile = engine.profiles.current();
                sApplied = PerformanceProfiles.sApplied;
            }
            // Near the level this game settled at last time (one step above, to be safe), with
            // the frame rate it reached then; from the top for a game seen for the first time.
            int level = 0;
            if (sPrefs != null) {
                final int saved = sPrefs.getInt(KEY_LEVEL + pkg, 0);
                final int ref = sPrefs.getInt(KEY_REF + pkg, 0);
                if (saved > 0 && ref > 0) {
                    level = Math.min(LEVELS - 1, saved - 1);
                    sRef = ref;
                }
            }
            setLevel(level, 0, sRef);
            sHandler.postDelayed(sPoll, POLL_MS);
        });
    }

    static void stop() {
        handler().post(() -> {
            if (!sActive) return;
            sActive = false;
            sHandler.removeCallbacks(sPoll);
            PerfBoost.releaseLock(sHandle);
            sHandle = 0;
            Log.i(TAG, "Minimum frequencies given back");
        });
    }

    private static final Runnable sPoll = new Runnable() {
        @Override
        public void run() {
            if (!sActive) return;
            try {
                poll();
            } catch (RuntimeException e) {
                Log.w(TAG, "poll failed", e);
            } finally {
                if (sActive) sHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    private static void reset() {
        sLevel = 0;
        sRef = 0;
        sGood = 0;
        sBelow = 0;
        sNoFrames = 0;
        sAvoidLevel = LEVELS;
        sAvoidUntil = 0;
        sDropped = false;
        java.util.Arrays.fill(sHistory, 0);
        sHistoryIndex = 0;
    }

    /** Polls between two renewals of the lease of the lock (see PerfBoost#LEASE_MS). */
    private static final int RENEW_POLLS = 5;
    private static int sSinceRenew;

    private static void poll() {
        final PerformanceController engine = PerformanceController.get();
        if (engine == null) return;
        if (++sSinceRenew >= RENEW_POLLS && sLevel < LEVELS) {
            sSinceRenew = 0;
            lockLevel(sLevel);
        }
        final int mode = engine.profiles.current();
        // A profile applied or switched: the caps changed, the search starts again from the top.
        if (mode != sProfile || sApplied != PerformanceProfiles.sApplied) {
            sProfile = mode;
            sApplied = PerformanceProfiles.sApplied;
            reset();
            setLevel(0, 0, 0);
            return;
        }
        final PerfOverlayController perf = PerfOverlayController.sInstance;
        final int fps = perf == null || sPackage == null ? -1 : perf.fpsOf(sPackage);
        if (fps < 0) return;
        sHistory[sHistoryIndex++ % PEAK_SECONDS] = fps;
        if (fps == 0) {
            // Nothing drawn (menu, pause, black screen): the minimum is given back after a while
            // and starts again from the top when the game draws again.
            if (++sNoFrames == NO_FRAMES_POLLS && sLevel < LEVELS) {
                setLevel(LEVELS, 0, 0);
                sDropped = true;
            }
            return;
        }
        sNoFrames = 0;
        if (sDropped) {
            sDropped = false;
            reset();
            setLevel(0, 0, 0);
            return;
        }
        final int[] drawn = new int[PEAK_SECONDS];
        int count = 0;
        for (int sample : sHistory) if (sample > 0) drawn[count++] = sample;
        if (count < MIN_SAMPLES) return;
        java.util.Arrays.sort(drawn, 0, count);
        final int peak = drawn[Math.min(count - 1, count * 3 / 4)];
        // The frame rate the game reaches is taken at the top; lower down it is kept, and only
        // goes up when the game shows more.
        if (sLevel == 0 || peak > sRef || sRef == 0) sRef = peak;
        final long now = System.nanoTime() / 1_000_000_000L;
        if (fps < sRef * LOW) {
            sGood = 0;
            if (++sBelow >= UP_POLLS) {
                sBelow = 0;
                sAvoidLevel = sLevel;
                sAvoidUntil = now + AVOID_SECONDS;
                final int next = Math.max(0, sLevel - (fps < sRef * VERY_LOW ? 3 : 2));
                if (next != sLevel) setLevel(next, fps, sRef);
            }
        } else if (fps >= sRef * GOOD) {
            sBelow = 0;
            if (++sGood >= DOWN_AFTER) {
                sGood = 0;
                final int lower = sLevel + 1;
                if (lower < LEVELS && !(now < sAvoidUntil && lower >= sAvoidLevel)) {
                    setLevel(lower, fps, sRef);
                }
            }
        } else {
            sBelow = 0;
        }
    }

    /** The minimum (MHz) of a cluster at a level: a share of the cap of the profile. */
    private static int minMhz(int cpu, int capKhz, int level) {
        if (level == 0) return 0xFFF;
        final int[] table = PerformanceProfiles.cpuFrequencies(cpu);
        if (table.length == 0 || capKhz <= 0) return 0xFFF;
        final long target = (long) capKhz * (20 - level) / 20;
        int pick = capKhz;
        for (int khz : table) {
            if (khz >= target) {
                pick = khz;
                break;
            }
        }
        return Math.min(pick, capKhz) / 1000;
    }

    private static void setLevel(int level, int fps, int ref) {
        sLevel = level;
        sSinceRenew = 0;
        if (sPrefs != null && sPackage != null && level < LEVELS && ref > 0) {
            sPrefs.edit().putInt(KEY_LEVEL + sPackage, level).putInt(KEY_REF + sPackage, ref)
                    .apply();
        }
        final int[] mhz = lockLevel(level);
        if (mhz == null) {
            Log.i(TAG, "No minimum held, fps " + fps + " of " + ref);
        } else {
            Log.i(TAG, "Level " + level + ": big " + (mhz[0] == 0xFFF ? "top" : mhz[0] + " MHz")
                    + ", little " + (mhz[1] == 0xFFF ? "top" : mhz[1] + " MHz")
                    + ", fps " + fps + " of " + ref + " (lock " + sHandle + ")");
        }
    }

    /**
     * Takes a new lease for a level, then gives the old one back (the boost never lapses).
     * Returns the minimums asked for (big, little), or null when no minimum is held.
     */
    private static int[] lockLevel(int level) {
        final PerformanceController engine = PerformanceController.get();
        final int old = sHandle;
        sHandle = 0;
        int[] mhz = null;
        if (level < LEVELS && engine != null) {
            final PerformanceProfiles.Profile profile =
                    engine.profiles.get(engine.profiles.current());
            mhz = new int[] {minMhz(3, profile.cpuKhz[3], level),
                minMhz(0, profile.cpuKhz[0], level)};
            final ArrayList<Integer> pairs = new ArrayList<>();
            pairs.add(OP_BIG_MIN);
            pairs.add(mhz[0]);
            pairs.add(OP_LITTLE_MIN);
            pairs.add(mhz[1]);
            sHandle = Math.max(0, PerfBoost.lockPairs(pairs));
        }
        PerfBoost.releaseLock(old);
        return mhz;
    }
}
