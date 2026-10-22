/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Storage of the rules (JSON in the shared preferences) and of what they changed. */
final class RuleStore {
    private static final String TAG = "PocketDSRules";
    static final String KEY_RULES = "rules_json";
    static final String KEY_MASTER = "rules_enabled";
    private static final String KEY_SEEDED = "rules_seeded_v1";
    private static final String KEY_SAVED = "rule_saved_";

    private final SharedPreferences mPrefs;

    RuleStore(Context context) {
        mPrefs = PartsPrefs.get(context);
        seed();
    }

    List<Rule> load() {
        final List<Rule> rules = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(mPrefs.getString(KEY_RULES, "[]"));
            for (int i = 0; i < array.length(); i++) rules.add(Rule.fromJson(array.getJSONObject(i)));
        } catch (JSONException e) {
            Log.e(TAG, "Rules unreadable", e);
        }
        return rules;
    }

    void save(List<Rule> rules) {
        try {
            final JSONArray array = new JSONArray();
            for (Rule rule : rules) array.put(rule.toJson());
            mPrefs.edit().putString(KEY_RULES, array.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Rules not saved", e);
        }
    }

    Rule find(String id) {
        for (Rule rule : load()) if (rule.id.equals(id)) return rule;
        return null;
    }

    void put(Rule rule) {
        final List<Rule> rules = load();
        boolean found = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(rule.id)) {
                rules.set(i, rule);
                found = true;
            }
        }
        if (!found) rules.add(rule);
        save(rules);
    }

    void remove(String id) {
        final List<Rule> rules = load();
        rules.removeIf(rule -> rule.id.equals(id));
        save(rules);
    }

    boolean masterEnabled() {
        return mPrefs.getBoolean(KEY_MASTER, true);
    }

    /** What a rule changed (to undo it), kept across reboots; null when nothing. */
    String savedState(String ruleId) {
        return mPrefs.getString(KEY_SAVED + ruleId, null);
    }

    void saveState(String ruleId, String json) {
        mPrefs.edit().putString(KEY_SAVED + ruleId, json).apply();
    }

    void clearState(String ruleId) {
        mPrefs.edit().remove(KEY_SAVED + ruleId).apply();
    }

    /** No rule is created for the user: the ready-made ones are offered in "Ready-made". */
    private void seed() {
        mPrefs.edit().putBoolean(KEY_SEEDED, true).apply();
    }

    static List<Rule> presets() {
        final List<Rule> list = new ArrayList<>();
        list.add(preset("Lid closed: radios off", "\uD83D\uDCD5", 1, 120, true,
                new Rule.Condition[] {new Rule.Condition(Rule.C_LID_CLOSED, "")},
                new Rule.Action(Rule.A_WIFI, "off"), new Rule.Action(Rule.A_BLUETOOTH, "off"),
                new Rule.Action(Rule.A_SYNC, "off")));
        list.add(preset("Screen off for 15 minutes: deep saving", "\uD83C\uDF19", 1, 900, true,
                new Rule.Condition[] {new Rule.Condition(Rule.C_SCREEN_OFF, "")},
                new Rule.Action(Rule.A_WIFI, "off"), new Rule.Action(Rule.A_BLUETOOTH, "off"),
                new Rule.Action(Rule.A_LOCATION, "off"), new Rule.Action(Rule.A_SYNC, "off")));
        list.add(preset("On battery: 60 Hz", "\uD83D\uDCFA", 4, 0, false,
                new Rule.Condition[] {new Rule.Condition(Rule.C_UNPLUGGED, "")},
                new Rule.Action(Rule.A_REFRESH, "60")));
        list.add(preset("Battery below 20 %: saver", "\uD83D\uDD0B", 3, 0, false,
                new Rule.Condition[] {new Rule.Condition(Rule.C_BATTERY_BELOW, "20"),
                    new Rule.Condition(Rule.C_UNPLUGGED, "")},
                new Rule.Action(Rule.A_BATTERY_SAVER, "on"), new Rule.Action(Rule.A_REFRESH, "60"),
                new Rule.Action(Rule.A_SMART_PIXELS, "on")));
        list.add(preset("Battery below 10 %: main screen only", "\uD83D\uDD0B", 2, 0, false,
                new Rule.Condition[] {new Rule.Condition(Rule.C_BATTERY_BELOW, "10"),
                    new Rule.Condition(Rule.C_UNPLUGGED, "")},
                new Rule.Action(Rule.A_SCREEN_MODE, "3"), new Rule.Action(Rule.A_WIFI, "off"),
                new Rule.Action(Rule.A_BLUETOOTH, "off")));
        list.add(preset("On battery: screen timeout 30 s", "\u23F1\uFE0F", 3, 0, false,
                new Rule.Condition[] {new Rule.Condition(Rule.C_UNPLUGGED, "")},
                new Rule.Action(Rule.A_TIMEOUT, "30000")));
        list.add(preset("Night: dark and quiet", "\uD83C\uDF11", 1, 0, false,
                new Rule.Condition[] {new Rule.Condition(Rule.C_TIME, "22:00-07:00;1111111")},
                new Rule.Action(Rule.A_DARK_MODE, "on"), new Rule.Action(Rule.A_BRIGHTNESS, "20"),
                new Rule.Action(Rule.A_SOUND, "1")));
        list.add(preset("Too hot: cool down", "\uD83C\uDF21\uFE0F", 2, 60, false,
                new Rule.Condition[] {new Rule.Condition(Rule.C_TEMP_ABOVE, "42")},
                new Rule.Action(Rule.A_REFRESH, "60"), new Rule.Action(Rule.A_BRIGHTNESS, "40"),
                new Rule.Action(Rule.A_BATTERY_SAVER, "on")));
        list.add(preset("Away from Wi-Fi, screen off: save", "\uD83D\uDEAB", 7, 600, true,
                new Rule.Condition[] {new Rule.Condition(Rule.C_NO_WIFI, ""),
                    new Rule.Condition(Rule.C_SCREEN_OFF, "")},
                new Rule.Action(Rule.A_SYNC, "off"), new Rule.Action(Rule.A_LOCATION, "off")));
        list.add(preset("Headphones: sound on", "\uD83C\uDFA7", 6, 0, false,
                new Rule.Condition[] {new Rule.Condition(Rule.C_BT_DEVICE, "")},
                new Rule.Action(Rule.A_SOUND, "2")));
        return list;
    }

    private static Rule preset(String name, String icon, int color, int delaySec,
            boolean onlyOnBattery, Rule.Condition[] conditions, Rule.Action... actions) {
        final Rule rule = new Rule();
        rule.name = name;
        rule.icon = icon;
        rule.color = color;
        rule.delaySec = delaySec;
        rule.onlyOnBattery = onlyOnBattery;
        rule.enabled = false;
        for (Rule.Condition condition : conditions) rule.conditions.add(condition);
        for (Rule.Action action : actions) rule.actions.add(action);
        return rule;
    }
}
