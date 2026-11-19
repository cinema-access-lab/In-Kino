// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Lock, seek, holdover, and gradual correction. Observations are (film seconds, phone seconds). See
 * docs/sync-correction.md for the rules being tested.
 */
public class SyncClockTest {
    private static final double EXACT = 1e-6;

    @Test
    public void lockNeedsTwoConsistentObservations() {
        SyncClock clock = new SyncClock(150);
        clock.observe(38, 100);
        assertSame(SyncClock.State.VERIFYING, clock.state(100));
        assertFalse(PlaybackTimelinePolicy.hasVerifiedOrigin(clock.state(100)));
        clock.observe(40, 102);
        assertSame(SyncClock.State.LOCKED, clock.state(102));
        assertEquals(1, clock.lockGeneration());
    }

    @Test
    public void seekNeedsConfirmation() {
        SyncClock clock = lockedAt(38, 100);
        clock.observe(80, 104);
        assertSame(SyncClock.State.SEEK_CANDIDATE, clock.state(104));
        clock.observe(82, 106);
        assertSame(SyncClock.State.LOCKED, clock.state(106));
        assertEquals(82, clock.estimate(106), 0.01);
    }

    @Test
    public void holdoverKeepsAMonotonicClockIndefinitely() {
        SyncClock clock = lockedAt(80, 104);
        assertSame(SyncClock.State.HOLDOVER, clock.state(113));
        assertTrue(PlaybackTimelinePolicy.hasVerifiedOrigin(clock.state(113)));
        assertTrue(PlaybackTimelinePolicy.deferCorrection(clock.state(113)));
        assertSame(SyncClock.State.HOLDOVER, clock.state(121));
        assertSame(SyncClock.State.HOLDOVER, clock.state(3600));
        assertEquals(116, clock.estimate(140), 0.01);
    }

    @Test
    public void unconfirmedJumpDuringHoldoverDoesNotMoveTheClock() {
        SyncClock clock = lockedAt(80, 104);
        clock.observe(125, 141);
        assertSame(SyncClock.State.HOLDOVER, clock.state(141));
        assertTrue(PlaybackTimelinePolicy.hasVerifiedOrigin(clock.state(141)));
        assertEquals(117, clock.estimate(141), 0.01);
        clock.observe(127, 143);
        assertSame(SyncClock.State.LOCKED, clock.state(143));
        assertEquals(127, clock.estimate(143), 0.01);
        assertEquals(2, clock.lockGeneration());
    }

    @Test
    public void pendingJumpKeepsTheSubtitleTimeline() {
        SyncClock clock = new SyncClock(400);
        clock.observe(12, 10);
        clock.observe(14, 12);
        clock.observe(200, 14);
        assertSame(SyncClock.State.SEEK_CANDIDATE, clock.state(14));
        assertTrue(PlaybackTimelinePolicy.hasVerifiedOrigin(clock.state(14)));
        assertTrue(PlaybackTimelinePolicy.deferCorrection(clock.state(14)));
    }

    @Test
    public void smallPositiveOffsetIsSlewedAtMostHalfAPercent() {
        SyncClock clock = new SyncClock(1000);
        clock.observe(30, 100);
        clock.observe(32, 102);
        clock.observe(34.3, 104);
        assertEquals(
                "one discrepant observation must not move the clock",
                34,
                clock.estimate(104),
                EXACT);
        clock.observe(36.3, 106);
        assertSame(SyncClock.Correction.SLEW, clock.lastAction());
        assertEquals("a slew is not a jump", 1, clock.lockGeneration());
        assertEquals(36, clock.estimate(106), EXACT);
        assertEquals(1.005, clock.playbackRate(106), EXACT);
        assertEquals(46.05, clock.estimate(116), 1e-4);
        assertEquals(96.3, clock.estimate(166), 1e-4);
        assertEquals("speed returns to normal", 1, clock.playbackRate(166), EXACT);
    }

    @Test
    public void isolatedFalseMarkerIsIgnoredButConfirmedSeekJumps() {
        SyncClock clock = new SyncClock(1000);
        clock.observe(30, 100);
        clock.observe(32, 102);
        clock.observe(34.3, 104);
        clock.observe(36.3, 106);
        assertSame(SyncClock.State.HOLDOVER, clock.state(500));
        clock.observe(90, 171);
        assertEquals(101.3, clock.estimate(171), 0.001);
        clock.observe(92, 173);
        assertEquals(2, clock.lockGeneration());
        assertEquals(92, clock.estimate(173), 1e-4);
        assertEquals(1, clock.playbackRate(173), EXACT);
    }

    @Test
    public void smallNegativeOffsetIsSlewedWithoutSteppingBack() {
        SyncClock clock = new SyncClock(1000);
        clock.observe(20, 10);
        clock.observe(22, 12);
        clock.observe(23.7, 14);
        clock.observe(25.7, 16);
        assertSame(SyncClock.Correction.SLEW, clock.lastAction());
        assertEquals(26, clock.estimate(16), EXACT);
        assertEquals(0.995, clock.playbackRate(16), EXACT);
        assertEquals(85.7, clock.estimate(76), 1e-4);
    }

    @Test
    public void jitterBelowOneHundredMillisecondsIsIgnored() {
        SyncClock clock = new SyncClock(1000);
        clock.observe(10, 10);
        clock.observe(12, 12);
        clock.observe(14.055, 14);
        assertSame(SyncClock.Correction.WITHIN_TOLERANCE, clock.lastAction());
        assertEquals(14, clock.estimate(14), 1e-4);
    }

    /** A clock that has locked at {@code film} seconds at phone time {@code now}. */
    private static SyncClock lockedAt(double film, double now) {
        SyncClock clock = new SyncClock(150);
        clock.observe(film - 2, now - 2);
        clock.observe(film, now);
        return clock;
    }
}
