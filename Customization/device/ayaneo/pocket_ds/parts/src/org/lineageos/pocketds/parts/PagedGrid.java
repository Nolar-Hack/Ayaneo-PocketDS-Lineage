/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Icons in pages of {@code columns} x {@code rows}: swipe sideways to change page, dots show
 * where one is. A cell is an app or a folder (four small icons in a square).
 */
final class PagedGrid extends FrameLayout {
    /** One cell. */
    static final class Item {
        String label;
        Drawable icon;
        List<Drawable> folderIcons;      // not null: a folder
        Runnable click;
        Runnable longClick;
    }

    private final float mScale;
    private final int mSlop;
    private final FrameLayout mStrip;
    private final LinearLayout mDots;
    private List<Item> mItems = java.util.Collections.emptyList();
    private int mColumns = 5;
    private int mRows = 3;
    private int mPage;
    private int mPages = 1;
    private float mDownX;
    private float mDownY;
    private boolean mDragging;
    private VelocityTracker mVelocity;

    PagedGrid(Context context, float scale) {
        super(context);
        mScale = scale;
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mStrip = new FrameLayout(context);
        addView(mStrip, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mDots = new LinearLayout(context);
        mDots.setGravity(Gravity.CENTER);
        addView(mDots, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(22 * scale), Gravity.BOTTOM));
    }

    void setItems(List<Item> items, int columns, int rows) {
        mItems = items;
        mColumns = Math.max(2, columns);
        mRows = Math.max(1, rows);
        rebuild();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) rebuild();
    }

    private void rebuild() {
        final int width = getWidth();
        final int height = getHeight() - Math.round(26 * mScale);
        mStrip.removeAllViews();
        mDots.removeAllViews();
        if (width <= 0 || height <= 0) return;
        final int perPage = mColumns * mRows;
        mPages = Math.max(1, (mItems.size() + perPage - 1) / perPage);
        mPage = Math.min(mPage, mPages - 1);
        final int cellW = width / mColumns;
        final int cellH = height / mRows;
        final int iconSize = Math.round(Math.min(cellW * 0.62f, cellH * 0.56f));
        for (int i = 0; i < mItems.size(); i++) {
            final Item item = mItems.get(i);
            final int page = i / perPage;
            final int slot = i % perPage;
            final View cell = cell(item, cellW, cellH, iconSize);
            final LayoutParams params = new LayoutParams(cellW, cellH);
            params.leftMargin = page * width + (slot % mColumns) * cellW;
            params.topMargin = (slot / mColumns) * cellH;
            mStrip.addView(cell, params);
        }
        mStrip.setTranslationX(-mPage * width);
        if (mPages > 1) {
            for (int p = 0; p < mPages; p++) {
                final View dot = new View(getContext());
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        Math.round(9 * mScale), Math.round(9 * mScale));
                params.leftMargin = params.rightMargin = Math.round(5 * mScale);
                mDots.addView(dot, params);
            }
            paintDots();
        }
    }

    private void paintDots() {
        for (int p = 0; p < mDots.getChildCount(); p++) {
            final GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(p == mPage ? 0xFF5B9DFF : 0xFF3A4252);
            mDots.getChildAt(p).setBackground(dot);
        }
    }

    private View cell(Item item, int cellW, int cellH, int iconSize) {
        final LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, Math.round(cellH * 0.08f), 0, 0);
        final View icon;
        if (item.folderIcons != null) {
            icon = folderIcon(item, iconSize);
        } else {
            final ImageView image = new ImageView(getContext());
            image.setImageDrawable(item.icon);
            icon = image;
        }
        cell.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        final TextView label = new TextView(getContext());
        label.setText(item.label);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(13 * mScale * 1.22f,
                Math.min(20 * mScale * 1.22f, iconSize * 0.2f)));
        label.setPadding(Math.round(4 * mScale), Math.round(8 * mScale), Math.round(4 * mScale),
                0);
        cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(v -> {
            if (item.click != null) item.click.run();
        });
        cell.setOnLongClickListener(v -> {
            if (item.longClick == null) return false;
            item.longClick.run();
            return true;
        });
        return cell;
    }

    /** A folder: a rounded square with up to four of its icons. */
    private View folderIcon(Item item, int size) {
        final FrameLayout box = new FrameLayout(getContext());
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF232832);
        shape.setCornerRadius(size * 0.28f);
        shape.setStroke(Math.max(1, Math.round(mScale)), 0xFF3A4252);
        box.setBackground(shape);
        final int pad = Math.round(size * 0.12f);
        final int mini = (size - pad * 3) / 2;
        for (int i = 0; i < Math.min(4, item.folderIcons.size()); i++) {
            final ImageView image = new ImageView(getContext());
            image.setImageDrawable(item.folderIcons.get(i));
            final LayoutParams params = new LayoutParams(mini, mini);
            params.leftMargin = pad + (i % 2) * (mini + pad);
            params.topMargin = pad + (i / 2) * (mini + pad);
            box.addView(image, params);
        }
        return box;
    }

    // ---- paging

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mDragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mPages > 1 && Math.abs(event.getX() - mDownX) > mSlop
                        && Math.abs(event.getX() - mDownX) > Math.abs(event.getY() - mDownY)) {
                    mDragging = true;
                    startTracking(event);
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    private void startTracking(MotionEvent event) {
        if (mVelocity == null) mVelocity = VelocityTracker.obtain();
        mVelocity.addMovement(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mDragging && event.getActionMasked() == MotionEvent.ACTION_DOWN) return mPages > 1;
        if (mVelocity == null) mVelocity = VelocityTracker.obtain();
        mVelocity.addMovement(event);
        final int width = getWidth();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                mStrip.setTranslationX(-mPage * width + (event.getX() - mDownX));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mVelocity.computeCurrentVelocity(1000);
                final float dx = event.getX() - mDownX;
                final float velocity = mVelocity.getXVelocity();
                mVelocity.recycle();
                mVelocity = null;
                if ((dx < -width / 5f || velocity < -900) && mPage < mPages - 1) mPage++;
                else if ((dx > width / 5f || velocity > 900) && mPage > 0) mPage--;
                mStrip.animate().translationX(-mPage * width).setDuration(180).start();
                paintDots();
                mDragging = false;
                return true;
            default:
                return true;
        }
    }
}
