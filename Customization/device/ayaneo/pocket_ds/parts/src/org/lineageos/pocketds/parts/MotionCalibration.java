/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Calibration of the gyroscope bias. With the console flat and still the gyroscope must read
 * zero; what it reads is its bias. The sensor stack (Qualcomm SEE) subtracts a factory bias that
 * it keeps in the persist partition (sensors registry); on this console the factory values were
 * never filled in, so the gyroscope drifts. The measure is taken on the output of the sensor,
 * which already has the stored bias removed, so the new bias is the stored one plus the mean.
 * The registry is only read again by the sensors at boot: a restart applies a new bias.
 */
final class MotionCalibration implements SensorEventListener {
    private static final String TAG = "PocketDSMotion";

    private static final String KEY = "icm4x6xx_0_platform.gyro.fac_cal.bias";
    private static final String DIR = "/mnt/vendor/persist/sensors/registry/registry/";
    private static final String FILE = DIR + KEY;
    static final long MEASURE_MS = 10000;
    // Largest spread of a reading, in rad/s, and largest wobble of gravity, in m/s², for the
    // console to count as still; and the largest bias that is believed (about 3 °/s).
    private static final float MAX_SPREAD = 0.04f;
    private static final float MAX_ACCEL_STD = 0.06f;
    private static final float MAX_BIAS = 0.05f;

    interface Listener {
        void onProgress(int percent);

        void onDone(Result result);
    }

    static final class Result {
        boolean ok;
        String problem;
        /** What the gyroscope reads, the console being still (rad/s, x y z). */
        final float[] mean = new float[3];
        /** The bias to store: the one in use plus the mean. */
        final float[] bias = new float[3];
    }

    private final SensorManager mSensors;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Listener mListener;
    private long mStart;
    private boolean mRunning;
    private final double[] mSum = new double[3];
    private final float[] mMin = new float[3];
    private final float[] mMax = new float[3];
    private final double[] mAccelSum = new double[3];
    private final double[] mAccelSq = new double[3];
    private long mGyroCount;
    private long mAccelCount;
    private float[] mInUse;

    MotionCalibration(Context context) {
        mSensors = context.getSystemService(SensorManager.class);
    }

    boolean available() {
        return mSensors != null && mSensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
    }

    /** Measures for {@link #MEASURE_MS}; the bias in use is read first. */
    void start(Listener listener) {
        if (mRunning) return;
        mListener = listener;
        for (int i = 0; i < 3; i++) {
            mSum[i] = 0;
            mAccelSum[i] = 0;
            mAccelSq[i] = 0;
            mMin[i] = Float.MAX_VALUE;
            mMax[i] = -Float.MAX_VALUE;
        }
        mGyroCount = 0;
        mAccelCount = 0;
        new Thread(() -> {
            final float[] inUse = readBias();
            mMain.post(() -> {
                mInUse = inUse == null ? new float[3] : inUse;
                mRunning = true;
                mStart = System.currentTimeMillis();
                mSensors.registerListener(this, mSensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                        SensorManager.SENSOR_DELAY_GAME);
                final Sensor accel = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accel != null) {
                    mSensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
                }
                mMain.post(mTick);
            });
        }, "PocketDSMotionRead").start();
    }

    void cancel() {
        if (!mRunning) return;
        mRunning = false;
        mSensors.unregisterListener(this);
        mMain.removeCallbacks(mTick);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            final long elapsed = System.currentTimeMillis() - mStart;
            if (elapsed >= MEASURE_MS) {
                finish();
                return;
            }
            mListener.onProgress((int) (elapsed * 100 / MEASURE_MS));
            mMain.postDelayed(this, 250);
        }
    };

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mRunning) return;
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            mGyroCount++;
            for (int i = 0; i < 3; i++) {
                final float v = event.values[i];
                mSum[i] += v;
                mMin[i] = Math.min(mMin[i], v);
                mMax[i] = Math.max(mMax[i], v);
            }
        } else {
            mAccelCount++;
            for (int i = 0; i < 3; i++) {
                mAccelSum[i] += event.values[i];
                mAccelSq[i] += (double) event.values[i] * event.values[i];
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void finish() {
        cancel();
        final Result result = new Result();
        if (mGyroCount < 100) {
            result.problem = "The gyroscope sent no data";
        } else {
            boolean still = true;
            for (int i = 0; i < 3; i++) {
                result.mean[i] = (float) (mSum[i] / mGyroCount);
                if (mMax[i] - mMin[i] > MAX_SPREAD) still = false;
            }
            if (mAccelCount > 20) {
                for (int i = 0; i < 3; i++) {
                    final double mean = mAccelSum[i] / mAccelCount;
                    final double std = Math.sqrt(Math.max(0, mAccelSq[i] / mAccelCount - mean * mean));
                    if (std > MAX_ACCEL_STD) still = false;
                }
            }
            if (!still) {
                result.problem = "The console moved: put it flat on a steady surface and try again";
            } else {
                boolean sane = true;
                for (int i = 0; i < 3; i++) {
                    result.bias[i] = mInUse[i] + result.mean[i];
                    if (Math.abs(result.bias[i]) > MAX_BIAS) sane = false;
                }
                if (!sane) {
                    result.problem = "The reading is too large to be a bias: check that nothing"
                            + " moves the console";
                } else {
                    result.ok = true;
                }
            }
        }
        mListener.onDone(result);
    }

    // ---- the stored bias (root: the registry belongs to the sensors)

    /** The stored bias (x y z, rad/s), or null when it cannot be read. Not on the main thread. */
    static float[] readBias() {
        try {
            final String json = su("cat " + FILE).trim();
            final JSONObject root = new JSONObject(json).getJSONObject(KEY);
            final float[] bias = new float[3];
            final String[] axes = {"x", "y", "z"};
            for (int i = 0; i < 3; i++) {
                bias[i] = Float.parseFloat(root.getJSONObject(axes[i]).getString("data"));
            }
            return bias;
        } catch (Exception e) {
            Log.w(TAG, "Cannot read the gyroscope bias", e);
            return null;
        }
    }

    /** Stores the bias and reads it back to be sure; false when it did not stick. */
    static boolean writeBias(float[] bias) {
        try {
            final String json = String.format(Locale.US, "{\"%s\":{\"owner\":\"sns_icm4x6xx\","
                    + "\"x\":{\"type\":\"flt\",\"ver\":\"0\",\"data\":\"%.6f\"},"
                    + "\"y\":{\"type\":\"flt\",\"ver\":\"0\",\"data\":\"%.6f\"},"
                    + "\"z\":{\"type\":\"flt\",\"ver\":\"0\",\"data\":\"%.6f\"}}}",
                    KEY, bias[0], bias[1], bias[2]);
            // A redirection into the existing file keeps its owner and its SELinux label.
            su("printf %s '" + json + "' > " + FILE);
            final float[] back = readBias();
            if (back == null) return false;
            for (int i = 0; i < 3; i++) {
                if (Math.abs(back[i] - bias[i]) > 0.000002f) return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Cannot store the gyroscope bias", e);
            return false;
        }
    }

    private static String su(String command) throws Exception {
        final Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        process.getOutputStream().close();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            final byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        process.waitFor();
        return out.toString("UTF-8");
    }

    static String describe(float[] v) {
        return String.format(Locale.US, "x %.4f · y %.4f · z %.4f rad/s", v[0], v[1], v[2]);
    }
}
