/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.view.Display;
import android.hardware.display.DisplayManager;
import android.graphics.PixelFormat;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The overlay of the console, opened by the AYA window button on the bottom screen: the
 * performance profiles, live numbers (frame rate, CPU, GPU, memory, fan, battery), the refresh rate
 * of the main screen, the brightness of both screens, the volume and the swap of the screens. It
 * replaces the AYA overlay; see mockups in the project notes (page "Performance").
 */
final class OverlayWindow extends android.content.ContextWrapper {
    static final String ACTION_TOGGLE = "org.lineageos.pocketds.parts.TOGGLE_OVERLAY";
    private static final long PERIOD_MS = 700;
    static OverlayWindow sInstance;

    private static int BG = 0xFF0D0F13;
    private static int CARD = 0xFF1A1E26;
    private static final int LINE = 0xFF2A303B;
    private static final int TEXT = 0xFFE9ECF2;
    private static final int DIM = 0xFF98A2B3;
    private static int ACCENT = 0xFF5B9DFF;
    private static final int ON_ACCENT = 0xFF07101F;

    private static final String[] REFRESH_VALUES = {"auto", "60", "90", "120", "144", "165"};
    private static final String[] REFRESH_NAMES = {"Auto", "60", "90", "120", "144", "165"};

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private HandlerThread mThread;
    private Handler mWorker;
    private float mScale = 1f;
    // The overlay is read on a 5 inch screen: the texts of the design are drawn this much bigger.
    private static final float TEXT_SCALE = 1.22f;

    private TextView mSummary;
    private final TextView[] mProfileButtons = new TextView[PerformanceProfiles.NAMES.length];
    private final LinearLayout[] mProfileTiles = new LinearLayout[PerformanceProfiles.NAMES.length];
    private final android.widget.ImageView[] mProfileIcons =
            new android.widget.ImageView[PerformanceProfiles.NAMES.length];
    private static final int[] PROFILE_COLORS = {0xFF34C3A5, 0xFF5B9DFF, 0xFFA78BFA, 0xFF5B9DFF,
        0xFFF06B5E};
    private LinearLayout mCpuBar;
    private LinearLayout mGpuBar;
    private LinearLayout mMemBar;
    private LinearLayout mBatteryBar;
    private Spark mSpark;
    private TextView mFpsValue;
    private TextView mFpsLine;
    private TextView mCpuValue;
    private TextView mCpuFreq;
    private TextView mCpuTemp;
    private TextView mGpuValue;
    private TextView mGpuFreq;
    private TextView mGpuTemp;
    private TextView mMemValue;
    private TextView mMemLine;
    private TextView mFanValue;
    private TextView mBatteryValue;
    private TextView mBatteryLine;
    private final List<TextView> mFanChips = new ArrayList<>();
    private final List<TextView> mRefreshChips = new ArrayList<>();

    private long mLastTotal;
    private long mLastIdle;
    private final List<String> mGpuZones = new ArrayList<>();

    private final WindowManager mWindowManager;
    private HostLayout mRoot;
    private int mDisplayId;

    /** The display the overlay is open on, or -1. */
    static int openDisplay() {
        return sInstance == null ? -1 : sInstance.mDisplayId;
    }

    private boolean mHasFocus;

    private OverlayWindow(Context windowContext) {
        super(windowContext);
        mWindowManager = windowContext.getSystemService(WindowManager.class);
    }

