/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Counts the consoles by version of the image, for the statistics of the developer (which versions
 * are still in use, so how many incremental packages to keep). Once a day, from the update job: the
 * random number of this console (one per console, kept until a wipe) and the version are sent to
 * ro.pocketds.stats_url, nothing else.
 *
 * Built to stay within the audience measurement that needs no consent (CNIL): the identifier is
 * random (not taken from the console), made by the app, renewed every 12 months and lost at a wipe;
 * the server keeps no address and forgets an identifier not seen for 30 days; the user is told
 * (System update page, README) and can switch it off at any time (PREF_ENABLED).
 */
final class VersionCount {
    static final String PREF_ENABLED = "version_count";
    private static final String KEY_ID = "version_count_id";
    private static final String KEY_ID_SINCE = "version_count_id_since";
    private static final String KEY_SENT_DAY = "version_count_sent_day";
    /** The previous number, sent once after a renewal so the server forgets it. */
    private static final String KEY_REPLACES = "version_count_replaces";
    private static final long DAY_MS = 86_400_000L;
    private static final long ID_LIFETIME_MS = 365 * DAY_MS;

    private VersionCount() {}

    /** Sends once a day (call from a thread). No address set in the build: does nothing. */
    static void send(Context context) {
        final String url = SystemProperties.get("ro.pocketds.stats_url", "");
        final SharedPreferences prefs = PartsPrefs.get(context);
        if (url.isEmpty()) return;
        if (!prefs.getBoolean(PREF_ENABLED, true)) {
            // switched off: the identifier is dropped (a new one is made if switched on again)
            if (prefs.contains(KEY_ID)) forget(context);
            return;
        }
        final String version = Updates.version();
        if (version == null || version.isEmpty()) return;
        final long now = System.currentTimeMillis();
        final long today = now / DAY_MS;
        if (prefs.getLong(KEY_SENT_DAY, -1) == today) return;

        // One number for this console, kept until a wipe; renewed once a year (the limit for an
        // audience count without consent). The old one is sent once with the new one, so that the
        // server drops it at once instead of counting the console twice for 30 days.
        String id = prefs.getString(KEY_ID, "");
        String replaces = prefs.getString(KEY_REPLACES, "");
        if (id.isEmpty() || now - prefs.getLong(KEY_ID_SINCE, 0) > ID_LIFETIME_MS) {
            replaces = id;
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_ID, id).putLong(KEY_ID_SINCE, now)
                    .putString(KEY_REPLACES, replaces).apply();
        }
        final byte[] body = ("{\"id\":\"" + id + "\",\"version\":\"" + version + "\""
                + (replaces.isEmpty() ? "" : ",\"replaces\":\"" + replaces + "\"") + "}")
                .getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            if (c.getResponseCode() / 100 == 2) {
                prefs.edit().putLong(KEY_SENT_DAY, today).remove(KEY_REPLACES).apply();
            }
        } catch (Exception e) {
            // tried again at the next check
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Switched off: a new identifier is made if it is switched on again. */
    static void forget(Context context) {
        PartsPrefs.get(context).edit().remove(KEY_ID).remove(KEY_ID_SINCE)
                .remove(KEY_SENT_DAY).remove(KEY_REPLACES).apply();
    }

    /** Not carried by the backups: a console restored elsewhere must not take this identifier. */
    static boolean isPrivateKey(String key) {
        return key.equals(KEY_ID) || key.equals(KEY_ID_SINCE) || key.equals(KEY_SENT_DAY)
                || key.equals(KEY_REPLACES);
    }
}
