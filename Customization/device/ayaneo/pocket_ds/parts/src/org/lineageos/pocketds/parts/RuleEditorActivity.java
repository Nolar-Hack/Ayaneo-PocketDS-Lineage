/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Editor of one rule: its look, the conditions (all of them or any of them), the delay, the
 * actions and a few options. The page is built again after every change.
 */
public class RuleEditorActivity extends Activity {
    static final String EXTRA_RULE_ID = "rule_id";

    private static final String[] DELAY_LABELS = {"At once", "10 seconds", "30 seconds",
        "1 minute", "2 minutes", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour",
        "2 hours"};
    private static final int[] DELAY_SECONDS = {0, 10, 30, 60, 120, 300, 600, 900, 1800, 3600,
        7200};
    private static final String[] ON_OFF = {"Off", "On"};
    private static final String[] ON_OFF_VALUES = {"off", "on"};

    private RuleStore mStore;
    private Rule mRule;
    private boolean mIsNew;
    private Ui mUi;
    private EditText mName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStore = new RuleStore(this);
        final String id = getIntent().getStringExtra(EXTRA_RULE_ID);
        mRule = id == null ? null : mStore.find(id);
        mIsNew = mRule == null;
        if (mIsNew) {
            mRule = new Rule();
            mRule.name = "My rule";
            mRule.conditions.add(new Rule.Condition(Rule.C_SCREEN_OFF, ""));
            mRule.actions.add(new Rule.Action(Rule.A_WIFI, "off"));
        }
        setTitle(mIsNew ? "New rule" : "Edit the rule");
        render();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void readName() {
        if (mName != null) mRule.name = mName.getText().toString();
    }

    private void render() {
        readName();
        mUi = new Ui(this);
        final Ui ui = mUi;
        final LinearLayout page = ui.page(this);

        // ---- identity
        final LinearLayout identity = ui.card(page);
        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        final TextView bubble = ui.bubble(mRule.icon, mRule.colorValue(), 56);
        bubble.setOnClickListener(v -> chooseLook());
        head.addView(bubble);
        ((LinearLayout.LayoutParams) bubble.getLayoutParams()).rightMargin = ui.dp(14);
        mName = new EditText(this);
        mName.setHint("Name of the rule");
        mName.setText(mRule.name);
        mName.setSingleLine();
        mName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mName.setTextColor(ui.text);
        mName.setHintTextColor(ui.textDim);
        mName.setTextSize(18);
        head.addView(mName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        identity.addView(head);

        // ---- when
        ui.section(page, "When");
        final LinearLayout when = ui.card(page);
        if (mRule.conditions.size() > 1) {
            final Ui.Row match = ui.row(when, "🔀", Ui.PURPLE,
                    mRule.matchAll ? "All the conditions" : "Any of the conditions",
                    mRule.matchAll ? "The rule runs when every condition holds"
                            : "The rule runs as soon as one condition holds");
            match.trailing.addView(ui.chevron());
            match.view.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("The rule runs when")
                    .setSingleChoiceItems(new String[] {"All the conditions hold",
                        "Any condition holds"}, mRule.matchAll ? 0 : 1, (d, which) -> {
                            mRule.matchAll = which == 0;
                            d.dismiss();
                            render();
                        }).show());
        }
        for (final Rule.Condition condition : mRule.conditions) {
            final int index = Rule.index(Rule.CONDITIONS, condition.type);
            final Ui.Row row = ui.row(when, index < 0 ? "?" : Rule.CONDITION_ICONS[index],
                    Ui.BLUE, Rule.describe(condition, appLabelOrNull(condition)),
                    Rule.needsParam(condition.type) && condition.param.isEmpty()
                            ? "Tap to set it" : null);
            row.trailing.addView(removeButton(() -> {
                mRule.conditions.remove(condition);
                render();
            }));
            row.view.setOnClickListener(v -> chooseCondition(condition));
        }
        ui.actionRow(when, "＋", Ui.BLUE, "Add a condition", null, () -> {
            final Rule.Condition added = new Rule.Condition(Rule.C_SCREEN_OFF, "");
            mRule.conditions.add(added);
            chooseCondition(added);
        });

        // ---- delay
        ui.section(page, "Delay");
        final LinearLayout delay = ui.card(page);
        int delayIndex = 0;
        for (int i = 0; i < DELAY_SECONDS.length; i++) {
            if (DELAY_SECONDS[i] == mRule.delaySec) delayIndex = i;
        }
        final Ui.Row delayRow = ui.row(delay, "⏳", Ui.ORANGE, "Wait before it runs",
                DELAY_LABELS[delayIndex]);
        delayRow.trailing.addView(ui.chevron());
        final int checkedDelay = delayIndex;
        delayRow.view.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Wait before it runs")
                .setSingleChoiceItems(DELAY_LABELS, checkedDelay, (d, which) -> {
                    mRule.delaySec = DELAY_SECONDS[which];
                    d.dismiss();
                    render();
                }).show());

