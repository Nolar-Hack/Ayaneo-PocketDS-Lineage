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
 * current); under the limit by 3 points charging is let through again. The state of the battery
 * is read back a few seconds after the limit is reached, then every minute while it is held (the
 * restriction is written again if something undid it), and the result is shown in the settings,
 * since not every charger obeys: on the USB port of a computer (usb_real_type SDP, 5 V, 500 mA)
 * what the console does not use goes to the battery whatever the restriction (+264 mA measured at
 * 99 %; the driver asks the charger for 0 uA), a USB-C PD charger obeys it. The input current
 * limit (usb/input_current_limit) cannot help: the input never goes under about 350 mA.
 *
 * battery/charge_control_limit (0 to 17, the charge current levels) is NOT used: the thermal
 * service of the console writes it too (it went to 17 by itself after a heavy load), and writing
 * it back to 0 could undo a protection against a hot battery.
 */
final class ChargeLimitController {
    private static final String TAG = "PocketDSChargeLimit";
    static final String PREF_ON = "charge_limit_on";
    static final String PREF_LEVEL = "charge_limit_level";
    static final String PREF_STATE = "charge_limit_state";
    /** A full charge is allowed from this time to the next one (ms since 1970); 0 when not. */
    static final String PREF_FULL_FROM = "charge_full_from";
    static final String PREF_FULL_UNTIL = "charge_full_until";
    /** A planned full charge starts this long before the time it must be ready. */
    static final long FULL_LEAD_MS = 3 * 3_600_000L;
    /** "Charge to 100 % now" lasts at most this long. */
    static final long FULL_NOW_MAX_MS = 6 * 3_600_000L;
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
    private static final String USB = "/sys/class/power_supply/usb/";
    /** The module of the fast charger (pumps): its parameters are read when a session starts. */
    private static final String TS = "/sys/module/ts_charger/parameters/";
    private static final int HYSTERESIS = 3;
    private static final long CHECK_DELAY_MS = 25_000;
    /** While the limit is held: how often the battery is read back. */
    private static final long RECHECK_MS = 60_000;
    /** The charge current is read this many times, a second apart, and averaged. */
    private static final int SAMPLES = 5;

    /** Above this current (µA) into the battery it is still being charged. */
    private static final long CHARGING_MICRO_AMPS = 50_000;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mHeld;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;
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
        // Kept in a field: SharedPreferences holds its listeners weakly.
        mPrefsListener = (prefs, key) -> {
            if (PREF_ON.equals(key) || PREF_LEVEL.equals(key) || PREF_GAME_BYPASS.equals(key)
                    || PREF_BYPASS.equals(key) || PREF_FULL_FROM.equals(key)
                    || PREF_FULL_UNTIL.equals(key)) {
                evaluate(mContext.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            }
        };
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
    }

    private void evaluate(Intent battery) {
        if (battery == null) return;
        final boolean on = mPrefs.getBoolean(PREF_ON, false);
        final boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int level = realLevel(battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100
                / Math.max(1, battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)));
        final int limit = effectiveLimit();
        scheduleFullCharge();
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

    /** The limit, or 100 while a full charge is allowed. */
    private int effectiveLimit() {
        return fullChargeOpen(mPrefs) ? 100 : limit();
    }

    /** A full charge is allowed now; one that is over is forgotten. */
    static boolean fullChargeOpen(SharedPreferences prefs) {
        final long now = System.currentTimeMillis();
        final long until = prefs.getLong(PREF_FULL_UNTIL, 0);
        if (until != 0 && now > until) {
            prefs.edit().remove(PREF_FULL_FROM).remove(PREF_FULL_UNTIL).apply();
            return false;
        }
        return until != 0 && now >= prefs.getLong(PREF_FULL_FROM, 0);
    }

    /** Allows 100 % from {@code from} to {@code until} (ms since 1970); the limit comes back after. */
    static void planFullCharge(SharedPreferences prefs, long from, long until) {
        prefs.edit().putLong(PREF_FULL_FROM, from).putLong(PREF_FULL_UNTIL, until).apply();
    }

    static void cancelFullCharge(SharedPreferences prefs) {
        prefs.edit().remove(PREF_FULL_FROM).remove(PREF_FULL_UNTIL).apply();
    }

