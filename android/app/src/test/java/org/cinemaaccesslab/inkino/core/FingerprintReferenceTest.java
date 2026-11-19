// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class FingerprintReferenceTest {
    private static final byte[] VALID = new SyntheticSoundtrack(712, 20).csref();

    @Test
    public void validFileIsRead() throws IOException {
        FingerprintReference reference = FingerprintReference.read(new ByteArrayInputStream(VALID));
        assertEquals(20, reference.durationSeconds, 0);
        assertEquals((VALID.length - 24) / 8, reference.hashes.length);
    }

    @Test
    public void corruptedFileIsRejectedByChecksum() {
        byte[] bytes = VALID.clone();
        bytes[30] ^= 1;
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> FingerprintReference.read(new ByteArrayInputStream(bytes)));
        assertTrue(error.getMessage(), error.getMessage().contains("checksum"));
    }
}
