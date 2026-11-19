// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;

/** Fingerprint matching of degraded excerpts against a generated soundtrack. */
public class LandmarkMatcherTest {
    private static final SyntheticSoundtrack FILM = new SyntheticSoundtrack(712, 60);
    private static final double EXCERPT_START = 37.0;
    private static final double EXCERPT_SECONDS = 8.0;

    private static LandmarkMatcher matcher;

    @BeforeClass
    public static void buildReference() throws Exception {
        FingerprintReference reference =
                FingerprintReference.read(new ByteArrayInputStream(FILM.csref()));
        assertEquals(60, reference.durationSeconds, 0);
        assertTrue("reference hashes", reference.hashes.length > 500);
        matcher = new LandmarkMatcher(reference);
    }

    @Test
    public void microphoneExcerptMatchesAtItsPosition() {
        float[] heard =
                FILM.renderAsHeard(EXCERPT_START, EXCERPT_SECONDS, LandmarkExtractor.SAMPLE_RATE);
        LandmarkMatcher.Result result = matcher.match(LandmarkExtractor.extract(heard));
        assertTrue("should match: " + result.reason, result.matched);
        assertEquals(EXCERPT_START, result.queryStartSeconds, 0.35);
    }

    @Test
    public void nativeRateExcerptMatchesAfterDownsampling() {
        short[] pcm44100 =
                SyntheticSoundtrack.toPcm16(
                        FILM.renderAsHeard(EXCERPT_START, EXCERPT_SECONDS, 44100));
        LandmarkMatcher.Result result =
                matcher.match(
                        LandmarkExtractor.extract(LandmarkExtractor.downsample44100(pcm44100)));
        assertTrue("should match: " + result.reason, result.matched);
        assertEquals(EXCERPT_START, result.queryStartSeconds, 0.35);
    }

    @Test
    public void differentSoundtrackDoesNotMatch() {
        float[] other =
                new SyntheticSoundtrack(9001, 60)
                        .renderAsHeard(
                                EXCERPT_START, EXCERPT_SECONDS, LandmarkExtractor.SAMPLE_RATE);
        assertFalse(matcher.match(LandmarkExtractor.extract(other)).matched);
    }
}
