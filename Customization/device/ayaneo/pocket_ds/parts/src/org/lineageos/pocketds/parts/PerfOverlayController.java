/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performance overlay: frames per second of the app in the foreground (from the presentation
 * times SurfaceFlinger keeps for each layer), GPU and CPU load, memory, temperature and power.
 * Shown on the main screen; it can be moved by dragging it, and stays where it was left. It is switched on and off by the option of the app or by the
 * broadcast {@link #ACTION_TOGGLE} (sent by the button of the console that was chosen for it).
 */
class PerfOverlayController implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String ACTION_TOGGLE = "org.lineageos.pocketds.parts.TOGGLE_PERF_OVERLAY";
    private static final String TAG = "PocketDSParts";
    private static final String SEPARATOR = "  |  ";
    private static final long PERIOD_MS = 500;
    // The list of the layers of the app changes rarely: read again every few seconds only.
    private static final long LAYERS_REFRESH_MS = 3000;
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/";
    private static final String BATTERY = "/sys/class/power_supply/battery/";

    private static final Pattern REQUESTED_LAYER =
            Pattern.compile("^RequestedLayerState\\{(.*) parentId=.*");

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private HandlerThread mThread;
    private Handler mWorker;
    private WindowManager mWindowManager;
    private TextView mView;
    private List<String> mLayers = new ArrayList<>();
    private String mLayersPackage;
    private long mLayersTime;

    // The overlay of the performance page reads the frame rate from here.
    static PerfOverlayController sInstance;

    PerfOverlayController(Context context) {
        mContext = context;
        mPrefs = PartsPrefs.get(context);
        sInstance = this;
    }

    void start() {
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mPrefs.edit().putBoolean(PartsPrefs.PERF_OVERLAY,
                        !mPrefs.getBoolean(PartsPrefs.PERF_OVERLAY, false)).apply();
            }
        }, new IntentFilter(ACTION_TOGGLE), Context.RECEIVER_NOT_EXPORTED);
        // Never shown again by itself after a reboot: it is a tool for the moment.
        mPrefs.edit().putBoolean(PartsPrefs.PERF_OVERLAY, false).apply();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (!PartsPrefs.PERF_OVERLAY.equals(key)) return;
        mMain.post(prefs.getBoolean(key, false) ? this::show : this::hide);
    }

    // ---- window

    private void show() {
        if (mView != null) return;
        try {
            final Display display = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            // Above the status bar and the notification shade, so that the overlay can be dragged
            // anywhere, including the top of the screen (system overlay window, system uid).
            int type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            Context windowContext;
            try {
                windowContext = mContext.createDisplayContext(display)
                        .createWindowContext(type, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "System overlay window not allowed, using an application overlay", e);
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                windowContext = mContext.createDisplayContext(display)
                        .createWindowContext(type, null);
            }
            mWindowManager = windowContext.getSystemService(WindowManager.class);
            // A thin line on a barely visible background, so that the game stays in sight; each
            // value is coloured by its state (green fine, yellow tight, red at the limit).
            final TextView view = new TextView(windowContext);
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            view.setTextColor(Color.WHITE);
            view.setShadowLayer(2f, 0f, 1f, 0xD9000000);
            final android.graphics.drawable.GradientDrawable back =
                    new android.graphics.drawable.GradientDrawable();
            final float density = windowContext.getResources().getDisplayMetrics().density;
            back.setColor(0x520A0E14);
            back.setCornerRadius(7 * density);
            view.setBackground(back);
            final int padH = Math.round(8 * density);
            final int padV = Math.round(4 * density);
            view.setPadding(padH, padV, padH, padV);
            view.setSingleLine(true);
            view.setText("...");
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("PocketDS performance overlay");
            lp.setFitInsetsTypes(0);
            // Centred at the top of the screen until the user moves it, then where it was left.
            final boolean[] centered = {!mPrefs.contains(PartsPrefs.PERF_OVERLAY_X)};
            if (centered[0]) {
                // In the top left corner until the user moves it.
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = Math.round(12 * density);
                lp.y = Math.round(8 * density);
            } else {
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = mPrefs.getInt(PartsPrefs.PERF_OVERLAY_X, 0);
                lp.y = mPrefs.getInt(PartsPrefs.PERF_OVERLAY_Y, 0);
            }
            view.setOnTouchListener(new View.OnTouchListener() {
                private float mDownX;
                private float mDownY;
                private int mStartX;
                private int mStartY;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            if (centered[0]) {
                                // From now on it is placed by coordinates.
                                final int[] location = new int[2];
                                v.getLocationOnScreen(location);
                                lp.gravity = Gravity.TOP | Gravity.START;
                                lp.x = location[0];
                                lp.y = location[1];
                                centered[0] = false;
                            }
                            mDownX = event.getRawX();
                            mDownY = event.getRawY();
                            mStartX = lp.x;
                            mStartY = lp.y;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            // Stays inside the screen.
                            final DisplayMetrics metrics = v.getResources().getDisplayMetrics();
                            lp.x = Math.max(0, Math.min(metrics.widthPixels - v.getWidth(),
                                    mStartX + Math.round(event.getRawX() - mDownX)));
                            lp.y = Math.max(0, Math.min(metrics.heightPixels - v.getHeight(),
                                    mStartY + Math.round(event.getRawY() - mDownY)));
                            mWindowManager.updateViewLayout(v, lp);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            mPrefs.edit().putInt(PartsPrefs.PERF_OVERLAY_X, lp.x)
                                    .putInt(PartsPrefs.PERF_OVERLAY_Y, lp.y).apply();
                            return true;
                        default:
                            return true;
                    }
                }
            });
            mWindowManager.addView(view, lp);
            mView = view;
            mThread = new HandlerThread("PerfOverlay");
            mThread.start();
            mWorker = new Handler(mThread.getLooper());
            mWorker.post(mSample);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot show the performance overlay", e);
            mView = null;
        }
    }

    private void hide() {
        if (mWorker != null) mWorker.removeCallbacks(mSample);
        if (mThread != null) mThread.quitSafely();
        mThread = null;
        mWorker = null;
        if (mView != null) {
            try {
                mWindowManager.removeView(mView);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot remove the performance overlay", e);
            }
            mView = null;
        }
    }

    // ---- sampling

    private final Runnable mSample = new Runnable() {
        @Override
        public void run() {
            CharSequence text;
            try {
                text = sample();
            } catch (RuntimeException e) {
                // A broken reading must not take the whole app down.
                Log.w(TAG, "Performance overlay sample failed", e);
                text = "?";
            }
            final CharSequence shown = text;
            mMain.post(() -> {
                if (mView != null) mView.setText(shown);
            });
            final Handler worker = mWorker;
            if (worker != null) worker.postDelayed(this, PERIOD_MS);
        }
    };

    private static final int GREEN = 0xFF7DFFA8;
    private static final int YELLOW = 0xFFFFD166;
    private static final int RED = 0xFFFF7B72;
    private static final int DIM = 0x80FFFFFF;
    /** Seconds of frame rate kept to know what the game reaches. */
    private static final int PEAK_SAMPLES = 60;   // 30 s at two readings a second
    private final int[] mFpsHistory = new int[PEAK_SAMPLES];
    private int mFpsIndex;

    /** The frame rate the game reaches: the 75th percentile of the last 30 seconds that drew. */
    private int peakFps(int fps) {
        mFpsHistory[mFpsIndex++ % PEAK_SAMPLES] = Math.max(0, fps);
        final int[] drawn = new int[PEAK_SAMPLES];
        int count = 0;
        for (int sample : mFpsHistory) if (sample > 0) drawn[count++] = sample;
        if (count == 0) return 0;
        java.util.Arrays.sort(drawn, 0, count);
        return drawn[Math.min(count - 1, count * 3 / 4)];
    }

    private static void add(android.text.SpannableStringBuilder out, String text, int color) {
        final int start = out.length();
        out.append(text);
        out.setSpan(new android.text.style.ForegroundColorSpan(color), start, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void dot(android.text.SpannableStringBuilder out) {
        add(out, "  ·  ", DIM);
    }

    /**
     * fps · 1 % low · power · time left, each in the colour of its state:
     * fps against what the game reaches (green from 93 %, yellow from 75 %), 1 % low against the
     * fps (green from 80 %, yellow from 60 %: under that the game stutters), power drawn (green
     * under 7 W, yellow under 11 W; green when the battery gains), time left (green from 2 h,
     * yellow from 1 h; green when charging or on the cable).
     */
    private CharSequence sample() {
        final android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        final int fps = foregroundFps();
        final int peak = peakFps(fps);
        if (fps < 0) {
            add(out, "-- fps", Color.WHITE);
        } else {
            add(out, fps + " fps", peak <= 0 || fps >= peak * 0.93f ? GREEN
                    : fps >= peak * 0.75f ? YELLOW : RED);
        }
        dot(out);
        final int low = lastLowFps();
        if (fps <= 0 || low <= 0) {
            add(out, "low --", Color.WHITE);
        } else {
            add(out, "low " + low, low >= fps * 0.8f ? GREEN : low >= fps * 0.6f ? YELLOW : RED);
        }
        dot(out);
        final BatteryInfo power = BatteryInfo.read();
        final double drawn = -power.watts;
        add(out, power.signedWatts(), drawn <= 0 || drawn < 7 ? GREEN : drawn < 11 ? YELLOW : RED);
        dot(out);
        final String timeLeft = power.timeLeft();
        if (timeLeft != null) {
            add(out, timeLeft, power.minutesLeft >= 120 ? GREEN
                    : power.minutesLeft >= 60 ? YELLOW : RED);
        } else {
            final String state = power.state();
            add(out, state != null ? state : "--", state != null ? GREEN : Color.WHITE);
        }
        return out;
    }

    static long readLong(String path, long fallback) {
        try {
            final String text = new String(Files.readAllBytes(new File(path).toPath()),
                    StandardCharsets.UTF_8).trim();
            final StringBuilder digits = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (c == '-' || Character.isDigit(c)) {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            return Long.parseLong(digits.toString());
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    static long maxCpuKhz() {
        long max = 0;
        final File[] policies = new File("/sys/devices/system/cpu/cpufreq").listFiles();
        if (policies == null) return 0;
        for (File policy : policies) {
            max = Math.max(max, readLong(policy.getPath() + "/scaling_cur_freq", 0));
        }
        return max;
    }

    /** Frames per second shown by the app in the foreground, -1 when they cannot be known. */
    synchronized int foregroundFps() {
        String pkg = null;
        try {
            // The tasks of every screen are listed, the bottom screen launcher first at times:
            // the one that counts is the top task of the main screen.
            final List<ActivityManager.RunningTaskInfo> tasks = mContext.getSystemService(
                    ActivityManager.class).getRunningTasks(10);
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task.displayId == Display.DEFAULT_DISPLAY && task.topActivity != null) {
                    pkg = task.topActivity.getPackageName();
                    break;
                }
            }
        } catch (RuntimeException e) {
            return -1;
        }
        return pkg == null ? -1 : fpsOf(pkg);
    }

    /**
     * Frames per second shown by an app, on whichever screen it is (a game opened on the bottom
     * screen too). Several controllers ask every second (GPU floor, game CPU, metrics, overlay):
     * each reading starts dumpsys processes, so one reading of an app serves them all for a moment.
     */
    synchronized int fpsOf(String pkg) {
        final long asked = System.nanoTime() / 1_000_000;
        if (pkg.equals(mFpsPackage) && asked - mFpsTime < FPS_CACHE_MS) return mFps;
        mFps = measure(pkg);
        mFpsPackage = pkg;
        mFpsTime = asked;
        return mFps;
    }

    private static final long FPS_CACHE_MS = 900;
    private int mFps = -1;
    private String mFpsPackage;
    private long mFpsTime;

    private int measure(String pkg) {
        int best = 0;
        final long now = System.nanoTime() / 1_000_000;
        if (!pkg.equals(mLayersPackage) || now - mLayersTime > LAYERS_REFRESH_MS) {
            mLayers = layersOf(pkg);
            mLayersPackage = pkg;
            mLayersTime = now;
        }
        int low = 0;
        for (String layer : mLayers) {
            final int[] layerLow = new int[1];
            final int fps = fpsOfLayer(layer, layerLow);
            if (fps > best) {
                best = fps;
                low = layerLow[0];
            }
        }
        mLastLow = low;
        return best;
    }

    private volatile int mLastLow;

    /** The "1 % low" of the last reading of the frame rate (frames per second, 0 when unknown). */
    int lastLowFps() {
        return mLastLow;
    }

    /** Names of the layers of the package (window, SurfaceView, BLAST buffer queues). */
    private static List<String> layersOf(String pkg) {
        final List<String> layers = new ArrayList<>();
        for (String line : run("dumpsys", "SurfaceFlinger", "--list")) {
            String name = line.trim();
            if (name.startsWith("RequestedLayerState{")) {
                // "RequestedLayerState{<name> parentId=..."
                final Matcher matcher = REQUESTED_LAYER.matcher(name);
                if (!matcher.matches()) continue;
                name = matcher.group(1);
            }
            if (name.contains(pkg) && name.indexOf('#') > 0 && !name.startsWith("ActivityRecord")
                    && !name.startsWith("Task") && !name.contains("ActivityRecordInputSink")) {
                layers.add(name);
            }
        }
        return layers;
    }

    /**
     * Frames per second of a layer over the last second; {@code low[0]} gets the 1 % low: the
     * frame rate of the slowest 1 % of the frames of the history SurfaceFlinger keeps (about
     * 2 seconds at 60 fps).
     */
    private static int fpsOfLayer(String layer, int[] low) {
        final List<String> lines = run("dumpsys", "SurfaceFlinger", "--latency", layer);
        if (lines.size() < 2) return 0;
        final List<Long> presented = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            final String[] columns = lines.get(i).trim().split("\\s+");
            if (columns.length < 2) continue;
            try {
                final long time = Long.parseLong(columns[1]);
                // Frames that were not presented are reported as 0 or Long.MAX_VALUE.
                if (time > 0 && time < Long.MAX_VALUE / 2) presented.add(time);
            } catch (NumberFormatException e) {
                // not a frame line
            }
        }
        if (presented.isEmpty()) return 0;
        long newest = 0;
        for (long time : presented) newest = Math.max(newest, time);
        // The last second of the animation, counted from the newest frame. When the app has
        // stopped drawing the newest frame is old: it is not drawing, so 0.
        if (System.nanoTime() - newest > 2 * WINDOW_NS) return 0;
        int count = 0;
        for (long time : presented) {
            if (newest - time < WINDOW_NS) count++;
        }
        java.util.Collections.sort(presented);
        final List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < presented.size(); i++) gaps.add(presented.get(i) - presented.get(i - 1));
        if (gaps.size() >= 20) {
            java.util.Collections.sort(gaps, java.util.Collections.reverseOrder());
            final long slowest = gaps.get(Math.min(gaps.size() - 1, gaps.size() / 100));
            if (slowest > 0) low[0] = (int) Math.round(1e9 / slowest);
        }
        return count;
    }

    private static List<String> run(String... command) {
        final List<String> lines = new ArrayList<>();
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (InputStream in = process.getInputStream();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "Cannot run " + command[0], e);
        }
        return lines;
    }
}
