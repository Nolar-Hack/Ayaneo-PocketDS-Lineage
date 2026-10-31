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
import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * System updates of the image. {@code update.json} (made by scripts/make-ota.sh, published with the
 * GitHub release) lists the newest version with its complete package and, when it exists, an
 * incremental one that only applies to the build named in its "from". This class reads it, tells
 * whether it is newer than the installed version (ro.pocketds.version.code), downloads the right
 * package and gives it to update_engine (Virtual A/B: it is installed in the background, the
 * console only restarts when the user says so).
 */
final class Updates {
    static final String TAG = "PocketDSUpdates";
    static final String DEFAULT_URL = "https://github.com/Nolar-Hack/"
            + "Ayaneo-PocketDS-Lineage-Releases/releases/latest/download/update.json";

    static final String PREF_AUTO_CHECK = "update_auto_check";
    static final String PREF_AUTO_INSTALL = "update_auto_install";
    private static final String PREFS = "updates";
    private static final String KEY_URL = "url";
    private static final String KEY_JSON = "json";
    private static final String KEY_CHECKED = "checked";
    private static final String KEY_READY_CODE = "ready_code";
    private static final String KEY_NOTIFIED = "notified_code";

    static final String CHANNEL = "pocketds_updates";
    static final int NOTIFICATION_ID = 40;

    static final int IDLE = 0;
    static final int DOWNLOADING = 1;
    static final int APPLYING = 2;
    static final int FAILED = 3;

    // Progress of the running download/installation, read by the screen.
    static volatile int sState = IDLE;
    static volatile int sPercent;
    static volatile String sMessage = "";

    private Updates() {}

    /** One package of a release. */
    static final class Pack {
        String file;
        String url;
        long size;
        String sha256;
        String from;
    }

    /** The newest release described by update.json. */
    static final class Info {
        String version;
        int code;
        String date;
        String notes;
        Pack full;
        Pack incremental;

        /** The incremental package when it starts from exactly the installed build, else the full. */
        Pack pick() {
            if (incremental != null && incremental.from != null
                    && incremental.from.equals(buildId())) {
                return incremental;
            }
            return full;
        }
    }

    static String version() {
        return SystemProperties.get("ro.pocketds.version", "unknown");
    }

    static int versionCode() {
        return SystemProperties.getInt("ro.pocketds.version.code", 0);
    }

