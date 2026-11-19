// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

/** Bounded real-time ring, designed to be written by mic thread and copied by worker. */
public final class AudioRing {
    public static final class Snapshot {
        public final short[] data;
        public final long endElapsedNanos;

        Snapshot(short[] x, long t) {
            data = x;
            endElapsedNanos = t;
        }
    }

    private final short[] buffer;
    private int tail = 0, filled = 0;
    private long lastEndNanos;

    public AudioRing(int sampleRate, int historySeconds) {
        buffer = new short[sampleRate * historySeconds];
    }

    public synchronized void put(short[] data, int n, long captureEndNanos) {
        if (n < 0 || n > data.length) throw new IllegalArgumentException();
        for (int i = 0; i < n; i++) {
            buffer[tail] = data[i];
            tail = (tail + 1) % buffer.length;
        }
        filled = Math.min(buffer.length, filled + n);
        lastEndNanos = captureEndNanos;
    }

    public synchronized Snapshot recent(int required) {
        if (required > filled || required <= 0) return null;
        short[] copy = new short[required];
        int start = (tail - required + buffer.length) % buffer.length;
        for (int i = 0; i < required; i++) copy[i] = buffer[(start + i) % buffer.length];
        return new Snapshot(copy, lastEndNanos);
    }
}
