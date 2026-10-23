/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Controller test and calibration: the drawing of the console lights each button while it is held,
 * the sticks and triggers move live, and the calibration (sticks, triggers, then a button
 * combination) is done here. The overlay only has a shortcut to this page.
 */
public class ControllerTestActivity extends Activity {
    static final String EXTRA_CALIBRATE = "calibrate";
    /** While on, the system lets the buttons of the console (M, Home, Swap, FPS, Profile) through. */
    /** The display of the page while it is in front, else -1: the controller is given to it. */
    static volatile int sDisplay = -1;
    static final String PROP_BUTTONS_TEST = "persist.sys.pocketds.buttons_test";

    private static final int SCAN_L1 = 310;   // BTN_TL
    private static final int SCAN_R1 = 311;   // BTN_TR
    private static final int SCAN_B = 305;    // BTN_EAST

    private Ui mUi;
    private ConsoleMapView mConsole;
    private TextView mLast;
    private StickPad mLeftPad;
    private StickPad mRightPad;
    private TextView mLeftText;
    private TextView mRightText;
    private TextView mTriggerText;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    // calibration
    private TextView mCalStatus;
    private TextView mCalButton;
    private int mCalStep;                       // 0 idle, 1 sticks, 2 triggers, 3 buttons, 4 done
    private final double[] mCalAngle = new double[2];
    private final double[] mCalTurn = new double[2];
    private boolean mCalLeftTrigger;
    private boolean mCalRightTrigger;
    private boolean mCalHatLeft;
    private long mCalHoldSince;
    private final Set<Integer> mCalKeys = new HashSet<>();

    /** A round stick pad: crosshair, the dead zone of the controller (dotted), the live point. */
    private final class StickPad extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mX;
        private float mY;

        StickPad(Context context) {
            super(context);
        }

