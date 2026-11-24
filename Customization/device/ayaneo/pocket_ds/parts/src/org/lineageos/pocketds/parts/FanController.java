/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The fan, driven by the temperature of the processor: a duty cycle per fan mode, from a curve
 * (temperature in degrees -> percent, linear between the points). The mode comes from the profile
 * in use, or from the fan choice of the overlay when the user picked one.
 *
 * The duty is written to the pwm1 file of the fan (0-255, inverted: 255 = stopped, measured on the
 * console) when it changed enough to matter, and again whenever the file no longer holds it: at
 * boot the fan rail is power-cycled (init.pocketds.fan.sh) and the root shell may not be granted
 * yet (first boot of a fresh install), which left the fan stopped until the next restart. The file
 * is given to uid system by init.pocketds.fan.sh, so it is written directly; the root shell is
 * only the fallback.
 */
class FanController {
    private static final String TAG = "PocketDSFan";
    private static final long PERIOD_MS = 2000;
    private static final int MIN_PERCENT_ON = 25;

    // {temperature C, percent}: the fan starts low and reaches full speed at the top of the curve.
    private static final int[][] MUTE = {{40, 25}, {50, 35}, {60, 55}, {70, 80}, {80, 100}};
    private static final int[][] BALANCE = {{35, 30}, {45, 45}, {55, 65}, {65, 85}, {72, 100}};

    /** Temperatures of the points of the own curve; the percents are the user's. */
    static final int[] OWN_TEMPS = {40, 50, 60, 70, 80};
    static final int[] OWN_DEFAULT = {25, 35, 55, 80, 100};
    /** Above this temperature the fan runs at full speed, whatever the curve says. */
    private static final int ALWAYS_FULL_C = 85;

    private final Context mContext;
    private final PerformanceProfiles mProfiles;
    private final HandlerThread mThread = new HandlerThread("PocketDSFan");
    private Handler mHandler;
    private String mPwm;
    private final List<String> mZones = new ArrayList<>();
    private int mLastPercent = -1;
    /** The duty last written to pwm1, -1 before the first write. */
    private int mLastDuty = -1;
    private String mLastMode = "";

    FanController(Context context, PerformanceProfiles profiles) {
        mContext = context;
        mProfiles = profiles;
    }

    void start() {
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.post(this::discover);
    }

    /** The mode in force: the user's choice, else the one of the current profile. */
    String mode() {
        final String chosen = PartsPrefs.get(mContext).getString(PartsPrefs.FAN_MODE, "profile");
        return "profile".equals(chosen) ? mProfiles.get(mProfiles.current()).fan : chosen;
    }

    /** Applies a new mode at once (the overlay changed it). */
    void refresh() {
        if (mHandler != null) {
            mHandler.removeCallbacks(mTick);
            mHandler.post(mTick);
        }
    }

