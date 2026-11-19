// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.cinemaaccesslab.inkino.R;

/**
 * Shared visual layer for all screens: palette, type scale, spacing, and widgets.
 *
 * <p>No microphone, media, or synchronization logic lives here, so the look of the app can be
 * changed without touching playback code. See docs/theming.md.
 */
public final class CinemaUi {
    private CinemaUi() {}

    // Palette: graphite with a sea-glass teal accent. All text colours meet WCAG AA on BACKGROUND.
    public static final int BACKGROUND = 0xFF091620;
    public static final int SURFACE = 0xFF132532;
    public static final int SURFACE_RAISED = 0xFF1C3342;
    public static final int TEXT = 0xFFF5FAFC;
    public static final int MUTED = 0xFFB4C6D0;
    public static final int ACCENT = 0xFF69E4D0;
    public static final int ACCENT_SOFT = 0x3369E4D0;
    public static final int ON_ACCENT = 0xFF061D21;
    public static final int BORDER = 0xFF33505E;
    public static final int WARNING = 0xFFF5C26B;
    public static final int ON_WARNING = 0xFF2B1C00;
    public static final int ERROR = 0xFFFF9A8F;
    public static final int ON_ERROR = 0xFF3B0A06;

    // Spacing scale, in dp.
    public static final int SCREEN_MARGIN = 20;
    public static final int GAP = 12;
    public static final int SECTION_GAP = 28;
    public static final int BUTTON_HEIGHT = 64;

    private static final int CORNER = 16;

    public static int dp(Context context, int n) {
        return (int) (context.getResources().getDisplayMetrics().density * n + .5f);
    }

    // ---------------------------------------------------------------- window

