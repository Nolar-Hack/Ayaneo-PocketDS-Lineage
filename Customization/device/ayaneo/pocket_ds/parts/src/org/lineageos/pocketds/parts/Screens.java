/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * The two screens of the console and what the system lets us change about them: which panel is
 * which display (the screens can be swapped, see patches/frameworks/base/0013), the screen mode
 * and the controller focus lock of the AYA settings (Settings.Global, read by
 * patches/frameworks/base/0003-ayaneo-dual-screen.patch), and the home of the bottom screen.
 *
 * A panel is told apart by its unique id, never by the display number: the number of a panel
 * changes when the screens are swapped.
 */
final class Screens {
    private static final String TAG = "PocketDSScreens";

    static final int TOP = 0;
    static final int BOTTOM = 1;

    // Settings.Global of the screens (the first four are the ones AYAHome writes).
    private static final String SETTING_MODE = "dual_display_mode";
    private static final String SETTING_POINTER_MODE = "dual_display_pointer_mode";
    private static final String SETTING_AS_TOUCHPAD = "secondary_screen_as_touchpad";
    private static final String SETTING_FOCUS_LOCK = "locked_focus_display_id";
    private static final String SETTING_SWAP = "dual_display_swap";
    private static final String SETTING_SECOND_HOME = "pocketds_second_home";

    static final String MODE_BOTH = "both";
    static final String MODE_TOP_CURSOR = "top_cursor";
    static final String MODE_TOP_TOUCHPAD = "top_touchpad";
    static final String MODE_BOTTOM = "bottom";

    static final String FOCUS_AUTO = "auto";
    static final String FOCUS_TOP = "top";
    static final String FOCUS_BOTTOM = "bottom";

    // What the screens show when nothing runs on them.


    private Screens() {}

    /** Display number of a panel, or {@link Display#INVALID_DISPLAY} while that panel is off. */
    static int displayId(Context context, int panel) {
        final String wanted = panel == TOP ? PartsPrefs.MAIN_SCREEN_ID : PartsPrefs.SECOND_SCREEN_ID;
        for (Display display : context.getSystemService(DisplayManager.class).getDisplays()) {
            if (wanted.equals(display.getUniqueId())) return display.getDisplayId();
        }
        return Display.INVALID_DISPLAY;
    }

