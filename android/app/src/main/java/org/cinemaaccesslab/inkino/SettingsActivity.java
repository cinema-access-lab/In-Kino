// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.cinemaaccesslab.inkino.ui.CinemaUi;
import org.cinemaaccesslab.inkino.ui.ShiftControl;

import java.net.URI;

/** Local user preferences. No server connection occurs here. */
public final class SettingsActivity extends Activity {
    private int dp(int n) {
        return CinemaUi.dp(this, n);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CinemaUi.BACKGROUND);
        scroll.setFillViewport(true);
        LinearLayout root = CinemaUi.column(this);
        scroll.addView(root);
        root.addView(CinemaUi.screenHeader(this, getString(R.string.settings_title)));

        // Catalogue server
        TextView serverHeading =
                CinemaUi.sectionHeading(this, getString(R.string.settings_server_heading));
        serverHeading.setPadding(0, dp(8), 0, dp(8));
        root.addView(serverHeading);
        EditText server = new EditText(this);
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setHint("https://catalogue.example.org");
        server.setContentDescription(getString(R.string.settings_server_description));
        server.setText(CinemaPreferences.get(this).getString(CinemaPreferences.SERVER_ORIGIN, ""));
        CinemaUi.styleInput(server);
        root.addView(server, new LinearLayout.LayoutParams(-1, -2));
        root.addView(CinemaUi.caption(this, getString(R.string.settings_server_help)));
        Button demo = CinemaUi.button(this, getString(R.string.use_demo_catalogue), false);
        demo.setOnClickListener(
                v -> {
                    server.setText(CinemaPreferences.DEMO_SERVER_ORIGIN);
                    server.setError(null);
                });
        root.addView(demo, CinemaUi.stacked(this));

        // Subtitles
        root.addView(CinemaUi.sectionHeading(this, getString(R.string.subtitles)));
        int[] currentSize = {
            CinemaPreferences.get(this)
                    .getInt(
                            CinemaPreferences.SUBTITLE_SIZE,
                            CinemaPreferences.SUBTITLE_SIZE_DEFAULT)
        };
        // The buttons come before the preview, so they stay in reach when the preview is huge.
        Button smaller = CinemaUi.button(this, "A−", false),
                larger = CinemaUi.button(this, "A+", false);
        smaller.setContentDescription(getString(R.string.size_smaller));
        larger.setContentDescription(getString(R.string.size_larger));
        root.addView(CinemaUi.pair(this, smaller, larger));
        TextView size = CinemaUi.caption(this, "");
        size.setGravity(Gravity.CENTER_HORIZONTAL);
        size.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(size);
        TextView preview =
                CinemaUi.text(
                        this,
                        getString(R.string.settings_subtitle_preview),
                        currentSize[0],
                        CinemaUi.TEXT,
                        true);
        preview.setGravity(Gravity.CENTER);
        preview.setMinHeight(dp(140));
        preview.setPadding(dp(16), dp(16), dp(16), dp(16));
        CinemaUi.card(this, preview);
        preview.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(preview, CinemaUi.listItem(this));
        Runnable showSize =
                () -> {
                    size.setText(getString(R.string.settings_text_size, currentSize[0]));
                    preview.setTextSize(currentSize[0]);
                };
        showSize.run();
        smaller.setOnClickListener(
                v -> {
                    currentSize[0] = CinemaPreferences.nextSubtitleSize(currentSize[0], -1);
                    showSize.run();
                });
        larger.setOnClickListener(
                v -> {
                    currentSize[0] = CinemaPreferences.nextSubtitleSize(currentSize[0], +1);
                    showSize.run();
                });

        CheckBox announce = new CheckBox(this);
        announce.setText(R.string.settings_announce);
        CinemaUi.styleCheckBox(announce);
        announce.setChecked(
                CinemaPreferences.get(this)
                        .getBoolean(CinemaPreferences.SUBTITLE_ANNOUNCEMENTS, false));
        LinearLayout.LayoutParams announceParams = new LinearLayout.LayoutParams(-1, -2);
        announceParams.topMargin = dp(12);
        root.addView(announce, announceParams);
        root.addView(CinemaUi.caption(this, getString(R.string.settings_announce_help)));

        // Timing: a lasting shift, for example to make up for slow Bluetooth headphones. The player
        // adds a temporary shift on top for a single screening.
        root.addView(CinemaUi.sectionHeading(this, getString(R.string.timing_heading)));
        root.addView(CinemaUi.caption(this, getString(R.string.timing_settings_help)));
        ShiftControl adShift =
                new ShiftControl(
                        this,
                        getString(R.string.audio_description),
                        0,
                        CinemaPreferences.get(this).getInt(CinemaPreferences.AD_SHIFT_MS, 0),
                        null);
        root.addView(adShift.view(), CinemaUi.listItem(this));
        ShiftControl subtitleShift =
                new ShiftControl(
                        this,
                        getString(R.string.subtitles),
                        0,
                        CinemaPreferences.get(this).getInt(CinemaPreferences.SUBTITLE_SHIFT_MS, 0),
                        null);
        root.addView(subtitleShift.view(), CinemaUi.listItem(this));

        root.addView(CinemaUi.sectionHeading(this, getString(R.string.settings_feedback_heading)));
        CheckBox vibrate = new CheckBox(this);
        vibrate.setText(R.string.settings_vibrate);
        CinemaUi.styleCheckBox(vibrate);
        vibrate.setChecked(
                CinemaPreferences.get(this).getBoolean(CinemaPreferences.VIBRATE_ON_LOCK, true));
        root.addView(vibrate, new LinearLayout.LayoutParams(-1, -2));
        root.addView(CinemaUi.caption(this, getString(R.string.settings_vibrate_help)));

        root.addView(new android.view.View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        Button save = CinemaUi.button(this, getString(R.string.settings_save), true);
        LinearLayout.LayoutParams saveParams = CinemaUi.stacked(this);
        saveParams.topMargin = dp(CinemaUi.SECTION_GAP);
        root.addView(save, saveParams);
        save.setOnClickListener(
                v -> {
                    String address = server.getText().toString().trim().replaceAll("/+$", "");
                    try {
                        validateOrigin(address);
                    } catch (Exception e) {
                        server.setError(getString(R.string.error_server_address));
                        server.requestFocus();
                        return;
                    }
                    CinemaPreferences.get(this)
                            .edit()
                            .putString(CinemaPreferences.SERVER_ORIGIN, address)
                            .putInt(CinemaPreferences.SUBTITLE_SIZE, currentSize[0])
                            .putBoolean(
                                    CinemaPreferences.SUBTITLE_ANNOUNCEMENTS, announce.isChecked())
                            .putBoolean(CinemaPreferences.VIBRATE_ON_LOCK, vibrate.isChecked())
                            .putInt(CinemaPreferences.AD_SHIFT_MS, adShift.totalMs())
                            .putInt(CinemaPreferences.SUBTITLE_SHIFT_MS, subtitleShift.totalMs())
                            .apply();
                    Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                    finish();
                });
        setContentView(scroll);
        CinemaUi.edgeToEdge(this, scroll);
    }

    private static void validateOrigin(String address) throws Exception {
        if (address.isEmpty()) return; // An offline-only user needs no server.
        URI uri = new URI(address);
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !CatalogActivity.isPlainPath(uri.getRawPath())
                || uri.getPort() > 65535)
            throw new IllegalArgumentException(
                    "Enter a server address, for example https://example.org");
    }
}
