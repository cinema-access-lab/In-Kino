// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.subtitles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

public class SubtitleTimelineTest {
    private static final String SRT =
            "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n[DOOR SLAMS]\r\n- We should go.\r\n\r\n"
                    + "2\r\n00:00:03,000 --> 00:00:04,000\r\n<i>Hi!</i>\r\n\r\n"
                    + "3\r\n00:01:04,050 --> 00:01:05,250\r\nMÜLLER: Grüß dich &amp; komm!\r\n";

    @Test
    public void srtCuesStartInclusiveAndEndExclusive() throws IOException {
        SubtitleTimeline timeline = SubtitleTimeline.parse(new StringReader(SRT));
        assertEquals(3, timeline.size());
        assertEquals("", timeline.textAt(999L));
        assertEquals("[DOOR SLAMS]\n- We should go.", timeline.textAt(1000L));
        assertEquals("Hi!", timeline.textAt(3500L));
        assertEquals("", timeline.textAt(4000L));
    }

    @Test
    public void overlappingCuesAreShownTogether() throws IOException {
        SubtitleTimeline timeline = SubtitleTimeline.parse(new StringReader(SRT));
        assertEquals("[DOOR SLAMS]\n- We should go.\nHi!", timeline.textAt(3000L));
    }

    @Test
    public void unicodeAndEntitiesSurviveAndTagsAreRemoved() throws IOException {
        SubtitleTimeline timeline = SubtitleTimeline.parse(new StringReader(SRT));
        assertEquals("MÜLLER: Grüß dich & komm!", timeline.textAt(64050L));
    }

    @Test
    public void webVttWithCueSettingsIsParsed() throws IOException {
        String vtt = "WEBVTT\n\n00:05.000 --> 00:07.000 align:start position:5%\n[MUSIC]\n\n";
        SubtitleTimeline timeline = SubtitleTimeline.parse(new StringReader(vtt));
        assertEquals("[MUSIC]", timeline.textAt(5000L));
        assertEquals("", timeline.textAt(7000L));
    }

    @Test
    public void webVttNoteBlocksAreSkipped() throws IOException {
        String vtt =
                "WEBVTT\n\nNOTE\nCaptions by someone --> licensed\n\n"
                        + "1\n00:01.000 --> 00:02.000\nHello\n";
        SubtitleTimeline timeline = SubtitleTimeline.parse(new StringReader(vtt));
        assertEquals(1, timeline.size());
        assertEquals("Hello", timeline.textAt(1500L));
    }

    @Test
    public void fileWithoutCuesIsRejected() {
        assertThrows(IOException.class, () -> SubtitleTimeline.parse(new StringReader("no cues")));
    }
}
