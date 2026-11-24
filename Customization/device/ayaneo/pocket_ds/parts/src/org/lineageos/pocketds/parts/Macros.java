/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Macros: a name and a short script that presses keys, one after the other. A button of the
 * console can run a macro (as the action of an extra button, or as what any button sends: the
 * input reader gives it the key code {@link #CODE_BASE} + id and the system tells PocketDS Extra,
 * patch 0025 of frameworks/base).
 *
 * <p>A script is words separated by spaces or new lines:
 * <ul>
 *   <li>{@code ctrl+s}: keys pressed together, then released ({@code f5} is one key);</li>
 *   <li>{@code down:shift} and {@code up:shift}: hold a key and let it go;</li>
 *   <li>{@code hold:space:500}: hold a key for 500 ms;</li>
 *   <li>{@code wait:300}: pause for 300 ms;</li>
 *   <li>a line starting with {@code #} is a comment.</li>
 * </ul>
 */
final class Macros {
    private static final String TAG = "PocketDSMacros";
    private static final String KEY_LIST = "macros";
    private static final String KEY_NEXT = "macro_next";

    /** The key code a button sends to run the macro n (the system keeps it for PocketDS Extra). */
    static final int CODE_BASE = 1000;
    static final int MAX_ID = 99;
    static final String ACTION_RUN = "org.lineageos.pocketds.parts.MACRO";

    private static final long PRESS_MS = 40;
    private static final long GAP_MS = 40;
    private static final long MAX_TOTAL_MS = 60_000;
    private static final int MAX_STEPS = 400;

    private static final AtomicBoolean sRunning = new AtomicBoolean();
    private static HandlerThread sThread;
    private static Handler sHandler;

    private Macros() {}

    /** A saved macro. */
    static final class Macro {
        int id;
        String name;
        String script;

        Macro(int id, String name, String script) {
            this.id = id;
            this.name = name;
            this.script = script;
        }
    }

    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int WAIT = 2;

    /** One thing a macro does: press a key, release it, or wait. */
    private static final class Step {
        final int kind;
        final int code;
        final long ms;

        Step(int kind, int code, long ms) {
            this.kind = kind;
            this.code = code;
            this.ms = ms;
        }
    }

    // ---- the list

    static List<Macro> all(Context context) {
        final List<Macro> list = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(PartsPrefs.get(context).getString(KEY_LIST,
                    "[]"));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.getJSONObject(i);
                list.add(new Macro(o.getInt("id"), o.optString("name", "Macro"),
                        o.optString("script", "")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Macro list unreadable", e);
        }
        return list;
    }

    static Macro find(Context context, int id) {
        for (Macro m : all(context)) {
            if (m.id == id) return m;
        }
        return null;
    }

    /** The name of a macro, or "Macro n" when it was deleted. */
    static String nameOf(Context context, int id) {
        final Macro m = find(context, id);
        return m == null ? "Macro " + id + " (deleted)" : m.name;
    }

    private static void write(Context context, List<Macro> list) {
        final JSONArray array = new JSONArray();
        try {
            for (Macro m : list) {
                array.put(new JSONObject().put("id", m.id).put("name", m.name)
                        .put("script", m.script));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Macro list not saved", e);
            return;
        }
        PartsPrefs.get(context).edit().putString(KEY_LIST, array.toString()).apply();
    }

    /** Saves a macro (a new one when its id is 0) and returns it, or null when there is no room. */
    static Macro save(Context context, int id, String name, String script) {
        final List<Macro> list = all(context);
        Macro target = null;
        for (Macro m : list) {
            if (m.id == id) target = m;
        }
        if (target == null) {
            int next = PartsPrefs.get(context).getInt(KEY_NEXT, 1);
            if (next > MAX_ID) return null;
            target = new Macro(next, name, script);
            PartsPrefs.get(context).edit().putInt(KEY_NEXT, next + 1).apply();
            list.add(target);
        }
        target.name = name;
        target.script = script;
        write(context, list);
        return target;
    }

    static void delete(Context context, int id) {
        final List<Macro> list = all(context);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) list.remove(i);
        }
        write(context, list);
    }

    // ---- the key code a button sends to run a macro

    static int codeOf(int id) {
        return CODE_BASE + id;
    }

    static boolean isMacroCode(int code) {
        return code > CODE_BASE && code <= CODE_BASE + MAX_ID;
    }

    // ---- the script

    private static final class Parsed {
        final List<Step> steps = new ArrayList<>();
        String error;
    }

    /** The first mistake of a script, or null when it can run. */
    static String check(String script) {
        return parse(script).error;
    }

    private static Parsed parse(String script) {
        final Parsed out = new Parsed();
        long total = 0;
        for (String line : script.split("\n")) {
            if (line.trim().startsWith("#")) continue;
            for (String word : line.trim().split("\\s+")) {
                if (word.isEmpty()) continue;
                final String lower = word.toLowerCase(Locale.ROOT);
                if (lower.startsWith("wait:")) {
                    final long ms = number(lower.substring(5));
                    if (ms < 0) return fail(out, word, "a time in milliseconds is expected");
                    out.steps.add(new Step(WAIT, 0, ms));
                    total += ms;
                } else if (lower.startsWith("down:") || lower.startsWith("up:")) {
                    final boolean down = lower.startsWith("down:");
                    final Keys.Key key = Keys.byId(lower.substring(down ? 5 : 3));
                    if (key == null) return fail(out, word, "unknown key");
                    out.steps.add(new Step(down ? DOWN : UP, key.code, 0));
                } else if (lower.startsWith("hold:")) {
                    final String[] parts = lower.substring(5).split(":");
                    final Keys.Key key = parts.length == 2 ? Keys.byId(parts[0]) : null;
                    final long ms = parts.length == 2 ? number(parts[1]) : -1;
                    if (key == null || ms < 0) return fail(out, word, "use hold:key:milliseconds");
                    out.steps.add(new Step(DOWN, key.code, 0));
                    out.steps.add(new Step(WAIT, 0, ms));
                    out.steps.add(new Step(UP, key.code, 0));
                    total += ms;
                } else {
                    final String[] names = lower.split("\\+");
                    final List<Keys.Key> keys = new ArrayList<>();
                    for (String name : names) {
                        final Keys.Key key = Keys.byId(name);
                        if (key == null) return fail(out, word, "unknown key \"" + name + "\"");
                        keys.add(key);
                    }
                    for (Keys.Key key : keys) out.steps.add(new Step(DOWN, key.code, 0));
                    out.steps.add(new Step(WAIT, 0, PRESS_MS));
                    for (int i = keys.size() - 1; i >= 0; i--) {
                        out.steps.add(new Step(UP, keys.get(i).code, 0));
                    }
                    out.steps.add(new Step(WAIT, 0, GAP_MS));
                    total += PRESS_MS + GAP_MS;
                }
                if (out.steps.size() > MAX_STEPS) return fail(out, word, "the macro is too long");
                if (total > MAX_TOTAL_MS) return fail(out, word, "the macro lasts over a minute");
            }
        }
        if (out.steps.isEmpty()) out.error = "The macro is empty";
        return out;
    }

    private static long number(String text) {
        try {
            final long value = Long.parseLong(text);
            return value >= 0 && value <= 30_000 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Parsed fail(Parsed out, String word, String why) {
        out.error = "\"" + word + "\": " + why;
        return out;
    }

    // ---- running

    static void run(Context context, int id) {
        final Macro macro = find(context, id);
        if (macro == null) return;
        run(context, macro.script);
    }

    /** Runs a script now. A macro that is still running is not started twice. */
    static void run(Context context, String script) {
        final Parsed parsed = parse(script);
        if (parsed.error != null) {
            Log.w(TAG, "Macro not run: " + parsed.error);
            return;
        }
        if (!sRunning.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        synchronized (Macros.class) {
            if (sThread == null) {
                sThread = new HandlerThread("PocketDSMacros");
                sThread.start();
                sHandler = new Handler(sThread.getLooper());
            }
        }
        sHandler.post(() -> {
            try {
                play(app, parsed.steps);
            } finally {
                sRunning.set(false);
            }
        });
    }

    private static void play(Context context, List<Step> steps) {
        final InputManager input = context.getSystemService(InputManager.class);
        final int display = targetDisplay(context);
        final List<Integer> held = new ArrayList<>();
        int meta = 0;
        long downTime = SystemClock.uptimeMillis();
        try {
            for (Step step : steps) {
                if (step.kind == WAIT) {
                    SystemClock.sleep(step.ms);
                    continue;
                }
                final boolean down = step.kind == DOWN;
                if (down) {
                    if (held.isEmpty()) downTime = SystemClock.uptimeMillis();
                    held.add(step.code);
                    meta |= Keys.metaOf(step.code);
                } else {
                    held.remove(Integer.valueOf(step.code));
                }
                inject(input, display, downTime, down, step.code, meta);
                if (!down) {
                    meta = 0;
                    for (int code : held) meta |= Keys.metaOf(code);
                }
            }
        } finally {
            // never leave a key stuck down
            for (int i = held.size() - 1; i >= 0; i--) {
                inject(input, display, downTime, false, held.get(i), 0);
            }
        }
    }

    private static void inject(InputManager input, int display, long downTime, boolean down,
            int code, int meta) {
        final int source = KeyEvent.isGamepadButton(code) ? InputDevice.SOURCE_GAMEPAD
                | InputDevice.SOURCE_KEYBOARD : InputDevice.SOURCE_KEYBOARD;
        final KeyEvent event = new KeyEvent(downTime, SystemClock.uptimeMillis(),
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        if (display != Display.INVALID_DISPLAY) event.setDisplayId(display);
        input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    /** The screen the controller is on (the focus lock of the system), or no particular one. */
    private static int targetDisplay(Context context) {
        try {
            final String value = Settings.Global.getString(context.getContentResolver(),
                    "locked_focus_display_id");
            if (value != null) return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // "none"
        }
        return Display.INVALID_DISPLAY;
    }
}
