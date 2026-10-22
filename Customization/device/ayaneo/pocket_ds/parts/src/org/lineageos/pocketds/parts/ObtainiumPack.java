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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies the emulation app list for Obtainium (RJNY/Obtainium-Emulation-Pack) to
 * Download/obtainium-emulation-pack.json once, so that it can be imported with Obtainium >
 * Import/Export > Import from file. Done once only: deleting the file does not bring it back.
 */
class ObtainiumPack {
    private static final String TAG = "PocketDSParts";
    private static final String ASSET = "obtainium-emulation-pack.json";
    private static final String KEY_DONE = "obtainium_pack_copied";

    private ObtainiumPack() {}

    static void copyOnce(Context context) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        if (prefs.getBoolean(KEY_DONE, false)) return;
        new Thread(() -> {
            try {
                copy(context);
                prefs.edit().putBoolean(KEY_DONE, true).apply();
            } catch (IOException | RuntimeException e) {
                // Storage not ready yet: tried again the next time the process starts.
                Log.w(TAG, "Obtainium pack not copied", e);
            }
        }, "ObtainiumPack").start();
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
