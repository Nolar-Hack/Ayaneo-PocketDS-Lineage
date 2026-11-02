/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import java.util.Locale;

/**
 * The power going into or out of the battery, and the time left at this pace, for the overlay and
 * the small statistics bar. The current of the battery is positive while it charges and negative
 * while it is drained.
 */
final class BatteryInfo {
    private static final String BATTERY = "/sys/class/power_supply/battery/";
    /** Under this current (µA) either way the console runs on the cable, the battery rests. */
    private static final long RESTING_MICRO_AMPS = 50_000;

    /** Watts: positive when the battery gains energy, negative when it loses some. */
    final double watts;
    /** Minutes left at the pace of the last two minutes, or -1 when not drained or unknown. */
    final int minutesLeft;
    final boolean charging;
    final boolean onCable;

    private BatteryInfo(double watts, int minutesLeft, boolean charging, boolean onCable) {
        this.watts = watts;
        this.minutesLeft = minutesLeft;
        this.charging = charging;
        this.onCable = onCable;
    }

    static BatteryInfo read() {
        final long microAmps = PerfOverlayController.readLong(BATTERY + "current_now", 0);
        final long microVolts = PerfOverlayController.readLong(BATTERY + "voltage_now", 0);
        final double watts = microAmps / 1e6 * microVolts / 1e6;
        if (microAmps > RESTING_MICRO_AMPS) return new BatteryInfo(watts, -1, true, false);
        if (microAmps > -RESTING_MICRO_AMPS) return new BatteryInfo(watts, -1, false, true);
        final long microAmpHours = PerfOverlayController.readLong(BATTERY + "charge_counter", 0);
        if (microAmpHours <= 0 || microVolts <= 0) return new BatteryInfo(watts, -1, false, false);
        // The average power of the last two minutes: one reading jumps too much (a menu, a
        // loading screen).
        double sum = 0;
        int count = 0;
        final MetricsRecorder recorder = MetricsRecorder.get();
        if (recorder != null) {
            for (float w : recorder.series(MetricsRecorder.BATTERY, 120)) {
                if (w > 0) {
                    sum += w;
                    count++;
                }
            }
        }
        final double drawn = count > 0 ? sum / count : Math.abs(watts);
        if (drawn < 0.2) return new BatteryInfo(watts, -1, false, false);
        final double hours = (microAmpHours / 1e6) * (microVolts / 1e6) / drawn;
        return new BatteryInfo(watts, (int) Math.round(hours * 60), false, false);
    }

    /** "+5.2 W" while the battery gains energy, "-7.1 W" while it loses some. */
    String signedWatts() {
        return String.format(Locale.US, "%+.1f W", watts);
    }

    /** "2 h 10", or null when the battery is not being drained. */
    String timeLeft() {
        if (minutesLeft < 0) return null;
        return String.format(Locale.US, "%d h %02d", minutesLeft / 60, minutesLeft % 60);
    }

    /** What the battery does, in a word or two, when no time is left to show. */
    String state() {
        return charging ? "charging" : onCable ? "on cable" : null;
    }
}
