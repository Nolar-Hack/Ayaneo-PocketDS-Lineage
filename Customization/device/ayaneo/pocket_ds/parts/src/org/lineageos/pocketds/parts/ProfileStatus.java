/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;

/**
 * The performance profile in the status bar: an icon of the profile next to the clock and the
 * Wi-Fi, and its name and limits in the notification shade.
 */
final class ProfileStatus {
    // A silent notification (low importance) shows no icon in the status bar: this channel has
    // the default importance, with no sound and no vibration.
    private static final String CHANNEL = "pocketds_profile_status";
    private static final String OLD_CHANNEL = "pocketds_profile";
    private static final int ID = 2;
    private static final String[] ICONS = {"leaf", "gauge", "cast", "gamepad", "zap"};

    private ProfileStatus() {}

    static void show(Context context, int profile) {
        if (profile < 0 || profile >= ICONS.length) return;
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            RootShell.run("pm grant " + context.getPackageName()
                    + " android.permission.POST_NOTIFICATIONS");
        }
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
                manager.deleteNotificationChannel(OLD_CHANNEL);
        final NotificationChannel channel = new NotificationChannel(CHANNEL, "Performance profile",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        final int icon = context.getResources().getIdentifier("ic_" + ICONS[profile], "drawable",
                context.getPackageName());
        final String name = PerformanceProfiles.NAMES[profile];
        final Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(Icon.createWithResource(context, icon))
                .setColor(Accent.of(profile))
                .setContentTitle(name + " profile")
                .setContentText("Performance profile in use. The blue button opens the overlay.")
                .setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS).build();
        manager.notify(ID, notification);
    }
}