        void set(float x, float y) {
            mX = x;
            mY = y;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float radius = Math.min(cx, cy) - mUi.dp(2);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFF14181F);
            canvas.drawCircle(cx, cy, radius, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1, mUi.dp(1.5f)));
            mPaint.setColor(0xFF3A4252);
            canvas.drawCircle(cx, cy, radius, mPaint);
            mPaint.setStrokeWidth(Math.max(1, mUi.dp(1)));
            mPaint.setColor(0xFF2A303B);
            canvas.drawLine(cx - radius, cy, cx + radius, cy, mPaint);
            canvas.drawLine(cx, cy - radius, cx, cy + radius, mPaint);
            mPaint.setPathEffect(new DashPathEffect(new float[] {mUi.dp(3), mUi.dp(4)}, 0));
            mPaint.setColor(0xFF4A5366);
            canvas.drawCircle(cx, cy, radius * 0.125f, mPaint);
            mPaint.setPathEffect(null);
            mPaint.setStyle(Paint.Style.FILL);
            final float px = cx + mX * (radius - mUi.dp(9));
            final float py = cy + mY * (radius - mUi.dp(9));
            mPaint.setColor((Ui.sAccent & 0x00FFFFFF) | 0x40000000);
            canvas.drawCircle(px, py, mUi.dp(13), mPaint);
            mPaint.setColor(Ui.sAccent);
            canvas.drawCircle(px, py, mUi.dp(8), mPaint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUi = new Ui(this);
        setTitle("Test and calibration");
        final LinearLayout page = mUi.page(this);
        mUi.hero(page, "🎯", Ui.ORANGE, "Test and calibration",
                "Press a button or move a stick: it lights up on the console");

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // The console on the left, the sticks and triggers beside it: all in view at once.
        final LinearLayout card = mUi.card(row);
        row.removeView(card);
        row.addView(card, columnParams(1.35f, 0));
        mConsole = new ConsoleMapView(this, Ui.sAccent);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(mUi.dp(8), mUi.dp(8), mUi.dp(8), mUi.dp(4));
        card.addView(mConsole, params);
        mLast = mUi.label("Waiting for a button…", 14, mUi.textDim, false);
        mLast.setPadding(mUi.dp(14), 0, mUi.dp(14), mUi.dp(12));
        card.addView(mLast);

        final LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        row.addView(side, columnParams(1f, mUi.dp(10)));
        final LinearLayout sticks = mUi.card(side);
        final LinearLayout pads = new LinearLayout(this);
        pads.setOrientation(LinearLayout.VERTICAL);
        pads.setPadding(mUi.dp(10), mUi.dp(12), mUi.dp(10), mUi.dp(12));
        mLeftPad = new StickPad(this);
        mRightPad = new StickPad(this);
        mLeftText = mUi.label("x +0.00  ·  y +0.00", 14, mUi.text, true);
        mRightText = mUi.label("x +0.00  ·  y +0.00", 14, mUi.text, true);
        for (int i = 0; i < 2; i++) {
            final LinearLayout line = new LinearLayout(this);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.addView(i == 0 ? mLeftPad : mRightPad,
                    new LinearLayout.LayoutParams(mUi.dp(110), mUi.dp(110)));
            final LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(mUi.dp(12), 0, 0, 0);
            texts.addView(mUi.label(i == 0 ? "Left stick" : "Right stick", 14, mUi.textDim,
                    false));
            texts.addView(i == 0 ? mLeftText : mRightText);
            line.addView(texts);
            pads.addView(line);
        }
        sticks.addView(pads);
        mTriggerText = mUi.label("L2 0 %   ·   R2 0 %", 15, mUi.text, true);
        mTriggerText.setGravity(android.view.Gravity.CENTER);
        mTriggerText.setPadding(0, 0, 0, mUi.dp(14));
        sticks.addView(mTriggerText);
        mUi.note(page, "The dotted circle is the dead zone the controller itself ignores (12 %). "
                + "While this page is open, M, Home, Swap, FPS and Profile only light up here: "
                + "they do not open the overlay or swap the screens.");

        mUi.section(page, "Calibration");
        final LinearLayout cal = mUi.card(page);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mUi.dp(18), mUi.dp(14), mUi.dp(18), mUi.dp(16));
        cal.addView(box);
        mCalStatus = mUi.label("", 15, mUi.text, false);
        box.addView(mCalStatus);
        mCalButton = mUi.pill("Start calibration", Ui.sAccent, true, v -> {
            if (mCalStep == 0 || mCalStep == 4) {
                calibrationStart();
            } else {
                mCalStep = 0;
                mMain.removeCallbacks(mCalHoldCheck);
                calibrationShow();
            }
        });
        final LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = mUi.dp(14);
        box.addView(mCalButton, bp);
        calibrationShow();
        if (getIntent().getBooleanExtra(EXTRA_CALIBRATE, false)) calibrationStart();
    }

    private LinearLayout.LayoutParams columnParams(float weight, int leftMargin) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.leftMargin = leftMargin;
        return params;
    }

    // ---- live input

    private static boolean fromConsole(InputDevice device) {
        return device != null && device.getName().startsWith("AYANEO");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final int scan = event.getScanCode();
        if (fromConsole(event.getDevice()) && scan != 0) {
            final boolean on = down || event.getAction() == KeyEvent.ACTION_MULTIPLE;
            boolean known = mConsole.setPressedScan(scan, on);
            String shortcut = null;
            if (!known) {
                // The buttons the system keeps otherwise: told by their key code.
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_BUTTON_3: shortcut = "Swap"; break;
                    case KeyEvent.KEYCODE_BUTTON_6: shortcut = "AYA / M"; break;
                    case KeyEvent.KEYCODE_BUTTON_7: shortcut = "Home"; break;
                    case KeyEvent.KEYCODE_BUTTON_8: shortcut = "Profile"; break;
                    case KeyEvent.KEYCODE_BUTTON_MODE: shortcut = "FPS"; break;
                    default: break;
                }
                if (shortcut != null) {
                    mConsole.setPressed(shortcut, on);
                    known = true;
                }
            }
            if (down && event.getRepeatCount() == 0) {
                mLast.setText(String.format(Locale.US, "Last: %s  ·  scan code %d  ·  sends %s",
                        shortcut != null ? shortcut : known ? nameOf(scan) : "unknown button", scan,
                        KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "")));
            }
            if (mCalStep == 3 && (scan == SCAN_L1 || scan == SCAN_R1 || scan == SCAN_B)) {
                if (down) mCalKeys.add(scan);
                else mCalKeys.remove(scan);
                calibrationCheckHold();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static String nameOf(int scan) {
        for (ConsoleMapView.Region r : ConsoleMapView.REGIONS) if (r.scan == scan) return r.name;
        return "Button";
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == 0) {
            return super.onGenericMotionEvent(event);
        }
        final float lx = event.getAxisValue(MotionEvent.AXIS_X);
        final float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        final float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        final float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        final float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        final float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        final float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        final float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        mConsole.setSticks(lx, ly, rx, ry);
        mLeftPad.set(lx, ly);
        mRightPad.set(rx, ry);
        mLeftText.setText(String.format(Locale.US, "x %+.2f  ·  y %+.2f", lx, ly));
        mRightText.setText(String.format(Locale.US, "x %+.2f  ·  y %+.2f", rx, ry));
        mTriggerText.setText(String.format(Locale.US, "L2 %d %%   ·   R2 %d %%",
                Math.round(lt * 100), Math.round(rt * 100)));
        mConsole.setPressed("L2", lt > 0.1f);
        mConsole.setPressed("R2", rt > 0.1f);
        mConsole.setDpad(hx, hy);
        if (hx != 0 || hy != 0) {
            mLast.setText("Last: D-pad " + (hy < 0 ? "up" : hy > 0 ? "down" : "")
                    + (hx < 0 ? " left" : hx > 0 ? " right" : ""));
        }
        mCalHatLeft = hx < -0.5f;
        calibrationFeed(lx, ly, rx, ry, lt, rt);
        calibrationCheckHold();
        return true;
    }

    private static void setButtonsTest(boolean on) {
        try {
            SystemProperties.set(PROP_BUTTONS_TEST, on ? "true" : "false");
        } catch (RuntimeException e) {
            // not allowed for this caller: the buttons keep their job
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The buttons of the console reach this page, they do not open the overlay or swap.
        setButtonsTest(true);
        sDisplay = getDisplay() == null ? -1 : getDisplay().getDisplayId();
        takeController();
    }

    /** The lock of the controller follows this page while it is open (as for the overlay). */
    private void takeController() {
        try {
            Screens.applyFocusLock(this, true);
        } catch (RuntimeException e) {
            // not allowed for this caller: the lock stays as it is
        }
    }

    @Override
    protected void onDestroy() {
        setButtonsTest(false);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        setButtonsTest(false);
        sDisplay = -1;
        takeController();
        mConsole.clearPressed();
        mMain.removeCallbacks(mCalHoldCheck);
    }

    // ---- calibration

    private void calibrationStart() {
        mCalStep = 1;
        mCalAngle[0] = mCalAngle[1] = Double.NaN;
        mCalTurn[0] = mCalTurn[1] = 0;
        mCalLeftTrigger = mCalRightTrigger = false;
        mCalHoldSince = 0;
        mCalKeys.clear();
        calibrationShow();
    }

    private void calibrationShow() {
        final int left = (int) Math.round(Math.min(100, mCalTurn[0] / (4 * Math.PI) * 100));
        final int right = (int) Math.round(Math.min(100, mCalTurn[1] / (4 * Math.PI) * 100));
        switch (mCalStep) {
            case 1:
                mCalStatus.setText("1 / 3   Rotate both sticks two full turns, any direction.\n"
                        + "Left " + left + " %   ·   Right " + right + " %");
                mCalButton.setText("Cancel");
                break;
            case 2:
                mCalStatus.setText("2 / 3   Press both triggers all the way down.\n"
                        + "L2 " + (mCalLeftTrigger ? "done" : "…") + "   ·   R2 "
                        + (mCalRightTrigger ? "done" : "…"));
                mCalButton.setText("Cancel");
                break;
            case 3:
                mCalStatus.setText("3 / 3   Hold L1, R1, D-pad left and B together for 3 seconds.");
                mCalButton.setText("Cancel");
                break;
            case 4:
                mCalStatus.setText("Calibration done. The controller keeps it by itself.");
                mCalButton.setText("Calibrate again");
                break;
            default:
                mCalStatus.setText("Rotate the sticks, press the triggers, then hold a button "
                        + "combination: the controller learns its range by itself.");
                mCalButton.setText("Start calibration");
        }
    }

    private void calibrationFeed(float lx, float ly, float rx, float ry, float lt, float rt) {
        if (mCalStep == 1) {
            final float[][] sticks = {{lx, ly}, {rx, ry}};
            for (int i = 0; i < 2; i++) {
                if (Math.hypot(sticks[i][0], sticks[i][1]) < 0.6) continue;
                final double angle = Math.atan2(sticks[i][1], sticks[i][0]);
                if (!Double.isNaN(mCalAngle[i])) {
                    double delta = angle - mCalAngle[i];
                    while (delta > Math.PI) delta -= 2 * Math.PI;
                    while (delta < -Math.PI) delta += 2 * Math.PI;
                    mCalTurn[i] += Math.abs(delta);
                }
                mCalAngle[i] = angle;
            }
            if (mCalTurn[0] >= 4 * Math.PI && mCalTurn[1] >= 4 * Math.PI) mCalStep = 2;
            calibrationShow();
        } else if (mCalStep == 2) {
            if (lt > 0.95f) mCalLeftTrigger = true;
            if (rt > 0.95f) mCalRightTrigger = true;
            if (mCalLeftTrigger && mCalRightTrigger) mCalStep = 3;
            calibrationShow();
        }
    }

    /** Step 3: L1, R1, B and D-pad left together, for 3 seconds. */
    private void calibrationCheckHold() {
        if (mCalStep != 3) return;
        if (mCalKeys.size() == 3 && mCalHatLeft) {
            if (mCalHoldSince == 0) {
                mCalHoldSince = System.currentTimeMillis();
                mMain.postDelayed(mCalHoldCheck, 3100);
                mCalStatus.setText("3 / 3   Keep holding L1, R1, D-pad left and B…");
            }
        } else if (mCalHoldSince != 0) {
            mCalHoldSince = 0;
            mMain.removeCallbacks(mCalHoldCheck);
            calibrationShow();
        }
    }

    private final Runnable mCalHoldCheck = new Runnable() {
        @Override
        public void run() {
            if (mCalStep == 3 && mCalHoldSince != 0
                    && System.currentTimeMillis() - mCalHoldSince >= 3000) {
                mCalStep = 4;
                calibrationShow();
            }
        }
    };
}
