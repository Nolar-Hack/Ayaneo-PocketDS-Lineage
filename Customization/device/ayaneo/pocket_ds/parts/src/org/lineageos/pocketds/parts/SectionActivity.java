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
    static final String UPDATES = "updates";

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
            case UPDATES:
                updates(ui, page);
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

        ui.section(page, "Charging");
        final LinearLayout charging = ui.card(page);
        ui.switchRow(charging, "🔋", Ui.GREEN, "Limit the charge",
                "Stops charging at the level below, to keep the battery healthy longer",
                ChargeLimitController.PREF_ON, false);
        ui.choiceRow(charging, "🎯", Ui.TEAL, "Charge up to", ChargeLimitController.PREF_LEVEL,
                new String[] {"60 %", "70 %", "75 %", "80 %", "85 %", "90 %"},
                new String[] {"60", "70", "75", "80", "85", "90"}, "80");
        final String chargeState = PartsPrefs.get(this).getString(ChargeLimitController.PREF_STATE,
                null);
        ui.note(page, chargeState != null ? chargeState
                : "Charging resumes 3 points under the limit. Plug the console in above the "
                + "limit and this line tells whether the charger obeyed.");

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

    private final Handler mUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable mUpdateTick;

    @Override
    protected void onPause() {
        super.onPause();
        if (mUpdateTick != null) mUpdateHandler.removeCallbacks(mUpdateTick);
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
                        + Updates.url(SectionActivity.this);
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
            ui.actionRow(now, "🔄", Ui.GREEN, "Restart to finish the update",
                    "The new version is installed; it starts after the restart", () ->
                            getSystemService(android.os.PowerManager.class).reboot(null));
        } else if (busy) {
            ui.row(now, "⏳", Ui.ORANGE, Updates.sState == Updates.APPLYING ? "Installing"
                    : "Downloading", Updates.sMessage + (Updates.sPercent > 0
                    ? " · " + Updates.sPercent + " %" : ""));
        } else if (info != null) {
            final Updates.Pack pack = info.pick();
            ui.row(now, "✨", Ui.TEAL, "Version " + info.version + " available",
                    (info.date.isEmpty() ? "" : info.date + " · ")
                            + (pack == info.full ? "Complete package " : "Small update ")
                            + Updates.size(pack.size));
            if (Updates.sState == Updates.FAILED) {
                ui.row(now, "⚠️", Ui.RED, "Last attempt failed", Updates.sMessage);
            }
            ui.actionRow(now, "⬇️", Ui.GREEN, "Download and install",
                    "In the background; keep the console on Wi-Fi and charged", () -> {
                        startForegroundService(new Intent(this, UpdateService.class));
                        Toast.makeText(this, "Update started", Toast.LENGTH_SHORT).show();
                    });
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
        ui.section(body, "Update source");
        final LinearLayout source = ui.card(body);
        ui.actionRow(source, "🔗", Ui.PURPLE, Updates.customUrl(this) ? "Custom source"
                : "Default source", Updates.url(this), this::editUpdateSource);
        ui.note(body, "For your own build: give the GitHub repository (owner/repo) that publishes "
                + "your releases, or the https address of its update.json.");
        ui.note(body, "The console never restarts on its own: a notification and the button "
                + "above wait for you. An update that cannot be installed leaves the current "
                + "version untouched.");
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
        final LinearLayout general = ui.card(page);
        ui.actionRow(general, "🕹️", Ui.PURPLE, "Button remapping, every app",
                "A drawing of the console: tap a button, pick what it sends", () ->
                        startActivity(new Intent(this, ControllerMapActivity.class)));
        ui.section(page, "Each app");
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
            row[0] = ui.actionRow(card, "🎮", Ui.PURPLE, label, gameText(prefs, key, pkg), () ->
                    new android.app.AlertDialog.Builder(this).setTitle(title)
                            .setItems(new String[] {"Performance profile", "Controller"},
                                    (d, which) -> {
                                        if (which == 0) {
                                            performanceDialog(prefs, key, title, choices, () ->
                                                    ui.setSummary(row[0], gameText(prefs, key, pkg)));
                                        } else {
                                            controllerDialog(prefs, pkg, title, () ->
                                                    ui.setSummary(row[0], gameText(prefs, key, pkg)));
                                        }
                                    }).show());
        }
        ui.note(page, "When you leave the app, the profile and the controller setup you had "
                + "come back.");
    }

    private String gameText(android.content.SharedPreferences prefs, String key, String pkg) {
        final ControllerProfiles.Profile pad = ControllerProfiles.of(prefs, pkg);
        return profileText(prefs, key) + (pad.empty() ? "" : " · Controller: " + pad.summary());
    }

    private void performanceDialog(android.content.SharedPreferences prefs, String key,
            String title, String[] choices, Runnable changed) {
        new android.app.AlertDialog.Builder(this).setTitle(title + " · performance")
                .setItems(choices, (d, which) -> {
                    if (which == 0) prefs.edit().remove(key).apply();
                    else prefs.edit().putString(key, Integer.toString(which - 1)).apply();
                    changed.run();
                }).show();
    }

    private static final int[] ZONES = {-1, 0, 5, 10, 15, 20, 25};

    /** The controller setup of one app: each line goes to its next value when tapped. */
    private void controllerDialog(android.content.SharedPreferences prefs, String pkg,
            String title, Runnable changed) {
        final ControllerProfiles.Profile p = ControllerProfiles.of(prefs, pkg);
        final String[] items = {
            "Swap A ↔ B: " + choiceText(p.swapAb),
            "Swap X ↔ Y: " + choiceText(p.swapXy),
            "Dead zone: " + (p.zone < 0 ? "Same as everywhere"
                    : p.zone == 0 ? "None" : p.zone + " %"),
            "Button remapping on the drawing: " + (p.map == null ? "Same as everywhere"
                    : p.map.isEmpty() ? "None" : p.map.split(",").length + " remaps"),
            "Reset: same as everywhere"};
        new android.app.AlertDialog.Builder(this).setTitle(title + " · controller")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: p.swapAb = nextChoice(p.swapAb); break;
                        case 1: p.swapXy = nextChoice(p.swapXy); break;
                        case 2: {
                            int i = 0;
                            while (i < ZONES.length && ZONES[i] != p.zone) i++;
                            p.zone = ZONES[(i + 1) % ZONES.length];
                            break;
                        }
                        case 3:
                            startActivity(new Intent(this, ControllerMapActivity.class)
                                    .putExtra(ControllerMapActivity.EXTRA_PACKAGE, pkg)
                                    .putExtra(ControllerMapActivity.EXTRA_LABEL, title));
                            return;
                        default:
                            p.swapAb = -1;
                            p.swapXy = -1;
                            p.zone = -1;
                            p.map = null;
                            break;
                    }
                    ControllerProfiles.save(prefs, pkg, p);
                    changed.run();
                    if (which != 4) controllerDialog(prefs, pkg, title, changed);
                }).setPositiveButton("Done", null).show();
    }

    private static String choiceText(int value) {
        return value < 0 ? "Same as everywhere" : value == 1 ? "On" : "Off";
    }

    private static int nextChoice(int value) {
        return value < 0 ? 1 : value == 1 ? 0 : -1;
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
