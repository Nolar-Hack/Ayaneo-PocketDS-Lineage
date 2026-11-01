/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Keeps the screen settings true: the homes chosen in the Screens settings are the ones the system
 * uses, and the controller focus lock follows its panel when the screens are swapped.
 */
class ScreensController {
    static final String ACTION_TOGGLE_OVERLAY = OverlayWindow.ACTION_TOGGLE;
    static final String ACTION_TOGGLE_FOCUS = "org.lineageos.pocketds.parts.TOGGLE_FOCUS";
    static final String ACTION_SHOW_SECOND_HOME = "org.lineageos.pocketds.parts.SHOW_SECOND_HOME";

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mReapply = this::reapply;

    ScreensController(Context context) {
        mContext = context;
    }

    private void reapply() {
        Screens.applyHomeLayout(mContext, true);
    }

    void start() {
        // The homes are chosen by the system (see patch 0016): here only the settings
        // are made true. They are not started by hand: that early the second display cannot host
        // a home yet, and the activity landed on the main screen.
        Screens.applyHomeLayout(mContext, false);
        // The small button of the console (the "home" button, handled by the system, patch 0018):
        // back to the home of the bottom screen.
        mContext.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                Screens.homeOrLastApp(mContext);
            }
        }, new android.content.IntentFilter(ACTION_SHOW_SECOND_HOME),
                Context.RECEIVER_NOT_EXPORTED);
        // The AYA window button of the console: the overlay.
        mContext.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                Screens.toggleOverlay(mContext);
            }
        }, new android.content.IntentFilter(ACTION_TOGGLE_OVERLAY),
                Context.RECEIVER_NOT_EXPORTED);
        // Every other press of the extra buttons: the system names the button, the action is the
        // one chosen in the Controller page.
        mContext.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                ButtonActions.run(mContext, intent.getStringExtra("button"),
                        intent.getBooleanExtra("long", false));
            }
        }, new android.content.IntentFilter(ButtonActions.ACTION), Context.RECEIVER_NOT_EXPORTED);
        // Long press of the swap button: the controller goes to the other screen.
        mContext.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                Screens.toggleFocus(mContext);
            }
        }, new android.content.IntentFilter(ACTION_TOGGLE_FOCUS), Context.RECEIVER_NOT_EXPORTED);
        // The wallpaper-only home of the main screen needs both screens: put the right homes on
        // them again when the screen mode changes (and once at the end of the first setup).
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("dual_display_mode"), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        mHandler.removeCallbacks(mReapply);
                        mHandler.postDelayed(mReapply, 2500);
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("dual_display_swap"), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        // Once the displays have moved.
                        mHandler.postDelayed(() -> Screens.applyFocusLock(mContext, false), 1500);
                    }
                }, UserHandle.USER_ALL);
    }
}