    /**
     * Draws the screen edge to edge on every Android version and pads {@code root} so that its
     * content stays clear of the status bar, navigation bar, display cutout, and keyboard. Apply it
     * to the outermost scrolling view of a screen.
     */
    public static void edgeToEdge(Activity activity, View root) {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView()
                    .setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (root instanceof ViewGroup) ((ViewGroup) root).setClipToPadding(false);
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    int[] bars = systemBarInsets(insets);
                    view.setPadding(
                            left + bars[0], top + bars[1], right + bars[2], bottom + bars[3]);
                    return insets;
                });
        root.requestApplyInsets();
    }

    /** Left, top, right, bottom insets of the system bars, cutout, and on-screen keyboard. */
    private static int[] systemBarInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                    insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            return new int[] {bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom)};
        }
        return new int[] {
            insets.getSystemWindowInsetLeft(),
            insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(),
            insets.getSystemWindowInsetBottom()
        };
    }

    /** A vertical column with the standard screen margins. */
    public static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(context, SCREEN_MARGIN);
        column.setPadding(margin, dp(context, 8), margin, dp(context, 24));
        return column;
    }

    // ------------------------------------------------------------ typography

    public static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setLineSpacing(0, 1.15f);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return v;
    }

    /** Screen title, announced as a heading. */
    public static TextView title(Context context, String value) {
        TextView v = text(context, value, 28, TEXT, true);
        v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        markHeading(v);
        return v;
    }

    /** Section heading inside a screen, announced as a heading. */
    public static TextView sectionHeading(Context context, String value) {
        TextView v = text(context, value, 20, TEXT, true);
        v.setPadding(0, dp(context, SECTION_GAP), 0, dp(context, 8));
        markHeading(v);
        return v;
    }

    public static TextView body(Context context, String value) {
        return text(context, value, 17, TEXT, false);
    }

    public static TextView caption(Context context, String value) {
        return text(context, value, 15, MUTED, false);
    }

    public static void markHeading(TextView v) {
        if (Build.VERSION.SDK_INT >= 28) v.setAccessibilityHeading(true);
    }

    // ---------------------------------------------------------------- header

    /**
     * Top bar for secondary screens: a back button and the screen title. Returns the bar so the
     * caller can add it as the first child of its column.
     */
    public static LinearLayout screenHeader(Activity activity, String value) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(activity, 12));
        Button back = iconButton(activity, "←", activity.getString(R.string.back));
        back.setOnClickListener(v -> activity.finish());
        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48));
        backParams.setMarginStart(-dp(activity, 12));
        backParams.setMarginEnd(dp(activity, 4));
        bar.addView(back, backParams);
        TextView heading = title(activity, value);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }

    // --------------------------------------------------------------- buttons

    public static Button button(Context context, String value, boolean primary) {
        Button b = new Button(context);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setGravity(Gravity.CENTER);
        // The platform button adds its own shadow and elevation animation; drop them so
        // buttons stay flat and never paint over their neighbours.
        b.setStateListAnimator(null);
        b.setElevation(0);
        int h = dp(context, 20);
        b.setPadding(h, dp(context, 10), h, dp(context, 10));
        b.setMinHeight(dp(context, 56));
        b.setMinimumHeight(dp(context, 56));
        GradientDrawable bg = rounded(context, primary ? ACCENT : SURFACE_RAISED, CORNER);
        if (!primary) bg.setStroke(dp(context, 1), BORDER);
        b.setBackground(ripple(primary ? 0x33000000 : 0x33FFFFFF, bg));
        return b;
    }

    /** Borderless square button for a single symbol, with a spoken label. */
    public static Button iconButton(Context context, String symbol, String spokenLabel) {
        Button b = new Button(context);
        b.setText(symbol);
        b.setAllCaps(false);
        b.setTextSize(24);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask));
        b.setContentDescription(spokenLabel);
        return b;
    }

    /** Full-width layout parameters for a stacked button, with the standard gap below. */
    public static LinearLayout.LayoutParams stacked(Context context) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(context, BUTTON_HEIGHT));
        params.bottomMargin = dp(context, GAP);
        return params;
    }

    /** Places two views side by side with the standard gap between them. */
    public static LinearLayout pair(Context context, View first, View second) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a =
                new LinearLayout.LayoutParams(0, dp(context, BUTTON_HEIGHT), 1);
        a.setMarginEnd(dp(context, GAP / 2));
        LinearLayout.LayoutParams b =
                new LinearLayout.LayoutParams(0, dp(context, BUTTON_HEIGHT), 1);
        b.setMarginStart(dp(context, GAP / 2));
        row.addView(first, a);
        row.addView(second, b);
        return row;
    }

    // ---------------------------------------------------------------- status

    /** How a status pill reads at a glance. Colour is never the only cue: the text says it too. */
    public enum Tone {
        NEUTRAL,
        ACTIVE,
        OK,
        WARNING,
        ERROR
    }

    /** A rounded status label, sized to its text. Use {@link #setStatus} to change it. */
    public static TextView statusPill(Context context) {
        TextView v = text(context, "", 18, TEXT, true);
        v.setGravity(Gravity.CENTER);
        int h = dp(context, 18);
        v.setPadding(h, dp(context, 8), h, dp(context, 8));
        v.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        setStatus(v, "", Tone.NEUTRAL);
        return v;
    }

    public static void setStatus(TextView pill, CharSequence message, Tone tone) {
        int fill, ink;
        switch (tone) {
            case ACTIVE:
                fill = ACCENT_SOFT;
                ink = ACCENT;
                break;
            case OK:
                fill = ACCENT;
                ink = ON_ACCENT;
                break;
            case WARNING:
                fill = WARNING;
                ink = ON_WARNING;
                break;
            case ERROR:
                fill = ERROR;
                ink = ON_ERROR;
                break;
            default:
                fill = SURFACE_RAISED;
                ink = TEXT;
                break;
        }
        pill.setText(message);
        pill.setTextColor(ink);
        pill.setBackground(rounded(pill.getContext(), fill, 999));
    }

    // ----------------------------------------------------------------- cards

    public static void card(Context context, View view) {
        GradientDrawable bg = rounded(context, SURFACE, CORNER);
        bg.setStroke(dp(context, 1), BORDER);
        view.setBackground(bg);
    }

    /**
     * A tappable film entry: title and detail on the left, a short status label on the right. The
     * caller sets the click listener and a content description for screen readers.
     */
    public static LinearLayout filmRow(
            Context context, String title, String detail, String status, boolean highlight) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 16);
        row.setPadding(pad, dp(context, 14), pad, dp(context, 14));
        row.setMinimumHeight(dp(context, 76));
        GradientDrawable bg = rounded(context, SURFACE, CORNER);
        bg.setStroke(dp(context, 1), BORDER);
        row.setBackground(ripple(0x22FFFFFF, bg));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, title, 19, TEXT, true);
        name.setPadding(0, 0, 0, 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        if (detail != null && !detail.isEmpty()) {
            TextView sub = caption(context, detail);
            sub.setPadding(0, dp(context, 2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        TextView chip = text(context, status, 14, highlight ? ON_ACCENT : ACCENT, true);
        chip.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        chip.setBackground(rounded(context, highlight ? ACCENT : ACCENT_SOFT, 999));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.setMarginStart(dp(context, 12));
        row.addView(chip, chipParams);
        // Screen readers get the row's own description instead of three separate items.
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setContentDescription(title + ", " + (detail == null ? "" : detail + ", ") + status);
        return row;
    }

    /** Muted message inside a card, for empty lists and errors. */
    public static TextView emptyState(Context context, String message) {
        TextView v = text(context, message, 16, MUTED, false);
        v.setGravity(Gravity.CENTER);
        int pad = dp(context, 20);
        v.setPadding(pad, dp(context, 28), pad, dp(context, 28));
        card(context, v);
        return v;
    }

    /** Full-width layout parameters for a list entry, with the standard gap below. */
    public static LinearLayout.LayoutParams listItem(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, GAP);
        return params;
    }

    // ---------------------------------------------------------------- inputs

    public static void styleInput(EditText input) {
        Context context = input.getContext();
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(18);
        int pad = dp(context, 16);
        GradientDrawable normal = rounded(context, SURFACE, 12);
        normal.setStroke(dp(context, 1), BORDER);
        GradientDrawable focused = rounded(context, SURFACE, 12);
        focused.setStroke(dp(context, 2), ACCENT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, normal);
        input.setBackground(states);
        input.setPadding(pad, dp(context, 14), pad, dp(context, 14));
        input.setMinHeight(dp(context, 56));
    }

    public static void styleCheckBox(CheckBox box) {
        box.setTextColor(TEXT);
        box.setTextSize(17);
        box.setButtonTintList(ColorStateList.valueOf(ACCENT));
        box.setMinHeight(dp(box.getContext(), 56));
        box.setPadding(dp(box.getContext(), 8), 0, 0, 0);
    }

    // --------------------------------------------------------------- helpers

    private static GradientDrawable rounded(Context context, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    private static Drawable ripple(int color, GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(color), content, null);
    }
}
