// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.cinemaaccesslab.inkino.R;

/**
 * The small bar over fullscreen subtitles: earlier and later, and smaller and larger text. It is
 * meant for sighted viewers, so it stays out of the way: a single tap shows it, and it hides again
 * after a few seconds without a touch. With TalkBack running it stays until hidden, since finding
 * the buttons by touch takes longer.
 */
public final class SubtitleControlBar {
    /** What the buttons change. The owner applies the change and then calls {@link #show}. */
    public interface Listener {
        void onShift(int deltaMs);

        void onResize(int direction);
    }

    private static final long HIDE_AFTER_MS = 4000;

    private final Context context;
    private final LinearLayout bar;
    private final TextView shiftView, sizeView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;

    public SubtitleControlBar(Context context, Listener listener) {
        this.context = context;
        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC000000);
        background.setCornerRadius(CinemaUi.dp(context, 24));
        background.setStroke(CinemaUi.dp(context, 1), 0x44FFFFFF);
        bar.setBackground(background);
        int padding = CinemaUi.dp(context, 8);
        bar.setPadding(padding, padding / 2, padding, padding / 2);
        bar.setVisibility(View.GONE);

        String subtitles = context.getString(R.string.subtitles);
        Button earlier =
                CinemaUi.iconButton(
                        context,
                        "◀◀",
                        context.getString(R.string.shift_earlier_description, subtitles));
        earlier.setOnClickListener(v -> listener.onShift(+ShiftControl.STEP_MS));
        Button later =
                CinemaUi.iconButton(
                        context,
                        "▶▶",
                        context.getString(R.string.shift_later_description, subtitles));
        later.setOnClickListener(v -> listener.onShift(-ShiftControl.STEP_MS));
        Button smaller =
                CinemaUi.iconButton(context, "A−", context.getString(R.string.size_smaller));
        smaller.setOnClickListener(v -> listener.onResize(-1));
        Button larger = CinemaUi.iconButton(context, "A+", context.getString(R.string.size_larger));
        larger.setOnClickListener(v -> listener.onResize(+1));
        shiftView = label(110);
        sizeView = label(44);

        int button = CinemaUi.dp(context, 48);
        bar.addView(earlier, new LinearLayout.LayoutParams(button, button));
        bar.addView(shiftView);
        bar.addView(later, new LinearLayout.LayoutParams(button, button));
        bar.addView(new View(context), new LinearLayout.LayoutParams(CinemaUi.dp(context, 20), 1));
        bar.addView(smaller, new LinearLayout.LayoutParams(button, button));
        bar.addView(sizeView);
        bar.addView(larger, new LinearLayout.LayoutParams(button, button));
    }

    private TextView label(int minWidthDp) {
        TextView view = CinemaUi.text(context, "", 15, Color.WHITE, false);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(CinemaUi.dp(context, minWidthDp));
        return view;
    }

    /** Adds the bar to the bottom centre of a fullscreen layer. */
    public void attachTo(FrameLayout layer) {
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = CinemaUi.dp(context, 20);
        layer.addView(bar, params);
    }

    /** Shows the current subtitle shift and text size. */
    public void setValues(int shiftMs, int sizeSp) {
        String shift = ShiftControl.describe(context, shiftMs);
        shiftView.setText(shift);
        shiftView.setContentDescription(
                context.getString(
                        R.string.shift_value_description,
                        context.getString(R.string.subtitles),
                        shift));
        sizeView.setText(String.valueOf(sizeSp));
        sizeView.setContentDescription(context.getString(R.string.subtitle_size_value, sizeSp));
    }

    /** Shows the bar, or restarts its hide timer if it is already showing. */
    public void show() {
        bar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hide);
        AccessibilityManager accessibility =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibility == null || !accessibility.isTouchExplorationEnabled())
            handler.postDelayed(hide, HIDE_AFTER_MS);
    }

    public void hide() {
        handler.removeCallbacks(hide);
        bar.setVisibility(View.GONE);
    }

    public void toggle() {
        if (bar.getVisibility() == View.VISIBLE) hide();
        else show();
    }
}
