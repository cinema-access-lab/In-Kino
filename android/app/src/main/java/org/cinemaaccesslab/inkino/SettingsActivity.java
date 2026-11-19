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

        // Subtitles
        root.addView(CinemaUi.sectionHeading(this, getString(R.string.subtitles)));
        int[] currentSize = {
            CinemaPreferences.get(this).getInt(CinemaPreferences.SUBTITLE_SIZE, 32)
        };
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
        TextView size = CinemaUi.caption(this, "");
        size.setGravity(Gravity.CENTER_HORIZONTAL);
        size.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Runnable showSize =
                () -> {
                    size.setText(getString(R.string.settings_text_size, currentSize[0]));
                    preview.setTextSize(currentSize[0]);
                };
        showSize.run();
        Button smaller = CinemaUi.button(this, "A−", false),
                larger = CinemaUi.button(this, "A+", false);
        smaller.setContentDescription(getString(R.string.size_smaller));
        larger.setContentDescription(getString(R.string.size_larger));
        smaller.setOnClickListener(
                v -> {
                    currentSize[0] = Math.max(22, currentSize[0] - 4);
                    showSize.run();
                });
        larger.setOnClickListener(
                v -> {
                    currentSize[0] = Math.min(52, currentSize[0] + 4);
                    showSize.run();
                });
        root.addView(CinemaUi.pair(this, smaller, larger));
        root.addView(size);

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
