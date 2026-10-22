/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Powers the console off some minutes after the lid was closed, and/or some minutes after the
 * screen went off. Each delay is an alarm (it must fire while the console sleeps) and is
 * cancelled as soon as the lid opens or the screen comes back.
 */
class PowerOffController {
    private static final String TAG = "PocketDSPowerOff";
    // Hall effect sensor of the lid, as in the framework (AyaneoDualScreenController).
    private static final String LID_SENSOR_TYPE = "qti.sensor.hall_effect";
    private static final int LID_SENSOR_FALLBACK_TYPE = 33171002;
    private static final float LID_NEAR_THRESHOLD = 5.0f;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AlarmManager mAlarms;
    private boolean mLidClosed;

    PowerOffController(Context context) {
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
                mAlarms.cancel(mScreenAlarm);
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    schedule(mScreenAlarm, "pocketds-poweroff-screen",
                            PartsPrefs.POWEROFF_SCREEN_MIN);
                }
            }
        }, filter, null, mHandler);
        registerLidSensor();
    }

    private void schedule(AlarmManager.OnAlarmListener listener, String tag, String key) {
        final int minutes = PartsPrefs.getInt(mPrefs, key, 0);
        if (minutes <= 0) return;
        mAlarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + minutes * 60_000L, tag, listener, mHandler);
    }

    private void registerLidSensor() {
        final SensorManager sensors = mContext.getSystemService(SensorManager.class);
        if (sensors == null) return;
        Sensor hall = null;
        for (Sensor sensor : sensors.getSensorList(Sensor.TYPE_ALL)) {
            if (sensor.isWakeUpSensor() && (LID_SENSOR_TYPE.equals(sensor.getStringType())
                    || sensor.getType() == LID_SENSOR_FALLBACK_TYPE)) {
                hall = sensor;
                break;
            }
        }
        if (hall == null) {
            Log.w(TAG, "No Hall sensor: power off on lid close cannot fire");
            return;
        }
        final float threshold = Math.min(hall.getMaximumRange(), LID_NEAR_THRESHOLD);
        sensors.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                final float value = event.values[0];
                final boolean closed = value >= 0 && value < threshold;
                if (closed == mLidClosed) return;
                mLidClosed = closed;
                mAlarms.cancel(mLidAlarm);
                if (closed) {
                    schedule(mLidAlarm, "pocketds-poweroff-lid", PartsPrefs.POWEROFF_LID_MIN);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        }, hall, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
    }

    private final AlarmManager.OnAlarmListener mLidAlarm = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (mLidClosed) powerOff("lid closed");
        }
    };

    private final AlarmManager.OnAlarmListener mScreenAlarm = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (!mContext.getSystemService(PowerManager.class).isInteractive()) {
                powerOff("screen off");
            }
        }
    };

    private void powerOff(String why) {
        if (mPrefs.getBoolean(PartsPrefs.POWEROFF_ONLY_ON_BATTERY, true)
                && mContext.getSystemService(BatteryManager.class).isCharging()) {
            Log.i(TAG, "Power off (" + why + ") skipped: charging");
            return;
        }
        Log.i(TAG, "Powering off: " + why);
        mContext.getSystemService(PowerManager.class).shutdown(false, "pocketds", false);
    }
}
