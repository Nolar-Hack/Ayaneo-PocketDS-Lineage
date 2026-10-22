/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import java.util.Locale;

/** A line chart of the overlay: a grid, the curve filled under it, the scale on the left. */
final class LineChart extends View {
    private static final int LINE = 0xFF2A303B;
    private static final int DIM = 0xFF98A2B3;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private float[] mValues = new float[0];
    private float mFixedMax;
    private int mColor = 0xFF5B9DFF;
    private String mUnit = "";
    private final float mScale;

    LineChart(Context context, float scale) {
        super(context);
        mScale = scale;
    }

    /** @param fixedMax top of the scale, or 0 to take the highest sample */
    void set(float[] values, int color, String unit, float fixedMax) {
        mValues = values;
        mColor = color;
        mUnit = unit;
        mFixedMax = fixedMax;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float left = 64 * mScale;
        final float top = 12 * mScale;
        final float right = getWidth() - 10 * mScale;
        final float bottom = getHeight() - 10 * mScale;
        float max = mFixedMax;
        if (max <= 0) {
            for (float v : mValues) max = Math.max(max, v);
            max = niceCeil(Math.max(max, 1f));
        }
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1, mScale));
        mPaint.setTextSize(13 * mScale);
        for (int i = 0; i <= 4; i++) {
            final float y = bottom - (bottom - top) * i / 4f;
            mPaint.setColor(LINE);
            mPaint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(left, y, right, y, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(DIM);
            mPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(format(max * i / 4f), left - 8 * mScale, y + 5 * mScale, mPaint);
        }
        if (mValues.length < 2) return;
        mPath.reset();
        for (int i = 0; i < mValues.length; i++) {
            final float x = left + (right - left) * i / (mValues.length - 1);
            final float y = bottom - (bottom - top) * Math.min(1f, mValues[i] / max);
            if (i == 0) mPath.moveTo(x, y); else mPath.lineTo(x, y);
        }
        final Path fill = new Path(mPath);
        fill.lineTo(right, bottom);
        fill.lineTo(left, bottom);
        fill.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setShader(new LinearGradient(0, top, 0, bottom, (mColor & 0x00FFFFFF) | 0x59000000,
                (mColor & 0x00FFFFFF) | 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawPath(fill, mPaint);
        mPaint.setShader(null);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2.5f * mScale);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setColor(mColor);
        canvas.drawPath(mPath, mPaint);
    }

    private static float niceCeil(float value) {
        final float[] steps = {1, 2, 5, 10, 20, 25, 50, 100, 200, 500};
        for (float step : steps) if (value <= step) return step;
        return (float) (Math.ceil(value / 100) * 100);
    }

    private String format(float value) {
        return (value >= 10 || value == Math.floor(value)
                ? String.format(Locale.US, "%.0f", value) : String.format(Locale.US, "%.1f", value))
                + mUnit;
    }
}
