/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.bluetooth.BluetoothAdapter;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Battery: some minutes after the screens went off, switches off Wi-Fi and/or Bluetooth (which
 * keep waking the SoC up), and switches them back on when the screen comes back. Nothing is
 * touched while charging unless asked, and only what was on is restored. Same idea as the Suspend
 * actions of AICP.
 */
class SuspendActionsController {
    private static final String TAG = "PocketDSSuspend";
    private static final String KEY_RESTORE_WIFI = "suspend_restore_wifi";
    private static final String KEY_RESTORE_BLUETOOTH = "suspend_restore_bluetooth";

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AlarmManager mAlarms;

    SuspendActionsController(Context context) {
        mContext = context;
        mPrefs = PartsPrefs.get(context);
        mAlarms = context.getSystemService(AlarmManager.class);
    }

    void start() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    scheduleSuspend();
                } else {
                    mAlarms.cancel(mSuspendAlarm);
                    restore();
                }
            }
        }, filter, null, mHandler);
        // A reboot while suspended would leave them off: restore what was switched off.
        if (mContext.getSystemService(PowerManager.class).isInteractive()) restore();
    }

    private void scheduleSuspend() {
        mAlarms.cancel(mSuspendAlarm);
        final int minutes = PartsPrefs.getInt(mPrefs, PartsPrefs.SUSPEND_DELAY_MIN, 0);
        // An alarm, not a handler delay: it must fire while the console sleeps.
        if (minutes > 0) {
            mAlarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + minutes * 60_000L, "pocketds-suspend",
                    mSuspendAlarm, mHandler);
        }
    }

    private final AlarmManager.OnAlarmListener mSuspendAlarm =
            new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (mContext.getSystemService(PowerManager.class).isInteractive()) return;
            if (mPrefs.getBoolean(PartsPrefs.SUSPEND_ONLY_ON_BATTERY, true)
                    && mContext.getSystemService(BatteryManager.class).isCharging()) {
                return;
            }
            final SharedPreferences.Editor edit = mPrefs.edit();
            if (mPrefs.getBoolean(PartsPrefs.SUSPEND_WIFI, false)) {
                final WifiManager wifi = mContext.getSystemService(WifiManager.class);
                if (wifi != null && wifi.isWifiEnabled() && wifi.setWifiEnabled(false)) {
                    edit.putBoolean(KEY_RESTORE_WIFI, true);
                    Log.i(TAG, "Wi-Fi switched off");
                }
            }
            if (mPrefs.getBoolean(PartsPrefs.SUSPEND_BLUETOOTH, false)) {
                // Without this the controller stays in BLE-only mode (Play Services keeps
                // scanning and advertising) and its UART wakes the SoC about once a second:
                // the console never reaches suspend.
                Settings.Global.putInt(mContext.getContentResolver(),
                        "ble_scan_always_enabled", 0);
                final BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
                if (bluetooth != null && bluetooth.isEnabled() && bluetooth.disable()) {
                    edit.putBoolean(KEY_RESTORE_BLUETOOTH, true);
                    Log.i(TAG, "Bluetooth switched off");
                }
            }
            edit.apply();
        }
    };

    private void restore() {
        final SharedPreferences.Editor edit = mPrefs.edit();
        if (mPrefs.getBoolean(KEY_RESTORE_WIFI, false)) {
            final WifiManager wifi = mContext.getSystemService(WifiManager.class);
            if (wifi != null) wifi.setWifiEnabled(true);
            edit.putBoolean(KEY_RESTORE_WIFI, false);
            Log.i(TAG, "Wi-Fi switched back on");
        }
        if (mPrefs.getBoolean(KEY_RESTORE_BLUETOOTH, false)) {
            final BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
            if (bluetooth != null) bluetooth.enable();
            edit.putBoolean(KEY_RESTORE_BLUETOOTH, false);
            Log.i(TAG, "Bluetooth switched back on");
        }
        edit.apply();
    }
}
