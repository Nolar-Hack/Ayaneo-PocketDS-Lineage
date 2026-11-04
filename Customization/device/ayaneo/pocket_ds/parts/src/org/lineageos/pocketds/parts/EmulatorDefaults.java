/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Four game profiles made once, by the weight of what they emulate, and given to an emulator the
 * first time it comes to the front without a game profile:
 * Switch, PS3, Wii U and Xbox 360 on Gaming; GameCube/Wii, PS2, Dreamcast, Xbox and the PC game
 * launchers (Winlator and the like, whose games range from light to heavy) on Streaming; 3DS, DS,
 * PSP, PS Vita, PS1 and N64 on Balanced; every other emulator on ECO. Each app gets it once only: an app taken out
 * of its profile, or moved to another one, stays as the user left it, and a default profile the
 * user deleted is not made again.
 */
final class EmulatorDefaults {
    private static final String KEY_SETUP = "emu_default_setup_";
    private static final String KEY_DONE = "emu_default_done_";

    /** Name, performance profile (PerformanceProfiles.NAMES) and package words of each group. */
    private static final String[] NAMES = {
            "Switch · PS3 · Wii U · Xbox 360", "GameCube · PS2 · Dreamcast · Xbox · PC",
            "3DS · DS · PSP · Vita · PS1 · N64",
            "Other emulators"};
    private static final int[] PERF = {3, 2, 1, 0};
    private static final String[][] WORDS = {
            // Switch (Eden and its forks, Citron, Sudachi, Yuzu, Ryujinx, Skyline, Eglantine for
            // Switch 2), PS3 (aPS3e, RPCSX, ARMSX3), Wii U (Cemu), Xbox 360 (X360 Mobile, XenDroid)
            {"dev.eden", "citron", "sudachi", "yuzu", "ryujinx", "skyline", "eglantine",
                    "aenu.aps3e", "rpcsx", "armsx3", "cemu", "x360", "xendroid", "xenia"},
            // GameCube and Wii (Dolphin), PS2 (NetherSX2, AetherSX2, ARMSX2, Play!), Dreamcast
            // (Flycast, Redream), Xbox (X1 BOX, xemu, hakuX), PC games (Winlator, GameHub Lite,
            // GameNative, Starboard, Droiddeck)
            {"dolphin", "aethersx2", "nethersx2", "armsx2", "com.virtualapplications.play",
                    "flycast", "redream", "x1box", "xemu", "haku_x", "winlator", "gamehub",
                    "gamenative", "starboard", "droiddeck"},
            // 3DS (Citra, Azahar, Lime3DS, Mandarine), DS (melonDS, WatermelonDS, DraStic),
            // PSP (PPSSPP), PS Vita (Vita3K), PS1 (DuckStation, ARMSX1, ePSXe), N64 (Gopher64,
            // Mupen64Plus)
            {"citra", "azahar", "lime3ds", "mandarine", "melonds", "melondualds", "drastic",
                    "ppsspp", "vita3k", "duckstation", "com.nanodata.armsx", "epsxe",
                    "gopher64", "mupen64"},
    };

    private EmulatorDefaults() {}

    /** The group of an emulator: 0 to 3 (3 for every other emulator), or -1 for another app. */
    static int groupOf(String pkg) {
        if (!PerfHints.isEmulator(pkg)) return -1;
        final String name = pkg.toLowerCase(Locale.ROOT);
        for (int g = 0; g < WORDS.length; g++) {
            for (String word : WORDS[g]) if (name.contains(word)) return g;
        }
        return NAMES.length - 1;
    }

    /** Makes the four profiles if they were never made (they show in Profiles from then on). */
    static void createProfiles(SharedPreferences prefs) {
        // the per-app settings of before the game profiles are grouped first: the ones written
        // here would otherwise be grouped again into copies of these profiles
        GameSetups.migrate(prefs);
        for (int g = 0; g < NAMES.length; g++) setup(prefs, g);
    }

    /** An emulator seen for the first time without a game profile gets the one of its group. */
    static void assignIfNew(SharedPreferences prefs, String pkg) {
        if (pkg == null || prefs.getBoolean(KEY_DONE + pkg, false)) return;
        final int group = groupOf(pkg);
        if (group < 0) return;
        prefs.edit().putBoolean(KEY_DONE + pkg, true).apply();
        if (GameSetups.assignedId(prefs, pkg) != null) return;
        final GameSetups.Setup s = setup(prefs, group);
        if (s != null) GameSetups.assign(prefs, pkg, s.id);
    }

    /** The default profile of a group, made the first time; null once the user deleted it. */
    private static GameSetups.Setup setup(SharedPreferences prefs, int group) {
        final String id = prefs.getString(KEY_SETUP + group, null);
        if (id != null) return GameSetups.find(prefs, id);
        final GameSetups.Setup s = GameSetups.create(prefs, NAMES[group]);
        s.perf = PERF[group];
        GameSetups.update(prefs, s);
        prefs.edit().putString(KEY_SETUP + group, s.id).apply();
        return s;
    }
}
