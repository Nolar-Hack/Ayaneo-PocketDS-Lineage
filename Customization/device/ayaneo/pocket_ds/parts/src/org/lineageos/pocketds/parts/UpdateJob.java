/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.util.Log;

/** Looks for a new release twice a day and, if the user wants it, installs it by itself. */
public class UpdateJob extends JobService {
    private static final int JOB_ID = 4200;

    static void schedule(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJob.class))
                .setPeriodic(12 * 60 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                if (PartsPrefs.get(this).getBoolean(Updates.PREF_AUTO_CHECK, true)) {
                    final Updates.Info info = Updates.check(this);
                    if (info != null) {
                        Updates.notifyAvailable(this, info);
                        if (PartsPrefs.get(this).getBoolean(Updates.PREF_AUTO_INSTALL, false)
                                && canInstallNow() && !Updates.restartPending(this)
                                && !UpdateService.running()) {
                            startForegroundService(new Intent(this, UpdateService.class));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(Updates.TAG, "check failed: " + e);
            }
            jobFinished(params, false);
        }, "pocketds-update-check").start();
        return true;
    }

    /** Wi-Fi (not a metered network) and a battery that will not run out during the installation. */
    private boolean canInstallNow() {
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm.isActiveNetworkMetered()) return false;
        final Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        final boolean charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100
                / Math.max(1, battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        return charging || level >= 50;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
