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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Downloads the package of the newest release and has update_engine install it in the other slot.
 * An incremental package that fails is replaced by the complete one. It never restarts the console:
 * the user does it from the System update page (or the notification).
 */
public class UpdateService extends Service {
    private static final String TAG = Updates.TAG;
    private static final String CHANNEL = "pocketds_update_progress";
    private static final int ID = 41;
    private static boolean sRunning;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Update in progress",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(ID, progress("Preparing the update", -1),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        synchronized (UpdateService.class) {
            if (sRunning) return START_NOT_STICKY;
            sRunning = true;
        }
        new Thread(() -> {
            try {
                run();
            } catch (RuntimeException e) {
                Log.e(TAG, "update failed", e);
                Updates.sState = Updates.FAILED;
                Updates.sMessage = "Unexpected error";
            } finally {
                synchronized (UpdateService.class) {
                    sRunning = false;
                }
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "pocketds-update").start();
        return START_NOT_STICKY;
    }

    static boolean running() {
        synchronized (UpdateService.class) {
            return sRunning;
        }
    }

    private Notification progress(String text, int percent) {
        final Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("System update").setContentText(text)
                .setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        return b.build();
    }

    private void notifyProgress(String text, int percent) {
        getSystemService(NotificationManager.class).notify(ID, progress(text, percent));
    }

    private void run() {
        final Updates.Info info = Updates.available(this);
        if (info == null) return;
        // The package that matches the installed build first; the complete one if it fails.
        final List<Updates.Pack> tries = new ArrayList<>();
        final Updates.Pack first = info.pick();
        tries.add(first);
        if (first != info.full) tries.add(info.full);

        String error = "";
        for (Updates.Pack pack : tries) {
            error = install(pack);
            if (error == null) {
                Updates.markReady(this, info.code);
                Updates.sState = Updates.IDLE;
                Updates.sMessage = "Restart to finish the update";
                Updates.post(this, "Update " + info.version + " is ready",
                        "Restart the console to finish. Nothing changes until you do.");
                return;
            }
            Log.w(TAG, pack.file + ": " + error);
        }
        Updates.sState = Updates.FAILED;
        Updates.sMessage = error;
        Updates.post(this, "Update " + info.version + " failed", error);
    }

    /** @return null when the package is installed, else why not */
    private String install(Updates.Pack pack) {
        final File file = new File(Updates.dir(this), pack.file);
        Updates.sState = Updates.DOWNLOADING;
        Updates.sPercent = 0;
        Updates.sMessage = "Downloading " + pack.file + " (" + Updates.size(pack.size) + ")";
        final String error = Updates.download(pack, file, percent -> {
            Updates.sPercent = percent;
            if (percent % 2 == 0) notifyProgress("Downloading", percent);
        });
        if (error != null) return error;

        Updates.sState = Updates.APPLYING;
        Updates.sPercent = 0;
        Updates.sMessage = "Installing";
        notifyProgress("Installing in the background", -1);
        final UpdateEngine engine = new UpdateEngine();
        final String[] result = {null};
        final CountDownLatch done = new CountDownLatch(1);
        try {
            final Updates.Payload payload = Updates.payload(file);
            engine.bind(new UpdateEngineCallback() {
                @Override
                public void onStatusUpdate(int status, float percent) {
                    Updates.sPercent = (int) (percent * 100);
                }

                @Override
                public void onPayloadApplicationComplete(int errorCode) {
                    if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                        result[0] = "Installation refused (code " + errorCode + ")";
                    }
                    done.countDown();
                }
            }, new Handler(Looper.getMainLooper()));
            engine.applyPayload("file://" + file.getAbsolutePath(), payload.offset, payload.size,
                    payload.headers);
            done.await();
        } catch (Exception e) {
            Log.w(TAG, "apply failed", e);
            result[0] = "Installation failed: " + e.getMessage();
        } finally {
            try {
                engine.unbind();
            } catch (RuntimeException ignored) {
            }
            file.delete();
        }
        return result[0];
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
