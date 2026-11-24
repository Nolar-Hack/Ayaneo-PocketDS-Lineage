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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Log it all": gathers the logs of the device (logcat of every buffer, kernel log, system
 * information) in a zip file of Downloads/PocketDS and offers to share it. Same idea as the
 * Log it all of AICP. The kernel log and the state of the root modules are read through the root
 * shell of KernelSU. The zip is made to be posted: the serial number and the MAC addresses are
 * blanked (the logcat can still hold other personal things, such as Wi-Fi names).
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
            // first, what a person reading the report needs: which version, which root modules
            add(zip, "report.txt", report(context).getBytes(StandardCharsets.UTF_8));
            if (logcat) {
                add(zip, "logcat.txt", run("logcat", "-b", "all", "-d", "-v", "threadtime"));
                // what the system said before the last restarts (warnings, 2 days at most)
                add(zip, "syslog.txt", runRoot("for f in " + SystemLog.dir(context)
                        + "/log*; do echo \"=== $f\"; cat \"$f\"; done"));
                add(zip, "shutdown.txt", ShutdownLog.read(context).getBytes(
                        StandardCharsets.UTF_8));
            }
            if (kernel) {
                byte[] dmesg = run("dmesg");
                if (dmesg.length == 0) dmesg = runRoot("dmesg");
                add(zip, "dmesg.txt", dmesg);
                add(zip, "pstore.txt", runRoot(
                        "for f in /sys/fs/pstore/*; do echo \"== $f\"; cat \"$f\"; done"));
            }
            if (info) {
                add(zip, "root.txt", runRoot(ROOT_STATE));
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
        zip.write(scrub(data));
        zip.closeEntry();
    }

    /** Serial number and MAC addresses blanked: these files are posted in public channels. */
    private static byte[] scrub(byte[] data) {
        final String text = new String(data, StandardCharsets.UTF_8)
                .replaceAll("(androidboot\\.(serialno|wifimac|btmac)=)\\S+", "$1<removed>")
                .replaceAll("(\\[(ro\\.serialno|ro\\.boot\\.serialno|ro\\.boot\\.wifimac|"
                        + "ro\\.boot\\.btmac)\\]: \\[)[^\\]]*", "$1<removed>")
                .replaceAll("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b", "<mac removed>");
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The version of the system and of the app, to read first in a bug report. */
    private static String report(Context context) {
        final StringBuilder s = new StringBuilder();
        s.append("PocketDS ").append(Updates.version()).append('\n');
        s.append("Build ").append(android.os.Build.VERSION.INCREMENTAL).append('\n');
        s.append("Fingerprint ").append(android.os.Build.FINGERPRINT).append('\n');
        s.append("Android ").append(android.os.Build.VERSION.RELEASE).append(" (security patch ")
                .append(android.os.Build.VERSION.SECURITY_PATCH).append(")\n");
        s.append("Kernel ").append(System.getProperty("os.version")).append('\n');
        s.append("Date ").append(new Date()).append("\n\n");
        s.append("Root modules (more in root.txt):\n").append(
                new String(runRoot(MODULES), StandardCharsets.UTF_8));
        return s.toString();
    }

    /** The root state as text, read through the root shell; empty when root is not given. */
    static String rootState() {
        return new String(runRoot(ROOT_STATE), StandardCharsets.UTF_8);
    }

    /** One line a module: id, version, and [off] when it is disabled. */
    private static final String MODULES =
            "for d in /data/adb/modules/*; do [ -f \"$d/module.prop\" ] || continue;"
            + " echo \"$(basename $d) $(sed -n 's/^version=//p' $d/module.prop)"
            + "$([ -f $d/disable ] && echo ' [off]')\"; done";

    /** KernelSU, SUSFS and the Zygisk state: what `adb root` used to be needed for. */
    private static final String ROOT_STATE =
            "K=/data/adb/ksud; [ -x $K ] || { echo 'ksud not there yet (open KernelSU Next once)';"
            + " exit 0; }; echo '== ksud'; $K --version; echo '== susfs'; $K susfs support;"
            + " $K susfs version; $K susfs variant; $K susfs autoload status;"
            + " echo '== features'; $K feature list; echo '== modules'; " + MODULES
            + "; echo '== zygisk'; ps -A | grep -E 'zygiskd|zygisk-ptrace';"
            + " echo '== profile of PocketDS Extra'; $K profile get org.lineageos.pocketds.parts";

    /**
     * Output of a shell command run as root (KernelSU's su). Starting su sometimes fails right
     * after the boot ("error=13"), so it is tried again; empty when root is not given.
     */
    private static byte[] runRoot(String script) {
        for (int attempt = 0; attempt < 4; attempt++) {
            final byte[] out = run("/system/bin/su", "-c", script);
            if (out.length > 0) return out;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return out;
            }
        }
        return new byte[0];
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
