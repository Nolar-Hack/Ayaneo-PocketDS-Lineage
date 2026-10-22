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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Backup and restore of the settings of PocketDS Extra (options, automation rules) and of the
 * performance profiles (CPU / GPU frequencies, fan) in one JSON file of Downloads/PocketDS. The
 * state kept while a rule is active ("rule_saved_*") and the one-time flags are not saved.
 */
final class BackupManager {
    private static final int VERSION = 1;

    private BackupManager() {}

    private static boolean skip(String key) {
        return key.startsWith("rule_saved_") || key.equals("obtainium_pack_copied");
    }

    /** Writes the backup in Downloads/PocketDS and returns its name. */
    static String export(Context context) throws IOException {
        try {
            final JSONObject root = new JSONObject();
            root.put("app", "PocketDS Extra");
            root.put("version", VERSION);
            final JSONObject prefs = new JSONObject();
            for (Map.Entry<String, ?> e : PartsPrefs.get(context).getAll().entrySet()) {
                if (skip(e.getKey())) continue;
                final Object v = e.getValue();
                final JSONObject item = new JSONObject();
                if (v instanceof Boolean) {
                    item.put("t", "b").put("v", (Boolean) v);
                } else if (v instanceof Integer) {
                    item.put("t", "i").put("v", (Integer) v);
                } else if (v instanceof Long) {
                    item.put("t", "l").put("v", (Long) v);
                } else if (v instanceof Float) {
                    item.put("t", "f").put("v", ((Float) v).doubleValue());
                } else if (v instanceof String) {
                    item.put("t", "s").put("v", (String) v);
                } else {
                    continue;
                }
                prefs.put(e.getKey(), item);
            }
            root.put("prefs", prefs);
            final String profiles = readProfiles(context);
            if (profiles != null) root.put("performance_profiles", profiles);

            final String name = "pocketds-backup-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + ".json";
            final ContentResolver resolver = context.getContentResolver();
            final ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/PocketDS");
            final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("cannot create the file in Downloads");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return name;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Restores a backup file; returns what was restored, for the user. */
    static String restore(Context context, Uri uri) throws IOException {
        try {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open the file");
                final byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
            }
            final JSONObject root = new JSONObject(buffer.toString("UTF-8"));
            if (!"PocketDS Extra".equals(root.optString("app")) || root.optInt("version") != VERSION) {
                throw new IOException("this is not a PocketDS Extra backup");
            }
            final JSONObject prefs = root.getJSONObject("prefs");
            final SharedPreferences.Editor editor = PartsPrefs.get(context).edit();
            final JSONArray keys = prefs.names();
            int count = 0;
            for (int i = 0; keys != null && i < keys.length(); i++) {
                final String key = keys.getString(i);
                if (skip(key)) continue;
                final JSONObject item = prefs.getJSONObject(key);
                switch (item.getString("t")) {
                    case "b": editor.putBoolean(key, item.getBoolean("v")); break;
                    case "i": editor.putInt(key, item.getInt("v")); break;
                    case "l": editor.putLong(key, item.getLong("v")); break;
                    case "f": editor.putFloat(key, (float) item.getDouble("v")); break;
                    case "s": editor.putString(key, item.getString("v")); break;
                    default: continue;
                }
                count++;
            }
            editor.apply();
            String result = count + " settings restored";
            // "aya_performance" is the name the first backups gave to the same file.
            final String key = root.has("performance_profiles") ? "performance_profiles"
                    : root.has("aya_performance") ? "aya_performance" : null;
            if (key != null) {
                result += writeProfiles(context, root.getString(key))
                        ? ", performance profiles restored"
                        : ", performance profiles could not be written";
            }
            return result;
        } catch (JSONException e) {
            throw new IOException("invalid backup file", e);
        }
    }

    /** The performance profiles edited by the user (none: the built-in ones are in use). */
    private static String readProfiles(Context context) {
        try {
            final File file = new File(context.getFilesDir(), PerformanceProfiles.FILE);
            if (file.canRead()) {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            // no profile file
        }
        return null;
    }

    private static boolean writeProfiles(Context context, String json) {
        try {
            new JSONObject(json); // refuses anything that is not a JSON object
            java.nio.file.Files.write(new File(context.getFilesDir(),
                    PerformanceProfiles.FILE).toPath(), json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (JSONException | IOException | RuntimeException e) {
            return false;
        }
    }
}
