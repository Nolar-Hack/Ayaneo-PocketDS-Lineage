/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Application;
import android.util.Log;

/**
 * System app process: starts the Pocket DS hardware controllers and features. {@link BootReceiver}
 * and {@link KeepAliveService} bring the process up at boot and keep it alive.
 */
public class PocketDSApplication extends Application {
    private static final String TAG = "PocketDSParts";

    @Override
    public void onCreate() {
        super.onCreate();
        // A test page that was killed must not leave the buttons of the console switched off.
        android.os.SystemProperties.set(ControllerTestActivity.PROP_BUTTONS_TEST, "false");
        // One controller failing must not keep the others from starting.
        start("SecondScreen", () -> new SecondScreenController(this).start());
        start("Performance", () -> new PerformanceController(this).start());
        start("AppVolume", () -> new AppVolumeController(this).start());
        start("Screens", () -> new ScreensController(this).start());
        start("SecondScreenGestures", () -> new SecondScreenGestures(this).start());
        start("Touch", () -> new TouchController(this).start());
        start("SmartPixels", () -> new SmartPixelsController(this).start());
        start("SuspendActions", () -> new SuspendActionsController(this).start());
        start("BluetoothIdle", () -> new BluetoothIdleController(this).start());
        start("PowerOff", () -> new PowerOffController(this).start());
        start("UsbPowerGuard", () -> new UsbPowerGuard(this).start());
        start("Rules", () -> new RulesController(this).start());
        start("Foreground", () -> new ForegroundWatcher(this).start());
        start("Focus", () -> new FocusController(this).start());
        start("GameProfiles", () -> new GameProfiles(this).start());
        start("ControllerProfiles", () -> new ControllerProfiles(this).start());
        start("ChargeLimit", () -> new ChargeLimitController(this).start());
        start("Metrics", () -> new MetricsRecorder(this).start());
        start("PerfOverlay", () -> new PerfOverlayController(this).start());
        start("CrtAnimation", () -> new CrtAnimation(this).start());
        start("Updates", () -> UpdateJob.schedule(this));
        start("ObtainiumPack", () -> ObtainiumPack.copyOnce(this));
    }

    private static void start(String name, Runnable controller) {
        try {
            controller.run();
        } catch (RuntimeException e) {
            Log.e(TAG, name + " did not start", e);
        }
    }
}
