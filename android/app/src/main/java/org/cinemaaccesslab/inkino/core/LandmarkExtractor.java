// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Plain-Java port of the spectral landmark extractor in shared/fingerprint_lab.py. Uses only
 * public-domain math and our own code; no external DSP packages. Deliberately keeps the same STFT,
 * whitening, peak and landmark definitions.
 */
public final class LandmarkExtractor {
    public static final int SAMPLE_RATE = 11025, FFT_SIZE = 2048, HOP = 256;
    private static final int BINS = FFT_SIZE / 2 + 1;
    private static final int GAUSS_RADIUS = 56;
    private static final double[] GAUSS = gaussianWeights();
    private static final double[] WINDOW = hannWindow();

    private LandmarkExtractor() {}

    /** Four-to-one 44,100 Hz > 11,025 Hz conversion with SciPy resample_poly-compatible FIR. */
    public static float[] downsample44100(short[] mono44100) {
        final int len = 81, half = 40;
        float[] out = new float[(mono44100.length + 3) / 4];
        double[] weights = new double[len];
        double denom = besselI0(5.0);
        for (int k = 0; k < len; k++) {
            int offset = k - half;
            double z = offset / 40.0;
            double window = besselI0(5.0 * Math.sqrt(Math.max(0, 1 - z * z))) / denom;
            weights[k] =
                    (offset == 0 ? .25 : Math.sin(Math.PI * offset * .25) / (Math.PI * offset))
                            * window;
        }
        // scipy.signal.resample_poly(x, 1, 4): symmetric Kaiser (beta=5), 81 taps
        for (int p = 0; p < out.length; p++) {
            double total = 0;
            int center = p * 4;
            for (int k = 0; k < len; k++) {
                int idx = center + k - half;
                if (idx >= 0 && idx < mono44100.length)
                    total += weights[k] * (mono44100[idx] / 32768.0);
            }
            out[p] = (float) total;
        }
        return out;
    }

    private static double besselI0(double x) {
        // Series adequate to much better than 1e-9 at x<=5.
        double sum = 1, term = 1, a = x * x / 4;
        for (int j = 1; j <= 30; j++) {
            term *= a / (j * j);
            sum += term;
            if (term < 1e-16) break;
        }
        return sum;
    }

    private static double[] hannWindow() {
        double[] w = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++)
            w[i] = (float) (.5 - .5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
        return w;
    }

    private static double[] gaussianWeights() {
        double[] w = new double[2 * GAUSS_RADIUS + 1];
        double sum = 0;
        for (int j = -GAUSS_RADIUS; j <= GAUSS_RADIUS; j++) {
            w[j + GAUSS_RADIUS] = Math.exp(-.5 * j * j / (14. * 14.));
            sum += w[j + GAUSS_RADIUS];
        }
        for (int j = 0; j < w.length; j++) w[j] /= sum;
        return w;
    }

    private static int reflect(int pos, int size) {
        while (pos < 0 || pos >= size) {
            if (pos < 0) pos = -pos - 1;
            else pos = 2 * size - 1 - pos;
        }
        return pos;
    }

