/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.Intent;

/**
 * What the extra buttons of the console do (swap, AYA / M, home, profile, FPS). The system only
 * says which button was pressed (patch 0021 of frameworks/base); the action is chosen here, in the
 * Controller page, and a button that was never set keeps its former job.
 */
final class ButtonActions {
    static final String ACTION = "org.lineageos.pocketds.parts.BUTTON";

    /** Buttons as the system names them, the name of their drawing, the action they had. */
    static final String[] BUTTONS = {"swap", "aya", "home", "profile", "fps"};
    static final String[] BUTTON_NAMES = {"Swap", "AYA / M", "Home", "Profile", "FPS"};
    private static final String[] DEFAULTS = {"swap", "overlay", "home", "profile", "fps"};

    /** The actions a button can be given. */
    static final String[] IDS = {"swap", "overlay", "home", "profile", "fps", "keyboard", "focus",
        "none"};
    static final String[] NAMES = {"Swap the screens", "Overlay", "Apps, then last app",
        "Next performance profile", "FPS bar", "Keyboard (show / hide, stays open)",
        "Controller to the other screen", "Nothing"};

    private ButtonActions() {}

    /** The button of a drawing region ("AYA / M", "Home"...), or null. */
    static String buttonOf(String regionName) {
        for (int i = 0; i < BUTTON_NAMES.length; i++) {
            if (BUTTON_NAMES[i].equals(regionName)) return BUTTONS[i];
        }
        return null;
    }

    private static String key(String button, boolean longPress) {
        return "button_" + button + (longPress ? "_long" : "");
    }

    /**
     * The action of a button for a short or a long press. A short press keeps its former job; a
     * long press does nothing, except on the swap button where it gives the controller to the
     * other screen, as it always did.
     */
    static String get(Context context, String button, boolean longPress) {
        String fallback = longPress && "swap".equals(button) ? "focus" : "none";
        if (!longPress) {
            for (int i = 0; i < BUTTONS.length; i++) {
                if (BUTTONS[i].equals(button)) fallback = DEFAULTS[i];
            }
        }
        return PartsPrefs.get(context).getString(key(button, longPress), fallback);
    }

    static void set(Context context, String button, boolean longPress, String action) {
        PartsPrefs.get(context).edit().putString(key(button, longPress), action).apply();
    }

    static void run(Context context, String button, boolean longPress) {
        if (button == null) return;
        switch (get(context, button, longPress)) {
            case "swap":
                // only while both screens are in use (screen mode 1)
                if (Screens.MODE_BOTH.equals(Screens.screenMode(context))) {
                    Screens.setSwapped(context, !Screens.isSwapped(context));
                }
                break;
            case "overlay":
                Screens.toggleOverlay(context);
                break;
            case "home":
                Screens.homeOrLastApp(context);
                break;
            case "profile":
                send(context, PerformanceController.ACTION_NEXT_PROFILE);
                break;
            case "fps":
                send(context, PerfOverlayController.ACTION_TOGGLE);
                break;
            case "keyboard":
                send(context, PocketKeyboardService.ACTION_TOGGLE);
                break;
            case "focus":
                Screens.toggleFocus(context);
                break;
            default:
                break;
        }
    }

    private static void send(Context context, String action) {
        context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()));
    }
}
