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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Counts the charge cycles of the battery itself. The gauge of the console (cycle_count, Qualcomm
 * firmware) counts far too few: it stayed at 5 after weeks of a full charge a day. Here every
 * microampere-hour taken from the battery is added up, and one cycle is as much as the capacity of
 * a new battery (charge_full_design), as phones do: two times 50 % make one cycle.
 *
 * Only what leaves the battery while the charger is not plugged counts (a battery that rests on the
 * charger, or that is held at its limit, is not used). The count starts when this version is first
 * run: the cycles of before are not known. It is part of the backups, so it survives a wipe that is
 * restored; the last reading (KEY_LAST) is not, it is only a point of reference.
 */
final class CycleCounter {
    /** Microampere-hours taken from the battery since KEY_SINCE. */
    static final String KEY_DISCHARGED = "cycle_discharged_uah";
    /** When the count started (ms since 1970). */
    static final String KEY_SINCE = "cycle_since";
    /** charge_counter at the last time the count was saved: kept out of the backups. */
    static final String KEY_LAST = "cycle_last_uah";

    private static final String SUPPLY = "/sys/class/power_supply/battery/";

    private final Context mContext;
    private final SharedPreferences mPrefs;
    /** charge_counter at the last reading, -1 before the first. */
    private long mLast = -1;
    /** Counted since the last save to the preferences. */
    private long mUnsaved;

    CycleCounter(Context context) {
        mContext = context;
        mPrefs = PartsPrefs.get(context);
    }

    void start() {
        if (mPrefs.getLong(KEY_SINCE, 0) == 0) {
            mPrefs.edit().putLong(KEY_SINCE, System.currentTimeMillis()).apply();
        }
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                update(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private synchronized void update(boolean plugged) {
        final long now = readLong("charge_counter");
        final long design = readLong("charge_full_design");
        if (now < 0 || design <= 0) return;
        // After a restart of the app, the last point saved: what was taken since is counted.
        if (mLast < 0) mLast = mPrefs.getLong(KEY_LAST, now);
        if (!plugged && now < mLast) {
            final long delta = mLast - now;
            // A jump of more than a quarter of the battery between two readings is the gauge
            // adjusting itself, not use.
            if (delta <= design / 4) mUnsaved += delta;
        }
        mLast = now;
        // Written by steps of half a percent: no write at every change of the battery.
        if (mUnsaved >= design / 200) {
            mPrefs.edit()
                    .putLong(KEY_DISCHARGED, mPrefs.getLong(KEY_DISCHARGED, 0) + mUnsaved)
                    .putLong(KEY_LAST, mLast).apply();
            mUnsaved = 0;
        }
    }

    /** Cycles counted so far, 0 until something was counted. */
    static float cycles(SharedPreferences prefs) {
        final long design = readLong("charge_full_design");
        return design > 0 ? prefs.getLong(KEY_DISCHARGED, 0) / (float) design : 0f;
    }

    /**
     * "2.4 cycles counted since 21 Nov 2026 · the gauge of the console says 5", the gauge part only
     * when it can be read (gauge &lt; 0 when it cannot).
     */
    static String describe(SharedPreferences prefs, long gauge) {
        final long since = prefs.getLong(KEY_SINCE, 0);
        final String when = since > 0 ? " since " + DateFormat.getDateInstance(DateFormat.MEDIUM)
                .format(new Date(since)) : "";
        final String text = String.format(Locale.US, "%.1f cycles counted%s", cycles(prefs), when);
        return gauge >= 0 ? text + "  ·  gauge of the console: " + gauge + " (unreliable)" : text;
    }

    private static long readLong(String file) {
        try {
            return Long.parseLong(new String(Files.readAllBytes(Paths.get(SUPPLY + file)),
                    StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}
