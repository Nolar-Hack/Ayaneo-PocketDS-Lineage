/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * A performance profile for each app: when an app that has one comes to the front the profile is
 * switched on, and the profile that was in use comes back when another app is used.
 */
final class GameProfiles implements ForegroundWatcher.Listener {
    static final String KEY_PREFIX = "game_profile_";
    private final Context mContext;
    private final SharedPreferences mPrefs;
    private int mRestore = -1;

    GameProfiles(Context context) {
        mContext = context;
        mPrefs = PartsPrefs.get(context);
    }

    void start() {
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        if (watcher != null) watcher.add(this);
        EmulatorDefaults.createProfiles(mPrefs);
    }

    /** The profile chosen for an app: 0 to 4, or -1 for none. */
    static int profileOf(SharedPreferences prefs, String packageName) {
        return PartsPrefs.getInt(prefs, KEY_PREFIX + packageName, -1);
    }

    @Override
    public void onForeground(ActivityManager.RunningTaskInfo app) {
        final PerformanceController engine = PerformanceController.get();
        if (engine == null) return;
        final String pkg = app == null ? null : app.baseActivity.getPackageName();
        // an emulator seen for the first time gets the default profile of what it emulates
        EmulatorDefaults.assignIfNew(mPrefs, pkg);
        final int wanted = pkg == null ? -1 : profileOf(mPrefs, pkg);
        if (wanted >= 0 && wanted < PerformanceProfiles.NAMES.length) {
            if (mRestore < 0) mRestore = engine.profiles.current();
            if (engine.profiles.current() != wanted) engine.select(wanted);
        } else if (mRestore >= 0) {
            if (engine.profiles.current() != mRestore) engine.select(mRestore);
            mRestore = -1;
        }
    }
}
