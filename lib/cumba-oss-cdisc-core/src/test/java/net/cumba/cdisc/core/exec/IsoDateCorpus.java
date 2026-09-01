package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fix #209 — the shared differential corpus for the ISO-8601 structural predicates.
 *
 * <p>
 * Three generators, because random noise alone almost never produces a <i>near-miss</i> of a
 * canonical form and the boundary logic lives exactly there:
 * </p>
 * <ol>
 * <li><b>Exhaustive</b> over every string of length 0–4 from a ten-character alphabet of the
 * characters that can change a verdict.</li>
 * <li><b>Mutations</b> — every single-character substitution, deletion and insertion at every
 * position of every canonical ISO form.</li>
 * <li><b>Decorations</b> — offsets, fractional seconds, intervals, the SDTM masked forms, and
 * (load-bearing) the <b>stacked</b> decorations that are the only inputs on which the pre-Fix-#209
 * {@code CalendarDates.isValidDate} could throw.</li>
 * <li><b>Seeded random noise</b> — 60 000 strings; the seed is fixed so a failure reproduces.</li>
 * </ol>
 */
public final class IsoDateCorpus
{

    /** The canonical right-truncated forms, one per precision tier. */
    public static final List<String> CANONICAL = List.of("2012", "2012-06", "2012-06-15",
            "2012-06-15T10", "2012-06-15T10:30", "2012-06-15T10:30:45");

    /** The four SDTM masked forms plus their near-misses. */
    public static final List<String> MASKED_SHAPES = List.of("2012-06-", "2012-06--", "2012--15",
            "2012---15", "--06-15", "----06-15", "2012-0--15", "2012----15", "2012---5",
            "0000-00--");

    private static final char[] ALPHABET =
    {
            '0', '9', '-', ':', 'T', 'Z', '+', '.', '/', 'x'
    };

    private static final String[] TAILS =
    {
            "", "Z", "ZZ", "ZZZ", "+01:00", "-05:00", "+0100", ".000", ".5", "Z+01:00",
            "+01:00+01:00", "-99:99", "-99:99+01:00", ".000Z", ".000ZZ"
    };

    private static List<String> cached;

    private IsoDateCorpus()
    {
    }


    /** The whole corpus, built once per JVM. */
    public static synchronized List<String> all()
    {
        if (cached == null)
        {
            List<String> out = new ArrayList<>();
            exhaustiveUpToLength(out, 4);
            mutations(out);
            decorations(out);
            randomNoise(out);
            cached = List.copyOf(out);
        }
        return cached;
    }


    private static void exhaustiveUpToLength(List<String> out, int maxLen)
    {
        out.add("");
        List<String> level = List.of("");
        for (int len = 1; len <= maxLen; len++)
        {
            List<String> next = new ArrayList<>();
            for (String prefix : level)
            {
                for (char c : ALPHABET)
                {
                    next.add(prefix + c);
                }
            }
            out.addAll(next);
            level = next;
        }
    }


    private static void mutations(List<String> out)
    {
        for (String base : CANONICAL)
        {
            out.add(base);
            for (int i = 0; i < base.length(); i++)
            {
                out.add(base.substring(0, i) + base.substring(i + 1));
                for (char c : ALPHABET)
                {
                    out.add(base.substring(0, i) + c + base.substring(i + 1));
                    out.add(base.substring(0, i) + c + base.substring(i));
                }
            }
            for (char c : ALPHABET)
            {
                out.add(base + c);
            }
        }
    }


    private static void decorations(List<String> out)
    {
        for (String base : CANONICAL)
        {
            for (String tail : TAILS)
            {
                out.add(base + tail);
                out.add(base + tail + "/" + base);
                out.add(base + "/" + base + tail);
            }
        }
        for (String masked : MASKED_SHAPES)
        {
            out.add(masked);
            for (String tail : TAILS)
            {
                out.add(masked + tail);
            }
        }
    }


    private static void randomNoise(List<String> out)
    {
        Random rnd = new Random(20_260_810L);
        for (int i = 0; i < 60_000; i++)
        {
            int len = rnd.nextInt(24);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++)
            {
                sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
            }
            out.add(sb.toString());
        }
    }

}
