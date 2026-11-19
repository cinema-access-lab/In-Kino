// SPDX-License-Identifier: Apache-2.0

import org.cinemaaccesslab.inkino.core.FingerprintReference;
import org.cinemaaccesslab.inkino.core.LandmarkExtractor;
import org.cinemaaccesslab.inkino.core.LandmarkMatcher;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the Android app's matcher on excerpts of the real film against the fingerprint made by the
 * Python publisher. Usage: java AppMatcherCheck DIR, where DIR holds film.csref and
 * excerpt-SECONDS.s16 files (raw 16-bit little-endian mono PCM at 11025 Hz).
 */
public final class AppMatcherCheck {
    private static final Pattern EXCERPT = Pattern.compile("excerpt-(\\d+)\\.s16");

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args[0]);
        FingerprintReference reference;
        try (InputStream in = new FileInputStream(dir.resolve("film.csref").toFile())) {
            reference = FingerprintReference.read(in);
        }
        LandmarkMatcher matcher = new LandmarkMatcher(reference);
        int checked = 0;
        boolean failed = false;
        try (Stream<Path> files = Files.list(dir).sorted()) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Matcher name = EXCERPT.matcher(file.getFileName().toString());
                if (!name.matches()) continue;
                int start = Integer.parseInt(name.group(1));
                LandmarkMatcher.Result result =
                        matcher.match(LandmarkExtractor.extract(readPcm(file)));
                boolean ok = result.matched && Math.abs(result.queryStartSeconds - start) < 0.35;
                System.out.printf(
                        Locale.ROOT,
                        "  app matcher, excerpt at %3ds -> %s (%s, %d votes)%n",
                        start,
                        result.matched
                                ? String.format(Locale.ROOT, "%.3fs", result.queryStartSeconds)
                                : "no match",
                        ok ? "ok" : "FAILED",
                        result.bestVotes);
                failed |= !ok;
                checked++;
            }
        }
        if (checked == 0) throw new IOException("No excerpts found in " + dir);
        if (failed) System.exit(1);
    }

    private static float[] readPcm(Path file) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[bytes.remaining() / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = bytes.getShort() / 32768f;
        return samples;
    }
}
