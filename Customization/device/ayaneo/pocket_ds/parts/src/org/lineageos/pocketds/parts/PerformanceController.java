/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;

/**
 * The performance engine of the console: profiles (CPU / GPU frequencies) and fan. It applies the
 * profile in use at every boot and is the one the overlay talks to ({@link #get()}).
 */
class PerformanceController {
    private static PerformanceController sInstance;

    final PerformanceProfiles profiles;
    final FanController fan;
    private final Context mContext;

    PerformanceController(Context context) {
        mContext = context;
        profiles = new PerformanceProfiles(context);
        fan = new FanController(context, profiles);
        sInstance = this;
    }

    static PerformanceController get() {
        return sInstance;
    }

    void start() {
        profiles.apply(profiles.current());
        ProfileStatus.show(mContext, profiles.current());
        fan.start();
        new GpuFloorController(mContext, profiles).start();
        // The profile button of the console (the system, see patch 0018) asks for the next profile.
        mContext.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                select((profiles.current() + 1) % PerformanceProfiles.NAMES.length);
            }
        }, new android.content.IntentFilter(ACTION_NEXT_PROFILE), Context.RECEIVER_NOT_EXPORTED);
    }

    static final String ACTION_NEXT_PROFILE = "org.lineageos.pocketds.parts.NEXT_PROFILE";

    /** Switches profile (and the fan, unless the user picked a mode of his own). */
    void select(int mode) {
        // A profile brings its own fan mode: a fan choice made earlier in the overlay (Off, Mute...)
        // no longer overrides it, so that Max really runs the fan at full speed.
        PartsPrefs.get(mContext).edit().putString(PartsPrefs.FAN_MODE, "profile").apply();
        profiles.select(mode);
        fan.refresh();
        ProfileAnimation.show(mContext, mode);
        ProfileStatus.show(mContext, mode);
    }

    /** A fan mode of the user, or "profile" to follow the profile again. */
    void setFanMode(String mode) {
        PartsPrefs.get(mContext).edit().putString(PartsPrefs.FAN_MODE, mode).apply();
        fan.refresh();
    }
}
