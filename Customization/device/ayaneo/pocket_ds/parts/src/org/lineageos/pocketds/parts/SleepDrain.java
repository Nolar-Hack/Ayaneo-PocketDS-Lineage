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
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the battery lost while the console slept: from the screen off to the screen on, the charge
 * counter of the battery before and after, only on battery (a cable would hide the loss). The last
 * sleeps are kept for 2 days, with the wake locks held (they keep the processor awake). Answers
 * "why did I lose 16 % overnight".
 */
final class SleepDrain {
    private static final String KEY = "sleep_drain";
    private static final String KEY_OPEN = "sleep_drain_open";
    private static final long KEEP_MS = 2 * 86_400_000L;
    private static final long MIN_MS = 10 * 60_000L;
    private static final String SUPPLY = "/sys/class/power_supply/battery/";

    private SleepDrain() {}

    static void start(Context context) {
        final Context app = context.getApplicationContext();
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    open(app);
                } else {
                    close(app);
                }
            }
        }, filter);
    }

    private static long counter() {
        return PerfOverlayController.readLong(SUPPLY + "charge_counter", -1);
    }

    private static void open(Context context) {
        // wall clock, the console may be off between the two: "time|counter|plugged"
        PartsPrefs.get(context).edit().putString(KEY_OPEN, System.currentTimeMillis() + "|"
                + counter() + "|" + (PartsPrefs.pluggedIn(context) ? 1 : 0)).apply();
    }

    private static void close(Context context) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        final String[] open = prefs.getString(KEY_OPEN, "").split("\\|");
        prefs.edit().remove(KEY_OPEN).apply();
        if (open.length != 3) return;
        try {
            final long start = Long.parseLong(open[0]);
            final long before = Long.parseLong(open[1]);
            final long after = counter();
            final long ms = System.currentTimeMillis() - start;
            if (ms < MIN_MS || before <= 0 || after <= 0 || open[2].equals("1")
                    || PartsPrefs.pluggedIn(context)) {
                return;
            }
            final long full = PerfOverlayController.readLong(SUPPLY + "charge_full", 0);
            // percent of the capacity in tenths, positive when the battery lost charge
            final long tenths = full > 0 ? (before - after) * 1000 / full : 0;
            final StringBuilder kept = new StringBuilder();
            final long now = System.currentTimeMillis();
            for (String entry : prefs.getString(KEY, "").split(";")) {
                final String[] p = entry.split("\\|");
                if (p.length == 3 && now - parse(p[0]) < KEEP_MS) kept.append(entry).append(';');
            }
            kept.append(start).append('|').append(ms).append('|').append(tenths);
            prefs.edit().putString(KEY, kept.toString()).apply();
        } catch (NumberFormatException e) {
            // an entry that cannot be read is dropped
        }
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One line a sleep, the newest first, for the page. */
    static List<String> lines(Context context) {
        final List<String> out = new ArrayList<>();
        final String[] entries = PartsPrefs.get(context).getString(KEY, "").split(";");
        for (int i = entries.length - 1; i >= 0; i--) {
            final String[] p = entries[i].split("\\|");
            if (p.length != 3) continue;
            final long start = parse(p[0]);
            final long ms = parse(p[1]);
            final long tenths = parse(p[2]);
            final double hours = ms / 3_600_000.0;
            out.add(String.format(Locale.US, "%s · %s · %s %.1f %% (%.1f %% an hour)",
                    android.text.format.DateFormat.format("EEE HH:mm", start),
                    duration(ms), tenths >= 0 ? "lost" : "gained", Math.abs(tenths) / 10.0,
                    hours > 0 ? Math.abs(tenths) / 10.0 / hours : 0));
        }
        return out;
    }

    private static String duration(long ms) {
        final long minutes = ms / 60_000;
        return minutes >= 60 ? minutes / 60 + " h " + minutes % 60 + " min" : minutes + " min";
    }

    /** The wake locks held now, "name (app)", from the dump of the power service. */
    static List<String> wakeLocks(Context context) {
        final List<String> out = new ArrayList<>();
        try {
            final Process process = new ProcessBuilder("dumpsys", "power")
                    .redirectErrorStream(true).start();
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                final byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            }
            final Pattern lock = Pattern.compile(
                    "PARTIAL_WAKE_LOCK\\s+'([^']+)'.*?\\(uid=(\\d+)");
            for (String line : bytes.toString("UTF-8").split("\n")) {
                final Matcher m = lock.matcher(line);
                if (!m.find()) continue;
                final String app = context.getPackageManager().getNameForUid(
                        Integer.parseInt(m.group(2)));
                final String entry = m.group(1) + " (" + (app == null ? "uid " + m.group(2) : app)
                        + ")";
                if (!out.contains(entry)) out.add(entry);
            }
        } catch (java.io.IOException | RuntimeException e) {
            // unreadable: the page says so
        }
        return out;
    }
}
