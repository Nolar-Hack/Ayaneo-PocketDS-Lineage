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
 * console) through the root shell, only when it changed enough to matter.
 */
class FanController {
    private static final String TAG = "PocketDSFan";
    private static final long PERIOD_MS = 2000;
    private static final int MIN_PERCENT_ON = 25;

    // {temperature C, percent}: the fan starts low and reaches full speed at the top of the curve.
    private static final int[][] MUTE = {{40, 25}, {50, 35}, {60, 55}, {70, 80}, {80, 100}};
    private static final int[][] BALANCE = {{35, 30}, {45, 45}, {55, 65}, {65, 85}, {72, 100}};

    private final Context mContext;
    private final PerformanceProfiles mProfiles;
    private final HandlerThread mThread = new HandlerThread("PocketDSFan");
    private Handler mHandler;
    private String mPwm;
    private final List<String> mZones = new ArrayList<>();
    private int mLastPercent = -1;
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
        final int percent = percentFor(mode, hottestZone());
        final boolean modeChanged = !mode.equals(mLastMode);
        if (modeChanged || mLastPercent < 0 || Math.abs(percent - mLastPercent) >= 4
                || (percent == 0 && mLastPercent != 0)) {
            // The fan of this console is wired the other way round: 255 stops it, 0 is full speed.
            final boolean written = RootShell.run("echo " + (255 - percent * 255 / 100)
                    + " > " + mPwm);
            Log.i(TAG, "Fan " + mode + " " + percent + " % at " + hottestZone() + " C: "
                    + (written ? "written" : "NOT written (no root yet?), will retry"));
            // A write that failed (root not ready yet) is tried again at the next tick.
            if (written) {
                mLastPercent = percent;
                mLastMode = mode;
            }
        }
    }

    static int percentFor(String mode, float temperature) {
        if (PerformanceProfiles.FAN_OFF.equals(mode)) return 0;
        if (PerformanceProfiles.FAN_TURBO.equals(mode)) return 100;
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
