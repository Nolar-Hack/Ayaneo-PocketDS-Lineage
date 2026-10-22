/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The keyboard of the bottom screen: it fills that screen, in the colours of the overlay
 * (see mockup "Keyboard on the bottom screen"). Letters (QWERTY or AZERTY), a number / symbol
 * layer, shift with caps lock, a repeating backspace, and the word being typed in the top bar.
 */
public class PocketKeyboardService extends InputMethodService {
    private static final int BG = 0xFF12151B;
    private static final int KEY = 0xFF252B36;
    private static final int KEY_FN = 0xFF1B2029;
    private static final int LINE = 0xFF333B49;
    private static final int TEXT = 0xFFE9ECF2;
    private static final int DIM = 0xFF98A2B3;
    private static int ACCENT = 0xFF5B9DFF;
    private static final int ON_ACCENT = 0xFF07101F;

    private static final String[][] QWERTY = {
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {"z", "x", "c", "v", "b", "n", "m"}};
    private static final String[][] AZERTY = {
        {"a", "z", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"q", "s", "d", "f", "g", "h", "j", "k", "l", "m"},
        {"w", "x", "c", "v", "b", "n"}};
    private static final String[][] SYMBOLS = {
        {"@", "#", "$", "%", "&", "-", "+", "(", ")", "/"},
        {"*", "\"", "'", ":", ";", "!", "?", "=", "_", "~"},
        {"<", ">", "[", "]", "{", "}", "\\"}};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private android.widget.FrameLayout mRoot;
    private LinearLayout mColumn;
    private TextView mPopup;
    private final TextView[] mSuggest = new TextView[3];
    private Dictionary mFr;
    private Dictionary mEn;
    private boolean mShift;
    private boolean mCaps;
    private boolean mSymbols;
    private boolean mAzerty = Locale.getDefault().getLanguage().equals("fr");
    private final StringBuilder mTyped = new StringBuilder();

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public View onCreateInputView() {
        mRoot = new android.widget.FrameLayout(this);
        mRoot.setBackgroundColor(BG);
        mColumn = new LinearLayout(this);
        mColumn.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(10);
        mColumn.setPadding(dp(14), pad, dp(14), pad);
        mRoot.addView(mColumn, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPopup = new TextView(this);
        mPopup.setGravity(Gravity.CENTER);
        mPopup.setTextColor(TEXT);
        mPopup.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        mPopup.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mPopup.setBackground(shape(0xFF2C3442, ACCENT, 14));
        mPopup.setElevation(dp(8));
        mPopup.setVisibility(View.GONE);
        mRoot.addView(mPopup, new android.widget.FrameLayout.LayoutParams(dp(60), dp(70)));
        // On the bottom screen the keyboard takes the whole screen (the app is on the other one);
        // on the main screen (a swap, or only that screen on) it keeps to the lower half.
        applyHeight();
        render();
        return mRoot;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        ACCENT = Accent.current(this);
        mTyped.setLength(0);
        mPrev = "";
        mShift = false;
        mSymbols = false;
        if (mRoot != null) {
            applyHeight();
            render();
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private GradientDrawable shape(int fill, int stroke, float radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(1) / 2), stroke);
        return drawable;
    }

    // ---- height

    /** The display of the app that has the focus, or -1 when it cannot be told. */
    private int focusedDisplay() {
        try {
            final android.app.ActivityTaskManager.RootTaskInfo info = android.app.ActivityTaskManager
                    .getService().getFocusedRootTaskInfo();
            return info == null ? -1 : info.displayId;
        } catch (android.os.RemoteException | RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /**
     * True when the app that owns the field is shown on the screen of the keyboard (a name asked
     * by the list of apps, an app moved down): the field then stays in sight above the keys.
     */
    private boolean fieldOnKeyboardScreen(int imeDisplay) {
        final EditorInfo info = getCurrentInputEditorInfo();
        final String pkg = info == null ? null : info.packageName;
        if (pkg == null || imeDisplay < 0) return false;
        try {
            for (android.app.ActivityManager.RunningTaskInfo task
                    : android.app.ActivityTaskManager.getService().getTasks(40, false, false, imeDisplay)) {
                if (task.displayId == imeDisplay && task.isVisible
                        && task.baseActivity != null
                        && pkg.equals(task.baseActivity.getPackageName())) {
                    return true;
                }
            }
        } catch (android.os.RemoteException | RuntimeException | LinkageError e) {
            // unknown: the keyboard is taken as covering the field
        }
        return false;
    }

    /**
     * On the second screen the keyboard takes the whole screen when the field is in an app of the
     * other screen (the keyboard is then all that this screen shows, and an editing bar above the
     * keys shows what is typed). When the field is on the same screen, the keyboard keeps to the
     * lower part so that the field stays in sight.
     */
    private boolean mFull;

    private void applyHeight() {
        // The screen the keyboard is on is read from the size of its window: the bottom panel
        // (1024 x 768) or the main one (1920 x 1080), whichever display number it has now.
        final android.graphics.Rect bounds = getSystemService(android.view.WindowManager.class)
                .getCurrentWindowMetrics().getBounds();
        final int displayHeight = bounds.height();
        final int imeDisplay = SystemProperties_getInt("persist.sys.pocketds.ime_display", -1);
        // The keyboard lives on the second screen, the bottom panel or, after a swap, the top one.
        final boolean bottom = imeDisplay > android.view.Display.DEFAULT_DISPLAY;
        final int editorDisplay = focusedDisplay();
        final boolean sameScreen = fieldOnKeyboardScreen(imeDisplay);
        final boolean full = bottom && !sameScreen;
        mFull = full;
        // The input frame wraps its content: a minimum height sets the height of the keyboard.
        final int height = full ? displayHeight : Math.round(displayHeight * 0.5f);
        // The rows share the height of the column by their weights: the column needs the exact
        // height, a minimum on the parent alone leaves each row at the size of its text.
        mRoot.setMinimumHeight(height);
        mColumn.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        final int side = Math.max(dp(14), (bounds.width() - dp(1000)) / 2);
        mColumn.setPadding(side, dp(10), side, dp(10));
        mRoot.requestLayout();
        android.util.Log.i("PocketDSKeyboard", "height " + height + " full=" + full
                + " bottom=" + bottom + " editorDisplay=" + editorDisplay
                + " imeDisplay=" + imeDisplay);
    }

    private static int SystemProperties_getInt(String key, int fallback) {
        return android.os.SystemProperties.getInt(key, fallback);
    }

    // ---- layout

    private void render() {
        mColumn.removeAllViews();
        mPreview = null;
        if (mFull) addEditBar();
        // top bar: three suggestions for the word being typed
        final LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < mSuggest.length; i++) {
            if (i > 0) {
                final View divider = new View(this);
                divider.setBackgroundColor(LINE);
                bar.addView(divider, new LinearLayout.LayoutParams(Math.max(1, dp(1) / 2),
                        dp(26)));
            }
            final TextView view = new TextView(this);
            view.setGravity(Gravity.CENTER);
            view.setTextColor(TEXT);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            view.setSingleLine(true);
            view.setEllipsize(android.text.TextUtils.TruncateAt.END);
            view.setPadding(dp(6), 0, dp(6), 0);
            view.setOnClickListener(v -> {
                final CharSequence word = ((TextView) v).getText();
                if (word.length() > 0) applySuggestion(word.toString());
            });
            mSuggest[i] = view;
            bar.addView(view, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        mColumn.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                0.8f));
        updateSuggestions();

        final String[] numbers = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
        mColumn.addView(row(numbers, true, 0, 0), rowParams());
        final String[][] letters = mSymbols ? SYMBOLS : (mAzerty ? AZERTY : QWERTY);
        mColumn.addView(row(letters[0], false, 0, 0), rowParams());
        mColumn.addView(row(letters[1], false, letters[1].length < 10 ? 33 : 0, 0), rowParams());

        final LinearLayout third = new LinearLayout(this);
        third.setOrientation(LinearLayout.HORIZONTAL);
        third.addView(fnKey(mSymbols ? "" : "⇧", mShift || mCaps, 1.5f, v -> toggleShift()),
                keyParams(1.5f));
        for (String letter : letters[2]) third.addView(letterKey(letter), keyParams(1f));
        final TextView back = fnKey("⌫", false, 1.5f, null);
        back.setOnTouchListener(new RepeatTouch(() -> sendKey(android.view.KeyEvent.KEYCODE_DEL)));
        third.addView(back, keyParams(1.5f));
        mColumn.addView(third, rowParams());

        final LinearLayout bottom = new LinearLayout(this);
        bottom.addView(fnKey(mSymbols ? "ABC" : "?123", false, 1.6f, v -> {
            mSymbols = !mSymbols;
            render();
        }), keyParams(1.6f));
        bottom.addView(fnKey(",", false, 1f, v -> commit(",")), keyParams(1f));
        final TextView space = fnKey(mAzerty ? "Français" : "English", false, 5f,
                v -> commit(" "));
        space.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        space.setTextColor(DIM);
        space.setBackground(pressable(KEY, LINE, 0xFF3D4A5E));
        space.setOnLongClickListener(v -> {
            mAzerty = !mAzerty;
            render();
            return true;
        });
        bottom.addView(space, keyParams(5f));
        bottom.addView(fnKey(".", false, 1f, v -> commit(".")), keyParams(1f));
        final TextView enter = fnKey("⏎", false, 1.6f, v -> enter());
        enter.setTextColor(ON_ACCENT);
        enter.setBackground(pressable(ACCENT, ACCENT, 0xFF8DBBFF));
        bottom.addView(enter, keyParams(1.6f));
        mColumn.addView(bottom, rowParams());
    }

    // ---- editing bar: what is typed, with the cursor and the selection, and the editing actions

    private TextView mPreview;
    private int mPreviewBase = -1;      // offset of the shown text in the field, -1 when unknown
    private int mWindowStart;           // first character of the field shown
    private int mPrefix;                // length of the "…" put before the shown text
    private int mCaretAt = -1;          // where the caret mark was inserted in the shown text
    private int mSelStart;
    private int mSelEnd;
    private String mFieldText = "";     // the text of the field as last read
    private boolean mExtend;            // "Sel" is on: the arrows extend the selection
    private int mExtendAnchor;          // where the selection started
    private TextView mExtendKey;
    private long mLastDown;
    private int mLastDownOffset = -1;

    private void addEditBar() {
        mPreview = new TextView(this);
        mPreview.setTextColor(TEXT);
        mPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mPreview.setGravity(Gravity.CENTER_VERTICAL);
        mPreview.setPadding(dp(16), dp(4), dp(16), dp(4));
        mPreview.setMaxLines(2);
        mPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mPreview.setBackground(shape(0xFF0E1116, ACCENT, 12));
        mPreview.setOnTouchListener(new View.OnTouchListener() {
            private int mAnchor;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                final int offset = shownToField(mPreview.getOffsetForPosition(event.getX(),
                        event.getY()));
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        final long now = event.getEventTime();
                        if (now - mLastDown < 500 && Math.abs(offset - mLastDownOffset) <= 2) {
                            // double tap: the word under the finger
                            int from = Math.min(offset, mFieldText.length());
                            int to = from;
                            while (from > 0 && Character.isLetterOrDigit(
                                    mFieldText.charAt(from - 1))) from--;
                            while (to < mFieldText.length() && Character.isLetterOrDigit(
                                    mFieldText.charAt(to))) to++;
                            mAnchor = from;
                            select(from, to);
                            mLastDown = 0;
                            break;
                        }
                        mLastDown = now;
                        mLastDownOffset = offset;
                        mAnchor = offset;
                        select(offset, offset);
                        break;
                    }
                    case MotionEvent.ACTION_MOVE:
                        select(Math.min(mAnchor, offset), Math.max(mAnchor, offset));
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        mColumn.addView(mPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.3f));

        final LinearLayout actions = new LinearLayout(this);
        final TextView left = fnKey("◀", false, 1f, null);
        left.setOnTouchListener(new RepeatTouch(() -> moveCursor(false)));
        final TextView right = fnKey("▶", false, 1f, null);
        right.setOnTouchListener(new RepeatTouch(() -> moveCursor(true)));
        mExtendKey = fnKey("Sel", mExtend, 1.2f, v -> {
            mExtend = !mExtend;
            mExtendAnchor = mSelStart;
            mExtendKey.setTextColor(mExtend ? ON_ACCENT : 0xFFC4CBD8);
            mExtendKey.setBackground(pressable(mExtend ? ACCENT : KEY_FN, LINE, 0xFF3D4A5E));
        });
        actions.addView(mExtendKey, keyParams(1.2f));
        actions.addView(left, keyParams(1f));
        actions.addView(right, keyParams(1f));
        actions.addView(fnKey("All", false, 1.4f, v -> menuAction(android.R.id.selectAll)),
                keyParams(1.4f));
        actions.addView(fnKey("Cut", false, 1.4f, v -> menuAction(android.R.id.cut)),
                keyParams(1.4f));
        actions.addView(fnKey("Copy", false, 1.4f, v -> menuAction(android.R.id.copy)),
                keyParams(1.4f));
        actions.addView(fnKey("Paste", false, 1.4f, v -> menuAction(android.R.id.paste)),
                keyParams(1.4f));
        actions.addView(fnKey("Clear", false, 1.4f, v -> clearField()), keyParams(1.4f));
        final LinearLayout.LayoutParams params = rowParams();
        params.weight = 0.9f;
        mColumn.addView(actions, params);
        refreshPreview();
    }

    private void menuAction(int id) {
        final InputConnection input = getCurrentInputConnection();
        if (input != null) input.performContextMenuAction(id);
    }

    private void clearField() {
        final InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        input.performContextMenuAction(android.R.id.selectAll);
        input.commitText("", 1);
        mTyped.setLength(0);
        updateSuggestions();
    }

    /** One letter to the left or right; with a selection the cursor goes to its end. */
    private void moveCursor(boolean forward) {
        final int code = forward ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                : android.view.KeyEvent.KEYCODE_DPAD_LEFT;
        final InputConnection input = getCurrentInputConnection();
        if (mExtend && input != null && mPreviewBase >= 0) {
            // The selection grows or shrinks by one letter on the side that moves.
            final int focus = mSelStart == mExtendAnchor ? mSelEnd : mSelStart;
            final int moved = Math.max(0, Math.min(mFieldText.length(),
                    focus + (forward ? 1 : -1)));
            select(Math.min(mExtendAnchor, moved), Math.max(mExtendAnchor, moved));
            return;
        }
        sendKey(code);
        mTyped.setLength(0);
        updateSuggestions();
    }

    private void select(int start, int end) {
        final InputConnection input = getCurrentInputConnection();
        if (input == null || mPreviewBase < 0) return;
        input.setSelection(mPreviewBase + start, mPreviewBase + end);
    }

    /** A position in the text as shown (with the "…" and the caret mark) to one in the field. */
    private int shownToField(int shown) {
        int offset = shown - mPrefix;
        if (mCaretAt >= 0 && shown > mCaretAt) offset -= 1;
        return Math.max(0, offset + mWindowStart);
    }

    private void refreshPreview() {
        if (mPreview == null) return;
        final InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        String text;
        int start;
        int end;
        android.view.inputmethod.ExtractedText extracted = input.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (extracted != null && extracted.text != null) {
            text = extracted.text.toString();
            start = Math.max(0, extracted.selectionStart);
            end = Math.max(start, extracted.selectionEnd);
            mPreviewBase = extracted.startOffset;
        } else {
            final CharSequence before = input.getTextBeforeCursor(500, 0);
            final CharSequence selected = input.getSelectedText(0);
            final CharSequence after = input.getTextAfterCursor(500, 0);
            final String b = before == null ? "" : before.toString();
            final String sel = selected == null ? "" : selected.toString();
            text = b + sel + (after == null ? "" : after.toString());
            start = b.length();
            end = start + sel.length();
            mPreviewBase = -1;
        }
        mFieldText = text;
        final int length = text.length();
        start = Math.min(start, length);
        end = Math.min(end, length);
        // Long text: only the part around the cursor is shown.
        final int from = Math.max(0, start - 70);
        final int to = Math.min(length, end + 70);
        final String prefix = from > 0 ? "…" : "";
        final String suffix = to < length ? "…" : "";
        final String shown = prefix + text.substring(from, start)
                + (start == end ? "|" : "") + text.substring(start, to) + suffix;
        mWindowStart = from;
        mPrefix = prefix.length();
        mCaretAt = start == end ? mPrefix + (start - from) : -1;
        mSelStart = start;
        mSelEnd = end;
        final EditorInfo info = getCurrentInputEditorInfo();
        if (length == 0) {
            final CharSequence hint = info == null ? null : info.hintText;
            final android.text.SpannableString empty = new android.text.SpannableString(
                    "|" + (hint == null ? "" : " " + hint));
            empty.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, 1, 0);
            if (empty.length() > 1) {
                empty.setSpan(new android.text.style.ForegroundColorSpan(DIM), 1, empty.length(),
                        0);
            }
            mCaretAt = 0;
            mPreview.setText(empty);
            return;
        }
        final android.text.SpannableString spanned = new android.text.SpannableString(shown);
        if (start == end) {
            spanned.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), mCaretAt,
                    mCaretAt + 1, 0);
            spanned.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), mCaretAt,
                    mCaretAt + 1, 0);
        } else {
            spanned.setSpan(new android.text.style.BackgroundColorSpan((ACCENT & 0x00FFFFFF)
                    | 0x66000000), mPrefix + (start - from), mPrefix + (end - from), 0);
        }
        mPreview.setText(spanned);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart,
                candidatesEnd);
        if (mPreview != null) mHandler.post(this::refreshPreview);
    }

    private LinearLayout row(String[] keys, boolean function, int sidePadding, int unused) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (sidePadding > 0) row.setPadding(dp(sidePadding), 0, dp(sidePadding), 0);
        for (String key : keys) {
            row.addView(function ? numberKey(key) : letterKey(key), keyParams(1f));
        }
        return row;
    }

    private LinearLayout.LayoutParams rowParams() {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams keyParams(float weight) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private TextView baseKey(String label, int fill, int color) {
        final TextView key = new TextView(this);
        key.setText(label);
        key.setGravity(Gravity.CENTER);
        key.setTextColor(color);
        key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        key.setBackground(pressable(fill, LINE, 0xFF3D4A5E));
        return key;
    }

    /** A key background that lights up while it is pressed. */
    private android.graphics.drawable.StateListDrawable pressable(int fill, int stroke,
            int pressed) {
        final android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, shape(pressed, ACCENT, 10));
        states.addState(new int[] {}, shape(fill, stroke, 10));
        return states;
    }

    private void tap(View view) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** A bigger copy of the key above the finger, so that one sees which key is pressed. */
    private void showPopup(View key, CharSequence label) {
        final int[] root = new int[2];
        final int[] at = new int[2];
        mRoot.getLocationInWindow(root);
        key.getLocationInWindow(at);
        final int width = Math.max(dp(60), Math.round(key.getWidth() * 1.3f));
        final int height = Math.round(key.getHeight() * 1.2f);
        final android.widget.FrameLayout.LayoutParams params =
                new android.widget.FrameLayout.LayoutParams(width, height);
        params.leftMargin = Math.max(0, Math.min(mRoot.getWidth() - width,
                at[0] - root[0] + key.getWidth() / 2 - width / 2));
        params.topMargin = Math.max(0, at[1] - root[1] - height - dp(4));
        mPopup.setLayoutParams(params);
        mPopup.setText(label);
        mPopup.setVisibility(View.VISIBLE);
    }

    /** Accented letters offered on a long press. */
    private static final java.util.Map<String, String> VARIANTS = new java.util.HashMap<>();
    static {
        VARIANTS.put("a", "àâäáãå");
        VARIANTS.put("e", "éèêë€");
        VARIANTS.put("i", "îïíì");
        VARIANTS.put("o", "ôöóòõœ");
        VARIANTS.put("u", "ùûüú");
        VARIANTS.put("c", "ç");
        VARIANTS.put("y", "ÿý");
        VARIANTS.put("n", "ñ");
        VARIANTS.put("s", "ß");
    }
    private static final long LONG_PRESS_MS = 380;

    private String mVariants;          // variants being offered, null when none
    private boolean mVariantsUpper;
    private Runnable mLongPress;

    private void showVariants(View key, String variants) {
        final StringBuilder shown = new StringBuilder();
        for (int i = 0; i < variants.length(); i++) {
            if (i > 0) shown.append("  ");
            shown.append(variants.charAt(i));
        }
        showPopup(key, shown);
        final android.widget.FrameLayout.LayoutParams params =
                (android.widget.FrameLayout.LayoutParams) mPopup.getLayoutParams();
        params.width = variants.length() * dp(44);
        params.leftMargin = Math.max(0, Math.min(mRoot.getWidth() - params.width,
                params.leftMargin - (params.width - dp(60)) / 2));
        mPopup.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        mPopup.setLayoutParams(params);
    }

    /** A key that types on the touch itself (no wait for the finger to lift). */
    private void typeKey(TextView key, String text, boolean letter) {
        key.setClickable(true);
        final String lower = text.toLowerCase(Locale.ROOT);
        final String variants = letter && mAzerty ? VARIANTS.get(lower)
                : letter ? VARIANTS.get(lower) : null;
        key.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tap(v);
                    mPopup.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
                    showPopup(v, text);
                    commit(text);
                    mVariants = null;
                    if (variants != null) {
                        mLongPress = () -> {
                            mVariantsUpper = !text.equals(lower);
                            mVariants = mVariantsUpper ? variants.toUpperCase(Locale.ROOT)
                                    : variants;
                            tap(v);
                            showVariants(v, mVariants);
                        };
                        mHandler.postDelayed(mLongPress, LONG_PRESS_MS);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mLongPress != null) mHandler.removeCallbacks(mLongPress);
                    mLongPress = null;
                    if (mVariants != null && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        // The letter already typed is replaced by the one under the finger
                        // (the finger slides to the right over the choices; none = stays).
                        final int index = Math.max(0, Math.min(mVariants.length() - 1,
                                (int) ((event.getX() - v.getWidth() / 2f) / dp(44)
                                        + mVariants.length() / 2f)));
                        final String chosen = mVariants.substring(index, index + 1);
                        final InputConnection input = getCurrentInputConnection();
                        if (input != null) {
                            input.deleteSurroundingText(1, 0);
                            input.commitText(chosen, 1);
                            if (mTyped.length() > 0) mTyped.setLength(mTyped.length() - 1);
                            mTyped.append(chosen);
                            updateSuggestions();
                        }
                    }
                    mVariants = null;
                    mPopup.setVisibility(View.GONE);
                    if (letter && mShift && !mCaps) {
                        mShift = false;
                        render();
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private TextView letterKey(String letter) {
        final boolean upper = (mShift || mCaps) && !mSymbols;
        final String shown = upper ? letter.toUpperCase(Locale.ROOT) : letter;
        final TextView key = baseKey(shown, KEY, TEXT);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        typeKey(key, shown, true);
        return key;
    }

    private TextView numberKey(String digit) {
        final TextView key = baseKey(digit, KEY_FN, 0xFFC4CBD8);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        typeKey(key, digit, false);
        return key;
    }

    private TextView fnKey(String label, boolean active, float unused, View.OnClickListener click) {
        final TextView key = baseKey(label, active ? ACCENT : KEY_FN, active ? ON_ACCENT : 0xFFC4CBD8);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        key.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (click != null) {
            key.setOnClickListener(v -> {
                tap(v);
                click.onClick(v);
            });
        }
        return key;
    }

    // ---- suggestions (word lists of the AOSP keyboard, assets/words_fr.txt and words_en.txt)

    /** Words sorted by spelling, with their frequency: a prefix is found by a binary search. */
    private static final class Dictionary {
        final String[] words;
        final int[] freq;

        Dictionary(String[] words, int[] freq) {
            this.words = words;
            this.freq = freq;
        }
    }

    private Dictionary loadDictionary(String asset) {
        final java.util.ArrayList<String> words = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> freq = new java.util.ArrayList<>();
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                getAssets().open(asset), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                final int space = line.indexOf(' ');
                if (space <= 0) continue;
                words.add(line.substring(0, space));
                freq.add(Integer.parseInt(line.substring(space + 1).trim()));
            }
        } catch (java.io.IOException | RuntimeException e) {
            android.util.Log.w("PocketDSKeyboard", "No word list " + asset, e);
        }
        final int[] frequencies = new int[freq.size()];
        for (int i = 0; i < frequencies.length; i++) frequencies[i] = freq.get(i);
        return new Dictionary(words.toArray(new String[0]), frequencies);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        new Thread(() -> {
            loadLearned();
            final Dictionary fr = loadDictionary("words_fr.txt");
            final Dictionary en = loadDictionary("words_en.txt");
            mHandler.post(() -> {
                mFr = fr;
                mEn = en;
                updateSuggestions();
            });
        }, "PocketDSKeyboardWords").start();
    }

    /** The three most frequent words that start with what is typed (the word itself excluded). */
    private final java.util.HashMap<String, Integer> mLearned = new java.util.HashMap<>();
    private static final String LEARNED_FILE = "learned_words.txt";

    /** Words that usually follow a word (assets/bigrams_*.txt), in order of use. */
    private final java.util.HashMap<String, String[]> mSeed = new java.util.HashMap<>();
    private String mSeedLanguage = "";
    private String mPrev = "";          // the word typed before, "" at the start of a sentence

    private void loadSeed(String language) {
        if (language.equals(mSeedLanguage)) return;
        mSeedLanguage = language;
        mSeed.clear();
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                getAssets().open("bigrams_" + language + ".txt"),
                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                final int colon = line.indexOf(':');
                if (colon < 0) continue;
                final String next = line.substring(colon + 1).trim();
                if (next.isEmpty()) continue;
                mSeed.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        next.split(" "));
            }
        } catch (java.io.IOException e) {
            android.util.Log.w("PocketDSKeyboard", "No bigrams", e);
        }
    }

    private static void offer(String[] top, int[] score, String word, int value) {
        for (String t : top) if (word.equals(t)) return;
        for (int k = 0; k < top.length; k++) {
            if (top[k] == null || value > score[k]) {
                for (int m = top.length - 1; m > k; m--) {
                    top[m] = top[m - 1];
                    score[m] = score[m - 1];
                }
                top[k] = word;
                score[k] = value;
                return;
            }
        }
    }

    /** The words that follow the previous word: what the user wrote after it, then the usual ones. */
    private void offerNext(String[] top, int[] score, String prefix) {
        final String key = mPrev + ">";
        for (java.util.Map.Entry<String, Integer> e : mLearned.entrySet()) {
            if (!e.getKey().startsWith(key)) continue;
            final String word = e.getKey().substring(key.length());
            if (word.startsWith(prefix) && word.length() > prefix.length() || prefix.isEmpty()) {
                offer(top, score, word, 3000 + e.getValue());
            }
        }
        final String[] seed = mSeed.get(mPrev);
        if (seed != null) {
            for (int i = 0; i < seed.length; i++) {
                if (seed[i].startsWith(prefix) && seed[i].length() > prefix.length()
                        || prefix.isEmpty()) {
                    offer(top, score, seed[i], 2000 - i);
                }
            }
        }
    }

    private void loadLearned() {
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(
                new java.io.File(getFilesDir(), LEARNED_FILE)))) {
            String line;
            while ((line = in.readLine()) != null) {
                final int space = line.indexOf(' ');
                if (space <= 0) continue;
                mLearned.put(line.substring(0, space), Integer.parseInt(line.substring(space + 1)));
            }
        } catch (java.io.IOException | RuntimeException e) {
            // nothing learned yet
        }
    }

    private void saveLearned() {
        final StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : mLearned.entrySet()) {
            out.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        final String text = out.toString();
        new Thread(() -> {
            try (java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(getFilesDir(), LEARNED_FILE))) {
                w.write(text);
            } catch (java.io.IOException e) {
                android.util.Log.w("PocketDSKeyboard", "Cannot save the learned words", e);
            }
        }, "PocketDSKeyboardLearn").start();
    }

    /** A word was finished: the keyboard remembers it (3 letters or more, not in the lists). */
    private void learnTyped() {
        if (mTyped.length() == 0) return;
        final String done = mTyped.toString().toLowerCase(Locale.ROOT);
        // What follows what: "bonne" then "journée".
        final String pair = mPrev + ">" + done;
        final int times = mLearned.containsKey(pair) ? mLearned.get(pair) + 1 : 1;
        mLearned.put(pair, times);
        mPrev = done;
        if (times <= 3 || times % 5 == 0) saveLearned();
        if (mTyped.length() < 3) return;
        final String word = done;
        final Dictionary dictionary = mAzerty ? mFr : mEn;
        if (dictionary != null && java.util.Arrays.binarySearch(dictionary.words, word) >= 0
                && !mLearned.containsKey(word)) {
            return;
        }
        final int count = mLearned.containsKey(word) ? mLearned.get(word) + 1 : 1;
        mLearned.put(word, count);
        if (count <= 3 || count % 5 == 0) saveLearned();
    }

    private String[] suggestions() {
        final String[] none = {"", "", ""};
        final Dictionary dictionary = mAzerty ? mFr : mEn;
        loadSeed(mAzerty ? "fr" : "en");
        if (mTyped.length() == 0) {
            final String[] next = new String[3];
            offerNext(next, new int[3], "");
            for (int k = 0; k < 3; k++) if (next[k] == null) next[k] = "";
            return next;
        }
        if (dictionary == null) return none;
        final String typed = mTyped.toString();
        final String prefix = typed.toLowerCase(Locale.ROOT);
        int low = 0;
        int high = dictionary.words.length;
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (dictionary.words[mid].compareTo(prefix) < 0) low = mid + 1; else high = mid;
        }
        final int[] bestIndex = {-1, -1, -1};
        for (int i = low; i < dictionary.words.length && dictionary.words[i].startsWith(prefix);
                i++) {
            if (dictionary.words[i].length() == prefix.length()) continue;
            for (int k = 0; k < 3; k++) {
                if (bestIndex[k] < 0 || dictionary.freq[i] > dictionary.freq[bestIndex[k]]) {
                    for (int m = 2; m > k; m--) bestIndex[m] = bestIndex[m - 1];
                    bestIndex[k] = i;
                    break;
                }
            }
        }
        final String[] top = new String[3];
        final int[] topScore = {-1, -1, -1};
        for (int k = 0; k < 3; k++) {
            if (bestIndex[k] >= 0) {
                top[k] = dictionary.words[bestIndex[k]];
                topScore[k] = dictionary.freq[bestIndex[k]];
            }
        }
        offerNext(top, topScore, prefix);
        // What the user types often comes first.
        for (java.util.Map.Entry<String, Integer> e : mLearned.entrySet()) {
            final String word = e.getKey();
            // "before>after" entries are the word sequences, not words.
            if (word.indexOf('>') >= 0) continue;
            if (!word.startsWith(prefix) || word.length() == prefix.length()) continue;
            final int score = 1000 + e.getValue();
            boolean known = false;
            for (int k = 0; k < 3; k++) if (word.equals(top[k])) known = true;
            if (known) continue;
            for (int k = 0; k < 3; k++) {
                if (top[k] == null || score > topScore[k]) {
                    for (int m = 2; m > k; m--) {
                        top[m] = top[m - 1];
                        topScore[m] = topScore[m - 1];
                    }
                    top[k] = word;
                    topScore[k] = score;
                    break;
                }
            }
        }
        final boolean allCaps = typed.length() > 1 && typed.equals(typed.toUpperCase(Locale.ROOT));
        final boolean capital = Character.isUpperCase(typed.charAt(0));
        final String[] result = new String[3];
        for (int k = 0; k < 3; k++) {
            String word = top[k] == null ? "" : top[k];
            if (allCaps) word = word.toUpperCase(Locale.ROOT);
            else if (capital && !word.isEmpty()) {
                word = word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
            }
            result[k] = word;
        }
        return result;
    }

    private void updateSuggestions() {
        if (mSuggest[0] == null) return;
        final String[] words = suggestions();
        // Nothing to propose: the word being typed is shown in the middle.
        final boolean none = words[0].isEmpty();
        mSuggest[0].setText(none ? "" : words[0]);
        mSuggest[1].setText(none ? mTyped.toString() : words[1]);
        mSuggest[2].setText(none ? "" : words[2]);
    }

    private void applySuggestion(String word) {
        final InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        input.deleteSurroundingText(mTyped.length(), 0);
        input.commitText(word + " ", 1);
        mTyped.setLength(0);
        mTyped.append(word);
        learnTyped();
        mTyped.setLength(0);
        mPrev = "";
        updateSuggestions();
    }

    // ---- input

    private long mLastShift;

    private void toggleShift() {
        final long now = System.currentTimeMillis();
        if (mCaps) {
            mCaps = false;
            mShift = false;
        } else if (mShift && now - mLastShift < 400) {
            mCaps = true;
        } else {
            mShift = !mShift;
        }
        mLastShift = now;
        render();
    }

    private void commit(String text) {
        final InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        input.commitText(text, 1);
        if (text.length() == 1 && Character.isLetter(text.charAt(0))) {
            mTyped.append(text);
        } else {
            learnTyped();
            mTyped.setLength(0);
            if (".!?\n".contains(text)) mPrev = "";
        }
        updateSuggestions();
    }

    private void sendKey(int code) {
        final InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        input.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
        input.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
        if (code == android.view.KeyEvent.KEYCODE_DEL && mTyped.length() > 0) {
            mTyped.setLength(mTyped.length() - 1);
            updateSuggestions();
        }
    }

    private void enter() {
        final EditorInfo info = getCurrentInputEditorInfo();
        final InputConnection input = getCurrentInputConnection();
        if (info != null && input != null) {
            final int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
            final boolean multiline = (info.inputType
                    & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
            if (!multiline && action != EditorInfo.IME_ACTION_NONE
                    && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                input.performEditorAction(action);
                learnTyped();
                mTyped.setLength(0);
                updateSuggestions();
                return;
            }
        }
        sendKey(android.view.KeyEvent.KEYCODE_ENTER);
        learnTyped();
        mTyped.setLength(0);
        updateSuggestions();
    }

    /** Fires once at the touch, then repeats while the key stays pressed. */
    private final class RepeatTouch implements View.OnTouchListener {
        private final Runnable mAction;
        private final Runnable mRepeat = new Runnable() {
            @Override
            public void run() {
                mAction.run();
                mHandler.postDelayed(this, 50);
            }
        };

        RepeatTouch(Runnable action) {
            mAction = action;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    tap(v);
                    mAction.run();
                    mHandler.postDelayed(mRepeat, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    mHandler.removeCallbacks(mRepeat);
                    return true;
                default:
                    return true;
            }
        }
    }
}
