/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An automation rule: when its conditions hold (all of them, or any of them, for the delay), the
 * actions are applied; when they stop holding, the actions are undone (if asked). Stored as JSON,
 * see {@link RuleStore}.
 */
final class Rule {
    // Conditions
    static final String C_SCREEN_OFF = "screen_off";
    static final String C_SCREEN_ON = "screen_on";
    static final String C_LID_CLOSED = "lid_closed";
    static final String C_LID_OPEN = "lid_open";
    static final String C_UNPLUGGED = "unplugged";
    static final String C_PLUGGED = "plugged";
    static final String C_BATTERY_BELOW = "battery_below";   // param: percent
    static final String C_BATTERY_ABOVE = "battery_above";   // param: percent
    static final String C_TEMP_ABOVE = "temp_above";         // param: degrees Celsius
    static final String C_APP = "app";                       // param: package name
    static final String C_TIME = "time";                     // param: see TimeWindow
    static final String C_WIFI = "wifi_connected";
    static final String C_NO_WIFI = "wifi_disconnected";
    static final String C_BT_DEVICE = "bt_connected";
    static final String C_NO_BT_DEVICE = "bt_none";
    static final String[] CONDITIONS = {C_SCREEN_OFF, C_SCREEN_ON, C_LID_CLOSED, C_LID_OPEN,
        C_UNPLUGGED, C_PLUGGED, C_BATTERY_BELOW, C_BATTERY_ABOVE, C_TEMP_ABOVE, C_APP, C_TIME,
        C_WIFI, C_NO_WIFI, C_BT_DEVICE, C_NO_BT_DEVICE};
    static final String[] CONDITION_LABELS = {"Screen is off", "Screen is on", "Lid is closed",
        "Lid is open", "On battery", "Charging", "Battery below", "Battery above",
        "Battery temperature above", "App in the foreground", "Between two times",
        "Connected to Wi-Fi", "Not connected to Wi-Fi", "Bluetooth device connected",
        "No Bluetooth device connected"};
    static final String[] CONDITION_ICONS = {"🌙", "☀️", "📕",
        "📖", "🔋", "⚡", "🪫", "🔋", "🌡️",
        "🎮", "⏰", "📶", "🚫", "🎧", "🔕"};

    // Actions
    static final String A_WIFI = "wifi";                     // on / off
    static final String A_BLUETOOTH = "bluetooth";           // on / off
    static final String A_LOCATION = "location";             // on / off
    static final String A_SYNC = "sync";                     // on / off
    static final String A_BATTERY_SAVER = "battery_saver";   // on / off
    static final String A_SMART_PIXELS = "smart_pixels";     // on / off
    static final String A_DARK_MODE = "dark_mode";           // on / off
    static final String A_ROTATION = "auto_rotate";          // on / off
    static final String A_REFRESH = "refresh_rate";          // 60 / 120
    static final String A_SCREEN_MODE = "screen_mode";       // 1 both / 3 main / 4 bottom
    static final String A_TIMEOUT = "screen_timeout";        // milliseconds
    static final String A_BRIGHTNESS = "brightness";         // percent
    static final String A_SOUND = "sound_mode";              // 2 normal / 1 vibrate / 0 silent
    static final String A_FORCE_STOP = "force_stop";         // packages (comma separated)
    static final String A_RESTRICT_BG = "restrict_background"; // packages (comma separated)
    static final String[] ACTIONS = {A_WIFI, A_BLUETOOTH, A_LOCATION, A_SYNC, A_BATTERY_SAVER,
        A_SMART_PIXELS, A_DARK_MODE, A_ROTATION, A_REFRESH, A_SCREEN_MODE, A_TIMEOUT, A_BRIGHTNESS,
        A_SOUND, A_FORCE_STOP, A_RESTRICT_BG};
    static final String[] ACTION_LABELS = {"Wi-Fi", "Bluetooth", "Location", "Account sync",
        "Battery Saver", "Smart Pixels", "Dark theme", "Auto-rotate", "Refresh rate", "Screens",
        "Screen timeout", "Brightness", "Sound mode", "Stop apps", "Restrict apps in background"};
    static final String[] ACTION_ICONS = {"📶", "🟦", "📍",
        "🔄", "🔋", "✨", "🌑", "🔃", "📺",
        "🖥️", "⏱️", "🔆", "🔔", "⛔", "🚧"};

