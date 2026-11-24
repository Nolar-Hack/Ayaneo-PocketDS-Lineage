/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Dialog;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

/**
 * A dialog that does not close or click on a touch it cannot have been meant for: one shorter than
 * a finger (see {@link TapGuard}) and any touch in the half second after it opened, when the
 * finger of the long press that opened it lands again or a palm rests on the screen. The touches
 * it ignores are logged (PocketDSParts) to see what they were.
 */
class TapGuardDialog extends Dialog {
    private static final String TAG = "PocketDSParts";
    /** Nobody picks a choice faster than this after the menu appeared. */
    private static final long SETTLE_MS = 500;

    private final TapGuard mGuard = new TapGuard();
    private long mShownAt;
    private boolean mIgnoring;

    TapGuardDialog(Context context, int theme) {
        super(context, theme);
    }

    @Override
    public void show() {
        mShownAt = SystemClock.uptimeMillis();
        super.show();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mIgnoring = event.getEventTime() - mShownAt < SETTLE_MS;
            if (mIgnoring) {
                Log.i(TAG, "Touch ignored " + (event.getEventTime() - mShownAt)
                        + " ms after a menu opened");
            }
        }
        if (mIgnoring) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mIgnoring = false;
            }
            return true;
        }
        if (mGuard.isGhostUp(event)) {
            Log.i(TAG, "Touch of " + (event.getEventTime() - event.getDownTime())
                    + " ms ignored in a menu");
            final MotionEvent cancel = TapGuard.cancelOf(event);
            final boolean handled = super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return handled;
        }
        return super.dispatchTouchEvent(event);
    }
}
