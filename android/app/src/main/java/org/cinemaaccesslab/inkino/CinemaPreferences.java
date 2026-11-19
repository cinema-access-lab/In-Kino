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

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("in_kino_preferences", Context.MODE_PRIVATE);
    }
}
