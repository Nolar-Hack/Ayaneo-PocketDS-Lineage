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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the last hour of the numbers of the overlay (processor, GPU, memory, battery power, frame
 * rate, temperatures), one sample every 5 seconds, whether the overlay is open or not: tapping a
 * card of the overlay shows the curve of the last 5 minutes, 30 minutes or hour.
 */
final class MetricsRecorder {
    static final int CPU = 0;
    static final int GPU = 1;
    static final int RAM = 2;
    static final int BATTERY = 3;
    static final int FPS = 4;
    static final int CPU_TEMP = 5;
    static final int GPU_TEMP = 6;
    private static final int COUNT = 7;

    static final int PERIOD_S = 5;
    private static final int CAPACITY = 720; // one hour

    private static MetricsRecorder sInstance;

    private final Context mContext;
    private final float[][] mData = new float[COUNT][CAPACITY];
    private int mSamples;
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
        mHandler.post(mSample);
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
        final long microAmps = PerfOverlayController.readLong(
                "/sys/class/power_supply/battery/current_now", 0);
        final long microVolts = PerfOverlayController.readLong(
                "/sys/class/power_supply/battery/voltage_now", 0);
        // Watts drawn from (or put into) the battery.
        values[BATTERY] = (float) (Math.abs(microAmps) / 1e6 * microVolts / 1e6);
        final PerfOverlayController perf = PerfOverlayController.sInstance;
        values[FPS] = interactive && perf != null ? Math.max(0, perf.foregroundFps()) : 0;
        final PerformanceController engine = PerformanceController.get();
        values[CPU_TEMP] = engine == null ? 0 : engine.fan.hottestZone();
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
            final int slot = mSamples % CAPACITY;
            for (int i = 0; i < COUNT; i++) mData[i][slot] = values[i];
            mSamples++;
        }
    }

    /** The samples of the last {@code seconds} seconds, oldest first (fewer while it is young). */
    synchronized float[] series(int metric, int seconds) {
        final int wanted = Math.max(2, Math.min(Math.min(mSamples, CAPACITY),
                seconds / PERIOD_S));
        final float[] out = new float[Math.min(wanted, mSamples)];
        for (int i = 0; i < out.length; i++) {
            out[i] = mData[metric][(mSamples - out.length + i) % CAPACITY];
        }
        return out;
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