    /**
     * Opens the overlay on a display, or closes it when it is open. It is a window of the app (not
     * an activity): it stays above everything on that screen, a game and the second screen of an
     * emulator (a presentation) included, and the game goes on behind it.
     */
    static void toggle(Context context, int displayId) {
        if (sInstance != null) {
            sInstance.dismiss();
            return;
        }
        final Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) return;
        final Context windowContext = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        final OverlayWindow window = new OverlayWindow(windowContext);
        window.mDisplayId = displayId;
        window.create();
    }

    private void create() {
        sInstance = this;
        ACCENT = Accent.current(this);
        retint();
        mScale = Math.min(1f, getResources().getDisplayMetrics().widthPixels / 1024f);
        findGpuZones();
        mRoot = new HostLayout(this);
        mRoot.setBackgroundColor(BG);
        mRoot.addView(build(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.setTitle("PocketDS overlay");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowManager.addView(mRoot, lp);
        mRoot.requestFocus();
        // The controller goes to the overlay while it is open. The lock also gives each screen
        // its own input focus (without it only the screen touched last has one, and a window on
        // the other screen never gets it).
        Screens.applyFocusLock(getApplicationContext(), true);
        // Seen once, right after a boot: the window was added but never got the focus, the keys of
        // the controller went nowhere and the app below reported "not responding". If the window
        // has no focus shortly after, it is opened again.
        mMain.postDelayed(this::ensureFocus, 800);
        mThread = new HandlerThread("PocketDSOverlay");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
        mWorker.post(mSample);
    }

    /** Only a note in the log when the window has no input focus shortly after opening. */
    private void ensureFocus() {
        if (sInstance != this || mRoot == null || !mRoot.isAttachedToWindow()) return;
        if (!mRoot.hasWindowFocus()) {
            android.util.Log.w("PocketDSOverlay", "The overlay window has no input focus yet");
        }
    }

    /** Closes the overlay. */
    void dismiss() {
        mHasFocus = false;
        if (sInstance == this) sInstance = null;
        if (mWorker != null) mWorker.removeCallbacks(mSample);
        if (mThread != null) mThread.quitSafely();
        mMain.removeCallbacksAndMessages(null);
        try {
            if (mRoot != null && mRoot.isAttachedToWindow()) mWindowManager.removeView(mRoot);
        } catch (RuntimeException e) {
            // already gone
        }
        // ... and goes back to the game.
        Screens.applyFocusLock(getApplicationContext(), true);
    }

    /** The root of the window: keys and sticks of the controller reach the overlay through it. */
    private final class HostLayout extends FrameLayout {
        HostLayout(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(android.view.KeyEvent event) {
            if (OverlayWindow.this.dispatchKeyEvent(event)) return true;
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) dismiss();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            mHasFocus = hasWindowFocus;
        }

        @Override
        public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
            return OverlayWindow.this.onGenericMotionEvent(event) || super
                    .dispatchGenericMotionEvent(event);
        }
    }

    // ---- pieces

    private int u(float px) {
        return Math.round(px * mScale);
    }

    private TextView text(String value, float sizePx, int color, boolean bold) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(sizePx * TEXT_SCALE));
        view.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD)
                : Typeface.create("sans-serif", Typeface.NORMAL));
        view.setSingleLine(true);
        return view;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(u(radius));
        if (stroke != 0) shape.setStroke(Math.max(1, u(1)), stroke);
        return shape;
    }

    private LinearLayout card() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(CARD, LINE, 16));
        card.setPadding(u(20), u(17), u(20), u(17));
        return card;
    }

    private LinearLayout.LayoutParams weight(float weight, int rightMargin) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.rightMargin = u(rightMargin);
        return params;
    }

    private TextView chip(String label, boolean on) {
        final TextView chip = text(label, 14, on ? ON_ACCENT : 0xFFC4CBD8, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(u(11), u(7), u(11), u(7));
        chip.setBackground(box(on ? ACCENT : 0xFF232832, 0, 9));
        return chip;
    }

    private void setChip(TextView chip, boolean on) {
        chip.setTextColor(on ? ON_ACCENT : 0xFFC4CBD8);
        chip.setBackground(hitInset(chip, box(on ? ACCENT : 0xFF232832, 0, 9)));
    }

    /**
     * A chip in a row of equal cells: the whole cell (48 px high) answers to the touch, the
     * bubble drawn in it stays small.
     */
    private void bigHit(TextView chip, int unusedH, int unusedV) {
        chip.setTag(new int[] {u(3), u(4)});
        chip.setPadding(0, u(3), 0, 0);
        chip.setGravity(Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setBackground(hitInset(chip, chip.getBackground()));
    }

    /** Chips on several lines of equal cells: the last line is completed with empty cells. */
    private void addToGrid(LinearLayout grid, int index, int perRow, TextView chip) {
        LinearLayout line;
        if (index % perRow == 0) {
            line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(line);
        } else {
            line = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        }
        line.addView(chip, cell());
    }

    private void completeGrid(LinearLayout grid, int count, int perRow) {
        final int missing = (perRow - count % perRow) % perRow;
        if (missing == 0 || grid.getChildCount() == 0) return;
        final LinearLayout line = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        for (int i = 0; i < missing; i++) line.addView(new View(this), cell());
    }

    private LinearLayout.LayoutParams cell() {
        return new LinearLayout.LayoutParams(0, u(48), 1f);
    }

    private android.graphics.drawable.Drawable hitInset(TextView chip,
            android.graphics.drawable.Drawable background) {
        if (!(chip.getTag() instanceof int[])) return background;
        final int[] extra = (int[]) chip.getTag();
        return new android.graphics.drawable.InsetDrawable(background, extra[0], extra[1],
                extra[0], extra[1]);
    }

    private void space(LinearLayout row, int px) {
        final View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(u(px), 1));
    }

    private LinearLayout line(String name, TextView value) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView label = text(name, 14, 0xFFC4CBD8, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        row.addView(value);
        return row;
    }

    // ---- layout

    private LinearLayout mContent;
    private final LinearLayout[] mTabs = new LinearLayout[4];
    private final TextView[] mTabLabels = new TextView[4];
    private final android.widget.ImageView[] mTabIcons = new android.widget.ImageView[4];
    private static final String[] TAB_ICONS = {"gauge", "gamepad", "volume", "monitor"};
    private static final String[] TAB_NAMES = {"Performance", "Controller", "Sound", "Screens"};
    private int mPage = -1;

    private View build() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int side = u(26) + Math.max(0,
                (getResources().getDisplayMetrics().widthPixels - 1180) / 2);
        root.setPadding(side, u(22), side, u(18));
        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int page = i;
            final LinearLayout tab = new LinearLayout(this);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(u(16), u(9), u(18), u(9));
            final android.widget.ImageView glyph = icon(TAB_ICONS[i], DIM, 18);
            tab.addView(glyph);
            final TextView label = text(TAB_NAMES[i], 16, DIM, true);
            label.setPadding(u(8), 0, 0, 0);
            tab.addView(label);
            tab.setOnClickListener(v -> showPage(page));
            mTabs[i] = tab;
            mTabLabels[i] = label;
            mTabIcons[i] = glyph;
            final LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tabParams.rightMargin = u(6);
            tabs.addView(tab, tabParams);
        }
        root.addView(tabs);
        final View rule = new View(this);
        rule.setBackgroundColor(0xFF262B34);
        final LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, u(1)));
        ruleParams.topMargin = u(10);
        root.addView(rule, ruleParams);
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mContent, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                1f));
        showPage(0);
        return root;
    }

    /** The base colours of the overlay lean a little towards the colour of the profile. */
    private void retint() {
        BG = Accent.tint(0xFF0D0F13, ACCENT, 0.09f);
        CARD = Accent.tint(0xFF1A1E26, ACCENT, 0.07f);
    }

    private void showPage(int page) {
        mPage = page;
        mRemapListening = false;
        mRemapScan = 0;
        mFpsValue = null;
        mNowTitle = null;
        mFanChips.clear();
        mRefreshChips.clear();
        mContent.removeAllViews();
        for (int i = 0; i < mTabs.length; i++) {
            final boolean on = i == page;
            mTabLabels[i].setTextColor(on ? TEXT : DIM);
            mTabIcons[i].setColorFilter(on ? ACCENT : DIM);
            mTabs[i].setBackground(on ? box(0xFF232832, 0, 11) : null);
        }
        switch (page) {
            case 1:
                buildController(mContent);
                break;
            case 2:
                buildSound(mContent);
                break;
            case 3:
                buildScreens(mContent);
                break;
            default:
                buildPerformance(mContent);
                break;
        }
    }

    private void buildPerformance(LinearLayout page) {
        // Profiles
        final LinearLayout profiles = new LinearLayout(this);
        profiles.setOrientation(LinearLayout.HORIZONTAL);
        final String[] profileIcons = {"leaf", "gauge", "cast", "gamepad", "zap"};
        for (int i = 0; i < PerformanceProfiles.NAMES.length; i++) {
            final int mode = i;
            final LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            final android.widget.ImageView glyph = icon(profileIcons[i], PROFILE_COLORS[i], 22);
            tile.addView(glyph);
            final TextView label = text(PerformanceProfiles.NAMES[i], 16, TEXT, true);
            label.setGravity(Gravity.CENTER);
            tile.addView(label, fill(5));
            tile.setOnClickListener(v -> {
                final PerformanceController engine = PerformanceController.get();
                if (engine != null) engine.select(mode);
                showProfile();
            });
            mProfileButtons[i] = label;
            mProfileTiles[i] = tile;
            mProfileIcons[i] = glyph;
            tile.setBackground(box(CARD, LINE, 13));
            profiles.addView(tile, weight(1f, i < 4 ? 8 : 0));
        }
        final LinearLayout.LayoutParams profilesParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u(86));
        profilesParams.topMargin = u(14);
        page.addView(profiles, profilesParams);

        final LinearLayout summaryRow = new LinearLayout(this);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        mSummary = text("", 14, DIM, false);
        mSummary.setSingleLine(false);
        summaryRow.addView(mSummary, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView edit = chip("Edit profiles", false);
        edit.setOnClickListener(v -> showProfileEditor(-1));
        summaryRow.addView(edit);
        page.addView(summaryRow, fill(8));

        // Live numbers: a tap on a card shows its curve
        final LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        mFpsValue = text("--", 38, TEXT, true);
        mFpsLine = text("fps · main screen app", 14, DIM, false);
        mSpark = new Spark(this);
        final LinearLayout fpsCard = statCard("Frame rate", null, mFpsValue, null, null, null);
        fpsCard.addView(mSpark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                u(48)));
        fpsCard.addView(mFpsLine, fill(8));
        fpsCard.setOnClickListener(v -> showGraph(MetricsRecorder.FPS));
        stats.addView(fpsCard, weight(1f, 12));
        mCpuValue = text("--", 38, TEXT, true);
        mCpuFreq = text("--", 14, TEXT, true);
        mCpuTemp = text("--", 14, TEXT, true);
        mCpuBar = bar(ACCENT);
        final LinearLayout cpuCard = statCard("CPU", "SM8550", mCpuValue, mCpuBar,
                line("Frequency", mCpuFreq), line("Temperature", mCpuTemp));
        cpuCard.setOnClickListener(v -> showGraph(MetricsRecorder.CPU));
        stats.addView(cpuCard, weight(1f, 12));
        mGpuValue = text("--", 38, TEXT, true);
        mGpuFreq = text("--", 14, TEXT, true);
        mGpuTemp = text("--", 14, TEXT, true);
        mGpuBar = bar(ACCENT);
        final LinearLayout gpuCard = statCard("GPU", "kgsl-3d0", mGpuValue, mGpuBar,
                line("Frequency", mGpuFreq), line("Temperature", mGpuTemp));
        gpuCard.setOnClickListener(v -> showGraph(MetricsRecorder.GPU));
        stats.addView(gpuCard, weight(1f, 12));
        mMemValue = text("--", 38, TEXT, true);
        mMemLine = text("--", 14, TEXT, true);
        mMemBar = bar(0xFF34C3A5);
        final LinearLayout memCard = statCard("Memory", "16 GB", mMemValue, mMemBar,
                line("In use", mMemLine), null);
        memCard.setOnClickListener(v -> showGraph(MetricsRecorder.RAM));
        stats.addView(memCard, weight(1f, 0));
        final LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u(236));
        statsParams.topMargin = u(12);
        page.addView(stats, statsParams);

        // Fan, battery, refresh rate of the main screen
        final LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        second.addView(fanCard(), weight(1f, 12));
        mBatteryValue = text("--", 38, TEXT, true);
        mBatteryLine = text("--", 14, TEXT, true);
        mBatteryBar = bar(0xFF43C27A);
        final LinearLayout batteryCard = statCard("Battery", null, mBatteryValue, mBatteryBar,
                line("Status", mBatteryLine), null);
        batteryCard.setOnClickListener(v -> showGraph(MetricsRecorder.BATTERY));
        // The title and the value of the battery are centred in its card.
        batteryCard.setGravity(Gravity.CENTER_HORIZONTAL);
        if (batteryCard.getChildAt(0) instanceof LinearLayout) {
            final LinearLayout title = (LinearLayout) batteryCard.getChildAt(0);
            title.setGravity(Gravity.CENTER);
            if (title.getChildAt(0) instanceof TextView) {
                ((TextView) title.getChildAt(0)).setGravity(Gravity.CENTER);
            }
            title.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        second.addView(batteryCard, weight(1f, 12));
        second.addView(refreshCard(), weight(1.3f, 0));
        final LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u(236));
        secondParams.topMargin = u(12);
        page.addView(second, secondParams);

        showProfile();
    }

    // ---- curves (a tap on a card of the performance page)

    private static final String[] METRIC_NAMES = {"CPU usage", "GPU usage", "Memory in use",
        "Battery power", "Frame rate", "CPU temperature", "GPU temperature"};
    private static final String[] METRIC_UNITS = {" %", " %", " %", " W", " fps", " °C", " °C"};
    private static final int[] METRIC_COLORS = {0xFF5B9DFF, 0xFFA78BFA, 0xFF34C3A5, 0xFF43C27A,
        0xFF34C3A5, 0xFFF2A33A, 0xFFF06B5E};
    private static final float[] METRIC_MAX = {100, 100, 100, 0, 0, 0, 0};
    private int mGraphMetric = -1;
    private int mGraphSeconds = 300;
    private LineChart mChart;
    private TextView mGraphNow;
    private TextView mGraphMin;
    private TextView mGraphAvg;
    private TextView mGraphMax;

    /** A header with a back button, shared by the pages that open over the performance page. */
    private LinearLayout detailHeader(String title, Runnable back) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.ImageView arrow = icon("back", TEXT, 18);
        arrow.setPadding(u(10), u(10), u(10), u(10));
        arrow.setBackground(box(0xFF232832, 0xFF2F3643, 12));
        arrow.setOnClickListener(v -> back.run());
        row.addView(arrow, new LinearLayout.LayoutParams(u(56), u(56)));
        final TextView name = text(title, 22, TEXT, true);
        name.setPadding(u(16), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        return row;
    }

    private void enterDetail(int page) {
        mPage = page;
        mFpsValue = null;
        mNowTitle = null;
        mFanChips.clear();
        mRefreshChips.clear();
        mContent.removeAllViews();
        for (int i = 0; i < mTabs.length; i++) {
            final boolean on = i == 0;
            mTabLabels[i].setTextColor(on ? TEXT : DIM);
            mTabIcons[i].setColorFilter(on ? ACCENT : DIM);
            mTabs[i].setBackground(on ? box(0xFF232832, 0, 11) : null);
        }
    }

    private void showGraph(int metric) {
        enterDetail(4);
        mGraphMetric = metric;
        final LinearLayout page = mContent;
        final LinearLayout header = detailHeader(METRIC_NAMES[metric], () -> showPage(0));
        mGraphNow = text("--", 30, METRIC_COLORS[metric], true);
        header.addView(mGraphNow);
        page.addView(header, fill(14));

        final LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        final int[] seconds = {300, 1800, 3600};
        final String[] labels = {"5 min", "30 min", "1 h"};
        final List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int value = seconds[i];
            final TextView chip = chip(labels[i], mGraphSeconds == value);
            chip.setOnClickListener(v -> {
                mGraphSeconds = value;
                for (int k = 0; k < chips.size(); k++) setChip(chips.get(k), seconds[k] == value);
                refreshGraph();
            });
            chips.add(chip);
            controls.addView(chip);
            space(controls, 8);
        }
        // CPU and GPU: usage or temperature
        if (metric == MetricsRecorder.CPU || metric == MetricsRecorder.GPU
                || metric == MetricsRecorder.CPU_TEMP || metric == MetricsRecorder.GPU_TEMP) {
            final boolean cpu = metric == MetricsRecorder.CPU || metric == MetricsRecorder.CPU_TEMP;
            final int usage = cpu ? MetricsRecorder.CPU : MetricsRecorder.GPU;
            final int temp = cpu ? MetricsRecorder.CPU_TEMP : MetricsRecorder.GPU_TEMP;
            space(controls, 24);
            final TextView usageChip = chip("Usage", metric == usage);
            usageChip.setOnClickListener(v -> showGraph(usage));
            controls.addView(usageChip);
            space(controls, 8);
            final TextView tempChip = chip("Temperature", metric == temp);
            tempChip.setOnClickListener(v -> showGraph(temp));
            controls.addView(tempChip);
        }
        page.addView(controls, fill(14));

        final LinearLayout chartCard = card();
        mChart = new LineChart(this, mScale);
        chartCard.addView(mChart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u(330)));
        final LinearLayout axis = new LinearLayout(this);
        final String[] ends = {mGraphSeconds >= 3600 ? "1 h ago" : mGraphSeconds >= 1800
                ? "30 min ago" : "5 min ago", "now"};
        axis.addView(text(ends[0], 13, DIM, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        axis.addView(text(ends[1], 13, DIM, false));
        chartCard.addView(axis, fill(6));
        page.addView(chartCard, fill(14));

        final LinearLayout stats = new LinearLayout(this);
        mGraphMin = text("--", 24, TEXT, true);
        mGraphAvg = text("--", 24, TEXT, true);
        mGraphMax = text("--", 24, TEXT, true);
        final String[] names = {"Lowest", "Average", "Highest"};
        final TextView[] values = {mGraphMin, mGraphAvg, mGraphMax};
        for (int i = 0; i < 3; i++) {
            final LinearLayout cell = card();
            cell.addView(text(names[i].toUpperCase(Locale.ROOT), 12, DIM, true));
            cell.addView(values[i], fill(6));
            stats.addView(cell, weight(1f, i < 2 ? 12 : 0));
        }
        page.addView(stats, fill(12));
        refreshGraph();
    }

    private void refreshGraph() {
        if (mPage != 4 || mChart == null || mGraphMetric < 0) return;
        final MetricsRecorder recorder = MetricsRecorder.get();
        if (recorder == null) return;
        final float[] values = recorder.series(mGraphMetric, mGraphSeconds);
        mChart.set(values, METRIC_COLORS[mGraphMetric], METRIC_UNITS[mGraphMetric].trim().isEmpty()
                ? "" : "", METRIC_MAX[mGraphMetric]);
        float min = Float.MAX_VALUE;
        float max = 0;
        float sum = 0;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        final String unit = METRIC_UNITS[mGraphMetric];
        final boolean decimals = mGraphMetric == MetricsRecorder.BATTERY;
        final String pattern = decimals ? "%.1f%s" : "%.0f%s";
        if (values.length == 0) {
            mGraphNow.setText("--");
            return;
        }
        mGraphNow.setText(String.format(Locale.US, pattern, values[values.length - 1], unit));
        mGraphMin.setText(String.format(Locale.US, pattern, min, unit));
        mGraphAvg.setText(String.format(Locale.US, pattern, sum / values.length, unit));
        mGraphMax.setText(String.format(Locale.US, pattern, max, unit));
    }

    // ---- editing the profiles (maximum frequency of each cluster, GPU, fan)

    private int mEditMode = -1;

    private void showProfileEditor(int mode) {
        final PerformanceController engine = PerformanceController.get();
        if (engine == null) return;
        enterDetail(5);
        mEditMode = mode < 0 ? engine.profiles.current() : mode;
        final LinearLayout page = mContent;
        page.addView(detailHeader("Edit the profiles", () -> showPage(0)), fill(14));

        // which profile
        final LinearLayout which = new LinearLayout(this);
        final List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < PerformanceProfiles.NAMES.length; i++) {
            final int value = i;
            final TextView chip = chip(PerformanceProfiles.NAMES[i], i == mEditMode);
            chip.setOnClickListener(v -> showProfileEditor(value));
            chips.add(chip);
            which.addView(chip);
            space(which, 8);
        }
        page.addView(which, fill(14));

        final PerformanceProfiles.Profile profile = engine.profiles.get(mEditMode);
        final LinearLayout[] cols = columns(page, 1.2f, 1f, 14);

        final LinearLayout cpu = columnCard(cols[0], 0);
        cpu.addView(titleRow("Processor", "Highest frequency of each cluster"));
        final String[] clusterNames = {"Little cores (0-2)", "Big cores (3-6)", "Prime core (7)"};
        for (int c = 0; c < 3; c++) {
            final int cluster = c;
            final int[] freqs = PerformanceProfiles.cpuFrequencies(
                    PerformanceProfiles.CLUSTERS[c][0]);
            final TextView value = text("", 15, TEXT, true);
            value.setGravity(Gravity.END);
            cpu.addView(text(clusterNames[c], 15, TEXT, true), fill(c == 0 ? 14 : 18));
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int index = 0;
            for (int i = 0; i < freqs.length; i++) {
                if (freqs[i] <= profile.cpuKhz[PerformanceProfiles.CLUSTERS[c][0]]) index = i;
            }
            value.setText((freqs.length == 0 ? profile.cpuKhz[PerformanceProfiles.CLUSTERS[c][0]]
                    : freqs[index]) / 1000 + " MHz");
            final SeekBar seek = slider(index, Math.max(1, freqs.length - 1), new Changed() {
                @Override
                void changed(int position) {
                    if (freqs.length == 0) return;
                    engine.profiles.setCluster(mEditMode, cluster, freqs[position]);
                    value.setText(freqs[position] / 1000 + " MHz");
                }
            });
            seek.setEnabled(freqs.length > 0);
            row.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f));
            row.addView(value, new LinearLayout.LayoutParams(u(110),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            cpu.addView(row, fill(6));
        }

        final LinearLayout other = columnCard(cols[1], 0);
        other.addView(titleRow("GPU", "Highest frequency"));
        final long[] gpuFreqs = PerformanceProfiles.gpuFrequencies();
        int gpuIndex = 0;
        for (int i = 0; i < gpuFreqs.length; i++) if (gpuFreqs[i] <= profile.gpuMaxHz) gpuIndex = i;
        final TextView gpuValue = text((gpuFreqs.length == 0 ? profile.gpuMaxHz
                : gpuFreqs[gpuIndex]) / 1_000_000 + " MHz", 15, TEXT, true);
        gpuValue.setGravity(Gravity.END);
        final LinearLayout gpuRow = new LinearLayout(this);
        gpuRow.setGravity(Gravity.CENTER_VERTICAL);
        final SeekBar gpuSeek = slider(gpuIndex, Math.max(1, gpuFreqs.length - 1), new Changed() {
            @Override
            void changed(int position) {
                if (gpuFreqs.length == 0) return;
                engine.profiles.setGpuMax(mEditMode, gpuFreqs[position]);
                gpuValue.setText(gpuFreqs[position] / 1_000_000 + " MHz");
            }
        });
        gpuSeek.setEnabled(gpuFreqs.length > 0);
        gpuRow.addView(gpuSeek, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gpuRow.addView(gpuValue, new LinearLayout.LayoutParams(u(110),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        other.addView(gpuRow, fill(14));

        final LinearLayout fan = columnCard(cols[1], 12);
        fan.addView(titleRow("Fan", null));
        final String[] fanNames = {"Off", "Mute", "Balance", "Turbo"};
        final String[] fanValues = {PerformanceProfiles.FAN_OFF, PerformanceProfiles.FAN_MUTE,
            PerformanceProfiles.FAN_BALANCE, PerformanceProfiles.FAN_TURBO};
        final LinearLayout fanChips = new LinearLayout(this);
        final List<TextView> fanList = new ArrayList<>();
        for (int i = 0; i < fanNames.length; i++) {
            final String value = fanValues[i];
            final TextView chip = chip(fanNames[i], value.equals(profile.fan));
            chip.setOnClickListener(v -> {
                profile.fan = value;
                for (int k = 0; k < fanList.size(); k++) setChip(fanList.get(k),
                        fanValues[k].equals(value));
            });
            fanList.add(chip);
            fanChips.addView(chip);
            space(fanChips, 8);
        }
        fan.addView(fanChips, fill(14));

        final LinearLayout buttons = new LinearLayout(this);
        final TextView save = text("Save", 16, ON_ACCENT, true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(box(ACCENT, 0, 12));
        save.setOnClickListener(v -> {
            final boolean ok = engine.profiles.save();
            if (engine.profiles.current() == mEditMode) engine.select(mEditMode);
            android.widget.Toast.makeText(getApplicationContext(), ok ? "Profiles saved" : "Could not save",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        final TextView reset = text("Back to the original profiles", 14, TEXT, true);
        reset.setGravity(Gravity.CENTER);
        reset.setBackground(box(0xFF232832, 0xFF2F3643, 12));
        reset.setOnClickListener(v -> {
            engine.profiles.reset();
            engine.select(engine.profiles.current());
            showProfileEditor(mEditMode);
        });
        buttons.addView(save, new LinearLayout.LayoutParams(0, u(52), 1f));
        space(buttons, 10);
        buttons.addView(reset, new LinearLayout.LayoutParams(0, u(52), 1.6f));
        cols[1].addView(buttons, fill(12));
    }

    // ---- shared widgets of the sound / screens / controller pages

    private LinearLayout.LayoutParams fill(int top) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = u(top);
        return params;
    }

    private SeekBar slider(int progress, int max, SeekBar.OnSeekBarChangeListener listener) {
        final SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        final android.content.res.ColorStateList accent =
                android.content.res.ColorStateList.valueOf(ACCENT);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        bar.setPadding(u(10), 0, u(10), 0);
        bar.setOnSeekBarChangeListener(listener);
        return bar;
    }

    private abstract static class Changed implements SeekBar.OnSeekBarChangeListener {
        abstract void changed(int value);

        @Override
        public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            if (fromUser) changed(value);
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {}

        @Override
        public void onStopTrackingTouch(SeekBar bar) {}
    }

    /** The on/off switch of the mockup: a 46x26 track with a round knob. */
    private View toggle(boolean on, java.util.function.Consumer<Boolean> set) {
        final android.widget.FrameLayout track = new android.widget.FrameLayout(this);
        final View knob = new View(this);
        final boolean[] state = {on};
        final Runnable paint = () -> {
            track.setBackground(box(state[0] ? ACCENT : LINE, 0, 13));
            knob.setBackground(box(state[0] ? 0xFFFFFFFF : DIM, 0, 10));
            ((android.widget.FrameLayout.LayoutParams) knob.getLayoutParams()).leftMargin =
                    u(state[0] ? 23 : 3);
            knob.requestLayout();
        };
        final android.widget.FrameLayout.LayoutParams knobParams =
                new android.widget.FrameLayout.LayoutParams(u(20), u(20));
        knobParams.topMargin = u(3);
        track.addView(knob, knobParams);
        track.setLayoutParams(new LinearLayout.LayoutParams(u(46), u(26)));
        paint.run();
        track.setOnClickListener(v -> {
            state[0] = !state[0];
            paint.run();
            set.accept(state[0]);
        });
        return track;
    }

    private LinearLayout switchRow(String title, String subtitle, boolean on,
            java.util.function.Consumer<Boolean> set) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(title, 16, TEXT, true));
        if (subtitle != null) names.addView(text(subtitle, 14, DIM, false));
        row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        row.addView(toggle(on, set));
        return row;
    }

    private TextView title(String name, String note) {
        final TextView view = text(name.toUpperCase(Locale.ROOT)
                + (note == null ? "" : "   " + note.toUpperCase(Locale.ROOT)), 12, DIM, true);
        return view;
    }

    private LinearLayout pageCard(LinearLayout page, int top) {
        final LinearLayout card = card();
        page.addView(card, fill(top));
        return card;
    }

    // ---- page: controller

    // ---- icons and columns

    private android.widget.ImageView icon(String name, int color, int size) {
        final android.widget.ImageView view = new android.widget.ImageView(this);
        final int id = getResources().getIdentifier("ic_" + name, "drawable", getPackageName());
        if (id != 0) view.setImageResource(id);
        view.setColorFilter(color);
        view.setLayoutParams(new LinearLayout.LayoutParams(u(size * TEXT_SCALE), u(size * TEXT_SCALE)));
        return view;
    }

    /** Two columns side by side; the weights are the ones of the design. */
    private LinearLayout[] columns(LinearLayout page, float left, float right, int top) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout a = new LinearLayout(this);
        a.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, left);
        pa.rightMargin = u(12);
        row.addView(a, pa);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                right));
        page.addView(row, fill(top));
        return new LinearLayout[] {a, b};
    }

    private LinearLayout columnCard(LinearLayout column, int top) {
        final LinearLayout card = card();
        column.addView(card, fill(top));
        return card;
    }

    private LinearLayout titleRow(String left, String right) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(left.toUpperCase(Locale.ROOT), 12, DIM, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) row.addView(text(right, 14, DIM, false));
        return row;
    }

    /** A horizontal level bar (live values): the fill takes {@code fraction} of the width. */
    private LinearLayout bar(int fillColor) {
        final LinearLayout track = new LinearLayout(this);
        track.setBackground(box(0xFF262B34, 0, 5));
        final View fill = new View(this);
        fill.setBackground(box(fillColor, 0, 5));
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                0f));
        track.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return track;
    }

    private void setBar(LinearLayout track, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        ((LinearLayout.LayoutParams) track.getChildAt(0).getLayoutParams()).weight = fraction;
        ((LinearLayout.LayoutParams) track.getChildAt(1).getLayoutParams()).weight =
                1f - fraction;
        track.requestLayout();
    }

    // ---- page: controller

    /** A round stick pad: crosshair, the dead zone of the controller (dotted), the live point. */
    private final class StickPad extends View {
        private final android.graphics.Paint mPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float mX;
        private float mY;

        StickPad(android.content.Context context) {
            super(context);
        }

        void set(float x, float y) {
            mX = x;
            mY = y;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float radius = Math.min(cx, cy) - u(2);
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            mPaint.setColor(0xFF14181F);
            canvas.drawCircle(cx, cy, radius, mPaint);
            mPaint.setStyle(android.graphics.Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1, u(1.5f)));
            mPaint.setColor(0xFF3A4252);
            canvas.drawCircle(cx, cy, radius, mPaint);
            mPaint.setStrokeWidth(Math.max(1, u(1)));
            mPaint.setColor(0xFF2A303B);
            canvas.drawLine(cx - radius, cy, cx + radius, cy, mPaint);
            canvas.drawLine(cx, cy - radius, cx, cy + radius, mPaint);
            // The dead zone the controller reports (flat 4095 of 32768).
            mPaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[] {u(3), u(4)}, 0));
            mPaint.setColor(0xFF4A5366);
            canvas.drawCircle(cx, cy, radius * 0.125f, mPaint);
            mPaint.setPathEffect(null);
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            mPaint.setColor(0x405B9DFF);
            final float px = cx + mX * (radius - u(9));
            final float py = cy + mY * (radius - u(9));
            canvas.drawCircle(px, py, u(13), mPaint);
            mPaint.setColor(ACCENT);
            canvas.drawCircle(px, py, u(8), mPaint);
        }
    }

    private StickPad mLeftPad;
    private StickPad mRightPad;
    private TextView mLeftStick;
    private TextView mRightStick;
    private LinearLayout mLeftTrigger;
    private LinearLayout mRightTrigger;
    private final java.util.Map<Integer, TextView> mFaceButtons = new java.util.HashMap<>();

    boolean onGenericMotionEvent(android.view.MotionEvent event) {
        if (mPage == 1 && mLeftPad != null
                && (event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) != 0) {
            final float lx = event.getAxisValue(android.view.MotionEvent.AXIS_X);
            final float ly = event.getAxisValue(android.view.MotionEvent.AXIS_Y);
            final float rx = event.getAxisValue(android.view.MotionEvent.AXIS_Z);
            final float ry = event.getAxisValue(android.view.MotionEvent.AXIS_RZ);
            final float lt = event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER);
            final float rt = event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER);
            mLeftPad.set(lx, ly);
            mRightPad.set(rx, ry);
            mLeftStick.setText(String.format(Locale.US, "x %+.2f  ·  y %+.2f", lx, ly));
            mRightStick.setText(String.format(Locale.US, "x %+.2f  ·  y %+.2f", rx, ry));
            setBar(mLeftTrigger, lt);
            setBar(mRightTrigger, rt);
            calibrationFeed(lx, ly, rx, ry, lt, rt);
            // The D-pad is a hat axis, not a key: left is -1.
            mCalHatLeft = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X) < -0.5f;
            calibrationCheckHold();
            return true;
        }
        return false;
    }

    private android.os.VibratorManager controllerVibrator() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            final android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device != null && device.getName().startsWith("AYANEO Controller")
                    && device.getVibratorManager().getVibratorIds().length > 0) {
                return device.getVibratorManager();
            }
        }
        return null;
    }

    private TextView mCalStatus;
    private TextView mCalButton;
    private int mCalStep;                       // 0 idle, 1 sticks, 2 triggers, 3 buttons, 4 done
    private final double[] mCalAngle = new double[2];
    private final double[] mCalTurn = new double[2];
    private boolean mCalLeftTrigger;
    private boolean mCalRightTrigger;
    private long mCalHoldSince;
    private final java.util.Set<Integer> mCalKeys = new java.util.HashSet<>();

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
        if (mCalStatus == null) return;
        final int turnsLeft = (int) Math.round(Math.min(100, mCalTurn[0] / (4 * Math.PI) * 100));
        final int turnsRight = (int) Math.round(Math.min(100, mCalTurn[1] / (4 * Math.PI) * 100));
        switch (mCalStep) {
            case 1:
                mCalStatus.setText("1 / 3   Rotate both sticks two full turns, any direction.\n"
                        + "Left " + turnsLeft + " %   ·   Right " + turnsRight + " %");
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

    boolean dispatchKeyEvent(android.view.KeyEvent event) {
        final int code = event.getKeyCode();
        if (mPage == 1 && mRemapListening && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getScanCode() != 0 && event.getDevice() != null
                && event.getDevice().getName().startsWith("AYANEO")) {
            // The physical button is told by its scan code, which no remap changes.
            mRemapListening = false;
            mRemapScan = event.getScanCode();
            paintRemap();
            return true;
        }
        if (mPage == 1) {
            final TextView face = mFaceButtons.get(code);
            if (face != null) {
                final boolean down = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
                face.setBackground(down ? box(ACCENT, ACCENT, 29) : box(0xFF232832, 0xFF3A4252, 29));
                face.setTextColor(down ? ON_ACCENT : TEXT);
            }
        }
        if (mPage == 1 && mCalStep == 3) {
            // By the scan codes of the controller (L1, R1, B), so that a swapped A / B does not
            // matter.
            final int scan = event.getScanCode();
            if (scan == SCAN_L1 || scan == SCAN_R1 || scan == SCAN_B) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) mCalKeys.add(scan);
                else mCalKeys.remove(scan);
                calibrationCheckHold();
                return true;
            }
        }
        return false;
    }

    private static final int SCAN_L1 = 310;   // BTN_TL
    private static final int SCAN_R1 = 311;   // BTN_TR
    private static final int SCAN_B = 305;    // BTN_EAST
    private boolean mCalHatLeft;

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

    private TextView faceButton(String label, int keyCode) {
        final TextView button = text(label, 20, TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(box(0xFF232832, 0xFF3A4252, 29));
        mFaceButtons.put(keyCode, button);
        return button;
    }

    private void buildController(LinearLayout page) {
        mFaceButtons.clear();
        final LinearLayout[] cols = columns(page, 1.12f, 1f, 16);
        // Sticks
        final LinearLayout sticks = columnCard(cols[0], 0);
        sticks.addView(titleRow("Sticks", "Live position"));
        final LinearLayout pads = new LinearLayout(this);
        mLeftPad = new StickPad(this);
        mRightPad = new StickPad(this);
        mLeftStick = text("x +0.00  ·  y +0.00", 14, TEXT, true);
        mRightStick = text("x +0.00  ·  y +0.00", 14, TEXT, true);
        for (int side = 0; side < 2; side++) {
            final LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.addView(side == 0 ? mLeftPad : mRightPad, new LinearLayout.LayoutParams(u(190),
                    u(190)));
            column.addView(text(side == 0 ? "Left stick" : "Right stick", 14, DIM, false),
                    fill(10));
            column.addView(side == 0 ? mLeftStick : mRightStick, fill(2));
            final LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pads.addView(column, columnParams);
        }
        sticks.addView(pads, fill(14));
        sticks.addView(text("Dotted circle: dead zone of the controller (12 %)", 14, DIM, false),
                fill(12));
        // Dead zone: the share of the travel of a stick that reads as 0
        final int zoneNow = Math.max(0, Math.min(30,
                android.os.SystemProperties.getInt("persist.sys.pocketds.deadzone", 0)));
        final TextView zoneValue = text(zoneNow == 0 ? "None" : zoneNow + " %", 15, TEXT, true);
        zoneValue.setGravity(Gravity.END);
        final LinearLayout zoneRow = new LinearLayout(this);
        zoneRow.setGravity(Gravity.CENTER_VERTICAL);
        zoneRow.addView(text("Dead zone", 15, TEXT, true), new LinearLayout.LayoutParams(u(100),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        zoneRow.addView(slider(zoneNow, 30, new Changed() {
            @Override
            void changed(int value) {
                android.os.SystemProperties.set("persist.sys.pocketds.deadzone",
                        Integer.toString(value));
                zoneValue.setText(value == 0 ? "None" : value + " %");
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        zoneRow.addView(zoneValue, new LinearLayout.LayoutParams(u(70),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sticks.addView(zoneRow, fill(14));
        final TextView zoneNote = text("A stick inside the dead zone reads as centred. The "
                + "controller already ignores 12 %.", 13, DIM, false);
        zoneNote.setSingleLine(false);
        sticks.addView(zoneNote, fill(6));
        mCalStatus = text("", 14, TEXT, false);
        mCalStatus.setSingleLine(false);
        sticks.addView(mCalStatus, fill(14));
        mCalButton = text("Start calibration", 15, TEXT, true);
        mCalButton.setGravity(Gravity.CENTER);
        mCalButton.setBackground(box(0xFF232832, 0xFF2F3643, 10));
        mCalButton.setOnClickListener(v -> {
            if (mCalStep == 0 || mCalStep == 4) {
                calibrationStart();
            } else {
                mCalStep = 0;
                mMain.removeCallbacks(mCalHoldCheck);
                calibrationShow();
            }
        });
        final LinearLayout.LayoutParams calParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u(54));
        calParams.topMargin = u(14);
        sticks.addView(mCalButton, calParams);
        mCalStep = 0;
        calibrationShow();

        // Face buttons
        final LinearLayout faces = columnCard(cols[1], 0);
        faces.addView(titleRow("Face buttons", null));
        final LinearLayout facesRow = new LinearLayout(this);
        facesRow.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.FrameLayout diamond = new android.widget.FrameLayout(this);
        final int[][] places = {{58, 0}, {0, 58}, {116, 58}, {58, 116}};
        final String[] labels = {"Y", "X", "B", "A"};
        final int[] keys = {android.view.KeyEvent.KEYCODE_BUTTON_Y,
            android.view.KeyEvent.KEYCODE_BUTTON_X, android.view.KeyEvent.KEYCODE_BUTTON_B,
            android.view.KeyEvent.KEYCODE_BUTTON_A};
        for (int i = 0; i < 4; i++) {
            final android.widget.FrameLayout.LayoutParams params =
                    new android.widget.FrameLayout.LayoutParams(u(58), u(58));
            params.leftMargin = u(places[i][0]);
            params.topMargin = u(places[i][1]);
            diamond.addView(faceButton(labels[i], keys[i]), params);
        }
        facesRow.addView(diamond, new LinearLayout.LayoutParams(u(174), u(174)));
        final LinearLayout switches = new LinearLayout(this);
        switches.setOrientation(LinearLayout.VERTICAL);
        switches.addView(switchRow("Swap A ↔ B", null,
                android.os.SystemProperties.getBoolean("persist.sys.pocketds.swap_ab", false),
                on -> android.os.SystemProperties.set("persist.sys.pocketds.swap_ab",
                        on ? "true" : "false")));
        switches.addView(switchRow("Swap X ↔ Y", null,
                android.os.SystemProperties.getBoolean("persist.sys.pocketds.swap_xy", false),
                on -> android.os.SystemProperties.set("persist.sys.pocketds.swap_xy",
                        on ? "true" : "false")), fill(18));
        final TextView note = text("Applies to every app. A sends B and B sends A.", 14, DIM,
                false);
        note.setSingleLine(false);
        switches.addView(note, fill(10));
        final LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        switchParams.leftMargin = u(14);
        facesRow.addView(switches, switchParams);
        faces.addView(facesRow, fill(8));

        // Vibration
        final android.os.VibratorManager vibrator = controllerVibrator();
        final android.content.SharedPreferences prefs = PartsPrefs.get(this);
        final LinearLayout vibration = columnCard(cols[1], 12);
        final LinearLayout vibrationHead = titleRow("Vibration", null);
        final TextView test = text("Test", 14, TEXT, true);
        test.setPadding(u(20), u(10), u(20), u(10));
        test.setBackground(box(0xFF232832, 0xFF2F3643, 10));
        test.setOnClickListener(v -> {
            if (vibrator == null) return;
            final int amplitude = Math.max(1, prefs.getInt("controller_vibration", 70) * 255 / 100);
            vibrator.vibrate(android.os.CombinedVibration.createParallel(
                    android.os.VibrationEffect.createOneShot(350, amplitude)));
        });
        vibrationHead.addView(test);
        vibration.addView(vibrationHead);
        final TextView percent = text(prefs.getInt("controller_vibration", 70) + " %", 14, TEXT,
                true);
        final LinearLayout strength = new LinearLayout(this);
        strength.setGravity(Gravity.CENTER_VERTICAL);
        strength.addView(text("Strength", 14, TEXT, false), new LinearLayout.LayoutParams(u(86),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        strength.addView(slider(prefs.getInt("controller_vibration", 70), 100, new Changed() {
            @Override
            void changed(int value) {
                prefs.edit().putInt("controller_vibration", value).apply();
                percent.setText(value + " %");
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        percent.setGravity(Gravity.END);
        strength.addView(percent, new LinearLayout.LayoutParams(u(56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        vibration.addView(strength, fill(16));
        vibration.addView(switchRow("Touch and keyboard feedback", null,
                Settings.System.getInt(getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED,
                        1) == 1, on -> Settings.System.putInt(getContentResolver(),
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, on ? 1 : 0)), fill(18));

        // Triggers
        final LinearLayout triggers = columnCard(cols[1], 12);
        triggers.addView(titleRow("Triggers", null));
        mLeftTrigger = bar(0xFF34C3A5);
        mRightTrigger = bar(0xFF34C3A5);
        for (int i = 0; i < 2; i++) {
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(i == 0 ? "L2" : "R2", 14, TEXT, true), new LinearLayout.LayoutParams(
                    u(36), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(i == 0 ? mLeftTrigger : mRightTrigger, new LinearLayout.LayoutParams(0, u(14),
                    1f));
            row.addView(text("Analog", 14, DIM, true), new LinearLayout.LayoutParams(u(76),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            ((TextView) row.getChildAt(2)).setGravity(Gravity.END);
            triggers.addView(row, fill(i == 0 ? 18 : 16));
        }

        buildRemap(columnCard(cols[1], 12));
    }

    // ---- remapping the buttons (persist.sys.pocketds.keymap, read by the input reader)

    private static final String KEYMAP_PROP = "persist.sys.pocketds.keymap";
    private boolean mRemapListening;
    private int mRemapScan;
    private LinearLayout mRemapCard;

    private static String scanName(int scan) {
        switch (scan) {
            case 304: return "A";
            case 305: return "B";
            case 307: return "X";
            case 308: return "Y";
            case 310: return "L1";
            case 311: return "R1";
            case 314: return "Select";
            case 315: return "Start";
            case 316: return "Mode";
            case 317: return "L3";
            case 318: return "R3";
            case 259: return "LC";
            case 260: return "RC";
            default: return "Button " + scan;
        }
    }

    private static final String[] TARGET_NAMES = {"A", "B", "X", "Y", "L1", "R1", "L3", "R3",
        "Start", "Select", "Mode", "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Back",
        "Home", "Volume up", "Volume down", "Nothing"};
    private static final int[] TARGET_CODES = {96, 97, 99, 100, 102, 103, 106, 107, 108, 109,
        110, 19, 20, 21, 22, 4, 3, 24, 25, 0};

    private static String targetName(int code) {
        for (int i = 0; i < TARGET_CODES.length; i++) if (TARGET_CODES[i] == code) {
            return TARGET_NAMES[i];
        }
        return "Key " + code;
    }

    private List<int[]> readKeymap() {
        final List<int[]> out = new ArrayList<>();
        final String text = android.os.SystemProperties.get(KEYMAP_PROP, "");
        for (String pair : text.split(",")) {
            final String[] parts = pair.split(":");
            if (parts.length != 2) continue;
            try {
                out.add(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
            } catch (NumberFormatException e) {
                // skipped
            }
        }
        return out;
    }

    private boolean writeKeymap(List<int[]> map) {
        final StringBuilder text = new StringBuilder();
        for (int[] pair : map) {
            if (text.length() > 0) text.append(',');
            text.append(pair[0]).append(':').append(pair[1]);
        }
        if (text.length() > 91) {
            android.widget.Toast.makeText(getApplicationContext(), "Too many remaps", android.widget.Toast.LENGTH_SHORT)
                    .show();
            return false;
        }
        android.os.SystemProperties.set(KEYMAP_PROP, text.toString());
        return true;
    }

    private void buildRemap(LinearLayout card) {
        mRemapCard = card;
        paintRemap();
    }

    private void paintRemap() {
        final LinearLayout card = mRemapCard;
        if (card == null) return;
        card.removeAllViews();
        card.addView(titleRow("Button remapping", "Every app"));
        final List<int[]> map = readKeymap();
        for (int i = 0; i < map.size(); i++) {
            final int[] pair = map.get(i);
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(scanName(pair[0]) + "   →   " + targetName(pair[1]), 15, TEXT, true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final TextView remove = chip("Remove", false);
            final int index = i;
            remove.setOnClickListener(v -> {
                final List<int[]> now = readKeymap();
                if (index < now.size()) now.remove(index);
                writeKeymap(now);
                paintRemap();
            });
            row.addView(remove);
            card.addView(row, fill(i == 0 ? 14 : 10));
        }
        if (map.isEmpty() && !mRemapListening) {
            card.addView(text("A button can send another one: press it here, then pick what "
                    + "it does.", 14, DIM, false), fill(12));
        }
        if (mRemapListening) {
            card.addView(text("Press the button you want to change…", 16, ACCENT, true),
                    fill(14));
            final TextView cancel = chip("Cancel", false);
            cancel.setOnClickListener(v -> {
                mRemapListening = false;
                mRemapScan = 0;
                paintRemap();
            });
            card.addView(cancel, fill(10));
        } else if (mRemapScan != 0) {
            card.addView(text(scanName(mRemapScan) + " should act as…", 16, TEXT, true), fill(14));
            final LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.VERTICAL);
            LinearLayout current = null;
            for (int i = 0; i < TARGET_NAMES.length; i++) {
                if (i % 4 == 0) {
                    current = new LinearLayout(this);
                    line.addView(current, fill(8));
                }
                final int code = TARGET_CODES[i];
                final TextView chip = chip(TARGET_NAMES[i], false);
                chip.setOnClickListener(v -> {
                    final List<int[]> now = readKeymap();
                    now.removeIf(pair -> pair[0] == mRemapScan);
                    now.add(new int[] {mRemapScan, code});
                    if (writeKeymap(now)) mRemapScan = 0;
                    paintRemap();
                });
                current.addView(chip);
                space(current, 8);
            }
            card.addView(line, fill(0));
        } else {
            final TextView add = chip("Add a remap", true);
            add.setOnClickListener(v -> {
                mRemapListening = true;
                paintRemap();
            });
            final LinearLayout buttons = new LinearLayout(this);
            buttons.addView(add);
            if (!map.isEmpty()) {
                space(buttons, 8);
                final TextView clear = chip("Remove all", false);
                clear.setOnClickListener(v -> {
                    writeKeymap(new ArrayList<>());
                    paintRemap();
                });
                buttons.addView(clear);
            }
            card.addView(buttons, fill(14));
        }
    }

    // ---- page: sound

    private TextView mMasterPercent;

    private int percentOf(AudioManager audio, int stream) {
        return audio.getStreamVolume(stream) * 100 / Math.max(1, audio.getStreamMaxVolume(stream));
    }

    private String outputState(AudioManager audio, int... types) {
        for (android.media.AudioDeviceInfo device : audio.getDevices(
                AudioManager.GET_DEVICES_OUTPUTS)) {
            for (int type : types) if (device.getType() == type) return "Connected";
        }
        return null;
    }

    private void buildSound(LinearLayout page) {
        final AudioManager audio = getSystemService(AudioManager.class);
        final LinearLayout[] cols = columns(page, 0.8f, 1.15f, 16);

        final LinearLayout master = columnCard(cols[0], 0);
        master.addView(titleRow("Master volume", null));
        final LinearLayout big = new LinearLayout(this);
        big.setGravity(Gravity.BOTTOM);
        mMasterPercent = text(Integer.toString(percentOf(audio, AudioManager.STREAM_MUSIC)), 54,
                TEXT, true);
        big.addView(mMasterPercent);
        final TextView unit = text(" %", 20, DIM, false);
        unit.setPadding(u(4), 0, 0, u(6));
        big.addView(unit);
        master.addView(big, fill(8));
        master.addView(slider(percentOf(audio, AudioManager.STREAM_MUSIC), 100, new Changed() {
            @Override
            void changed(int value) {
                final int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value * max / 100f), 0);
                mMasterPercent.setText(Integer.toString(value));
            }
        }), fill(6));
        master.addView(switchRow("Mute everything", null,
                audio.isStreamMute(AudioManager.STREAM_MUSIC), on -> audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0)), fill(14));

        final LinearLayout output = columnCard(cols[0], 12);
        output.addView(titleRow("Output", null));
        final String headphones = outputState(audio,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET);
        final String bluetooth = outputState(audio,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        final String[][] outputs = {
            {"Speakers", headphones == null && bluetooth == null ? "Selected" : "Available"},
            {"Headphones", headphones == null ? "Not connected" : "Selected"},
            {"Bluetooth", bluetooth == null ? "None connected" : "Selected"}};
        for (String[] entry : outputs) {
            final LinearLayout line = new LinearLayout(this);
            line.addView(text(entry[0], 15, TEXT, false), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(text(entry[1], 14, "Selected".equals(entry[1]) ? TEXT : DIM,
                    "Selected".equals(entry[1])));
            output.addView(line, fill(16));
        }
        final TextView note = text("One master volume for the whole console. Each app has its own "
                + "level on top of it.", 14, DIM, false);
        note.setSingleLine(false);
        cols[0].addView(note, fill(12));

        buildNowPlaying(cols[1]);
        final LinearLayout apps = columnCard(cols[1], 12);
        apps.addView(titleRow("Per-app volume", "Apps that make sound"));
        final AppVolumeController controller = AppVolumeController.get();
        final android.content.pm.PackageManager pm = getPackageManager();
        int shown = 0;
        if (controller != null) {
            for (AppVolumeController.Entry entry : controller.entries()) {
                if (pm.getLaunchIntentForPackage(entry.packageName) == null
                        || entry.packageName.equals("com.ayaneo.home")) continue;
                shown++;
                addRule(apps, 8);
                apps.addView(appRow(entry.label, entry.playing ? "Playing" : "Idle", entry,
                        controller, pm));
            }
        }
        if (shown == 0) {
            apps.addView(text("Start an app that plays sound: it shows up here.", 14, DIM, false),
                    fill(12));
        }
        addRule(apps, 8);
        apps.addView(appRowSystem(audio));
    }

    // ---- now playing (the media session of the music app, MEDIA_CONTENT_CONTROL)

    private TextView mNowTitle;
    private TextView mNowArtist;
    private android.widget.ImageView mNowIcon;
    private android.widget.ImageView mNowPlay;
    private android.media.session.MediaController mNowController;

    private android.media.session.MediaController pickSession() {
        android.media.session.MediaController chosen = null;
        try {
            final android.media.session.MediaSessionManager manager =
                    getSystemService(android.media.session.MediaSessionManager.class);
            for (android.media.session.MediaController controller :
                    manager.getActiveSessions(null)) {
                if (chosen == null) chosen = controller;
                final android.media.session.PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState()
                        == android.media.session.PlaybackState.STATE_PLAYING) {
                    return controller;
                }
            }
        } catch (SecurityException e) {
            // no right to list the sessions: nothing is shown
        }
        return chosen;
    }

    private void buildNowPlaying(LinearLayout column) {
        final LinearLayout card = columnCard(column, 0);
        card.addView(titleRow("Now playing", null));
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        mNowIcon = new android.widget.ImageView(this);
        mNowIcon.setBackground(box(0xFF1C2129, 0xFF2B3340, 14));
        mNowIcon.setPadding(u(6), u(6), u(6), u(6));
        row.addView(mNowIcon, new LinearLayout.LayoutParams(u(60), u(60)));
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        mNowTitle = text("", 16, TEXT, true);
        mNowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mNowArtist = text("", 14, DIM, false);
        mNowArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(mNowTitle);
        names.addView(mNowArtist);
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = u(14);
        row.addView(names, nameParams);
        final String[] glyphs = {"skip_back", "play", "skip_forward"};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            final android.widget.FrameLayout button = new android.widget.FrameLayout(this);
            button.setBackground(box(0xFF232832, 0xFF2F3643, 12));
            final android.widget.ImageView glyph = icon(glyphs[i], TEXT, 16);
            if (i == 1) mNowPlay = glyph;
            button.addView(glyph, new android.widget.FrameLayout.LayoutParams(u(20), u(20),
                    Gravity.CENTER));
            button.setOnClickListener(v -> {
                if (mNowController == null) {
                    // No media session (a web page, a game): the media keys, which reach the app
                    // that plays when it listens to them.
                    sendMediaKey(which == 0 ? android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                            : which == 2 ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                            : android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    mMain.postDelayed(this::refreshNowPlaying, 500);
                    return;
                }
                final android.media.session.MediaController.TransportControls controls =
                        mNowController.getTransportControls();
                if (which == 0) {
                    controls.skipToPrevious();
                } else if (which == 2) {
                    controls.skipToNext();
                } else {
                    final android.media.session.PlaybackState state =
                            mNowController.getPlaybackState();
                    if (state != null && state.getState()
                            == android.media.session.PlaybackState.STATE_PLAYING) {
                        controls.pause();
                    } else {
                        controls.play();
                    }
                }
                mMain.postDelayed(this::refreshNowPlaying, 400);
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(u(52), u(52));
            params.leftMargin = u(8);
            row.addView(button, params);
        }
        card.addView(row, fill(12));
        refreshNowPlaying();
    }

    private void sendMediaKey(int code) {
        final android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        final long now = android.os.SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now,
                android.view.KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now,
                android.view.KeyEvent.ACTION_UP, code, 0));
    }

    /** The app that is making sound although it has no media session, or null. */
    private AppVolumeController.Entry playingWithoutSession() {
        final AppVolumeController controller = AppVolumeController.get();
        if (controller == null) return null;
        for (AppVolumeController.Entry entry : controller.entries()) {
            if (entry.playing && !getPackageName().equals(entry.packageName)
                    && !"android".equals(entry.packageName)) {
                return entry;
            }
        }
        return null;
    }

    private void refreshNowPlaying() {
        if (mNowTitle == null) return;
        mNowController = pickSession();
        if (mNowController != null) {
            final android.media.session.PlaybackState st = mNowController.getPlaybackState();
            final boolean active = st != null && st.getState()
                    != android.media.session.PlaybackState.STATE_NONE
                    && st.getState() != android.media.session.PlaybackState.STATE_ERROR
                    && st.getState() != android.media.session.PlaybackState.STATE_STOPPED;
            // A session that is finished or broken (a Bluetooth link that is gone) is not shown.
            if (!active) mNowController = null;
        }
        if (mNowController == null) {
            final AppVolumeController.Entry sound = playingWithoutSession();
            if (sound != null) {
                mNowTitle.setText(sound.label != null ? sound.label : sound.packageName);
                mNowArtist.setText("Playing · the app gives no title");
                try {
                    mNowIcon.setImageDrawable(getPackageManager()
                            .getApplicationIcon(sound.packageName));
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    mNowIcon.setImageDrawable(null);
                }
                mNowPlay.setImageResource(getResources().getIdentifier("ic_pause", "drawable",
                        getPackageName()));
                return;
            }
            mNowTitle.setText("Nothing is playing");
            mNowArtist.setText("Start some music or a video");
            mNowIcon.setImageDrawable(null);
            mNowPlay.setImageResource(getResources().getIdentifier("ic_play", "drawable",
                    getPackageName()));
            return;
        }
        final android.media.MediaMetadata metadata = mNowController.getMetadata();
        String app = mNowController.getPackageName();
        try {
            final android.content.pm.PackageManager pm = getPackageManager();
            app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(app, 0)));
            mNowIcon.setImageDrawable(pm.getApplicationIcon(mNowController.getPackageName()));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            mNowIcon.setImageDrawable(null);
        }
        final CharSequence title = metadata == null ? null
                : metadata.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
        final CharSequence artist = metadata == null ? null
                : metadata.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        mNowTitle.setText(title == null || title.length() == 0 ? app : title);
        mNowArtist.setText(artist == null || artist.length() == 0 ? app : artist + " · " + app);
        final android.media.session.PlaybackState state = mNowController.getPlaybackState();
        final boolean playing = state != null && state.getState()
                == android.media.session.PlaybackState.STATE_PLAYING;
        mNowPlay.setImageResource(getResources().getIdentifier(playing ? "ic_pause" : "ic_play",
                "drawable", getPackageName()));
    }

    /** A thin line between two rows (a bare View would take all the room offered). */
    private void addRule(LinearLayout card, int top) {
        final View line = new View(this);
        line.setBackgroundColor(0xFF262B34);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, u(1)));
        params.topMargin = u(top);
        card.addView(line, params);
    }

    private LinearLayout soundRow(View icon, String name, String status, int percent,
            SeekBar.OnSeekBarChangeListener listener, java.util.function.IntConsumer onMute) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, u(14), 0, u(14));
        row.addView(icon, new LinearLayout.LayoutParams(u(54), u(54)));
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        final TextView label = text(name, 15, TEXT, true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(label);
        final TextView state = text(status, 14, DIM, false);
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(state);
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(u(150),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.leftMargin = u(14);
        row.addView(names, nameParams);
        final TextView value = text(percent + " %", 14, TEXT, true);
        value.setGravity(Gravity.END);
        final SeekBar seek = slider(percent, 100, new Changed() {
            @Override
            void changed(int v) {
                value.setText(v + " %");
                listener.onProgressChanged(null, v, true);
            }
        });
        row.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(u(60), ViewGroup.LayoutParams.WRAP_CONTENT));
        final android.widget.FrameLayout mute = new android.widget.FrameLayout(this);
        mute.setBackground(box(0xFF232832, 0xFF2F3643, 10));
        final android.widget.ImageView glyph = icon(percent == 0 ? "volume_x" : "volume", DIM, 15);
        mute.addView(glyph, new android.widget.FrameLayout.LayoutParams(u(24), u(24), Gravity.CENTER));
        final int[] previous = {percent > 0 ? percent : 100};
        mute.setOnClickListener(v -> {
            final boolean muted = seek.getProgress() == 0;
            if (!muted) previous[0] = seek.getProgress();
            final int next = muted ? previous[0] : 0;
            seek.setProgress(next);
            value.setText(next + " %");
            glyph.setImageResource(getResources().getIdentifier(next == 0 ? "ic_volume_x"
                    : "ic_volume", "drawable", getPackageName()));
            onMute.accept(next);
        });
        row.addView(mute, new LinearLayout.LayoutParams(u(46), u(46)));
        return row;
    }

    private FrameBox iconBox(View content) {
        final FrameBox box = new FrameBox(this);
        box.setBackground(box(0xFF1C2129, 0xFF2B3340, 12));
        box.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        return box;
    }

    private static final class FrameBox extends android.widget.FrameLayout {
        FrameBox(android.content.Context context) {
            super(context);
        }
    }

    private LinearLayout appRow(String name, String status, AppVolumeController.Entry entry,
            AppVolumeController controller, android.content.pm.PackageManager pm) {
        final android.widget.ImageView image = new android.widget.ImageView(this);
        try {
            image.setImageDrawable(pm.getApplicationIcon(entry.packageName));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // no icon
        }
        image.setPadding(u(5), u(5), u(5), u(5));
        return soundRow(iconBox(image), name, status, entry.percent, new Changed() {
            @Override
            void changed(int value) {
                controller.set(entry.packageName, value);
            }
        }, value -> controller.set(entry.packageName, value));
    }

    private LinearLayout appRowSystem(AudioManager audio) {
        final android.widget.ImageView gear = icon("settings", DIM, 22);
        final android.widget.FrameLayout.LayoutParams unused = null;
        final FrameBox boxed = iconBox(gear);
        gear.setLayoutParams(new android.widget.FrameLayout.LayoutParams(u(22), u(22),
                Gravity.CENTER));
        return soundRow(boxed, "System sounds", "Touch, keyboard, notifications",
                percentOf(audio, AudioManager.STREAM_SYSTEM), new Changed() {
                    @Override
                    void changed(int value) {
                        final int max = audio.getStreamMaxVolume(AudioManager.STREAM_SYSTEM);
                        audio.setStreamVolume(AudioManager.STREAM_SYSTEM,
                                Math.round(value * max / 100f), 0);
                    }
                }, value -> {
                    final int max = audio.getStreamMaxVolume(AudioManager.STREAM_SYSTEM);
                    audio.setStreamVolume(AudioManager.STREAM_SYSTEM,
                            Math.round(value * max / 100f), 0);
                });
    }

    // ---- page: screens

    /** A tile of the design: the two screens drawn one above the other, the label below. */
    private LinearLayout tile(String label, String glyph, boolean on) {
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL | (glyph == null ? Gravity.CENTER_VERTICAL : 0));
        tile.setPadding(u(glyph == null ? 4 : 10), u(22), u(glyph == null ? 4 : 10), u(20));
        tile.setBackground(box(on ? Accent.soft(ACCENT) : 0xFF171B22, on ? ACCENT : LINE, 14));
        if (glyph != null) {
            final boolean topLit = !"bottom".equals(glyph);
            final boolean bottomLit = !glyph.startsWith("top");
            final View top = new View(this);
            top.setBackground(topLit ? box(on ? ACCENT : 0xFF2F3643, 0, 6)
                    : box(0xFF1D222B, 0xFF3A4252, 6));
            final View bottom = new View(this);
            bottom.setBackground(bottomLit ? box(on ? ACCENT : 0xFF2F3643, 0, 6)
                    : box(0xFF1D222B, 0xFF3A4252, 6));
            tile.addView(top, new LinearLayout.LayoutParams(u(82), u(46)));
            final LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(u(58),
                    u(46));
            bottomParams.topMargin = u(6);
            tile.addView(bottom, bottomParams);
        }
        final TextView name = text(label, 14, on ? TEXT : 0xFFC4CBD8, true);
        name.setSingleLine(false);
        name.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = u(glyph == null ? 0 : 14);
        tile.addView(name, nameParams);
        return tile;
    }

    private void addTiles(LinearLayout card, String[] names, String[] glyphs, String[] values,
            java.util.function.Supplier<String> get, java.util.function.Consumer<String> set) {
        final LinearLayout row = new LinearLayout(this);
        final List<LinearLayout> tiles = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final LinearLayout tile = tile(names[i], glyphs == null ? null : glyphs[i],
                    values[i].equals(get.get()));
            tiles.add(tile);
            row.addView(tile, tileParams(i, names.length));
        }
        addTilesRefresh(row, tiles, names, glyphs, values, get.get(), set);
        card.addView(row, fill(12));
    }

    private LinearLayout.LayoutParams tileParams(int index, int count) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.rightMargin = u(index < count - 1 ? 10 : 0);
        return params;
    }

    private void addTilesRefresh(LinearLayout row, List<LinearLayout> tiles, String[] names,
            String[] glyphs, String[] values, String selected,
            java.util.function.Consumer<String> set) {
        for (int k = 0; k < tiles.size(); k++) {
            final String target = values[k];
            final LinearLayout fresh = tile(names[k], glyphs == null ? null : glyphs[k],
                    target.equals(selected));
            row.removeViewAt(k);
            tiles.set(k, fresh);
            row.addView(fresh, k, tileParams(k, names.length));
            fresh.setOnClickListener(v -> {
                set.accept(target);
                addTilesRefresh(row, tiles, names, glyphs, values, target, set);
            });
        }
    }

    private void buildScreens(LinearLayout page) {
        final SharedPreferences prefs = PartsPrefs.get(this);
        final LinearLayout mode = pageCard(page, 16);
        mode.addView(titleRow("Screen mode", "1920×1080 top · 1024×768 bottom"));
        addTiles(mode, new String[] {"Both screens", "Top only · bottom as cursor",
            "Top only · bottom as touchpad", "Bottom only"}, new String[] {"both", "topcursor",
            "toptouch", "bottom"}, new String[] {Screens.MODE_BOTH, Screens.MODE_TOP_CURSOR,
            Screens.MODE_TOP_TOUCHPAD, Screens.MODE_BOTTOM}, () -> Screens.screenMode(this),
                choice -> Screens.setScreenMode(this, choice));
        mode.addView(switchRow("Swap the screens", "The bottom screen becomes the main screen "
                + "and the other way round", Screens.isSwapped(this),
                on -> Screens.setSwapped(this, on)), fill(16));

        page.addView(brightnessCard(), fill(12));

        final LinearLayout focus = pageCard(page, 12);
        focus.addView(titleRow("Controller focus", null));
        addTiles(focus, new String[] {"Automatic", "Always the top screen",
            "Always the bottom screen"}, null,
                new String[] {Screens.FOCUS_AUTO, Screens.FOCUS_TOP, Screens.FOCUS_BOTTOM},
                () -> prefs.getString(PartsPrefs.FOCUS_LOCK, Screens.FOCUS_AUTO), choice -> {
                    prefs.edit().putString(PartsPrefs.FOCUS_LOCK, choice).apply();
                    Screens.applyFocusLock(this, true);
                });

        final LinearLayout swipe = pageCard(page, 12);
        swipe.addView(switchRow("Swipe back on the second screen",
                "Swipe from its left or right edge towards the middle",
                prefs.getBoolean(PartsPrefs.SECOND_GESTURES, true),
                on -> prefs.edit().putBoolean(PartsPrefs.SECOND_GESTURES, on).apply()));
    }

    private LinearLayout statCard(String title, TextView value, View first, View second) {
        return statCard(title, null, value, null, first, second);
    }

    private LinearLayout statCard(String title, String sub, TextView value, View bar, View first,
            View second) {
        final LinearLayout card = card();
        card.addView(titleRow(title, sub));
        final LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = u(8);
        valueParams.bottomMargin = u(6);
        card.addView(value, valueParams);
        if (bar != null) {
            final LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, u(6));
            barParams.bottomMargin = u(8);
            card.addView(bar, barParams);
        }
        if (first != null) card.addView(first);
        if (second != null) {
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = u(5);
            card.addView(second, params);
        }
        return card;
    }

    /** The last frame rates as a line (the frame rate card). */
    private final class Spark extends View {
        private final float[] mValues = new float[40];
        private int mCount;
        private final android.graphics.Paint mPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        Spark(android.content.Context context) {
            super(context);
            mPaint.setStyle(android.graphics.Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(2, u(2)));
            mPaint.setColor(0xFF34C3A5);
            mPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        void add(float value) {
            if (mCount < mValues.length) {
                mValues[mCount++] = value;
            } else {
                System.arraycopy(mValues, 1, mValues, 0, mValues.length - 1);
                mValues[mValues.length - 1] = value;
            }
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (mCount < 2) return;
            float max = 1;
            for (int i = 0; i < mCount; i++) max = Math.max(max, mValues[i]);
            final android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < mCount; i++) {
                final float x = getWidth() * i / (float) (mValues.length - 1);
                final float y = getHeight() * (1 - mValues[i] / (max * 1.1f)) - u(2);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            canvas.drawPath(path, mPaint);
        }
    }

    private View fanCard() {
        final LinearLayout card = card();
        final TextView fanTitle = text("FAN", 12, DIM, true);
        fanTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(fanTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mFanValue = text("--", 30, TEXT, true);
        final LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = u(8);
        valueParams.bottomMargin = u(6);
        mFanValue.setGravity(Gravity.CENTER_HORIZONTAL);
        valueParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        card.addView(mFanValue, valueParams);
        final LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        final String[] names = {"Auto", "Off", "Mute", "Balance", "Turbo"};
        final String[] values = {"profile", PerformanceProfiles.FAN_OFF,
            PerformanceProfiles.FAN_MUTE, PerformanceProfiles.FAN_BALANCE,
            PerformanceProfiles.FAN_TURBO};
        final String chosen = PartsPrefs.get(this).getString(PartsPrefs.FAN_MODE, "profile");
        for (int i = 0; i < names.length; i++) {
            final String value = values[i];
            final TextView chip = chip(names[i], value.equals(chosen));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(16));
            chip.setSingleLine(true);
            bigHit(chip, 6, 6);
            chip.setOnClickListener(v -> {
                final PerformanceController engine = PerformanceController.get();
                if (engine != null) engine.setFanMode(value);
                for (int k = 0; k < mFanChips.size(); k++) {
                    setChip(mFanChips.get(k), values[k].equals(value));
                }
            });
            mFanChips.add(chip);
            addToGrid(chips, i, 3, chip);
        }
        completeGrid(chips, names.length, 3);
        final LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsParams.leftMargin = -u(3);
        chipsParams.rightMargin = -u(3);
        card.addView(chips, chipsParams);
        return card;
    }

    private View refreshCard() {
        final LinearLayout card = card();
        final TextView refreshTitle = text("REFRESH RATE · TOP SCREEN", 12, DIM, true);
        refreshTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(refreshTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        top.addView(grid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        ((LinearLayout.LayoutParams) grid.getLayoutParams()).leftMargin = -u(3);
        ((LinearLayout.LayoutParams) grid.getLayoutParams()).rightMargin = -u(3);
        final String current = PerformanceProfiles.topRefresh(this);
        for (int i = 0; i < REFRESH_VALUES.length; i++) {
            final String value = REFRESH_VALUES[i];
            final TextView chip = chip(REFRESH_NAMES[i], value.equals(current));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(17));
            bigHit(chip, 6, 7);
            chip.setOnClickListener(v -> {
                PerformanceProfiles.setTopRefresh(this, value);
                for (int k = 0; k < mRefreshChips.size(); k++) {
                    setChip(mRefreshChips.get(k), REFRESH_VALUES[k].equals(value));
                }
            });
            mRefreshChips.add(chip);
            addToGrid(grid, i, 3, chip);
        }
        completeGrid(grid, REFRESH_VALUES.length, 3);
        final LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.topMargin = u(8) + u(37) + u(6) + u(14);
        card.addView(top, topParams);
        return card;
    }

    private LinearLayout sliderRow(String name, int progress, int max,
            SeekBar.OnSeekBarChangeListener listener, SeekBar[] out) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(name, 15, TEXT, true), new LinearLayout.LayoutParams(u(70),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView percent = text(progress * 100 / Math.max(1, max) + " %", 14, TEXT, true);
        percent.setGravity(Gravity.END);
        final SeekBar bar = slider(progress, max, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
                percent.setText(value * 100 / Math.max(1, max) + " %");
                listener.onProgressChanged(seek, value, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seek) {
                listener.onStartTrackingTouch(seek);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seek) {
                listener.onStopTrackingTouch(seek);
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(percent, new LinearLayout.LayoutParams(u(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (out != null) out[0] = bar;
        return row;
    }

    private View brightnessCard() {
        final LinearLayout card = card();
        final LinearLayout header = titleRow("Brightness", null);
        card.addView(header);
        final android.content.ContentResolver resolver = getContentResolver();
        final int top = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128);
        int bottom = Settings.Global.getInt(resolver, "secondary_screen_brightness", -1);
        if (bottom <= 0) bottom = top;
        final SeekBar[] topBar = new SeekBar[1];
        final SeekBar[] bottomBar = new SeekBar[1];
        final boolean[] linked = {PartsPrefs.get(this).getBoolean(PartsPrefs.BRIGHTNESS_LINK, false)};
        final LinearLayout topRow = sliderRow("Top", top, 255, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                // Until it has a level of its own the bottom screen follows the top one: pin it
                // first so that it stays where it is.
                if (!linked[0] && Settings.Global.getInt(resolver, "secondary_screen_brightness",
                        -1) <= 0 && bottomBar[0] != null) {
                    Settings.Global.putInt(resolver, "secondary_screen_brightness",
                            Math.max(1, bottomBar[0].getProgress()));
                }
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                        Math.max(1, value));
                if (linked[0] && bottomBar[0] != null) {
                    bottomBar[0].setProgress(value);
                    Settings.Global.putInt(resolver, "secondary_screen_brightness",
                            Math.max(1, value));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        }, topBar);
        final LinearLayout bottomRow = sliderRow("Bottom", bottom, 255,
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (!fromUser) return;
                        Settings.Global.putInt(resolver, "secondary_screen_brightness",
                                Math.max(1, value));
                        if (linked[0] && topBar[0] != null) {
                            topBar[0].setProgress(value);
                            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                                    Math.max(1, value));
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {}
                }, bottomBar);
        // The two screens are independent unless the user links them with this button.
        final LinearLayout link = new LinearLayout(this);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setPadding(u(10), u(5), u(10), u(5));
        final android.widget.ImageView linkIcon = icon("link", DIM, 14);
        final TextView linkText = text("Separate", 13, DIM, true);
        linkText.setPadding(u(6), 0, 0, 0);
        link.addView(linkIcon);
        link.addView(linkText);
        final Runnable paintLink = () -> {
            linkText.setText(linked[0] ? "Linked" : "Separate");
            linkText.setTextColor(linked[0] ? ACCENT : DIM);
            linkIcon.setColorFilter(linked[0] ? ACCENT : DIM);
            link.setBackground(box(linked[0] ? Accent.soft(ACCENT) : 0xFF232832,
                    linked[0] ? ACCENT : LINE, 10));
        };
        paintLink.run();
        link.setOnClickListener(v -> {
            linked[0] = !linked[0];
            PartsPrefs.get(this).edit().putBoolean(PartsPrefs.BRIGHTNESS_LINK, linked[0]).apply();
            paintLink.run();
            if (linked[0] && bottomBar[0] != null && topBar[0] != null) {
                // Linking puts the bottom screen at the level of the top one.
                bottomBar[0].setProgress(topBar[0].getProgress());
                Settings.Global.putInt(resolver, "secondary_screen_brightness",
                        Math.max(1, topBar[0].getProgress()));
            }
        });
        header.addView(link);
        card.addView(topRow);
        card.addView(bottomRow);
        return card;
    }

    private View volumeCard() {
        final LinearLayout card = card();
        card.addView(text("VOLUME", 12, DIM, true));
        final AudioManager audio = getSystemService(AudioManager.class);
        final int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final LinearLayout row = sliderRow("Master", audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                max, new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {}
                }, null);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = u(22);
        card.addView(row, params);
        return card;
    }

    // ---- profile

    private void showProfile() {
        final PerformanceController engine = PerformanceController.get();
        if (engine == null) return;
        final int current = engine.profiles.current();
        if (Accent.of(current) != ACCENT) {
            // The whole interface takes the colour of the profile.
            ACCENT = Accent.of(current);
            retint();
            mMain.post(() -> {
                if (mRoot != null) mRoot.setBackgroundColor(BG);
                showPage(mPage);
            });
            return;
        }
        for (int i = 0; i < mProfileButtons.length; i++) {
            final boolean on = i == current;
            mProfileButtons[i].setTextColor(on ? ON_ACCENT : TEXT);
            mProfileIcons[i].setColorFilter(on ? ON_ACCENT : PROFILE_COLORS[i]);
            mProfileTiles[i].setBackground(box(on ? ACCENT : CARD, on ? ACCENT : LINE, 13));
        }
        final String[] fanValues = {"profile", PerformanceProfiles.FAN_OFF,
            PerformanceProfiles.FAN_MUTE, PerformanceProfiles.FAN_BALANCE,
            PerformanceProfiles.FAN_TURBO};
        final String fanChosen = PartsPrefs.get(this).getString(PartsPrefs.FAN_MODE, "profile");
        for (int k = 0; k < mFanChips.size() && k < fanValues.length; k++) {
            setChip(mFanChips.get(k), fanValues[k].equals(fanChosen));
        }
        final PerformanceProfiles.Profile p = engine.profiles.get(current);
        mSummary.setText(String.format(Locale.US,
                "%s · CPU limits %d / %d / %d MHz · GPU up to %d MHz · fan %s",
                PerformanceProfiles.NAMES[current], p.cpuKhz[0] / 1000, p.cpuKhz[3] / 1000,
                p.cpuKhz[7] / 1000, p.gpuMaxHz / 1_000_000, fanName(p.fan)));
    }

    private static String fanName(String mode) {
        switch (mode) {
            case PerformanceProfiles.FAN_OFF:
                return "off";
            case PerformanceProfiles.FAN_BALANCE:
                return "Balance";
            case PerformanceProfiles.FAN_TURBO:
                return "Turbo";
            default:
                return "Mute";
        }
    }

    // ---- live numbers

    private void findGpuZones() {
        final File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) return;
        for (File zone : zones) {
            final String type = read(new File(zone, "type"));
            if (type != null && type.startsWith("gpuss-")) {
                mGpuZones.add(new File(zone, "temp").getPath());
            }
        }
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private final Runnable mSample = new Runnable() {
        @Override
        public void run() {
            try {
                sample();
            } catch (RuntimeException e) {
                // a broken reading must not stop the others
            }
            if (mWorker != null) mWorker.postDelayed(this, PERIOD_MS);
        }
    };

    private void sample() {
        // The profile can change from the button of the console while the overlay is open.
        mMain.post(() -> {
            if (mPage == 0 && mSummary != null && mProfileTiles[0] != null) showProfile();
            if (mPage == 2 && mNowTitle != null) refreshNowPlaying();
            if (mPage == 4) refreshGraph();
        });
        final PerfOverlayController perf = PerfOverlayController.sInstance;
        final int fps = perf == null ? -1 : perf.foregroundFps();
        final int cpu = cpuUsage();
        final long cpuMhz = PerfOverlayController.maxCpuKhz() / 1000;
        final PerformanceController engine = PerformanceController.get();
        final float cpuTemp = engine == null ? 0 : engine.fan.hottestZone();
        final int rpm = engine == null ? 0 : engine.fan.rpm();
        final String gpuBusy = PerfOverlayController.readLong(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", -1) < 0 ? "--"
                : PerfOverlayController.readLong("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", 0)
                + "";
        final long gpuMhz = PerfOverlayController.readLong("/sys/class/kgsl/kgsl-3d0/gpuclk", 0)
                / 1_000_000;
        float gpuTemp = 0;
        for (String zone : mGpuZones) {
            final String text = read(new File(zone));
            try {
                if (text != null) gpuTemp = Math.max(gpuTemp, Integer.parseInt(text) / 1000f);
            } catch (NumberFormatException e) {
                // unreadable sensor
            }
        }
        final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(memory);
        final int memPercent = (int) (100L * (memory.totalMem - memory.availMem) / memory.totalMem);
        final String memLine = String.format(Locale.US, "%.1f / %.1f GB",
                (memory.totalMem - memory.availMem) / 1e9, memory.totalMem / 1e9);
        final Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        final int level = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        final double temp = battery == null ? 0
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
        final long microAmps = PerfOverlayController.readLong(
                "/sys/class/power_supply/battery/current_now", 0);
        final long microVolts = PerfOverlayController.readLong(
                "/sys/class/power_supply/battery/voltage_now", 0);
        final double watts = microAmps / 1e6 * microVolts / 1e6;
        final String batteryLine = String.format(Locale.US, "%.0f °C · %.1f W", temp, watts);
        final float gpuTempShown = gpuTemp;
        mMain.post(() -> {
            if (mFpsValue == null || mPage != 0) return;
            mFpsValue.setText(fps < 0 ? "--" : Integer.toString(fps));
            mFpsLine.setText("fps · main screen app");
            mCpuValue.setText(cpu + " %");
            mCpuFreq.setText(cpuMhz + " MHz");
            mCpuTemp.setText(Math.round(cpuTemp) + " °C");
            mGpuValue.setText(gpuBusy + " %");
            mGpuFreq.setText(gpuMhz + " MHz");
            mGpuTemp.setText(gpuTempShown > 0 ? Math.round(gpuTempShown) + " °C" : "--");
            mMemValue.setText(memPercent + " %");
            mMemLine.setText(memLine);
            mFanValue.setText(rpm + " rpm");
            mBatteryValue.setText(level + " %");
            mBatteryLine.setText(batteryLine);
            setBar(mCpuBar, cpu / 100f);
            try {
                setBar(mGpuBar, Integer.parseInt(gpuBusy) / 100f);
            } catch (NumberFormatException e) {
                setBar(mGpuBar, 0);
            }
            setBar(mMemBar, memPercent / 100f);
            setBar(mBatteryBar, level / 100f);
            if (fps >= 0) mSpark.add(fps);
        });
    }

    /** Share of the processor in use since the last reading, from /proc/stat. */
    private int cpuUsage() {
        final String text = read(new File("/proc/stat"));
        if (text == null) return 0;
        final String first = text.split("\n")[0].trim();
        final String[] f = first.split("\\s+");
        long total = 0;
        for (int i = 1; i < f.length; i++) {
            try {
                total += Long.parseLong(f[i]);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        long idle = 0;
        try {
            idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
        } catch (RuntimeException e) {
            return 0;
        }
        final long dTotal = total - mLastTotal;
        final long dIdle = idle - mLastIdle;
        mLastTotal = total;
        mLastIdle = idle;
        if (dTotal <= 0) return 0;
        return (int) Math.max(0, Math.min(100, 100 * (dTotal - dIdle) / dTotal));
    }
}
