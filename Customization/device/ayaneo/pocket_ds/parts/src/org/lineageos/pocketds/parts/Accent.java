/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;

/** The accent colour of the interface follows the performance profile in use. */
final class Accent {
    /** ECO, Balanced, Streaming, Gaming, Max. */
    private static final int[] COLORS = {0xFF34C3A5, 0xFF5B9DFF, 0xFFA78BFA, 0xFFF2A33A,
            0xFFF06B5E};

    private Accent() {}

    static int of(int profile) {
        return profile >= 0 && profile < COLORS.length ? COLORS[profile] : COLORS[1];
    }

    static int current(Context context) {
        return of(PartsPrefs.getInt(PartsPrefs.get(context), PartsPrefs.PERF_PROFILE, 1));
    }

    /** A base colour pulled a little towards the accent: the background takes the profile's hue. */
    static int tint(int base, int accent, float amount) {
        final int a = base >>> 24;
        final int r = Math.round(((base >> 16) & 0xFF) * (1 - amount) + ((accent >> 16) & 0xFF) * amount);
        final int g = Math.round(((base >> 8) & 0xFF) * (1 - amount) + ((accent >> 8) & 0xFF) * amount);
        final int b = Math.round((base & 0xFF) * (1 - amount) + (accent & 0xFF) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** The same colour, very transparent: the background of a selected tile. */
    static int soft(int color) {
        return (color & 0x00FFFFFF) | 0x1F000000;
    }
}
