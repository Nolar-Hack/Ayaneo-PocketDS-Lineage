/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.SparseIntArray;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Remapping of the buttons of the console on a drawing of it: tap a button, then pick what it
 * sends. For one app (its controller setup) or, with no package, for every app.
 */
public class ControllerMapActivity extends Activity {
    static final String EXTRA_PROFILE = "profile";
    static final String EXTRA_LABEL = "label";
    private static final int MAX_CHARS = 91; // a system property value

    private Ui mUi;
    private String mProfileId;
    private ControllerProfiles.Named mNamed;
    private SharedPreferences mPrefs;
    private SparseIntArray mMap = new SparseIntArray();
    private SparseIntArray mTurbo = new SparseIntArray();   // scan code -> presses per second
    private ConsoleMapView mView;
    private LinearLayout mDetail;
    private int mSelected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mProfileId = getIntent().getStringExtra(EXTRA_PROFILE);
        mNamed = ControllerProfiles.find(PartsPrefs.get(this), mProfileId);
        mPrefs = PartsPrefs.get(this);
        mUi = new Ui(this);
        setTitle("Button remapping");
        final LinearLayout page = mUi.page(this);
        final String label = getIntent().getStringExtra(EXTRA_LABEL);
        mUi.hero(page, "🎮", Ui.PURPLE, "Button remapping",
                mNamed == null ? "For every app" : "Profile " + label);
        load();

        final LinearLayout card = mUi.card(page);
        mView = new ConsoleMapView(this, Ui.sAccent);
        mView.setMap(mMap);
        mView.setTurbo(mTurbo);
        mView.setListener(this::picked);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(mUi.dp(10), mUi.dp(10), mUi.dp(10), mUi.dp(10));
        card.addView(mView, params);

        mDetail = new LinearLayout(this);
        mDetail.setOrientation(LinearLayout.VERTICAL);
        page.addView(mDetail, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        detail(null);
        if (mNamed != null) {
            mUi.section(page, "Profile");
            mOptions = mUi.card(page);
            paintOptions();
        } else {
            mUi.section(page, "Gyroscope");
            mGyro = mUi.card(page);
            paintGyro();
            mUi.note(page, "If a game aims the wrong way when you move the console, turn the"
                    + " gyroscope round here, or in a profile to do it for some games only.");
        }
    }

    // ---- the other settings of a profile: below the drawing, the remapping comes first

    private LinearLayout mOptions;
    private LinearLayout mGyro;

    /** The gyroscope of every app: the two directions, applied at once. */
    private void paintGyro() {
        mGyro.removeAllViews();
        final boolean h = SystemProperties.getBoolean(ControllerProfiles.PROP_GYRO_H, false);
        final boolean v = SystemProperties.getBoolean(ControllerProfiles.PROP_GYRO_V, false);
        mUi.actionRow(mGyro, "↔️", Ui.BLUE, "Invert left / right", h ? "On" : "Off", () -> {
            SystemProperties.set(ControllerProfiles.PROP_GYRO_H, h ? "false" : "true");
            paintGyro();
        });
        mUi.actionRow(mGyro, "↕️", Ui.BLUE, "Invert up / down", v ? "On" : "Off", () -> {
            SystemProperties.set(ControllerProfiles.PROP_GYRO_V, v ? "false" : "true");
            paintGyro();
        });
    }
    private static final int[] ZONES = {-1, 0, 5, 10, 15, 20, 25};

    private static String choiceText(int value) {
        return value < 0 ? "Same as everywhere" : value == 1 ? "On" : "Off";
    }

    private void paintOptions() {
        mOptions.removeAllViews();
        final ControllerProfiles.Profile p = mNamed.profile;
        mUi.actionRow(mOptions, "🔀", Ui.BLUE, "Swap A ↔ B", choiceText(p.swapAb), () -> {
            p.swapAb = p.swapAb < 0 ? 1 : p.swapAb == 1 ? 0 : -1;
            saveProfile();
        });
        mUi.actionRow(mOptions, "🔀", Ui.BLUE, "Swap X ↔ Y", choiceText(p.swapXy), () -> {
            p.swapXy = p.swapXy < 0 ? 1 : p.swapXy == 1 ? 0 : -1;
            saveProfile();
        });
        mUi.actionRow(mOptions, "🎯", Ui.TEAL, "Dead zone", p.zone < 0 ? "Same as everywhere"
                : p.zone == 0 ? "None" : p.zone + " %", () -> {
            int i = 0;
            while (i < ZONES.length && ZONES[i] != p.zone) i++;
            p.zone = ZONES[(i + 1) % ZONES.length];
            saveProfile();
        });
        mUi.actionRow(mOptions, "↔️", Ui.BLUE, "Invert the gyroscope left / right",
                choiceText(p.gyroH), () -> {
            p.gyroH = p.gyroH < 0 ? 1 : p.gyroH == 1 ? 0 : -1;
            saveProfile();
        });
        mUi.actionRow(mOptions, "↕️", Ui.BLUE, "Invert the gyroscope up / down",
                choiceText(p.gyroV), () -> {
            p.gyroV = p.gyroV < 0 ? 1 : p.gyroV == 1 ? 0 : -1;
            saveProfile();
        });
        mUi.actionRow(mOptions, "✏️", Ui.ORANGE, "Rename", mNamed.name, this::rename);
        mUi.actionRow(mOptions, "🗑️", Ui.RED, "Delete this profile",
                "The apps that use it go back to the setup of every app", this::delete);
    }

