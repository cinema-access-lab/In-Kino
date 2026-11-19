// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

/**
 * Shared AD/SDH gating: never blank or pause an already synchronized timeline because the
 * microphone is obscured or a possible seek lacks confirmation.
 */
public final class PlaybackTimelinePolicy {
    private PlaybackTimelinePolicy() {}

    public static boolean hasVerifiedOrigin(SyncClock.State state) {
        return state == SyncClock.State.LOCKED
                || state == SyncClock.State.HOLDOVER
                || state == SyncClock.State.SEEK_CANDIDATE;
    }

    public static boolean deferCorrection(SyncClock.State state) {
        return state == SyncClock.State.HOLDOVER || state == SyncClock.State.SEEK_CANDIDATE;
    }
}
