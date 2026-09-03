package net.cumba.corej.define.conformance.rule;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Named, canned value-format patterns for {@code matches_regex} rules (full-match semantics). Named
 * formats are preferred over inline regexes so recurring formats (ISO 8601, integers) stay
 * byte-identical across rules.
 */
public final class RegexFormats
{

    /**
     * ISO 8601 complete datetime with optional fractional seconds and optional zone — the shape
     * ODM's {@code datetime} type accepts (e.g. {@code 2024-03-01T10:15:30},
     * {@code 2024-03-01T10:15:30.5+02:00}, {@code 2024-03-01T10:15:30Z}).
     */
    private static final String ISO8601_DATETIME = "\\d{4}-\\d{2}-\\d{2}"
            + "T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?((\\+|-)\\d{2}:\\d{2}|Z)?";

    /** ISO 8601 complete date ({@code YYYY-MM-DD}). */
    private static final String ISO8601_DATE = "\\d{4}-\\d{2}-\\d{2}";

    private static final Map<String, Pattern> FORMATS = Map.of(//
            "iso8601-datetime", Pattern.compile(ISO8601_DATETIME), //
            "iso8601-date", Pattern.compile(ISO8601_DATE), //
            "integer", Pattern.compile("[+-]?\\d+"), //
            "non-negative-integer", Pattern.compile("\\+?\\d+"), //
            "positive-integer", Pattern.compile("\\+?0*[1-9]\\d*"), //
            // PMDA DD0025: a decimal MedDRA version ending in .0 or .1 (e.g. "9.0", "14.1").
            "meddra-version", Pattern.compile("\\d+\\.[01]"));

    private RegexFormats()
    {
    }


    /** The pattern for a named format; throws {@link IllegalStateException} for unknown names. */
    public static Pattern byName(String aName)
    {
        Pattern pattern = FORMATS.get(aName);
        if (pattern == null)
        {
            throw new IllegalStateException("unknown matches_regex format '" + aName + "'; known: "
                    + FORMATS.keySet().stream().sorted().toList());
        }
        return pattern;
    }

}
