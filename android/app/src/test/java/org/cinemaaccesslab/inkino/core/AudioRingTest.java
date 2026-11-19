// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioRingTest {
    @Test
    public void recentReturnsNewestSamplesInOrder() {
        AudioRing ring = new AudioRing(44100, 12);
        short[] samples = new short[100];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) i;
        ring.put(samples, samples.length, 12_345_678L);

        AudioRing.Snapshot snapshot = ring.recent(80);
        assertEquals(20, snapshot.data[0]);
        assertEquals(99, snapshot.data[79]);
        assertEquals(12_345_678L, snapshot.endElapsedNanos);
    }
}
