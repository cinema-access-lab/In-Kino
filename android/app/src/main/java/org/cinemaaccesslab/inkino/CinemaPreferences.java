// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by Home, Catalogue and Manual Player. */
public final class CinemaPreferences {
    private CinemaPreferences() {}

    public static final String SERVER_ORIGIN = "server_origin";
    public static final String SUBTITLE_SIZE = "subtitle_size_sp";
    public static final String SUBTITLE_ANNOUNCEMENTS = "announce_subtitles";
    public static final String VIBRATE_ON_LOCK = "vibrate_on_lock";
    // Fullscreen subtitle size in sp. The subtitle box on the player page stays at the default.
    public static final int SUBTITLE_SIZE_DEFAULT = 32;
    public static final int SUBTITLE_SIZE_MIN = 22;
    public static final int SUBTITLE_SIZE_MAX = 120;

    /** The next size up (direction 1) or down (-1): steps of 4 sp, and 8 sp above 48 sp. */
    public static int nextSubtitleSize(int current, int direction) {
        int step = (direction > 0 ? current : current - 1) >= 48 ? 8 : 4;
        int next = current + Integer.signum(direction) * step;
        return Math.max(SUBTITLE_SIZE_MIN, Math.min(SUBTITLE_SIZE_MAX, next));
    }

    // Lasting timing shifts in milliseconds; positive means earlier.
    public static final String AD_SHIFT_MS = "ad_shift_ms";
    public static final String SUBTITLE_SHIFT_MS = "subtitle_shift_ms";

    /** The Elephants Dream demo catalogue, built by the demo workflow for GitHub Pages. */
    public static final String DEMO_SERVER_ORIGIN = "https://cinema-access-lab.github.io/In-Kino";

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("in_kino_preferences", Context.MODE_PRIVATE);
    }
}
