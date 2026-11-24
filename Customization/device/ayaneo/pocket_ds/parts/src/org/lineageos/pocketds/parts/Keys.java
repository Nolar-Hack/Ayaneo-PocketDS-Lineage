/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every key a button can be given or a macro can press: names, Android key codes, groups for the
 * picker. The names are what a macro is written with ("ctrl+s", "f5", "esc"...).
 */
final class Keys {
    static final String[] GROUPS = {"Letters", "Digits", "F keys", "Navigation", "Modifiers",
        "Symbols", "Numpad", "Media and system", "Controller"};

    /** One key: the name shown, the name used in macros, the Android key code. */
    static final class Key {
        final String label;
        final String id;
        final int code;
        final int group;

        Key(String label, String id, int code, int group) {
            this.label = label;
            this.id = id;
            this.code = code;
            this.group = group;
        }
    }

    private static final List<Key> ALL = new ArrayList<>();

    static {
        for (int i = 0; i < 26; i++) {
            final String letter = String.valueOf((char) ('A' + i));
            add(letter, letter.toLowerCase(Locale.ROOT), KeyEvent.KEYCODE_A + i, 0);
        }
        for (int i = 0; i < 10; i++) {
            add(String.valueOf(i), String.valueOf(i), KeyEvent.KEYCODE_0 + i, 1);
        }
        for (int i = 1; i <= 12; i++) {
            add("F" + i, "f" + i, KeyEvent.KEYCODE_F1 + i - 1, 2);
        }
        add("Esc", "esc", KeyEvent.KEYCODE_ESCAPE, 3);
        add("Tab", "tab", KeyEvent.KEYCODE_TAB, 3);
        add("Enter", "enter", KeyEvent.KEYCODE_ENTER, 3);
        add("Space", "space", KeyEvent.KEYCODE_SPACE, 3);
        add("Backspace", "backspace", KeyEvent.KEYCODE_DEL, 3);
        add("Delete", "delete", KeyEvent.KEYCODE_FORWARD_DEL, 3);
        add("Insert", "insert", KeyEvent.KEYCODE_INSERT, 3);
        add("Up", "up", KeyEvent.KEYCODE_DPAD_UP, 3);
        add("Down", "down", KeyEvent.KEYCODE_DPAD_DOWN, 3);
        add("Left", "left", KeyEvent.KEYCODE_DPAD_LEFT, 3);
        add("Right", "right", KeyEvent.KEYCODE_DPAD_RIGHT, 3);
        add("Page up", "pageup", KeyEvent.KEYCODE_PAGE_UP, 3);
        add("Page down", "pagedown", KeyEvent.KEYCODE_PAGE_DOWN, 3);
        add("Home key", "homekey", KeyEvent.KEYCODE_MOVE_HOME, 3);
        add("End", "end", KeyEvent.KEYCODE_MOVE_END, 3);
        add("Ctrl", "ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 4);
        add("Right Ctrl", "rctrl", KeyEvent.KEYCODE_CTRL_RIGHT, 4);
        add("Alt", "alt", KeyEvent.KEYCODE_ALT_LEFT, 4);
        add("Right Alt", "ralt", KeyEvent.KEYCODE_ALT_RIGHT, 4);
        add("Shift", "shift", KeyEvent.KEYCODE_SHIFT_LEFT, 4);
        add("Right Shift", "rshift", KeyEvent.KEYCODE_SHIFT_RIGHT, 4);
        add("Meta", "meta", KeyEvent.KEYCODE_META_LEFT, 4);
        add("Right Meta", "rmeta", KeyEvent.KEYCODE_META_RIGHT, 4);
        add("Caps lock", "caps", KeyEvent.KEYCODE_CAPS_LOCK, 4);
        add("- _", "minus", KeyEvent.KEYCODE_MINUS, 5);
        add("= +", "equals", KeyEvent.KEYCODE_EQUALS, 5);
        add("[", "lbracket", KeyEvent.KEYCODE_LEFT_BRACKET, 5);
        add("]", "rbracket", KeyEvent.KEYCODE_RIGHT_BRACKET, 5);
        add("\\", "backslash", KeyEvent.KEYCODE_BACKSLASH, 5);
        add(";", "semicolon", KeyEvent.KEYCODE_SEMICOLON, 5);
        add("'", "apostrophe", KeyEvent.KEYCODE_APOSTROPHE, 5);
        add("`", "grave", KeyEvent.KEYCODE_GRAVE, 5);
        add(",", "comma", KeyEvent.KEYCODE_COMMA, 5);
        add(".", "period", KeyEvent.KEYCODE_PERIOD, 5);
        add("/", "slash", KeyEvent.KEYCODE_SLASH, 5);
        for (int i = 0; i < 10; i++) {
            add("Num " + i, "num" + i, KeyEvent.KEYCODE_NUMPAD_0 + i, 6);
        }
        add("Num +", "numplus", KeyEvent.KEYCODE_NUMPAD_ADD, 6);
        add("Num -", "numminus", KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 6);
        add("Num *", "nummul", KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 6);
        add("Num /", "numdiv", KeyEvent.KEYCODE_NUMPAD_DIVIDE, 6);
        add("Num .", "numdot", KeyEvent.KEYCODE_NUMPAD_DOT, 6);
        add("Num Enter", "numenter", KeyEvent.KEYCODE_NUMPAD_ENTER, 6);
        add("Num lock", "numlock", KeyEvent.KEYCODE_NUM_LOCK, 6);
        add("Play / pause", "playpause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 7);
        add("Next track", "next", KeyEvent.KEYCODE_MEDIA_NEXT, 7);
        add("Previous track", "previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS, 7);
        add("Stop", "stop", KeyEvent.KEYCODE_MEDIA_STOP, 7);
        add("Mute", "mute", KeyEvent.KEYCODE_VOLUME_MUTE, 7);
        add("Volume up", "volup", KeyEvent.KEYCODE_VOLUME_UP, 7);
        add("Volume down", "voldown", KeyEvent.KEYCODE_VOLUME_DOWN, 7);
        add("Brightness up", "brightup", KeyEvent.KEYCODE_BRIGHTNESS_UP, 7);
        add("Brightness down", "brightdown", KeyEvent.KEYCODE_BRIGHTNESS_DOWN, 7);
        add("Screenshot (Print)", "print", KeyEvent.KEYCODE_SYSRQ, 7);
        add("Back", "back", KeyEvent.KEYCODE_BACK, 7);
        add("Menu", "menu", KeyEvent.KEYCODE_MENU, 7);
        add("Pad A", "a_btn", KeyEvent.KEYCODE_BUTTON_A, 8);
        add("Pad B", "b_btn", KeyEvent.KEYCODE_BUTTON_B, 8);
        add("Pad X", "x_btn", KeyEvent.KEYCODE_BUTTON_X, 8);
        add("Pad Y", "y_btn", KeyEvent.KEYCODE_BUTTON_Y, 8);
        add("L1", "l1", KeyEvent.KEYCODE_BUTTON_L1, 8);
        add("R1", "r1", KeyEvent.KEYCODE_BUTTON_R1, 8);
        add("L2", "l2", KeyEvent.KEYCODE_BUTTON_L2, 8);
        add("R2", "r2", KeyEvent.KEYCODE_BUTTON_R2, 8);
        add("L3", "l3", KeyEvent.KEYCODE_BUTTON_THUMBL, 8);
        add("R3", "r3", KeyEvent.KEYCODE_BUTTON_THUMBR, 8);
        add("Start", "start", KeyEvent.KEYCODE_BUTTON_START, 8);
        add("Select", "select", KeyEvent.KEYCODE_BUTTON_SELECT, 8);
        add("Mode", "mode", KeyEvent.KEYCODE_BUTTON_MODE, 8);
    }

    private Keys() {}

    /** The pages of the drawn keyboard (Controller > remapping > Keyboard). */
    static final String[] PAGES = {"Main", "Navigation", "Numpad", "Media"};

    /**
     * The rows of a page of the drawn keyboard: a key is "id" or "id:weight" (its width compared
     * with a normal key), "-:weight" is an empty space.
     */
    static String[][] rows(int page) {
        switch (page) {
            case 1:
                return new String[][] {
                    {"esc", "tab", "enter", "space", "backspace:1.5"},
                    {"insert", "homekey", "pageup"},
                    {"delete", "end", "pagedown"},
                    {"-", "up", "-"},
                    {"left", "down", "right"}};
            case 2:
                return new String[][] {
                    {"numlock", "numdiv", "nummul", "numminus"},
                    {"num7", "num8", "num9", "numplus"},
                    {"num4", "num5", "num6", "numenter"},
                    {"num1", "num2", "num3", "numdot"},
                    {"num0:2", "-:2"}};
            case 3:
                return new String[][] {
                    {"playpause:2", "stop", "previous:1.5", "next:1.5"},
                    {"mute", "voldown", "volup", "brightdown", "brightup"},
                    {"print:2", "back", "menu", "caps"}};
            default:
                return new String[][] {
                    {"esc:1.5", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10",
                        "f11", "f12"},
                    {"grave", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "minus",
                        "equals", "backspace:2"},
                    {"tab:1.5", "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "lbracket",
                        "rbracket", "backslash:1.5"},
                    {"caps:1.75", "a", "s", "d", "f", "g", "h", "j", "k", "l", "semicolon",
                        "apostrophe", "enter:2.25"},
                    {"shift:2.25", "z", "x", "c", "v", "b", "n", "m", "comma", "period", "slash",
                        "rshift:2.75"},
                    {"ctrl:1.5", "meta:1.25", "alt:1.25", "space:6", "ralt:1.25", "rctrl:1.5",
                        "left", "up", "down", "right"}};
        }
    }

    /** What a key shown on a key cap is called: short names for the long ones. */
    static String cap(Key key) {
        switch (key.id) {
            case "backspace": return "⌫";
            case "enter": return "Enter ↵";
            case "homekey": return "Home";
            case "pageup": return "PgUp";
            case "pagedown": return "PgDn";
            case "up": return "↑";
            case "down": return "↓";
            case "left": return "←";
            case "right": return "→";
            case "playpause": return "Play / pause";
            case "previous": return "Previous";
            case "brightdown": return "Bright −";
            case "brightup": return "Bright +";
            case "voldown": return "Vol −";
            case "volup": return "Vol +";
            case "print": return "Screenshot";
            case "rshift": return "Shift";
            case "rctrl": return "Ctrl";
            case "ralt": return "Alt";
            case "numminus": return "Num −";
            default: return key.label;
        }
    }

    private static void add(String label, String id, int code, int group) {
        ALL.add(new Key(label, id, code, group));
    }

    static List<Key> group(int group) {
        final List<Key> list = new ArrayList<>();
        for (Key key : ALL) {
            if (key.group == group) list.add(key);
        }
        return list;
    }

    /** The key of a name used in macros, or null. */
    static Key byId(String id) {
        final String wanted = id.toLowerCase(Locale.ROOT);
        for (Key key : ALL) {
            if (key.id.equals(wanted)) return key;
        }
        return null;
    }

    /** The key of an Android key code, or null when it is not in the list. */
    static Key byCode(int code) {
        for (Key key : ALL) {
            if (key.code == code) return key;
        }
        return null;
    }

    /** The meta flags a modifier key puts on the keys pressed while it is held (0 for others). */
    static int metaOf(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_META_LEFT:
                return KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
            case KeyEvent.KEYCODE_META_RIGHT:
                return KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
            default:
                return 0;
        }
    }
}
