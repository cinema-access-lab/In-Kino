// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.cinemaaccesslab.inkino.R;

import java.util.Locale;

/**
 * Moves a track earlier or later in steps of 0.1 seconds: a label, then "◀ Earlier", the current
 * shift, and "Later ▶". A positive shift means earlier, which matches how the player applies it
 * (the track plays the content of film time + shift).
 *
 * <p>The shown value is a fixed base (for example the default from Settings) plus this control's
 * own part, and the buttons only change the control's own part.
 */
public final class ShiftControl {
    public static final int STEP_MS = 100;
    public static final int LIMIT_MS = 5000;

    /** Called with the new total shift in milliseconds. */
    public interface Listener {
        void onShiftChanged(int totalMs);
    }

    private final Context context;
    private final String what;
    private final int baseMs;
    private final Listener listener;
    private final TextView value;
    private final LinearLayout view;
    private int ownMs;

    public ShiftControl(Context context, String what, int baseMs, int ownMs, Listener listener) {
        this.context = context;
        this.what = what;
        this.baseMs = baseMs;
        this.ownMs = ownMs;
        this.listener = listener;

        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        TextView label = CinemaUi.body(context, what);
        label.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.addView(label);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button earlier =
                CinemaUi.button(context, context.getString(R.string.shift_earlier_button), false);
        earlier.setContentDescription(context.getString(R.string.shift_earlier_description, what));
        earlier.setOnClickListener(v -> change(+STEP_MS));
        Button later =
                CinemaUi.button(context, context.getString(R.string.shift_later_button), false);
        later.setContentDescription(context.getString(R.string.shift_later_description, what));
        later.setOnClickListener(v -> change(-STEP_MS));
        value = CinemaUi.text(context, "", 18, CinemaUi.TEXT, true);
        value.setGravity(Gravity.CENTER);

        int height = CinemaUi.dp(context, CinemaUi.BUTTON_HEIGHT);
        row.addView(earlier, new LinearLayout.LayoutParams(0, height, 1));
        row.addView(value, new LinearLayout.LayoutParams(0, height, 1));
        row.addView(later, new LinearLayout.LayoutParams(0, height, 1));
        view.addView(row);
        show();
    }

    public LinearLayout view() {
        return view;
    }

    /** This control's own part of the shift, without the base. */
    public int ownMs() {
        return ownMs;
    }

    public int totalMs() {
        return baseMs + ownMs;
    }

    /** Moves the shift by deltaMs, for example from another control showing the same shift. */
    public void step(int deltaMs) {
        change(deltaMs);
    }

    private void change(int deltaMs) {
        int total = Math.max(-LIMIT_MS, Math.min(LIMIT_MS, totalMs() + deltaMs));
        if (total == totalMs()) return;
        ownMs = total - baseMs;
        show();
        value.announceForAccessibility(value.getContentDescription());
        if (listener != null) listener.onShiftChanged(total);
    }

    private void show() {
        String text = describe(context, totalMs());
        value.setText(text);
        value.setContentDescription(
                context.getString(R.string.shift_value_description, what, text));
    }

    /** "No shift", "0.3 s earlier", or "0.2 s later", with the decimal separator of the locale. */
    public static String describe(Context context, int ms) {
        if (ms == 0) return context.getString(R.string.shift_none);
        String seconds = String.format(Locale.getDefault(), "%.1f", Math.abs(ms) / 1000.0);
        return context.getString(ms > 0 ? R.string.shift_earlier : R.string.shift_later, seconds);
    }
}
