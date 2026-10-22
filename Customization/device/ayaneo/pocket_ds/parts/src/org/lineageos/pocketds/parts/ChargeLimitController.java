/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps the battery from charging past a chosen level (it ages less when it stays below 100 %).
 * At the limit the charger of the console is restricted (qcom-battery restrict_chg with no
 * current); under it by 3 points charging is let through again. A few seconds after the limit is
 * reached the state of the battery is read back and the result is shown in the settings, since
 * not every charger firmware obeys the restriction.
 */
final class ChargeLimitController {
    private static final String TAG = "PocketDSChargeLimit";
    static final String PREF_ON = "charge_limit_on";
    static final String PREF_LEVEL = "charge_limit_level";
    static final String PREF_STATE = "charge_limit_state";
    private static final String PREF_HELD = "charge_limit_held";
    private static final String QCOM = "/sys/class/qcom-battery/";
    private static final String BATTERY = "/sys/class/power_supply/battery/";
    private static final int HYSTERESIS = 3;
    private static final long CHECK_DELAY_MS = 25_000;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mHeld;

    ChargeLimitController(Context context) {
        mContext = context;
        mPrefs = PartsPrefs.get(context);
    }

    void start() {
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                evaluate(intent);
            }
        };
        evaluate(mContext.registerReceiver(receiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        mPrefs.registerOnSharedPreferenceChangeListener((prefs, key) -> {
            if (PREF_ON.equals(key) || PREF_LEVEL.equals(key)) {
                evaluate(mContext.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            }
        });
    }

    private void evaluate(Intent battery) {
        if (battery == null) return;
        final boolean on = mPrefs.getBoolean(PREF_ON, false);
        final boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100
                / Math.max(1, battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        final int limit = limit();
        if (!on || !plugged) {
            if (mHeld || mPrefs.getBoolean(PREF_HELD, false)) release();
            if (!on) mPrefs.edit().remove(PREF_STATE).apply();
            return;
        }
        if (!mHeld && level >= limit) {
            hold(limit);
        } else if (mHeld && level <= limit - HYSTERESIS) {
            release();
        }
    }

    private int limit() {
        try {
            return Math.max(50, Math.min(100, Integer.parseInt(
                    mPrefs.getString(PREF_LEVEL, "80"))));
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    private void hold(int limit) {
        mHeld = true;
        mPrefs.edit().putBoolean(PREF_HELD, true).apply();
        // The standard threshold when the kernel has it, else the restriction of the charger.
        RootShell.run("echo 0 > " + QCOM + "restrict_cur",
                "echo 1 > " + QCOM + "restrict_chg",
                "[ -w " + BATTERY + "charge_control_end_threshold ] && echo " + limit
                        + " > " + BATTERY + "charge_control_end_threshold");
        mPrefs.edit().putString(PREF_STATE, "Limit reached, checking…").apply();
        mMain.removeCallbacks(this::verify);
        mMain.postDelayed(this::verify, CHECK_DELAY_MS);
    }

    private void release() {
        mHeld = false;
        mMain.removeCallbacks(this::verify);
        mPrefs.edit().putBoolean(PREF_HELD, false).apply();
        RootShell.run("echo 0 > " + QCOM + "restrict_chg",
                "echo 1000000 > " + QCOM + "restrict_cur",
                "[ -w " + BATTERY + "charge_control_end_threshold ] && echo 100 > " + BATTERY
                        + "charge_control_end_threshold");
    }

    /** The battery must have stopped charging by now: tells the user whether it did. */
    private void verify() {
        if (!mHeld) return;
        final Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        final int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        final boolean stopped = status != BatteryManager.BATTERY_STATUS_CHARGING;
        Log.i(TAG, "limit reached, status " + status + (stopped ? ": charging stopped"
                : ": still charging"));
        mPrefs.edit().putString(PREF_STATE, stopped ? "Working: charging stopped at the limit"
                : "The charger of this console keeps charging past the limit").apply();
    }
}
