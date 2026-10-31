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
    static final String CONTROLLER = "controller";
    static final String UPDATES = "updates";
    static final String MOTION = "motion";

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
        if (mResumedBefore && (CONTROLLER.equals(section()) || GAMES.equals(section()))) {
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
            case CONTROLLER:
                controllerPage(ui, page);
                break;
            case UPDATES:
                updates(ui, page);
                break;
            case MOTION:
                motion(ui, page);
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
                "Calibration of the gyroscope, for games that aim with it. The direction is in "
                        + "Controller.");
        mMotion = new MotionCalibration(this);
        final Handler main = new Handler(Looper.getMainLooper());

        // A change is read by the sensors at boot only.
        final LinearLayout pending = ui.card(page);
        ui.actionRow(pending, "🔄", Ui.ORANGE, "Restart to apply",
                "The sensors read their settings when the console starts",
                () -> getSystemService(android.os.PowerManager.class).reboot(null));
        pending.setVisibility(android.view.View.GONE);

        ui.section(page, "Calibration");
        ui.note(page, "Put the console flat on a steady surface and do not touch it while it"
                + " measures (10 s). A gyroscope that drifts when the console is still reads a"
                + " small constant: this removes it.");
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

    /** The three steps of an update, each done, running or waiting (step = the one running). */
    private void updateSteps(Ui ui, LinearLayout body, int step, int percent) {
        final LinearLayout card = ui.card(body);
        final String[] names = {"Download", "Install in the other system slot", "Restart"};
        final String[] hints = {"The package is fetched from the release",
                "Written beside the running system, which stays untouched",
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
        setTitle("Profiles");
        ui.hero(page, "🎮", Ui.PURPLE, "Profiles",
                "For each app: a performance profile and a controller profile, switched on when it opens");
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
            final Runnable refresh = () -> ui.setSummary(row[0], gameText(prefs, key, pkg));
            row[0] = ui.actionRow(card, "🎮", Ui.PURPLE, label, gameText(prefs, key, pkg), () ->
                    new android.app.AlertDialog.Builder(this).setTitle(title)
                            .setItems(new String[] {"Performance profile",
                                "Controller profile",
                                "GPU floor: " + (GpuFloorController.isOn(prefs, pkg) ? "on (tap to switch off)"
                                        : "off (tap to switch on)")}, (d, which) -> {
                                if (which == 0) performanceDialog(prefs, key, title, choices, refresh);
                                else if (which == 1) controllerPickDialog(prefs, pkg, title, refresh);
                                else {
                                    prefs.edit().putBoolean(GpuFloorController.KEY_PREFIX + pkg,
                                            !GpuFloorController.isOn(prefs, pkg)).apply();
                                    refresh.run();
                                }
                            }).show());
        }
        ui.note(page, "When you leave the app, the profile and the controller setup you had "
                + "come back. Controller profiles are made in Controller. The GPU floor (set "
                + "per profile in the overlay, lowest = none) is only raised for the apps that have "
                + "it switched on.");
    }

    private String gameText(android.content.SharedPreferences prefs, String key, String pkg) {
        final ControllerProfiles.Named pad = ControllerProfiles.find(prefs,
                ControllerProfiles.assignedId(prefs, pkg));
        return profileText(prefs, key) + (pad == null ? "" : " · Controller: " + pad.name)
                + (GpuFloorController.isOn(prefs, pkg) ? " · GPU floor" : "");
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

    /** Which controller profile an app uses: one of the named ones, none, or a new one. */
    private void controllerPickDialog(android.content.SharedPreferences prefs, String pkg,
            String title, Runnable changed) {
        final java.util.List<ControllerProfiles.Named> list = ControllerProfiles.all(prefs);
        final String[] items = new String[list.size() + 2];
        items[0] = "None (same as everywhere)";
        for (int i = 0; i < list.size(); i++) items[i + 1] = list.get(i).name;
        items[items.length - 1] = "New profile…";
        new android.app.AlertDialog.Builder(this).setTitle(title + " · controller")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        ControllerProfiles.assign(prefs, pkg, null);
                        changed.run();
                    } else if (which == items.length - 1) {
                        nameDialog("New controller profile", "", name -> {
                            final ControllerProfiles.Named n = ControllerProfiles.create(prefs,
                                    name);
                            ControllerProfiles.assign(prefs, pkg, n.id);
                            changed.run();
                            openRemap(n);
                        });
                    } else {
                        ControllerProfiles.assign(prefs, pkg, list.get(which - 1).id);
                        changed.run();
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
                "What is used when an app has no profile: tap a button on a drawing of the "
                        + "console", () -> startActivity(new Intent(this,
                        ControllerMapActivity.class)));
        final LinearLayout tools = ui.card(page);
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
