/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Screen off / on animation like a CRT television (patch 0012 of the framework, in ColorFade).
 * The framework reads the persistent property when it draws a frame: this keeps it in line with
 * the option of the app.
 */
class CrtAnimation implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PocketDSParts";
    private static final String PROP = "persist.sys.pocketds.crt";

    private final SharedPreferences mPrefs;

    CrtAnimation(Context context) {
        mPrefs = PartsPrefs.get(context);
    }

    void start() {
        apply();
        mPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PartsPrefs.CRT_SCREEN_OFF.equals(key)) apply();
    }

    private void apply() {
        try {
            SystemProperties.set(PROP, mPrefs.getBoolean(PartsPrefs.CRT_SCREEN_OFF, true)
                    ? "true" : "false");
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot set " + PROP, e);
        }
    }
}
