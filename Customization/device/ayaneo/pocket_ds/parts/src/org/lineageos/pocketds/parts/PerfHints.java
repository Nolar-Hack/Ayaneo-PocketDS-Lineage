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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

/**
 * What the stock framework told the performance service: a boost at each app launch, the refresh
 * rate of the main screen, and the performance mode for a game in front. The governors alone
 * follow the load of a game whose threads wait on each other and leave the cores slow; these
 * hints are what lets the frequencies follow the use.
 */
final class PerfHints implements ForegroundWatcher.Listener, DisplayManager.DisplayListener {
    /** The frame rates the configuration has a hint for. */
    private static final int[] FPS_TYPES = {30, 45, 60, 90, 120, 144, 180};
    private static final String KEY_AUTO_REFRESH_GONE = "auto_refresh_gone";

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private String mPackage;

    PerfHints(Context context) {
        mContext = context;
    }

    void start() {
        sInstance = this;
        GameCpuBoost.init(mContext);
        PerfBoost.cleanUp();
        // The automatic refresh rate is gone: a console that still had it goes to 60 Hz, once
        // (an automation rule may change the highest rate alone later on, and is left alone).
        final android.content.SharedPreferences prefs = PartsPrefs.get(mContext);
        if (!prefs.getBoolean(KEY_AUTO_REFRESH_GONE, false)) {
            if ("auto".equals(PerformanceProfiles.topRefresh(mContext))) {
                PerformanceProfiles.setTopRefresh(mContext, "60");
            }
            prefs.edit().putBoolean(KEY_AUTO_REFRESH_GONE, true).apply();
        }
        final DisplayManager displays = mContext.getSystemService(DisplayManager.class);
        displays.registerDisplayListener(this, mHandler);
        refreshRate();
        final ForegroundWatcher watcher = ForegroundWatcher.get();
        if (watcher != null) watcher.add(this);
        // Screen off: nothing is played, the cores are given back. Screen on: the game in front
        // gets them again.
        final IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    setGameInFront(false);
                    PerfBoost.performanceOff();
                } else {
                    final ForegroundWatcher w = ForegroundWatcher.get();
                    if (w != null) onForeground(w.current());
                }
            }
        }, screen, null, mHandler);
        mHandler.postDelayed(mBeat, BEAT_MS);
    }

    /** Every few seconds: a game in front with the screen on has the performance mode. */
    private static final long BEAT_MS = 5000;
    private final Runnable mBeat = new Runnable() {
        @Override
        public void run() {
            try {
                final ForegroundWatcher w = ForegroundWatcher.get();
                final ActivityManager.RunningTaskInfo app = w == null ? null : w.current();
                final String pkg = app == null ? null : app.baseActivity.getPackageName();
                final boolean on = mContext.getSystemService(android.os.PowerManager.class)
                        .isInteractive();
                final boolean game = pkg != null && on && gameModeOn() && isGame(pkg);
                setGameInFront(game);
                if (game) PerfBoost.performanceOn(pkg);
                else PerfBoost.performanceOff();
                PerfBoost.renew();
            } catch (RuntimeException e) {
                android.util.Log.w("PocketDSPerfHints", "heartbeat failed", e);
            } finally {
                mHandler.postDelayed(this, BEAT_MS);
            }
        }
    };

    private void refreshRate() {
        // The rate chosen for the main screen, not the mode it is in at this instant: the panel
        // drops to 60 Hz by itself when the picture is still, and the hint followed it back and
        // forth every few seconds (the scheduler window with it).
        float rate = 0;
        try {
            rate = Float.parseFloat(android.provider.Settings.System.getString(
                    mContext.getContentResolver(), "peak_refresh_rate"));
        } catch (RuntimeException e) {
            // not set: the mode of the screen
        }
        if (rate <= 0) {
            final Display display = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return;
            rate = display.getRefreshRate();
        }
        int type = FPS_TYPES[FPS_TYPES.length - 1];
        for (int candidate : FPS_TYPES) {
            if (candidate >= Math.round(rate)) {
                type = candidate;
                break;
            }
        }
        PerfBoost.fps(type);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) refreshRate();
    }

    @Override
    public void onDisplayAdded(int displayId) {}

    @Override
    public void onDisplayRemoved(int displayId) {}

    /**
     * Emulators rarely say they are games: they are known by their package name (the same words
     * as the emulator backup, and "emu" only as a word of its own: citron_emu, yuzu_emu).
     */
    private static final String[] EMULATORS = {"emulator", "retroarch", "ppsspp", "dolphin",
            "citra", "lime3ds", "azahar", "mandarine", "melonds", "duckstation", "aethersx2",
            "nethersx2", "yuzu", "dev.eden", "citron", "sudachi", "skyline", "vita3k", "cemu",
            "redream", "flycast", "drastic", "lemuroid", "mupen64", "ryujinx", "xenia",
            "winlator", "scummvm", "eglantine", "armsx", "gopher64", "epsxe"};
    // ("dev.eden", not "eden": "credentialmanager" holds those letters)
    /**
     * The emulators and PC game launchers of the Obtainium Emulation Pack
     * (RJNY/Obtainium-Emulation-Pack, categories Emulator and PC Emulation) that no word above
     * finds.
     */
    private static final java.util.Set<String> GAME_APPS = java.util.Set.of(
            "aenu.aps3e", "com.virtualapplications.play", "net.rpcsx", "gamehub.lite",
            "app.gamenative", "org.force9.starboard", "me.magnum.melondualds", "io.wip.pico8",
            "com.izzy2lost.x1box", "com.rfandango.haku_x", "io.navivani.swiff",
            "xendroid.compose", "com.droiddeck.launcher");
    private static final java.util.regex.Pattern EMU_WORD =
            java.util.regex.Pattern.compile("(^|[._])emu([._]|$)");

    static boolean isEmulator(String pkg) {
        final String name = pkg.toLowerCase(java.util.Locale.ROOT);
        if (GAME_APPS.contains(name)) return true;
        for (String word : EMULATORS) if (name.contains(word)) return true;
        return EMU_WORD.matcher(name).find();
    }

    private static PerfHints sInstance;
    private static volatile boolean sGameInFront;

    /** A game is in front with the screen on (the game mode is on for it). */
    static boolean gameInFront() {
        return sGameInFront;
    }

    private static void setGameInFront(boolean game) {
        if (game == sGameInFront) return;
        sGameInFront = game;
        ChargeLimitController.gameChanged();
    }

    /** The game mode was switched in the overlay: applied at once. */
    static void gameModeChanged() {
        final PerfHints hints = sInstance;
        if (hints == null) return;
        hints.mHandler.removeCallbacks(hints.mBeat);
        hints.mHandler.post(hints.mBeat);
    }

    private boolean gameModeOn() {
        return PartsPrefs.get(mContext).getBoolean(PartsPrefs.GAME_MODE, true);
    }

    /**
     * An app is a game when Android says so, when it is an emulator, or when it has a game
     * profile in Profiles.
     */
    private boolean isGame(String pkg) {
        return isGame(mContext, pkg);
    }

    static boolean isGame(Context context, String pkg) {
        if (pkg == null) return false;
        if (isEmulator(pkg)) return true;
        try {
            final ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (info.category == ApplicationInfo.CATEGORY_GAME
                    || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // not a game
        }
        return GameSetups.assignedId(PartsPrefs.get(context), pkg) != null;
    }

    @Override
    public void onForeground(ActivityManager.RunningTaskInfo app) {
        final String pkg = app == null ? null : app.baseActivity.getPackageName();
        final boolean game = pkg != null && isGame(pkg);
        if (pkg != null && !pkg.equals(mPackage)) PerfBoost.launch(pkg, game);
        mPackage = pkg;
        final boolean active = game && gameModeOn();
        setGameInFront(active);
        if (active) PerfBoost.performanceOn(pkg);
        else PerfBoost.performanceOff();
    }
}
