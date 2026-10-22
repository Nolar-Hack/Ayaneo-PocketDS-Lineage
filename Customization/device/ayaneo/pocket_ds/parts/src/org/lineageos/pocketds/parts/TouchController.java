/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Main screen touch (FocalTech FT5452), against phantom touches with a finger hovering
 * the screen:
 *  - detection threshold (register 0x80) raised from the firmware default 0x46 (70) to 0x78 (120);
 *    the controller is reset on every resume, so it is written again when the screen
 *    turns on;
 *  - charger mode (noise filtering) on while a charger / USB cable is connected.
 */
class TouchController extends BroadcastReceiver {
    private static final String TAG = "PocketDSParts";
    private static final String FTS =
            "/sys/devices/platform/soc/ac0000.qcom,qupv3_1_geni_se/a90000.i2c/i2c-3/3-0038/";
    // fts_rw_reg only takes a "<register><value>" line: without the newline the driver
    // rejects the write (EINVAL). The value is the threshold of the settings (default 0x78).
    private static final int THRESHOLD_DEFAULT = 120;
    // The controller takes ~600 ms to be ready after a resume (writes fail with EINVAL
    // before): written after 1 s, then once more in case it was still not ready.
    private static final long RESUME_DELAY_MS = 1000;
    private static final long RESUME_RETRY_DELAY_MS = 2000;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener
            mPrefListener = (prefs, key) -> {
                if (PartsPrefs.TOUCH_THRESHOLD.equals(key)) mHandler.post(this::writeThreshold);
            };

    TouchController(Context context) {
        mContext = context;
    }

    void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(this, filter);

        Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean plugged = battery != null
                && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        setChargerMode(plugged);
        writeThreshold();
        // A new sensitivity chosen in the settings is written at once.
        // (kept in a field: the preferences only hold their listeners weakly)
        PartsPrefs.get(mContext).registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
            case Intent.ACTION_SCREEN_ON:
                // The driver resets the controller on resume: wait for it.
                mHandler.removeCallbacksAndMessages(null);
                mHandler.postDelayed(this::writeThreshold, RESUME_DELAY_MS);
                mHandler.postDelayed(this::writeThreshold, RESUME_RETRY_DELAY_MS);
                break;
            case Intent.ACTION_POWER_CONNECTED:
                setChargerMode(true);
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                setChargerMode(false);
                break;
        }
    }

    private void writeThreshold() {
        final int threshold = Math.max(30, Math.min(200,
                PartsPrefs.getInt(PartsPrefs.get(mContext), PartsPrefs.TOUCH_THRESHOLD,
                        THRESHOLD_DEFAULT)));
        write(FTS + "fts_rw_reg", String.format(java.util.Locale.US, "80%02x\n", threshold));
    }

    private void setChargerMode(boolean on) {
        write(FTS + "fts_charger_mode", on ? "1" : "0");
    }

    private static void write(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        } catch (IOException e) {
            Log.w(TAG, "Failed to write " + value + " to " + path, e);
        }
    }
}
