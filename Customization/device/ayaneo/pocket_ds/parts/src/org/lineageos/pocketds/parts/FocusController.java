/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.content.Context;

/**
 * "Automatic" controller focus: the controller goes to the screen of the app used last (the
 * game), and stays there when the other screen is touched (the list of apps, the overlay,
 * a keyboard). Without it, touching the bottom screen takes the focus away from the game, which
 * then gets no input and is reported as not responding. While the overlay is open the controller
 * goes to the overlay, so that its buttons and sticks can be used.
 */
final class FocusController implements ForegroundWatcher.Listener {
    private final Context mContext;

    FocusController(Context context) {
        mContext = context;
    }

    void start() {
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        if (watcher != null) watcher.add(this);
        Screens.applyFocusLock(mContext, true);
    }

    @Override
    public void onForeground(ActivityManager.RunningTaskInfo app) {
        Screens.applyFocusLock(mContext, true);
    }

    /** The display the controller should go to in automatic mode, as the setting wants it. */
    static String autoValue(Context context) {
        final int overlay = OverlayWindow.takesController(context) ? OverlayWindow.openDisplay()
                : -1;
        if (overlay >= 0) return Integer.toString(overlay);
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        final ActivityManager.RunningTaskInfo app = watcher == null ? null : watcher.current();
        return app == null ? "none" : Integer.toString(app.displayId);
    }
}
