/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import vendor.qti.hardware.perf.V2_0.IPerf;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hints of the Qualcomm performance service (perf-hal), as the stock system sent them through
 * its BoostFramework. LineageOS has no such call, so the governors alone decided and left the
 * cores slow in a game whose threads wait on each other. The hints are the ones of
 * /vendor/etc/perf/perfboostsconfig.xml, read at run time and asked for as raw locks: the
 * service refuses the same hints when they are asked for by id. The service is reached by HIDL
 * (vendor.qti.hardware.perf), from a thread of our own.
 *
 * When the service cannot be reached the performance mode falls back to the same minimum
 * frequencies written directly.
 */
final class PerfBoost {
    private static final String TAG = "PocketDSPerfBoost";

    static final int HINT_LAUNCH = 0x00001081;
    static final int HINT_PERFORMANCE_MODE = 0x00001091;
    static final int HINT_FPS = 0x00001094;
    /** Launch types of the configuration: a main launch, and a game launch. */
    static final int LAUNCH_APP = 1;
    static final int LAUNCH_GAME = 6;

    // The minimum frequencies of the performance mode (kHz): little 800 MHz, big 1785 MHz, prime
    // 1670 MHz, rounded up to the frequencies the clusters have.
    private static final int[][] FALLBACK_MIN = {{0, 902_400}, {3, 1_785_600}, {7, 1_708_800}};

    private static final HandlerThread sThread = new HandlerThread("PocketDSPerf");
    private static final Handler sHandler;
    private static IPerf sService;
    // The state below belongs to the thread above.
    private static int sPerformance;
    private static int sGameCpu;
    private static boolean sFallback;
    private static int sFps;
    private static int sFpsType;

    static {
        sThread.start();
        sHandler = new Handler(sThread.getLooper());
    }

    private PerfBoost() {}

    /**
     * Every lock is a lease of this length, renewed every few seconds while it is wanted: the
     * performance service does not know which process asked (it records pid 0 for a HIDL call
     * from Java), so a lock held "until released" stays forever if this process dies.
     */
    static final int LEASE_MS = 15_000;

    private static synchronized IPerf service() {
        if (sService == null) {
            try {
                sService = IPerf.getService("default");
            } catch (RemoteException | NoSuchElementException e) {
                Log.w(TAG, "Performance service not reached", e);
            }
        }
        return sService;
    }

    // ---- the hints of the configuration, asked for as raw locks

    /** A hint of perfboostsconfig.xml: its resources (opcode, value pairs) and its timeout. */
    private static final class Hint {
        final ArrayList<Integer> resources = new ArrayList<>();
        int timeoutMs;
    }

