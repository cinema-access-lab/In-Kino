// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Standalone reference index; no Android framework or third-party dependencies. */
public final class FingerprintReference {
    public final double durationSeconds;
    public final int[] hashes;
    public final int[] frames;

    private FingerprintReference(double durationSeconds, int[] hashes, int[] frames) {
        this.durationSeconds = durationSeconds;
        this.hashes = hashes;
        this.frames = frames;
    }

    public static FingerprintReference read(InputStream stream) throws IOException {
        try (InputStream in = stream;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (output.size() + n > 40_000_024)
                    throw new IOException("Reference too large (40 MB limit)");
                output.write(buffer, 0, n);
            }
            byte[] data = output.toByteArray();
            if (data.length < 24
                    || data[0] != 'C'
                    || data[1] != 'S'
                    || data[2] != 'R'
                    || data[3] != 'F') throw new IOException("Not an In-Kino .csref file");
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            bb.position(4);
            int version = bb.getInt();
            if (version != 1) throw new IOException("Unsupported .csref version " + version);
            double duration = bb.getDouble();
            int count = bb.getInt();
            if (count < 1
                    || count > 5_000_000
                    || !Double.isFinite(duration)
                    || duration <= 0
                    || duration > 43200)
                throw new IOException("Invalid duration or landmark count");
            if (data.length != 24L + count * 8L)
                throw new IOException("Incorrect .csref file size");
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length - 4);
            int stored =
                    ByteBuffer.wrap(data, data.length - 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .getInt();
            if ((int) crc.getValue() != stored)
                throw new IOException("Fingerprint checksum mismatch");
            int[] hashes = new int[count];
            int[] frames = new int[count];
            long previous = -1;
            for (int i = 0; i < count; i++) {
                int hash = bb.getInt();
                int frame = bb.getInt();
                if (Integer.toUnsignedLong(hash) < previous || frame < 0)
                    throw new IOException("Invalid hash ordering or timestamp");
                previous = Integer.toUnsignedLong(hash);
                hashes[i] = hash;
                frames[i] = frame;
            }
            return new FingerprintReference(duration, hashes, frames);
        }
    }

    public int lowerBoundUnsigned(int hash) {
        int low = 0, high = hashes.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Integer.compareUnsigned(hashes[mid], hash) < 0) low = mid + 1;
            else high = mid;
        }
        return low;
    }
}
