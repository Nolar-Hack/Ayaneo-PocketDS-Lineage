/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Log it all": gathers the logs of the device (logcat of every buffer, kernel log, system
 * information) in a zip file of Downloads/PocketDS and offers to share it. Same idea as the
 * Log it all of AICP. The kernel log is read through xsu (root helper of the Ayaneo apps) when
 * the system user may not read it.
 */
final class LogCollector {
    private static final String TAG = "PocketDSLogs";

    private LogCollector() {}

    /** Collects on a thread and shares the zip when done. */
    static void collect(final Context context, final boolean logcat, final boolean kernel,
            final boolean info) {
        final Handler main = new Handler(Looper.getMainLooper());
        toast(main, context, "Collecting the logs...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Uri zip = write(context, logcat, kernel, info);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            share(context, zip);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Log collection failed", e);
                    toast(main, context, "Log collection failed: " + e.getMessage());
                }
            }
        }, "PocketDSLogs").start();
    }

    private static void toast(Handler main, final Context context, final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static Uri write(Context context, boolean logcat, boolean kernel, boolean info)
            throws IOException {
        final String name = "pocketds-logs-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
        final ContentResolver resolver = context.getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/PocketDS");
        final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("cannot create the file in Downloads");
        try (OutputStream out = resolver.openOutputStream(uri);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            if (logcat) {
                add(zip, "logcat.txt", run("logcat", "-b", "all", "-d", "-v", "threadtime"));
            }
            if (kernel) {
                byte[] dmesg = run("dmesg");
                if (dmesg.length == 0) dmesg = run("xsu", "dmesg");
                add(zip, "dmesg.txt", dmesg);
                add(zip, "pstore.txt", run("xsu", "sh", "-c",
                        "for f in /sys/fs/pstore/*; do echo \"== $f\"; cat \"$f\"; done"));
            }
            if (info) {
                add(zip, "getprop.txt", run("getprop"));
                add(zip, "battery.txt", run("dumpsys", "battery"));
                add(zip, "power.txt", run("dumpsys", "power"));
                add(zip, "processes.txt", run("ps", "-A", "-o", "PID,PPID,USER,STAT,NAME"));
                add(zip, "cpu-gpu.txt", run("sh", "-c",
                        "for p in /sys/devices/system/cpu/cpufreq/policy*; do echo \"$p\";"
                        + " cat $p/scaling_governor $p/scaling_cur_freq $p/scaling_max_freq;"
                        + " done"));
            }
        }
        return uri;
    }

    private static void add(ZipOutputStream zip, String entry, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(data);
        zip.closeEntry();
    }

    /** Output (stdout and stderr) of a command, empty when it cannot run. */
    private static byte[] run(String... command) {
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                final byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
            return out.toByteArray();
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "Cannot run " + command[0], e);
            return new byte[0];
        }
    }

    private static void share(Context context, Uri zip) {
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, zip);
        send.setClipData(ClipData.newRawUri("logs", zip));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final Intent chooser = Intent.createChooser(send, "Share the logs");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
        Toast.makeText(context, "Saved in Downloads/PocketDS", Toast.LENGTH_LONG).show();
    }
}
