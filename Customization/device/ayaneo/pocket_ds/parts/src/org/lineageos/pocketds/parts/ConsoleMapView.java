/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A drawing of the Pocket DS: the front, and the top edge with the shoulder buttons. The buttons that
 * can be remapped are tappable; the ones the system keeps for itself are drawn but fixed. Drawn by
 * hand (not a photo of the maker): coordinates are those of a 1350-unit wide sheet.
 */
final class ConsoleMapView extends View {
    private static final float W = 1350f;
    private static final float FRONT_H = 800f;
    private static final float TOP_Y = 860f;
    private static final float TOP_H = 300f;
    private static final float H = TOP_Y + TOP_H;

    /** One button of the drawing; scan 0 = kept by the system. */
    static final class Region {
        final String name;
        final int scan;
        final boolean round;
        final float x, y, w, h;
        final String note;

        Region(String name, int scan, boolean round, float x, float y, float w, float h,
                String note) {
            this.name = name;
            this.scan = scan;
            this.round = round;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.note = note;
        }

        boolean fixed() {
            return scan == 0;
        }
    }

    interface Listener {
        void onPick(Region region);
    }

    static final List<Region> REGIONS = new ArrayList<>();
    static {
        // front: the centre and the radius (w) of a round button, the corner and the size of a flat one
        REGIONS.add(new Region("Y", 308, true, 1220, 185, 30, 0, null));
        REGIONS.add(new Region("X", 307, true, 1163, 245, 30, 0, null));
        REGIONS.add(new Region("B", 305, true, 1280, 245, 30, 0, null));
        REGIONS.add(new Region("A", 304, true, 1220, 305, 30, 0, null));
        REGIONS.add(new Region("Left stick (press)", 317, true, 122, 245, 52, 0, null));
        REGIONS.add(new Region("Right stick (press)", 318, true, 1222, 462, 52, 0, null));
        REGIONS.add(new Region("Select", 314, true, 108, 672, 27, 0, null));
        REGIONS.add(new Region("Start", 315, true, 167, 626, 27, 0, null));
        REGIONS.add(new Region("D-pad", 0, true, 125, 458, 62, 0,
                "an axis, not a button: it cannot be remapped here"));
        REGIONS.add(new Region("AYA / M", 0, true, 1177, 626, 27, 0, "overlay"));
        REGIONS.add(new Region("Home", 0, true, 1237, 672, 27, 0, "home of the bottom screen"));
        REGIONS.add(new Region("Swap", 0, false, 300, 733, 66, 34, "swap the two screens"));
        REGIONS.add(new Region("FPS", 0, false, 366, 733, 66, 34, "FPS and statistics bar"));
        REGIONS.add(new Region("Profile", 0, false, 500, 733, 72, 34, "next performance profile"));
        REGIONS.add(new Region("Volume", 0, false, 912, 733, 136, 34, "volume"));
        // top edge (y counts from TOP_Y)
        REGIONS.add(new Region("L1", 310, false, 70, TOP_Y + 50, 170, 64, null));
        REGIONS.add(new Region("L2", 312, false, 86, TOP_Y + 120, 140, 62, null));
        REGIONS.add(new Region("LC", 259, false, 262, TOP_Y + 124, 76, 40, null));
        REGIONS.add(new Region("R1", 311, false, 1110, TOP_Y + 50, 170, 64, null));
        REGIONS.add(new Region("R2", 313, false, 1124, TOP_Y + 120, 140, 62, null));
        REGIONS.add(new Region("RC", 260, false, 1012, TOP_Y + 124, 76, 40, null));
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();
    private final int mAccent;
    private SparseIntArray mMap = new SparseIntArray();
    private int mSelected = -1;
    private Listener mListener;
    private float mScale = 1f;

    ConsoleMapView(Context context, int accent) {
        super(context);
        mAccent = accent;
    }

    void setListener(Listener listener) {
        mListener = listener;
    }

    /** scan code -> key code of the current remapping. */
    void setMap(SparseIntArray map) {
        mMap = map;
        invalidate();
    }

    void setSelected(int scan) {
        mSelected = scan;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * H / W));
    }

    private String targetName(int code) {
        for (int i = 0; i < ControllerProfiles.TARGET_CODES.length; i++) {
            if (ControllerProfiles.TARGET_CODES[i] == code) return ControllerProfiles.TARGET_NAMES[i];
        }
        return "Key " + code;
    }

    @Override
    protected void onDraw(Canvas c) {
        mScale = getWidth() / W;
        c.save();
        c.scale(mScale, mScale);
        drawFront(c);
        drawTop(c);
        for (Region r : REGIONS) drawRegion(c, r);
        c.restore();
    }

    private void fill(Canvas c, int color) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
    }

    private void body(Canvas c, float x, float y, float w, float h, float r, int color) {
        mRect.set(x, y, x + w, y + h);
        fill(c, color);
        c.drawRoundRect(mRect, r, r, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(4);
        mPaint.setColor(0xFF8F95A1);
        c.drawRoundRect(mRect, r, r, mPaint);
    }

    private void ring(Canvas c, float cx, float cy, float r) {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(6);
        mPaint.setColor(0xFF8F95A1);
        c.drawCircle(cx, cy, r, mPaint);
    }

    private void drawFront(Canvas c) {
        mRect.set(230, 0, 1120, 70);
        fill(c, 0xFF8F95A1);
        c.drawRoundRect(mRect, 14, 14, mPaint);
        body(c, 6, 40, 1338, 745, 70, 0xFFB9BDC6);
        mRect.set(232, 98, 1116, 716);
        fill(c, 0xFF14161C);
        c.drawRoundRect(mRect, 10, 10, mPaint);
        ring(c, 122, 245, 92);
        ring(c, 125, 458, 92);
        ring(c, 1222, 245, 95);
        ring(c, 1222, 462, 92);
        fill(c, 0xFF23262D);
        c.drawCircle(122, 245, 48, mPaint);
        c.drawCircle(1222, 462, 48, mPaint);
        mPath.reset();
        mPath.moveTo(95, 400);
        mPath.rLineTo(60, 0);
        mPath.rLineTo(0, 40);
        mPath.rLineTo(40, 0);
        mPath.rLineTo(0, 40);
        mPath.rLineTo(-40, 0);
        mPath.rLineTo(0, 40);
        mPath.rLineTo(-60, 0);
        mPath.rLineTo(0, -40);
        mPath.rLineTo(-40, 0);
        mPath.rLineTo(0, -40);
        mPath.rLineTo(40, 0);
        mPath.close();
        c.drawPath(mPath, mPaint);
        mRect.set(775, 736, 869, 766);
        c.drawRoundRect(mRect, 15, 15, mPaint);
        c.drawCircle(683, 750, 6, mPaint);
        fill(c, 0xFFFFFFFF);
        c.drawCircle(722, 750, 8, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(5);
        mPaint.setColor(0xFF23262D);
        for (int i = 0; i < 5; i++) {
            c.drawLine(142 + i * 16, 722, 142 + i * 16, 754, mPaint);
            c.drawLine(1138 + i * 16, 722, 1138 + i * 16, 754, mPaint);
        }
    }

    private void drawTop(Canvas c) {
        mRect.set(470, TOP_Y + 58, 880, TOP_Y + 122);
        fill(c, 0xFF8F95A1);
        c.drawRoundRect(mRect, 14, 14, mPaint);
        body(c, 40, TOP_Y + 110, 1270, 150, 44, 0xFFB9BDC6);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFF6B7387);
        mPaint.setTextSize(30);
        mPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("TOP EDGE", 675, TOP_Y + 235, mPaint);
        c.drawText("FRONT", 675, 830, mPaint);
        mPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawRegion(Canvas c, Region r) {
        final boolean selected = r.scan != 0 && r.scan == mSelected;
        final boolean remapped = r.scan != 0 && mMap.indexOfKey(r.scan) >= 0;
        final int base = r.fixed() ? 0xFFD3D6DC : 0xFFF3F4F7;
        float cx;
        float cy;
        if (r.round) {
            cx = r.x;
            cy = r.y;
        } else {
            cx = r.x + r.w / 2;
            cy = r.y + r.h / 2;
        }
        // the face buttons keep their red, the others a light cap; fixed buttons are dimmed
        final boolean face = r.scan == 304 || r.scan == 305 || r.scan == 307 || r.scan == 308;
        if (r.round && (r.scan == 317 || r.scan == 318)) {
            fill(c, 0x00000000);
        } else if (r.round && r.scan != 0 || r.round) {
            if (r.name.equals("D-pad")) {
                // drawn with the front
            } else {
                fill(c, face ? 0xFFB94A42 : base);
                c.drawCircle(cx, cy, r.w, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(3);
                mPaint.setColor(0xFF8F95A1);
                c.drawCircle(cx, cy, r.w, mPaint);
            }
        } else {
            mRect.set(r.x, r.y, r.x + r.w, r.y + r.h);
            fill(c, base);
            c.drawRoundRect(mRect, r.h / 2, r.h / 2, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(3);
            mPaint.setColor(0xFF8F95A1);
            c.drawRoundRect(mRect, r.h / 2, r.h / 2, mPaint);
        }
        if (face) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFFFFFFFF);
            mPaint.setTextSize(26);
            mPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(r.name, cx, cy + 9, mPaint);
            mPaint.setTextAlign(Paint.Align.LEFT);
        }
        final String tag = shortName(r);
        if (!face && tag != null) {
            final boolean onDark = r.scan == 317 || r.scan == 318 || r.name.equals("D-pad");
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(onDark ? 0xFFD9DCE3 : 0xFF3B4150);
            mPaint.setTextSize(r.round && r.w < 40 ? 17 : 24);
            mPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(tag, cx, cy + (r.round && r.w < 40 ? 6 : 8), mPaint);
            mPaint.setTextAlign(Paint.Align.LEFT);
        }
        if (r.fixed() && tag == null) {
            // a small lock: this button belongs to the system
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFF6B7387);
            c.drawCircle(cx, cy, Math.min(10, (r.round ? r.w : r.h) / 3), mPaint);
        }
        if (selected || remapped) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(selected ? 9 : 6);
            mPaint.setColor(selected ? mAccent : 0xFF43C27A);
            if (r.round) {
                c.drawCircle(cx, cy, r.w + 10, mPaint);
            } else {
                mRect.set(r.x - 8, r.y - 8, r.x + r.w + 8, r.y + r.h + 8);
                c.drawRoundRect(mRect, (r.h + 16) / 2, (r.h + 16) / 2, mPaint);
            }
        }
        if (remapped) {
            // "A -> B" under or beside the button
            final String label = "→ " + targetName(mMap.get(r.scan));
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setTextSize(30);
            final float width = mPaint.measureText(label) + 24;
            final float top = (r.round ? cy + r.w + 18 : r.y + r.h + 16);
            final float left = Math.max(4, Math.min(W - width - 4, cx - width / 2));
            mRect.set(left, top, left + width, top + 42);
            mPaint.setColor(0xFF1E2A22);
            c.drawRoundRect(mRect, 21, 21, mPaint);
            mPaint.setColor(0xFF7FE0A5);
            mPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText(label, left + 12, top + 31, mPaint);
        }
    }

    /** What is written on the button in the drawing. */
    private static String shortName(Region r) {
        switch (r.name) {
            case "Select": return "SEL";
            case "Start": return "STA";
            case "AYA / M": return "M";
            case "Home": return "HOME";
            case "Swap": return "SWAP";
            case "FPS": return "FPS";
            case "Profile": return "PROF";
            case "Volume": return "VOL  −  +";
            case "Left stick (press)": return "L3";
            case "Right stick (press)": return "R3";
            case "D-pad": return "D-pad";
            default: return r.round ? null : r.name;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        final float x = event.getX() / mScale;
        final float y = event.getY() / mScale;
        Region best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Region r : REGIONS) {
            final float cx = r.round ? r.x : r.x + r.w / 2;
            final float cy = r.round ? r.y : r.y + r.h / 2;
            final boolean hit = r.round
                    ? Math.hypot(x - cx, y - cy) <= r.w + 18
                    : x >= r.x - 14 && x <= r.x + r.w + 14 && y >= r.y - 14 && y <= r.y + r.h + 14;
            if (!hit) continue;
            final float distance = (float) Math.hypot(x - cx, y - cy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = r;
            }
        }
        if (best != null && mListener != null) mListener.onPick(best);
        return true;
    }
}
