/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.view.MenuItem;
import android.widget.LinearLayout;

/** One category of PocketDS Extra: a banner and cards of options. */
public class SectionActivity extends Activity {

    private int mShownAccent;

    /** The profile changed while this screen was away: it is built again in the new colour. */
    private void checkAccent() {
        if (mShownAccent != 0 && mShownAccent != Accent.current(this)) {
            recreate();
        }
    }
    static final String EXTRA_SECTION = "section";
    static final String PIXELS = "pixels";
    static final String BATTERY = "battery";
    static final String POWER_OFF = "power_off";
    static final String TOOLS = "tools";
    static final String SCREENS = "screens";
    static final String SOUND = "sound";
    static final String LAUNCHER = "launcher";
    static final String GAMES = "games";
    static final String SETUP = "setup";
    static final String EXTRA_SETUP = "setup_id";
    static final String CONTROLLER = "controller";
    static final String UPDATES = "updates";
    static final String MOTION = "motion";
    static final String ROOT = "root";

    private static final String[] DELAYS = {"Never", "1 min", "5 min", "15 min", "30 min", "1 h"};
    private static final String[] DELAY_VALUES = {"0", "1", "5", "15", "30", "60"};

    /** The category to show; the entries of the Settings app give it without an extra. */
    protected String section() {
        return getIntent().getStringExtra(EXTRA_SECTION);
    }

    private boolean mResumedBefore;

    @Override
    protected void onResume() {
        super.onResume();
        checkAccent();
        // Back from the remapping of a profile: the list shows what was saved.
        if (mResumedBefore && (CONTROLLER.equals(section()) || GAMES.equals(section())
                || SETUP.equals(section()))) {
            recreate();
        }
        mResumedBefore = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mShownAccent = Accent.current(this);
        final Ui ui = new Ui(this);
        final LinearLayout page = ui.page(this);
        final String section = section();
        if (section == null) {
            finish();
            return;
        }
        switch (section) {
            case PIXELS:
                pixels(ui, page);
                break;
            case BATTERY:
                battery(ui, page);
                break;
            case POWER_OFF:
                powerOff(ui, page);
                break;
            case SCREENS:
                screens(ui, page);
                break;
            case SOUND:
                sound(ui, page);
                break;
            case LAUNCHER:
                launcher(ui, page);
                break;
            case GAMES:
                games(ui, page);
                break;
            case SETUP:
                setupPage(ui, page);
                break;
            case CONTROLLER:
                controllerPage(ui, page);
                break;
            case UPDATES:
                updates(ui, page);
                break;
            case MOTION:
                motion(ui, page);
                break;
            case ROOT:
                rootPage(ui, page);
                break;
            default:
                tools(ui, page);
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void pixels(Ui ui, LinearLayout page) {
        setTitle("Smart Pixels");
        ui.hero(page, "✨", Ui.PINK, "Smart Pixels",
                "Turns off some pixels to save power and limit burn-in");
        final LinearLayout master = ui.card(page);
        final Ui.Row main = ui.switchRow(master, "✨", Ui.PINK, "Smart Pixels",
                "Best for the OLED screen", PartsPrefs.SMART_PIXELS, false);
        final LinearLayout options = ui.card(page);
        ui.choiceRow(options, "🔳", Ui.PURPLE, "Pixels turned off",
                PartsPrefs.SMART_PIXELS_PERCENT,
                new String[] {"3 %", "6 %", "12 %", "25 %", "50 %"},
                new String[] {"3", "6", "12", "25", "50"}, "12");
        ui.choiceRow(options, "🔄", Ui.BLUE, "Move the pattern every",
                PartsPrefs.SMART_PIXELS_SHIFT_MIN,
                new String[] {"Fixed", "1 min", "5 min", "10 min", "30 min"},
                new String[] {"0", "1", "5", "10", "30"}, "5");
        ui.choiceRow(options, "🖥️", Ui.TEAL, "Screens",
                PartsPrefs.SMART_PIXELS_SCREEN,
                new String[] {"Main screen (OLED)", "Bottom screen", "Both"},
                new String[] {PartsPrefs.SCREEN_MAIN, PartsPrefs.SCREEN_SECOND,
                    PartsPrefs.SCREEN_BOTH}, PartsPrefs.SCREEN_MAIN);
        final LinearLayout saver = ui.card(page);
        ui.switchRow(saver, "🔋", Ui.GREEN, "Enable with Battery Saver",
                "Turns on with Android's Battery Saver",
                PartsPrefs.SMART_PIXELS_ON_SAVER, false);
        ui.dependOn(main, options);

        ui.section(page, "Screen off");
        final LinearLayout animation = ui.card(page);
        ui.switchRow(animation, "📺", Ui.ORANGE, "Old TV (CRT) animation",
                "The screen shrinks to a line when it turns off", PartsPrefs.CRT_SCREEN_OFF, true);
    }

    private void screens(Ui ui, LinearLayout page) {
        setTitle("Screens");
        ui.hero(page, "🖥️", Ui.TEAL, "Screens",
                "Brightness, touch, layout and screen off");

        ui.section(page, "Brightness");
        // One tile for each screen, side by side.
        final LinearLayout brightnessRow = new LinearLayout(this);
        brightnessRow.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(brightnessRow, ui.matchWrap(0, 12, 0, 0));
        final LinearLayout mainTile = ui.cardInRow(brightnessRow, false);
        final LinearLayout bottomTile = ui.cardInRow(brightnessRow, true);
        final android.content.ContentResolver resolver = getContentResolver();
        final SharedPreferences prefs = PartsPrefs.get(this);
        final int top = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128);
        int bottomValue = Settings.Global.getInt(resolver, "secondary_screen_brightness", -1);
        if (bottomValue <= 0) bottomValue = top;
        final Ui.Row[] rows = new Ui.Row[2];
        rows[0] = ui.sliderRow(mainTile, "☀️", Ui.ORANGE, "Main screen", top, 255, value -> {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, value));
            if (prefs.getBoolean(PartsPrefs.BRIGHTNESS_LINK, false)) {
                Settings.Global.putInt(resolver, "secondary_screen_brightness", Math.max(1, value));
            } else if (Settings.Global.getInt(resolver, "secondary_screen_brightness", -1) <= 0) {
                // Until it has a level of its own the bottom screen follows the main one: pin it.
                Settings.Global.putInt(resolver, "secondary_screen_brightness",
                        Settings.Global.getInt(resolver, "secondary_screen_brightness", 128));
            }
        });
        ui.sliderRow(bottomTile, "☀️", Ui.TEAL, "Bottom screen", bottomValue, 255, value ->
                Settings.Global.putInt(resolver, "secondary_screen_brightness",
                        Math.max(1, value)));
        final LinearLayout link = ui.card(page);
        ui.switchRow(link, "🔗", Ui.BLUE, "Link the two screens",
                "Moving one slider moves the other", PartsPrefs.BRIGHTNESS_LINK, false);

