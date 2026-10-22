/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

import java.util.ArrayList;
import java.util.List;

/**
 * Automation: when something happens (lid, screen, battery, time, app...), the console changes
 * its settings and puts them back afterwards. Switch the rules on and off, edit them, add some
 * (ready-made or your own).
 */
public class RulesActivity extends Activity {

    private int mShownAccent;

    /** The profile changed while this screen was away: it is built again in the new colour. */
    private void checkAccent() {
        if (mShownAccent != 0 && mShownAccent != Accent.current(this)) {
            recreate();
        }
    }
    private RuleStore mStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mShownAccent = Accent.current(this);
        mStore = new RuleStore(this);
        setTitle("Automation");
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAccent();
        build();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void build() {
        final Ui ui = new Ui(this);
        final LinearLayout page = ui.page(this);
        final List<Rule> rules = mStore.load();
        int running = 0;
        for (Rule rule : rules) if (mStore.savedState(rule.id) != null) running++;
        ui.hero(page, "🤖", Ui.PURPLE, "Automation",
                rules.size() + " rules · " + running + " running now");

        final LinearLayout master = ui.card(page);
        ui.switchRow(master, "⚡", Ui.PURPLE, "Automation enabled",
                "Master switch of all the rules (each rule has its own switch)",
                RuleStore.KEY_MASTER, true);

        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        final View add = ui.pill("＋  New rule", Ui.PURPLE, true,
                v -> startActivity(new Intent(this, RuleEditorActivity.class)));
        final View ready = ui.pill("Ready-made", Ui.PURPLE, false, v -> addTemplate());
        buttons.addView(add, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gap.leftMargin = ui.dp(8);
        buttons.addView(ready, gap);
        page.addView(buttons, ui.matchWrap(0, 8, 0, 4));

        ui.section(page, "Your rules");
        if (rules.isEmpty()) {
            ui.note(page, "No rule yet. Create one, or start from a ready-made rule.");
        }
        for (final Rule rule : rules) {
            final LinearLayout card = ui.card(page);
            final Ui.Row row = ui.row(card, rule.icon, rule.colorValue(),
                    rule.name.isEmpty() ? "(no name)" : rule.name, rule.summary());
            row.summary.setMaxLines(3);
            final LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setGravity(Gravity.CENTER_HORIZONTAL);
            if (mStore.savedState(rule.id) != null) {
                final View chip = ui.chip("Running", Ui.GREEN);
                side.addView(chip);
            }
            final Switch toggle = new Switch(this);
            Ui.styleSwitch(toggle);
            toggle.setChecked(rule.enabled);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                final Rule fresh = mStore.find(rule.id);
                if (fresh != null && fresh.enabled != checked) {
                    fresh.enabled = checked;
                    mStore.put(fresh);
                }
            });
            side.addView(toggle);
            row.trailing.addView(side);
            row.view.setOnClickListener(v -> startActivity(new Intent(this,
                    RuleEditorActivity.class).putExtra(RuleEditorActivity.EXTRA_RULE_ID,
                    rule.id)));
        }
    }

    private void addTemplate() {
        final List<Rule> templates = new ArrayList<>(RuleStore.presets());
        final List<String> names = new ArrayList<>();
        for (Rule rule : templates) names.add(rule.icon + "  " + rule.name + "\n" + rule.summary());
        new AlertDialog.Builder(this).setTitle("Add a ready-made rule")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    mStore.put(templates.get(which));
                    build();
                }).setNegativeButton("Cancel", null).show();
    }
}
