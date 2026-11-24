/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps the screens on while the GPU works, in any app, or while a game is open. Android only knows what an app asks
 * for (a video player or a game that asks to keep the screen on); a game played with the
 * controller, an emulator or a web page that does not ask would see the screen go off after the
 * delay of the settings in the middle of the action. While the screen is on, one reading a second:
 * a game in front (an emulator, an app of the game category or with a game profile), whatever the
 * load of the GPU, or a GPU above 3 % (measured: 0 or 1 % at rest, 9 then 32 % for a video) holds
 * a screen wake lock for 30 s more. When both stop, the lock ends by itself and the delay of the
 * settings starts again from there.
 */
final class KeepScreenOn {
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage";
    private static final long TICK_MS = 1_000;
    private static final long LOCK_MS = 30_000;
    /** The GPU works above this (percent); at rest it reads 0 or 1, now and then a short peak. */
    private static final int BUSY_ABOVE = 3;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PowerManager mPower;
    private PowerManager.WakeLock mLock;

    KeepScreenOn(Context context) {
        mContext = context;
        mPower = context.getSystemService(PowerManager.class);
    }

    @SuppressWarnings("deprecation")
    void start() {
        // The old screen locks still work for a system app and are the only ones that hold the
        // screen on; ON_AFTER_RELEASE starts the delay of the settings again at the release.
        mLock = mPower.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ON_AFTER_RELEASE, "PocketDS:gpu-busy");
        mLock.setReferenceCounted(false);
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mHandler.removeCallbacks(mTick);
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    mHandler.postDelayed(mTick, TICK_MS);
                } else {
                    release();
                }
            }
        }, filter);
        if (mPower.isInteractive()) mHandler.postDelayed(mTick, TICK_MS);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mPower.isInteractive()) return;
            if (gameInFront() || PerfOverlayController.readLong(GPU, 0) > BUSY_ABOVE) {
                mLock.acquire(LOCK_MS);
            }
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    /** The app in front is a game: an emulator, a game of the store, or an app with a game profile. */
    private boolean gameInFront() {
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        final android.app.ActivityManager.RunningTaskInfo app =
                watcher == null ? null : watcher.current();
        return app != null && PerfHints.isGame(mContext, app.baseActivity.getPackageName());
    }

    private void release() {
        if (mLock != null && mLock.isHeld()) mLock.release();
    }
}