    private static void fft(double[] real, double[] imaginary) {
        final int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = real[i];
                real[i] = real[j];
                real[j] = t;
                t = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = t;
            }
        }
        for (int block = 2; block <= n; block <<= 1) {
            double angle = -2 * Math.PI / block;
            double cr = Math.cos(angle), ci = Math.sin(angle);
            for (int i = 0; i < n; i += block) {
                double wr = 1, wi = 0;
                for (int j = 0; j < block / 2; j++) {
                    int u = i + j, v = u + block / 2;
                    double tr = wr * real[v] - wi * imaginary[v];
                    double ti = wr * imaginary[v] + wi * real[v];
                    real[v] = real[u] - tr;
                    imaginary[v] = imaginary[u] - ti;
                    real[u] += tr;
                    imaginary[u] += ti;
                    double next = wr * cr - wi * ci;
                    wi = wr * ci + wi * cr;
                    wr = next;
                }
            }
        }
    }

    private static final class Peak {
        final int time, frequency;
        final float score;

        Peak(int t, int f, float s) {
            time = t;
            frequency = f;
            score = s;
        }
    }

    public static Landmarks extract(float[] mono11025) {
        int frameCount = mono11025.length < FFT_SIZE ? 0 : (mono11025.length - FFT_SIZE) / HOP + 1;
        if (frameCount == 0) return new Landmarks(new int[0], new int[0]);
        float[][] db = new float[frameCount][BINS];
        float[][] white = new float[frameCount][BINS];
        float[] loudest = new float[frameCount];
        double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE];
        for (int frame = 0; frame < frameCount; frame++) {
            int offset = frame * HOP;
            for (int j = 0; j < FFT_SIZE; j++) {
                re[j] = mono11025[offset + j] * WINDOW[j];
                im[j] = 0;
            }
            fft(re, im);
            float max = Float.NEGATIVE_INFINITY;
            for (int f = 0; f < BINS; f++) {
                float mag = (float) Math.hypot(re[f], im[f]);
                float level = (float) (20.0 * Math.log10(Math.max(1e-6f, mag)));
                db[frame][f] = level;
                if (level > max) max = level;
            }
            loudest[frame] = max;
            // Gaussian smoothing on spectral (not time) dimension, sigma=14, reflect mode.
            for (int f = 0; f < BINS; f++) {
                double smoothed = 0;
                for (int j = -GAUSS_RADIUS; j <= GAUSS_RADIUS; j++)
                    smoothed += db[frame][reflect(f + j, BINS)] * GAUSS[j + GAUSS_RADIUS];
                white[frame][f] = (f < 46 || f >= 745) ? -1000f : (float) (db[frame][f] - smoothed);
            }
        }
        ArrayList<Peak> peaks = new ArrayList<>();
        for (int cellStart = 0; cellStart < frameCount; cellStart += 4) {
            Peak strongest = null, second = null;
            for (int t = cellStart; t < Math.min(frameCount, cellStart + 4); t++) {
                for (int f = 46; f < 745; f++) {
                    float val = white[t][f];
                    if (val <= 5 || db[t][f] <= loudest[t] - 48) continue;
                    boolean localMax = true;
                    outer:
                    for (int dt = -3; dt <= 3; dt++) {
                        int row = Math.max(0, Math.min(frameCount - 1, t + dt));
                        for (int df = -5; df <= 5; df++) {
                            int col = Math.max(0, Math.min(BINS - 1, f + df));
                            if (white[row][col] > val) {
                                localMax = false;
                                break outer;
                            }
                        }
                    }
                    if (!localMax) continue;
                    Peak peak = new Peak(t, f, val);
                    if (strongest == null || val > strongest.score) {
                        second = strongest;
                        strongest = peak;
                    } else if (second == null || val > second.score) second = peak;
                }
            }
            if (second != null) peaks.add(second);
            if (strongest != null) peaks.add(strongest);
        }
        peaks.sort(Comparator.comparingInt((Peak p) -> p.time).thenComparingInt(p -> p.frequency));
        int[] times = new int[Math.max(16, peaks.size() * 6)];
        int[] hashes = new int[times.length];
        int used = 0;
        for (int i = 0; i < peaks.size(); i++) {
            Peak a = peaks.get(i);
            int formed = 0;
            for (int j = i + 1; j < peaks.size(); j++) {
                Peak b = peaks.get(j);
                int dt = b.time - a.time;
                if (dt > 59) break;
                if (dt < 5 || Math.abs(a.frequency - b.frequency) < 7) continue;
                if (used == times.length) {
                    times = Arrays.copyOf(times, times.length * 2);
                    hashes = Arrays.copyOf(hashes, hashes.length * 2);
                }
                times[used] = a.time;
                hashes[used] =
                        ((a.frequency / 4) << 16) | ((b.frequency / 4) << 8) | ((dt + 1) / 2);
                used++;
                if (++formed >= 6) break;
            }
        }
        return new Landmarks(Arrays.copyOf(times, used), Arrays.copyOf(hashes, used));
    }
}