    static String buildId() {
        return SystemProperties.get("ro.build.version.incremental", "");
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The update.json address: the one set in the app, else the system property, else ours. */
    static String url(Context context) {
        final String chosen = prefs(context).getString(KEY_URL, "");
        if (!chosen.isEmpty()) return chosen;
        final String custom = SystemProperties.get("persist.pocketds.update_url", "");
        if (!custom.isEmpty()) return custom;
        // The address of this build (RELEASE_REPO of pocketds.conf), else our own repository.
        final String built = SystemProperties.get("ro.pocketds.update_url", "");
        return built.isEmpty() ? DEFAULT_URL : built;
    }

    static boolean customUrl(Context context) {
        return !prefs(context).getString(KEY_URL, "").isEmpty();
    }

    /**
     * Sets the update source. Accepts "owner/repo" (the latest GitHub release of that repository)
     * or a full https address of an update.json; empty goes back to the default.
     *
     * @return false when the text is neither
     */
    static boolean setUrl(Context context, String text) {
        String value = text == null ? "" : text.trim();
        if (value.matches("[\\w.-]+/[\\w.-]+")) {
            value = "https://github.com/" + value + "/releases/latest/download/update.json";
        } else if (!value.isEmpty() && !value.startsWith("https://")) {
            return false;
        }
        // The release found at the old address no longer applies.
        prefs(context).edit().putString(KEY_URL, value).remove(KEY_JSON).remove(KEY_CHECKED)
                .apply();
        return true;
    }

    static File dir(Context context) {
        final File dir = new File(context.getFilesDir(), "ota");
        dir.mkdirs();
        return dir;
    }

    // --- update.json

    static Info parse(String json) throws Exception {
        final JSONObject o = new JSONObject(json);
        final Info info = new Info();
        info.version = o.getString("version");
        info.code = o.getInt("versionCode");
        info.date = o.optString("date", "");
        info.notes = o.optString("notes", "");
        info.full = pack(o.getJSONObject("full"));
        final JSONObject inc = o.optJSONObject("incremental");
        if (inc != null) {
            info.incremental = pack(inc);
            info.incremental.from = inc.optString("from", null);
        }
        return info;
    }

    private static Pack pack(JSONObject o) throws Exception {
        final Pack p = new Pack();
        p.file = o.getString("file");
        p.url = o.getString("url");
        p.size = o.getLong("size");
        p.sha256 = o.getString("sha256").toLowerCase();
        return p;
    }

    /** The release saved by the last check, if it is newer than what is installed. */
    static Info available(Context context) {
        final String json = prefs(context).getString(KEY_JSON, null);
        if (json == null) return null;
        try {
            final Info info = parse(json);
            return info.code > versionCode() ? info : null;
        } catch (Exception e) {
            return null;
        }
    }

    static long lastChecked(Context context) {
        return prefs(context).getLong(KEY_CHECKED, 0);
    }

    /** An update is installed and waits for the restart. */
    static boolean restartPending(Context context) {
        final int ready = prefs(context).getInt(KEY_READY_CODE, 0);
        return ready > versionCode();
    }

    static void markReady(Context context, int code) {
        prefs(context).edit().putInt(KEY_READY_CODE, code).apply();
    }

    /**
     * Downloads update.json (call from a thread). Returns the newer release, null if there is none.
     *
     * @throws Exception when the page cannot be read
     */
    static Info check(Context context) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url(context)).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "PocketDS-Extra/" + version());
        try {
            if (c.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + c.getResponseCode());
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            try (InputStream in = c.getInputStream()) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 1 << 20) throw new java.io.IOException("update.json too big");
                }
            }
            final String json = out.toString("UTF-8");
            parse(json); // refuses a page that is not an update.json
            prefs(context).edit().putString(KEY_JSON, json)
                    .putLong(KEY_CHECKED, System.currentTimeMillis()).apply();
        } finally {
            c.disconnect();
        }
        return available(context);
    }

    /** Tells the user once per release that an update is out. */
    static void notifyAvailable(Context context, Info info) {
        final SharedPreferences p = prefs(context);
        if (p.getInt(KEY_NOTIFIED, 0) == info.code) return;
        p.edit().putInt(KEY_NOTIFIED, info.code).apply();
        post(context, "Update " + info.version + " available",
                "Open System update to download and install it.");
    }

    static void post(Context context, String title, String text) {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "System updates",
                NotificationManager.IMPORTANCE_DEFAULT));
        final Intent open = new Intent(context, SectionActivity.class)
                .putExtra(SectionActivity.EXTRA_SECTION, SectionActivity.UPDATES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(PendingIntent.getActivity(context, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true).build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    // --- download

    interface Progress {
        void percent(int percent);
    }

    /**
     * Downloads the package into {@code target}, resuming a partial file, and checks its sha256.
     *
     * @return null when the file is good, else the reason
     */
    static String download(Pack pack, File target, Progress progress) {
        final File part = new File(target.getPath() + ".part");
        try {
            if (target.exists() && sha256(target).equals(pack.sha256)) return null;
            final long free = target.getParentFile().getUsableSpace();
            final long have = part.exists() ? part.length() : 0;
            // The installation needs room in /data as well (the new version is written next to the old).
            if (free < (pack.size - have) + pack.size / 2) return "Not enough free space";

            long offset = have;
            final HttpURLConnection c = (HttpURLConnection) new URL(pack.url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "PocketDS-Extra/" + version());
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            final int code = c.getResponseCode();
            if (code == 200) offset = 0; // the server ignored the range: start again
            else if (code != 206) return "Download refused (HTTP " + code + ")";
            try (InputStream in = c.getInputStream();
                    RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                out.seek(offset);
                out.setLength(offset);
                final byte[] buffer = new byte[1 << 16];
                long done = offset;
                int last = -1;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    done += n;
                    final int percent = (int) (done * 100 / Math.max(1, pack.size));
                    if (percent != last) {
                        last = percent;
                        progress.percent(percent);
                    }
                }
            } finally {
                c.disconnect();
            }
            if (part.length() != pack.size) return "Download incomplete, try again";
            if (!sha256(part).equals(pack.sha256)) {
                part.delete();
                return "The file is damaged (checksum), try again";
            }
            target.delete();
            if (!part.renameTo(target)) return "Cannot save the file";
            return null;
        } catch (Exception e) {
            Log.w(TAG, "download failed", e);
            return "No connection (" + e.getClass().getSimpleName() + ")";
        }
    }

    static String sha256(File file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            final byte[] buffer = new byte[1 << 20];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // --- the payload inside the package

    /** Where update_engine finds the payload: offset and size in the zip, and its properties. */
    static final class Payload {
        long offset;
        long size;
        String[] headers;
    }

    /**
     * Reads the package: payload.bin must be stored (not compressed), update_engine reads it in place.
     * The offset comes from the local header of the entry, found through the central directory.
     */
    static Payload payload(File zip) throws Exception {
        final Payload payload = new Payload();
        try (RandomAccessFile f = new RandomAccessFile(zip, "r")) {
            final long length = f.length();
            final int tail = (int) Math.min(length, 66000);
            final byte[] end = new byte[tail];
            f.seek(length - tail);
            f.readFully(end);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (u32(end, i) == 0x06054b50L) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) throw new java.io.IOException("Not a zip");
            final int entries = u16(end, eocd + 10);
            final long cdSize = u32(end, eocd + 12);
            final long cdOffset = u32(end, eocd + 16);
            final byte[] cd = new byte[(int) cdSize];
            f.seek(cdOffset);
            f.readFully(cd);
            int p = 0;
            for (int i = 0; i < entries && p + 46 <= cd.length; i++) {
                final int method = u16(cd, p + 10);
                final long csize = u32(cd, p + 20);
                final int nameLen = u16(cd, p + 28);
                final int extraLen = u16(cd, p + 30);
                final int commentLen = u16(cd, p + 32);
                final long localOffset = u32(cd, p + 42);
                final String name = new String(cd, p + 46, nameLen, StandardCharsets.UTF_8);
                if (name.equals("payload.bin")) {
                    if (method != 0) throw new java.io.IOException("payload.bin is compressed");
                    final byte[] local = new byte[30];
                    f.seek(localOffset);
                    f.readFully(local);
                    payload.offset = localOffset + 30 + u16(local, 26) + u16(local, 28);
                    payload.size = csize;
                }
                p += 46 + nameLen + extraLen + commentLen;
            }
        }
        if (payload.size == 0) throw new java.io.IOException("No payload.bin in the package");
        final List<String> headers = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip)) {
            final ZipEntry props = z.getEntry("payload_properties.txt");
            if (props == null) throw new java.io.IOException("No payload_properties.txt");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    z.getInputStream(props), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) headers.add(line);
            }
        }
        payload.headers = headers.toArray(new String[0]);
        return payload;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xffL) | (b[i + 1] & 0xffL) << 8 | (b[i + 2] & 0xffL) << 16
                | (b[i + 3] & 0xffL) << 24;
    }

    static String size(long bytes) {
        return bytes >= 1 << 30 ? String.format("%.1f GB", bytes / (double) (1 << 30))
                : String.format("%d MB", bytes >> 20);
    }
}
