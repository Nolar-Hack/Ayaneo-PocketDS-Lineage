/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One root shell kept open (KernelSU's su, granted to this app by its profile), to write the
 * system files that only root can write (GPU clocks, fan). Commands are sent as lines; nothing is
 * read back, the output is drained so that the shell never blocks.
 */
final class RootShell {
    private static final String TAG = "PocketDSRoot";

    private static Process sProcess;
    private static OutputStream sInput;

    private RootShell() {}

    /** Runs the commands, one per line; false when there is no root shell. */
    static synchronized boolean run(String... commands) {
        try {
            if (sProcess == null || !isAlive(sProcess)) start();
            for (String command : commands) {
                sInput.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            }
            sInput.flush();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Root command failed", e);
            stop();
            return false;
        }
    }

    private static boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static void start() throws IOException {
        final Process process = new ProcessBuilder("su").redirectErrorStream(true).start();
        sProcess = process;
        sInput = process.getOutputStream();
        final Thread drain = new Thread(() -> {
            final byte[] buffer = new byte[512];
            try (InputStream out = process.getInputStream()) {
                while (out.read(buffer) >= 0) {
                    // nothing to keep
                }
            } catch (IOException e) {
                // the shell is gone
            }
        }, "PocketDSRootDrain");
        drain.setDaemon(true);
        drain.start();
    }

    private static void stop() {
        if (sProcess != null) sProcess.destroy();
        sProcess = null;
        sInput = null;
    }
}