    private void discover() {
        for (int i = 0; i < 9 && mPwm == null; i++) {
            final File pwm = new File("/sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon" + i
                    + "/pwm1");
            if (pwm.exists()) mPwm = pwm.getPath();
        }
        final File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File zone : zones) {
                final String type = readString(new File(zone, "type"));
                if (type != null && (type.startsWith("cpu-") || type.startsWith("cpuss-"))) {
                    mZones.add(new File(zone, "temp").getPath());
                }
            }
        }
        Log.i(TAG, "Fan file " + mPwm + ", " + mZones.size() + " processor sensors");
        if (mPwm != null) mHandler.post(mTick);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (RuntimeException e) {
                Log.w(TAG, "Fan step failed", e);
            }
            mHandler.postDelayed(this, PERIOD_MS);
        }
    };

    private void step() {
        final String mode = mode();
        final int percent = percentFor(mode, hottestZone(), ownCurve());
        final boolean modeChanged = !mode.equals(mLastMode);
        // What the file holds now: a write lost (no root yet, rail power-cycled) is done again.
        final boolean lost = mLastDuty >= 0 && duty() != mLastDuty;
        if (modeChanged || lost || mLastPercent < 0 || Math.abs(percent - mLastPercent) >= 4
                || (percent == 0 && mLastPercent != 0)) {
            // The fan of this console is wired the other way round: 255 stops it, 0 is full speed.
            final int duty = 255 - percent * 255 / 100;
            final boolean written = write(duty);
            Log.i(TAG, "Fan " + mode + " " + percent + " % at " + hottestZone() + " C: "
                    + (written ? "written" : "NOT written (no root yet?), will retry")
                    + (lost ? " (the fan file had lost the last value)" : ""));
            // A write that failed is tried again at the next tick.
            if (written) {
                mLastPercent = percent;
                mLastMode = mode;
                mLastDuty = duty;
            }
        }
    }

    /**
     * Writes the duty: directly when the file is ours (init.pocketds.fan.sh), else through the
     * root shell. The root shell does not tell whether su was granted: the next tick reads the
     * file back and writes again when it does not hold the value.
     */
    private boolean write(int duty) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(mPwm)) {
            out.write(Integer.toString(duty).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | SecurityException e) {
            return RootShell.run("echo " + duty + " > " + mPwm);
        }
    }

    /** The duty the fan file holds, -1 when unreadable. */
    private int duty() {
        final String text = readString(new File(mPwm));
        try {
            return text == null ? -1 : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The own curve (percents at OWN_TEMPS, never going down), or null when it is not in use. */
    private int[] ownCurve() {
        final android.content.SharedPreferences prefs = PartsPrefs.get(mContext);
        return prefs.getBoolean(PartsPrefs.FAN_CURVE_ON, false)
                ? ownPercents(prefs.getString(PartsPrefs.FAN_CURVE, "")) : null;
    }

    /** The percents of the own curve from their saved text, each at least the one before. */
    static int[] ownPercents(String saved) {
        final String[] parts = saved.split(",");
        final int[] out = new int[OWN_TEMPS.length];
        for (int i = 0; i < out.length; i++) {
            int value = OWN_DEFAULT[i];
            try {
                if (i < parts.length) value = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                // the default stays
            }
            out[i] = Math.max(i > 0 ? out[i - 1] : 0, Math.max(0, Math.min(100, value)));
        }
        return out;
    }

    static int percentFor(String mode, float temperature) {
        return percentFor(mode, temperature, null);
    }

    static int percentFor(String mode, float temperature, int[] own) {
        if (PerformanceProfiles.FAN_OFF.equals(mode)) return 0;
        if (PerformanceProfiles.FAN_TURBO.equals(mode) || temperature >= ALWAYS_FULL_C) return 100;
        if (own != null) {
            if (temperature <= OWN_TEMPS[0]) return own[0];
            for (int i = 1; i < OWN_TEMPS.length; i++) {
                if (temperature <= OWN_TEMPS[i]) {
                    final float k = (temperature - OWN_TEMPS[i - 1])
                            / (float) (OWN_TEMPS[i] - OWN_TEMPS[i - 1]);
                    return Math.round(own[i - 1] + k * (own[i] - own[i - 1]));
                }
            }
            return own[own.length - 1];
        }
        final int[][] curve = PerformanceProfiles.FAN_BALANCE.equals(mode) ? BALANCE : MUTE;
        if (temperature <= curve[0][0]) return MIN_PERCENT_ON;
        for (int i = 1; i < curve.length; i++) {
            if (temperature <= curve[i][0]) {
                final float span = curve[i][0] - curve[i - 1][0];
                final float k = (temperature - curve[i - 1][0]) / span;
                return Math.max(MIN_PERCENT_ON, Math.round(curve[i - 1][1]
                        + k * (curve[i][1] - curve[i - 1][1])));
            }
        }
        return 100;
    }

    /** Temperature of the processor in degrees: the hottest of its sensors, 40 when unknown. */
    float hottestZone() {
        float hottest = 0;
        for (String zone : mZones) {
            final String text = readString(new File(zone));
            if (text == null) continue;
            try {
                hottest = Math.max(hottest, Integer.parseInt(text) / 1000f);
            } catch (NumberFormatException e) {
                // unreadable sensor
            }
        }
        return hottest > 0 ? hottest : 40f;
    }

    /** Speed of the fan in turns per minute (fan1_input next to pwm1), 0 when unknown. */
    int rpm() {
        if (mPwm == null) return 0;
        final String text = readString(new File(new File(mPwm).getParentFile(), "fan1_input"));
        try {
            return text == null ? 0 : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readString(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
