/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;

import java.util.Locale;

/**
 * The animation of a profile change, on the main screen: a card that pops up with the icon and the
 * name of the profile, five bars that light up to its level, and its numbers (CPU, GPU, fan). It
 * is a see-through window of the app, gone after a second and a half.
 */
final class ProfileAnimation extends View {
    private static final String TAG = "PocketDSProfileAnim";
    private static final long DURATION_MS = 1800;
    private static final String[] ICONS = {"leaf", "gauge", "cast", "gamepad", "zap"};
    private static final int[] COLORS = {0xFF34C3A5, 0xFF5B9DFF, 0xFFA78BFA, 0xFF5B9DFF,
        0xFFF06B5E};
    private static ProfileAnimation sCurrent;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int mMode;
    private final String mNumbers;
    private final float mScale;
    private float mProgress;
    private WindowManager mWindowManager;

    private ProfileAnimation(Context context, int mode, String numbers, float scale) {
        super(context);
        mMode = mode;
        mNumbers = numbers;
        mScale = scale;
    }

    /** Shows the animation of the profile on the main screen (replaces the one on screen). */
    static void show(Context context, int mode) {
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                showNow(context, mode);
            } catch (RuntimeException e) {
                Log.w(TAG, "No profile animation", e);
            }
        });
    }

    private static void showNow(Context context, int mode) {
        if (sCurrent != null) sCurrent.dismiss();
        final int id = Screens.displayId(context, Screens.TOP);
        final Display display = context.getSystemService(DisplayManager.class).getDisplay(
                id == Display.INVALID_DISPLAY ? Display.DEFAULT_DISPLAY : id);
        if (display == null) return;
        final Context windowContext = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        final PerformanceController engine = PerformanceController.get();
        String numbers = "";
        if (engine != null) {
            final PerformanceProfiles.Profile p = engine.profiles.get(mode);
            numbers = String.format(Locale.US, "CPU %d / %d / %d MHz   ·   GPU %d MHz",
                    p.cpuKhz[0] / 1000, p.cpuKhz[3] / 1000, p.cpuKhz[7] / 1000,
                    p.gpuMaxHz / 1_000_000);
        }
        final float scale = windowContext.getResources().getDisplayMetrics().widthPixels / 1024f;
        final ProfileAnimation view = new ProfileAnimation(windowContext, mode, numbers, scale);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("PocketDS profile animation");
        lp.setFitInsetsTypes(0);
        view.mWindowManager = windowContext.getSystemService(WindowManager.class);
        view.mWindowManager.addView(view, lp);
        sCurrent = view;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.addUpdateListener(a -> {
            view.mProgress = (float) a.getAnimatedValue();
            view.invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                view.dismiss();
            }
        });
        animator.start();
    }

    private void dismiss() {
        try {
            if (mWindowManager != null && isAttachedToWindow()) mWindowManager.removeView(this);
        } catch (RuntimeException e) {
            // already gone
        }
        if (sCurrent == this) sCurrent = null;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float t = mProgress;
        final float fadeIn = clamp(t / 0.1f);
        final float fadeOut = 1f - clamp((t - 0.82f) / 0.18f);
        final float alpha = fadeIn * fadeOut;
        final int color = COLORS[Math.max(0, Math.min(4, mMode))];
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float s = mScale * 1.7f;

        // dimmed background
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(((int) (alpha * 0.72f * 255) << 24) | 0x05060C);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);

        // the card pops up
        final float pop = new OvershootInterpolator(2.2f).getInterpolation(clamp(t / 0.28f));
        final float k = 0.7f + 0.3f * pop;
        canvas.save();
        canvas.scale(k, k, cx, cy);
        final float w = 520 * s / 1.7f * 1.0f;
        final float h = 300 * s / 1.7f;
        mRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        mPaint.setColor(((int) (alpha * 255) << 24) | 0x171B22);
        canvas.drawRoundRect(mRect, 28 * s, 28 * s, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1, 2 * s));
        mPaint.setColor(((int) (alpha * 255) << 24) | (color & 0x00FFFFFF));
        canvas.drawRoundRect(mRect, 28 * s, 28 * s, mPaint);

        // icon in a tinted square
        final float box = 76 * s;
        final float bx = cx - box / 2;
        final float by = mRect.top + 26 * s;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(((int) (alpha * 0.2f * 255) << 24) | (color & 0x00FFFFFF));
        canvas.drawRoundRect(bx, by, bx + box, by + box, 22 * s, 22 * s, mPaint);
        final int iconId = getResources().getIdentifier("ic_" + ICONS[Math.max(0,
                Math.min(4, mMode))], "drawable", getContext().getPackageName());
        if (iconId != 0) {
            final Drawable icon = getContext().getDrawable(iconId).mutate();
            icon.setTint(color);
            icon.setAlpha((int) (alpha * 255));
            final float pad = 18 * s;
            icon.setBounds((int) (bx + pad), (int) (by + pad), (int) (bx + box - pad),
                    (int) (by + box - pad));
            icon.draw(canvas);
        }

        // name
        mPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setColor(((int) (alpha * 255) << 24) | 0xE9ECF2);
        mPaint.setTextSize(40 * s);
        canvas.drawText(PerformanceProfiles.NAMES[Math.max(0, Math.min(4, mMode))], cx,
                by + box + 52 * s, mPaint);

        // five bars light up one after the other up to the level of the profile
        final float barW = 56 * s;
        final float barH = 12 * s;
        final float gap = 10 * s;
        final float total = 5 * barW + 4 * gap;
        final float barY = by + box + 78 * s;
        for (int i = 0; i < 5; i++) {
            final float local = clamp((t - 0.18f - i * 0.07f) / 0.12f);
            final boolean lit = i <= mMode;
            final float x = cx - total / 2 + i * (barW + gap);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(((int) (alpha * 255) << 24) | 0x2A303B);
            canvas.drawRoundRect(x, barY, x + barW, barY + barH, barH / 2, barH / 2, mPaint);
            if (lit && local > 0) {
                mPaint.setColor(((int) (alpha * 255) << 24) | (color & 0x00FFFFFF));
                canvas.drawRoundRect(x, barY, x + barW * local, barY + barH, barH / 2, barH / 2,
                        mPaint);
            }
        }

        // numbers
        mPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mPaint.setTextSize(17 * s);
        mPaint.setColor(((int) (alpha * 255) << 24) | 0x98A2B3);
        canvas.drawText(mNumbers, cx, barY + barH + 34 * s, mPaint);
        canvas.restore();
    }
}
