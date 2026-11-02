/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the numbers of the overlay (processor, GPU, memory, battery, frame rate, temperatures,
 * frequencies), whether the overlay is open or not: tapping a card of the overlay shows the curve
 * of the last 5 minutes, hour or day. One sample every 5 seconds is kept for an hour; for the day
 * one sample is the average of a minute. Both are saved every 5 minutes, so that the curves
 * survive a restart (the time the console was off shows as zeros).
 */
final class MetricsRecorder {
    static final int CPU = 0;
    static final int GPU = 1;
    static final int RAM = 2;
    static final int BATTERY = 3;
    static final int FPS = 4;
    static final int CPU_TEMP = 5;
    static final int GPU_TEMP = 6;
    static final int CPU_FREQ = 7;
    static final int GPU_FREQ = 8;
    static final int BATT_LEVEL = 9;
    static final int BATT_TEMP = 10;
    static final int FPS_LOW = 11;
    static final int RAM_GB = 12;
    private static final int COUNT = 13;

    static final int PERIOD_S = 5;
    private static final int FINE = 720;            // one hour of 5 seconds
    private static final int STEP = 12;             // fine samples in one coarse one
    private static final int COARSE = 1440;         // one day of minutes
    private static final long SAVE_MS = 5 * 60_000L;
    private static final int MAGIC = 0x504D5432;    // "PMT2"
    private static final String TAG = "PocketDSMetrics";

    private static MetricsRecorder sInstance;

    private final Context mContext;
    private final float[][] mFine = new float[COUNT][FINE];
    private final float[][] mCoarse = new float[COUNT][COARSE];
    private final float[] mSum = new float[COUNT];
    private int mFineSamples;
    private int mCoarseSamples;
    private int mInMinute;
    private final HandlerThread mThread = new HandlerThread("PocketDSMetrics");
    private Handler mHandler;
    private final List<String> mGpuZones = new ArrayList<>();
    private long mLastTotal;
    private long mLastIdle;

    MetricsRecorder(Context context) {
        mContext = context;
        sInstance = this;
    }

    static MetricsRecorder get() {
        return sInstance;
    }

