/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerProxy;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A volume for each app, on top of the master volume: every player of an app is scaled by the
 * level chosen for it (0-100 %). The players are the ones the audio service lists; the level is
 * applied again whenever a player starts or changes, since an app can reset its own volume.
 */
class AppVolumeController extends AudioManager.AudioPlaybackCallback {
    private static final String TAG = "PocketDSAppVolume";
    private static final String PREFS = "app_volumes";

    static final class Entry {
        String packageName;
        String label;
        boolean playing;
        int percent;
    }

    private static AppVolumeController sInstance;

    private final Context mContext;
    private final AudioManager mAudio;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    AppVolumeController(Context context) {
        mContext = context;
        mAudio = context.getSystemService(AudioManager.class);
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sInstance = this;
    }

    static AppVolumeController get() {
        return sInstance;
    }

    void start() {
        mAudio.registerAudioPlaybackCallback(this, mHandler);
    }

    int percent(String packageName) {
        return mPrefs.getInt(packageName, 100);
    }

    /** Sets the level of an app and applies it to its players now. */
    void set(String packageName, int percent) {
        mPrefs.edit().putInt(packageName, percent).apply();
        apply(mAudio.getActivePlaybackConfigurations());
    }

    /** The apps that are playing, then the ones that have a level of their own. */
    List<Entry> entries() {
        final Map<String, Entry> found = new LinkedHashMap<>();
        final PackageManager pm = mContext.getPackageManager();
        for (AudioPlaybackConfiguration config : mAudio.getActivePlaybackConfigurations()) {
            final String pkg = packageOf(config.getClientUid());
            if (pkg == null || pkg.equals(mContext.getPackageName())) continue;
            final Entry entry = found.computeIfAbsent(pkg, k -> newEntry(pm, k));
            entry.playing |= config.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED;
        }
        for (String pkg : mPrefs.getAll().keySet()) {
            found.computeIfAbsent(pkg, k -> newEntry(pm, k));
        }
        return new ArrayList<>(found.values());
    }

    private Entry newEntry(PackageManager pm, String pkg) {
        final Entry entry = new Entry();
        entry.packageName = pkg;
        entry.percent = percent(pkg);
        try {
            entry.label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            entry.label = pkg;
        }
        return entry;
    }

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        apply(configs);
    }

    private void apply(List<AudioPlaybackConfiguration> configs) {
        for (AudioPlaybackConfiguration config : configs) {
            final String pkg = packageOf(config.getClientUid());
            if (pkg == null) continue;
            final int percent = percent(pkg);
            if (percent >= 100 && !mPrefs.contains(pkg)) continue;
            try {
                final PlayerProxy proxy = config.getPlayerProxy();
                if (proxy != null) proxy.setVolume(percent / 100f);
            } catch (RuntimeException e) {
                Log.d(TAG, "Player of " + pkg + " not scaled: " + e.getMessage());
            }
        }
    }

    private String packageOf(int uid) {
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        return packages == null || packages.length == 0 ? null : packages[0];
    }
}