    /** The display that is not the default one: it hosts the home of the second screen. */
    static int secondaryDisplayId(Context context) {
        for (Display display : context.getSystemService(DisplayManager.class).getDisplays()) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY
                    && display.getType() == Display.TYPE_INTERNAL) {
                return display.getDisplayId();
            }
        }
        return Display.INVALID_DISPLAY;
    }

    // ---- swap

    static boolean isSwapped(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING_SWAP, 0) == 1;
    }

    static void setSwapped(Context context, boolean swapped) {
        Settings.Global.putInt(context.getContentResolver(), SETTING_SWAP, swapped ? 1 : 0);
    }

    // ---- screen mode

    static String screenMode(Context context) {
        final int mode = Settings.Global.getInt(context.getContentResolver(), SETTING_MODE, 1);
        final int pointer = Settings.Global.getInt(context.getContentResolver(),
                SETTING_POINTER_MODE, 0);
        if (mode == 4) return MODE_BOTTOM;
        if (mode == 3) return pointer == 2 ? MODE_TOP_TOUCHPAD : MODE_TOP_CURSOR;
        return MODE_BOTH;
    }

    static void setScreenMode(Context context, String mode) {
        final android.content.ContentResolver resolver = context.getContentResolver();
        int dual = 1;
        int pointer = 0;
        switch (mode) {
            case MODE_TOP_CURSOR:
                dual = 3;
                pointer = 1;
                break;
            case MODE_TOP_TOUCHPAD:
                dual = 3;
                pointer = 2;
                break;
            case MODE_BOTTOM:
                dual = 4;
                break;
            default:
                break;
        }
        Settings.Global.putInt(resolver, SETTING_AS_TOUCHPAD, pointer == 2 ? 1 : 0);
        Settings.Global.putInt(resolver, SETTING_POINTER_MODE, pointer);
        Settings.Global.putInt(resolver, SETTING_MODE, dual);
    }

    // ---- controller focus lock

    /**
     * Puts the lock the user chose on the display that shows the chosen panel now. In automatic
     * mode the lock follows the game (see {@link FocusController}).
     */
    static void applyFocusLock(Context context, boolean force) {
        final String lock = PartsPrefs.get(context).getString(PartsPrefs.FOCUS_LOCK, FOCUS_AUTO);
        String value;
        if (FOCUS_TOP.equals(lock) || FOCUS_BOTTOM.equals(lock)) {
            value = "none";
            final int id = displayId(context, FOCUS_TOP.equals(lock) ? TOP : BOTTOM);
            if (id != Display.INVALID_DISPLAY) value = Integer.toString(id);
        } else {
            // Automatic: the screen of the game (see FocusController).
            value = FocusController.autoValue(context);
        }
        final android.content.ContentResolver resolver = context.getContentResolver();
        if (!value.equals(Settings.Global.getString(resolver, SETTING_FOCUS_LOCK))) {
            Settings.Global.putString(resolver, SETTING_FOCUS_LOCK, value);
        }
    }

    /**
     * Gives the controller to the other screen (long press of the swap button): the focus lock
     * goes to the top screen, or to the bottom one when it was on the top one.
     */
    static void toggleFocus(Context context) {
        final android.content.SharedPreferences prefs = PartsPrefs.get(context);
        final boolean toBottom = FOCUS_TOP.equals(prefs.getString(PartsPrefs.FOCUS_LOCK,
                FOCUS_AUTO));
        prefs.edit().putString(PartsPrefs.FOCUS_LOCK, toBottom ? FOCUS_BOTTOM : FOCUS_TOP).apply();
        applyFocusLock(context, true);
        android.widget.Toast.makeText(context, toBottom ? "Controller on the bottom screen"
                : "Controller on the top screen", android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---- homes of the two screens

    /**
     * Makes the system use the homes of PocketDS Extra (Settings.Global, read by
     * patches/frameworks/base/0016): the list of applications for the bottom screen, only the
     * wallpaper for the main one. With {@code show} the homes are put on the screens now.
     */
    static void applyHomeLayout(Context context, boolean show) {
        final android.content.ContentResolver resolver = context.getContentResolver();
        Settings.Global.putInt(resolver, SETTING_SECOND_HOME, 1);
        final int second = secondaryDisplayId(context);
        Log.i(TAG, "Homes: second display " + second + ", show " + show);
        if (!show) return;
        // The main screen: a plain Home intent, the system answers with our home.
        startOn(context, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                Display.DEFAULT_DISPLAY);
        showSecondHome(context);
    }

    /**
     * Shows the overlay of the console, or closes it when it is open. It opens on the bottom
     * screen when both screens are on, else on the screen that is lit.
     */
    static void toggleOverlay(Context context) {
        final OverlayWindow open = OverlayWindow.sInstance;
        if (open != null) {
            open.dismiss();
            return;
        }
        int display = Display.DEFAULT_DISPLAY;
        if (MODE_BOTH.equals(screenMode(context))) {
            final int second = secondaryDisplayId(context);
            if (second != Display.INVALID_DISPLAY) display = second;
        }
        OverlayWindow.toggle(context, display);
    }

    /**
     * Brings the second screen back to its home: the list of applications. With both screens on
     * it goes to the display that is not the default one (the screens can be swapped: the panel
     * behind it changes, the display does not); with one screen only, to that screen.
     */
    static void showSecondHome(Context context) {
        int target = MODE_BOTH.equals(screenMode(context)) ? secondaryDisplayId(context)
                : Display.DEFAULT_DISPLAY;
        if (target == Display.INVALID_DISPLAY) target = Display.DEFAULT_DISPLAY;
        // CLEAR_TOP: whatever sits above the list in its task goes away.
        startOn(context, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .setComponent(new ComponentName(context, SecondHomeActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), target);
    }

    private static void startOn(Context context, Intent intent, int display) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display);
        try {
            context.startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "Home not started on display " + display + ": " + e.getMessage());
        }
    }

    /** One line for the dashboard: how the screens are set now. */
    static String status(Context context) {
        final StringBuilder sb = new StringBuilder();
        if (isSwapped(context)) sb.append("Swapped · ");
        switch (screenMode(context)) {
            case MODE_TOP_CURSOR:
            case MODE_TOP_TOUCHPAD:
                sb.append("Main screen only");
                break;
            case MODE_BOTTOM:
                sb.append("Bottom screen only");
                break;
            default:
                sb.append("Both screens");
                break;
        }
        return sb.toString();
    }

    /** Which panel a display shows now: {@link #TOP}, {@link #BOTTOM} or -1. */
    static int panelOf(Context context, int displayId) {
        if (displayId == displayId(context, TOP)) return TOP;
        if (displayId == displayId(context, BOTTOM)) return BOTTOM;
        return -1;
    }

    /** The apps that are open (no home, no recents), most recent first. */
    static List<ActivityManager.RunningTaskInfo> runningApps(Context context) {
        final List<ActivityManager.RunningTaskInfo> apps = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo task : context.getSystemService(ActivityManager.class)
                .getRunningTasks(40)) {
            final int type = task.configuration.windowConfiguration.getActivityType();
            if (type == android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
                    || type == android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS
                    || task.baseActivity == null
                    || context.getPackageName().equals(task.baseActivity.getPackageName())) {
                continue;
            }
            apps.add(task);
        }
        return apps;
    }

    /** Closes every open app (see {@link #runningApps}) and stops its process; returns how many. */
    static int killAll(Context context) {
        int closed = 0;
        final ActivityManager am = context.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningTaskInfo task : runningApps(context)) {
            try {
                ActivityTaskManager.getService().removeTask(task.taskId);
                am.forceStopPackage(task.baseActivity.getPackageName());
                closed++;
            } catch (android.os.RemoteException | RuntimeException e) {
                Log.w(TAG, "Cannot close " + task.baseActivity, e);
            }
        }
        return closed;
    }

    /** Sends an open app to a panel; false when that screen is off or the move failed. */
    static boolean moveTask(Context context, int taskId, int panel) {
        final int display = displayId(context, panel);
        if (display == Display.INVALID_DISPLAY) return false;
        try {
            ActivityTaskManager.getService().moveRootTaskToDisplay(taskId, display);
            return true;
        } catch (android.os.RemoteException | RuntimeException e) {
            Log.w(TAG, "Cannot move task " + taskId + " to display " + display, e);
            return false;
        }
    }

    /**
     * Opens an app on a panel; false when that screen is off. An app that is already open is
     * sent to that panel instead of being started a second time.
     */
    static boolean launch(Context context, ComponentName app, int panel) {
        final int display = displayId(context, panel);
        if (display == Display.INVALID_DISPLAY) return false;
        for (ActivityManager.RunningTaskInfo task : runningApps(context)) {
            // An app of its own: by package. PocketDS Extra also holds the homes and the overlay, so
            // there only the task of that very activity counts.
            final boolean ours = app.getPackageName().equals(context.getPackageName());
            if (ours ? task.baseActivity.equals(app)
                    : task.baseActivity.getPackageName().equals(app.getPackageName())) {
                return moveTask(context, task.taskId, panel);
            }
        }
        final Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display);
        try {
            context.startActivity(intent, options.toBundle());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot start " + app + " on display " + display, e);
            return false;
        }
    }
}