    private void saveProfile() {
        ControllerProfiles.update(mPrefs, mNamed);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        paintOptions();
    }

    private void rename() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(mNamed.name);
        input.setSelection(mNamed.name.length());
        new android.app.AlertDialog.Builder(this).setTitle("Rename the profile").setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    final String name = input.getText().toString().trim();
                    if (!name.isEmpty()) mNamed.name = name;
                    saveProfile();
                }).setNegativeButton("Cancel", null).show();
    }

    private void delete() {
        new android.app.AlertDialog.Builder(this).setTitle("Delete " + mNamed.name + "?")
                .setMessage("The apps that use it go back to the setup of every app.")
                .setPositiveButton("Delete", (d, w) -> {
                    ControllerProfiles.delete(mPrefs, mNamed.id);
                    finish();
                }).setNegativeButton("Cancel", null).show();
    }

    private void load() {
        String text;
        if (mNamed == null) {
            text = SystemProperties.get(ControllerProfiles.PROP_MAP, "");
        } else {
            text = mNamed.profile.map != null ? mNamed.profile.map
                    : SystemProperties.get(ControllerProfiles.PROP_MAP, "");
        }
        mMap = ControllerProfiles.parseMap(text);
        final String turbo = mNamed != null && mNamed.profile.turbo != null ? mNamed.profile.turbo
                : SystemProperties.get(ControllerProfiles.PROP_TURBO, "");
        mTurbo = ControllerProfiles.parseMap(turbo);
    }

    private void saveTurbo() {
        final String text = ControllerProfiles.encodeMap(mTurbo);
        if (mNamed == null) {
            SystemProperties.set(ControllerProfiles.PROP_TURBO, text);
        } else {
            mNamed.profile.turbo = text;
            ControllerProfiles.update(mPrefs, mNamed);
        }
        mView.setTurbo(mTurbo);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private void save() {
        final String text = ControllerProfiles.encodeMap(mMap);
        if (text.length() > MAX_CHARS) {
            Toast.makeText(this, "Too many remaps", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mNamed == null) {
            SystemProperties.set(ControllerProfiles.PROP_MAP, text);
        } else {
            mNamed.profile.map = text;
            ControllerProfiles.update(mPrefs, mNamed);
        }
        mView.setMap(mMap);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private void picked(ConsoleMapView.Region region) {
        if (region.fixed()) {
            mSelected = 0;
            mView.setSelected(-1);
            detail(region);
            return;
        }
        mSelected = region.scan;
        mView.setSelected(region.scan);
        detail(region);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        return mUi.label(value, size, color, bold);
    }

    private void detail(ConsoleMapView.Region region) {
        mDetail.removeAllViews();
        final LinearLayout card = mUi.card(mDetail);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mUi.dp(18), mUi.dp(14), mUi.dp(18), mUi.dp(16));
        card.addView(box);
        if (region == null) {
            box.addView(text("Tap a button on the drawing to change what it sends.", 15,
                    mUi.textDim, false));
            if (mNamed != null) {
                box.addView(text("These remaps apply while an app that uses this profile is in "
                        + "front. They start from the ones set for every app.", 14, mUi.textDim,
                        false));
                box.addView(resetButton());
            }
            return;
        }
        final String button = ButtonActions.buttonOf(region.name);
        if (button != null) {
            // An extra button of the console: the user picks what it does, for a short press and
            // for a long one (600 ms).
            mView.setSelected(-1);
            mSelected = 0;
            box.addView(text(region.name, 18, mUi.text, true));
            actionPills(box, region, button, false);
            actionPills(box, region, button, true);
            return;
        }
        if (region.fixed()) {
            box.addView(text(region.name, 18, mUi.text, true));
            box.addView(text(region.note == null ? "Kept by the system" : region.name + ": "
                    + region.note + ". The system keeps this button, it cannot be remapped.", 14,
                    mUi.textDim, false));
            return;
        }
        final int now = mMap.get(region.scan, -1);
        box.addView(text(region.name + (now < 0 ? " sends its own key" : " sends "
                + targetName(now)), 18, mUi.text, true));
        box.addView(text("Pick what it should send instead:", 14, mUi.textDim, false));
        LinearLayout line = null;
        for (int i = 0; i < ControllerProfiles.TARGET_NAMES.length; i++) {
            if (i % 5 == 0) {
                line = new LinearLayout(this);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = mUi.dp(10);
                box.addView(line, lp);
            }
            final int code = ControllerProfiles.TARGET_CODES[i];
            final boolean chosen = code == now;
            final TextView pill = mUi.pill(ControllerProfiles.TARGET_NAMES[i], Ui.sAccent, chosen,
                    v -> {
                        mMap.put(region.scan, code);
                        save();
                        detail(region);
                    });
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pp.rightMargin = mUi.dp(8);
            line.addView(pill, pp);
        }
        final TextView normal = mUi.pill("Normal (remove the remap)", Ui.GREEN, false, v -> {
            mMap.delete(region.scan);
            save();
            detail(region);
        });
        final LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = mUi.dp(14);
        box.addView(normal, np);

        // Turbo: held down, the button presses itself again and again, as fast as chosen
        final int rate = mTurbo.get(region.scan, 0);
        final TextView turboTitle = text("Turbo " + (rate == 0 ? "is off" : "· " + rate
                + " presses per second"), 16, mUi.text, true);
        final LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = mUi.dp(22);
        box.addView(turboTitle, tp);
        box.addView(text("Hold " + region.name + ": it presses itself again and again while you "
                + "keep it down. Pick how fast.", 14, mUi.textDim, false));
        LinearLayout rates = null;
        for (int i = 0; i < ControllerProfiles.TURBO_RATES.length; i++) {
            if (i % 4 == 0) {
                rates = new LinearLayout(this);
                final LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.topMargin = mUi.dp(10);
                box.addView(rates, rl);
            }
            final int value = ControllerProfiles.TURBO_RATES[i];
            final TextView pill = mUi.pill(value == 0 ? "Off" : value + " / s", Ui.sAccent,
                    value == rate, v -> {
                        if (value == 0) mTurbo.delete(region.scan);
                        else mTurbo.put(region.scan, value);
                        saveTurbo();
                        detail(region);
                    });
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pp.rightMargin = mUi.dp(8);
            rates.addView(pill, pp);
        }
    }

    /** The actions of an extra button of the console, for its short or its long press. */
    private void actionPills(LinearLayout box, ConsoleMapView.Region region, String button,
            boolean longPress) {
        box.addView(text((longPress ? "Long press: " : "Short press: ") + actionName(
                ButtonActions.get(this, button, longPress)), 16, mUi.text, true),
                spaced(longPress ? 18 : 12));
        LinearLayout actions = null;
        for (int i = 0; i < ButtonActions.IDS.length; i++) {
            if (i % 2 == 0) {
                actions = new LinearLayout(this);
                final LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                al.topMargin = mUi.dp(10);
                box.addView(actions, al);
            }
            final String id = ButtonActions.IDS[i];
            final TextView pill = mUi.pill(ButtonActions.NAMES[i], Ui.sAccent,
                    id.equals(ButtonActions.get(this, button, longPress)), v -> {
                        ButtonActions.set(this, button, longPress, id);
                        detail(region);
                    });
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pp.rightMargin = mUi.dp(8);
            actions.addView(pill, pp);
        }
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = mUi.dp(topDp);
        return params;
    }

    private static String actionName(String id) {
        for (int i = 0; i < ButtonActions.IDS.length; i++) {
            if (ButtonActions.IDS[i].equals(id)) return ButtonActions.NAMES[i];
        }
        return id;
    }

    private View resetButton() {
        final TextView reset = mUi.pill("Same remaps as everywhere", Ui.ORANGE, false, v -> {
            mNamed.profile.map = null;
            ControllerProfiles.update(mPrefs, mNamed);
            load();
            mView.setMap(mMap);
            Toast.makeText(this, "This profile follows the general remapping",
                    Toast.LENGTH_SHORT).show();
        });
        final LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = mUi.dp(14);
        reset.setLayoutParams(rp);
        return reset;
    }

    private static String targetName(int code) {
        for (int i = 0; i < ControllerProfiles.TARGET_CODES.length; i++) {
            if (ControllerProfiles.TARGET_CODES[i] == code) return ControllerProfiles.TARGET_NAMES[i];
        }
        return "key " + code;
    }
}
