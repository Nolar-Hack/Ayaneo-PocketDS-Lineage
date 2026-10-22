/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MenuItem;
import android.widget.LinearLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

/** Live battery figures, read from the kernel (current, voltage, wear) and the power manager. */
public class BatteryStatsActivity extends Activity {
    private static final String BATTERY = "/sys/class/power_supply/battery/";
    private static final long REFRESH_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Ui mUi;
    private Ui.Row mLevel;
    private Ui.Row mPower;
    private Ui.Row mTemperature;
    private Ui.Row mHealth;
    private Ui.Row mEstimate;
    private Ui.Row mSleep;
    private Ui.Row mSuspend;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Battery stats");
        mUi = new Ui(this);
        final LinearLayout page = mUi.page(this);
        mUi.hero(page, "📊", Ui.GREEN, "Battery stats",
                "What the battery is doing right now, refreshed every 2 seconds");
        final LinearLayout now = mUi.card(page);
        mLevel = mUi.row(now, "🔋", Ui.GREEN, "Charge", "");
        mPower = mUi.row(now, "⚡", Ui.ORANGE, "Power", "");
        mEstimate = mUi.row(now, "⏳", Ui.BLUE, "Time left", "");
        mUi.section(page, "Battery");
        final LinearLayout battery = mUi.card(page);
        mTemperature = mUi.row(battery, "🌡️", Ui.RED, "Temperature", "");
        mHealth = mUi.row(battery, "❤️", Ui.PINK, "Health", "");
        mUi.section(page, "Sleep");
        final LinearLayout sleep = mUi.card(page);
        mSleep = mUi.row(sleep, "🌙", Ui.PURPLE, "Deep sleep since boot", "");
        mSuspend = mUi.row(sleep, "💤", Ui.TEAL, "Suspend", "");
        mUi.note(page, "Deep sleep is the share of the time since the boot during which the "
                + "processor was fully asleep: the higher, the better for the battery.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRefresh);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static long readLong(String name, long fallback) {
        try {
            return Long.parseLong(new String(Files.readAllBytes(Paths.get(BATTERY + name)),
                    StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    private static String readText(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void refresh() {
        final Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        final int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        final int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        final boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        final boolean full = status == BatteryManager.BATTERY_STATUS_FULL;
        String state = charging ? "Charging" : full ? "Full"
                : plugged != 0 ? "Plugged in, not charging" : "On battery";
        mUi.setSummary(mLevel, level + " %  ·  " + state);

        // current_now is in microamperes; its sign depends on the driver, so the status says
        // whether it charges or drains.
        final double milliamps = Math.abs(readLong("current_now", 0)) / 1000.0;
        final double volts = readLong("voltage_now", 0) / 1_000_000.0;
        mUi.setSummary(mPower, String.format(Locale.US, "%.0f mA  ·  %.2f V  ·  %.1f W",
                milliamps, volts, milliamps * volts / 1000.0));

        final long chargeFull = readLong("charge_full", 0);
        final long chargeDesign = readLong("charge_full_design", 0);
        String estimate = "—";
        if (!charging && !full && plugged == 0 && milliamps > 50 && chargeFull > 0) {
            final double hours = chargeFull / 1000.0 * level / 100.0 / milliamps;
            estimate = String.format(Locale.US, "about %d h %02d min at this draw",
                    (int) hours, (int) ((hours - (int) hours) * 60));
        } else if (charging) {
            final long ms = ((BatteryManager) getSystemService(BATTERY_SERVICE))
                    .computeChargeTimeRemaining();
            if (ms > 0) {
                estimate = String.format(Locale.US, "about %d h %02d min to full",
                        ms / 3_600_000, ms / 60_000 % 60);
            }
        } else if (plugged != 0) {
            estimate = "plugged in";
        }
        mUi.setSummary(mEstimate, estimate);

        final int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        mUi.setSummary(mTemperature, String.format(Locale.US, "%.1f °C", temperature / 10.0));

        final long cycles = readLong("cycle_count", -1);
        String health = healthName(battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
        if (chargeFull > 0 && chargeDesign > 0) {
            health += String.format(Locale.US, "  ·  capacity %.0f %% of new  ·  %d mAh",
                    100.0 * chargeFull / chargeDesign, chargeFull / 1000);
        }
        if (cycles >= 0) health += "  ·  " + cycles + " cycles";
        mUi.setSummary(mHealth, health);

        final long up = SystemClock.uptimeMillis();
        final long real = SystemClock.elapsedRealtime();
        mUi.setSummary(mSleep, String.format(Locale.US, "%.0f %%  ·  up %s",
                100.0 * (real - up) / Math.max(1, real), duration(real)));

        final String ok = readText("/sys/power/suspend_stats/success");
        final String fail = readText("/sys/power/suspend_stats/fail");
        final String last = readText("/sys/power/suspend_stats/last_failed_dev");
        mUi.setSummary(mSuspend, ok == null ? "not readable" : ok + " successful, " + fail
                + " failed" + (last == null || last.isEmpty() ? "" : "  ·  last failure: " + last));
    }

    private static String healthName(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheating";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }

    private static String duration(long ms) {
        final long minutes = ms / 60_000;
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }
}
