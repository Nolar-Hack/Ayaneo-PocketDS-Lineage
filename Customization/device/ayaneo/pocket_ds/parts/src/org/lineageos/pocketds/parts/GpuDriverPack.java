/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Puts a custom Vulkan driver (TurnipZ, a modified Mesa Turnip for Adreno) in the driver folder
 * of the Switch emulators, so that it shows in their list of drivers (Eden: Settings > GPU
 * driver, or the driver of a single game). It is only a file in their folder: the driver of the
 * system and the one an emulator uses stay what they were until the user chooses this one. Done
 * once for each emulator and version (a hidden mark is kept beside it): deleting the driver does
 * not bring it back.
 */
final class GpuDriverPack {
    private static final String TAG = "PocketDSDrivers";
    /** The driver of the image; a newer one downloaded from System update > Add-ons wins. */
    private static final String BUNDLED = "TurnipZ-0.4.0.zip";
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile(
            "TurnipZ-([0-9][0-9A-Za-z.\\-]*)\\.zip");
    private static GpuDriverPack sInstance;
    /** The emulators and the folder, in their data folder, where their drivers are kept. */
    private static final String[] PACKAGES = {"dev.eden.eden_emulator", "dev.eden.eden_nightly",
        "org.citron.citron_emu"};
    private static final long RETRY_MS = 10 * 60_000L;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastTry;

    GpuDriverPack(Context context) {
        mContext = context;
    }

    void start() {
        sInstance = this;
        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // the data folder of an emulator only exists once it has been started
                mHandler.postDelayed(() -> install(true), 5_000);
            }
        };
        mContext.registerReceiver(receiver, filter, null, mHandler);
        final IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                install(false);
            }
        }, screen, null, mHandler);
        mHandler.postDelayed(() -> install(true), 30_000);
    }

    /** Folder of the drivers downloaded by the add-ons page. */
    static File downloadDir(Context context) {
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), "drivers");
    }

    /** The version of the driver that is put in the emulators: the newest of the two. */
    static String currentVersion(Context context) {
        return versionOf(newest(context));
    }

    private static String versionOf(String name) {
        final java.util.regex.Matcher m = NAME.matcher(name);
        return m.matches() ? m.group(1) : "0";
    }

    private static String newest(Context context) {
        String best = BUNDLED;
        final String[] names = downloadDir(context).list();
        if (names != null) {
            for (String name : names) {
                if (NAME.matcher(name).matches()
                        && Addons.compare(versionOf(name), versionOf(best)) > 0) {
                    best = name;
                }
            }
        }
        return best;
    }

    /** A driver was downloaded: put it in the emulators now. */
    static void refresh() {
        final GpuDriverPack pack = sInstance;
        if (pack != null) pack.mHandler.post(() -> pack.install(true));
    }

    private boolean enabled() {
        return PartsPrefs.get(mContext).getBoolean(PartsPrefs.GPU_DRIVER_PACK, true);
    }

    /** Copies the driver where an emulator keeps its drivers, if it is there and has none yet. */
    private void install(boolean force) {
        if (!enabled()) return;
        final long now = SystemClock.elapsedRealtime();
        if (!force && now - mLastTry < RETRY_MS) return;
        mLastTry = now;
        final String driver = newest(mContext);
        final String mark = ".pocketds-turnipz-" + versionOf(driver);
        final File downloaded = new File(downloadDir(mContext), driver);
        final File cached = new File(mContext.getCacheDir(), driver);
        try (InputStream in = driver.equals(BUNDLED) && !downloaded.exists()
                ? mContext.getAssets().open(driver) : new java.io.FileInputStream(downloaded);
                OutputStream out = new java.io.FileOutputStream(cached)) {
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot read the driver", e);
            return;
        }
        cached.setReadable(true, false);
        for (String pkg : PACKAGES) {
            final String dir = "/data/media/0/Android/data/" + pkg + "/files/gpu_drivers";
            // Only where the emulator already has its folder, once, with the owner and the
            // security label of that folder (an app reads only what carries its own).
            RootShell.run("if [ -d " + dir + " ] && [ ! -e " + dir + "/" + mark + " ]; then"
                    + " rm -f " + dir + "/TurnipZ-*.zip " + dir + "/.pocketds-turnipz-*;"
                    + " cp " + cached.getPath() + " " + dir + "/" + driver
                    + " && chown $(stat -c %u:%g " + dir + ") " + dir + "/" + driver
                    + " && chmod 660 " + dir + "/" + driver
                    + " && chcon $(stat -c %C " + dir + ") " + dir + "/" + driver
                    + " && : > " + dir + "/" + mark
                    + " && chown $(stat -c %u:%g " + dir + ") " + dir + "/" + mark
                    + " && chcon $(stat -c %C " + dir + ") " + dir + "/" + mark
                    + " && echo \"TurnipZ added to " + pkg + "\" > /dev/kmsg; fi");
        }
    }
}
