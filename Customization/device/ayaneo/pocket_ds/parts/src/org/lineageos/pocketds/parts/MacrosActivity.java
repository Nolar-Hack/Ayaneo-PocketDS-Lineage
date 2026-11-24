/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The macros: a name and a short script of key presses. A macro is given to a button in the
 * remapping of the Controller page (any button, or an action of the extra buttons).
 */
public class MacrosActivity extends Activity {
    private Ui mUi;
    private LinearLayout mList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUi = new Ui(this);
        setTitle("Macros");
        final LinearLayout page = mUi.page(this);
        mUi.hero(page, "⌨️", Ui.PURPLE, "Macros",
                "Keys pressed one after the other by a single button");
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        page.addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mUi.note(page, "How to write one, words separated by spaces or new lines:\n"
                + "ctrl+s  keys pressed together, then released (f5, esc, enter, a, up...)\n"
                + "down:shift  and  up:shift  hold a key and let it go\n"
                + "hold:space:500  hold a key for 500 ms\n"
                + "wait:300  pause for 300 ms\n"
                + "# a line starting with # is a comment\n"
                + "Then give the macro to a button: Controller > Every app (or a profile) > tap "
                + "the button > Macro. The keys go to the screen the controller is on.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        paint();
    }

    private void paint() {
        mList.removeAllViews();
        final LinearLayout card = mUi.card(mList);
        final List<Macros.Macro> list = Macros.all(this);
        for (Macros.Macro m : list) {
            mUi.actionRow(card, "⌨️", Ui.PURPLE, m.name, firstLine(m.script), () -> edit(m));
        }
        mUi.actionRow(card, "➕", Ui.GREEN, "New macro",
                list.isEmpty() ? "No macro yet" : "Up to " + Macros.MAX_ID + " macros",
                () -> edit(null));
    }

    private static String firstLine(String script) {
        final String flat = script.trim().replaceAll("\\s+", " ");
        return flat.length() > 70 ? flat.substring(0, 70) + "…" : flat;
    }

    private void edit(Macros.Macro macro) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mUi.dp(20), mUi.dp(10), mUi.dp(20), 0);
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Name");
        name.setText(macro == null ? "" : macro.name);
        box.addView(name);
        final EditText script = new EditText(this);
        script.setHint("ctrl+s  wait:200  f5");
        script.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        script.setMinLines(5);
        script.setGravity(android.view.Gravity.TOP);
        script.setTypeface(android.graphics.Typeface.MONOSPACE);
        script.setText(macro == null ? "" : macro.script);
        box.addView(script);
        final LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(tools);
        final TextView insert = mUi.pill("Insert a key", Ui.sAccent, false, v -> pickKey(script));
        final TextView test = mUi.pill("Test in 5 s", Ui.GREEN, false, v -> {
            final String error = Macros.check(script.getText().toString());
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "Go to the app: the macro runs in 5 seconds",
                    Toast.LENGTH_LONG).show();
            final String text = script.getText().toString();
            new Handler(Looper.getMainLooper()).postDelayed(() -> Macros.run(this, text), 5000);
        });
        for (TextView pill : new TextView[] {insert, test}) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.topMargin = mUi.dp(10);
            lp.rightMargin = mUi.dp(8);
            tools.addView(pill, lp);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(macro == null ? "New macro" : "Macro").setView(box)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null);
        if (macro != null) {
            builder.setNeutralButton("Delete", (d, w) -> {
                Macros.delete(this, macro.id);
                paint();
            });
        }
        final AlertDialog dialog = builder.create();
        dialog.show();
        // Save stays open when the script has a mistake
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String text = script.getText().toString();
            final String error = Macros.check(text);
            String title = name.getText().toString().trim();
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                return;
            }
            if (title.isEmpty()) title = "Macro";
            if (Macros.save(this, macro == null ? 0 : macro.id, title, text) == null) {
                Toast.makeText(this, "Too many macros", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            paint();
        });
    }

    /** Adds the name of a key to the script. */
    private void pickKey(EditText script) {
        new AlertDialog.Builder(this).setTitle("Insert a key of…").setItems(Keys.GROUPS,
                (d, group) -> {
                    final List<Keys.Key> keys = Keys.group(group);
                    final String[] labels = new String[keys.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = keys.get(i).label;
                    new AlertDialog.Builder(this).setTitle(Keys.GROUPS[group])
                            .setItems(labels, (d2, which) -> {
                                final android.text.Editable text = script.getText();
                                final int at = script.getSelectionStart() < 0 ? text.length()
                                        : script.getSelectionStart();
                                final String before = at > 0 && !Character.isWhitespace(
                                        text.charAt(at - 1)) ? " " : "";
                                text.insert(at, before + keys.get(which).id + " ");
                            }).setNegativeButton("Cancel", null).show();
                }).setNegativeButton("Cancel", null).show();
    }
}