        // ---- then
        ui.section(page, "Then");
        final LinearLayout then = ui.card(page);
        for (final Rule.Action action : mRule.actions) {
            final int index = Rule.index(Rule.ACTIONS, action.type);
            final Ui.Row row = ui.row(then, index < 0 ? "?" : Rule.ACTION_ICONS[index], Ui.TEAL,
                    Rule.label(Rule.ACTIONS, Rule.ACTION_LABELS, action.type),
                    valueText(action));
            row.trailing.addView(removeButton(() -> {
                mRule.actions.remove(action);
                render();
            }));
            row.view.setOnClickListener(v -> chooseAction(action));
        }
        ui.actionRow(then, "＋", Ui.TEAL, "Add an action", null, () -> {
            final Rule.Action added = new Rule.Action(Rule.A_WIFI, defaultValue(Rule.A_WIFI));
            mRule.actions.add(added);
            chooseAction(added);
        });

        // ---- options
        ui.section(page, "Options");
        final LinearLayout options = ui.card(page);
        optionRow(options, "🔌", Ui.GREEN, "Only on battery",
                "Does nothing while the console is charging", mRule.onlyOnBattery,
                on -> mRule.onlyOnBattery = on);
        optionRow(options, "↩️", Ui.BLUE, "Undo when the conditions end",
                "Puts back what the rule changed", mRule.undo, on -> mRule.undo = on);
        optionRow(options, "🔔", Ui.ORANGE, "Notify me when it runs",
                "A discreet notification each time the rule starts", mRule.notify,
                on -> mRule.notify = on);

        // ---- buttons
        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(ui.pill("Save", mRule.colorValue(), true, v -> save()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        if (!mIsNew) {
            final View copy = ui.pill("Duplicate", mRule.colorValue(), false, v -> duplicate());
            final LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            copyParams.leftMargin = ui.dp(8);
            buttons.addView(copy, copyParams);
            final View delete = ui.pill("Delete", Ui.RED, false, v -> new AlertDialog.Builder(this)
                    .setMessage("Delete this rule?")
                    .setPositiveButton("Delete", (d, w) -> {
                        mStore.remove(mRule.id);
                        finish();
                    }).setNegativeButton("Cancel", null).show());
            final LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            deleteParams.leftMargin = ui.dp(8);
            buttons.addView(delete, deleteParams);
        }
        page.addView(buttons, ui.matchWrap(0, 20, 0, 8));
    }

    private interface BooleanCallback {
        void value(boolean on);
    }

    private void optionRow(LinearLayout card, String icon, int color, String title,
            String summary, boolean checked, final BooleanCallback callback) {
        final Ui.Row row = mUi.row(card, icon, color, title, summary);
        final Switch toggle = new Switch(this);
            Ui.styleSwitch(toggle);
        toggle.setChecked(checked);
        toggle.setClickable(false);
        row.trailing.addView(toggle);
        row.view.setOnClickListener(v -> {
            toggle.setChecked(!toggle.isChecked());
            callback.value(toggle.isChecked());
        });
    }

    private View removeButton(final Runnable run) {
        final TextView view = mUi.label("✕", 16, mUi.textDim, true);
        view.setPadding(mUi.dp(14), mUi.dp(8), mUi.dp(4), mUi.dp(8));
        view.setOnClickListener(v -> run.run());
        return view;
    }

    // ---- look

    private void chooseLook() {
        readName();
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mUi.dp(16), mUi.dp(12), mUi.dp(16), mUi.dp(4));
        final AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout line = null;
        for (int i = 0; i < Rule.ICONS.length; i++) {
            if (i % 6 == 0) {
                line = new LinearLayout(this);
                box.addView(line);
            }
            final String icon = Rule.ICONS[i];
            final TextView bubble = mUi.bubble(icon, icon.equals(mRule.icon)
                    ? mRule.colorValue() : Ui.GREY, 44);
            bubble.setOnClickListener(v -> {
                mRule.icon = icon;
                dialog[0].dismiss();
                chooseLook();
            });
            line.addView(bubble);
            ((LinearLayout.LayoutParams) bubble.getLayoutParams()).setMargins(mUi.dp(3),
                    mUi.dp(3), mUi.dp(3), mUi.dp(3));
        }
        final LinearLayout colors = new LinearLayout(this);
        colors.setPadding(0, mUi.dp(12), 0, 0);
        box.addView(colors);
        for (int i = 0; i < Rule.COLORS.length; i++) {
            final int index = i;
            final View dot = new View(this);
            dot.setBackground(mUi.rounded(Rule.COLORS[i], 16));
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(mUi.dp(32),
                    mUi.dp(32));
            params.setMargins(mUi.dp(5), 0, mUi.dp(5), 0);
            dot.setAlpha(i == mRule.color ? 1f : 0.45f);
            dot.setOnClickListener(v -> {
                mRule.color = index;
                dialog[0].dismiss();
                chooseLook();
            });
            colors.addView(dot, params);
        }
        dialog[0] = new AlertDialog.Builder(this).setTitle("Icon and colour").setView(box)
                .setPositiveButton("OK", (d, w) -> render()).show();
    }

