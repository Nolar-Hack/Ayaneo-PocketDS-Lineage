/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Home of the second (bottom) screen (see patches/frameworks/base/0016): a list of the
 * applications, and a question at every launch, "top screen or bottom screen".
 */
public class SecondHomeActivity extends Activity {

    private int mShownAccent;

    /** The profile changed while this screen was away: it is built again in the new colour. */
    private void checkAccent() {
        if (mShownAccent != 0 && mShownAccent != Accent.current(this)) {
            recreate();
        }
    }
    private static final class Entry {
        ComponentName component;
        String label;
        Drawable icon;
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Entry> mApps = new ArrayList<>();
    private PagedGrid mGrid;
    private LinearLayout mRoot;
    private boolean mReceiving;

    private final BroadcastReceiver mPackages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mShownAccent = Accent.current(this);
        // With both screens on, the list belongs to the second display only: the default display
        // (the main screen, whichever panel it is after a swap) shows its own home. A stray copy
        // of the list there, left by a start while the screens were being swapped, closes.
        if (getDisplay() != null && getDisplay().getDisplayId() == android.view.Display.DEFAULT_DISPLAY
                && Screens.MODE_BOTH.equals(Screens.screenMode(this))) {
            finish();
            return;
        }
        mReceiving = true;
        build();
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(mPackages, filter);
        // The user is unlocked a few seconds after boot: the list is read again (names and icons
        // of the apps are complete then).
        registerReceiver(mPackages, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        registerReceiver(mPackages, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
        load();
    }

    @Override
    protected void onDestroy() {
        if (mReceiving) unregisterReceiver(mPackages);
        super.onDestroy();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void build() {
        final Ui ui = new Ui(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26), dp(22), dp(26), dp(14));
        // The page fills the whole screen, in the colour of the overlay (no frame around it).
        root.setBackgroundColor(Accent.tint(0xFF0D0F13, Accent.current(this), 0.09f));

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        // One line each: the title never wraps, whatever the buttons next to it.
        final TextView title = ui.label("Applications", 26, 0xFFE9ECF2, true);
        title.setSingleLine(true);
        heading.addView(title);
        final TextView hint = ui.label("Tap an app, then pick a screen", 14, 0xFF98A2B3, false);
        hint.setSingleLine(true);
        heading.addView(hint);
        bar.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        bar.addView(headerButton("⇄  Swap", true, 0, v -> {
            Screens.setSwapped(this, !Screens.isSwapped(this));
        }));
        bar.addView(headerButton("Running", false, 0, v -> showRunning()));
        bar.addView(headerButton("Settings", false, 0, v -> {
            startActivity(new Intent(this, PartsSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }));
        bar.addView(headerButton("Kill all", false, 0xFFF58A7F, v -> {
            final int closed = Screens.killAll(this);
            Toast.makeText(this, closed == 0 ? "No app to close"
                    : "Closed " + closed + (closed == 1 ? " app" : " apps"),
                    Toast.LENGTH_SHORT).show();
        }));
        for (int i = 1; i < bar.getChildCount(); i++) {
            ((LinearLayout.LayoutParams) bar.getChildAt(i).getLayoutParams()).leftMargin = dp(8);
        }
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The update notice has a row of its own: in the header it left no room for the title,
        // which wrapped letter by letter and squeezed the list of apps to nothing.
        final Updates.Info update = Updates.available(this);
        if (update != null || Updates.restartPending(this)) {
            final TextView notice = headerButton(update != null ? "⬆  Update " + update.version
                    + " available" : "⬆  Restart to finish the update", true, 0, v -> startActivity(
                    new Intent(this, SectionActivity.class)
                            .putExtra(SectionActivity.EXTRA_SECTION, SectionActivity.UPDATES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            final LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noticeParams.topMargin = dp(10);
            root.addView(notice, noticeParams);
        }

        mGrid = new PagedGrid(this, getResources().getDisplayMetrics().widthPixels / 1024f);
        root.addView(mGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                1f));
        mRoot = root;
        applyBackground();
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        final LinearLayout outer = new LinearLayout(this);
        outer.addView(root, params);
        setContentView(outer);
    }

    /** The apps that are open, to send each one to the top or to the bottom screen. */
    private void showRunning() {
        final List<android.app.ActivityManager.RunningTaskInfo> apps = Screens.runningApps(this);
        if (apps.isEmpty()) {
            Toast.makeText(this, "No app is open", Toast.LENGTH_SHORT).show();
            return;
        }
        final PackageManager pm = getPackageManager();
        final String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) {
            final android.app.ActivityManager.RunningTaskInfo task = apps.get(i);
            String name = task.baseActivity.getPackageName();
            try {
                name = String.valueOf(pm.getApplicationLabel(
                        pm.getApplicationInfo(task.baseActivity.getPackageName(), 0)));
            } catch (PackageManager.NameNotFoundException e) {
                // the package name is shown
            }
            final int panel = Screens.panelOf(this, task.displayId);
            labels[i] = name + (panel == Screens.TOP ? "  ·  top screen"
                    : panel == Screens.BOTTOM ? "  ·  bottom screen" : "");
        }
        new AlertDialog.Builder(this).setTitle("Running apps")
                .setItems(labels, (d, which) -> send(apps.get(which), labels[which]))
                .setNegativeButton("Close", null).show();
    }

    private void send(android.app.ActivityManager.RunningTaskInfo task, String label) {
        Drawable icon = null;
        try {
            icon = getPackageManager().getApplicationIcon(task.baseActivity.getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            // no icon
        }
        screenChoice(label.split("  ·  ")[0], icon, panel -> move(task, panel));
    }

    private void move(android.app.ActivityManager.RunningTaskInfo task, int panel) {
        if (!Screens.moveTask(this, task.taskId, panel)) {
            Toast.makeText(this, "That screen is off", Toast.LENGTH_SHORT).show();
        }
    }

    /** A button of the header: accent when primary, else a quiet outline (red for "Kill all"). */
    private TextView headerButton(String label, boolean primary, int tint,
            View.OnClickListener click) {
        final TextView button = new TextView(this);
        button.setText(label);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.BOLD));
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        button.setPadding(dp(14), dp(11), dp(14), dp(11));
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(11));
        if (primary) {
            shape.setColor(Accent.current(this));
            button.setTextColor(0xFF07101F);
        } else {
            shape.setColor(0xFF1C2129);
            shape.setStroke(Math.max(1, dp(1) / 2), tint != 0 ? 0x80F06B5E : 0xFF2B3340);
            button.setTextColor(tint != 0 ? tint : 0xFFE9ECF2);
        }
        button.setBackground(shape);
        button.setOnClickListener(click);
        return button;
    }

    /** "Top screen or bottom screen?", then launches the app there. */
    private void ask(Entry entry) {
        screenChoice(entry.label, entry.icon, panel -> open(entry, panel));
    }

    /** Pixels of the design (1024 px wide): the dialog is drawn like the mockup. */
    private int u(float value) {
        return Math.round(value * getResources().getDisplayMetrics().widthPixels / 1024f);
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(sp));
        view.setTypeface(android.graphics.Typeface.create("sans-serif", bold
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        return view;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(u(radius));
        if (stroke != 0) shape.setStroke(Math.max(1, u(1.5f)), stroke);
        return shape;
    }

    /** The card of the design: app, "Open it on which screen?", two screen choices, Cancel. */
    private void screenChoice(String name, Drawable icon, java.util.function.IntConsumer pick) {
        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_DeviceDefault_NoActionBar);
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(0xFF171B22, 0xFF2F3643, 24));
        card.setPadding(u(32), u(28), u(32), u(22));

        final LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageDrawable(icon);
        image.setBackground(box(0xFF1C2129, 0xFF2B3340, 18));
        image.setPadding(u(8), u(8), u(8), u(8));
        head.addView(image, new LinearLayout.LayoutParams(u(64), u(64)));
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(name, 24, 0xFFE9ECF2, true));
        names.addView(text("Open it on which screen?", 15, 0xFF98A2B3, false));
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.leftMargin = u(16);
        head.addView(names, nameParams);
        card.addView(head);

        final LinearLayout choices = new LinearLayout(this);
        choices.addView(screenCard("Top screen", "1920×1080 · up to 165 Hz", true, () -> {
            dialog.dismiss();
            pick.accept(Screens.TOP);
        }), cardParams(true));
        choices.addView(screenCard("Bottom screen", "1024×768 · 60 Hz", false, () -> {
            dialog.dismiss();
            pick.accept(Screens.BOTTOM);
        }), cardParams(false));
        final LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        choiceParams.topMargin = u(24);
        card.addView(choices, choiceParams);

        final TextView cancel = text("Cancel", 16, 0xFF98A2B3, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(u(20), u(10), u(20), u(10));
        cancel.setOnClickListener(v -> dialog.dismiss());
        final LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelParams.topMargin = u(12);
        card.addView(cancel, cancelParams);

        final android.widget.FrameLayout scrim = new android.widget.FrameLayout(this);
        scrim.setBackgroundColor(0xB305060C);
        scrim.setOnClickListener(v -> dialog.dismiss());
        final android.widget.FrameLayout.LayoutParams place =
                new android.widget.FrameLayout.LayoutParams(u(660),
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        card.setClickable(true);
        scrim.addView(card, place);
        dialog.setContentView(scrim);
        final android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    private LinearLayout.LayoutParams cardParams(boolean first) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (first) params.rightMargin = u(7); else params.leftMargin = u(7);
        return params;
    }

    /** One screen to choose: a drawing of the two screens with the chosen one lit, and its specs. */
    private LinearLayout screenCard(String label, String specs, boolean top, Runnable onClick) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(u(12), u(20), u(12), u(16));
        card.setBackground(top ? box(Accent.soft(Accent.current(this)), Accent.current(this), 16) : box(0xFF1B2029, 0xFF2F3643, 16));
        final LinearLayout drawing = new LinearLayout(this);
        drawing.setOrientation(LinearLayout.VERTICAL);
        drawing.setGravity(Gravity.CENTER_HORIZONTAL);
        final View upper = new View(this);
        upper.setBackground(box(top ? Accent.current(this) : 0xFF2F3643, 0, 7));
        final View lower = new View(this);
        lower.setBackground(box(top ? 0xFF2F3643 : Accent.current(this), 0, 7));
        drawing.addView(upper, new LinearLayout.LayoutParams(u(92), u(52)));
        final LinearLayout.LayoutParams lowerParams = new LinearLayout.LayoutParams(u(66), u(52));
        lowerParams.topMargin = u(5);
        drawing.addView(lower, lowerParams);
        card.addView(drawing);
        final TextView name = text(label, 18, 0xFFE9ECF2, true);
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = u(12);
        card.addView(name, nameParams);
        final TextView specsView = text(specs, 13, 0xFF98A2B3, false);
        specsView.setGravity(Gravity.CENTER);
        card.addView(specsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnClickListener(v -> onClick.run());
        return card;
    }

    private void open(Entry entry, int panel) {
        if (!Screens.launch(this, entry.component, panel)) {
            Toast.makeText(this, "That screen is off", Toast.LENGTH_SHORT).show();
        }
    }

    /** Reads the launchable applications off the main thread, sorted by name. */
    private void load() {
        new Thread(() -> {
            final PackageManager pm = getPackageManager();
            final Intent query = new Intent(Intent.ACTION_MAIN).addCategory(
                    Intent.CATEGORY_LAUNCHER);
            final List<Entry> found = new ArrayList<>();
            final java.util.Set<String> seen = new java.util.HashSet<>();
            // At boot the user is still locked: without these flags only the apps that work before
            // the unlock (this one and the Settings) are listed, the others come ten seconds later.
            final int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
            for (ResolveInfo info : pm.queryIntentActivities(query, flags)) {
                // An app with two launcher entries of the same name is listed once.
                if (!seen.add(info.activityInfo.packageName + "/" + info.loadLabel(pm))) continue;
                final Entry entry = new Entry();
                entry.component = new ComponentName(info.activityInfo.packageName,
                        info.activityInfo.name);
                entry.label = String.valueOf(info.loadLabel(pm));
                entry.icon = info.loadIcon(pm);
                found.add(entry);
            }
            Collections.sort(found, (a, b) -> a.label.toLowerCase(Locale.ROOT)
                    .compareTo(b.label.toLowerCase(Locale.ROOT)));
            mMain.post(() -> {
                mApps.clear();
                mApps.addAll(found);
                refreshGrid();
            });
        }).start();
    }

    // ---- background: the wallpaper of this screen (its own, else the one of the system), dimmed

    /** The dimming over the wallpaper, leaning a little towards the colour of the profile. */
    private int scrim() {
        return (Accent.tint(0xFF060810, Accent.current(this), 0.15f) & 0x00FFFFFF) | 0x99000000;
    }

    private void applyBackground() {
        if (mRoot == null) return;
        int panel = -1;
        try {
            if (getDisplay() != null) panel = Screens.panelOf(this, getDisplay().getDisplayId());
        } catch (LinkageError | RuntimeException e) {
            // the panel is not known: no wallpaper of its own
        }
        final Drawable wallpaper = Wallpapers.load(this, panel);
        if (wallpaper == null) {
            // The wallpaper of the system shows through (the theme of this home shows it), dimmed
            // so that the icons read well.
            mRoot.setBackgroundColor(scrim());
        } else {
            mRoot.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[] {
                wallpaper, new android.graphics.drawable.ColorDrawable(scrim())}));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAccent();
        applyBackground();
        refreshGrid();
    }

    // ---- the grid: folders first, then the apps that are in no folder

    private String key(Entry entry) {
        return entry.component.flattenToShortString();
    }

    private Entry entryOf(String key) {
        for (Entry entry : mApps) if (key(entry).equals(key)) return entry;
        return null;
    }

    private void refreshGrid() {
        if (mGrid == null) return;
        final Folders store = new Folders(this);
        final List<Folders.Folder> folders = store.load();
        final List<PagedGrid.Item> items = new ArrayList<>();
        for (Folders.Folder folder : folders) {
            final List<Drawable> icons = new ArrayList<>();
            for (String app : folder.apps) {
                final Entry entry = entryOf(app);
                if (entry != null) icons.add(entry.icon);
            }
            if (icons.isEmpty()) continue;
            final PagedGrid.Item item = new PagedGrid.Item();
            item.label = folder.name;
            item.folderIcons = icons;
            item.click = () -> showFolder(folder.id);
            item.longClick = () -> folderMenu(folder.id);
            items.add(item);
        }
        for (Entry entry : mApps) {
            if (store.folderOf(folders, key(entry)) != null) continue;
            final PagedGrid.Item item = new PagedGrid.Item();
            item.label = entry.label;
            item.icon = entry.icon;
            item.click = () -> ask(entry);
            item.longClick = () -> appMenu(entry);
            items.add(item);
        }
        final android.content.SharedPreferences prefs = PartsPrefs.get(this);
        mGrid.setItems(items, PartsPrefs.getInt(prefs, PartsPrefs.LAUNCHER_COLUMNS, 5),
                PartsPrefs.getInt(prefs, PartsPrefs.LAUNCHER_ROWS, 3));
    }

    // ---- cards (the same look as the "which screen" card)

    private android.app.Dialog cardDialog(LinearLayout card, int widthU) {
        return cardDialog(card, widthU, Gravity.CENTER);
    }

    private android.app.Dialog cardDialog(LinearLayout card, int widthU, int gravity) {
        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_DeviceDefault_NoActionBar);
        card.setBackground(box(0xFF171B22, 0xFF2F3643, 24));
        final android.widget.FrameLayout scrim = new android.widget.FrameLayout(this);
        scrim.setBackgroundColor(0xB305060C);
        scrim.setOnClickListener(v -> dialog.dismiss());
        card.setClickable(true);
        final android.widget.FrameLayout.LayoutParams place =
                new android.widget.FrameLayout.LayoutParams(u(widthU),
                        ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) place.topMargin = u(60);
        scrim.addView(card, place);
        dialog.setContentView(scrim);
        final android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        return dialog;
    }