        ui.section(page, "Screen off");
        final LinearLayout off = ui.card(page);
        ui.valueChoiceRow(off, "⏱️", Ui.BLUE, "Turn the screens off after",
                new String[] {"15 s", "30 s", "1 min", "2 min", "5 min", "10 min", "30 min"},
                new String[] {"15000", "30000", "60000", "120000", "300000", "600000", "1800000"},
                () -> Integer.toString(Settings.System.getInt(resolver,
                        Settings.System.SCREEN_OFF_TIMEOUT, 60000)),
                value -> Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT,
                        Integer.parseInt(value)));
        ui.valueSwitchRow(off, "📺", Ui.ORANGE, "Old TV (CRT) animation",
                "The screen shrinks to a line when it turns off",
                () -> prefs.getBoolean(PartsPrefs.CRT_SCREEN_OFF, true),
                on -> prefs.edit().putBoolean(PartsPrefs.CRT_SCREEN_OFF, on).apply());

        ui.section(page, "Touch");
        final LinearLayout touch = ui.card(page);
        ui.choiceRow(touch, "👆", Ui.PINK, "Sensitivity of the main screen",
                PartsPrefs.TOUCH_THRESHOLD,
                new String[] {"Very high", "High", "Normal", "Low", "Very low"},
                new String[] {"60", "90", "120", "150", "180"}, "120");
        ui.note(page, "Lower it if the screen reacts to a finger that is only near.");

        ui.section(page, "Layout");
        final LinearLayout layout = ui.card(page);
        ui.valueSwitchRow(layout, "⇄", Ui.BLUE, "Swap the screens",
                "The two screens trade places",
                () -> Screens.isSwapped(this), on -> Screens.setSwapped(this, on));
        ui.setSummary(ui.valueChoiceRow(layout, "🖥️", Ui.PURPLE, "Screen mode",
                new String[] {"Both", "Top, cursor", "Top, touchpad", "Bottom only"},
                new String[] {Screens.MODE_BOTH, Screens.MODE_TOP_CURSOR,
                    Screens.MODE_TOP_TOUCHPAD, Screens.MODE_BOTTOM},
                () -> Screens.screenMode(this), mode -> Screens.setScreenMode(this, mode)),
                "Both screens, the top one (the bottom one as cursor or touchpad), or the bottom one");
        ui.setSummary(ui.valueChoiceRow(layout, "🎮", Ui.ORANGE, "Controller focus",
                new String[] {"Automatic", "Top screen", "Bottom screen"},
                new String[] {Screens.FOCUS_AUTO, Screens.FOCUS_TOP, Screens.FOCUS_BOTTOM},
                () -> prefs.getString(PartsPrefs.FOCUS_LOCK, Screens.FOCUS_AUTO), lock -> {
                    prefs.edit().putString(PartsPrefs.FOCUS_LOCK, lock).apply();
                    Screens.applyFocusLock(this, true);
                }), "Which screen the controller buttons act on");
        ui.switchRow(layout, "🕹️", Ui.ORANGE, "Keep the controller on the game",
                "Using the overlay does not take the controller from the game",
                PartsPrefs.OVERLAY_GAME_FOCUS, true);
        ui.switchRow(layout, "👆", Ui.TEAL, "Swipe back on the second screen",
                "Bottom screen: swipe in from an edge to go back", PartsPrefs.SECOND_GESTURES, true);
    }

    private static final int REQUEST_WALLPAPER_TOP = 2;
    private static final int REQUEST_WALLPAPER_BOTTOM = 3;

    private void launcher(Ui ui, LinearLayout page) {
        setTitle("Applications");
        ui.hero(page, "📱", Ui.PINK, "Applications",
                "App list and wallpapers");

        ui.section(page, "Grid");
        final LinearLayout grid = ui.card(page);
        ui.choiceRow(grid, "🔳", Ui.BLUE, "Icons per row", PartsPrefs.LAUNCHER_COLUMNS,
                new String[] {"4", "5", "6", "7"}, new String[] {"4", "5", "6", "7"}, "5");
        ui.choiceRow(grid, "🔳", Ui.PURPLE, "Rows per page", PartsPrefs.LAUNCHER_ROWS,
                new String[] {"2", "3", "4", "5"}, new String[] {"2", "3", "4", "5"}, "3");
        ui.note(page, "Swipe sideways for more pages. Tap an app to open it, see its info or put it "
                + "in a folder.");

        ui.section(page, "Wallpapers");
        final LinearLayout wallpapers = ui.card(page);
        final Ui.Row top = ui.actionRow(wallpapers, "🖥️", Ui.TEAL, "Top screen",
                Wallpapers.has(this, Screens.TOP) ? "Your picture" : "System wallpaper",
                () -> pickWallpaper(REQUEST_WALLPAPER_TOP));
        final Ui.Row bottom = ui.actionRow(wallpapers, "🖥️", Ui.ORANGE, "Bottom screen",
                Wallpapers.has(this, Screens.BOTTOM) ? "Your picture" : "System wallpaper",
                () -> pickWallpaper(REQUEST_WALLPAPER_BOTTOM));
        ui.actionRow(wallpapers, "⏱️", Ui.RED, "Back to the default wallpapers", null, () -> {
            Wallpapers.clear(this, Screens.TOP);
            Wallpapers.clear(this, Screens.BOTTOM);
            ui.setSummary(top, "System wallpaper");
            ui.setSummary(bottom, "System wallpaper");
            Toast.makeText(this, "Wallpapers reset", Toast.LENGTH_SHORT).show();
        });

        ui.section(page, "Folders");
        final LinearLayout folders = ui.card(page);
        ui.actionRow(folders, "📦", Ui.GREY, "Undo all the folders",
                "The apps go back to the list", () -> {
                    new Folders(this).clear();
                    Toast.makeText(this, "Folders removed", Toast.LENGTH_SHORT).show();
                });
    }

    private void pickWallpaper(int request) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), request);
    }

    private void sound(Ui ui, LinearLayout page) {
        setTitle("Sound");
        ui.hero(page, "🔊", Ui.BLUE, "Sound",
                "Volumes, output and sound of each app");
        final android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);

        ui.section(page, "Volumes");
        final LinearLayout volumes = ui.card(page);
        final int[] streams = {android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_ALARM,
            android.media.AudioManager.STREAM_SYSTEM};
        final String[] names = {"Media", "Notifications", "Alarms", "System sounds"};
        final String[] icons = {"🎵", "🔔", "⏱️", "🔊"};
        final int[] colors = {Ui.BLUE, Ui.ORANGE, Ui.RED, Ui.GREY};
        for (int i = 0; i < streams.length; i++) {
            final int stream = streams[i];
            final int max = audio.getStreamMaxVolume(stream);
            ui.sliderRow(volumes, icons[i], colors[i], names[i], audio.getStreamVolume(stream), max,
                    value -> audio.setStreamVolume(stream, value, 0));
        }

        ui.section(page, "Output of the media");
        final LinearLayout output = ui.card(page);
        ui.valueChoiceRow(output, "🎧", Ui.PURPLE, "Play on",
                new String[] {"Automatic", "Speakers", "Headphones", "Bluetooth", "USB"},
                new String[] {"auto", "speaker", "wired", "bluetooth", "usb"},
                () -> PartsPrefs.get(this).getString("media_output", "auto"),
                value -> {
                    PartsPrefs.get(this).edit().putString("media_output", value).apply();
                    setMediaOutput(audio, value);
                });
        ui.note(page, "Automatic follows what is plugged in. Your choice lasts until a restart.");

        ui.section(page, "Effects");
        final LinearLayout effects = ui.card(page);
        final android.content.ContentResolver resolver = getContentResolver();
        ui.valueSwitchRow(effects, "👆", Ui.TEAL, "Touch sounds", "A tick when you tap the screen",
                () -> Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1)
                        == 1, on -> {
                    Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED,
                            on ? 1 : 0);
                    if (on) audio.loadSoundEffects(); else audio.unloadSoundEffects();
                });
        ui.valueSwitchRow(effects, "📳", Ui.ORANGE, "Vibration feedback",
                "Vibrate on touch and keys",
                () -> Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
                        == 1, on -> Settings.System.putInt(resolver,
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, on ? 1 : 0));

        ui.section(page, "Silence an app");
        final LinearLayout apps = ui.card(page);
        final android.content.pm.PackageManager pm = getPackageManager();
        final android.content.SharedPreferences levels = getSharedPreferences("app_volumes",
                MODE_PRIVATE);
        final java.util.List<android.content.pm.ResolveInfo> launchers = pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0);
        final java.util.TreeMap<String, String> found = new java.util.TreeMap<>();
        for (android.content.pm.ResolveInfo info : launchers) {
            final String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            found.put(String.valueOf(info.loadLabel(pm)).toLowerCase(java.util.Locale.ROOT)
                    + "\u0000" + pkg, pkg);
        }
        for (String pkg : found.values()) {
            String label = pkg;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // the package name is shown
            }
            final String name = pkg;
            ui.valueSwitchRow(apps, "🔇", Ui.RED, label, "No sound from this app",
                    () -> levels.getInt(name, 100) == 0, on -> {
                        final AppVolumeController controller = AppVolumeController.get();
                        if (controller != null) controller.set(name, on ? 0 : 100);
                        else levels.edit().putInt(name, on ? 0 : 100).apply();
                    });
        }
        ui.note(page, "The volume of each app is in the overlay (Sound).");
    }

    /** Sends the media to an output, or back to the automatic choice. */
    static void setMediaOutput(android.media.AudioManager audio, String choice) {
        try {
            final int type;
            switch (choice) {
                case "speaker": type = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER; break;
                case "wired": type = android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES; break;
                case "bluetooth": type = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP; break;
                case "usb": type = android.media.AudioDeviceInfo.TYPE_USB_DEVICE; break;
                default: type = -1;
            }
            final android.media.AudioAttributes media = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build();
            for (android.media.audiopolicy.AudioProductStrategy strategy :
                    android.media.AudioManager.getAudioProductStrategies()) {
                if (!strategy.supportsAudioAttributes(media)) continue;
                if (type < 0) {
                    audio.removePreferredDeviceForStrategy(strategy);
                    continue;
                }
                for (android.media.AudioDeviceInfo device : audio.getDevices(
                        android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (device.getType() == type || (type == android.media.AudioDeviceInfo
                            .TYPE_WIRED_HEADPHONES && device.getType()
                            == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET)) {
                        audio.setPreferredDeviceForStrategy(strategy,
                                new android.media.AudioDeviceAttributes(device));
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            android.util.Log.w("PocketDSSound", "Output not changed", e);
        }
    }

    private void battery(Ui ui, LinearLayout page) {
        setTitle("Battery & radios");
        ui.hero(page, "🔋", Ui.GREEN, "Battery & radios",
                "Battery health, charge limit and radios (Wi-Fi, Bluetooth)");

        batteryInfo(ui, page);
        chargingLed(ui, page);
        final LinearLayout stats = ui.card(page);
        ui.actionRow(stats, "📊", Ui.BLUE, "Battery stats",
                "Live power, and what the battery lost while asleep",
                () -> startActivity(new Intent(this, BatteryStatsActivity.class)));

        ui.section(page, "After the screen goes off");
        final LinearLayout screen = ui.card(page);
        ui.choiceRow(screen, "⏱️", Ui.BLUE, "Wait before turning off",
                PartsPrefs.SUSPEND_DELAY_MIN, DELAYS, DELAY_VALUES, "0");
        ui.switchRow(screen, "📶", Ui.TEAL, "Wi-Fi",
                "Back on when the screen turns on",
                PartsPrefs.SUSPEND_WIFI, false);
        ui.switchRow(screen, "🟦", Ui.BLUE, "Bluetooth",
                "Back on when the screen turns on",
                PartsPrefs.SUSPEND_BLUETOOTH, false);
        ui.switchRow(screen, "🔌", Ui.ORANGE, "Only on battery",
                "Not while a cable is plugged in",
                PartsPrefs.SUSPEND_ONLY_ON_BATTERY, true);

        ui.section(page, "Charging");
        final LinearLayout charging = ui.card(page);
        ui.switchRow(charging, "🔋", Ui.GREEN, "Limit the charge",
                "Stops at the level below to spare the battery",
                ChargeLimitController.PREF_ON, false);
        ui.choiceRow(charging, "🎯", Ui.TEAL, "Charge up to", ChargeLimitController.PREF_LEVEL,
                new String[] {"60 %", "70 %", "75 %", "80 %", "85 %", "90 %"},
                new String[] {"60", "70", "75", "80", "85", "90"}, "80");
        final String chargeState = PartsPrefs.get(this).getString(ChargeLimitController.PREF_STATE,
                null);
        ui.note(page, chargeState != null ? chargeState
                : "Charging restarts 3 points under the limit.");
        ui.note(page, "It holds on a USB-C PD charger, and on a computer USB port while the "
                + "console rests.");
        final SharedPreferences chargePrefs = PartsPrefs.get(this);
        ui.actionRow(charging, "⚡", Ui.ORANGE, "Charge fully once",
                ChargeLimitController.describeFullCharge(this, chargePrefs),
                () -> fullChargeDialog(chargePrefs));
        final Ui.Row bypassRow = ui.valueChoiceRow(charging, "🔌", Ui.PURPLE,
                "Run on the charger",
                new String[] {"Off", "While playing", "Always"},
                new String[] {"off", "games", "always"},
                () -> ChargeLimitController.bypassMode(chargePrefs),
                mode -> chargePrefs.edit().putString(ChargeLimitController.PREF_BYPASS, mode)
                        .apply());
        ui.setSummary(bypassRow, "The charger powers the console and the battery rests. "
                + "While playing: above 50 %. Always: above the charge limit. Needs a USB-C PD "
                + "charger");
        ui.switchRow(charging, "🔌", Ui.BLUE, "Never power the USB port",
                "Keeps the console from emptying its battery into a cable that goes "
                + "nowhere, such as a computer that is off",
                PartsPrefs.USB_POWER_GUARD, true);
        final String guardLast = PartsPrefs.get(this).getString(PartsPrefs.USB_POWER_GUARD_LAST,
                null);
        ui.note(page, guardLast == null ? "Never needed so far." : "Last time: " + guardLast);

        ui.section(page, "Bluetooth");
        final LinearLayout bluetooth = ui.card(page);
        ui.choiceRow(bluetooth, "🎧", Ui.PURPLE, "Off when no device is connected",
                PartsPrefs.BT_IDLE_OFF_MIN, DELAYS, DELAY_VALUES, "0");
        ui.note(page, "Google's Nearby keeps Bluetooth scanning and the console awake. This turns "
                + "Bluetooth off when nothing is connected.");
    }

    private static final String POWER_SUPPLY = "/sys/class/power_supply/battery/";

    /** Level, state, temperature, voltage, wear and cycles of the battery. */
    private void batteryInfo(Ui ui, LinearLayout page) {
        ui.section(page, "Battery");
        final LinearLayout card = ui.card(page);
        final Intent battery = registerReceiver(null,
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1;
        int tempTenths = 0;
        int health = android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN;
        boolean plugged = false;
        if (battery != null) {
            final int raw = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            final int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            if (raw >= 0 && scale > 0) level = raw * 100 / scale;
            tempTenths = battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
            health = battery.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, health);
            plugged = battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        final BatteryInfo info = BatteryInfo.read();
        String state = info.timeLeft() != null ? info.timeLeft() + " left"
                : info.state() != null ? info.state() : plugged ? "plugged in" : "on battery";
        state = Character.toUpperCase(state.charAt(0)) + state.substring(1) + " · "
                + info.signedWatts();
        final int real = level < 0 ? level : ChargeLimitController.realLevel(level);
        if (real < level) {
            state += " · real charge " + real + " % (the charger reports full)";
        }
        ui.row(card, "🔋", Ui.GREEN, level < 0 ? "Battery" : level + " %", state);
        ui.progressBar(card, level, level <= 15 ? Ui.RED : level <= 30 ? Ui.ORANGE : Ui.GREEN);

        final long microVolts = PerfOverlayController.readLong(POWER_SUPPLY + "voltage_now", 0);
        ui.row(card, "🌡️", Ui.ORANGE, "Temperature and voltage", String.format(
                java.util.Locale.US, "%.1f °C · %.2f V", tempTenths / 10f, microVolts / 1e6));

        final long full = PerfOverlayController.readLong(POWER_SUPPLY + "charge_full", 0);
        final long design = PerfOverlayController.readLong(POWER_SUPPLY + "charge_full_design", 0);
        String wear = healthLabel(health);
        if (full > 0 && design > 0) {
            wear += String.format(java.util.Locale.US, " · %d %% of its capacity when new "
                    + "(%d of %d mAh)", Math.round(full * 100.0 / design), full / 1000,
                    design / 1000);
        }
        ui.row(card, "❤️", Ui.RED, "Health", wear);
        // The gauge of the console counts far too few cycles (5 after weeks of daily charges):
        // the cycles are counted by PocketDS Extra, the gauge is shown next to them.
        ui.row(card, "🔁", Ui.PURPLE, "Charge cycles", CycleCounter.describe(PartsPrefs.get(this),
                PerfOverlayController.readLong(POWER_SUPPLY + "cycle_count", -1)));
        ui.switchRow(card, "⚠️", Ui.ORANGE, "Warn when the battery is worn",
                "A notification at 80, 70 and 60 % of its capacity when new",
                BatteryHealth.PREF, true);
    }

    private static String healthLabel(int health) {
        switch (health) {
            case android.os.BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Too hot";
            case android.os.BatteryManager.BATTERY_HEALTH_COLD: return "Too cold";
            case android.os.BatteryManager.BATTERY_HEALTH_DEAD: return "Worn out";
            case android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            default: return "Unknown";
        }
    }

    /** The LED next to the USB-C port: brightness, battery level, charging, night. */
    private void chargingLed(Ui ui, LinearLayout page) {
        final SharedPreferences prefs = PartsPrefs.get(this);
        ui.section(page, "LED of the charging port");
        final LinearLayout card = ui.card(page);
        ui.sliderRow(card, "💡", Ui.PURPLE, "Brightness", LedController.brightness(this), 100,
                value -> LedController.setBrightness(this, value));
        ui.choiceRow(card, "🔋", Ui.GREEN, "Show the battery level", LedController.PREF_BATTERY,
                new String[] {"Never", "While charging", "Always"},
                new String[] {"off", "charging", "always"}, LedController.DEFAULT_BATTERY);
        ui.switchRow(card, "🌬️", Ui.TEAL, "Breathe while charging",
                "Steady once charged", LedController.PREF_BREATH, LedController.DEFAULT_BREATH);
        final boolean gradient = prefs.getBoolean(LedController.PREF_GRADIENT, false);
        ui.valueSwitchRow(card, "🌈", Ui.ORANGE, "Gradient",
                "From red to green with the level, instead of the steps", () -> gradient,
                on -> {
                    prefs.edit().putBoolean(LedController.PREF_GRADIENT, on).apply();
                    recreate();
                });
        final String[][] steps = LedController.steps(this);
        for (int i = 0; i < steps.length; i++) {
            final int index = i;
            final int from = i == 0 ? 0 : Integer.parseInt(steps[i - 1][0]) + 1;
            final Ui.Row row = ui.actionRow(card, "●", ledColor(steps[i][1]),
                    from + " to " + steps[i][0] + " %", LedController.colorLabel(steps[i][1]),
                    () -> editLedStep(ui, steps, index));
            if (gradient) ui.setEnabledDeep(row.view, false);
        }

        ui.section(page, "LED while charging");
        final LinearLayout charge = ui.card(page);
        ledColorRow(ui, charge, "✅", Ui.GREEN, "Charge complete", "At 100 %, or at the charge limit",
                LedController.PREF_FULL, LedController.DEFAULT_FULL);
        ledColorRow(ui, charge, "🔌", Ui.PURPLE, "Running on the charger",
                "The battery rests, see Run on the charger below",
                LedController.PREF_BYPASS, "");

        ui.section(page, "LED at night");
        final LinearLayout night = ui.card(page);
        final Ui.Row nightSwitch = ui.switchRow(night, "🌙", Ui.BLUE, "Night mode",
                "Lower brightness between two times", LedController.PREF_NIGHT, false);
        final Rule.TimeWindow window = LedController.nightWindow(this);
        final Ui.Row hours = ui.actionRow(night, "⏰", Ui.PURPLE, "Hours", window.describe(),
                () -> new android.app.TimePickerDialog(this, (v1, h1, m1) -> {
                    window.startMin = h1 * 60 + m1;
                    new android.app.TimePickerDialog(this, (v2, h2, m2) -> {
                        window.endMin = h2 * 60 + m2;
                        prefs.edit().putString(LedController.PREF_NIGHT_WINDOW,
                                window.encode()).apply();
                        recreate();
                    }, window.endMin / 60, window.endMin % 60, true).show();
                }, window.startMin / 60, window.startMin % 60, true).show());
        final Ui.Row level = ui.valueChoiceRow(night, "💡", Ui.ORANGE, "Brightness at night",
                new String[] {"Off", "5 %", "10 %", "25 %", "50 %"},
                new String[] {"0", "5", "10", "25", "50"},
                () -> Integer.toString(prefs.getInt(LedController.PREF_NIGHT_LEVEL, 0)),
                value -> prefs.edit().putInt(LedController.PREF_NIGHT_LEVEL,
                        Integer.parseInt(value)).apply());
        ui.dependOn(nightSwitch, hours.view, level.view);
        ui.note(page, "At night the LED uses this brightness; 0 % keeps it off. Notification "
                + "colours are set per app in Settings > Notifications.");
    }

    private static int ledColor(String color) {
        try {
            final int rgb = Integer.parseInt(color, 16);
            return rgb == 0 ? Ui.GREY : 0xFF000000 | rgb;
        } catch (NumberFormatException e) {
            return Ui.GREY;
        }
    }

    /** A colour picked in a list, "not used" first. */
    private void ledColorRow(Ui ui, LinearLayout card, String icon, int color, String title,
            String summary, String key, String fallback) {
        final SharedPreferences prefs = PartsPrefs.get(this);
        final String current = prefs.getString(key, fallback);
        final Ui.Row row = ui.actionRow(card, icon, color, title,
                LedController.colorLabel(current) + " · " + summary, null);
        row.view.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(LedController.OPTIONAL_LABELS,
                        Math.max(0, Rule.index(LedController.OPTIONAL_COLORS,
                                prefs.getString(key, fallback))), (d, which) -> {
                            d.dismiss();
                            prefs.edit().putString(key, LedController.OPTIONAL_COLORS[which])
                                    .apply();
                            recreate();
                        })
                .setNegativeButton("Cancel", null).show());
    }

    private void editLedStep(Ui ui, String[][] steps, int index) {
        int checked = Math.max(0, Rule.index(LedController.COLORS, steps[index][1]));
        new android.app.AlertDialog.Builder(this).setTitle("Colour")
                .setSingleChoiceItems(LedController.COLOR_LABELS, checked, (d, which) -> {
                    d.dismiss();
                    steps[index][1] = LedController.COLORS[which];
                    if (index == steps.length - 1) {
                        // The last step always goes up to 100 %.
                        LedController.setSteps(this, steps);
                        recreate();
                        return;
                    }
                    final int min = index == 0 ? 1 : Integer.parseInt(steps[index - 1][0]) + 1;
                    final int max = Integer.parseInt(steps[index + 1][0]) - 1;
                    ui.sliderDialog("Up to", " %", min, Math.max(min, max),
                            Integer.parseInt(steps[index][0]), value -> {
                                steps[index][0] = Integer.toString(value);
                                LedController.setSteps(this, steps);
                                recreate();
                            });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void powerOff(Ui ui, LinearLayout page) {
        setTitle("Power off");
        ui.hero(page, "⏻", Ui.RED, "Power off",
                "Turns the console off by itself to save the battery");
        final LinearLayout card = ui.card(page);
        ui.choiceRow(card, "📕", Ui.ORANGE, "After the lid is closed",
                PartsPrefs.POWEROFF_LID_MIN, DELAYS, DELAY_VALUES, "0");
        ui.choiceRow(card, "🌙", Ui.PURPLE, "After the screen is off",
                PartsPrefs.POWEROFF_SCREEN_MIN, DELAYS, DELAY_VALUES, "0");
        ui.switchRow(card, "🔌", Ui.GREEN, "Only on battery",
                "Screen-off delay only: not while a cable is plugged in",
                PartsPrefs.POWEROFF_ONLY_ON_BATTERY, true);
        ui.switchRow(card, "📕", Ui.ORANGE, "Lid: also when plugged in",
                "A closed lid powers off even on a charger or a computer, unless data moves "
                + "over USB", PartsPrefs.POWEROFF_LID_PLUGGED, true);
        ui.switchRow(card, "🎮", Ui.BLUE, "Sleep during a game",
                "With a game open, sleep instead of powering off (below 10 %: power off)",
                PartsPrefs.POWEROFF_KEEP_GAME, true);
        ui.choiceRow(card, "⏳", Ui.ORANGE, "Power off the game anyway after",
                PartsPrefs.POWEROFF_GAME_MAX_MIN,
                new String[] {"Never", "30 min", "1 h", "2 h", "4 h", "8 h"},
                new String[] {"0", "30", "60", "120", "240", "480"}, "0");
        ui.note(page, "Opening the lid or waking the screen cancels the countdown. While data "
                + "moves over USB or a game is open, the lid rule is checked again every 5 "
                + "minutes.");
    }

    private final Handler mUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable mUpdateTick;

    @Override
    protected void onPause() {
        super.onPause();
        if (mUpdateTick != null) mUpdateHandler.removeCallbacks(mUpdateTick);
        if (mMotion != null) mMotion.cancel();
    }

    private MotionCalibration mMotion;

    /**
     * Motion sensors: which way the gyroscope turns (two switches) and the calibration of its
     * bias. Both are stored in the sensors registry, which the sensors read at boot.
     */
    private void motion(Ui ui, LinearLayout page) {
        setTitle("Motion sensors");
        ui.hero(page, "🧭", Ui.TEAL, "Motion sensors",
                "Gyroscope calibration for games that aim with it. The direction is in Controller.");
        mMotion = new MotionCalibration(this);
        final Handler main = new Handler(Looper.getMainLooper());

        // A change is read by the sensors at boot only.
        final LinearLayout pending = ui.card(page);
        ui.actionRow(pending, "🔄", Ui.ORANGE, "Restart to apply",
                "Applied when the console starts",
                () -> getSystemService(android.os.PowerManager.class).reboot(null));
        pending.setVisibility(android.view.View.GONE);

        ui.section(page, "Calibration");
        ui.note(page, "Lay the console flat and do not touch it for 10 s. This removes the drift "
                + "of the gyroscope when the console is still.");
        final LinearLayout cal = ui.card(page);
        final Runnable[] render = new Runnable[1];
        final MotionCalibration.Result[] last = {null};
        final int[] progress = {0};
        final int[] state = {0};   // 0 idle, 1 measuring, 2 result, 3 saved
        final float[][] inUse = {null};
        render[0] = () -> {
            cal.removeAllViews();
            switch (state[0]) {
                case 1:
                    ui.row(cal, "⏳", Ui.ORANGE, "Measuring… " + progress[0] + " %",
                            "Do not move the console");
                    ui.progressBar(cal, progress[0], Ui.ORANGE);
                    ui.actionRow(cal, "✖️", Ui.RED, "Cancel", null, () -> {
                        mMotion.cancel();
                        state[0] = 0;
                        render[0].run();
                    });
                    break;
                case 2:
                    if (last[0].ok) {
                        ui.row(cal, "📊", Ui.TEAL, "It drifts by",
                                MotionCalibration.describe(last[0].mean));
                        ui.actionRow(cal, "✅", Ui.GREEN, "Apply",
                                "Stores " + MotionCalibration.describe(last[0].bias), () ->
                                new Thread(() -> {
                                    final boolean saved =
                                            MotionCalibration.writeBias(last[0].bias);
                                    main.post(() -> {
                                        if (saved) {
                                            state[0] = 3;
                                            inUse[0] = last[0].bias;
                                            pending.setVisibility(android.view.View.VISIBLE);
                                        } else {
                                            Toast.makeText(this, "Could not store the"
                                                    + " calibration (root)", Toast.LENGTH_LONG)
                                                    .show();
                                        }
                                        render[0].run();
                                    });
                                }, "PocketDSMotionSave").start());
                    } else {
                        ui.row(cal, "⚠️", Ui.ORANGE, "No calibration", last[0].problem);
                    }
                    ui.actionRow(cal, "🔄", Ui.BLUE, "Measure again", null, () -> {
                        state[0] = 1;
                        progress[0] = 0;
                        render[0].run();
                        mMotion.start(listener(state, progress, last, render));
                    });
                    break;
                case 3:
                    ui.row(cal, "✅", Ui.GREEN, "Saved", "Restart the console to use it");
                    break;
                default:
                    ui.row(cal, "🧭", Ui.TEAL, "Correction in use", inUse[0] == null
                            ? "Reading…" : MotionCalibration.describe(inUse[0]));
                    if (mMotion.available()) {
                        ui.actionRow(cal, "🎯", Ui.TEAL, "Calibrate", "Flat and still, 10 s", () -> {
                            state[0] = 1;
                            progress[0] = 0;
                            render[0].run();
                            mMotion.start(listener(state, progress, last, render));
                        });
                    } else {
                        ui.row(cal, "⚠️", Ui.ORANGE, "No gyroscope", null);
                    }
                    ui.actionRow(cal, "↩️", Ui.GREY, "Reset to zero",
                            "Removes the stored correction", () ->
                            new Thread(() -> {
                                final boolean saved = MotionCalibration.writeBias(new float[3]);
                                main.post(() -> {
                                    if (saved) {
                                        inUse[0] = new float[3];
                                        pending.setVisibility(android.view.View.VISIBLE);
                                    }
                                    render[0].run();
                                });
                            }, "PocketDSMotionReset").start());
                    break;
            }
        };
        render[0].run();
        new Thread(() -> {
            final float[] bias = MotionCalibration.readBias();
            main.post(() -> {
                inUse[0] = bias;
                if (state[0] == 0) render[0].run();
            });
        }, "PocketDSMotionBias").start();
    }

    private MotionCalibration.Listener listener(int[] state, int[] progress,
            MotionCalibration.Result[] last, Runnable[] render) {
        return new MotionCalibration.Listener() {
            @Override
            public void onProgress(int percent) {
                progress[0] = percent;
                render[0].run();
            }

            @Override
            public void onDone(MotionCalibration.Result result) {
                last[0] = result;
                state[0] = 2;
                render[0].run();
            }
        };
    }

    /** System update: the installed version, the newest release, and one button for what is next. */
    private void updates(Ui ui, LinearLayout page) {
        setTitle("System update");
        ui.hero(page, "⬆️", Ui.TEAL, "System update",
                "Installed in the background, the console only restarts when you say so");
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        final String[] shown = {null};
        mUpdateTick = new Runnable() {
            @Override
            public void run() {
                final String signature = Updates.sState + "/" + Updates.sPercent + "/"
                        + Updates.sMessage + "/" + Updates.restartPending(SectionActivity.this)
                        + "/" + Updates.lastChecked(SectionActivity.this) + "/"
                        + Updates.url(SectionActivity.this) + "/"
                        + Updates.reminderState(SectionActivity.this);
                if (!signature.equals(shown[0])) {
                    shown[0] = signature;
                    body.removeAllViews();
                    renderUpdates(ui, body);
                }
                mUpdateHandler.postDelayed(this, 1000);
            }
        };
        mUpdateHandler.post(mUpdateTick);
    }

    private void renderUpdates(Ui ui, LinearLayout body) {
        final LinearLayout now = ui.card(body);
        ui.row(now, "📦", Ui.BLUE, "Installed", Updates.version() + " · build " + Updates.buildId());
        final Updates.Info info = Updates.available(this);
        final boolean busy = UpdateService.running();

        if (Updates.restartPending(this)) {
            updateSteps(ui, body, 3, 100);
            ui.actionRow(now, "🔄", Ui.GREEN, "Restart to finish the update",
                    "The new version is installed; it starts after the restart", () ->
                            getSystemService(android.os.PowerManager.class).reboot(null));
        } else if (busy) {
            final boolean applying = Updates.sState == Updates.APPLYING;
            ui.row(now, "⏳", Ui.ORANGE, applying ? "Installing" : "Downloading",
                    Updates.sMessage + (Updates.sPercent > 0 ? " · " + Updates.sPercent + " %" : ""));
            ui.progressBar(now, Updates.sPercent > 0 ? Updates.sPercent : -1, Ui.ORANGE);
            updateSteps(ui, body, applying ? 2 : 1, Updates.sPercent);
        } else if (info != null) {
            final Updates.Pack pack = info.pick();
            ui.row(now, "✨", Ui.TEAL, "Version " + info.version + " available",
                    (info.date.isEmpty() ? "" : info.date + " · ")
                            + (pack == info.full ? "Complete package " : "Small update ")
                            + Updates.size(pack.size));
            if (info.notes != null && !info.notes.isEmpty()) {
                ui.row(now, "📝", Ui.PURPLE, "What's new", info.notes);
            }
            if (Updates.sState == Updates.FAILED) {
                ui.row(now, "⚠️", Ui.RED, "Last attempt failed", Updates.sMessage);
            }
            ui.actionRow(now, "⬇️", Ui.GREEN, "Download and install",
                    "In the background; keep the console on Wi-Fi and charged", () -> {
                        startForegroundService(new Intent(this, UpdateService.class));
                        Toast.makeText(this, "Update started", Toast.LENGTH_SHORT).show();
                    });
            final long snoozed = Updates.snoozedUntil(this);
            final boolean skipped = Updates.skipped(this, info);
            if (snoozed > 0) {
                ui.row(now, "⏰", Ui.ORANGE, "Reminders paused", "Until "
                        + android.text.format.DateFormat.getMediumDateFormat(this)
                                .format(new java.util.Date(snoozed))
                        + ": no notification and no installation by itself");
            } else if (skipped) {
                ui.row(now, "⏭️", Ui.ORANGE, "Version skipped",
                        "You are told again when the next one is out");
            }
            ui.actionRow(now, "⏰", Ui.PURPLE, snoozed > 0 || skipped ? "Change the reminder"
                    : "Remind me later", "Not now: no notification and no installation by "
                    + "itself for a while", () -> remindMeLater(info, snoozed > 0 || skipped));
        } else {
            final long checked = Updates.lastChecked(this);
            ui.row(now, "✅", Ui.GREEN, "Up to date", checked == 0 ? "Not checked yet"
                    : "Checked " + android.text.format.DateUtils.getRelativeTimeSpanString(checked));
        }
        if (!busy) {
            ui.actionRow(now, "🔎", Ui.BLUE, "Check now", "Looks for a newer release", () -> {
                Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show();
                final Handler main = new Handler(Looper.getMainLooper());
                new Thread(() -> {
                    String message;
                    try {
                        final Updates.Info found = Updates.check(this);
                        message = found == null ? "You have the latest version"
                                : "Version " + found.version + " is available";
                    } catch (Exception e) {
                        message = "Cannot reach the update server";
                    }
                    final String shownMessage = message;
                    main.post(() -> Toast.makeText(this, shownMessage, Toast.LENGTH_LONG).show());
                }).start();
            });
        }

        ui.section(body, "Automatic");
        final LinearLayout auto = ui.card(body);
        ui.switchRow(auto, "🔎", Ui.BLUE, "Check twice a day",
                "A notification tells you when a new version is out", Updates.PREF_AUTO_CHECK,
                true);
        ui.switchRow(auto, "⬇️", Ui.GREEN, "Install by itself",
                "Download and install in the background on Wi-Fi, charging or above 50 %",
                Updates.PREF_AUTO_INSTALL, false);
        if (!android.os.SystemProperties.get("ro.pocketds.stats_url", "").isEmpty()) {
            ui.switchRow(auto, "📊", Ui.TEAL, "Count this console",
                    "Once a day, a random number and the system version are sent, nothing else. "
                    + "It shows which versions are in use",
                    VersionCount.PREF_ENABLED, true);
        }
        ui.section(body, "Update source");
        final LinearLayout source = ui.card(body);
        ui.actionRow(source, "🔗", Ui.PURPLE, Updates.customUrl(this) ? "Custom source"
                : "Default source", Updates.url(this), this::editUpdateSource);
        ui.note(body, "For your own build: a GitHub repository (owner/repo) or the https address "
                + "of its update.json.");

        ui.section(body, "Add-ons");
        final LinearLayout addons = ui.card(body);
        ui.actionRow(addons, "🧩", Ui.PURPLE, "Check the add-ons", "TurnipZ GPU driver "
                + GpuDriverPack.currentVersion(this) + " and the Obtainium emulator list",
                this::checkAddons);
        ui.note(body, "Downloaded from GitHub only when you ask, without a new system. The "
                + "driver goes to Eden and Citron; the list is saved in Downloads, to import "
                + "in Obtainium.");
        ui.note(body, "The console never restarts by itself. A failed update leaves the current "
                + "version as it is.");

        // The console is not certified by Google: its Google services (GSF) ID can be registered on
        // Google's own page for custom systems, then the Play Store treats it as certified.
        final String gsf = gsfId();
        if (gsf != null) {
            ui.section(body, "Google Play certification");
            final LinearLayout play = ui.card(body);
            ui.actionRow(play, "🆔", Ui.BLUE, "Google services ID", gsf + "  ·  tap to copy", () -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                        android.content.ClipData.newPlainText("GSF ID", gsf));
                Toast.makeText(this, "ID copied", Toast.LENGTH_SHORT).show();
            });
            ui.actionRow(play, "✅", Ui.GREEN, "Register this console",
                    "If the Play Store says the device is not certified: paste the ID on Google's "
                    + "page, wait a few minutes, then clear the storage of the Play Store",
                    () -> openPage("https://www.google.com/android/uncertified"));
        }
    }

    private void showShutdownLog() {
        final String text = ShutdownLog.read(this).trim();
        new android.app.AlertDialog.Builder(this).setTitle("Power-off log · last 2 days")
                .setMessage(text.isEmpty() ? "Nothing yet." : text)
                .setPositiveButton("OK", null).show();
    }

    /** Looks at GitHub for a newer TurnipZ driver and Obtainium emulator list. */
    private void checkAddons() {
        Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final Addons.Status status = Addons.check(this);
            main.post(() -> {
                if (status.turnip == null && status.pack == null) {
                    Toast.makeText(this, "Cannot reach GitHub", Toast.LENGTH_LONG).show();
                } else if (!status.turnipNewer && !status.packNewer) {
                    Toast.makeText(this, "The add-ons are up to date", Toast.LENGTH_LONG).show();
                } else {
                    offerAddons(status);
                }
            });
        }, "pocketds-addons").start();
    }

    private void offerAddons(Addons.Status status) {
        final StringBuilder text = new StringBuilder();
        if (status.turnipNewer) {
            text.append("TurnipZ GPU driver ").append(GpuDriverPack.currentVersion(this))
                    .append(" → ").append(status.turnip.version).append(" (")
                    .append(Updates.size(status.turnip.size)).append(")\n");
        }
        if (status.packNewer) {
            text.append("Obtainium emulator list ").append(status.pack.version).append(" (")
                    .append(Updates.size(status.pack.size)).append(")\n");
        }
        new android.app.AlertDialog.Builder(this).setTitle("Add-on updates")
                .setMessage(text.toString().trim())
                .setPositiveButton("Download", (d, w) -> downloadAddons(status))
                .setNegativeButton("Later", null).show();
    }

    private void downloadAddons(Addons.Status status) {
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final StringBuilder result = new StringBuilder();
            if (status.turnipNewer) {
                final String error = Addons.updateDriver(this, status.turnip);
                result.append(error != null ? error : "TurnipZ " + status.turnip.version
                        + " added to the emulators").append('\n');
            }
            if (status.packNewer) {
                final String error = Addons.savePack(this, status.pack);
                result.append(error != null ? error : "Saved in Downloads: " + status.pack.file
                        + ". In Obtainium: Import/Export > Import from file").append('\n');
            }
            main.post(() -> new android.app.AlertDialog.Builder(this).setTitle("Add-ons")
                    .setMessage(result.toString().trim()).setPositiveButton("OK", null).show());
        }, "pocketds-addons").start();
    }

    /** A full charge for a trip or a long session: now, or ready at a time. */
    private void fullChargeDialog(SharedPreferences prefs) {
        final boolean planned = prefs.getLong(ChargeLimitController.PREF_FULL_UNTIL, 0)
                > System.currentTimeMillis();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add("Charge to 100 % now");
        labels.add("Be at 100 % at a time…");
        if (planned) labels.add("Cancel");
        new android.app.AlertDialog.Builder(this).setTitle("Charge fully once")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    final long now = System.currentTimeMillis();
                    if (which == 0) {
                        ChargeLimitController.planFullCharge(prefs, now,
                                now + ChargeLimitController.FULL_NOW_MAX_MS);
                        recreate();
                    } else if (which == 1) {
                        final java.util.Calendar soon = java.util.Calendar.getInstance();
                        new android.app.TimePickerDialog(this, (picker, hour, minute) -> {
                            final java.util.Calendar at = java.util.Calendar.getInstance();
                            at.set(java.util.Calendar.HOUR_OF_DAY, hour);
                            at.set(java.util.Calendar.MINUTE, minute);
                            at.set(java.util.Calendar.SECOND, 0);
                            // the next time it is that hour
                            if (at.getTimeInMillis() <= System.currentTimeMillis()) {
                                at.add(java.util.Calendar.DAY_OF_MONTH, 1);
                            }
                            final long ready = at.getTimeInMillis();
                            ChargeLimitController.planFullCharge(prefs, Math.max(
                                    System.currentTimeMillis(),
                                    ready - ChargeLimitController.FULL_LEAD_MS), ready);
                            Toast.makeText(this, "Charging starts "
                                    + ChargeLimitController.FULL_LEAD_MS / 3_600_000L
                                    + " hours before", Toast.LENGTH_LONG).show();
                            recreate();
                        }, soon.get(java.util.Calendar.HOUR_OF_DAY),
                                soon.get(java.util.Calendar.MINUTE), true).show();
                    } else {
                        ChargeLimitController.cancelFullCharge(prefs);
                        recreate();
                    }
                }).show();
    }

    /** "Remind me later": how long, or skip this version. */
    private void remindMeLater(Updates.Info info, boolean paused) {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Long> days = new java.util.ArrayList<>();
        if (paused) {
            labels.add("Remind me as usual");
            days.add(0L);
        }
        final String[] names = {"In 1 day", "In 7 days", "In 14 days", "In 1 month"};
        final long[] counts = {1, 7, 14, 30};
        for (int i = 0; i < names.length; i++) {
            labels.add(names[i]);
            days.add(counts[i]);
        }
        labels.add("Skip this version");
        days.add(-1L);
        new android.app.AlertDialog.Builder(this).setTitle("Remind me later")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    final long chosen = days.get(which);
                    if (chosen == 0) {
                        Updates.remindAgain(this);
                    } else if (chosen < 0) {
                        Updates.skip(this, info);
                    } else {
                        Updates.snooze(this, chosen * 86_400_000L);
                    }
                }).show();
    }

    /** The Google services framework ID (decimal), null without Google services. */
    private String gsfId() {
        try (android.database.Cursor c = getContentResolver().query(
                android.net.Uri.parse("content://com.google.android.gsf.gservices"), null, null,
                new String[] {"android_id"}, null)) {
            if (c != null && c.moveToFirst() && c.getColumnCount() >= 2) {
                final String id = c.getString(1);
                if (id != null && !id.isEmpty() && !id.equals("0")) return id;
            }
        } catch (RuntimeException e) {
            // no Google services, or no access
        }
        return null;
    }

    private void openPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException e) {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                    android.content.ClipData.newPlainText("link", url));
            Toast.makeText(this, "No browser: link copied (" + url + ")", Toast.LENGTH_LONG).show();
        }
    }

    /** The three steps of an update, each done, running or waiting (step = the one running). */
    private void updateSteps(Ui ui, LinearLayout body, int step, int percent) {
        final LinearLayout card = ui.card(body);
        final String[] names = {"Download", "Install in the other system slot", "Restart"};
        final String[] hints = {"Fetches the package",
                "Written next to the running system",
                "The new version starts after your restart"};
        for (int i = 1; i <= 3; i++) {
            final boolean done = i < step;
            final String icon = done ? "✅" : i == step ? (i == 3 ? "🔄" : "⏳") : "⚪";
            final String summary = i == step && i < 3 && percent > 0 ? hints[i - 1] + " · " + percent + " %"
                    : done ? "Done" : hints[i - 1];
            ui.row(card, icon, done ? Ui.GREEN : i == step ? Ui.ORANGE : Ui.BLUE,
                    i + ". " + names[i - 1], summary);
        }
    }

    private void editUpdateSource() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("owner/repo or https://…/update.json");
        input.setText(Updates.customUrl(this) ? Updates.url(this) : "");
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Update source").setView(input)
                .setPositiveButton("Save", null)
                .setNeutralButton("Default", (d, w) -> {
                    Updates.setUrl(this, "");
                    Toast.makeText(this, "Default source restored", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (Updates.setUrl(this, input.getText().toString())) {
                        dialog.dismiss();
                    } else {
                        Toast.makeText(this, "Use owner/repo or an https:// address",
                                Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void tools(Ui ui, LinearLayout page) {
        setTitle("Tools & logs");
        ui.hero(page, "🧰", Ui.GREY, "Tools & logs",
                "Logs, backups and the performance overlay");
        final LinearLayout card = ui.card(page);
        ui.actionRow(card, "📦", Ui.BLUE, "Collect all the logs",
                "Logcat, kernel log and system information in Downloads/PocketDS, to share",
                () -> LogCollector.collect(this, true, true, true));
        ui.actionRow(card, "📜", Ui.TEAL, "Collect the logcat only",
                "Faster and smaller", () -> LogCollector.collect(this, true, false, false));
        ui.actionRow(card, "🕘", Ui.ORANGE, "Power-off log", ShutdownLog.summary(this),
                this::showShutdownLog);
        ui.switchRow(card, "🗂️", Ui.GREY, "Keep the system log",
                "Warnings of the last 2 days (6 MB at most), kept after a restart and added to "
                + "the reports", SystemLog.PREF, true);

        ui.section(page, "Performance");
        final LinearLayout perf = ui.card(page);
        ui.switchRow(perf, "🎮", Ui.PURPLE, "Performance overlay",
                "Frames per second, GPU, CPU, memory, temperature and power on the main screen",
                PartsPrefs.PERF_OVERLAY, false);
        ui.note(page, "A console button can also switch it. The frame rate is the one of the app "
                + "in front.");

        ui.section(page, "Backup");
        final LinearLayout backup = ui.card(page);
        ui.actionRow(backup, "💾", Ui.GREEN, "Back up the settings",
                "Options, automation rules and performance profiles, in Downloads/PocketDS",
                this::backup);
        ui.actionRow(backup, "♻️", Ui.ORANGE, "Restore a backup",
                "Pick a pocketds-backup file", () -> {
                    final Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
                    startActivityForResult(pick, REQUEST_RESTORE);
                });
        ui.note(page, "Copy the file out of the console before a wipe, then restore it afterwards.");

        ui.section(page, "Root");
        final LinearLayout root = ui.card(page);
        ui.actionRow(root, "🛡️", Ui.PURPLE, "Root modules",
                "KernelSU Next, SUSFS, Zygisk and the modules installed (read only)",
                () -> startActivity(new Intent(this, SectionActivity.class)
                        .putExtra(EXTRA_SECTION, ROOT)));

        ui.section(page, "Emulators");
        final LinearLayout emus = ui.card(page);
        ui.actionRow(emus, "💾", Ui.GREEN, "Back up the emulators",
                "Settings, controls and saves of the installed emulators, in Downloads/PocketDS",
                this::backupEmulators);
        ui.actionRow(emus, "♻️", Ui.ORANGE, "Restore the emulators",
                "Put back the newest archive (the emulators are stopped first)",
                this::restoreEmulators);
        ui.note(page, "Games are not copied, only the data of the emulators.");
    }

    /** What runs as root: KernelSU Next, SUSFS, Zygisk and the modules. Nothing can be changed. */
    private void rootPage(Ui ui, LinearLayout page) {
        setTitle("Root modules");
        ui.hero(page, "🛡️", Ui.PURPLE, "Root modules",
                "What runs as root on the console. Change it in KernelSU Next");
        final LinearLayout state = ui.card(page);
        final Ui.Row loading = ui.row(state, "⏳", Ui.ORANGE, "Reading…", null);
        final LinearLayout modules = new LinearLayout(this);
        modules.setOrientation(LinearLayout.VERTICAL);
        page.addView(modules);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final String text = LogCollector.rootState();
            main.post(() -> {
                state.removeAllViews();
                if (text.trim().isEmpty()) {
                    ui.row(state, "⚠️", Ui.RED, "Root not available",
                            "Open KernelSU Next once, then come back");
                    return;
                }
                String section = "";
                final java.util.List<String[]> mods = new java.util.ArrayList<>();
                final StringBuilder susfs = new StringBuilder();
                String ksu = "";
                boolean zygisk = false;
                for (String line : text.split("\n")) {
                    final String l = line.trim();
                    if (l.startsWith("== ")) {
                        section = l.substring(3);
                        continue;
                    }
                    if (l.isEmpty()) continue;
                    switch (section) {
                        case "ksud": ksu = l; break;
                        case "susfs":
                            if (susfs.length() < 160) susfs.append(susfs.length() > 0 ? " · " : "")
                                    .append(l);
                            break;
                        case "modules": {
                            final int space = l.indexOf(' ');
                            mods.add(space < 0 ? new String[] {l, ""}
                                    : new String[] {l.substring(0, space), l.substring(space + 1)});
                            break;
                        }
                        case "zygisk": zygisk = true; break;
                        default: break;
                    }
                }
                ui.row(state, "🛡️", Ui.PURPLE, "KernelSU Next", ksu.isEmpty() ? "Installed" : ksu);
                ui.row(state, "🫥", Ui.TEAL, "SUSFS (root hiding)",
                        susfs.length() == 0 ? "Not loaded" : susfs.toString());
                ui.row(state, "🧬", Ui.BLUE, "Zygisk", zygisk ? "Running" : "Not running");
                ui.section(modules, mods.isEmpty() ? "No module installed" : "Modules");
                if (!mods.isEmpty()) {
                    final LinearLayout card = ui.card(modules);
                    for (String[] m : mods) {
                        final boolean off = m[1].contains("[off]");
                        ui.row(card, off ? "⏸️" : "🧩", off ? Ui.GREY : Ui.GREEN, m[0],
                                (off ? "Off · " : "On · ") + m[1].replace("[off]", "").trim());
                    }
                }
            });
        }, "pocketds-root").start();
    }

    private void backupEmulators() {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final java.util.List<String> emus = EmulatorBackup.installed(this);
            final String name = EmulatorBackup.backup(emus);
            final String message = emus.isEmpty() ? "No emulator installed"
                    : name == null ? "Backup failed"
                    : "Saved: Downloads/PocketDS/" + name + " (" + emus.size() + " emulators)";
            main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void restoreEmulators() {
        final java.util.List<java.io.File> archives = EmulatorBackup.archives();
        if (archives.isEmpty()) {
            Toast.makeText(this, "No archive in Downloads/PocketDS", Toast.LENGTH_LONG).show();
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final boolean ok = EmulatorBackup.restore(this, archives.get(0));
            main.post(() -> Toast.makeText(this, ok ? "Restored: " + archives.get(0).getName()
                    : "Restore failed", Toast.LENGTH_LONG).show());
        }).start();
    }

    /** The apps that can be launched, as {label, package}, by label. */
    private java.util.List<String[]> launchableApps(android.content.pm.PackageManager pm) {
        final java.util.TreeMap<String, String[]> found = new java.util.TreeMap<>();
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            final String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            final String label = String.valueOf(info.loadLabel(pm));
            found.put(label.toLowerCase(java.util.Locale.ROOT) + "\u0000" + pkg,
                    new String[] {label, pkg});
        }
        return new java.util.ArrayList<>(found.values());
    }

    private static String appLabel(android.content.pm.PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Game profiles: the settings are made once in a profile, then each app picks a profile. */
    private void games(Ui ui, LinearLayout page) {
        setTitle("Profiles");
        ui.hero(page, "🎮", Ui.PURPLE, "Profiles",
                "Set a game profile once, then pick it for each app");
        final SharedPreferences prefs = PartsPrefs.get(this);
        GameSetups.migrate(prefs);
        final android.content.pm.PackageManager pm = getPackageManager();

        ui.section(page, "Game profiles");
        final LinearLayout setupsCard = ui.card(page);
        for (GameSetups.Setup s : GameSetups.all(prefs)) {
            final int uses = GameSetups.appsOf(prefs, s.id).size();
            ui.actionRow(setupsCard, "🎮", Ui.PURPLE, s.name, GameSetups.summary(prefs, s) + " · "
                    + uses + (uses == 1 ? " app" : " apps"), () -> openSetup(s.id));
        }
        ui.actionRow(setupsCard, "➕", Ui.GREEN, "New game profile",
                "Name it, then choose its settings", () -> nameDialog("New game profile", "",
                        name -> openSetup(GameSetups.create(prefs, name).id)));

        ui.section(page, "Applications");
        final LinearLayout apps = ui.card(page);
        for (String[] app : launchableApps(pm)) {
            final GameSetups.Setup s = GameSetups.find(prefs,
                    GameSetups.assignedId(prefs, app[1]));
            if (s == null) continue;
            ui.actionRow(apps, "📱", Ui.BLUE, app[0], s.name, () ->
                    pickSetupDialog(prefs, app[1], app[0], this::recreate));
        }
        ui.actionRow(apps, "➕", Ui.GREEN, "Add an application",
                "Pick an app, then its game profile", () -> addAppDialog(prefs, pm));

        ui.section(page, "Fan curve");
        final LinearLayout fan = ui.card(page);
        final SharedPreferences fanPrefs = PartsPrefs.get(this);
        ui.switchRow(fan, "🌀", Ui.TEAL, "Own fan curve",
                "Replaces the Mute and Balance curves. Off and Turbo stay as they are, and the "
                + "fan is at full speed above 85 °C", PartsPrefs.FAN_CURVE_ON, false);
        final int[] own = FanController.ownPercents(fanPrefs.getString(PartsPrefs.FAN_CURVE, ""));
        for (int i = 0; i < FanController.OWN_TEMPS.length; i++) {
            final int point = i;
            final Ui.Row[] row = new Ui.Row[1];
            row[0] = ui.actionRow(fan, "🌡️", Ui.ORANGE, "At " + FanController.OWN_TEMPS[i]
                    + " °C", own[i] + " % of the fan speed", () -> {
                        final int[] now = FanController.ownPercents(
                                fanPrefs.getString(PartsPrefs.FAN_CURVE, ""));
                        ui.sliderDialog("Fan speed at " + FanController.OWN_TEMPS[point] + " °C",
                                " %", 0, 100, now[point], value -> {
                                    now[point] = value;
                                    final int[] fixed = FanController.ownPercents(
                                            java.util.Arrays.toString(now).replaceAll("[\\[\\] ]", ""));
                                    fanPrefs.edit().putString(PartsPrefs.FAN_CURVE,
                                            java.util.Arrays.toString(fixed).replaceAll(
                                                    "[\\[\\] ]", "")).apply();
                                    recreate();
                                });
                    });
        }
        ui.note(page, "The speed rises from one point to the next and never goes down as the "
                + "temperature rises. Below 40 °C the first value is used.");

        ui.section(page, "Driver Turnip");
        final LinearLayout drivers = ui.card(page);
        ui.switchRow(drivers, "🧩", Ui.PURPLE, "TurnipZ for Eden and Citron",
                "Adds the TurnipZ GPU driver to their list. Nothing changes until you pick it "
                + "there", PartsPrefs.GPU_DRIVER_PACK, true);
        ui.note(page, "Leaving an app restores your previous profile and controller setup.");
    }

    private void openSetup(String id) {
        startActivity(new Intent(this, SectionActivity.class)
                .putExtra(EXTRA_SECTION, SETUP).putExtra(EXTRA_SETUP, id));
    }

    /** Which game profile an app uses: one of the profiles, none, or a new one. */
    private void pickSetupDialog(SharedPreferences prefs, String pkg, String title,
            Runnable changed) {
        final java.util.List<GameSetups.Setup> list = GameSetups.all(prefs);
        final String[] items = new String[list.size() + 2];
        for (int i = 0; i < list.size(); i++) items[i] = list.get(i).name;
        items[list.size()] = "None (change nothing)";
        items[list.size() + 1] = "New game profile…";
        new android.app.AlertDialog.Builder(this).setTitle(title + " · game profile")
                .setItems(items, (d, which) -> {
                    if (which < list.size()) {
                        GameSetups.assign(prefs, pkg, list.get(which).id);
                        changed.run();
                    } else if (which == list.size()) {
                        GameSetups.assign(prefs, pkg, null);
                        changed.run();
                    } else {
                        nameDialog("New game profile", "", name -> {
                            final GameSetups.Setup s = GameSetups.create(prefs, name);
                            GameSetups.assign(prefs, pkg, s.id);
                            openSetup(s.id);
                        });
                    }
                }).show();
    }

    /** Lists the apps that have no profile yet. */
    private void addAppDialog(SharedPreferences prefs, android.content.pm.PackageManager pm) {
        final java.util.List<String[]> free = new java.util.ArrayList<>();
        for (String[] app : launchableApps(pm)) {
            if (GameSetups.find(prefs, GameSetups.assignedId(prefs, app[1])) == null) free.add(app);
        }
        final String[] items = new String[free.size()];
        for (int i = 0; i < items.length; i++) items[i] = free.get(i)[0];
        new android.app.AlertDialog.Builder(this).setTitle("Add an application")
                .setItems(items, (d, which) -> pickSetupDialog(prefs, free.get(which)[1],
                        free.get(which)[0], this::recreate)).show();
    }

    /** The page of one game profile: performance, controller and minimum GPU speed. */
    private void setupPage(Ui ui, LinearLayout page) {
        final SharedPreferences prefs = PartsPrefs.get(this);
        final GameSetups.Setup s = GameSetups.find(prefs, getIntent().getStringExtra(EXTRA_SETUP));
        if (s == null) {
            finish();
            return;
        }
        setTitle(s.name);
        ui.hero(page, "🎮", Ui.PURPLE, s.name,
                "Applied when an app that uses it opens, and undone when you leave it");

        ui.section(page, "Performance");
        final LinearLayout perf = ui.card(page);
        final String[] entries = new String[PerformanceProfiles.NAMES.length + 1];
        final String[] values = new String[entries.length];
        entries[0] = "No change";
        values[0] = "-1";
        for (int i = 0; i < PerformanceProfiles.NAMES.length; i++) {
            entries[i + 1] = PerformanceProfiles.NAMES[i];
            values[i + 1] = Integer.toString(i);
        }
        ui.valueChoiceRow(perf, "⚡", Ui.ORANGE, "Performance profile", entries, values,
                () -> Integer.toString(s.perf), value -> {
                    s.perf = Integer.parseInt(value);
                    GameSetups.update(prefs, s);
                });

        ui.section(page, "Controller");
        final LinearLayout pad = ui.card(page);
        final ControllerProfiles.Named named = ControllerProfiles.find(prefs, s.pad);
        ui.actionRow(pad, "🕹️", Ui.BLUE, "Controller profile",
                named == null ? "General controller (Every app)" : named.name,
                () -> padPickDialog(prefs, s));

        ui.section(page, "GPU");
        final LinearLayout gpu = ui.card(page);
        ui.valueSwitchRow(gpu, "⚡", Ui.TEAL, "Minimum GPU speed",
                "Keeps the GPU from dropping too low: starts at 85 % of the maximum, then follows the "
                + "frame rate",
                () -> s.gpu, on -> {
                    s.gpu = on;
                    GameSetups.update(prefs, s);
                });

        ui.section(page, "Used by");
        final android.content.pm.PackageManager pm = getPackageManager();
        final java.util.List<String> users = GameSetups.appsOf(prefs, s.id);
        final LinearLayout used = ui.card(page);
        if (users.isEmpty()) {
            ui.row(used, "📱", Ui.GREY, "No app yet",
                    "Pick this profile for an app in Profiles");
        }
        for (String pkg : users) ui.row(used, "📱", Ui.BLUE, appLabel(pm, pkg), null);

        ui.section(page, "Profile");
        final LinearLayout actions = ui.card(page);
        ui.actionRow(actions, "✏️", Ui.BLUE, "Rename", s.name, () ->
                nameDialog("Rename the profile", s.name, name -> {
                    if (!name.trim().isEmpty()) {
                        s.name = name.trim();
                        GameSetups.update(prefs, s);
                        recreate();
                    }
                }));
        ui.actionRow(actions, "🗑️", Ui.RED, "Delete this profile",
                "The apps that use it go back to no profile", () ->
                new android.app.AlertDialog.Builder(this).setTitle("Delete " + s.name + "?")
                        .setPositiveButton("Delete", (d, w) -> {
                            GameSetups.delete(prefs, s.id);
                            finish();
                        }).setNegativeButton("Cancel", null).show());
    }

    /** Which controller profile a game profile uses: general, one of the named ones, or new. */
    private void padPickDialog(SharedPreferences prefs, GameSetups.Setup s) {
        final java.util.List<ControllerProfiles.Named> list = ControllerProfiles.all(prefs);
        final String[] items = new String[list.size() + 2];
        items[0] = "General controller (Every app)";
        for (int i = 0; i < list.size(); i++) items[i + 1] = list.get(i).name;
        items[items.length - 1] = "New profile…";
        new android.app.AlertDialog.Builder(this).setTitle(s.name + " · controller")
                .setItems(items, (d, which) -> {
                    if (which == items.length - 1) {
                        nameDialog("New controller profile", "", name -> {
                            final ControllerProfiles.Named n = ControllerProfiles.create(prefs,
                                    name);
                            s.pad = n.id;
                            GameSetups.update(prefs, s);
                            recreate();
                            openRemap(n);
                        });
                    } else {
                        s.pad = which == 0 ? null : list.get(which - 1).id;
                        GameSetups.update(prefs, s);
                        recreate();
                    }
                }).show();
    }

    private interface NameListener {
        void onName(String name);
    }

    private void nameDialog(String title, String current, NameListener listener) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("Name");
        input.setText(current);
        input.setSelection(current.length());
        new android.app.AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton("Save", (d, w) -> listener.onName(input.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    /** Controller: the named profiles and the general setup. */
    private void controllerPage(Ui ui, LinearLayout page) {
        setTitle("Controller");
        ui.hero(page, "🕹️", Ui.PURPLE, "Controller",
                "Named setups for the buttons and sticks, chosen for each app in Profiles");
        final android.content.SharedPreferences prefs = PartsPrefs.get(this);
        final LinearLayout general = ui.card(page);
        ui.actionRow(general, "🌐", Ui.BLUE, "Every app",
                "Used when an app has no profile: tap a button on the console drawing", () -> startActivity(new Intent(this,
                        ControllerMapActivity.class)));
        final LinearLayout tools = ui.card(page);
        ui.actionRow(tools, "⌨️", Ui.PURPLE, "Macros",
                "Keyboard keys and key sequences for a button: any key, Ctrl+S, F5...", () ->
                        startActivity(new Intent(this, MacrosActivity.class)));
        ui.actionRow(tools, "🎯", Ui.ORANGE, "Test the buttons",
                "The console lights each button and stick as you press it", () ->
                        startActivity(new Intent(this, ControllerTestActivity.class)));
        ui.actionRow(tools, "🧭", Ui.TEAL, "Calibration",
                "Sticks, triggers and the button combination, step by step", () ->
                        startActivity(new Intent(this, ControllerTestActivity.class)
                                .putExtra(ControllerTestActivity.EXTRA_CALIBRATE, true)));
        ui.section(page, "Profiles");
        final LinearLayout card = ui.card(page);
        final java.util.List<ControllerProfiles.Named> list = ControllerProfiles.all(prefs);
        for (ControllerProfiles.Named n : list) {
            final int uses = ControllerProfiles.usedBy(prefs, n.id);
            ui.actionRow(card, "🕹️", Ui.PURPLE, n.name, n.profile.summary()
                    + (uses == 0 ? "" : " · " + uses + (uses == 1 ? " app" : " apps")),
                    () -> openRemap(n));
        }
        ui.actionRow(card, "➕", Ui.GREEN, "New profile", "Give it a name, then remap its buttons",
                () -> nameDialog("New controller profile", "", name -> {
                    final ControllerProfiles.Named n = ControllerProfiles.create(prefs, name);
                    openRemap(n);
                }));
        ui.note(page, list.isEmpty() ? "No profile yet. Create one, then pick it for an app "
                + "in Profiles." : "A profile only changes what you set in it; the rest stays "
                + "as in Every app.");
    }

    /** A controller profile opens straight on the remapping of its buttons. */
    private void openRemap(ControllerProfiles.Named n) {
        startActivity(new Intent(this, ControllerMapActivity.class)
                .putExtra(ControllerMapActivity.EXTRA_PROFILE, n.id)
                .putExtra(ControllerMapActivity.EXTRA_LABEL, n.name));
    }

    private static final int REQUEST_RESTORE = 1;

    private void backup() {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try {
                message = "Saved: Downloads/PocketDS/" + BackupManager.export(this);
            } catch (java.io.IOException | RuntimeException e) {
                message = "Backup failed: " + e.getMessage();
            }
            final String shown = message;
            main.post(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_WALLPAPER_TOP || requestCode == REQUEST_WALLPAPER_BOTTOM) {
            final int panel = requestCode == REQUEST_WALLPAPER_TOP ? Screens.TOP : Screens.BOTTOM;
            final android.net.Uri picture = data.getData();
            final Handler main = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                final boolean ok = Wallpapers.save(this, picture, panel);
                main.post(() -> {
                    Toast.makeText(this, ok ? "Wallpaper set" : "That picture cannot be used",
                            Toast.LENGTH_SHORT).show();
                    if (ok) recreate();
                });
            }).start();
            return;
        }
        if (requestCode != REQUEST_RESTORE) return;
        final android.net.Uri uri = data.getData();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try {
                message = BackupManager.restore(this, uri);
            } catch (java.io.IOException | RuntimeException e) {
                message = "Restore failed: " + e.getMessage();
            }
            final String shown = message;
            main.post(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
        }).start();
    }
}
