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
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Calendar;

/**
 * The RGB LED next to the USB-C port (tri-LED of the PMIC). Android drives it as the button
 * backlight: white on every touch, off 5 s later; that white lights the whole room at night.
 *
 * What it shows, first that applies:
 * 1. the colour of an automation rule ({@link Rule#A_LED});
 * 2. running on the charger (battery bypass, see {@link ChargeLimitController});
 * 3. charge complete (100 %, or the charge limit);
 * 4. the battery level, by steps or as a gradient from red to green;
 * 5. the button backlight of Android.
 * A blinking notification (LineageOS notification light) is never painted over.
 * Its brightness (one slider, lowered at night) scales all of them. For 1 to 4 the button
 * backlight is turned off (LineageOS button_brightness = 0) and the colour is written straight
 * to the LED; while charging it can breathe (pattern of the PMIC).
 */
final class LedController implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PocketDSLed";
    private static final String LEDS = "/sys/class/leds/";
    private static final String[] CHANNELS = {"red", "green", "blue"};
    private static final String LINEAGE_URI = "content://lineagesettings";
    private static final String BUTTON_BRIGHTNESS = "button_brightness";
    // The framework writes "off" to the LED a moment after the setting changes: come after it.
    private static final long SETTLE_MS = 600;

    /** Colour set by a rule, "RRGGBB"; "000000" = off; empty = none. */
    static final String PREF_OVERRIDE = "led_override";
    /** Brightness, percent. */
    static final String PREF_BRIGHTNESS = "led_brightness";
    /** Show the battery level: "off", "charging" (while plugged) or "always". */
    static final String PREF_BATTERY = "led_battery";
    /** The steps: "15:FF0000,50:FF5000,80:FFC000,100:00FF00" (up to that level, that colour). */
    static final String PREF_STEPS = "led_battery_steps";
    static final String DEFAULT_STEPS = "15:FF0000,50:FF5000,80:FFC000,100:00FF00";
    /** A gradient from red (0 %) to green (100 %) instead of the steps. */
    static final String PREF_GRADIENT = "led_gradient";
    static final String DEFAULT_BATTERY = "charging";
    /** Breathe while charging. */
    static final String PREF_BREATH = "led_breath";
    static final boolean DEFAULT_BREATH = true;
    /** Colour once charged ("" = not used). */
    static final String PREF_FULL = "led_full_color";
    static final String DEFAULT_FULL = "00FF00";
    /** Colour while playing on the charger ("" = not used). */
    static final String PREF_BYPASS = "led_bypass_color";
    /** Night: on/off, time window (see Rule.TimeWindow), brightness in percent then. */
    static final String PREF_NIGHT = "led_night";
    static final String PREF_NIGHT_WINDOW = "led_night_window";
    static final String PREF_NIGHT_LEVEL = "led_night_level";
    static final String DEFAULT_NIGHT_WINDOW = "22:00-07:00;1111111";

    // Colours offered.
    static final String[] COLORS = {"FF0000", "FF5000", "FFC000", "00FF00", "00FFFF", "0000FF",
        "A000FF", "FF00A0", "FFFFFF", "000000"};
    static final String[] COLOR_LABELS = {"🔴  Red", "🟠  Orange", "🟡  Yellow", "🟢  Green",
        "🩵  Cyan", "🔵  Blue", "🟣  Purple", "🩷  Pink", "⚪  White", "⚫  Off"};
    /** The same, with "not used" first (charge complete, playing on the charger). */
    static final String[] OPTIONAL_COLORS = withNone(COLORS, "");
    static final String[] OPTIONAL_LABELS = withNone(COLOR_LABELS, "Not used");

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static int sLevel = -1;
    private static boolean sPlugged;
    /** What was last written to the LED (null before the first time). */
    private static String sShown;
    private static AlarmManager.OnAlarmListener sNightAlarm;

    private final Context mContext;

    LedController(Context context) {
        mContext = context;
    }

    private static String[] withNone(String[] list, String none) {
        final String[] out = new String[list.length + 1];
        out[0] = none;
        System.arraycopy(list, 0, out, 1, list.length);
        return out;
    }

    void start() {
        readBattery(mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    readBattery(intent);
                    apply(context, false);
                } else if (Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                    scheduleNight(context);
                    apply(context, false);
                } else {
                    // The battery light of Android uses the same LED: put the colour back.
                    apply(context, true);
                }
            }
        }, filter, null, sHandler);
        PartsPrefs.get(mContext).registerOnSharedPreferenceChangeListener(this);
        scheduleNight(mContext);
        apply(mContext, true);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key == null || !key.startsWith("led_")) return;
        if (key.startsWith(PREF_NIGHT)) scheduleNight(mContext);
        apply(mContext, true);
    }

    private static void readBattery(Intent intent) {
        if (intent == null) return;
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) sLevel = level * 100 / scale;
        sPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    // ---- settings

    static int brightness(Context context) {
        return PartsPrefs.get(context).getInt(PREF_BRIGHTNESS, 100);
    }

    static void setBrightness(Context context, int percent) {
        PartsPrefs.get(context).edit().putInt(PREF_BRIGHTNESS,
                Math.max(0, Math.min(100, percent))).apply();
    }

    static String override(Context context) {
        return PartsPrefs.get(context).getString(PREF_OVERRIDE, "");
    }

    /** Sets the colour of a rule ("RRGGBB", or "" for none) and returns the previous one. */
    static String setOverride(Context context, String color) {
        final String was = override(context);
        PartsPrefs.get(context).edit().putString(PREF_OVERRIDE, color == null ? "" : color)
                .apply();
        return was;
    }

    /** The steps, sorted: {level, colour} pairs. */
    static String[][] steps(Context context) {
        final String text = PartsPrefs.get(context).getString(PREF_STEPS, DEFAULT_STEPS);
        final String[] parts = text.split(",");
        final String[][] steps = new String[parts.length][];
        for (int i = 0; i < parts.length; i++) steps[i] = parts[i].split(":");
        java.util.Arrays.sort(steps, (a, b) -> Integer.parseInt(a[0]) - Integer.parseInt(b[0]));
        return steps;
    }

    static void setSteps(Context context, String[][] steps) {
        final StringBuilder sb = new StringBuilder();
        for (String[] step : steps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(step[0]).append(':').append(step[1]);
        }
        PartsPrefs.get(context).edit().putString(PREF_STEPS, sb.toString()).apply();
    }

    static String colorLabel(String color) {
        return Rule.label(OPTIONAL_COLORS, OPTIONAL_LABELS, color);
    }

    static Rule.TimeWindow nightWindow(Context context) {
        return Rule.TimeWindow.parse(PartsPrefs.get(context).getString(PREF_NIGHT_WINDOW,
                DEFAULT_NIGHT_WINDOW));
    }

    // ---- what to show

    private static String levelColor(Context context, int level) {
        if (PartsPrefs.get(context).getBoolean(PREF_GRADIENT, false)) {
            // Hue 0 (red) at 0 %, 120 (green) at 100 %.
            final int rgb = Color.HSVToColor(new float[] {Math.max(0, Math.min(100, level))
                    * 1.2f, 1f, 1f}) & 0xFFFFFF;
            return String.format("%06X", rgb);
        }
        final String[][] steps = steps(context);
        for (String[] step : steps) {
            if (level <= Integer.parseInt(step[0])) return step[1];
        }
        return steps[steps.length - 1][1];
    }

    /** Level at which the charge is complete: the charge limit when it is on. */
    private static int fullLevel(Context context) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        if (!prefs.getBoolean(ChargeLimitController.PREF_ON, false)) return 100;
        try {
            return Math.max(50, Math.min(100, Integer.parseInt(
                    prefs.getString(ChargeLimitController.PREF_LEVEL, "80"))));
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    /** The colour the LED should show now, or "" to leave it to the button backlight. */
    private static String wanted(Context context) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        final String color = override(context);
        if (!color.isEmpty()) return color;
        if (sPlugged) {
            final String bypass = prefs.getString(PREF_BYPASS, "");
            if (!bypass.isEmpty() && ChargeLimitController.bypassing()) return bypass;
            final String full = prefs.getString(PREF_FULL, DEFAULT_FULL);
            if (!full.isEmpty() && sLevel >= fullLevel(context)) return full;
        }
        final String battery = prefs.getString(PREF_BATTERY, DEFAULT_BATTERY);
        if (sLevel >= 0 && ("always".equals(battery) || ("charging".equals(battery) && sPlugged))) {
            try {
                return levelColor(context, sLevel);
            } catch (RuntimeException e) {
                Log.w(TAG, "Bad battery steps", e);
            }
        }
        return "";
    }

    private static boolean isNight(Context context) {
        if (!PartsPrefs.get(context).getBoolean(PREF_NIGHT, false)) return false;
        final Calendar now = Calendar.getInstance();
        final int day = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // 0 = Monday
        return nightWindow(context).contains(day,
                now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
    }

    private static int effectiveBrightness(Context context) {
        final int percent = brightness(context);
        if (!isNight(context)) return percent;
        return Math.min(percent, PartsPrefs.get(context).getInt(PREF_NIGHT_LEVEL, 0));
    }

    /** Puts the LED in the state asked; force = write again even when nothing changed. */
    static void apply(Context context, boolean force) {
        final int percent = effectiveBrightness(context);
        final String color = wanted(context);
        // Breathing = charging; steady once charged, on the charger while playing, or a rule.
        final boolean breath = !color.isEmpty() && sPlugged && percent > 0
                && override(context).isEmpty() && sLevel < fullLevel(context)
                && !ChargeLimitController.bypassing()
                && PartsPrefs.get(context).getBoolean(PREF_BREATH, DEFAULT_BREATH);
        final String shown = color + "@" + percent + (breath ? "~" : "");
        if (!force && shown.equals(sShown)) return;
        final boolean wasColor = sShown != null && !sShown.startsWith("@");
        sShown = shown;
        sHandler.removeCallbacksAndMessages(LedController.class);
        if (color.isEmpty()) {
            // Until the slider is moved once, the LineageOS setting is left as it is.
            if (wasColor || PartsPrefs.get(context).contains(PREF_BRIGHTNESS)
                    || PartsPrefs.get(context).getBoolean(PREF_NIGHT, false)) {
                putButtonBrightness(context, percent / 100f);
            }
            // The colour goes; the next touch lights the LED again as button backlight.
            if (wasColor && !notificationBlinking()) writeColor("000000", 0, false);
            return;
        }
        putButtonBrightness(context, 0f);
        final Runnable write = () -> {
            if (!notificationBlinking()) writeColor(color, percent, breath);
        };
        write.run();
        sHandler.postAtTime(write, LedController.class, SystemClock.uptimeMillis() + SETTLE_MS);
    }

    // ---- night

    /** An alarm at the next start or end of the night, to change the brightness then. */
    private static void scheduleNight(Context context) {
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (sNightAlarm != null) alarms.cancel(sNightAlarm);
        sNightAlarm = null;
        if (!PartsPrefs.get(context).getBoolean(PREF_NIGHT, false)) return;
        final Rule.TimeWindow window = nightWindow(context);
        final Calendar next = Calendar.getInstance();
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        final int nowMin = next.get(Calendar.HOUR_OF_DAY) * 60 + next.get(Calendar.MINUTE);
        int best = Integer.MAX_VALUE;
        for (int edge : new int[] {window.startMin, window.endMin}) {
            int wait = edge - nowMin;
            if (wait <= 0) wait += 24 * 60;
            best = Math.min(best, wait);
        }
        next.add(Calendar.MINUTE, best);
        final Context app = context.getApplicationContext();
        sNightAlarm = () -> {
            sNightAlarm = null;
            apply(app, true);
            scheduleNight(app);
        };
        alarms.setExact(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), "PocketDSLedNight",
                sNightAlarm, sHandler);
    }

    // ---- hardware

    /** A notification blinks on the LED (the HAL sets the timer trigger): leave it alone. */
    private static boolean notificationBlinking() {
        for (String channel : CHANNELS) {
            if (read(LEDS + channel + "/trigger").contains("[timer]")) return true;
        }
        return false;
    }

    private static void writeColor(String color, int percent, boolean breath) {
        int rgb;
        try {
            rgb = Integer.parseInt(color, 16);
        } catch (NumberFormatException e) {
            rgb = 0;
        }
        for (int i = 0; i < CHANNELS.length; i++) {
            final int level = ((rgb >> (16 - 8 * i)) & 0xFF) * percent / 100;
            final String dir = LEDS + CHANNELS[i] + "/";
            // The breathing pattern of the PMIC runs on the channels that are lit.
            write(dir + "breath", "0");
            write(dir + "brightness", Integer.toString(level));
            if (breath && level > 0) write(dir + "breath", "1");
        }
    }

    private static String read(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            final String line = reader.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        } catch (IOException e) {
            Log.w(TAG, "Cannot write " + path, e);
        }
    }

    private static void putButtonBrightness(Context context, float value) {
        final Bundle args = new Bundle();
        args.putString("value", Float.toString(value));
        try {
            context.getContentResolver().call(android.net.Uri.parse(LINEAGE_URI),
                    "PUT_secure", BUTTON_BRIGHTNESS, args);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot set " + BUTTON_BRIGHTNESS, e);
        }
    }
}
