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
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long SAMPLE_MS = 10_000L;
    private static final long BUSY_INTERRUPTS = 200;
    /** A game left open: the console sleeps, checked again this often, off under this level. */
    private static final long GAME_CHECK_MS = 30 * 60_000L;
    private static final int GAME_LOW_BATTERY = 10;

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
            if (!mLidClosed) return;
            if (keepGame("lid closed", this, "pocketds-poweroff-lid")) return;
            // Lid closed: power off even when plugged, unless data is going through the USB port.
            // A closed lid with nothing happening on the port only drains the battery.
            new Thread(() -> {
                if (usbTransferring()) {
                    Log.i(TAG, "Power off (lid closed) postponed: USB transfer in progress");
                    mHandler.post(() -> mAlarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + RETRY_MS, "pocketds-poweroff-lid",
                            mLidAlarm, mHandler));
                } else {
                    mHandler.post(() -> {
                        if (mLidClosed) powerOff("lid closed", true);
                    });
                }
            }, "pocketds-usb-check").start();
        }
    };

    private final AlarmManager.OnAlarmListener mScreenAlarm = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (!mContext.getSystemService(PowerManager.class).isInteractive()) {
                if (keepGame("screen off", this, "pocketds-poweroff-screen")) return;
                powerOff("screen off");
            }
        }
    };

    /**
     * A game is open (the app in front is a game): the console stays asleep with the game paused,
     * as on a Switch, instead of losing the game by powering off; it is checked again every 30
     * minutes and powered off once the battery is under 10 %. True when the power off is held.
     */
    private boolean keepGame(String why, AlarmManager.OnAlarmListener alarm, String tag) {
        if (!mPrefs.getBoolean(PartsPrefs.POWEROFF_KEEP_GAME, true)) return false;
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        final android.app.ActivityManager.RunningTaskInfo app =
                watcher == null ? null : watcher.current();
        final String pkg = app == null ? null : app.baseActivity.getPackageName();
        if (!PerfHints.isGame(mContext, pkg)) return false;
        final int level = mContext.getSystemService(BatteryManager.class)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (level > 0 && level < GAME_LOW_BATTERY) {
            Log.i(TAG, "Game " + pkg + " open but battery at " + level + " %: powering off");
            return false;
        }
        Log.i(TAG, "Power off (" + why + ") held: game " + pkg + " open, battery " + level
                + " %, checked again in 30 min");
        mAlarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + GAME_CHECK_MS, tag, alarm, mHandler);
        return true;
    }

    /**
     * True when the USB device side is enumerated by a host and moving data: the controller state
     * is "configured" and its interrupt counter moves during a short sample. A host that is off or
     * asleep (suspended / not attached) or an idle link counts as no transfer.
     */
    private boolean usbTransferring() {
        try {
            if (!"configured".equals(readFirstUdcState())) return false;
            final long before = usbInterrupts();
            Thread.sleep(SAMPLE_MS);
            final long delta = usbInterrupts() - before;
            Log.i(TAG, "USB interrupts in " + SAMPLE_MS + " ms: " + delta);
            return delta > BUSY_INTERRUPTS;
        } catch (Exception e) {
            Log.w(TAG, "USB activity check failed, assuming no transfer", e);
            return false;
        }
    }

    private static String readFirstUdcState() throws java.io.IOException {
        final java.io.File[] udcs = new java.io.File("/sys/class/udc").listFiles();
        if (udcs == null) return "";
        for (java.io.File udc : udcs) {
            final String state = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(udc, "state").toPath())).trim();
            if (!state.isEmpty()) return state;
        }
        return "";
    }

    private static long usbInterrupts() throws java.io.IOException {
        long total = 0;
        for (String line : java.nio.file.Files.readAllLines(
                new java.io.File("/proc/interrupts").toPath())) {
            if (!line.contains("dwc3")) continue;
            for (String field : line.trim().split("\\s+")) {
                if (field.endsWith(":")) continue;
                try {
                    total += Long.parseLong(field);
                } catch (NumberFormatException e) {
                    break;
                }
            }
        }
        return total;
    }

    private void powerOff(String why) {
        powerOff(why, false);
    }

    private void powerOff(String why, boolean evenIfCharging) {
        if (!evenIfCharging
                && mPrefs.getBoolean(PartsPrefs.POWEROFF_ONLY_ON_BATTERY, true)
                && mContext.getSystemService(BatteryManager.class).isCharging()) {
            Log.i(TAG, "Power off (" + why + ") skipped: charging");
            return;
        }
        Log.i(TAG, "Powering off: " + why);
        mContext.getSystemService(PowerManager.class).shutdown(false, "pocketds", false);
    }
}
