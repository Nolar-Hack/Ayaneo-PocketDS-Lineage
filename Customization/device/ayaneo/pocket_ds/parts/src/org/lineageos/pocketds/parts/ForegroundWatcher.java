/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells which app was used last (a game, an emulator, any app but the homes and PocketDS Extra)
 * and on which screen it is: the focus of the controller and the profile of each game follow it.
 */
final class ForegroundWatcher {
    private static final String TAG = "PocketDSForeground";
    private static ForegroundWatcher sInstance;

    interface Listener {
        /** The app used last, or null when no app is open. */
        void onForeground(ActivityManager.RunningTaskInfo app);
    }

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private String mLastKey = "";
    private ActivityManager.RunningTaskInfo mCurrent;

    ForegroundWatcher(Context context) {
        mContext = context;
        sInstance = this;
    }

    static ForegroundWatcher get() {
        return sInstance;
    }

    void add(Listener listener) {
        mListeners.add(listener);
    }

    /** The app used last, or null. */
    ActivityManager.RunningTaskInfo current() {
        return mCurrent;
    }

    void start() {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mStack);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "No task stack listener: the apps are polled", e);
        }
        mMain.post(mEvaluate);
        mMain.postDelayed(mPoll, 5000);
    }

    private final TaskStackListener mStack = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mMain.removeCallbacks(mEvaluate);
            mMain.postDelayed(mEvaluate, 150);
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            mMain.removeCallbacks(mEvaluate);
            mMain.postDelayed(mEvaluate, 150);
        }
    };

    private final Runnable mEvaluate = this::evaluate;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            evaluate();
            mMain.postDelayed(this, 5000);
        }
    };

    private void evaluate() {
        final DisplayManager displays = mContext.getSystemService(DisplayManager.class);
        ActivityManager.RunningTaskInfo top = null;
        for (ActivityManager.RunningTaskInfo task : Screens.runningApps(mContext)) {
            final Display display = displays.getDisplay(task.displayId);
            // The apps of the screens of the console only (not a hidden display of an emulator).
            if (display != null && display.getType() == Display.TYPE_INTERNAL) {
                top = task;
                break;
            }
        }
        final String key = top == null ? "" : top.baseActivity.getPackageName() + "@"
                + top.displayId;
        if (key.equals(mLastKey)) return;
        mLastKey = key;
        mCurrent = top;
        for (Listener listener : new ArrayList<>(mListeners)) {
            try {
                listener.onForeground(top);
            } catch (RuntimeException e) {
                Log.w(TAG, "A listener failed", e);
            }
        }
    }
}
