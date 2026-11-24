/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Tells the user, once for each step (80 %, 70 %, 60 %), that the battery holds less of its
 * capacity when new. Checked twice a day with the update job; a battery that goes back up (a
 * new one) starts over.
 */
final class BatteryHealth {
    static final String PREF = "battery_health_alert";
    private static final String KEY_LEVEL = "battery_health_alerted";
    private static final String CHANNEL = "battery_health";
    private static final int NOTIFICATION_ID = 4203;
    private static final int[] STEPS = {80, 70, 60};
    private static final String SUPPLY = "/sys/class/power_supply/battery/";

    private BatteryHealth() {}

    /** The capacity compared with a new battery, in percent; -1 when the gauge does not say. */
    static int percent() {
        final long full = PerfOverlayController.readLong(SUPPLY + "charge_full", 0);
        final long design = PerfOverlayController.readLong(SUPPLY + "charge_full_design", 0);
        return full > 0 && design > 0 ? (int) Math.round(full * 100.0 / design) : -1;
    }

    static void check(Context context) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        final int percent = percent();
        if (percent < 0) return;
        int alerted = prefs.getInt(KEY_LEVEL, 100);
        if (percent > alerted + 5) {
            // a battery that was changed: start again
            alerted = 100;
            prefs.edit().putInt(KEY_LEVEL, alerted).apply();
        }
        if (!prefs.getBoolean(PREF, true)) return;
        for (int step : STEPS) {
            if (percent < step && alerted > step) {
                prefs.edit().putInt(KEY_LEVEL, step).apply();
                post(context, "Battery worn: " + percent + " % of its capacity",
                        "It holds " + percent + " % of what it held when new. Limiting the "
                        + "charge to 80 % slows the wear.");
                return;
            }
        }
    }

    private static void post(Context context, String title, String text) {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Battery health",
                NotificationManager.IMPORTANCE_DEFAULT));
        final Intent open = new Intent(context, SectionActivity.class)
                .putExtra(SectionActivity.EXTRA_SECTION, SectionActivity.BATTERY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        manager.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(context, 1, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true).build());
    }
}
