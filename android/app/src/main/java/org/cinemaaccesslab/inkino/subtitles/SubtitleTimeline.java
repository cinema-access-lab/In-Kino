// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino.subtitles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone, dependency-free SRT/WebVTT cue parser and film-clock lookup. Cue times are
 * presentation timestamps; no frame-rate conversion is performed. Preserve SDH content such as
 * [MUSIC], (LAUGHS), speakers and dialogue dashes.
 */
public final class SubtitleTimeline {
    public static final class Cue {
        public final long startMs, endMs;
        public final String text;

        public Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static final int MAX_CHARS = 4_000_000;
    private static final int MAX_CUES = 50_000;
    private static final Pattern TIME_LINE =
            Pattern.compile(
                    "^\\s*((?:\\d{1,3}:)?\\d{2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d{1,3}:)?\\d{2}:\\d{2}[,.]\\d{1,3})(?:\\s+.*)?$");
    private static final Pattern TIME =
            Pattern.compile("^(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[,.](\\d{1,3})$");
    private final List<Cue> cues;

    private SubtitleTimeline(List<Cue> cues) {
        this.cues = Collections.unmodifiableList(cues);
    }

    public int size() {
        return cues.size();
    }

    public List<Cue> cues() {
        return cues;
    }

    public static SubtitleTimeline parse(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        StringBuilder raw = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) != -1) {
            if (raw.length() + n > MAX_CHARS)
                throw new IOException("Subtitle file exceeds 4 million characters");
            raw.append(buf, 0, n);
        }
        String normalized =
                raw.toString().replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\\n[ \\t]*\\n");
        List<Cue> list = new ArrayList<>();
        for (String block : blocks) {
            String[] lines = block.split("\n", -1);
            int timeline = -1;
            Matcher matcher = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher m = TIME_LINE.matcher(lines[i]);
                if (m.matches()) {
                    timeline = i;
                    matcher = m;
                    break;
                }
            }
            if (timeline < 0 || matcher == null || timeline >= lines.length - 1) continue;
            long start, end;
            try {
                start = toMs(matcher.group(1));
                end = toMs(matcher.group(2));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (end <= start) continue;
            StringBuilder content = new StringBuilder();
            for (int i = timeline + 1; i < lines.length; i++) {
                String line = cleanMarkup(lines[i]).trim();
                if (line.isEmpty()) continue;
                if (content.length() > 0) content.append('\n');
                content.append(line);
            }
            if (content.length() == 0) continue;
            list.add(new Cue(start, end, content.toString()));
            if (list.size() > MAX_CUES) throw new IOException("Subtitle file exceeds 50,000 cues");
        }
        list.sort(Comparator.comparingLong((Cue c) -> c.startMs).thenComparingLong(c -> c.endMs));
        if (list.isEmpty()) throw new IOException("No valid SRT/WebVTT subtitle cues found");
        return new SubtitleTimeline(list);
    }

    private static long toMs(String value) {
        Matcher m = TIME.matcher(value);
        if (!m.matches()) throw new IllegalArgumentException("Bad cue time");
        int hours = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int minutes = Integer.parseInt(m.group(2)), seconds = Integer.parseInt(m.group(3));
        if (minutes >= 60 || seconds >= 60) throw new IllegalArgumentException("Invalid cue time");
        String fraction = m.group(4);
        while (fraction.length() < 3) fraction += "0";
        return (((hours * 60L + minutes) * 60 + seconds) * 1000) + Integer.parseInt(fraction);
    }

    private static String cleanMarkup(String s) {
        // Strip only common inline formatting, not speaker labels, SDH brackets or dashes.
        return s.replaceAll("(?i)</?(?:i|b|u|font)(?:\\s+[^>]*)?>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    public String textAt(long filmMs) {
        if (filmMs < 0 || cues.isEmpty()) return "";
        int low = 0, high = cues.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cues.get(mid).startMs <= filmMs) low = mid + 1;
            else high = mid;
        }
        int last = low - 1;
        if (last < 0) return "";
        StringBuilder visible = new StringBuilder();
        // Overlapping cues are permitted, so inspect earlier starts as well.
        for (int i = 0; i <= last; i++) {
            Cue cue = cues.get(i);
            if (filmMs < cue.endMs) {
                if (visible.length() > 0) visible.append('\n');
                visible.append(cue.text);
            }
        }
        return visible.toString();
    }
}
