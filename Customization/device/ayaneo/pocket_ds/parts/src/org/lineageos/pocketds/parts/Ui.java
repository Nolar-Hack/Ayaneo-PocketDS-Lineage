/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The look of PocketDS Extra, in code (the screens are small and need no layout files): the dark
 * theme of the overlay (see the mockup boards 9 to 11): cards with a thin border, tinted icon
 * squares, chips for the choices, switches, tiles. Sizes are in pixels of a 1024 px wide screen
 * (the bottom screen of the console) and scale with the width of the screen.
 */
final class Ui {
    // Accent colours of the categories and of the rules (see also Rule.COLORS).
    static final int BLUE = 0xFF5B9DFF;
    /** The accent of the interface: the colour of the profile in use. */
    static int sAccent = BLUE;
    static final int PURPLE = 0xFFA78BFA;
    static final int RED = 0xFFF06B5E;
    static final int ORANGE = 0xFFF2A33A;
    static final int TEAL = 0xFF34C3A5;
    static final int GREEN = 0xFF43C27A;
    static final int PINK = 0xFFE879B9;
    static final int GREY = 0xFF98A2B3;

    static final int LINE = 0xFF2A303B;
    private static final int CHIP = 0xFF232832;
    private static final int CHIP_LINE = 0xFF2F3643;
    private static final int ON_ACCENT = 0xFF07101F;
    private static final float TEXT_SCALE = 1.22f;

    final Context context;
    final boolean night = true;
    final float density;
    final float scale;
    final int background;
    final int surface;
    final int text = 0xFFE9ECF2;
    final int textDim = 0xFF98A2B3;
    final int divider;

    Ui(Context context) {
        this.context = context;
        sAccent = Accent.current(context);
        // The base colours lean a little towards the colour of the profile.
        background = Accent.tint(0xFF0D0F13, sAccent, 0.09f);
        surface = Accent.tint(0xFF1A1E26, sAccent, 0.07f);
        divider = Accent.tint(0xFF262B34, sAccent, 0.07f);
        density = context.getResources().getDisplayMetrics().density;
        // Drawn for the bottom screen (1024 px wide); both panels have the same pixel density, so
        // on the wide top screen the sizes stay the same and the page is centred instead.
        scale = Math.min(1f, context.getResources().getDisplayMetrics().widthPixels / 1024f);
    }

    /** Pixels of the design (the layout is drawn for a 1024 px wide screen). */
    int dp(float value) {
        return Math.round(value * scale);
    }

    GradientDrawable rounded(int color, float radiusDp) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    GradientDrawable outlined(int color, int stroke, float radiusDp) {
        final GradientDrawable shape = rounded(color, radiusDp);
        shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static int darken(int color, float factor) {
        return Color.rgb((int) (Color.red(color) * factor), (int) (Color.green(color) * factor),
                (int) (Color.blue(color) * factor));
    }

    /** The switch of the design: a 50 x 28 track, blue when on, with a round knob inside. */
    static void styleSwitch(Switch toggle) {
        final float scale = Math.min(1f, toggle.getResources().getDisplayMetrics().widthPixels / 1024f);
        final int width = Math.round(50 * scale);
        final int height = Math.round(28 * scale);
        final int knob = Math.round(20 * scale);
        final int inset = Math.round(4 * scale);
        final android.graphics.drawable.StateListDrawable track =
                new android.graphics.drawable.StateListDrawable();
        track.addState(new int[] {android.R.attr.state_checked}, pill(sAccent, width, height));
        track.addState(new int[] {}, pill(LINE, width, height));
        final android.graphics.drawable.StateListDrawable thumb =
                new android.graphics.drawable.StateListDrawable();
        thumb.addState(new int[] {android.R.attr.state_checked},
                new android.graphics.drawable.InsetDrawable(circle(0xFFFFFFFF, knob), inset));
        thumb.addState(new int[] {},
                new android.graphics.drawable.InsetDrawable(circle(0xFF98A2B3, knob), inset));
        toggle.setTrackDrawable(track);
        toggle.setThumbDrawable(thumb);
        toggle.setTrackTintList(null);
        toggle.setThumbTintList(null);
    }

    private static GradientDrawable pill(int color, int width, int height) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(height / 2f);
        shape.setSize(width, height);
        return shape;
    }

