/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * The USB-C port of the console is dual role: it receives power from a charger, but it can also
 * give power to what is plugged in (to feed a USB device). Plugged to a computer that is off or
 * asleep, the console may take the role of the power source and feed the cable with its own
 * battery: a night plugged to a PC that had been shut down emptied it from 61 % to 4 %. Here the
 * port goes back to the power sink role when the console feeds the cable and no USB device shows
 * up on it, and always when the console shuts down; a device that is really used (a controller
 * dongle, a drive, a hub) is left alone.
 */
final class UsbPowerGuard {
    private static final String TAG = "PocketDSUsbGuard";
    private static final String PORT = "/sys/class/typec/port0/";
    private static final String USB_DEVICES = "/sys/bus/usb/devices";
    private static final long POLL_MS = 5_000;
    /** Polls as a power source without any USB device before the port goes back to sink. */
    private static final int EMPTY_POLLS = 4;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mEmpty;

    UsbPowerGuard(Context context) {
        mContext = context;
    }

    void start() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!enabled()) return;
                if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                    // never leave the port feeding the cable when the console goes off
                    sink("shutdown");
                }
            }
        }, filter, null, mHandler);
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private boolean enabled() {
        return PartsPrefs.get(mContext).getBoolean(PartsPrefs.USB_POWER_GUARD, true);
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
            } catch (RuntimeException e) {
                Log.w(TAG, "poll failed", e);
            } finally {
                mHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void poll() {
        if (!enabled() || !"source".equals(powerRole())) {
            mEmpty = 0;
            return;
        }
        if (usbDevices() > 0) {
            // something is really used on the port: keep feeding it
            mEmpty = 0;
            return;
        }
        if (++mEmpty >= EMPTY_POLLS) {
            mEmpty = 0;
            sink("source without any USB device");
        }
    }

    private void sink(String why) {
        Log.i(TAG, "Power role of the USB port back to sink: " + why);
        // kept for the page of the settings: the log does not survive a restart
        PartsPrefs.get(mContext).edit().putString(PartsPrefs.USB_POWER_GUARD_LAST,
                new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date()) + " · " + why).apply();
        RootShell.run("echo sink > " + PORT + "power_role");
    }

    /** The power role the port plays now ("source" or "sink"), from "source [sink]". */
    private static String powerRole() {
        final String text = read(PORT + "power_role");
        if (text == null) return null;
        final int open = text.indexOf('[');
        final int close = text.indexOf(']');
        if (open >= 0 && close > open) return text.substring(open + 1, close);
        return text.trim();
    }

    /** USB devices on the bus (not the root hubs of the console itself). */
    private static int usbDevices() {
        final File[] entries = new File(USB_DEVICES).listFiles();
        if (entries == null) return 0;
        int count = 0;
        for (File entry : entries) {
            final String name = entry.getName();
            // "1-1", "2-1.3": a device; "usb1", "1-0:1.0": a root hub or an interface
            if (name.matches("\\d+-\\d+(\\.\\d+)*")) count++;
        }
        return count;
    }

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(new File(path).toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
