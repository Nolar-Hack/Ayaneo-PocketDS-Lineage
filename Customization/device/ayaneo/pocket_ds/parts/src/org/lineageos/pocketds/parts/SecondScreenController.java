/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

/**
 * Second (bottom, 5") screen:
 *  - its brightness is the one of the AYA settings (Settings.Global
 *    secondary_screen_brightness, 1-255), applied by its display power controller: AYAHome
 *    also writes it to the panel, both must agree or they overwrite each other. Until AYA
 *    sets it, the second screen takes the brightness of the main screen;
 *  - the keyboard of apps on the main screen is shown on it, like on a DS (read by
 *    patches/frameworks/base/0001-ime-on-second-screen.patch), once the setup wizard is
 *    done;
 *  - during the first setup the second screen is off (Ayaneo "main screen only" mode, see
 *    patches/frameworks/base/0003-ayaneo-dual-screen.patch), as on stock: the setup wizard
 *    and its keyboard are on the main screen.
 */
class SecondScreenController implements DisplayManager.DisplayListener {
    private static final String TAG = "PocketDSParts";
    // Built-in 768x1024 LCD (port 132), see configs/display_settings.xml.
    private static final String SECOND_SCREEN_ID = "local:4630946708815090308";
    private static final String PROP_IME_DISPLAY = "persist.sys.pocketds.ime_display";
    // Ayaneo screen mode (Settings.Global, AYAHome Dual Screen panel): 1 both, 3 main only.
    private static final String SETTING_DUAL_DISPLAY_MODE = "dual_display_mode";
    private static final int DUAL_DISPLAY_MODE_BOTH = 1;
    private static final int DUAL_DISPLAY_MODE_MAIN_ONLY = 3;
    // Set while the second screen is turned off for the setup wizard.
    private static final String PROP_SETUP_MAIN_ONLY = "persist.sys.pocketds.setup_main_only";
    // AYA settings: second screen backlight level (1-255).
    private static final String SETTING_SECONDARY_BRIGHTNESS = "secondary_screen_brightness";
    private static final float SECONDARY_BRIGHTNESS_MAX = 255f;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mSecondDisplayId = Display.INVALID_DISPLAY;

    SecondScreenController(Context context) {
        mContext = context;
        mDisplayManager = context.getSystemService(DisplayManager.class);
    }

    void start() {
        mDisplayManager.registerDisplayListener(this, mHandler,
                DisplayManager.EVENT_TYPE_DISPLAY_ADDED
                        | DisplayManager.EVENT_TYPE_DISPLAY_REMOVED
                        | DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                        | DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING_SECONDARY_BRIGHTNESS), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        syncBrightness();
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateImeDisplay();
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("dual_display_swap"), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mHandler.postDelayed(() -> updateImeDisplay(), 1500);
                    }
                }, UserHandle.USER_ALL);
        findSecondScreen();
    }

    private void findSecondScreen() {
        mSecondDisplayId = Display.INVALID_DISPLAY;
        for (Display display : mDisplayManager.getDisplays()) {
            if (SECOND_SCREEN_ID.equals(display.getUniqueId())) {
                mSecondDisplayId = display.getDisplayId();
            }
        }
        Log.i(TAG, "Second screen display id: " + mSecondDisplayId);
        updateImeDisplay();
        syncBrightness();
    }

    private void updateImeDisplay() {
        // The keyboard of PocketDS Extra fills the bottom screen, setup wizard included: both
        // screens stay on during the first setup. An older install may still have the setup mode
        // "main screen only" on: put both screens back.
        if (SystemProperties.getBoolean(PROP_SETUP_MAIN_ONLY, false)) {
            Settings.Global.putInt(mContext.getContentResolver(), SETTING_DUAL_DISPLAY_MODE,
                    DUAL_DISPLAY_MODE_BOTH);
            SystemProperties.set(PROP_SETUP_MAIN_ONLY, "false");
        }
        // The keyboard goes to the screen that is not the main one, whichever panel that is now:
        // after a swap it is the top panel.
        int target = Screens.secondaryDisplayId(mContext);
        if (target == Display.INVALID_DISPLAY) target = mSecondDisplayId;
        final String value = Integer.toString(target);
        if (!value.equals(SystemProperties.get(PROP_IME_DISPLAY, ""))) {
            Log.i(TAG, "Keyboard display: " + target);
            SystemProperties.set(PROP_IME_DISPLAY, value);
        }
    }

    private void syncBrightness() {
        if (mSecondDisplayId == Display.INVALID_DISPLAY) return;
        final int level = Settings.Global.getInt(mContext.getContentResolver(),
                SETTING_SECONDARY_BRIGHTNESS, -1);
        final float brightness = level > 0
                ? Math.min(level / SECONDARY_BRIGHTNESS_MAX, 1f)
                : mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
        if (brightness >= 0f && brightness != mDisplayManager.getBrightness(mSecondDisplayId)) {
            mDisplayManager.setBrightness(mSecondDisplayId, brightness);
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        findSecondScreen();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (displayId == mSecondDisplayId) findSecondScreen();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        // The screens were swapped: the second panel has another display number.
        if (Screens.displayId(mContext, Screens.BOTTOM) != mSecondDisplayId) {
            findSecondScreen();
        }
        updateImeDisplay();
        // Until AYA sets the second screen brightness, it follows the main screen.
        if (displayId == Display.DEFAULT_DISPLAY && Settings.Global.getInt(
                mContext.getContentResolver(), SETTING_SECONDARY_BRIGHTNESS, -1) <= 0) {
            syncBrightness();
        }
    }
}