    private static final String CONFIG_FILE = "/vendor/etc/perf/perfboostsconfig.xml";
    private static final Pattern CONFIG = Pattern.compile("<Config\\s+([^>]*?)/?>",
            Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static Map<String, Hint> sHints;

    private static String key(int id, int type) {
        return id + ":" + type;
    }

    /** The hints of the configuration for this platform, by id and type (read once). */
    private static synchronized Hint hintOf(int id, int type) {
        if (sHints == null) {
            sHints = new HashMap<>();
            final String platform = SystemProperties.get("ro.board.platform", "");
            try {
                final String xml = new String(Files.readAllBytes(new File(CONFIG_FILE).toPath()),
                        StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                final Matcher configs = CONFIG.matcher(xml);
                while (configs.find()) {
                    final Map<String, String> attributes = new HashMap<>();
                    final Matcher attribute = ATTRIBUTE.matcher(configs.group(1));
                    while (attribute.find()) attributes.put(attribute.group(1), attribute.group(2));
                    if (!attributes.containsKey("Id") || !attributes.containsKey("Resources")) {
                        continue;
                    }
                    if ("false".equalsIgnoreCase(attributes.get("Enable"))) continue;
                    final String target = attributes.get("Target");
                    if (target != null && !target.equals(platform)) continue;
                    if (attributes.containsKey("Fps")) continue;
                    final Hint hint = new Hint();
                    for (String token : attributes.get("Resources").trim().split("[\\s,]+")) {
                        if (!token.isEmpty()) hint.resources.add((int) Long.decode(token).longValue());
                    }
                    if (hint.resources.isEmpty() || hint.resources.size() % 2 != 0) continue;
                    final String timeout = attributes.get("Timeout");
                    hint.timeoutMs = timeout == null ? 0 : Integer.parseInt(timeout.trim());
                    final String type2 = attributes.get("Type");
                    sHints.put(key((int) Long.decode(attributes.get("Id").trim()).longValue(),
                            type2 == null ? 0 : Integer.parseInt(type2.trim())), hint);
                }
                Log.i(TAG, sHints.size() + " hints read from " + CONFIG_FILE);
            } catch (Exception e) {
                Log.w(TAG, "Hints not read from " + CONFIG_FILE, e);
            }
        }
        return sHints.get(key(id, type));
    }

    /**
     * Asks for a hint as a raw lock (the way the display composer asks, which the service
     * accepts; asking by hint id was refused). Returns the handle, or 0 or below when refused.
     * A timeout of 0 keeps the lock until it is released.
     */
    private static int lock(int id, int type) {
        final Hint hint = hintOf(id, type);
        if (hint == null) {
            Log.w(TAG, "Hint " + Integer.toHexString(id) + " type " + type + " not in the file");
            return -1;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            final IPerf perf = service();
            if (perf == null) return -1;
            try {
                return perf.perfLockAcquire(0, hint.timeoutMs > 0 ? hint.timeoutMs : LEASE_MS,
                        hint.resources, new ArrayList<>());
            } catch (RemoteException e) {
                Log.w(TAG, "Performance service lost, trying again", e);
                setService(null);
            }
        }
        return -1;
    }

    /** A raw lock of (opcode, value) pairs for one lease; returns the handle or 0 or less. */
    static int lockPairs(ArrayList<Integer> pairs) {
        for (int attempt = 0; attempt < 2; attempt++) {
            final IPerf perf = service();
            if (perf == null) return -1;
            try {
                return perf.perfLockAcquire(0, LEASE_MS, pairs, new ArrayList<>());
            } catch (RemoteException e) {
                Log.w(TAG, "Performance service lost, trying again", e);
                setService(null);
            }
        }
        return -1;
    }

    static void releaseLock(int handle) {
        release(handle);
    }

    /**
     * What a game needs from the scheduler: the thresholds of the stock game boost (60 % up,
     * 40 % down) for the move of a task to a faster cluster, so that the heavy threads of a game
     * can reach the prime core (3.36 GHz), and all the cores in service.
     */
    private static int gameSchedLock() {
        final ArrayList<Integer> pairs = new ArrayList<>();
        pairs.add(0x40CE0000);
        pairs.add(0x003C0028);
        pairs.add(0x40CE0200);
        pairs.add(0x003C0028);
        // All the big cores and the prime core stay in service (they are taken out of service
        // when idle; a game cannot use a core that is out, whatever the scheduler wants).
        pairs.add(0x41000000);
        pairs.add(4);
        pairs.add(0x41000200);
        pairs.add(1);
        return lockPairs(pairs);
    }

    private static void release(int handle) {
        if (handle <= 0) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            final IPerf perf = service();
            if (perf == null) return;
            try {
                perf.perfLockRelease(handle, new ArrayList<>());
                return;
            } catch (RemoteException e) {
                Log.w(TAG, "Performance service lost", e);
                setService(null);
            }
        }
    }

    private static void setService(IPerf perf) {
        synchronized (PerfBoost.class) {
            sService = perf;
        }
    }

    /**
     * Renews the leases that are held (called every few seconds): a new lock, then the old one is
     * released, so that the boost never lapses.
     */
    static void renew() {
        sHandler.post(() -> {
            if (sPerformance > 0) {
                final int performance = lock(HINT_PERFORMANCE_MODE, 0);
                if (performance > 0) {
                    release(sPerformance);
                    sPerformance = performance;
                }
                final int scheduling = gameSchedLock();
                if (scheduling > 0) {
                    release(sGameCpu);
                    sGameCpu = scheduling;
                }
            }
            if (sFps > 0) {
                final int fps = lock(HINT_FPS, sFpsType);
                if (fps > 0) {
                    release(sFps);
                    sFps = fps;
                }
            }
        });
    }

    /**
     * At the start of the app: the minimum frequencies written directly by an earlier run (when
     * the service refused the performance mode) are put back to the lowest the hardware has.
     */
    static void cleanUp() {
        sHandler.post(() -> {
            for (int[] cluster : FALLBACK_MIN) {
                final int[] freqs = PerformanceProfiles.cpuFrequencies(cluster[0]);
                if (freqs.length == 0) continue;
                RootShell.run("echo " + freqs[0] + " > /sys/devices/system/cpu/cpufreq/policy"
                        + cluster[0] + "/scaling_min_freq");
            }
        });
    }

    /** A boost for a launch: it lasts as long as the configuration says. */
    static void launch(String pkg, boolean game) {
        sHandler.post(() -> {
            final int handle = lock(HINT_LAUNCH, game ? LAUNCH_GAME : LAUNCH_APP);
            Log.i(TAG, "Launch boost for " + pkg + (game ? " (game)" : "") + ": " + handle);
        });
    }

    /** The refresh rate of the main screen, for the scheduler window and the memory sampling. */
    static void fps(int type) {
        sHandler.post(() -> {
            if (type == sFpsType && sFps > 0) return;
            final int old = sFps;
            final int handle = lock(HINT_FPS, type);
            if (handle > 0) {
                if (old > 0) release(old);
                sFps = handle;
                sFpsType = type;
            }
            Log.i(TAG, "Frame rate hint " + type + ": " + handle);
        });
    }

    /** The performance mode for a game in front; nothing happens if it is already on. */
    static void performanceOn(String pkg) {
        sHandler.post(() -> {
            if (sPerformance > 0) {
                GameCpuBoost.start(pkg);    // another game in front: its frame rate is followed
                return;
            }
            if (sFallback) return;
            final int handle = lock(HINT_PERFORMANCE_MODE, 0);
            if (handle > 0) {
                sPerformance = handle;
                sGameCpu = gameSchedLock();
                GameCpuBoost.start(pkg);
                Log.i(TAG, "Performance mode on for " + pkg + " (handle " + handle
                        + ", game scheduling: " + sGameCpu + ")");
                return;
            }
            Log.w(TAG, "Performance mode refused by the service (" + handle + ")");
            sFallback = true;
            for (int[] cluster : FALLBACK_MIN) {
                RootShell.run("echo " + cluster[1] + " > /sys/devices/system/cpu/cpufreq/policy"
                        + cluster[0] + "/scaling_min_freq");
            }
            Log.i(TAG, "Minimum frequencies written directly for " + pkg);
        });
    }

    static void performanceOff() {
        sHandler.post(() -> {
            if (sPerformance > 0) {
                release(sPerformance);
                release(sGameCpu);
                GameCpuBoost.stop();
                Log.i(TAG, "Performance mode released");
                sPerformance = 0;
                sGameCpu = 0;
            }
            if (sFallback) {
                sFallback = false;
                for (int[] cluster : FALLBACK_MIN) {
                    final int[] freqs = PerformanceProfiles.cpuFrequencies(cluster[0]);
                    if (freqs.length == 0) continue;
                    RootShell.run("echo " + freqs[0] + " > /sys/devices/system/cpu/cpufreq/policy"
                            + cluster[0] + "/scaling_min_freq");
                }
            }
        });
    }
}
