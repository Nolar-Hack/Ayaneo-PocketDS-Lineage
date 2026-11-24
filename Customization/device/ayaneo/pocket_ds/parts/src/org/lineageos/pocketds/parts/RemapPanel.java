/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.Intent;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * What a button of the console sends, under the drawing: four kinds of choice (controller button,
 * keyboard key, macro, system key), a drawn keyboard in four pages with the modifiers above it,
 * and the turbo folded under "Options".
 */
final class RemapPanel {
    /** What the page needs from the activity that holds it. */
    interface Host {
        SparseIntArray map();

        SparseIntArray turbo();

        /** The remap changed: save it. */
        void mapChanged();

        void turboChanged();

        /** Draw the panel again. */
        void refresh();
    }

    private static final String[] KINDS = {"Controller", "Keyboard", "Macro", "System"};
    private static final String[] KIND_ICONS = {"🎮", "⌨️", "⚙️", "🏠"};
    private static final String[] KIND_HINTS = {"A, B, L1, Start…", "Every key",
        "A series of keys", "Back, Home, volume…"};

    private final Context mContext;
    private final Ui mUi;
    private final Host mHost;

    // what is shown, kept while the same button stays selected
    private int mScan = -1;
    private int mKind;
    private int mPage;
    private int mMods;
    private boolean mOptionsOpen;

    RemapPanel(Context context, Ui ui, Host host) {
        mContext = context;
        mUi = ui;
        mHost = host;
    }

    private int dp(float v) {
        return mUi.dp(v);
    }

    private static int kindOf(int code) {
        if (code < 0) return 0;
        final int base = ControllerProfiles.baseOf(code);
        if (Macros.isMacroCode(base)) return 2;
        for (int i = 0; i < ControllerProfiles.TARGET_CODES.length; i++) {
            if (ControllerProfiles.TARGET_CODES[i] == base) return i < 13 ? 0 : 3;
        }
        return 1;
    }

    void build(LinearLayout box, ConsoleMapView.Region region) {
        final int now = mHost.map().get(region.scan, -1);
        if (mScan != region.scan) {
            mScan = region.scan;
            mKind = kindOf(now);
            mPage = 0;
            mMods = ControllerProfiles.modsOf(now);
            mOptionsOpen = false;
        }
        header(box, region, now);
        tiles(box);
        switch (mKind) {
            case 0:
                targets(box, region, now, 0, 13, "The button works as another button of the "
                        + "controller.");
                break;
            case 1:
                keyboard(box, region, now);
                break;
            case 2:
                macros(box, region, now);
                break;
            default:
                targets(box, region, now, 13, ControllerProfiles.TARGET_CODES.length,
                        "Keys the system understands. \"Nothing\" turns the button off.");
                break;
        }
        options(box, region);
    }

    // ---- the top line: the button, what it sends, back to normal