    /** What is planned, in a few words for the settings. */
    static String describeFullCharge(Context context, SharedPreferences prefs) {
        final java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(context);
        final long until = prefs.getLong(PREF_FULL_UNTIL, 0);
        if (until == 0 || System.currentTimeMillis() > until) {
            return "Not planned: the battery always stops at the limit";
        }
        final long from = prefs.getLong(PREF_FULL_FROM, 0);
        if (System.currentTimeMillis() < from) {
            return "Starts at " + time.format(new java.util.Date(from)) + ", full at "
                    + time.format(new java.util.Date(until)) + ", then back to the limit";
        }
        return "Charging to 100 % until " + time.format(new java.util.Date(until))
                + ", then back to the limit";
    }

    private final Runnable mFullChargeTick = new Runnable() {
        @Override
        public void run() {
            evaluate(mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        }
    };

    /**
     * The limit is lifted and put back at fixed times, whatever the battery does: an alarm wakes
     * the console for the next one (starting the app if needed), the handler is for when it
     * is awake.
     */
    private void scheduleFullCharge() {
        mMain.removeCallbacks(mFullChargeTick);
        final long now = System.currentTimeMillis();
        final long from = mPrefs.getLong(PREF_FULL_FROM, 0);
        final long until = mPrefs.getLong(PREF_FULL_UNTIL, 0);
        long next = 0;
        if (until > now) next = from > now ? from : until;
        final android.app.AlarmManager alarms =
                mContext.getSystemService(android.app.AlarmManager.class);
        final android.app.PendingIntent wake = android.app.PendingIntent.getBroadcast(mContext,
                7, new Intent(mContext, ChargeAlarmReceiver.class),
                android.app.PendingIntent.FLAG_IMMUTABLE
                        | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == 0) {
            alarms.cancel(wake);
            return;
        }
        alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next + 1000, wake);
        mMain.postDelayed(mFullChargeTick, Math.min(next - now + 1000, 3_600_000L));
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
        restrict(limit);
        // The note under the charge limit is about the limit, not about playing on the charger.
        if (!mPrefs.getBoolean(PREF_ON, false)) return;
        mPrefs.edit().putString(PREF_STATE, "Limit reached, checking…").apply();
        mMain.removeCallbacks(this::verify);
        mMain.postDelayed(this::verify, CHECK_DELAY_MS);
    }

    /** Writes the restriction: the standard threshold when the kernel has it, the charger restriction. */
    private void restrict(int limit) {
        restrict(limit, false);
    }

    /**
     * @param fiveVolts the battery is charging although the limit is held: a fast charger is
     *                  brought back to 5 V (see below). Not done as long as the battery rests: at
     *                  9 V under a game the charger powers the whole console and the battery stays
     *                  untouched (measured: -19 mA at full load), where 5 V makes it give 1.1 A.
     */
    private void restrict(int limit, boolean fiveVolts) {
        RootShell.run("echo 0 > " + QCOM + "restrict_cur",
                "echo 1 > " + QCOM + "restrict_chg",
                "[ -w " + BATTERY + "charge_control_end_threshold ] && echo " + limit
                        + " > " + BATTERY + "charge_control_end_threshold",
                // A fast (PD, PPS) charger charges the battery through its own pumps at 9 V and
                // ignores the restriction while that session runs (measured: +1.5 to +3.4 A at
                // 85-89 %, restriction on). The state machine of the pumps can be switched off
                // (ts_charger parameter sm_disabled), but it is read when a session starts: a write
                // to the voltage of the port makes the charger negotiate again, and the new session
                // is at 5 V without pumps, where the restriction holds (measured: 5 V, -6 mA at rest,
                // stable). Released again in release().
                fiveVolts ? "if [ \"$(cat " + USB + "voltage_now)\" -gt 6500000 ] && [ -w " + TS
                        + "sm_disabled ]; then echo 1 > " + TS + "sm_disabled; echo 5000000 > "
                        + USB + "voltage_now; fi" : ":");
    }

