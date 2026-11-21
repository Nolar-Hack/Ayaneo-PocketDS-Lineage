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
        tiles.add(ui.tile("🎮", Ui.PURPLE, "Profiles", "Performance and controller, per app",
                open(SectionActivity.GAMES)));
        tiles.add(ui.tile("🕹️", Ui.BLUE, "Controller", controllerStatus(),
                open(SectionActivity.CONTROLLER)));
        tiles.add(ui.tile("🧭", Ui.TEAL, "Motion sensors", "Gyroscope direction, calibration",
                open(SectionActivity.MOTION)));
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
        tiles.add(ui.tile("⬆️", Ui.TEAL, "System update", updateStatus(),
                open(SectionActivity.UPDATES)));
        tiles.add(ui.tile("🧰", Ui.GREY, "Tools & logs", "Collect the logs",
                open(SectionActivity.TOOLS)));
        ui.grid(page, tiles);
        aboutCard(ui, page);
    }

    private static final String DISCORD = "https://discord.gg/mG8xu7pA42";
    private static final String GITHUB = "https://github.com/Nolar-Hack";
    private static final String TIPEEE = "https://en.tipeee.com/nolar";

    /** Under the tiles, as wide as a line of them: version and the links of the community. */
    private void aboutCard(Ui ui, android.widget.LinearLayout page) {
        final android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        card.setBackground(ui.outlined(ui.surface, Ui.LINE, 16));

        final android.widget.LinearLayout head = new android.widget.LinearLayout(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final android.widget.TextView bubble = ui.bubble("ℹ️", Ui.BLUE, 60);
        head.addView(bubble);
        ((android.widget.LinearLayout.LayoutParams) bubble.getLayoutParams()).rightMargin =
                ui.dp(16);
        final android.widget.LinearLayout texts = new android.widget.LinearLayout(this);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        texts.addView(ui.label("About & community", 16, ui.text, true));
        final android.widget.TextView sub = ui.label("PocketDS " + Updates.version()
                + " · free and open source · questions, bugs and ideas on Discord", 14,
                ui.textDim, false);
        sub.setPadding(0, ui.dp(4), 0, 0);
        texts.addView(sub);
        head.addView(texts, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        final android.widget.LinearLayout links = new android.widget.LinearLayout(this);
        links.setPadding(0, ui.dp(16), 0, 0);
        link(ui, links, "discord", "Discord", 0xFF5865F2, DISCORD, false);
        link(ui, links, "github", "GitHub", 0xFF3A4150, GITHUB, false);
        // The Tipeee logo is its name: shown alone, no label.
        link(ui, links, "tipeee", null, 0xFFD64758, TIPEEE, true);
        card.addView(links);

        page.addView(card, ui.matchWrap(0, 14, 0, 0));
    }

    /**
     * A coloured button: the logo (res/drawable/ic_<glyph>) and the name, side by side; with no
     * name, the logo alone at its own proportions (a wordmark).
     */
    private void link(Ui ui, android.widget.LinearLayout line, String glyph, String label,
            int color, final String url, boolean last) {
        final android.widget.LinearLayout button = new android.widget.LinearLayout(this);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14));
        button.setBackground(ui.rounded(color, 12));
        final android.graphics.drawable.Drawable logo = ui.glyphDrawable(glyph, 0xFFFFFFFF);
        if (logo != null) {
            final android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(logo);
            final int height = ui.dp(label == null ? 24 : 22);
            final int width = label == null ? height * logo.getIntrinsicWidth()
                    / Math.max(1, logo.getIntrinsicHeight()) : height;
            final android.widget.LinearLayout.LayoutParams size =
                    new android.widget.LinearLayout.LayoutParams(width, height);
            if (label != null) size.rightMargin = ui.dp(10);
            button.addView(icon, size);
        }
        if (label != null || logo == null) {
            button.addView(ui.label(label != null ? label : "Tipeee", 15, 0xFFFFFFFF, true));
        }
        button.setOnClickListener(v -> openLink(url));
        final android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!last) params.rightMargin = ui.dp(12);
        line.addView(button, params);
    }

    /** Opens the link in a browser; with no browser on the console, copies it instead. */
    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException e) {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                    android.content.ClipData.newPlainText("link", url));
            android.widget.Toast.makeText(this, "No browser: link copied (" + url + ")",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private String controllerStatus() {
        final int n = ControllerProfiles.all(PartsPrefs.get(this)).size();
        return n == 0 ? "Buttons, sticks, profiles" : n + (n == 1 ? " profile" : " profiles");
    }

    private String updateStatus() {
        if (Updates.restartPending(this)) return "Restart to finish the update";
        final Updates.Info info = Updates.available(this);
        return info != null ? "Update " + info.version + " available"
                : "Up to date · " + Updates.version();
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
