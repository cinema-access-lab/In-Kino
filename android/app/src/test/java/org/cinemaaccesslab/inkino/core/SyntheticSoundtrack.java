// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * A deterministic stand-in for a film soundtrack: tone bursts at random pitches over light noise.
 * The bursts are defined in continuous time, so any excerpt can be rendered at any sample rate,
 * which lets tests build a reference and a "microphone" recording without any audio files.
 */
final class SyntheticSoundtrack {
    private static final class Burst {
        final double start, length, amplitude, frequency;

        Burst(double start, double length, double amplitude, double frequency) {
            this.start = start;
            this.length = length;
            this.amplitude = amplitude;
            this.frequency = frequency;
        }
    }

    final double durationSeconds;
    private final long seed;
    private final List<Burst> bursts = new ArrayList<>();

    SyntheticSoundtrack(long seed, double durationSeconds) {
        this.seed = seed;
        this.durationSeconds = durationSeconds;
        Random random = new Random(seed);
        for (double t = 0; t < durationSeconds; t += 0.05 + 0.15 * random.nextDouble()) {
            bursts.add(
                    new Burst(
                            t,
                            0.08 + 0.32 * random.nextDouble(),
                            0.1 + 0.2 * random.nextDouble(),
                            300 + 3200 * random.nextDouble()));
        }
    }

    /** Clean samples in [-1, 1] for the window starting at {@code startSeconds}. */
    float[] render(double startSeconds, double seconds, int rate) {
        float[] out = new float[(int) Math.round(seconds * rate)];
        Random noise = new Random(seed * 31 + rate);
        for (int i = 0; i < out.length; i++) out[i] = (float) (0.02 * noise.nextGaussian());
        for (Burst b : bursts) {
            int first = (int) Math.max(0, Math.ceil((b.start - startSeconds) * rate));
            int last =
                    (int)
                            Math.min(
                                    out.length,
                                    Math.ceil((b.start + b.length - startSeconds) * rate));
            for (int i = first; i < last; i++) {
                double local = startSeconds + (double) i / rate - b.start;
                double envelope = 0.5 - 0.5 * Math.cos(2 * Math.PI * local / b.length);
                out[i] +=
                        (float)
                                (b.amplitude
                                        * envelope
                                        * Math.sin(2 * Math.PI * b.frequency * local));
            }
        }
        return out;
    }

    /** The same window as a phone microphone might hear it: quieter, muffled, and noisier. */
    float[] renderAsHeard(double startSeconds, double seconds, int rate) {
        float[] clean = render(startSeconds, seconds, rate);
        Random room = new Random(seed * 17 + 5);
        float smoothed = 0;
        for (int i = 0; i < clean.length; i++) {
            smoothed += 0.6f * (clean[i] - smoothed); // simple low-pass
            clean[i] = 0.5f * smoothed + (float) (0.03 * room.nextGaussian());
        }
        return clean;
    }

    static short[] toPcm16(float[] samples) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) Math.round(Math.max(-1, Math.min(1, samples[i])) * 32767);
        }
        return pcm;
    }

    /** The whole soundtrack as a .csref fingerprint, in the format the publisher produces. */
    byte[] csref() {
        Landmarks landmarks =
                LandmarkExtractor.extract(
                        render(0, durationSeconds, LandmarkExtractor.SAMPLE_RATE));
        long[] records = new long[landmarks.size()];
        for (int i = 0; i < records.length; i++) {
            // Sort by unsigned hash, then by time, as the reader requires.
            records[i] = (Integer.toUnsignedLong(landmarks.hashes[i]) << 31) | landmarks.times[i];
        }
        Arrays.sort(records);
        ByteBuffer out =
                ByteBuffer.allocate(24 + 8 * records.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'C', 'S', 'R', 'F'}).putInt(1).putDouble(durationSeconds);
        out.putInt(records.length);
        for (long record : records) {
            out.putInt((int) (record >>> 31)).putInt((int) (record & 0x7FFFFFFFL));
        }
        CRC32 crc = new CRC32();
        crc.update(out.array(), 0, out.position());
        out.putInt((int) crc.getValue());
        return out.array();
    }
}
