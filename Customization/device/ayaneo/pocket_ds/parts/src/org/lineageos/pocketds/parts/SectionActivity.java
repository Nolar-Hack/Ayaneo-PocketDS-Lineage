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

    private static final String[] DELAYS = {"Never", "1 min", "5 min", "15 min", "30 min", "1 h"};
    private static final String[] DELAY_VALUES = {"0", "1", "5", "15", "30", "60"};

    /** The category to show; the entries of the Settings app give it without an extra. */
    protected String section() {
        return getIntent().getStringExtra(EXTRA_SECTION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAccent();
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
                "Switches off some pixels in a moving pattern: saves power and slows burn-in");
        final LinearLayout master = ui.card(page);
        final Ui.Row main = ui.switchRow(master, "✨", Ui.PINK, "Smart Pixels",
                "Best on the OLED main screen", PartsPrefs.SMART_PIXELS, false);
        final LinearLayout options = ui.card(page);
        ui.choiceRow(options, "🔳", Ui.PURPLE, "Share of pixels switched off",
                PartsPrefs.SMART_PIXELS_PERCENT,
                new String[] {"3 %", "6 %", "12 %", "25 %", "50 %"},
                new String[] {"3", "6", "12", "25", "50"}, "12");
        ui.choiceRow(options, "🔄", Ui.BLUE, "Burn-in protection",
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
                "Turns Smart Pixels on while Battery Saver is on",
                PartsPrefs.SMART_PIXELS_ON_SAVER, false);
        ui.dependOn(main, options);

        ui.section(page, "Screen off");
        final LinearLayout animation = ui.card(page);
        ui.switchRow(animation, "📺", Ui.ORANGE, "Old TV (CRT) animation",
                "The picture collapses to a line, then to a dot, when the screen turns off, and "
                + "opens up again when it turns on", PartsPrefs.CRT_SCREEN_OFF, true);
    }

    private void screens(Ui ui, LinearLayout page) {
        setTitle("Screens");
        ui.hero(page, "🖥️", Ui.TEAL, "Screens",
                "Which screen shows what, brightness, touch and when the screens turn off");

        ui.section(page, "Brightness");
        final LinearLayout brightness = ui.card(page);
        final android.content.ContentResolver resolver = getContentResolver();
        final SharedPreferences prefs = PartsPrefs.get(this);
        final int top = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128);
        int bottomValue = Settings.Global.getInt(resolver, "secondary_screen_brightness", -1);
        if (bottomValue <= 0) bottomValue = top;
        final Ui.Row[] rows = new Ui.Row[2];
        rows[0] = ui.sliderRow(brightness, "☀️", Ui.ORANGE, "Main screen", top, 255, value -> {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, value));
            if (prefs.getBoolean(PartsPrefs.BRIGHTNESS_LINK, false)) {
                Settings.Global.putInt(resolver, "secondary_screen_brightness", Math.max(1, value));
            } else if (Settings.Global.getInt(resolver, "secondary_screen_brightness", -1) <= 0) {
                // Until it has a level of its own the bottom screen follows the main one: pin it.
                Settings.Global.putInt(resolver, "secondary_screen_brightness",
                        Settings.Global.getInt(resolver, "secondary_screen_brightness", 128));
            }
        });
        ui.sliderRow(brightness, "☀️", Ui.TEAL, "Bottom screen", bottomValue, 255, value ->
                Settings.Global.putInt(resolver, "secondary_screen_brightness",
                        Math.max(1, value)));
        ui.switchRow(brightness, "🔗", Ui.BLUE, "Link the two screens",
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
                "The picture collapses to a line, then to a dot, when the screen turns off",
                () -> prefs.getBoolean(PartsPrefs.CRT_SCREEN_OFF, true),
                on -> prefs.edit().putBoolean(PartsPrefs.CRT_SCREEN_OFF, on).apply());

        ui.section(page, "Touch");
        final LinearLayout touch = ui.card(page);
        ui.choiceRow(touch, "👆", Ui.PINK, "Sensitivity of the main screen",
                PartsPrefs.TOUCH_THRESHOLD,
                new String[] {"Very high", "High", "Normal", "Low", "Very low"},
                new String[] {"60", "90", "120", "150", "180"}, "120");
        ui.note(page, "A low sensitivity avoids touches made by a finger that only hovers the "
                + "screen; a high one reacts to the lightest touch. Normal is the default.");

        ui.section(page, "Layout");
        final LinearLayout layout = ui.card(page);
        ui.valueSwitchRow(layout, "⇄", Ui.BLUE, "Swap the screens",
                "The bottom screen becomes the main screen and the other way round; both stay on",
                () -> Screens.isSwapped(this), on -> Screens.setSwapped(this, on));
        ui.valueChoiceRow(layout, "🖥️", Ui.PURPLE, "Screen mode",
                new String[] {"Both", "Top, cursor", "Top, touchpad", "Bottom only"},
                new String[] {Screens.MODE_BOTH, Screens.MODE_TOP_CURSOR,
                    Screens.MODE_TOP_TOUCHPAD, Screens.MODE_BOTTOM},
                () -> Screens.screenMode(this), mode -> Screens.setScreenMode(this, mode));
        ui.valueChoiceRow(layout, "🎮", Ui.ORANGE, "Controller focus",
                new String[] {"Automatic", "Top screen", "Bottom screen"},
                new String[] {Screens.FOCUS_AUTO, Screens.FOCUS_TOP, Screens.FOCUS_BOTTOM},
                () -> prefs.getString(PartsPrefs.FOCUS_LOCK, Screens.FOCUS_AUTO), lock -> {
                    prefs.edit().putString(PartsPrefs.FOCUS_LOCK, lock).apply();
                    Screens.applyFocusLock(this, true);
                });
        ui.switchRow(layout, "👆", Ui.TEAL, "Swipe back on the second screen",
                "Swipe from its left or right edge towards the inside to go back: the system "
                + "gesture only works on the main screen", PartsPrefs.SECOND_GESTURES, true);
    }

    private static final int REQUEST_WALLPAPER_TOP = 2;
    private static final int REQUEST_WALLPAPER_BOTTOM = 3;

    private void launcher(Ui ui, LinearLayout page) {
        setTitle("Applications");
        ui.hero(page, "📱", Ui.PINK, "Applications",
                "The list of the bottom screen and the wallpapers of the two screens");

        ui.section(page, "Grid");
        final LinearLayout grid = ui.card(page);
        ui.choiceRow(grid, "🔳", Ui.BLUE, "Icons per row", PartsPrefs.LAUNCHER_COLUMNS,
                new String[] {"4", "5", "6", "7"}, new String[] {"4", "5", "6", "7"}, "5");
        ui.choiceRow(grid, "🔳", Ui.PURPLE, "Rows per page", PartsPrefs.LAUNCHER_ROWS,
                new String[] {"2", "3", "4", "5"}, new String[] {"2", "3", "4", "5"}, "3");
        ui.note(page, "More icons than fit on a page go to the next page: swipe sideways. Press "
                + "and hold an app to put it in a folder.");

        ui.section(page, "Wallpapers");
        final LinearLayout wallpapers = ui.card(page);
        final Ui.Row top = ui.actionRow(wallpapers, "🖥️", Ui.TEAL, "Top screen",
                Wallpapers.has(this, Screens.TOP) ? "Your picture" : "The wallpaper of the system",
                () -> pickWallpaper(REQUEST_WALLPAPER_TOP));
        final Ui.Row bottom = ui.actionRow(wallpapers, "🖥️", Ui.ORANGE, "Bottom screen",
                Wallpapers.has(this, Screens.BOTTOM) ? "Your picture" : "The wallpaper of the system",
                () -> pickWallpaper(REQUEST_WALLPAPER_BOTTOM));
        ui.actionRow(wallpapers, "⏱️", Ui.RED, "Back to the default wallpapers", null, () -> {
            Wallpapers.clear(this, Screens.TOP);
            Wallpapers.clear(this, Screens.BOTTOM);
            ui.setSummary(top, "The wallpaper of the system");
            ui.setSummary(bottom, "The wallpaper of the system");
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
                "Volumes, where the sound goes, and the sound of each app");
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
        ui.note(page, "Automatic follows what is plugged in. A choice stays until you change it "
                + "or restart the console, and is ignored while that output is not connected.");

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
                "Touch and keyboard feedback in the controller",
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
        ui.note(page, "The volume of each app that is playing, between 0 and 100 %, is in the "
                + "overlay (Sound).");
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
                "Radios that keep the processor awake are switched off when you do not use them");

        ui.section(page, "After the screen goes off");
        final LinearLayout screen = ui.card(page);
        ui.choiceRow(screen, "⏱️", Ui.BLUE, "Switch off the radios",
                PartsPrefs.SUSPEND_DELAY_MIN, DELAYS, DELAY_VALUES, "0");
        ui.switchRow(screen, "📶", Ui.TEAL, "Wi-Fi",
                "Off after the delay, back on when the screen turns on",
                PartsPrefs.SUSPEND_WIFI, false);
        ui.switchRow(screen, "🟦", Ui.BLUE, "Bluetooth",
                "Off after the delay, back on when the screen turns on",
                PartsPrefs.SUSPEND_BLUETOOTH, false);
        ui.switchRow(screen, "🔌", Ui.ORANGE, "Only on battery",
                "Do nothing while the console is charging",
                PartsPrefs.SUSPEND_ONLY_ON_BATTERY, true);

        ui.section(page, "Bluetooth");
        final LinearLayout bluetooth = ui.card(page);
        ui.choiceRow(bluetooth, "🎧", Ui.PURPLE, "Off when no device is connected",
                PartsPrefs.BT_IDLE_OFF_MIN, DELAYS, DELAY_VALUES, "0");
        ui.note(page, "Google's Nearby services scan over Bluetooth all the time, which stops "
                + "the console from sleeping. This option switches Bluetooth (and its background "
                + "scanning) off when nothing is connected.");
    }

    private void powerOff(Ui ui, LinearLayout page) {
        setTitle("Power off");
        ui.hero(page, "⏻", Ui.RED, "Power off",
                "Shuts the console down by itself, to keep the battery for later");
        final LinearLayout card = ui.card(page);
        ui.choiceRow(card, "📕", Ui.ORANGE, "After the lid is closed",
                PartsPrefs.POWEROFF_LID_MIN, DELAYS, DELAY_VALUES, "0");
        ui.choiceRow(card, "🌙", Ui.PURPLE, "After the screen is off",
                PartsPrefs.POWEROFF_SCREEN_MIN, DELAYS, DELAY_VALUES, "0");
        ui.switchRow(card, "🔌", Ui.GREEN, "Only on battery",
                "Do not power off while the console is charging",
                PartsPrefs.POWEROFF_ONLY_ON_BATTERY, true);
        ui.note(page, "Opening the lid or turning the screen on cancels the countdown.");
    }

    private void tools(Ui ui, LinearLayout page) {
        setTitle("Tools & logs");
        ui.hero(page, "🧰", Ui.GREY, "Tools & logs",
                "Everything needed to report a problem");
        final LinearLayout card = ui.card(page);
        ui.actionRow(card, "📦", Ui.BLUE, "Collect all the logs",
                "Logcat, kernel log and system information in Downloads/PocketDS, to share",
                () -> LogCollector.collect(this, true, true, true));
        ui.actionRow(card, "📜", Ui.TEAL, "Collect the logcat only",
                "Faster and smaller", () -> LogCollector.collect(this, true, false, false));

        ui.section(page, "Performance");
        final LinearLayout perf = ui.card(page);
        ui.switchRow(perf, "🎮", Ui.PURPLE, "Performance overlay",
                "Frames per second, GPU, CPU, memory, temperature and power on the main screen",
                PartsPrefs.PERF_OVERLAY, false);
        ui.note(page, "It can also be switched on and off with a button of the console. "
                + "The frame rate is the one of the app in the foreground.");

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
        ui.note(page, "A backup survives a wipe if you keep the file: copy it out of the console "
                + "first, then restore it after the reinstall.");

        ui.section(page, "Emulators");
        final LinearLayout emus = ui.card(page);
        ui.actionRow(emus, "💾", Ui.GREEN, "Back up the emulators",
                "Settings, controls and saves of the installed emulators, in Downloads/PocketDS",
                this::backupEmulators);
        ui.actionRow(emus, "♻️", Ui.ORANGE, "Restore the emulators",
                "Put back the newest archive (the emulators are stopped first)",
                this::restoreEmulators);
        ui.note(page, "The games themselves are not copied, only what the emulators keep "
                + "for themselves.");
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

    /** A performance profile for each app: it is switched on when the app comes to the front. */
    private void games(Ui ui, LinearLayout page) {
        ui.hero(page, "🎮", Ui.PURPLE, "Games",
                "A profile for each app, switched on when it opens");
        final android.content.SharedPreferences prefs = PartsPrefs.get(this);
        final android.content.pm.PackageManager pm = getPackageManager();
        final java.util.TreeMap<String, String> found = new java.util.TreeMap<>();
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)) {
            final String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            found.put(String.valueOf(info.loadLabel(pm)).toLowerCase(java.util.Locale.ROOT)
                    + "\u0000" + pkg, pkg);
        }
        final LinearLayout card = ui.card(page);
        final String[] choices = new String[PerformanceProfiles.NAMES.length + 1];
        choices[0] = "None (leave the profile alone)";
        System.arraycopy(PerformanceProfiles.NAMES, 0, choices, 1,
                PerformanceProfiles.NAMES.length);
        for (String pkg : found.values()) {
            String label = pkg;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // the package name is shown
            }
            final String title = label;
            final String key = GameProfiles.KEY_PREFIX + pkg;
            final Ui.Row[] row = new Ui.Row[1];
            row[0] = ui.actionRow(card, "🎮", Ui.PURPLE, label, profileText(prefs, key), () ->
                    new android.app.AlertDialog.Builder(this).setTitle(title)
                            .setItems(choices, (d, which) -> {
                                if (which == 0) prefs.edit().remove(key).apply();
                                else prefs.edit().putString(key, Integer.toString(which - 1)).apply();
                                ui.setSummary(row[0], profileText(prefs, key));
                            }).show());
        }
        ui.note(page, "When you leave the app, the profile you had comes back.");
    }

    private static String profileText(android.content.SharedPreferences prefs, String key) {
        final int profile = PartsPrefs.getInt(prefs, key, -1);
        return profile < 0 || profile >= PerformanceProfiles.NAMES.length ? "No profile"
                : PerformanceProfiles.NAMES[profile];
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
