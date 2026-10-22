/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Smart Pixels: a see-through window over the screen draws black pixels in an ordered-dither
 * pattern (3 to 50 % of the pixels), and moves the pattern by one pixel every few minutes. On an
 * OLED a black pixel emits nothing: less power, and no pixel stays lit by a static interface for
 * hours. The main screen of the Pocket DS is the OLED; the second one (LCD) saves nothing, it is
 * an option. Same idea as the Smart Pixels of AICP, which needs the framework of that ROM: this
 * one is a plain overlay window of a system app.
 */
class SmartPixelsController implements DisplayManager.DisplayListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PocketDSSmartPixels";
    private static final int TILE = 8;
    // Ordered-dither (Bayer) matrix 8x8: a pixel is black when its value is under the count, so
    // the black pixels are spread evenly whatever the share (from 1 pixel in 64, 1.6 %).
    private static final int[][] BAYER = bayer(TILE);

    private static int[][] bayer(int size) {
        int[][] matrix = {{0}};
        for (int n = 1; n < size; n *= 2) {
            final int[][] next = new int[n * 2][n * 2];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    final int v = matrix[y][x] * 4;
                    next[y][x] = v;
                    next[y][x + n] = v + 2;
                    next[y + n][x] = v + 3;
                    next[y + n][x + n] = v + 1;
                }
            }
            matrix = next;
        }
        return matrix;
    }

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, PatternView> mViews = new HashMap<>();
    private boolean mPowerSave;
    private int mShift;

    SmartPixelsController(Context context) {
        mContext = context;
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mPrefs = PartsPrefs.get(context);
    }

    void start() {
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        mDisplayManager.registerDisplayListener(this, mHandler,
                DisplayManager.EVENT_TYPE_DISPLAY_ADDED
                        | DisplayManager.EVENT_TYPE_DISPLAY_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                update();
            }
        }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), null, mHandler);
        update();
    }

    private boolean enabled() {
        if (mPrefs.getBoolean(PartsPrefs.SMART_PIXELS, false)) return true;
        return mPrefs.getBoolean(PartsPrefs.SMART_PIXELS_ON_SAVER, false)
                && mContext.getSystemService(PowerManager.class).isPowerSaveMode();
    }

    private boolean wanted(Display display) {
        final String screens = mPrefs.getString(PartsPrefs.SMART_PIXELS_SCREEN,
                PartsPrefs.SCREEN_MAIN);
        final String id = display.getUniqueId();
        if (PartsPrefs.MAIN_SCREEN_ID.equals(id)) {
            return !PartsPrefs.SCREEN_SECOND.equals(screens);
        }
        if (PartsPrefs.SECOND_SCREEN_ID.equals(id)) {
            return !PartsPrefs.SCREEN_MAIN.equals(screens);
        }
        return false;
    }

    /** Puts the windows in line with the settings: one per wanted screen when enabled. */
    private void update() {
        mHandler.removeCallbacks(mShiftTask);
        final boolean on = enabled();
        final Map<Integer, PatternView> stale = new HashMap<>(mViews);
        if (on) {
            for (Display display : mDisplayManager.getDisplays()) {
                if (!wanted(display)) continue;
                PatternView view = mViews.get(display.getDisplayId());
                if (view == null) {
                    view = add(display);
                }
                if (view != null) {
                    stale.remove(display.getDisplayId());
                    view.setPattern(percentToCount(), mShift);
                }
            }
        }
        for (Map.Entry<Integer, PatternView> entry : stale.entrySet()) {
            remove(entry.getKey(), entry.getValue());
        }
        final int minutes = PartsPrefs.getInt(mPrefs, PartsPrefs.SMART_PIXELS_SHIFT_MIN, 5);
        if (on && minutes > 0 && !mViews.isEmpty()) {
            mHandler.postDelayed(mShiftTask, minutes * 60_000L);
        }
    }

    private final Runnable mShiftTask = new Runnable() {
        @Override
        public void run() {
            mShift = (mShift + 1) % (TILE * TILE);
            update();
        }
    };

    private int percentToCount() {
        final int percent = PartsPrefs.getInt(mPrefs, PartsPrefs.SMART_PIXELS_PERCENT, 12);
        // 64 pixels per tile: 3 % = 2, 6 % = 4, 12 % = 8, 25 % = 16, 50 % = 32
        return Math.max(1, Math.min(TILE * TILE / 2, Math.round(percent * TILE * TILE / 100f)));
    }

    private PatternView add(Display display) {
        try {
            final Context displayContext = mContext.createDisplayContext(display);
            final Context windowContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            final WindowManager windowManager = windowContext.getSystemService(
                    WindowManager.class);
            final PatternView view = new PatternView(windowContext);
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("PocketDS Smart Pixels");
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            windowManager.addView(view, lp);
            view.mWindowManager = windowManager;
            mViews.put(display.getDisplayId(), view);
            Log.i(TAG, "Smart Pixels on display " + display.getDisplayId());
            return view;
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot add the Smart Pixels window on display "
                    + display.getDisplayId(), e);
            return null;
        }
    }

    private void remove(int displayId, PatternView view) {
        try {
            if (view.mWindowManager != null) view.mWindowManager.removeView(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot remove the Smart Pixels window of display " + displayId, e);
        }
        mViews.remove(displayId);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key != null && key.startsWith("smart_pixels")) {
            mHandler.post(this::update);
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        update();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        final PatternView view = mViews.remove(displayId);
        if (view != null) view.mWindowManager = null; // the display is gone with its windows
        update();
    }

    @Override
    public void onDisplayChanged(int displayId) {}

    /** The pattern: black pixels where the dither matrix is under the count. */
    private static final class PatternView extends View {
        WindowManager mWindowManager;
        private final Paint mPaint = new Paint();
        private int mCount = -1;
        private int mShift = -1;

        PatternView(Context context) {
            super(context);
        }

        void setPattern(int count, int shift) {
            if (count == mCount && shift == mShift) return;
            mCount = count;
            mShift = shift;
            final Bitmap tile = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    tile.setPixel(x, y, BAYER[y][x] < count ? Color.BLACK : Color.TRANSPARENT);
                }
            }
            final BitmapShader shader = new BitmapShader(tile, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT);
            final Matrix matrix = new Matrix();
            matrix.setTranslate(shift % TILE, shift / TILE);
            shader.setLocalMatrix(matrix);
            mPaint.setShader(shader);
            mPaint.setFilterBitmap(false);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        }
    }
}