    private void header(LinearLayout box, ConsoleMapView.Region region, int now) {
        final LinearLayout line = new LinearLayout(mContext);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        final TextView chip = mUi.chip(region.name, Ui.sAccent);
        line.addView(chip);
        final TextView sends = mUi.label(now < 0 ? "sends its own key" : "sends "
                + ControllerProfiles.targetName(mContext, now), 17, mUi.text, true);
        final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(12);
        line.addView(sends, sp);
        if (now >= 0) {
            line.addView(mUi.pill("Back to normal", Ui.GREEN, false, v -> {
                mHost.map().delete(region.scan);
                mMods = 0;
                mHost.mapChanged();
                mHost.refresh();
            }));
        }
        box.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---- the four kinds

    private void tiles(LinearLayout box) {
        final LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < KINDS.length; i++) {
            final int kind = i;
            final boolean on = mKind == i;
            final LinearLayout tile = new LinearLayout(mContext);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(14), dp(12), dp(14), dp(12));
            tile.setBackground(on ? mUi.outlined(Ui.alpha(Ui.sAccent, 0x33), Ui.sAccent, 14)
                    : mUi.outlined(Ui.alpha(Ui.sAccent, 0x0F), Ui.alpha(Ui.sAccent, 0x40), 14));
            tile.addView(mUi.label(KIND_ICONS[i], 20, mUi.text, false));
            tile.addView(mUi.label(KINDS[i], 15, on ? Ui.sAccent : mUi.text, true));
            tile.addView(mUi.label(KIND_HINTS[i], 12, mUi.textDim, false));
            tile.setOnClickListener(v -> {
                mKind = kind;
                mHost.refresh();
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < KINDS.length - 1 ? dp(8) : 0;
            row.addView(tile, lp);
        }
        final LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        box.addView(row, rp);
    }

    private void hint(LinearLayout box, String text, int topDp) {
        final TextView view = mUi.label(text, 13, mUi.textDim, false);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        box.addView(view, lp);
    }

    /** Controller buttons or system keys: pills, five to a line. */
    private void targets(LinearLayout box, ConsoleMapView.Region region, int now, int from, int to,
            String text) {
        hint(box, text, 12);
        LinearLayout line = null;
        for (int i = from; i < to; i++) {
            if ((i - from) % 5 == 0) {
                line = new LinearLayout(mContext);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(10);
                box.addView(line, lp);
            }
            final int code = ControllerProfiles.TARGET_CODES[i];
            final TextView pill = mUi.pill(ControllerProfiles.TARGET_NAMES[i], Ui.sAccent,
                    now >= 0 && now == code, v -> choose(region, code));
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pp.rightMargin = dp(8);
            line.addView(pill, pp);
        }
    }

    private void choose(ConsoleMapView.Region region, int code) {
        mHost.map().put(region.scan, code);
        mHost.mapChanged();
        mHost.refresh();
    }

    // ---- the keyboard: modifiers, four pages, the drawn keys

    private void keyboard(LinearLayout box, ConsoleMapView.Region region, int now) {
        final int base = ControllerProfiles.baseOf(now);
        final boolean keyChosen = now >= 0 && kindOf(now) == 1;
        hint(box, "With (optional): tick the modifiers, then touch the key. Ctrl + S on one "
                + "button, for instance.", 12);
        final LinearLayout mods = new LinearLayout(mContext);
        mods.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < ControllerProfiles.MOD_NAMES.length; i++) {
            final int bit = 1 << i;
            final TextView pill = mUi.pill(ControllerProfiles.MOD_NAMES[i], Ui.sAccent,
                    (mMods & bit) != 0, v -> {
                        mMods ^= bit;
                        // a key already chosen takes the change at once
                        if (keyChosen) {
                            mHost.map().put(region.scan,
                                    ControllerProfiles.withMods(base, mMods));
                            mHost.mapChanged();
                        }
                        mHost.refresh();
                    });
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pp.rightMargin = dp(8);
            mods.addView(pill, pp);
        }
        final LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(8);
        box.addView(mods, mp);

        final LinearLayout pages = new LinearLayout(mContext);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Keys.PAGES.length; i++) {
            final int page = i;
            final TextView pill = mUi.pill(Keys.PAGES[i], Ui.sAccent, mPage == i, v -> {
                mPage = page;
                mHost.refresh();
            });
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pp.rightMargin = dp(8);
            pages.addView(pill, pp);
        }
        final LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(12);
        box.addView(pages, pl);

