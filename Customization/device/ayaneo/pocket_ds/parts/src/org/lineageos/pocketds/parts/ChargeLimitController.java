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
    /** First version: on = bypass while a game is in front (read when PREF_BYPASS is unset). */
    static final String PREF_GAME_BYPASS = "charge_game_bypass";
    /**
     * Run on the cable, battery resting, while plugged in: "off", "games" (a game in front) or
     * "always".
     */
    static final String PREF_BYPASS = "charge_bypass";
    /** Under this level the battery is charged even while a game is in front. */
    static final int BYPASS_FROM = 50;
    private static ChargeLimitController sInstance;
    private static final String PREF_HELD = "charge_limit_held";
    private static final String QCOM = "/sys/class/qcom-battery/";
    private static final String BATTERY = "/sys/class/power_supply/battery/";
    private static final int HYSTERESIS = 3;
    private static final long CHECK_DELAY_MS = 25_000;
    /** Above this current (µA) into the battery it is still being charged. */
    private static final long CHARGING_MICRO_AMPS = 50_000;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mHeld;
    /** Playing on the charger: the battery rests (shown on the LED, see LedController). */
    private static volatile boolean sBypassing;

    ChargeLimitController(Context context) {
        mContext = context;
        mPrefs = PartsPrefs.get(context);
    }

    /** A game came to the front or left it: charging is looked at again. */
    static void gameChanged() {
        final ChargeLimitController controller = sInstance;
        if (controller == null) return;
        controller.mMain.post(() -> controller.evaluate(controller.mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED))));
    }

    void start() {
        sInstance = this;
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                evaluate(intent);
            }
        };
        evaluate(mContext.registerReceiver(receiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        mPrefs.registerOnSharedPreferenceChangeListener((prefs, key) -> {
            if (PREF_ON.equals(key) || PREF_LEVEL.equals(key) || PREF_GAME_BYPASS.equals(key)
                    || PREF_BYPASS.equals(key)) {
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
        // Plugged in: the charger powers the console and the battery rests (measured with a PD
        // charger and a game: -17 mA from the battery, against +800 mA of charge otherwise).
        // While playing it starts at 50 %; always: at the level of the charge limit, so that
        // the battery is still charged up to it.
        final String mode = bypassMode(mPrefs);
        final boolean always = "always".equals(mode);
        final int from = always ? limit : BYPASS_FROM;
        final boolean bypass = (always || ("games".equals(mode) && PerfHints.gameInFront()))
                && level >= (mHeld ? from - HYSTERESIS : from);
        final boolean wasBypassing = sBypassing;
        sBypassing = plugged && bypass;
        if (wasBypassing != sBypassing) LedController.apply(mContext, false);
        if (!plugged || (!on && !bypass)) {
            if (mHeld || mPrefs.getBoolean(PREF_HELD, false)) release();
            if (!on) mPrefs.edit().remove(PREF_STATE).apply();
            return;
        }
        final boolean overLimit = on && (mHeld ? level > limit - HYSTERESIS : level >= limit);
        if (!mHeld && (overLimit || bypass)) {
            hold(on ? limit : 100);
        } else if (mHeld && !overLimit && !bypass) {
            release();
        }
    }

    static String bypassMode(SharedPreferences prefs) {
        final String mode = prefs.getString(PREF_BYPASS, null);
        if (mode != null) return mode;
        return prefs.getBoolean(PREF_GAME_BYPASS, false) ? "games" : "off";
    }

    static boolean bypassing() {
        return sBypassing;
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
        // The note under the charge limit is about the limit, not about playing on the charger.
        if (!mPrefs.getBoolean(PREF_ON, false)) return;
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
        // Android keeps saying "charging" while restrict_chg holds the charger: the current of
        // the battery tells (measured: -40 to -80 mA held, against +800 mA charging).
        final long microAmps = PerfOverlayController.readLong(BATTERY + "current_now", 0);
        final boolean stopped = status != BatteryManager.BATTERY_STATUS_CHARGING
                || microAmps < CHARGING_MICRO_AMPS;
        Log.i(TAG, "limit reached, status " + status + ", " + microAmps / 1000 + " mA"
                + (stopped ? ": charging stopped" : ": still charging"));
        mPrefs.edit().putString(PREF_STATE, stopped ? "Working: charging stopped at the limit"
                : "The charger of this console keeps charging past the limit").apply();
    }
}