    void start() {
        final File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File zone : zones) {
                final String type = read(new File(zone, "type"));
                if (type != null && type.startsWith("gpuss-")) {
                    mGpuZones.add(new File(zone, "temp").getPath());
                }
            }
        }
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.post(() -> {
            load();
            mHandler.post(mSample);
            mHandler.postDelayed(mSave, SAVE_MS);
        });
    }

    private final Runnable mSample = new Runnable() {
        @Override
        public void run() {
            try {
                sample();
            } catch (RuntimeException e) {
                // a broken reading must not stop the next ones
            }
            mHandler.postDelayed(this, PERIOD_S * 1000L);
        }
    };

    private final Runnable mSave = new Runnable() {
        @Override
        public void run() {
            save();
            mHandler.postDelayed(this, SAVE_MS);
        }
    };

    private void sample() {
        final boolean interactive = mContext.getSystemService(PowerManager.class).isInteractive();
        final float[] values = new float[COUNT];
        values[CPU] = cpuUsage();
        final long gpuBusy = PerfOverlayController.readLong(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", 0);
        values[GPU] = Math.max(0, Math.min(100, gpuBusy));
        final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        mContext.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        values[RAM] = memory.totalMem == 0 ? 0
                : 100f * (memory.totalMem - memory.availMem) / memory.totalMem;
        values[RAM_GB] = (memory.totalMem - memory.availMem) / 1e9f;
        final long microAmps = PerfOverlayController.readLong(
                "/sys/class/power_supply/battery/current_now", 0);
        final long microVolts = PerfOverlayController.readLong(
                "/sys/class/power_supply/battery/voltage_now", 0);
        // Watts drawn from (or put into) the battery.
        values[BATTERY] = (float) (Math.abs(microAmps) / 1e6 * microVolts / 1e6);
        final Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        values[BATT_LEVEL] = battery == null ? 0
                : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        values[BATT_TEMP] = battery == null ? 0
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        final PerfOverlayController perf = PerfOverlayController.sInstance;
        values[FPS] = interactive && perf != null ? Math.max(0, perf.foregroundFps()) : 0;
        values[FPS_LOW] = interactive && perf != null ? perf.lastLowFps() : 0;
        final PerformanceController engine = PerformanceController.get();
        values[CPU_TEMP] = engine == null ? 0 : engine.fan.hottestZone();
        values[CPU_FREQ] = PerfOverlayController.maxCpuKhz() / 1000f;
        values[GPU_FREQ] = PerfOverlayController.readLong("/sys/class/kgsl/kgsl-3d0/gpuclk", 0)
                / 1_000_000f;
        float gpuTemp = 0;
        for (String zone : mGpuZones) {
            final String text = read(new File(zone));
            try {
                if (text != null) gpuTemp = Math.max(gpuTemp, Integer.parseInt(text) / 1000f);
            } catch (NumberFormatException e) {
                // unreadable sensor
            }
        }
        values[GPU_TEMP] = gpuTemp;
        synchronized (this) {
            push(values);
        }
    }

    /** Adds one 5 second sample, and the average of the minute when it is complete. */
    private void push(float[] values) {
        final int slot = mFineSamples % FINE;
        for (int i = 0; i < COUNT; i++) {
            mFine[i][slot] = values[i];
            mSum[i] += values[i];
        }
        mFineSamples++;
        if (++mInMinute >= STEP) {
            final int coarse = mCoarseSamples % COARSE;
            for (int i = 0; i < COUNT; i++) {
                mCoarse[i][coarse] = mSum[i] / STEP;
                mSum[i] = 0;
            }
            mCoarseSamples++;
            mInMinute = 0;
        }
    }

    /**
     * The samples of the last {@code seconds} seconds, oldest first (fewer while it is young).
     * Up to an hour these are the 5 second samples, beyond that the averages of a minute.
     */
    synchronized float[] series(int metric, int seconds) {
        final boolean coarse = seconds > FINE * PERIOD_S;
        final float[] data = coarse ? mCoarse[metric] : mFine[metric];
        final int have = coarse ? mCoarseSamples : mFineSamples;
        final int capacity = coarse ? COARSE : FINE;
        final int period = coarse ? PERIOD_S * STEP : PERIOD_S;
        final int wanted = Math.max(2, Math.min(Math.min(have, capacity), seconds / period));
        final float[] out = new float[Math.min(wanted, have)];
        for (int i = 0; i < out.length; i++) {
            out[i] = data[(have - out.length + i) % capacity];
        }
        return out;
    }

    // ---- saved between two runs

    private File file() {
        return new File(mContext.getFilesDir(), "metrics.bin");
    }

    private void save() {
        final float[][] fine = new float[COUNT][FINE];
        final float[][] coarse = new float[COUNT][COARSE];
        final int fineSamples;
        final int coarseSamples;
        synchronized (this) {
            for (int i = 0; i < COUNT; i++) {
                System.arraycopy(mFine[i], 0, fine[i], 0, FINE);
                System.arraycopy(mCoarse[i], 0, coarse[i], 0, COARSE);
            }
            fineSamples = mFineSamples;
            coarseSamples = mCoarseSamples;
        }
        final File tmp = new File(mContext.getFilesDir(), "metrics.tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(
                new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(COUNT);
            out.writeInt(fineSamples);
            out.writeInt(coarseSamples);
            for (int i = 0; i < COUNT; i++) {
                for (float v : fine[i]) out.writeFloat(v);
                for (float v : coarse[i]) out.writeFloat(v);
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot save the curves", e);
            return;
        }
        tmp.renameTo(file());
    }

    /** Reads the curves of the last run; the time since then is added as zeros. */
    private void load() {
        final File file = file();
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) return;
            final long savedAt = in.readLong();
            if (in.readInt() != COUNT) return;
            final int fineSamples = in.readInt();
            final int coarseSamples = in.readInt();
            final float[][] fine = new float[COUNT][FINE];
            final float[][] coarse = new float[COUNT][COARSE];
            for (int i = 0; i < COUNT; i++) {
                for (int k = 0; k < FINE; k++) fine[i][k] = in.readFloat();
                for (int k = 0; k < COARSE; k++) coarse[i][k] = in.readFloat();
            }
            synchronized (this) {
                for (int i = 0; i < COUNT; i++) {
                    System.arraycopy(fine[i], 0, mFine[i], 0, FINE);
                    System.arraycopy(coarse[i], 0, mCoarse[i], 0, COARSE);
                }
                mFineSamples = fineSamples;
                mCoarseSamples = coarseSamples;
                // the time without samples (off, or the app restarting) shows as zeros
                final long gapS = Math.max(0, (System.currentTimeMillis() - savedAt) / 1000);
                final float[] zeros = new float[COUNT];
                final long fineGap = Math.min(FINE, gapS / PERIOD_S);
                for (long k = 0; k < fineGap; k++) {
                    final int slot = mFineSamples % FINE;
                    for (int i = 0; i < COUNT; i++) mFine[i][slot] = 0;
                    mFineSamples++;
                }
                final long coarseGap = Math.min(COARSE, gapS / (PERIOD_S * STEP));
                for (long k = 0; k < coarseGap; k++) {
                    final int slot = mCoarseSamples % COARSE;
                    for (int i = 0; i < COUNT; i++) mCoarse[i][slot] = zeros[i];
                    mCoarseSamples++;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot read the saved curves", e);
        }
    }

    /** Battery level in percent, for the card of the battery. */
    static int batteryLevel(Context context) {
        final Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
    }

    private int cpuUsage() {
        final String text = read(new File("/proc/stat"));
        if (text == null) return 0;
        final String[] f = text.split("\n")[0].trim().split("\\s+");
        long total = 0;
        try {
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            final long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            final long dTotal = total - mLastTotal;
            final long dIdle = idle - mLastIdle;
            mLastTotal = total;
            mLastIdle = idle;
            if (dTotal <= 0) return 0;
            return (int) Math.max(0, Math.min(100, 100 * (dTotal - dIdle) / dTotal));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