    private TextView cardButton(String label, boolean primary, int tint) {
        final TextView button = text(label, 16, primary ? 0xFF07101F : (tint != 0 ? tint
                : 0xFFE9ECF2), true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(u(16), u(14), u(16), u(14));
        button.setBackground(primary ? box(Accent.current(this), 0, 12) : box(0xFF232832, 0xFF2F3643, 12));
        return button;
    }

    /** A card with a title and a list of choices. */
    private void optionsDialog(String title, String[] labels, Runnable[] actions) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(u(28), u(24), u(28), u(20));
        card.addView(text(title, 22, 0xFFE9ECF2, true));
        final android.app.Dialog dialog = cardDialog(card, 520);
        for (int i = 0; i < labels.length; i++) {
            final Runnable action = actions[i];
            final TextView option = cardButton(labels[i], false, 0);
            option.setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = u(i == 0 ? 18 : 10);
            card.addView(option, params);
        }
        final TextView cancel = text("Cancel", 16, 0xFF98A2B3, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(u(20), u(10), u(20), u(4));
        cancel.setOnClickListener(v -> dialog.dismiss());
        card.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
    }

    /** A card with a text field (a name for a folder). */
    private void promptDialog(String title, String initial, java.util.function.Consumer<String> done) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(u(28), u(24), u(28), u(20));
        card.addView(text(title, 22, 0xFFE9ECF2, true));
        final android.widget.EditText field = new android.widget.EditText(this);
        field.setText(initial);
        field.setSingleLine(true);
        field.setTextColor(0xFFE9ECF2);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(22));
        field.setPadding(u(16), u(12), u(16), u(12));
        field.setBackground(box(0xFF1D222B, Accent.current(this), 12));
        field.selectAll();
        final LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = u(18);
        card.addView(field, fieldParams);
        // At the top: the keyboard takes the lower half of the screen.
        final android.app.Dialog dialog = cardDialog(card, 560, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        final LinearLayout buttons = new LinearLayout(this);
        final TextView cancel = cardButton("Cancel", false, 0);
        cancel.setOnClickListener(v -> dialog.dismiss());
        final TextView ok = cardButton("OK", true, 0);
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            done.accept(field.getText().toString().trim());
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        okParams.leftMargin = u(10);
        buttons.addView(ok, okParams);
        final LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = u(16);
        card.addView(buttons, buttonsParams);
        dialog.show();
        field.requestFocus();
    }

