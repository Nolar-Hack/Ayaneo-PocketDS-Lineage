/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * A system log that survives a restart: the warnings and errors (and what the controllers of this
 * app say) are written by logcat, through the root shell, in 6 files of 1 MB at most in the
 * folder of the app; files older than 2 days are deleted. The normal logcat is lost at each
 * restart, which is when the cause of a power off is needed. It stays on the console and is added
 * to the bug reports.
 */
final class SystemLog {
    static final String PREF = "keep_system_log";
    private static final int KEEP_MINUTES = 2 * 24 * 60;
    private static final String TAGS = "PocketDSPowerOff:I PocketDSSuspend:I PocketDSChargeLimit:I"
            + " PocketDSUsbGuard:I PocketDSUpdates:I PocketDSRules:I PocketDSParts:I"
            + " PocketDSScreens:I PocketDSBtIdle:I ShutdownThread:I EventHub:S";

    private static SharedPreferences.OnSharedPreferenceChangeListener sListener;

    private SystemLog() {}

    static String dir(Context context) {
        return context.createDeviceProtectedStorageContext().getFilesDir().getPath() + "/syslog";
    }

    static void start(Context context) {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = PartsPrefs.get(app);
        sListener = (p, key) -> {
            if (PREF.equals(key)) apply(app);
        };
        prefs.registerOnSharedPreferenceChangeListener(sListener);
        apply(app);
    }

    /** Starts the log (once), clears the old files, or stops and erases it when it is off. */
    static void apply(Context context) {
        final String dir = dir(context);
        final String running = "pgrep -f \"logcat.* -f " + dir + "/log\" >/dev/null";
        if (PartsPrefs.get(context).getBoolean(PREF, true)) {
            RootShell.run("D=" + dir, "mkdir -p $D", "chmod 700 $D",
                    "find $D -type f -mmin +" + KEEP_MINUTES + " -delete",
                    "if ! " + running + "; then setsid logcat -b main -b system -b crash"
                            + " -v threadtime -f $D/log -r 1024 -n 6 '*:W' " + TAGS
                            + " >/dev/null 2>&1 & fi");
        } else {
            RootShell.run("pkill -f \"logcat.* -f " + dir + "/log\"", "rm -rf " + dir);
        }
    }

    /** Every 12 hours: deletes the files older than 2 days and checks the log still runs. */
    static void maintain(Context context) {
        apply(context);
    }
}
