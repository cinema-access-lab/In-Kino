// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.core;

import java.util.HashMap;
import java.util.Map;

/** Same vote thresholds and binning as the Python matcher in shared/fingerprint_lab.py. */
public final class LandmarkMatcher {
    private final FingerprintReference reference;
    public static final double FRAME_SECONDS = 256.0 / 11025.0;

    public static final class Result {
        public final boolean matched;
        public final double queryStartSeconds;
        public final int bestVotes, secondVotes, usable, queryCount;
        public final double ratio;
        public final String reason;

        Result(
                boolean matched,
                double queryStartSeconds,
                int bestVotes,
                int secondVotes,
                int usable,
                int queryCount,
                double ratio,
                String reason) {
            this.matched = matched;
            this.queryStartSeconds = queryStartSeconds;
            this.bestVotes = bestVotes;
            this.secondVotes = secondVotes;
            this.usable = usable;
            this.queryCount = queryCount;
            this.ratio = ratio;
            this.reason = reason;
        }
    }

    public LandmarkMatcher(FingerprintReference reference) {
        this.reference = reference;
    }

    private static int support(Map<Integer, Integer> votes, int bin) {
        return votes.getOrDefault(bin - 1, 0)
                + votes.getOrDefault(bin, 0)
                + votes.getOrDefault(bin + 1, 0);
    }

    public Result match(Landmarks query) {
        if (query.size() < 8)
            return new Result(false, 0, 0, 0, 0, query.size(), 0, "Too few landmarks");
        Map<Integer, Integer> votes = new HashMap<>();
        int accepted = 0;
        for (int i = 0; i < query.size(); i++) {
            int pos = reference.lowerBoundUnsigned(query.hashes[i]);
            if (pos >= reference.hashes.length || reference.hashes[pos] != query.hashes[i])
                continue;
            int last = pos;
            while (last < reference.hashes.length
                    && reference.hashes[last] == query.hashes[i]
                    && last - pos <= 100) last++;
            if (last - pos > 100) continue;
            accepted++;
            for (int k = pos; k < last; k++) {
                int bin = (int) Math.rint((reference.frames[k] - query.times[i]) / 4.0);
                votes.merge(bin, 1, Integer::sum);
            }
        }
        if (votes.isEmpty())
            return new Result(false, 0, 0, 0, accepted, query.size(), 0, "No shared landmarks");
        int bestBin = 0, bestHits = -1;
        for (int bin : votes.keySet()) {
            int s = support(votes, bin);
            if (s > bestHits) {
                bestHits = s;
                bestBin = bin;
            }
        }
        int second = 0;
        for (int bin : votes.keySet()) {
            if (Math.abs((long) bin - bestBin) > 16) second = Math.max(second, support(votes, bin));
        }
        double ratio = bestHits / (double) Math.max(1, second);
        boolean ok = bestHits >= 18 && ratio >= 1.6 && bestHits >= accepted * .05;
        return new Result(
                ok,
                bestBin * 4 * FRAME_SECONDS,
                bestHits,
                second,
                accepted,
                query.size(),
                ratio,
                ok ? "Match" : "Ambiguous / weak");
    }
}
