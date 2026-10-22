/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Keeps the app running now that it is no longer a persistent app (so that it can be updated with
 * a plain "adb install -r"). The controllers are started by {@link PocketDSApplication}; this
 * foreground service only keeps the process alive and brings it back if it is killed.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "pocketds_background";
    private static final int ID = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        // The lowest importance: no icon in the status bar, no sound.
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Background service",
                NotificationManager.IMPORTANCE_MIN));
        final Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("PocketDS Extra")
                .setContentText("Automation and battery features are running")
                .setOngoing(true).setShowWhen(false).build();
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
