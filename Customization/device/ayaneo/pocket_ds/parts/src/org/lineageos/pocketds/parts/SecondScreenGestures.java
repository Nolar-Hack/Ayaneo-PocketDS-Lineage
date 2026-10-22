/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * "Back" gesture on the second screen. The system gestures (swipe from an edge) only work on the
 * default display, which has the navigation bar: the other screen, which has no system bars, had
 * no way back. Two thin invisible strips along its left and right edges turn a swipe towards the
 * inside of the screen into a Back key sent to that screen.
 *
 * The strip follows the display that is not the default one, so it moves with a swap of the
 * screens.
 */
class SecondScreenGestures implements DisplayManager.DisplayListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PocketDSGestures";
    private static final int EDGE_DP = 22;
    private static final int SWIPE_DP = 72;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<View> mViews = new ArrayList<>();
    private WindowManager mWindowManager;
    private int mDisplayId = Display.INVALID_DISPLAY;

    SecondScreenGestures(Context context) {
        mContext = context;
    }

    void start() {
        mContext.getSystemService(DisplayManager.class).registerDisplayListener(this, mHandler);
        PartsPrefs.get(mContext).registerOnSharedPreferenceChangeListener(this);
        update();
    }

    private boolean enabled() {
        return PartsPrefs.get(mContext).getBoolean(PartsPrefs.SECOND_GESTURES, true);
    }

    private void update() {
        final int wanted = enabled() ? Screens.secondaryDisplayId(mContext)
                : Display.INVALID_DISPLAY;
        if (wanted == mDisplayId && (wanted == Display.INVALID_DISPLAY || !mViews.isEmpty())) {
            return;
        }
        remove();
        mDisplayId = wanted;
        if (wanted != Display.INVALID_DISPLAY) add(wanted);
    }

    private void add(int displayId) {
        try {
            final Display display = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(displayId);
            if (display == null) return;
            final Context windowContext = mContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY, null);
            mWindowManager = windowContext.getSystemService(WindowManager.class);
            final int edge = Math.round(EDGE_DP * windowContext.getResources()
                    .getDisplayMetrics().density);
            final int swipe = Math.round(SWIPE_DP * windowContext.getResources()
                    .getDisplayMetrics().density);
            addStrip(windowContext, Gravity.LEFT, edge, swipe, displayId);
            addStrip(windowContext, Gravity.RIGHT, edge, swipe, displayId);
            Log.i(TAG, "Back gesture on display " + displayId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Back gesture not added on display " + displayId, e);
            remove();
        }
    }

    private void addStrip(Context windowContext, int side, int edge, int swipe, int displayId) {
        final View strip = new View(windowContext);
        strip.setOnTouchListener(new View.OnTouchListener() {
            private float mStartX;
            private float mStartY;
            private boolean mDone;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mStartX = event.getRawX();
                        mStartY = event.getRawY();
                        mDone = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        final float dx = event.getRawX() - mStartX;
                        final float dy = event.getRawY() - mStartY;
                        final boolean inside = side == Gravity.LEFT ? dx > swipe : -dx > swipe;
                        if (!mDone && inside && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            mDone = true;
                            sendBack(displayId);
                        }
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(edge,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = side | Gravity.TOP;
        lp.setTitle("PocketDSBackGesture");
        mWindowManager.addView(strip, lp);
        mViews.add(strip);
    }

    private void sendBack(int displayId) {
        final InputManager input = mContext.getSystemService(InputManager.class);
        final long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            final KeyEvent event = new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0);
            event.setSource(InputDevice.SOURCE_KEYBOARD);
            event.setDisplayId(displayId);
            input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    }

    private void remove() {
        for (View view : mViews) {
            try {
                mWindowManager.removeView(view);
            } catch (RuntimeException e) {
                Log.w(TAG, "Strip already gone", e);
            }
        }
        mViews.clear();
        mDisplayId = Display.INVALID_DISPLAY;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        update();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        update();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        update();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PartsPrefs.SECOND_GESTURES.equals(key)) update();
    }
}
