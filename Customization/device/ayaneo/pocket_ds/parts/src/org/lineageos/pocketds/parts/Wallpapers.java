/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** The wallpaper of each screen of the home (its own, chosen in the settings of the app). */
final class Wallpapers {
    private static final String TAG = "PocketDSWallpaper";
    // The size of each panel: the picture is cut to it once, when it is chosen.
    private static final int[][] SIZE = {{1920, 1080}, {1024, 768}};

    private Wallpapers() {}

    private static File file(Context context, int panel) {
        return new File(context.getFilesDir(), panel == Screens.TOP ? "wallpaper_top.jpg"
                : "wallpaper_bottom.jpg");
    }

    static boolean has(Context context, int panel) {
        return panel >= 0 && file(context, panel).exists();
    }

    /** The wallpaper of a panel, or null when it has none of its own. */
    static Drawable load(Context context, int panel) {
        if (!has(context, panel)) return null;
        final Bitmap bitmap = BitmapFactory.decodeFile(file(context, panel).getPath());
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    static void clear(Context context, int panel) {
        file(context, panel).delete();
    }

    /** Cuts the picture to the size of the panel (centered) and keeps it. */
    static boolean save(Context context, Uri uri, int panel) {
        final int width = SIZE[panel][0];
        final int height = SIZE[panel][1];
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2)
                    >= height) {
                sample *= 2;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            final Bitmap source;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                source = BitmapFactory.decodeStream(in, null, options);
            }
            if (source == null) return false;
            final Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final float scale = Math.max(width / (float) source.getWidth(),
                    height / (float) source.getHeight());
            final int cropW = Math.round(width / scale);
            final int cropH = Math.round(height / scale);
            final Rect from = new Rect((source.getWidth() - cropW) / 2,
                    (source.getHeight() - cropH) / 2, (source.getWidth() + cropW) / 2,
                    (source.getHeight() + cropH) / 2);
            new Canvas(out).drawBitmap(source, from, new Rect(0, 0, width, height), null);
            try (FileOutputStream stream = new FileOutputStream(file(context, panel))) {
                out.compress(Bitmap.CompressFormat.JPEG, 90, stream);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Wallpaper not saved", e);
            return false;
        }
    }
}
