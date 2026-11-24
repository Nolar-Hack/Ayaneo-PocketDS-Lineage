/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Why and when the console powered off, kept for 2 days: a line when this app turns the console
 * off (with the rule that did it) and a line at each start with what Android noted as the reason
 * of the previous stop (battery empty, too hot, crash, restart, power lost). The logcat of the
 * stop is lost at the restart; this file is not.
 */
final class ShutdownLog {
    private static final String TAG = "PocketDSPowerOff";
    private static final String FILE = "shutdown-log.txt";
    private static final String KEY_BOOT = "shutdown_log_boot";
    private static final long KEEP_MS = 2 * 86_400_000L;
    private static final String TIME = "yyyy-MM-dd HH:mm";

    private ShutdownLog() {}

    private static File file(Context context) {
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), FILE);
    }

    /** Adds a line (and drops the ones older than 2 days). */
    static synchronized void record(Context context, String text) {
        final SimpleDateFormat format = new SimpleDateFormat(TIME, Locale.US);
        final long now = System.currentTimeMillis();
        final StringBuilder out = new StringBuilder();
        for (String line : read(context).split("\n")) {
            if (line.length() < TIME.length() + 2) continue;
            try {
                if (now - format.parse(line.substring(0, TIME.length())).getTime() > KEEP_MS) {
                    continue;
                }
            } catch (ParseException e) {
                continue;
            }
            out.append(line).append('\n');
        }
        out.append(format.format(new Date(now))).append("  ").append(text).append('\n');
        try {
            Files.write(file(context).toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Cannot write the power-off log", e);
        }
    }

    /** At each start: the reason Android gives for the previous stop, once per boot. */
    static void noteBoot(Context context) {
        // sys.boot.reason is set as soon as the system starts; the history is only updated once
        // the boot is over (the app starts before that and would read the entry before).
        String reason = android.os.SystemProperties.get("sys.boot.reason", "").trim();
        final String history = android.os.SystemProperties.get(
                "persist.sys.boot.reason.history", "");
        String latest = history.split("\n")[0].trim();
        if (reason.isEmpty()) {
            if (latest.isEmpty()) return;
            final int comma = latest.lastIndexOf(',');
            reason = comma > 0 ? latest.substring(0, comma) : latest;
        }
        // one line for a boot: the reason and the time of the boot identify it
        latest = reason + "@" + (System.currentTimeMillis()
                - android.os.SystemClock.elapsedRealtime()) / 60_000;
        final SharedPreferences prefs = PartsPrefs.get(context);
        if (latest.equals(prefs.getString(KEY_BOOT, ""))) return;
        prefs.edit().putString(KEY_BOOT, latest).apply();
        record(context, "Started. Android noted the previous stop as: " + describe(reason)
                + " (" + reason + ")");
    }

    private static String describe(String reason) {
        if (reason.equals("shutdown,pocketds")) return "powered off by PocketDS Extra";
        if (reason.startsWith("shutdown,userrequested")) return "powered off from the menu";
        if (reason.startsWith("shutdown,battery")) return "battery empty";
        if (reason.startsWith("shutdown,thermal")) return "too hot";
        if (reason.contains("panic")) return "crash of the kernel";
        if (reason.contains("watchdog")) return "freeze of the system";
        if (reason.startsWith("reboot")) return "restart";
        if (reason.startsWith("shutdown")) return "powered off";
        if (reason.equals("cold") || reason.equals("unknown")) {
            return "power lost, or the power button held";
        }
        return reason;
    }

    /** The whole log, oldest line first. */
    static synchronized String read(Context context) {
        try {
            return new String(Files.readAllBytes(file(context).toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The last line, for the row of the Tools page. */
    static String summary(Context context) {
        final String text = read(context).trim();
        if (text.isEmpty()) return "Nothing yet";
        return text.substring(text.lastIndexOf('\n') + 1);
    }
}
