/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * The two things the image carries from outside, updated without a new system build, only when
 * the user asks (System update > Add-ons): the TurnipZ GPU driver and the Obtainium Emulation
 * Pack. The release is read from GitHub over https and the file must match the sha256 that
 * GitHub lists for it (it guards the download, not the author: nothing is signed). The driver is
 * only a file in the driver folder of the emulators, which choose it themselves; the app list is
 * saved in Downloads to import in Obtainium, nothing is changed in Obtainium.
 */
final class Addons {
    private static final String TURNIP_REPO = "AlexSanz0902/turnip-sd865-z";
    private static final String PACK_REPO = "RJNY/Obtainium-Emulation-Pack";
    private static final Pattern TURNIP_FILE = Pattern.compile("TurnipZ-[0-9][0-9A-Za-z.\\-]*\\.zip");
    private static final Pattern PACK_FILE = Pattern.compile("[A-Za-z0-9._\\-]+\\.json");
    private static final String PACK_ASSET = "obtainium-emulation-pack.json";
    private static final String KEY_PACK_DIGEST = "addons_pack_digest";
    private static final long MAX_BYTES = 30L * 1024 * 1024;

    private Addons() {}

    /** A release on GitHub and its file. */
    static final class Release {
        String version;   // the tag, without the leading v
        String file;
        String url;
        long size;
        String sha256;
    }

    /** What the check found; a null release means the check failed (no network, no answer). */
    static final class Status {
        Release turnip;
        Release pack;
        boolean turnipNewer;
        boolean packNewer;
    }

    static Status check(Context context) {
        final Status status = new Status();
        status.turnip = latest(TURNIP_REPO, ".zip", TURNIP_FILE);
        status.pack = latest(PACK_REPO, ".json", PACK_FILE);
        status.turnipNewer = status.turnip != null
                && compare(status.turnip.version, GpuDriverPack.currentVersion(context)) > 0;
        if (status.pack != null) {
            final String have = PartsPrefs.get(context).getString(KEY_PACK_DIGEST, bundledPackDigest(
                    context));
            status.packNewer = !status.pack.sha256.equals(have);
        }
        return status;
    }

    /** Downloads the driver; true when it is stored and the emulators will get it. */
    static String updateDriver(Context context, Release release) {
        final File dir = GpuDriverPack.downloadDir(context);
        dir.mkdirs();
        final File target = new File(dir, release.file);
        final String error = download(release, target);
        if (error != null) return error;
        GpuDriverPack.refresh();
        return null;
    }

    /** Saves the newest app list in Downloads. */
    static String savePack(Context context, Release release) {
        final File temp = new File(context.getCacheDir(), release.file);
        final String error = download(release, temp);
        if (error != null) return error;
        final ContentResolver resolver = context.getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, release.file);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download");
        final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return "Cannot create the file in Downloads";
        try (InputStream in = new FileInputStream(temp);
                OutputStream out = resolver.openOutputStream(uri)) {
            copy(in, out);
        } catch (IOException e) {
            return "Cannot save the file: " + e.getMessage();
        } finally {
            temp.delete();
        }
        PartsPrefs.get(context).edit().putString(KEY_PACK_DIGEST, release.sha256).apply();
        return null;
    }

    private static String bundledPackDigest(Context context) {
        try (InputStream in = context.getAssets().open(PACK_ASSET)) {
            return hex(digestOf(in));
        } catch (IOException e) {
            return "";
        }
    }

    private static Release latest(String repo, String suffix, Pattern accepted) {
        try {
            final JSONObject json = new JSONObject(new String(get("https://api.github.com/repos/"
                    + repo + "/releases/latest", 1 << 20), "UTF-8"));
            final JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                final JSONObject asset = assets.getJSONObject(i);
                final String name = asset.getString("name");
                final String digest = asset.optString("digest", "");
                if (!name.endsWith(suffix) || !accepted.matcher(name).matches()
                        || !digest.startsWith("sha256:")) {
                    continue;
                }
                final Release release = new Release();
                release.version = json.getString("tag_name").replaceFirst("^v", "");
                release.file = name;
                release.url = asset.getString("browser_download_url");
                release.size = asset.getLong("size");
                release.sha256 = digest.substring("sha256:".length()).toLowerCase(
                        java.util.Locale.ROOT);
                if (release.size > MAX_BYTES || !release.url.startsWith("https://")) continue;
                return release;
            }
        } catch (Exception e) {
            // no network or no answer: the caller says so
        }
        return null;
    }

    /** Downloads over https into the target and checks the sha256; null when good. */
    private static String download(Release release, File target) {
        final File part = new File(target.getPath() + ".part");
        try {
            final byte[] data = get(release.url, (int) MAX_BYTES);
            if (data.length != release.size) return "The download is incomplete";
            final String sum = hex(MessageDigest.getInstance("SHA-256").digest(data));
            if (!sum.equals(release.sha256)) return "The file does not match its checksum";
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(data);
            }
            if (release.file.endsWith(".zip")) new java.util.zip.ZipFile(part).close();
            if (!part.renameTo(target)) return "Cannot store the file";
            target.setReadable(true, false);
            return null;
        } catch (Exception e) {
            part.delete();
            return "Download failed: " + e.getMessage();
        }
    }

    private static byte[] get(String url, int max) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PocketDS-Extra/" + Updates.version());
        try (InputStream in = c.getInputStream()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > max) throw new IOException("file too big");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        final byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    private static byte[] digestOf(InputStream in) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02x", b));
        return s.toString();
    }

    /** Compares dotted versions ("0.4.1" > "0.4.0"). */
    static int compare(String a, String b) {
        final String[] x = a.split("[.\\-]");
        final String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final int p = i < x.length ? number(x[i]) : 0;
            final int q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
