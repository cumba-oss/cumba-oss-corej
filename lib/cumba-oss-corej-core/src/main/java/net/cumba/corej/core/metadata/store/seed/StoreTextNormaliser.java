package net.cumba.corej.core.metadata.store.seed;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The seeder's text normalisation (PLAN-store-library-content-errata §2.2, census §2.6): the one
 * defect class the source carries <em>systematically</em> rather than in individual rows, fixed for
 * every product text field at projection time and counted in the seed report.
 *
 * <ul>
 * <li><b>HTML entities</b> (the named quot, amp, lt, gt, apos and nbsp entities, and numeric ones)
 * are decoded in every text field — the source carries {@code "Not Done"} spelled with the quot
 * entity where the print has real quotes (issue #283.40).</li>
 * <li><b>Short fields</b> ({@link #SHORT_FIELDS}: label, prompt, usage restrictions, role
 * description, name) are written into datasets and define.xml, where a line break is never right: a
 * literal two-character {@code \n} <em>and</em> a real line break both become one space, and the
 * field is trimmed ({@code Sequence Identifier \n} → {@code Sequence Identifier}, #283.34;
 * {@code On Treatment⏎Record Flag} → one space, #282.13).</li>
 * <li><b>Long fields</b> (every other text): a literal {@code \n} is the source's escaped line
 * break (#283.35: 23 notes/examples fields per SDTM 2.x model print a hard line break there), so it
 * becomes a real line break with the surrounding spaces folded, and the field loses trailing
 * whitespace. A real line break already in a long field is left alone.</li>
 * </ul>
 *
 * <p>
 * Nothing else is touched: a doubled space, a trailing space, a stray character stay as the source
 * has them — those are individual rows of the errata register ({@link StoreErrata}), never a
 * blanket rewrite. CT terms and codelists go through the plain reader, not this class.
 * </p>
 *
 * <p>
 * ⚠ The errata register's {@code expected} values are the <em>normalised</em> source values, so a
 * rule changed here makes entries go stale unless the affected {@code expected} values in
 * {@code store-errata.json} are re-derived by hand. (The generator that produced the register's
 * first version, {@code gen_register.py}, mirrored these rules in its {@code norm()}; it is frozen
 * and no longer reproduces the register, which is now edited by hand.)
 * </p>
 */
final class StoreTextNormaliser
{

    /** The fields whose line breaks become one space and which are trimmed. */
    static final Set<String> SHORT_FIELDS = Set.of("label", "prompt", "usageRestrictions",
            "roleDescription", "name");

    private static final Pattern ENTITY = Pattern
            .compile("&(quot|amp|lt|gt|apos|nbsp|#\\d+|#x[0-9A-Fa-f]+);");

    /** A literal backslash-n or a real line break with the spaces around it (short fields). */
    private static final Pattern ANY_BREAK = Pattern
            .compile("[ \\t]*(\\\\n|\\r\\n|\\n|\\r)[ \\t]*");

    /** A literal backslash-n with the spaces around it (long fields). */
    private static final Pattern LITERAL_BREAK = Pattern.compile("[ \\t]*\\\\n[ \\t]*");

    private static final Pattern HAS_ANY_BREAK = Pattern.compile("\\\\n|\\r|\\n");

    /** What one normalisation did to a value, for the counters. */
    enum Kind
    {
        /** A literal {@code \n} in a long text became a real line break. */
        LITERAL_NEWLINE,
        /** A literal or real line break in a short field became one space. */
        SHORT_FIELD_NEWLINE,
        /** An HTML entity was decoded. */
        HTML_ENTITY
    }


    /** The normalised value and which kinds of change it took. */
    record Result(@Nullable String value, Set<Kind> kinds)
    {
    }

    private StoreTextNormaliser()
    {
    }


    /**
     * Normalises one product text field.
     *
     * @param aField
     *            the source field name (decides short vs long)
     * @param aValue
     *            the source value, or {@code null}
     * @return the normalised value and the kinds of change applied (empty when unchanged)
     */
    static Result normalise(String aField, @Nullable String aValue)
    {
        if (aValue == null)
        {
            return new Result(null, Set.of());
        }
        java.util.EnumSet<Kind> kinds = java.util.EnumSet.noneOf(Kind.class);
        String out = aValue;
        if (out.indexOf('&') >= 0)
        {
            String decoded = decodeEntities(out);
            if (!decoded.equals(out))
            {
                kinds.add(Kind.HTML_ENTITY);
                out = decoded;
            }
        }
        if (SHORT_FIELDS.contains(aField))
        {
            if (HAS_ANY_BREAK.matcher(out).find())
            {
                out = ANY_BREAK.matcher(out).replaceAll(" ").strip();
                kinds.add(Kind.SHORT_FIELD_NEWLINE);
            }
        }
        else if (out.contains("\\n"))
        {
            out = LITERAL_BREAK.matcher(out).replaceAll("\n").stripTrailing();
            kinds.add(Kind.LITERAL_NEWLINE);
        }
        return new Result(out, kinds);
    }


    /**
     * Decodes the entities {@link #ENTITY} matches. An entity that does not decode to a valid
     * character — a numeric value past {@code Integer.MAX_VALUE}, past {@code U+10FFFF} or in the
     * surrogate range — is left verbatim: the normaliser never throws on source text (review F7).
     * Names outside {@link #ENTITY}'s list are never matched, so they stay verbatim too.
     */
    static String decodeEntities(String aText)
    {
        Matcher m = ENTITY.matcher(aText);
        StringBuilder sb = new StringBuilder(aText.length());
        while (m.find())
        {
            String entity = m.group(1);
            String replacement = switch (entity)
            {
            case "quot" -> "\"";
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "apos" -> "'";
            case "nbsp" -> " ";
            default -> numeric(entity, m.group());
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }


    /** A numeric entity's character, or {@code aVerbatim} when it names no valid character. */
    private static String numeric(String aEntity, String aVerbatim)
    {
        boolean hex = aEntity.startsWith("#x");
        int codePoint;
        try
        {
            codePoint = Integer.parseInt(aEntity.substring(hex ? 2 : 1), hex ? 16 : 10);
        }
        catch (NumberFormatException _)
        {
            return aVerbatim;
        }
        if (!Character.isValidCodePoint(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE))
        {
            return aVerbatim;
        }
        return Character.toString(codePoint);
    }
}