    // ---- menus of an app and of a folder

    private void appMenu(Entry entry) {
        final Folders store = new Folders(this);
        final List<Folders.Folder> folders = store.load();
        final Folders.Folder current = store.folderOf(folders, key(entry));
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add("Put in a folder");
        actions.add(() -> chooseFolder(entry));
        if (current != null) {
            labels.add("Take out of \"" + current.name + "\"");
            actions.add(() -> {
                final List<Folders.Folder> all = store.load();
                store.remove(all, key(entry));
                store.save(all);
                refreshGrid();
            });
        }
        labels.add("App info");
        actions.add(() -> startActivity(new Intent(android.provider.Settings
                .ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package",
                entry.component.getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        optionsDialog(entry.label, labels.toArray(new String[0]),
                actions.toArray(new Runnable[0]));
    }

    private void chooseFolder(Entry entry) {
        final Folders store = new Folders(this);
        final List<Folders.Folder> folders = store.load();
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        for (Folders.Folder folder : folders) {
            labels.add(folder.name);
            actions.add(() -> {
                final List<Folders.Folder> all = store.load();
                for (Folders.Folder f : all) {
                    if (f.id.equals(folder.id)) store.add(all, f, key(entry));
                }
                store.save(all);
                refreshGrid();
            });
        }
        labels.add("New folder…");
        actions.add(() -> promptDialog("Name of the folder", "", name -> {
            final List<Folders.Folder> all = store.load();
            store.add(all, store.create(all, name), key(entry));
            store.save(all);
            refreshGrid();
        }));
        optionsDialog("Put " + entry.label + " in", labels.toArray(new String[0]),
                actions.toArray(new Runnable[0]));
    }

    private void folderMenu(String id) {
        final Folders store = new Folders(this);
        optionsDialog("Folder", new String[] {"Rename", "Delete (the apps go back to the list)"},
                new Runnable[] {() -> {
                    for (Folders.Folder folder : store.load()) {
                        if (!folder.id.equals(id)) continue;
                        promptDialog("Name of the folder", folder.name, name -> {
                            final List<Folders.Folder> all = store.load();
                            for (Folders.Folder f : all) if (f.id.equals(id) && !name.isEmpty()) {
                                f.name = name;
                            }
                            store.save(all);
                            refreshGrid();
                        });
                    }
                }, () -> {
                    final List<Folders.Folder> all = store.load();
                    all.removeIf(f -> f.id.equals(id));
                    store.save(all);
                    refreshGrid();
                }});
    }

    /** The apps of a folder, in a card; a tap opens one, a long press takes it out. */
    private void showFolder(String id) {
        final Folders store = new Folders(this);
        Folders.Folder folder = null;
        for (Folders.Folder f : store.load()) if (f.id.equals(id)) folder = f;
        if (folder == null) return;
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(u(28), u(24), u(28), u(20));
        card.addView(text(folder.name, 24, 0xFFE9ECF2, true));
        final android.app.Dialog dialog = cardDialog(card, 800);
        final PagedGrid grid = new PagedGrid(this, getResources().getDisplayMetrics().widthPixels
                / 1024f);
        final List<PagedGrid.Item> items = new ArrayList<>();
        for (String app : folder.apps) {
            final Entry entry = entryOf(app);
            if (entry == null) continue;
            final PagedGrid.Item item = new PagedGrid.Item();
            item.label = entry.label;
            item.icon = entry.icon;
            item.click = () -> {
                dialog.dismiss();
                ask(entry);
            };
            item.longClick = () -> {
                dialog.dismiss();
                appMenu(entry);
            };
            items.add(item);
        }
        final int columns = Math.min(5, Math.max(2, items.size()));
        final int rows = Math.max(1, (items.size() + columns - 1) / columns);
        final LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u(Math.min(3, rows) * 150 + 26));
        gridParams.topMargin = u(14);
        card.addView(grid, gridParams);
        grid.post(() -> grid.setItems(items, columns, Math.min(3, rows)));
        final TextView close = text("Close", 16, 0xFF98A2B3, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(u(20), u(10), u(20), u(4));
        close.setOnClickListener(v -> dialog.dismiss());
        card.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
    }
}