    private static GradientDrawable circle(int color, int size) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        shape.setSize(size, size);
        return shape;
    }

    // ---- icons (Lucide drawables, by the emoji the callers pass)

    private static String iconName(String glyph) {
        if (glyph == null) return null;
        switch (glyph) {
            case "🖥️": case "🖥": case "📕": return "monitor";
            case "🤖": return "bot";
            case "✨": case "🔳": return "grid";
            case "🔋": return "battery";
            case "⏻": return "power";
            case "🧰": return "wrench";
            case "🕹️": case "🕹": case "🎮": case "👆": return "gamepad";
            case "📶": return "wifi";
            case "🟦": case "🎧": return "bt";
            case "🔌": return "plug";
            case "⏱️": case "⏱": case "🔄": return "clock";
            case "🌙": return "moon";
            case "📺": return "tv";
            case "📦": case "📜": return "file";
            case "💾": case "♻️": case "♻": return "save";
            case "⇄": return "swap";
            case "🔊": return "volume";
            case "🔇": return "volume_x";
            case "🎵": return "music";
            case "📱": return "grid";
            case "☀️": case "☀": return "sun";
            case "🔔": return "bell";
            case "🔗": return "link";
            case "📳": return "vibrate";
            default: return null;
        }
    }

    /** The icon drawable for a glyph, tinted, or null when there is none (the emoji is shown). */
    android.graphics.drawable.Drawable iconDrawable(String glyph, int color) {
        final String name = iconName(glyph);
        if (name == null) return null;
        final int id = context.getResources().getIdentifier("ic_" + name, "drawable",
                context.getPackageName());
        if (id == 0) return null;
        final android.graphics.drawable.Drawable drawable =
                context.getDrawable(id).mutate();
        drawable.setTint(color);
        return drawable;
    }

    // ---- pages

    /** A scrolling page in the colours of the overlay; returns the column to fill. */
    LinearLayout page(Activity activity) {
        final ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(background);
        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        final int side = dp(26) + Math.max(0,
                (context.getResources().getDisplayMetrics().widthPixels - 1180) / 2);
        column.setPadding(side, dp(22), side, dp(18));
        scroll.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        activity.setContentView(scroll);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(background));
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        return column;
    }

    /**
     * The header of a page: back button (not on the home of the app), tinted icon, title and
     * subtitle, then a thin rule.
     */
    void hero(LinearLayout parent, String icon, int color, String title, String subtitle) {
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final boolean home = context instanceof PartsSettingsActivity;
        if (!home && context instanceof Activity) {
            final ImageView back = new ImageView(context);
            final android.graphics.drawable.Drawable arrow = glyphDrawable("back", text);
            if (arrow != null) back.setImageDrawable(arrow);
            back.setPadding(dp(12), dp(12), dp(12), dp(12));
            back.setBackground(outlined(CHIP, CHIP_LINE, 14));
            back.setOnClickListener(v -> ((Activity) context).finish());
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(52));
            params.rightMargin = dp(14);
            header.addView(back, params);
        }
        final TextView bubble = bubble(icon, color, home ? 56 : 48);
        ((LinearLayout.LayoutParams) bubble.getLayoutParams()).rightMargin = dp(16);
        header.addView(bubble);
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(title, 24, text, true));
        final TextView sub = label(subtitle == null ? "" : subtitle, 14, textDim, false);
        sub.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        texts.addView(sub);
        header.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(header, matchWrap(0, 0, 0, 14));
        final View rule = new View(context);
        rule.setBackgroundColor(divider);
        parent.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))));
    }

    /** A drawable by the name of its file (ic_<name>), tinted. */
    android.graphics.drawable.Drawable glyphDrawable(String name, int color) {
        final int id = context.getResources().getIdentifier("ic_" + name, "drawable",
                context.getPackageName());
        if (id == 0) return null;
        final android.graphics.drawable.Drawable drawable = context.getDrawable(id).mutate();
        drawable.setTint(color);
        return drawable;
    }

    TextView section(LinearLayout parent, String title) {
        final TextView view = label(title.toUpperCase(), 12, textDim, true);
        view.setLetterSpacing(0.06f);
        view.setPadding(dp(6), dp(18), dp(6), dp(8));
        parent.addView(view);
        return view;
    }

    TextView note(LinearLayout parent, String message) {
        final TextView view = label(message, 14, textDim, false);
        view.setPadding(dp(6), dp(6), dp(6), dp(8));
        view.setLineSpacing(0, 1.15f);
        parent.addView(view);
        return view;
    }

    LinearLayout card(LinearLayout parent) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(outlined(surface, LINE, 16));
        parent.addView(card, matchWrap(0, 12, 0, 0));
        return card;
    }

    /** A card that takes half of a line of two (a horizontal LinearLayout). */
    LinearLayout cardInRow(LinearLayout row, boolean last) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(outlined(surface, LINE, 16));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(last ? dp(7) : 0, 0, last ? 0 : dp(7), 0);
        row.addView(card, params);
        return card;
    }

    LinearLayout.LayoutParams matchWrap(int left, int top, int right, int bottom) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    TextView label(String value, float sizeSp, int color, boolean bold) {
        final TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(sizeSp * TEXT_SCALE));
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    // ---- bubbles, chips, buttons

    /** A rounded square tinted with the colour, with the icon of the glyph (or the emoji). */
    TextView bubble(String icon, int color, int sizeDp) {
        final TextView view = new TextView(context);
        view.setGravity(Gravity.CENTER);
        final GradientDrawable shape = rounded(alpha(color, 0x24), sizeDp * 0.29f);
        final android.graphics.drawable.Drawable glyph = iconDrawable(icon, color);
        if (glyph != null) {
            final int inset = Math.round(dp(sizeDp) * 0.27f);
            view.setBackground(new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[] {shape,
                        new android.graphics.drawable.InsetDrawable(glyph, inset)}));
        } else {
            view.setBackground(shape);
            view.setText(icon);
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(sizeDp * 0.45f));
        }
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return view;
    }

    TextView chip(String value, int color) {
        final TextView view = label(value, 12, color, true);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        view.setBackground(rounded(alpha(color, 0x33), 10));
        return view;
    }

    TextView pill(String value, int color, boolean filled, View.OnClickListener click) {
        final TextView view = label(value, 14, filled ? ON_ACCENT : color, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(11), dp(18), dp(11));
        view.setBackground(filled ? rounded(color, 12) : outlined(alpha(color, 0x1F),
                alpha(color, 0x66), 12));
        view.setOnClickListener(click);
        return view;
    }

    // ---- rows

    static final class Row {
        LinearLayout view;
        TextView title;
        TextView summary;
        LinearLayout trailing;
        Switch toggle;
        private final List<View> dependents = new ArrayList<>();
    }

    Row row(LinearLayout card, String icon, int color, String title, String summary) {
        if (card.getChildCount() > 0) {
            final View line = new View(context);
            line.setBackgroundColor(divider);
            card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, dp(1))));
        }
        final Row row = new Row();
        row.view = new LinearLayout(context);
        row.view.setOrientation(LinearLayout.HORIZONTAL);
        row.view.setGravity(Gravity.CENTER_VERTICAL);
        row.view.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.view.setMinimumHeight(dp(64));
        if (icon != null) {
            final TextView bubble = bubble(icon, color, 42);
            row.view.addView(bubble);
            ((LinearLayout.LayoutParams) bubble.getLayoutParams()).rightMargin = dp(14);
        }
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.title = label(title, 15, text, false);
        row.summary = label(summary == null ? "" : summary, 14, textDim, false);
        row.summary.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
        texts.addView(row.title);
        texts.addView(row.summary);
        row.view.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.trailing = new LinearLayout(context);
        row.trailing.setGravity(Gravity.CENTER_VERTICAL);
        row.view.addView(row.trailing);
        card.addView(row.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** A thin progress bar across a card, under a row; percent below 0 shows a half-filled bar. */
    void progressBar(LinearLayout card, int percent, int color) {
        final LinearLayout track = new LinearLayout(context);
        final android.graphics.drawable.GradientDrawable back =
                new android.graphics.drawable.GradientDrawable();
        back.setCornerRadius(dp(4));
        back.setColor(divider);
        track.setBackground(back);
        final int shown = percent < 0 ? 100 : Math.max(2, Math.min(100, percent));
        final View fill = new View(context);
        final android.graphics.drawable.GradientDrawable front =
                new android.graphics.drawable.GradientDrawable();
        front.setCornerRadius(dp(4));
        front.setColor(color);
        fill.setBackground(front);
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                shown));
        track.addView(new View(context), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 100 - shown));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        params.setMargins(dp(18), 0, dp(18), dp(14));
        card.addView(track, params);
    }

    void setSummary(Row row, String summary) {
        row.summary.setText(summary);
        row.summary.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
    }

    Row switchRow(LinearLayout card, String icon, int color, String title, String summary,
            final String key, boolean fallback) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        final Row row = row(card, icon, color, title, summary);
        row.toggle = new Switch(context);
        styleSwitch(row.toggle);
        row.toggle.setChecked(prefs.getBoolean(key, fallback));
        row.toggle.setClickable(false);
        row.trailing.addView(row.toggle);
        row.view.setOnClickListener(v -> {
            final boolean on = !row.toggle.isChecked();
            row.toggle.setChecked(on);
            prefs.edit().putBoolean(key, on).apply();
            applyDependents(row);
        });
        return row;
    }

    /** The rows that only make sense while this switch is on are dimmed when it is off. */
    void dependOn(Row master, View... views) {
        for (View view : views) master.dependents.add(view);
        applyDependents(master);
    }

    private void applyDependents(Row master) {
        final boolean on = master.toggle.isChecked();
        for (View view : master.dependents) setEnabledDeep(view, on);
    }

    void setEnabledDeep(View view, boolean enabled) {
        view.setAlpha(enabled ? 1f : 0.4f);
        enableTree(view, enabled);
    }

    private void enableTree(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enableTree(group.getChildAt(i), enabled);
        }
    }

    /** A line of chips under a row: the choices are all in sight, one tap to pick. */
    private void chipsLine(LinearLayout card, String[] entries, String[] values,
            Supplier<String> get, Consumer<String> set) {
        final android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(dp(18), 0, dp(18), dp(14));
        scroll.setClipToPadding(false);
        final LinearLayout line = new LinearLayout(context);
        final List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            final String value = values[i];
            final TextView chip = label(entries[i], 14, text, true);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(16), dp(10), dp(16), dp(10));
            chips.add(chip);
            chip.setOnClickListener(v -> {
                set.accept(value);
                paintChips(chips, values, value);
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            line.addView(chip, params);
        }
        paintChips(chips, values, get.get());
        scroll.addView(line);
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void paintChips(List<TextView> chips, String[] values, String current) {
        for (int i = 0; i < chips.size(); i++) {
            final boolean on = values[i].equals(current);
            chips.get(i).setTextColor(on ? ON_ACCENT : 0xFFC4CBD8);
            chips.get(i).setBackground(on ? rounded(sAccent, 10) : outlined(CHIP, CHIP_LINE, 10));
        }
    }

    Row choiceRow(LinearLayout card, String icon, int color, final String title,
            final String key, final String[] entries, final String[] values, String fallback) {
        final SharedPreferences prefs = PartsPrefs.get(context);
        final Row row = row(card, icon, color, title, null);
        chipsLine(card, entries, values, () -> prefs.getString(key, fallback),
                value -> prefs.edit().putString(key, value).apply());
        return row;
    }

    /** A choice stored elsewhere than in the preferences (a system setting, for instance). */
    Row valueChoiceRow(LinearLayout card, String icon, int color, final String title,
            final String[] entries, final String[] values, final Supplier<String> get,
            final Consumer<String> set) {
        final Row row = row(card, icon, color, title, null);
        chipsLine(card, entries, values, get, set);
        return row;
    }

    /** A switch stored elsewhere than in the preferences. */
    Row valueSwitchRow(LinearLayout card, String icon, int color, String title, String summary,
            final BooleanSupplier get, final Consumer<Boolean> set) {
        final Row row = row(card, icon, color, title, summary);
        row.toggle = new Switch(context);
        styleSwitch(row.toggle);
        row.toggle.setChecked(get.getAsBoolean());
        row.toggle.setClickable(false);
        row.trailing.addView(row.toggle);
        row.view.setOnClickListener(v -> {
            final boolean on = !row.toggle.isChecked();
            row.toggle.setChecked(on);
            set.accept(on);
            applyDependents(row);
        });
        return row;
    }

    Row actionRow(LinearLayout card, String icon, int color, String title, String summary,
            final Runnable run) {
        final Row row = row(card, icon, color, title, summary);
        row.trailing.addView(chevron());
        row.view.setOnClickListener(v -> run.run());
        return row;
    }

    /** A slider with its value: the title above, the bar and the percentage below. */
    Row sliderRow(LinearLayout card, String icon, int color, String title, int progress, int max,
            final Consumer<Integer> onChange) {
        final Row row = row(card, icon, color, title, null);
        final TextView value = label(progress * 100 / Math.max(1, max) + " %", 14, text, true);
        row.trailing.addView(value);
        final SeekBar seek = new SeekBar(context);
        seek.setMax(max);
        seek.setProgress(progress);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(sAccent));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        seek.setPadding(dp(10), 0, dp(10), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                value.setText(position * 100 / Math.max(1, max) + " %");
                if (fromUser) onChange.accept(position);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        final FrameLayout holder = new FrameLayout(context);
        holder.setPadding(dp(18), 0, dp(18), dp(14));
        holder.addView(seek);
        card.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    View chevron() {
        final ImageView view = new ImageView(context);
        view.setImageDrawable(glyphDrawable("chev", 0xFF5B6577));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        return view;
    }

    static String entryOf(String[] entries, String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return entries[i];
        return entries.length > 0 ? entries[0] : "";
    }

    // ---- dashboard

    /** A tile of the home screen: a tinted icon, a title and a live status. */
    View tile(String icon, int color, String title, String status, View.OnClickListener click) {
        final LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(20), dp(20), dp(20), dp(18));
        tile.setBackground(outlined(surface, LINE, 16));
                final FrameLayout top = new FrameLayout(context);
        final TextView bubble = bubble(icon, color, 60);
        top.addView(bubble);
        final ImageView arrow = new ImageView(context);
        arrow.setImageDrawable(glyphDrawable("chev", 0xFF5B6577));
        top.addView(arrow, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END | Gravity.TOP));
        tile.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final View push = new View(context);
        tile.addView(push, new LinearLayout.LayoutParams(1, 0, 1f));
        final TextView t = label(title, 16, text, true);
        tile.addView(t);
        final TextView s = label(status, 14, textDim, false);
        s.setPadding(0, dp(4), 0, 0);
        tile.addView(s);
        tile.setOnClickListener(click);
        return tile;
    }

    /** Three tiles per line on a wide screen (the bottom one), two on a narrow one. */
    void grid(LinearLayout parent, List<View> tiles) {
        final int columns = context.getResources().getDisplayMetrics().widthPixels >= 900 ? 3 : 2;
        for (int i = 0; i < tiles.size(); i += columns) {
            final LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < columns; k++) {
                final View view = i + k < tiles.size() ? tiles.get(i + k) : new View(context);
                line.addView(view, cell(k == 0 ? 0 : 7, 14, k == columns - 1 ? 0 : 7));
            }
            parent.addView(line, matchWrap(0, 14, 0, 0));
        }
    }

    private LinearLayout.LayoutParams cell(int left, int bottom, int right) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(200), 1f);
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    // ---- dialogs

    interface IntCallback {
        void value(int value);
    }

    void sliderDialog(String title, final String unit, int min, int max, int current,
            final IntCallback callback) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView value = label(current + unit, 28, text, true);
        value.setGravity(Gravity.CENTER);
        final SeekBar seek = new SeekBar(context);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText((progress + min) + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        box.addView(value);
        box.addView(seek);
        new AlertDialog.Builder(context).setTitle(title).setView(box)
                .setPositiveButton("OK", (d, w) -> callback.value(seek.getProgress() + min))
                .setNegativeButton("Cancel", null).show();
    }

    static FrameLayout spacer(Context context, int heightPx) {
        final FrameLayout view = new FrameLayout(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, heightPx));
        return view;
    }
}
