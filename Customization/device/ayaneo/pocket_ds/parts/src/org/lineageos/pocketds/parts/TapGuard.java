/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.view.MotionEvent;

/**
 * Spots the false touches of the touch screen. The bottom panel sometimes reports a contact of 20
 * to 30 ms with nobody touching it (seen with a fast charger plugged in). A real tap lasts 70 ms
 * or more, so a touch shorter than MIN_TAP_MS is cancelled before it can click anything.
 */
final class TapGuard {
    /** Shorter than any tap of a finger, longer than the false contacts measured (24 and 27 ms). */
    static final long MIN_TAP_MS = 40;

    private long mDownTime;

    /** True when this event ends a touch too short to be a finger. */
    boolean isGhostUp(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDownTime = event.getEventTime();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            return event.getEventTime() - mDownTime < MIN_TAP_MS;
        }
        return false;
    }

    /** A copy of the event as a cancel; the caller recycles it. */
    static MotionEvent cancelOf(MotionEvent event) {
        final MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        return cancel;
    }
}
