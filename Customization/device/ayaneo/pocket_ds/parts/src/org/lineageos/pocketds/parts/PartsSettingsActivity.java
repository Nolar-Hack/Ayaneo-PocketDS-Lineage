/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Home of PocketDS Extra: a banner with the state of the console and one bubble per category.
 * Each category opens its own page ({@link SectionActivity}, {@link RulesActivity}).
 */
public class PartsSettingsActivity extends Activity {

    private int mShownAccent;

    /** The profile changed while this screen was away: it is built again in the new colour. */
    private void checkAccent() {
        if (mShownAccent != 0 && mShownAccent != Accent.current(this)) {
            recreate();
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mShownAccent = Accent.current(this);
        setTitle("PocketDS Extra");
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAccent();
        build();
    }

    private void build() {
        final Ui ui = new Ui(this);
        final LinearLayout page = ui.page(this);
        final SharedPreferences prefs = PartsPrefs.get(this);

        final Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String state = "";
        if (battery != null) {
            final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100
                    / Math.max(1, battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            final boolean charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            state = "Battery " + level + " %" + (charging ? " · charging" : "")
                    + " · " + battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
                    + " °C";
        }
        ui.hero(page, "🕹️", Ui.BLUE, "PocketDS Extra", state.isEmpty() ? "Settings of the console"
                : "Settings of the console · " + state);

        final RuleStore store = new RuleStore(this);
        int on = 0;
        final List<Rule> rules = store.load();
        for (Rule rule : rules) if (rule.enabled) on++;
        final String automation = !store.masterEnabled() ? "Paused"
                : on + " of " + rules.size() + " rules on";

        final List<View> tiles = new ArrayList<>();
        tiles.add(ui.tile("🖥️", Ui.TEAL, "Screens", Screens.status(this),
                open(SectionActivity.SCREENS)));
        tiles.add(ui.tile("🔊", Ui.BLUE, "Sound", "Volumes, outputs, apps",
                open(SectionActivity.SOUND)));
        tiles.add(ui.tile("📱", Ui.PINK, "Applications", "Grid, folders, wallpapers",
                open(SectionActivity.LAUNCHER)));
        tiles.add(ui.tile("🎮", Ui.PURPLE, "Games", "A profile for each game",
                open(SectionActivity.GAMES)));
        tiles.add(ui.tile("🤖", Ui.PURPLE, "Automation", automation,
                v -> startActivity(new Intent(this, RulesActivity.class))));
        tiles.add(ui.tile("✨", Ui.PINK, "Smart Pixels",
                prefs.getBoolean(PartsPrefs.SMART_PIXELS, false)
                        ? "On · " + PartsPrefs.getInt(prefs, PartsPrefs.SMART_PIXELS_PERCENT,
                                12) + " %" : "Off", open(SectionActivity.PIXELS)));
        tiles.add(ui.tile("🔋", Ui.GREEN, "Battery & radios",
                batteryStatus(prefs), open(SectionActivity.BATTERY)));
        tiles.add(ui.tile("⏻", Ui.RED, "Power off", powerOffStatus(prefs),
                open(SectionActivity.POWER_OFF)));
        tiles.add(ui.tile("🧰", Ui.GREY, "Tools & logs", "Collect the logs",
                open(SectionActivity.TOOLS)));
        ui.grid(page, tiles);
    }

    private View.OnClickListener open(String section) {
        return v -> startActivity(new Intent(this, SectionActivity.class)
                .putExtra(SectionActivity.EXTRA_SECTION, section));
    }

    static String delayText(int minutes) {
        if (minutes <= 0) return "Never";
        if (minutes < 60) return minutes + " min";
        return minutes / 60 + " h";
    }

    private static String batteryStatus(SharedPreferences prefs) {
        final int bt = PartsPrefs.getInt(prefs, PartsPrefs.BT_IDLE_OFF_MIN, 0);
        final int screen = PartsPrefs.getInt(prefs, PartsPrefs.SUSPEND_DELAY_MIN, 0);
        if (bt <= 0 && screen <= 0) return "Nothing set";
        final StringBuilder sb = new StringBuilder();
        if (screen > 0) sb.append("Radios off after ").append(delayText(screen));
        if (bt > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("Bluetooth idle ").append(delayText(bt));
        }
        return sb.toString();
    }

    private static String powerOffStatus(SharedPreferences prefs) {
        final int lid = PartsPrefs.getInt(prefs, PartsPrefs.POWEROFF_LID_MIN, 0);
        final int screen = PartsPrefs.getInt(prefs, PartsPrefs.POWEROFF_SCREEN_MIN, 0);
        if (lid <= 0 && screen <= 0) return "Never";
        final StringBuilder sb = new StringBuilder();
        if (lid > 0) sb.append("Lid closed ").append(delayText(lid));
        if (screen > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("Screen off ").append(delayText(screen));
        }
        return sb.toString();
    }
}