    // ---- conditions

    private void chooseCondition(final Rule.Condition condition) {
        readName();
        final String[] labels = new String[Rule.CONDITIONS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = Rule.CONDITION_ICONS[i] + "  " + Rule.CONDITION_LABELS[i];
        }
        new AlertDialog.Builder(this).setTitle("Condition")
                .setSingleChoiceItems(labels, Math.max(0, Rule.index(Rule.CONDITIONS,
                        condition.type)), (d, which) -> {
                            final String type = Rule.CONDITIONS[which];
                            if (!type.equals(condition.type)) condition.param = "";
                            condition.type = type;
                            d.dismiss();
                            if (Rule.needsParam(type)) editConditionParam(condition);
                            else render();
                        })
                .setOnCancelListener(d -> render())
                .setNegativeButton("Cancel", (d, w) -> render()).show();
    }

    private void editConditionParam(final Rule.Condition condition) {
        switch (condition.type) {
            case Rule.C_BATTERY_BELOW:
            case Rule.C_BATTERY_ABOVE: {
                final boolean below = Rule.C_BATTERY_BELOW.equals(condition.type);
                mUi.sliderDialog(below ? "Battery below" : "Battery above", " %", 3, 98,
                        number(condition.param, below ? 20 : 80), value -> {
                            condition.param = Integer.toString(value);
                            render();
                        });
                break;
            }
            case Rule.C_TEMP_ABOVE:
                mUi.sliderDialog("Battery temperature above", " °C", 30, 60,
                        number(condition.param, 42), value -> {
                            condition.param = Integer.toString(value);
                            render();
                        });
                break;
            case Rule.C_APP:
                chooseApps(false, condition.param, packages -> {
                    condition.param = packages.isEmpty() ? "" : packages.get(0);
                    render();
                });
                break;
            case Rule.C_TIME:
                editTimeWindow(condition);
                break;
            default:
                render();
                break;
        }
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void editTimeWindow(final Rule.Condition condition) {
        final Rule.TimeWindow window = Rule.TimeWindow.parse(condition.param);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mUi.dp(20), mUi.dp(12), mUi.dp(20), mUi.dp(4));
        final LinearLayout times = new LinearLayout(this);
        box.addView(times);
        final TextView start = mUi.pill("From " + Rule.TimeWindow.clock(window.startMin),
                Ui.BLUE, false, null);
        final TextView end = mUi.pill("To " + Rule.TimeWindow.clock(window.endMin), Ui.BLUE,
                false, null);
        start.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) -> {
            window.startMin = hour * 60 + minute;
            start.setText("From " + Rule.TimeWindow.clock(window.startMin));
        }, window.startMin / 60, window.startMin % 60, true).show());
        end.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) -> {
            window.endMin = hour * 60 + minute;
            end.setText("To " + Rule.TimeWindow.clock(window.endMin));
        }, window.endMin / 60, window.endMin % 60, true).show());
        times.addView(start, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        endParams.leftMargin = mUi.dp(8);
        times.addView(end, endParams);
        final LinearLayout days = new LinearLayout(this);
        days.setPadding(0, mUi.dp(16), 0, 0);
        box.addView(days);
        final String[] letters = {"M", "T", "W", "T", "F", "S", "S"};
        for (int i = 0; i < 7; i++) {
            final int day = i;
            final TextView chip = mUi.label(letters[i], 14, window.days[i] ? 0xFFFFFFFF
                    : mUi.textDim, true);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(mUi.rounded(window.days[i] ? Ui.BLUE : mUi.divider, 18));
            chip.setOnClickListener(v -> {
                window.days[day] = !window.days[day];
                chip.setTextColor(window.days[day] ? 0xFFFFFFFF : mUi.textDim);
                chip.setBackground(mUi.rounded(window.days[day] ? Ui.BLUE : mUi.divider, 18));
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    mUi.dp(36), 1f);
            params.setMargins(mUi.dp(2), 0, mUi.dp(2), 0);
            days.addView(chip, params);
        }
        new AlertDialog.Builder(this).setTitle("Between two times").setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    condition.param = window.encode();
                    render();
                }).setNegativeButton("Cancel", (d, w) -> render())
                .setOnCancelListener(d -> render()).show();
    }

    private String appLabelOrNull(Rule.Condition condition) {
        if (!Rule.C_APP.equals(condition.type) || condition.param.isEmpty()) return null;
        return appLabel(condition.param);
    }

    // ---- actions

    private static String defaultValue(String type) {
        switch (type) {
            case Rule.A_REFRESH: return "60";
            case Rule.A_SCREEN_MODE: return "3";
            case Rule.A_TIMEOUT: return "30000";
            case Rule.A_BRIGHTNESS: return "30";
            case Rule.A_SOUND: return "1";
            case Rule.A_FORCE_STOP:
            case Rule.A_RESTRICT_BG: return "";
            default: return "off";
        }
    }

    private String valueText(Rule.Action action) {
        switch (action.type) {
            case Rule.A_REFRESH:
                return action.value + " Hz";
            case Rule.A_SCREEN_MODE:
                return Rule.label(new String[] {"1", "3", "4"}, new String[] {"Both screens",
                    "Main screen only", "Bottom screen only"}, action.value);
            case Rule.A_TIMEOUT:
                return Rule.label(timeoutValues(), timeoutLabels(), action.value);
            case Rule.A_BRIGHTNESS:
                return action.value + " %";
            case Rule.A_SOUND:
                return Rule.label(new String[] {"2", "1", "0"}, new String[] {"Normal",
                    "Vibrate", "Silent"}, action.value);
            case Rule.A_FORCE_STOP:
            case Rule.A_RESTRICT_BG: {
                final int count = action.value.isEmpty() ? 0 : action.value.split(",").length;
                return count == 0 ? "Tap to choose the apps" : count + " app(s)";
            }
            default:
                return "on".equals(action.value) ? "Switch on" : "Switch off";
        }
    }

    private static String[] timeoutValues() {
        return new String[] {"15000", "30000", "60000", "120000", "300000", "600000"};
    }

    private static String[] timeoutLabels() {
        return new String[] {"15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes",
            "10 minutes"};
    }

    private void chooseAction(final Rule.Action action) {
        readName();
        final String[] labels = new String[Rule.ACTIONS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = Rule.ACTION_ICONS[i] + "  " + Rule.ACTION_LABELS[i];
        }
        new AlertDialog.Builder(this).setTitle("Action")
                .setSingleChoiceItems(labels, Math.max(0, Rule.index(Rule.ACTIONS, action.type)),
                        (d, which) -> {
                            final String type = Rule.ACTIONS[which];
                            if (!type.equals(action.type)) action.value = defaultValue(type);
                            action.type = type;
                            d.dismiss();
                            editActionValue(action);
                        })
                .setOnCancelListener(d -> render())
                .setNegativeButton("Cancel", (d, w) -> render()).show();
    }

    private void editActionValue(final Rule.Action action) {
        switch (action.type) {
            case Rule.A_REFRESH:
                choose("Refresh rate", new String[] {"60 Hz", "120 Hz"},
                        new String[] {"60", "120"}, action);
                break;
            case Rule.A_SCREEN_MODE:
                choose("Screens", new String[] {"Both screens", "Main screen only",
                    "Bottom screen only"}, new String[] {"1", "3", "4"}, action);
                break;
            case Rule.A_TIMEOUT:
                choose("Screen timeout", timeoutLabels(), timeoutValues(), action);
                break;
            case Rule.A_SOUND:
                choose("Sound mode", new String[] {"Normal", "Vibrate", "Silent"},
                        new String[] {"2", "1", "0"}, action);
                break;
            case Rule.A_BRIGHTNESS:
                mUi.sliderDialog("Brightness", " %", 1, 100, number(action.value, 30), value -> {
                    action.value = Integer.toString(value);
                    render();
                });
                break;
            case Rule.A_FORCE_STOP:
            case Rule.A_RESTRICT_BG:
                chooseApps(true, action.value, packages -> {
                    action.value = String.join(",", packages);
                    render();
                });
                break;
            default:
                choose(Rule.label(Rule.ACTIONS, Rule.ACTION_LABELS, action.type), ON_OFF,
                        ON_OFF_VALUES, action);
                break;
        }
    }

    private void choose(String title, final String[] labels, final String[] values,
            final Rule.Action action) {
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(action.value)) checked = i;
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    action.value = values[which];
                    d.dismiss();
                    render();
                }).setOnCancelListener(d -> render())
                .setNegativeButton("Cancel", (d, w) -> render()).show();
    }

    // ---- apps

    private interface AppsCallback {
        void chosen(List<String> packages);
    }

    /** Apps with a launcher icon: {package, label}, sorted by label. */
    private List<String[]> launchableApps() {
        final PackageManager packages = getPackageManager();
        final List<ResolveInfo> infos = packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        final List<String[]> apps = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (ResolveInfo info : infos) {
            final String pkg = info.activityInfo.packageName;
            if (seen.add(pkg)) apps.add(new String[] {pkg, info.loadLabel(packages).toString()});
        }
        Collections.sort(apps, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return apps;
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0)
                    .loadLabel(getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void chooseApps(final boolean multiple, String current, final AppsCallback callback) {
        final List<String[]> apps = launchableApps();
        final Set<String> chosen = new HashSet<>();
        if (!current.isEmpty()) Collections.addAll(chosen, current.split(","));
        final String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = apps.get(i)[1];
            checked[i] = chosen.contains(apps.get(i)[0]);
        }
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(multiple ? "Apps" : "App in the foreground")
                .setNegativeButton("Cancel", (d, w) -> render())
                .setOnCancelListener(d -> render());
        if (multiple) {
            dialog.setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                if (isChecked) chosen.add(apps.get(which)[0]);
                else chosen.remove(apps.get(which)[0]);
            }).setPositiveButton("OK", (d, w) -> callback.chosen(new ArrayList<>(chosen)));
        } else {
            dialog.setItems(labels, (d, which) -> callback.chosen(
                    Collections.singletonList(apps.get(which)[0])));
        }
        dialog.show();
    }

    // ---- save

    private void duplicate() {
        readName();
        final Rule copy = new Rule();
        copy.name = mRule.name + " (copy)";
        copy.icon = mRule.icon;
        copy.color = mRule.color;
        copy.matchAll = mRule.matchAll;
        copy.delaySec = mRule.delaySec;
        copy.onlyOnBattery = mRule.onlyOnBattery;
        copy.undo = mRule.undo;
        copy.notify = mRule.notify;
        for (Rule.Condition c : mRule.conditions) {
            copy.conditions.add(new Rule.Condition(c.type, c.param));
        }
        for (Rule.Action a : mRule.actions) copy.actions.add(new Rule.Action(a.type, a.value));
        mStore.put(copy);
        finish();
    }

    private void save() {
        readName();
        mRule.name = mRule.name.trim();
        if (mRule.name.isEmpty()) mRule.name = "My rule";
        if (mRule.conditions.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("Add at least one condition.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        for (Rule.Condition condition : mRule.conditions) {
            if (Rule.needsParam(condition.type) && condition.param.isEmpty()) {
                new AlertDialog.Builder(this).setMessage("Set the value of the condition \""
                        + Rule.label(Rule.CONDITIONS, Rule.CONDITION_LABELS, condition.type)
                        + "\".").setPositiveButton("OK", null).show();
                return;
            }
        }
        if (mRule.actions.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("Add at least one action.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        mStore.put(mRule);
        finish();
    }
}
