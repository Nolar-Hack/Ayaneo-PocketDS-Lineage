/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The optimisation rules engine. It follows the state of the console (screen, lid, charger,
 * battery level, app in the foreground); for every enabled rule whose trigger holds (for its
 * delay), it applies the actions and remembers what they changed; when the trigger stops
 * holding, it undoes them. What was changed is kept in the preferences, so that a reboot while a
 * rule is active does not leave things switched off: it is undone at the next start.
 * Delays use alarms: they must fire while the console sleeps (lid closed).
 */
class RulesController implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PocketDSRules";
    // Hall effect sensor of the lid, as in the framework (AyaneoDualScreenController).
    private static final String LID_SENSOR_TYPE = "qti.sensor.hall_effect";
    private static final int LID_SENSOR_FALLBACK_TYPE = 33171002;
    private static final float LID_NEAR_THRESHOLD = 5.0f;
    private static final long APP_POLL_MS = 3000;
    // Battery rules end a bit above their level, so that they do not flap.
    private static final int BATTERY_HYSTERESIS = 3;

    private final Context mContext;
    private final RuleStore mStore;
    private final SharedPreferences mPrefs;
    private final AlarmManager mAlarms;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<String> mActive = new HashSet<>();
    private final Map<String, AlarmManager.OnAlarmListener> mPending = new HashMap<>();

    private boolean mInteractive = true;
    private boolean mLidClosed;
    private boolean mPlugged;
    private int mLevel = 100;
    private String mForeground = "";
    private int mTempTenths;
    private boolean mWifi;
    private boolean mBtDevice;
    private static final String NOTIFY_CHANNEL = "pocketds_rules";

    RulesController(Context context) {
        mContext = context;
        mStore = new RuleStore(context);
        mPrefs = PartsPrefs.get(context);
        mAlarms = context.getSystemService(AlarmManager.class);
    }

    void start() {
        mInteractive = mContext.getSystemService(PowerManager.class).isInteractive();
        readBattery(mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mInteractive = false;
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mInteractive = true;
                    pollForeground();
                } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    readBattery(intent);
                } else {
                    readBluetooth();
                }
                evaluate();
            }
        }, filter, null, mHandler);
        readBluetooth();
        registerWifiCallback();
        registerLidSensor();
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        undoLeftovers();
        pollForeground();
        evaluate();
    }

    private void readBattery(Intent intent) {
        if (intent == null) return;
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) mLevel = level * 100 / scale;
        mPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        mTempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
    }

    private void readBluetooth() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBtDevice = adapter != null && adapter.isEnabled()
                && adapter.getConnectionState() != BluetoothAdapter.STATE_DISCONNECTED;
    }

    private void registerWifiCallback() {
        final ConnectivityManager connectivity = mContext.getSystemService(
                ConnectivityManager.class);
        if (connectivity == null) return;
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        mWifi = true;
                        evaluate();
                    }

                    @Override
                    public void onLost(Network network) {
                        mWifi = false;
                        evaluate();
                    }
                }, mHandler);
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
            Log.w(TAG, "No Hall sensor: the lid rules cannot fire");
            return;
        }
        final float threshold = Math.min(hall.getMaximumRange(), LID_NEAR_THRESHOLD);
        sensors.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                final float value = event.values[0];
                final boolean closed = value >= 0 && value < threshold;
                if (closed != mLidClosed) {
                    mLidClosed = closed;
                    evaluate();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        }, hall, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
    }

    /** Foreground app, read every few seconds while the screen is on and a rule needs it. */
    private void pollForeground() {
        mHandler.removeCallbacks(mPollTask);
        boolean needed = false;
        for (Rule rule : mStore.load()) {
            if (rule.enabled && hasCondition(rule, Rule.C_APP)) needed = true;
        }
        if (!needed || !mInteractive || !mStore.masterEnabled()) {
            if (!mForeground.isEmpty()) {
                mForeground = "";
                evaluate();
            }
            return;
        }
        final List<ActivityManager.RunningTaskInfo> tasks = mContext.getSystemService(
                ActivityManager.class).getRunningTasks(1);
        final String pkg = tasks.isEmpty() || tasks.get(0).topActivity == null ? ""
                : tasks.get(0).topActivity.getPackageName();
        if (!pkg.equals(mForeground)) {
            mForeground = pkg;
            evaluate();
        }
        mHandler.postDelayed(mPollTask, APP_POLL_MS);
    }

    private final Runnable mPollTask = this::pollForeground;

    private static boolean hasCondition(Rule rule, String type) {
        for (Rule.Condition c : rule.conditions) if (c.type.equals(type)) return true;
        return false;
    }

    /** The conditions of a rule, all of them or any of them, as asked by the rule. */
    private boolean triggerHolds(Rule rule) {
        if (rule.conditions.isEmpty()) return false;
        for (Rule.Condition condition : rule.conditions) {
            final boolean holds = conditionHolds(rule, condition);
            if (rule.matchAll && !holds) return false;
            if (!rule.matchAll && holds) return true;
        }
        return rule.matchAll;
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean conditionHolds(Rule rule, Rule.Condition condition) {
        switch (condition.type) {
            case Rule.C_SCREEN_OFF:
                return !mInteractive;
            case Rule.C_SCREEN_ON:
                return mInteractive;
            case Rule.C_LID_CLOSED:
                return mLidClosed;
            case Rule.C_LID_OPEN:
                return !mLidClosed;
            case Rule.C_UNPLUGGED:
                return !mPlugged;
            case Rule.C_PLUGGED:
                return mPlugged;
            case Rule.C_BATTERY_BELOW: {
                int limit = number(condition.param, -1);
                if (limit < 0) return false;
                if (mActive.contains(rule.id)) limit += BATTERY_HYSTERESIS;
                return mLevel < limit;
            }
            case Rule.C_BATTERY_ABOVE: {
                int limit = number(condition.param, 101);
                if (mActive.contains(rule.id)) limit -= BATTERY_HYSTERESIS;
                return mLevel > limit;
            }
            case Rule.C_TEMP_ABOVE: {
                int limit = number(condition.param, 1000) * 10;
                if (mActive.contains(rule.id)) limit -= 20;   // 2 degrees of margin
                return mTempTenths > limit;
            }
            case Rule.C_APP:
                return !condition.param.isEmpty() && mInteractive
                        && condition.param.equals(mForeground);
            case Rule.C_TIME: {
                final Calendar now = Calendar.getInstance();
                // Calendar: Sunday = 1. Ours: Monday = 0.
                final int day = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                return Rule.TimeWindow.parse(condition.param).contains(day,
                        now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
            }
            case Rule.C_WIFI:
                return mWifi;
            case Rule.C_NO_WIFI:
                return !mWifi;
            case Rule.C_BT_DEVICE:
                return mBtDevice;
            case Rule.C_NO_BT_DEVICE:
                return !mBtDevice;
            default:
                return false;
        }
    }

    /** Wakes the engine at the next start or end of a time window used by an enabled rule. */
    private void scheduleTimeCheck() {
        mAlarms.cancel(mTimeAlarm);
        final Calendar now = Calendar.getInstance();
        long next = Long.MAX_VALUE;
        for (Rule rule : mStore.load()) {
            if (!rule.enabled) continue;
            for (Rule.Condition condition : rule.conditions) {
                if (!Rule.C_TIME.equals(condition.type)) continue;
                final Rule.TimeWindow window = Rule.TimeWindow.parse(condition.param);
                for (int minute : new int[] {window.startMin, window.endMin}) {
                    final Calendar at = (Calendar) now.clone();
                    at.set(Calendar.HOUR_OF_DAY, minute / 60 % 24);
                    at.set(Calendar.MINUTE, minute % 60);
                    at.set(Calendar.SECOND, 0);
                    at.set(Calendar.MILLISECOND, 0);
                    if (at.getTimeInMillis() <= now.getTimeInMillis()) at.add(Calendar.DAY_OF_MONTH, 1);
                    next = Math.min(next, at.getTimeInMillis());
                }
            }
        }
        if (next != Long.MAX_VALUE) {
            mAlarms.setExact(AlarmManager.RTC_WAKEUP, next, "pocketds-rule-time", mTimeAlarm,
                    mHandler);
        }
    }

    private final AlarmManager.OnAlarmListener mTimeAlarm = this::evaluate;

    private void notifyStarted(Rule rule) {
        final NotificationManager manager = mContext.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(NOTIFY_CHANNEL,
                "Automation", NotificationManager.IMPORTANCE_LOW));
        manager.notify(rule.id.hashCode(), new Notification.Builder(mContext, NOTIFY_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(rule.name.isEmpty() ? "Automation" : rule.name)
                .setContentText(rule.summary().replace('\n', ' '))
                .setAutoCancel(true).setShowWhen(true).build());
    }

    /** Puts every rule in line with the current state. */
    private void evaluate() {
        scheduleTimeCheck();
        final boolean master = mStore.masterEnabled();
        for (final Rule rule : mStore.load()) {
            final boolean holds = master && rule.enabled && triggerHolds(rule);
            if (holds && !mActive.contains(rule.id)) {
                if (!mPending.containsKey(rule.id)) schedule(rule);
            } else if (!holds) {
                cancelPending(rule.id);
                if (mActive.contains(rule.id)) end(rule);
            }
        }
        // Rules that were deleted or switched off while active are undone above only if still
        // listed: clean the others.
        for (String id : new HashSet<>(mActive)) {
            if (mStore.find(id) == null) {
                mActive.remove(id);
                mStore.clearState(id);
            }
        }
    }

    private void schedule(final Rule rule) {
        if (rule.delaySec <= 0) {
            begin(rule.id);
            return;
        }
        final AlarmManager.OnAlarmListener listener = () -> {
            mPending.remove(rule.id);
            begin(rule.id);
        };
        mPending.put(rule.id, listener);
        mAlarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + rule.delaySec * 1000L, "pocketds-rule", listener,
                mHandler);
    }

    private void cancelPending(String id) {
        final AlarmManager.OnAlarmListener listener = mPending.remove(id);
        if (listener != null) mAlarms.cancel(listener);
    }

    /** Applies the actions of a rule (its trigger held for the whole delay). */
    private void begin(String id) {
        final Rule rule = mStore.find(id);
        if (rule == null || !rule.enabled || !mStore.masterEnabled() || !triggerHolds(rule)) {
            return;
        }
        if (rule.onlyOnBattery && mPlugged) return;
        final JSONArray saved = new JSONArray();
        for (Rule.Action action : rule.actions) {
            final String was = ActionRunner.apply(mContext, action);
            try {
                saved.put(new JSONObject().put("type", action.type).put("value", action.value)
                        .put("was", was == null ? JSONObject.NULL : was));
            } catch (JSONException e) {
                Log.e(TAG, "State not kept", e);
            }
        }
        mActive.add(id);
        mStore.saveState(id, saved.toString());
        Log.i(TAG, "Rule started: " + rule.name);
        if (rule.notify) notifyStarted(rule);
    }

    private void end(Rule rule) {
        if (rule.undo) undo(rule.id);
        mActive.remove(rule.id);
        mStore.clearState(rule.id);
        Log.i(TAG, "Rule ended: " + rule.name);
    }

    private void undo(String id) {
        final String json = mStore.savedState(id);
        if (json == null) return;
        try {
            final JSONArray saved = new JSONArray(json);
            for (int i = saved.length() - 1; i >= 0; i--) {
                final JSONObject entry = saved.getJSONObject(i);
                ActionRunner.revert(mContext,
                        new Rule.Action(entry.getString("type"), entry.optString("value")),
                        entry.isNull("was") ? null : entry.getString("was"));
            }
        } catch (JSONException e) {
            Log.e(TAG, "State unreadable", e);
        }
    }

    /** After a reboot or a crash: what a rule changed is undone if its trigger no longer holds. */
    private void undoLeftovers() {
        for (Rule rule : mStore.load()) {
            if (mStore.savedState(rule.id) == null) continue;
            if (rule.enabled && mStore.masterEnabled() && triggerHolds(rule)) {
                mActive.add(rule.id);   // still holds: stays active
            } else {
                if (rule.undo) undo(rule.id);
                mStore.clearState(rule.id);
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (RuleStore.KEY_RULES.equals(key) || RuleStore.KEY_MASTER.equals(key)) {
            mHandler.post(() -> {
                pollForeground();
                evaluate();
            });
        }
    }
}
