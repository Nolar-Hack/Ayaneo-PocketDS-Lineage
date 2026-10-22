/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Backup and restore of the settings of the emulators (their data folder and the files of their
 * folder in Android/data, without the caches) in one archive of Downloads/PocketDS. Needs root:
 * the data of another app is private.
 */
final class EmulatorBackup {
    private static final String[] HINTS = {"emu", "retroarch", "ppsspp", "dolphin", "citra",
            "lime3ds", "azahar", "mandarine", "melon", "duckstation", "aethersx2", "nethersx2",
            "yuzu", "eden", "sudachi", "skyline", "vita3k", "cemu", "redream", "flycast",
            "drastic", "lemuroid", "mupen", "ryujinx", "xenia", "winlator", "scummvm"};

    private EmulatorBackup() {}

    /** The installed emulators (package names). */
    static List<String> installed(Context context) {
        final PackageManager pm = context.getPackageManager();
        final List<String> found = new ArrayList<>();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            final String name = info.packageName.toLowerCase(Locale.ROOT);
            for (String hint : HINTS) {
                if (name.contains(hint)) {
                    found.add(info.packageName);
                    break;
                }
            }
        }
        return found;
    }

    private static File folder() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "PocketDS");
    }

    /** The archives already made, the newest first. */
    static List<File> archives() {
        final List<File> list = new ArrayList<>();
        final File[] files = folder().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("emulators-") && f.getName().endsWith(".tar.gz")) {
                    list.add(f);
                }
            }
        }
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    /** Backs the given emulators up; returns the archive name or null when it failed. */
    static String backup(List<String> packages) {
        if (packages.isEmpty()) return null;
        final String name = "emulators-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new Date()) + ".tar.gz";
        final StringBuilder paths = new StringBuilder();
        final StringBuilder excludes = new StringBuilder();
        for (String pkg : packages) {
            paths.append(" data/data/").append(pkg);
            // The files of the emulator in Android/data (seen without the storage layer).
            if (new File("/data/media/0/Android/data/" + pkg).exists()) {
                paths.append(" data/media/0/Android/data/").append(pkg);
            }
        }
        excludes.append(" --exclude=cache --exclude=code_cache --exclude=no_backup");
        final File dir = folder();
        final String archive = new File(dir, name).getAbsolutePath();
        final boolean ok = RootShell.run("mkdir -p " + dir.getAbsolutePath(),
                "tar -czf '" + archive + "'" + excludes + " -C / " + paths,
                "chmod 666 '" + archive + "'", "test -s '" + archive + "'");
        return ok ? name : null;
    }

    /** Restores an archive over the data of the emulators (they are stopped first). */
    static boolean restore(Context context, File archive) {
        final List<String> packages = new ArrayList<>(installed(context));
        final List<String> commands = new ArrayList<>();
        for (String pkg : packages) commands.add("am force-stop " + pkg);
        commands.add("tar -xzf '" + archive.getAbsolutePath() + "' -C /");
        return RootShell.run(commands.toArray(new String[0]));
    }
}
