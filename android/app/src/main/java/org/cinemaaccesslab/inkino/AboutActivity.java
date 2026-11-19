// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.app.Activity;
import android.os.Bundle;
import android.text.util.Linkify;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.cinemaaccesslab.inkino.ui.CinemaUi;

/** What the app is, its license, and credits for the demo film. */
public final class AboutActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CinemaUi.BACKGROUND);
        LinearLayout root = CinemaUi.column(this);
        scroll.addView(root);
        root.addView(CinemaUi.screenHeader(this, getString(R.string.about_title)));

        root.addView(CinemaUi.body(this, getString(R.string.about_what)));
        TextView version = CinemaUi.caption(this, versionLabel());
        version.setPadding(0, CinemaUi.dp(this, 12), 0, 0);
        root.addView(version);
        root.addView(CinemaUi.caption(this, getString(R.string.about_prototype)));

        root.addView(CinemaUi.sectionHeading(this, getString(R.string.about_license_heading)));
        root.addView(CinemaUi.body(this, getString(R.string.about_license)));
        TextView source =
                CinemaUi.body(
                        this, getString(R.string.about_source, getString(R.string.source_url)));
        source.setLinkTextColor(CinemaUi.ACCENT);
        source.setAutoLinkMask(Linkify.WEB_URLS);
        source.setText(source.getText());
        root.addView(source);

        root.addView(CinemaUi.sectionHeading(this, getString(R.string.about_demo_heading)));
        root.addView(CinemaUi.caption(this, getString(R.string.about_demo)));

        setContentView(scroll);
        CinemaUi.edgeToEdge(this, scroll);
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