        for (String[] row : Keys.rows(mPage)) {
            final LinearLayout line = new LinearLayout(mContext);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String spec : row) {
                final String[] parts = spec.split(":");
                final float weight = parts.length > 1 ? Float.parseFloat(parts[1]) : 1f;
                final Keys.Key key = "-".equals(parts[0]) ? null : Keys.byId(parts[0]);
                final LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0,
                        dp(46), weight);
                kp.rightMargin = dp(5);
                if (key == null) {
                    line.addView(new View(mContext), kp);
                    continue;
                }
                final boolean on = keyChosen && key.code == base;
                final TextView cap = mUi.label(Keys.cap(key), 13, on ? Ui.sAccent : mUi.text,
                        true);
                cap.setGravity(Gravity.CENTER);
                cap.setSingleLine(true);
                cap.setBackground(on ? mUi.outlined(Ui.alpha(Ui.sAccent, 0x33), Ui.sAccent, 9)
                        : mUi.outlined(Ui.alpha(Ui.sAccent, 0x0F), Ui.alpha(Ui.sAccent, 0x40), 9));
                cap.setOnClickListener(v -> choose(region,
                        ControllerProfiles.withMods(key.code, mMods)));
                line.addView(cap, kp);
            }
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            box.addView(line, lp);
        }
    }

    // ---- macros

    private void macros(LinearLayout box, ConsoleMapView.Region region, int now) {
        hint(box, "The button plays a series of keys, one after the other.", 12);
        final List<Macros.Macro> list = Macros.all(mContext);
        for (Macros.Macro m : list) {
            final boolean on = now >= 0 && ControllerProfiles.baseOf(now) == Macros.codeOf(m.id);
            final LinearLayout row = new LinearLayout(mContext);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(on ? mUi.outlined(Ui.alpha(Ui.sAccent, 0x33), Ui.sAccent, 12)
                    : mUi.outlined(Ui.alpha(Ui.sAccent, 0x0F), Ui.alpha(Ui.sAccent, 0x40), 12));
            row.addView(mUi.label(m.name, 15, on ? Ui.sAccent : mUi.text, true));
            row.addView(mUi.label(m.script.trim().replaceAll("\\s+", " "), 12, mUi.textDim,
                    false));
            row.setOnClickListener(v -> choose(region, Macros.codeOf(m.id)));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            box.addView(row, lp);
        }
        final LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        box.addView(mUi.pill(list.isEmpty() ? "Write the first macro" : "Write or edit macros",
                Ui.GREEN, false, v -> mContext.startActivity(
                        new Intent(mContext, MacrosActivity.class))), np);
    }

    // ---- options: turbo, folded

    private void options(LinearLayout box, ConsoleMapView.Region region) {
        final int rate = mHost.turbo().get(region.scan, 0);
        final LinearLayout fold = new LinearLayout(mContext);
        fold.setOrientation(LinearLayout.HORIZONTAL);
        fold.setGravity(Gravity.CENTER_VERTICAL);
        fold.setPadding(dp(14), dp(12), dp(14), dp(12));
        fold.setBackground(mUi.outlined(Ui.alpha(Ui.sAccent, 0x0F), Ui.alpha(Ui.sAccent, 0x40),
                12));
        fold.addView(mUi.label("Options", 15, mUi.text, true));
        final TextView summary = mUi.label(rate == 0 ? "Turbo off" : "Turbo " + rate + " / s", 14,
                mUi.textDim, false);
        final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(12);
        fold.addView(summary, sp);
        fold.addView(mUi.label(mOptionsOpen ? "⌃" : "⌄", 16, mUi.textDim, true));
        fold.setOnClickListener(v -> {
            mOptionsOpen = !mOptionsOpen;
            mHost.refresh();
        });
        final LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(16);
        box.addView(fold, fp);
        if (!mOptionsOpen) return;
        hint(box, "Turbo: hold " + region.name + " and it presses itself again and again. Pick "
                + "how fast.", 10);
        LinearLayout rates = null;
        for (int i = 0; i < ControllerProfiles.TURBO_RATES.length; i++) {
            if (i % 4 == 0) {
                rates = new LinearLayout(mContext);
                final LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.topMargin = dp(10);
                box.addView(rates, rl);
            }
            final int value = ControllerProfiles.TURBO_RATES[i];
            final TextView pill = mUi.pill(value == 0 ? "Off" : value + " / s", Ui.sAccent,
                    value == rate, v -> {
                        if (value == 0) {
                            mHost.turbo().delete(region.scan);
                        } else {
                            mHost.turbo().put(region.scan, value);
                        }
                        mHost.turboChanged();
                        mHost.refresh();
                    });
            final LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pp.rightMargin = dp(8);
            rates.addView(pill, pp);
        }
    }
}
