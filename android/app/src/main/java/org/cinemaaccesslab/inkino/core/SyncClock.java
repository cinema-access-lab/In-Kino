// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

/**
 * Shared continuous presentation clock for AD and SDH. A candidate never changes the clock before
 * independent corroboration. Large confirmed jumps reset its origin; small confirmed discrepancies
 * are retired by a bounded rate slew rather than by sudden time steps.
 *
 * <p>All callers must serialize access (the Android app uses clockGuard).
 */
public final class SyncClock {
    public enum State {
        SEARCHING,
        VERIFYING,
        LOCKED,
        SEEK_CANDIDATE,
        HOLDOVER
    }

    public enum Correction {
        NONE,
        WITHIN_TOLERANCE,
        SLEW,
        SEEK
    }

    /** Phase noise below this threshold does not cause a correction. */
    public static final double TOLERANCE_SECONDS = 0.100;

    /** Above this, corroborated observations imply a discontinuity. */
    public static final double SEEK_THRESHOLD_SECONDS = 1.500;

    /** Absolute maximum speed variation for gradual correction (0.5%). */
    public static final double MAX_SLEW_FRACTION = 0.005;

    private static final double CONFIRM_MATCH_SECONDS = 0.35;
    private static final double CANDIDATE_EXPIRY_SECONDS = 12.0;
    private final double duration;
    private boolean anchored = false, pending = false;
    private double filmAnchor, monoAnchor, confirmation, pendingFilm, pendingMono;

    /** Signed seconds still to retire; not applied as an instantaneous jump. */
    private double desiredSlew;

    private int pendingCount, lockGeneration;
    public double lastCorrection = Double.NaN;
    private Correction lastAction = Correction.NONE;

    public SyncClock(double duration) {
        this.duration = duration;
    }

    private static double clip(double n, double limit) {
        return Math.max(-limit, Math.min(limit, n));
    }

    private double accruedSlew(double mono) {
        if (!anchored) return 0;
        double elapsed = Math.max(0, mono - monoAnchor);
        return clip(desiredSlew, MAX_SLEW_FRACTION * elapsed);
    }

    public double estimate(double mono) {
        if (!anchored) return Double.NaN;
        return Math.max(
                0,
                Math.min(
                        duration, filmAnchor + Math.max(0, mono - monoAnchor) + accruedSlew(mono)));
    }

    /** Playback media rate needed for AD to follow the same clock as SDH. */
    public double playbackRate(double mono) {
        if (!anchored || mono < monoAnchor) return 1.0;
        if (Math.abs(desiredSlew) - MAX_SLEW_FRACTION * (mono - monoAnchor) <= 0.000001) return 1.0;
        return desiredSlew > 0
                ? 1.0 + MAX_SLEW_FRACTION
                : desiredSlew < 0 ? 1.0 - MAX_SLEW_FRACTION : 1.0;
    }

    public double slewRemaining(double mono) {
        return anchored ? desiredSlew - accruedSlew(mono) : 0;
    }

    public Correction lastAction() {
        return lastAction;
    }

    /** Roll any already-played fraction of a slew into the clock's anchor. */
    private void rollTo(double mono) {
        if (!anchored) return;
        double used = accruedSlew(mono);
        filmAnchor = estimate(mono);
        monoAnchor = mono;
        desiredSlew -= used;
        if (Math.abs(desiredSlew) < 0.000001) desiredSlew = 0;
    }

    public void observe(double filmAtMicEnd, double micEndMono) {
        if (!Double.isFinite(filmAtMicEnd)
                || !Double.isFinite(micEndMono)
                || filmAtMicEnd < 0
                || filmAtMicEnd > duration + 1) return;
        double predicted = estimate(micEndMono);
        double error = filmAtMicEnd - predicted;
        if (anchored && Math.abs(error) <= TOLERANCE_SECONDS) {
            rollTo(micEndMono);
            // Residual discrepancy is indistinguishable from acoustic measurement noise.
            // End an active slew within the deadband, rather than hunting around zero.
            desiredSlew = 0;
            confirmation = micEndMono;
            pending = false;
            pendingCount = 0;
            lastCorrection = error;
            lastAction = Correction.WITHIN_TOLERANCE;
            return;
        }
        // Corroboration is independent in acquisition time, and its predicted position
        // progresses at normal projector speed. A pause/jump cannot be diagnosed from
        // sparse observations alone: we only establish the NEW film position.
        if (pending
                && micEndMono - pendingMono <= CANDIDATE_EXPIRY_SECONDS
                && micEndMono > pendingMono + 0.3
                && Math.abs(filmAtMicEnd - (pendingFilm + micEndMono - pendingMono))
                        <= CONFIRM_MATCH_SECONDS) pendingCount++;
        else pendingCount = 1;
        pending = true;
        pendingFilm = filmAtMicEnd;
        pendingMono = micEndMono;
        if (pendingCount < 2) return;
        pending = false;
        pendingCount = 0;
        confirmation = micEndMono;
        lastCorrection = error;
        if (!anchored || Math.abs(error) > SEEK_THRESHOLD_SECONDS) {
            filmAnchor = filmAtMicEnd;
            monoAnchor = micEndMono;
            desiredSlew = 0;
            anchored = true;
            lockGeneration++;
            lastAction = Correction.SEEK;
        } else {
            // Use the verified residual relative to the pre-existing clock, without
            // changing the presentation time at this instant.
            rollTo(micEndMono);
            desiredSlew = clip(error, SEEK_THRESHOLD_SECONDS);
            lastAction = Correction.SLEW;
        }
    }

    public State state(double monoNow) {
        if (!anchored) return pending ? State.VERIFYING : State.SEARCHING;
        if (monoNow - confirmation >= 5) return State.HOLDOVER;
        return pending ? State.SEEK_CANDIDATE : State.LOCKED;
    }

    public boolean hasLock() {
        return anchored;
    }

    public int lockGeneration() {
        return lockGeneration;
    }

    public double confirmedAge(double now) {
        return anchored ? Math.max(0, now - confirmation) : Double.NaN;
    }
}
