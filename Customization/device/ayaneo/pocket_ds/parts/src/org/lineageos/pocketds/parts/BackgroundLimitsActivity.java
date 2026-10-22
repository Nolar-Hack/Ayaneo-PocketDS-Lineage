/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.Switch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Per app battery optimisation: an app whose switch is on may not run in the background (same
 * "Restricted" state as in the system battery settings, but in one list). The apps that have a
 * launcher icon are listed.
 */
public class BackgroundLimitsActivity extends Activity {
    private static final String OP_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background";

    private static final class App {
        String label;
        String pkg;
        int uid;
        boolean system;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Background limits");
        final Ui ui = new Ui(this);
        final LinearLayout page = ui.page(this);
        ui.hero(page, "🛡️", Ui.ORANGE, "Background limits",
                "Stop the apps you choose from running when you are not using them");
        ui.note(page, "Loading the apps...");
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final List<App> apps = load();
            main.post(() -> show(ui, page, apps));
        }).start();
    }

    private List<App> load() {
        final PackageManager packages = getPackageManager();
        final Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<App> apps = new ArrayList<>();
        final List<String> seen = new ArrayList<>();
        for (ResolveInfo info : packages.queryIntentActivities(launcher, 0)) {
            final ApplicationInfo application = info.activityInfo.applicationInfo;
            if (seen.contains(application.packageName)
                    || application.packageName.equals(getPackageName())) {
                continue;
            }
            seen.add(application.packageName);
            final App app = new App();
            app.label = String.valueOf(info.loadLabel(packages));
            app.pkg = application.packageName;
            app.uid = application.uid;
            app.system = (application.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            apps.add(app);
        }
        Collections.sort(apps, (a, b) -> {
            if (a.system != b.system) return a.system ? 1 : -1;
            return a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT));
        });
        return apps;
    }

    private void show(Ui ui, LinearLayout page, List<App> apps) {
        page.removeViewAt(page.getChildCount() - 1); // "Loading..."
        final AppOpsManager ops = getSystemService(AppOpsManager.class);
        LinearLayout card = null;
        boolean systemHeader = false;
        boolean userHeader = false;
        for (App app : apps) {
            if (app.system && !systemHeader) {
                systemHeader = true;
                ui.section(page, "System apps");
                card = ui.card(page);
            } else if (!app.system && !userHeader) {
                userHeader = true;
                ui.section(page, "Installed apps");
                card = ui.card(page);
            }
            final Ui.Row row = ui.row(card, "📦", app.system ? Ui.GREY : Ui.BLUE, app.label,
                    app.pkg);
            row.toggle = new Switch(this);
            Ui.styleSwitch(row.toggle);
            row.toggle.setChecked(ops.unsafeCheckOpRaw(OP_RUN_ANY_IN_BACKGROUND, app.uid, app.pkg)
                    == AppOpsManager.MODE_IGNORED);
            row.toggle.setClickable(false);
            row.trailing.addView(row.toggle);
            row.view.setOnClickListener(v -> {
                final boolean restrict = !row.toggle.isChecked();
                try {
                    ops.setMode(OP_RUN_ANY_IN_BACKGROUND, app.uid, app.pkg,
                            restrict ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED);
                    row.toggle.setChecked(restrict);
                } catch (RuntimeException e) {
                    ui.setSummary(row, "Cannot change this app: " + e.getMessage());
                }
            });
        }
        ui.note(page, "Restricted apps can still be opened by hand. Some apps (messaging, "
                + "alarms) will stop notifying you while they are restricted.");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
