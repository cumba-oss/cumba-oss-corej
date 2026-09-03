package net.cumba.corej.core.expr.eval;

import java.util.Locale;

import net.cumba.corej.core.expr.RuleDefinitionException;

/**
 * The three metadata levels a {@code var_*} / {@code ds_*} accessor can read from:
 * <ul>
 * <li>{@link #DATA} — the submitted dataset itself (column / table metadata);</li>
 * <li>{@link #DEFINE} — the sponsor Define-XML ({@code EvaluationContext.defineProvider});</li>
 * <li>{@link #LIBRARY} — the CDISC Library ({@code EvaluationContext.libraryProvider}).</li>
 * </ul>
 *
 * <p>
 * The level is always supplied as a (case-insensitive) string literal in the rule; an unrecognised
 * spelling is a {@link RuleDefinitionException} (the rule is wrong), not a silent fallback.
 * </p>
 */
public enum MetadataLevel
{

    DATA(1), DEFINE(1 << 1), LIBRARY(1 << 2);

    private final int bit;

    MetadataLevel(int bit)
    {
        this.bit = bit;
    }


    /** A stable, explicit bit for this level (not {@code ordinal()}), used by support bitmasks. */
    int bit()
    {
        return bit;
    }


    /**
     * Parses a level literal (case-insensitive, surrounding whitespace ignored).
     *
     * @throws RuleDefinitionException
     *             if {@code raw} is null or not one of {@code DATA} / {@code DEFINE} /
     *             {@code LIBRARY}
     */
    public static MetadataLevel parse(String raw)
    {
        if (raw == null)
        {
            throw new RuleDefinitionException("metadata level must be a non-null string literal");
        }
        MetadataLevel level = tryParse(raw);
        if (level == null)
        {
            throw new RuleDefinitionException(
                    "unknown metadata level '" + raw + "' (expected DATA, DEFINE, or LIBRARY)");
        }
        return level;
    }


    /** Non-throwing parse: the level for {@code raw}, or {@code null} if null/unrecognised. */
    public static @org.jspecify.annotations.Nullable MetadataLevel tryParse(
            @org.jspecify.annotations.Nullable String raw)
    {
        if (raw == null)
        {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT))
        {
        case "DATA" -> DATA;
        case "DEFINE" -> DEFINE;
        case "LIBRARY" -> LIBRARY;
        default -> null;
        };
    }

}