    // Looks of a rule
    static final String[] ICONS = {"⚡", "🌙", "🔋", "🎮",
        "📕", "📶", "🔆", "🌡️", "🎧",
        "✨", "⏰", "🚀"};
    static final int[] COLORS = {0xFF4C8DFF, 0xFF7C5CFF, 0xFFFF6B6B, 0xFFFFA33D, 0xFF2EC4A6,
        0xFF3FB950, 0xFFE0559E, 0xFF8892A6};
    static final String[] COLOR_NAMES = {"Blue", "Purple", "Red", "Orange", "Teal", "Green",
        "Pink", "Grey"};

    static final class Condition {
        String type;
        String param;

        Condition(String type, String param) {
            this.type = type;
            this.param = param;
        }
    }

    static final class Action {
        String type;
        String value;

        Action(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    String id = UUID.randomUUID().toString();
    String name = "";
    String icon = ICONS[0];
    int color;                    // index in COLORS
    boolean enabled;
    boolean matchAll = true;      // all the conditions must hold (else: any of them)
    int delaySec;
    boolean onlyOnBattery;
    boolean undo = true;
    boolean notify;
    final List<Condition> conditions = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();

    int colorValue() {
        return COLORS[Math.max(0, Math.min(color, COLORS.length - 1))];
    }

    JSONObject toJson() throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("id", id).put("name", name).put("icon", icon).put("color", color)
                .put("enabled", enabled).put("matchAll", matchAll).put("delay", delaySec)
                .put("onlyOnBattery", onlyOnBattery).put("undo", undo).put("notify", notify);
        final JSONArray conds = new JSONArray();
        for (Condition c : conditions) {
            conds.put(new JSONObject().put("type", c.type).put("param", c.param));
        }
        json.put("conditions", conds);
        final JSONArray list = new JSONArray();
        for (Action a : actions) {
            list.put(new JSONObject().put("type", a.type).put("value", a.value));
        }
        json.put("actions", list);
        return json;
    }