    private void release() {
        mHeld = false;
        mMain.removeCallbacks(this::verify);
        mMain.removeCallbacks(mSampler);
        mPrefs.edit().putBoolean(PREF_HELD, false).apply();
        RootShell.run("echo 0 > " + QCOM + "restrict_chg",
                "echo 1000000 > " + QCOM + "restrict_cur",
                // Back to the fast charger: the state machine on, and a new session (measured: the
                // pumps start about a minute later and the charge goes back to +4.6 A).
                "if [ -w " + TS + "sm_disabled ] && [ \"$(cat " + TS + "sm_disabled)\" = 1 ]; then"
                        + " echo 0 > " + TS + "sm_disabled;"
                        + " [ \"$(cat " + USB + "online)\" = 1 ] && echo 9000000 > " + USB
                        + "voltage_now; fi",
                "[ -w " + BATTERY + "charge_control_end_threshold ] && echo 100 > " + BATTERY
                        + "charge_control_end_threshold");
    }

    /**
     * The charge of the battery. The gauge keeps saying 100 % while the charger reports "full",
     * even once the restriction of the limit has let the battery fall under it (measured: 7228 of
     * 7872 mAh, 92 %, and 4284 mV at rest, with 100 % on screen and no charging at all): the charge
     * counter tells the truth then. Under 100 % the gauge is believed.
     */
    static int realLevel(int shown) {
        if (shown < 100) return shown;
        final long counter = PerfOverlayController.readLong(BATTERY + "charge_counter", -1);
        final long full = PerfOverlayController.readLong(BATTERY + "charge_full", -1);
        if (counter <= 0 || full <= 0) return shown;
        final int fromCounter = (int) Math.min(100, counter * 100 / full);
        return fromCounter < 97 ? fromCounter : shown;
    }

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private int mSampleCount;
    private long mSampleSum;

    /** The battery must have stopped charging by now: reads the current and tells the user. */
    private void verify() {
        if (!mHeld) return;
        mSampleCount = 0;
        mSampleSum = 0;
        mMain.removeCallbacks(mSampler);
        mMain.post(mSampler);
    }

    /** One reading a second, averaged: a single one can fall in a peak of the console's own use. */
    private final Runnable mSampler = new Runnable() {
        @Override
        public void run() {
            if (!mHeld) return;
            mSampleSum += PerfOverlayController.readLong(BATTERY + "current_now", 0);
            if (++mSampleCount < SAMPLES) {
                mMain.postDelayed(this, 1000);
            } else {
                result(mSampleSum / SAMPLES);
            }
        }
    };

    private void result(long microAmps) {
        final Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        final int shown = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100
                / Math.max(1, battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        final int level = realLevel(shown);
        final boolean charging = microAmps >= CHARGING_MICRO_AMPS;
        Log.i(TAG, "held at " + level + " % (limit " + effectiveLimit() + " %): " + microAmps / 1000
                + " mA" + (charging ? ", still charging" : ", charging stopped"));
        if (mPrefs.getBoolean(PREF_ON, false)) {
            final String state;
            if (charging) {
                // Something undid the restriction, or this charger ignores it: written again.
                restrict(effectiveLimit(), true);
                // A computer USB port (SDP: 5 V, 500 mA): the charger firmware lets what the console
                // does not use go to the battery, whatever the restriction (read in the driver:
                // the restriction asks for 0 uA of charge current; measured: +264 mA at 99 %).
                state = "SDP".equals(read(QCOM + "usb_real_type"))
                        ? "A computer USB port cannot hold the charge: use a USB-C PD charger ("
                                + microAmps / 1000 + " mA into the battery)"
                        : "This cable or charger keeps charging past the limit ("
                                + microAmps / 1000 + " mA into the battery)";
            } else if (level > effectiveLimit() + 1) {
                state = "Charging stopped at " + level + " %"
                        + (shown > level ? " (the system shows " + shown + " %)" : "")
                        + ": it goes down to " + effectiveLimit()
                        + " % when you use the console on battery";
            } else {
                state = "Working: charging stopped at the limit";
            }
            mPrefs.edit().putString(PREF_STATE, state).apply();
        }
        // Looked at again while the limit is held: a charger change, a thermal override.
        mMain.removeCallbacks(this::verify);
        mMain.postDelayed(this::verify, RECHECK_MS);
    }
}
