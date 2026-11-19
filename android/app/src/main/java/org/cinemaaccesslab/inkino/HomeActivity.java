// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.cinemaaccesslab.inkino.ui.CinemaUi;

/** Start screen: the app name, a menu, and three destinations anchored near the bottom. */
public final class HomeActivity extends Activity {
    private int dp(int n) {
        return CinemaUi.dp(this, n);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CinemaUi.BACKGROUND);
        LinearLayout root = CinemaUi.column(this);
        scroll.addView(root);

        // Menu in the top corner, kept out of the way of the title.
        FrameLayout topBar = new FrameLayout(this);
        Button menu = CinemaUi.iconButton(this, "☰", getString(R.string.menu));
        menu.setOnClickListener(
                v -> {
                    PopupMenu popup = new PopupMenu(this, menu);
                    popup.getMenu()
                            .add(R.string.menu_settings)
                            .setOnMenuItemClickListener(
                                    item -> {
                                        startActivity(new Intent(this, SettingsActivity.class));
                                        return true;
                                    });
                    popup.getMenu()
                            .add(R.string.menu_about)
                            .setOnMenuItemClickListener(
                                    item -> {
                                        startActivity(new Intent(this, AboutActivity.class));
                                        return true;
                                    });
                    popup.show();
                });
        FrameLayout.LayoutParams menuParams =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        menuParams.setMarginEnd(-dp(8));
        topBar.addView(menu, menuParams);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(56)));

        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));

        // App name and a one-line explanation, centred in the upper part of the screen.
        TextView title = CinemaUi.text(this, getString(R.string.app_name), 36, CinemaUi.TEXT, true);
        title.setTypeface(
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        CinemaUi.markHeading(title);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        View accent = new View(this);
        accent.setBackgroundColor(CinemaUi.ACCENT);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(40), dp(3));
        accentParams.gravity = Gravity.CENTER_HORIZONTAL;
        accentParams.topMargin = dp(10);
        accentParams.bottomMargin = dp(14);
        root.addView(accent, accentParams);
        TextView tagline =
                CinemaUi.text(this, getString(R.string.app_tagline), 17, CinemaUi.MUTED, false);
        tagline.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(tagline, new LinearLayout.LayoutParams(-1, -2));

        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 2));

        addAction(
                root,
                getString(R.string.home_my_films),
                true,
                () -> {
                    Intent i = new Intent(this, CatalogActivity.class);
                    i.putExtra(CatalogActivity.EXTRA_MODE, CatalogActivity.MODE_LOCAL);
                    startActivity(i);
                });
        addAction(
                root,
                getString(R.string.home_online_catalogue),
                false,
                () -> {
                    Intent i = new Intent(this, CatalogActivity.class);
                    i.putExtra(CatalogActivity.EXTRA_MODE, CatalogActivity.MODE_ONLINE);
                    startActivity(i);
                });
        addAction(
                root,
                getString(R.string.home_manual_player),
                false,
                () -> startActivity(new Intent(this, MainActivity.class)));

        TextView version = CinemaUi.caption(this, versionLabel());
        version.setTextSize(13);
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-1, -2);
        versionParams.topMargin = dp(4);
        root.addView(version, versionParams);

        setContentView(scroll);
        CinemaUi.edgeToEdge(this, scroll);
    }

    private void addAction(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = CinemaUi.button(this, label, primary);
        button.setOnClickListener(v -> action.run());
        parent.addView(button, CinemaUi.stacked(this));
    }

    private String versionLabel() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return getString(R.string.version_label, name);
        } catch (Exception e) {
            return getString(R.string.version_unknown);
        }
    }
}