    static Rule fromJson(JSONObject json) throws JSONException {
        final Rule rule = new Rule();
        rule.id = json.getString("id");
        rule.name = json.optString("name", "");
        rule.icon = json.optString("icon", ICONS[0]);
        rule.color = json.optInt("color", 0);
        rule.enabled = json.optBoolean("enabled", false);
        rule.matchAll = json.optBoolean("matchAll", true);
        rule.delaySec = json.optInt("delay", 0);
        rule.onlyOnBattery = json.optBoolean("onlyOnBattery", false);
        rule.undo = json.optBoolean("undo", true);
        rule.notify = json.optBoolean("notify", false);
        final JSONArray conds = json.optJSONArray("conditions");
        if (conds != null) {
            for (int i = 0; i < conds.length(); i++) {
                final JSONObject c = conds.getJSONObject(i);
                rule.conditions.add(new Condition(c.getString("type"), c.optString("param", "")));
            }
        } else if (json.has("trigger")) {
            // First version of the rules: one trigger.
            final String trigger = json.getString("trigger");
            rule.conditions.add(new Condition(trigger, json.optString("param", "")));
            // The old "battery below" only held while on battery.
            if (C_BATTERY_BELOW.equals(trigger)) rule.conditions.add(new Condition(C_UNPLUGGED, ""));
        }
        final JSONArray list = json.optJSONArray("actions");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                final JSONObject a = list.getJSONObject(i);
                rule.actions.add(new Action(a.getString("type"), a.optString("value", "")));
            }
        }
        return rule;
    }

    static int index(String[] ids, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return i;
        return -1;
    }

    static String label(String[] ids, String[] labels, String id) {
        final int i = index(ids, id);
        return i < 0 ? id : labels[i];
    }

    /** True when the condition needs a parameter chosen by the user. */
    static boolean needsParam(String type) {
        return C_BATTERY_BELOW.equals(type) || C_BATTERY_ABOVE.equals(type)
                || C_TEMP_ABOVE.equals(type) || C_APP.equals(type) || C_TIME.equals(type);
    }

    /** The condition in words, with its parameter. */
    static String describe(Condition c, String appLabel) {
        final String base = label(CONDITIONS, CONDITION_LABELS, c.type);
        switch (c.type) {
            case C_BATTERY_BELOW:
            case C_BATTERY_ABOVE:
                return base + " " + c.param + " %";
            case C_TEMP_ABOVE:
                return base + " " + c.param + " °C";
            case C_APP:
                return base + ": " + (appLabel == null ? c.param : appLabel);
            case C_TIME:
                return TimeWindow.parse(c.param).describe();
            default:
                return base;
        }
    }

    static String describeDelay(int seconds) {
        if (seconds <= 0) return "at once";
        if (seconds < 60) return "after " + seconds + " s";
        if (seconds < 3600) return "after " + seconds / 60 + " min";
        return "after " + seconds / 3600 + " h";
    }

    /** One line that tells what the rule does. */
    String summary() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) sb.append(matchAll ? " + " : " or ");
            sb.append(describe(conditions.get(i), null));
        }
        if (conditions.isEmpty()) sb.append("No condition");
        if (delaySec > 0) sb.append(" · ").append(describeDelay(delaySec));
        sb.append("\n→ ");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(label(ACTIONS, ACTION_LABELS, actions.get(i).type));
        }
        return sb.toString();
    }

    /**
     * A time window "HH:MM-HH:MM;dddddddd": start, end and the days (Monday to Sunday, 1 = on) on
     * which it starts. A window that ends before it starts runs over midnight.
     */
    static final class TimeWindow {
        int startMin = 22 * 60;
        int endMin = 7 * 60;
        final boolean[] days = {true, true, true, true, true, true, true};

        static TimeWindow parse(String text) {
            final TimeWindow w = new TimeWindow();
            try {
                final String[] parts = text.split(";");
                final String[] range = parts[0].split("-");
                w.startMin = minutes(range[0]);
                w.endMin = minutes(range[1]);
                if (parts.length > 1 && parts[1].length() == 7) {
                    for (int i = 0; i < 7; i++) w.days[i] = parts[1].charAt(i) == '1';
                }
            } catch (RuntimeException e) {
                // Not set yet: the defaults apply.
            }
            return w;
        }

        private static int minutes(String hhmm) {
            final String[] t = hhmm.trim().split(":");
            return Integer.parseInt(t[0]) * 60 + Integer.parseInt(t[1]);
        }

        static String clock(int minutes) {
            return String.format("%02d:%02d", minutes / 60 % 24, minutes % 60);
        }

        String encode() {
            final StringBuilder sb = new StringBuilder(clock(startMin)).append('-')
                    .append(clock(endMin)).append(';');
            for (boolean day : days) sb.append(day ? '1' : '0');
            return sb.toString();
        }

        String describe() {
            final StringBuilder sb = new StringBuilder("From ").append(clock(startMin))
                    .append(" to ").append(clock(endMin));
            int on = 0;
            for (boolean day : days) if (day) on++;
            if (on < 7) {
                final String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
                sb.append(" (");
                boolean first = true;
                for (int i = 0; i < 7; i++) {
                    if (!days[i]) continue;
                    if (!first) sb.append(' ');
                    sb.append(names[i]);
                    first = false;
                }
                sb.append(')');
            }
            return sb.toString();
        }

        /** @param dayOfWeek 0 = Monday ... 6 = Sunday; @param minuteOfDay 0..1439 */
        boolean contains(int dayOfWeek, int minuteOfDay) {
            if (startMin == endMin) return days[dayOfWeek];
            if (startMin < endMin) {
                return days[dayOfWeek] && minuteOfDay >= startMin && minuteOfDay < endMin;
            }
            // Over midnight: the evening belongs to the day it starts, the morning to the next.
            if (minuteOfDay >= startMin) return days[dayOfWeek];
            return minuteOfDay < endMin && days[(dayOfWeek + 6) % 7];
        }
    }
}
