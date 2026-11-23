// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SubtitleSizeTest {
    @Test
    public void stepsGrowAboveFortyEight() {
        assertEquals(36, CinemaPreferences.nextSubtitleSize(32, +1));
        assertEquals(56, CinemaPreferences.nextSubtitleSize(48, +1));
        assertEquals(48, CinemaPreferences.nextSubtitleSize(56, -1));
        assertEquals(44, CinemaPreferences.nextSubtitleSize(48, -1));
    }

    @Test
    public void sizesStayWithinLimits() {
        assertEquals(
                CinemaPreferences.SUBTITLE_SIZE_MAX,
                CinemaPreferences.nextSubtitleSize(CinemaPreferences.SUBTITLE_SIZE_MAX, +1));
        assertEquals(
                CinemaPreferences.SUBTITLE_SIZE_MIN,
                CinemaPreferences.nextSubtitleSize(CinemaPreferences.SUBTITLE_SIZE_MIN, -1));
        int size = CinemaPreferences.SUBTITLE_SIZE_DEFAULT;
        int taps = 0;
        while (size < CinemaPreferences.SUBTITLE_SIZE_MAX) {
            size = CinemaPreferences.nextSubtitleSize(size, +1);
            taps++;
        }
        // From the default to the largest size in a reasonable number of taps.
        assertEquals(13, taps); // 32 to 48 in fours, then to 120 in eights
    }
}
