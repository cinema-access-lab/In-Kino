// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

/** Query-side uncompressed acoustic landmarks. */
public final class Landmarks {
    public final int[] times;
    public final int[] hashes;

    public Landmarks(int[] times, int[] hashes) {
        if (times.length != hashes.length)
            throw new IllegalArgumentException("Array lengths differ");
        this.times = times;
        this.hashes = hashes;
    }

    public int size() {
        return times.length;
    }
}
