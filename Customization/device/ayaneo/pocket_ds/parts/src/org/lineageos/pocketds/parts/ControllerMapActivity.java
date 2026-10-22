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
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_LABEL = "label";
    private static final int MAX_CHARS = 91; // a system property value

    private Ui mUi;
    private String mPackage;
    private SharedPreferences mPrefs;
    private SparseIntArray mMap = new SparseIntArray();
    private ConsoleMapView mView;
    private LinearLayout mDetail;
    private int mSelected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPackage = getIntent().getStringExtra(EXTRA_PACKAGE);
        mPrefs = PartsPrefs.get(this);
        mUi = new Ui(this);
        setTitle("Button remapping");
        final LinearLayout page = mUi.page(this);
        final String label = getIntent().getStringExtra(EXTRA_LABEL);
        mUi.hero(page, "🎮", Ui.PURPLE, "Button remapping",
                mPackage == null ? "For every app" : "Only in " + label);
        load();

        final LinearLayout card = mUi.card(page);
        mView = new ConsoleMapView(this, Ui.sAccent);
        mView.setMap(mMap);
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
    }

    private void load() {
        String text;
        if (mPackage == null) {
            text = SystemProperties.get(ControllerProfiles.PROP_MAP, "");
        } else {
            final ControllerProfiles.Profile p = ControllerProfiles.of(mPrefs, mPackage);
            text = p.map != null ? p.map : SystemProperties.get(ControllerProfiles.PROP_MAP, "");
        }
        mMap = ControllerProfiles.parseMap(text);
    }

    private void save() {
        final String text = ControllerProfiles.encodeMap(mMap);
        if (text.length() > MAX_CHARS) {
            Toast.makeText(this, "Too many remaps", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mPackage == null) {
            SystemProperties.set(ControllerProfiles.PROP_MAP, text);
        } else {
            final ControllerProfiles.Profile p = ControllerProfiles.of(mPrefs, mPackage);
            p.map = text;
            ControllerProfiles.save(mPrefs, mPackage, p);
        }
        mView.setMap(mMap);
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
            if (mPackage != null) {
                box.addView(text("These remaps only apply while this app is in front. "
                        + "They start from the ones set for every app.", 14, mUi.textDim, false));
                box.addView(resetButton());
            }
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
    }

    private View resetButton() {
        final TextView reset = mUi.pill("Same remaps as everywhere", Ui.ORANGE, false, v -> {
            final ControllerProfiles.Profile p = ControllerProfiles.of(mPrefs, mPackage);
            p.map = null;
            ControllerProfiles.save(mPrefs, mPackage, p);
            load();
            mView.setMap(mMap);
            Toast.makeText(this, "This app follows the general remapping", Toast.LENGTH_SHORT)
                    .show();
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
