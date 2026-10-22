/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.AlarmManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Switches Bluetooth off when it has been on for some minutes without any connected device
 * (Play Services keeps scanning and advertising over BLE, which wakes the SoC about once a
 * second and prevents suspend). The countdown restarts whenever Bluetooth turns on or a
 * device disconnects, and is cancelled while a device is connected.
 */
class BluetoothIdleController implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PocketDSBtIdle";

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AlarmManager mAlarms;

    BluetoothIdleController(Context context) {
        mContext = context;
        mPrefs = PartsPrefs.get(context);
        mAlarms = context.getSystemService(AlarmManager.class);
    }

    void start() {
        final IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                evaluate();
            }
        }, filter, null, mHandler);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        evaluate();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PartsPrefs.BT_IDLE_OFF_MIN.equals(key)) mHandler.post(this::evaluate);
    }

    private boolean isIdle() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled()
                && adapter.getConnectionState() == BluetoothAdapter.STATE_DISCONNECTED;
    }

    private void evaluate() {
        mAlarms.cancel(mIdleAlarm);
        final int minutes = PartsPrefs.getInt(mPrefs, PartsPrefs.BT_IDLE_OFF_MIN, 0);
        if (minutes <= 0 || !isIdle()) return;
        // An alarm, not a handler delay: it must fire while the console sleeps.
        mAlarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + minutes * 60_000L, "pocketds-bt-idle",
                mIdleAlarm, mHandler);
    }

    private final AlarmManager.OnAlarmListener mIdleAlarm = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (!isIdle()) return;
            Log.i(TAG, "No Bluetooth device connected: switching Bluetooth off");
            // Without this the controller stays in BLE-only mode and keeps waking the SoC.
            Settings.Global.putInt(mContext.getContentResolver(), "ble_scan_always_enabled", 0);
            BluetoothAdapter.getDefaultAdapter().disable();
        }
    };
}
