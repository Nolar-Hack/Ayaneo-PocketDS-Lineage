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
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The emulation app list for Obtainium (RJNY/Obtainium-Emulation-Pack):
 * - loaded into Obtainium itself the first time, as if it had been imported (its apps and its
 *   settings: groups by category, compact list, daily check of the installed apps). Only while
 *   Obtainium has never been opened (no app list and no settings yet), so nothing of the user is
 *   touched and an app deleted from Obtainium does not come back;
 * - copied to Download/obtainium-emulation-pack.json once, to import it again with Obtainium >
 *   Import/Export > Import from file. Deleting the file does not bring it back.
 */
class ObtainiumPack {
    private static final String TAG = "PocketDSParts";
    private static final String ASSET = "obtainium-emulation-pack.json";
    private static final String KEY_DONE = "obtainium_pack_copied";
    private static final String KEY_LOADED = "obtainium_pack_loaded";
    private static final String PKG = "dev.imranr.obtainium";

    private ObtainiumPack() {}

    static void copyOnce(Context context) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        if (prefs.getBoolean(KEY_DONE, false) && prefs.getBoolean(KEY_LOADED, false)) return;
        new Thread(() -> {
            if (!prefs.getBoolean(KEY_DONE, false)) {
                try {
                    copy(context);
                    prefs.edit().putBoolean(KEY_DONE, true).apply();
                } catch (IOException | RuntimeException e) {
                    // Storage not ready yet: tried again the next time the process starts.
                    Log.w(TAG, "Obtainium pack not copied", e);
                }
            }
            if (!prefs.getBoolean(KEY_LOADED, false)) {
                try {
                    if (load(context)) prefs.edit().putBoolean(KEY_LOADED, true).apply();
                } catch (IOException | JSONException | RuntimeException e) {
                    Log.w(TAG, "Obtainium pack not loaded", e);
                }
            }
        }, "ObtainiumPack").start();
    }

    /**
     * Puts the apps and the settings where Obtainium keeps them (one file for each app in its
     * app_data folder, its settings in the Flutter preferences file), as the import does. The
     * root shell does the copy and gives the files to Obtainium; it leaves a mark in the cache
     * when it is done (or when Obtainium has already been used). Done when the mark is seen,
     * which may be at the next start of the process: the script can safely run again.
     */
    private static boolean load(Context context) throws IOException, JSONException {
        final File mark = new File(context.getCacheDir(), "obtainium_pack_loaded");
        if (mark.exists()) return true;
        // Read whole before decoding: a character of several bytes (Daijishō) can fall across
        // two reads.
        final java.io.ByteArrayOutputStream json = new java.io.ByteArrayOutputStream();
        try (InputStream in = context.getAssets().open(ASSET)) {
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) json.write(buf, 0, n);
        }
        final JSONObject pack = new JSONObject(json.toString("UTF-8"));
        final File root = new File(context.getCacheDir(), "obtainium_pack");
        final File apps = new File(root, "app_data");
        apps.mkdirs();
        final JSONArray list = pack.getJSONArray("apps");
        for (int i = 0; i < list.length(); i++) {
            final JSONObject app = list.getJSONObject(i);
            write(new File(apps, app.getString("id") + ".json"), app.toString());
        }
        write(new File(root, "FlutterSharedPreferences.xml"), preferences(
                pack.getJSONObject("settings")));
        final String base = "/data/user/0/" + PKG;
        final String cache = root.getPath();
        final boolean ran = RootShell.run(
                "if [ -d " + base + " ]; then",
                // Never opened: no list of apps and no settings yet.
                "if [ ! -e " + base + "/app_flutter/app_data ] && [ ! -e " + base
                        + "/shared_prefs/FlutterSharedPreferences.xml ]; then",
                "mkdir -p " + base + "/app_flutter/app_data " + base + "/shared_prefs",
                "cp " + cache + "/app_data/*.json " + base + "/app_flutter/app_data/",
                "cp " + cache + "/FlutterSharedPreferences.xml " + base + "/shared_prefs/",
                "chmod 771 " + base + "/app_flutter " + base + "/app_flutter/app_data " + base
                        + "/shared_prefs",
                "chmod 660 " + base + "/app_flutter/app_data/*.json " + base
                        + "/shared_prefs/FlutterSharedPreferences.xml",
                // Owner and SELinux label of the app folder (toybox has no --reference).
                "chown -R $(stat -c %u:%g " + base + ") " + base + "/app_flutter " + base
                        + "/shared_prefs",
                "chcon -R $(stat -c %C " + base + ") " + base + "/app_flutter " + base
                        + "/shared_prefs",
                "echo 'Obtainium Emulation Pack loaded' > /dev/kmsg",
                "fi",
                ": > " + mark.getPath() + " && chmod 666 " + mark.getPath(),
                "fi");
        return ran && mark.exists();
    }

    private static void write(File file, String text) throws IOException {
        try (OutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
        file.setReadable(true, false);
    }

    /** The settings of the pack as the Flutter preferences file of Obtainium ("flutter." keys). */
    private static String preferences(JSONObject settings) throws JSONException {
        final StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='utf-8' "
                + "standalone='yes' ?>\n<map>\n");
        final java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final Object value = settings.get(key);
            if (value instanceof Boolean) {
                xml.append("    <boolean name=\"flutter.").append(key).append("\" value=\"")
                        .append(value).append("\" />\n");
            } else if (value instanceof Integer || value instanceof Long) {
                xml.append("    <long name=\"flutter.").append(key).append("\" value=\"")
                        .append(value).append("\" />\n");
            } else if (value instanceof String) {
                xml.append("    <string name=\"flutter.").append(key).append("\">")
                        .append(escape((String) value)).append("</string>\n");
            }
            // The slider of the check interval is a decimal number, stored in a form of its own:
            // left out, the interval (updateInterval) is what counts.
        }
        return xml.append("</map>\n").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void copy(Context context) throws IOException {
        final ContentResolver resolver = context.getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, ASSET);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download");
        final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("cannot create the file in Downloads");
        try (InputStream in = context.getAssets().open(ASSET);
                OutputStream out = resolver.openOutputStream(uri)) {
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
