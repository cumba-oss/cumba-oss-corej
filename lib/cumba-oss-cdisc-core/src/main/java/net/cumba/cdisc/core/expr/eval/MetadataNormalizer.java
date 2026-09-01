package net.cumba.cdisc.core.expr.eval;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Normalizes raw metadata attribute values to a canonical vocabulary so a comparison across levels
 * (DATA / DEFINE / LIBRARY) is meaningful (decision D5). Each {@link Normalization} mode is the
 * rule attached to a {@link MetadataAttribute}.
 *
 * <p>
 * All methods are pure. An empty (or whitespace-only) input normalizes to {@code null} (treated as
 * <em>missing</em> by the evaluator — decision D4); a non-empty value that does not match a known
 * token is returned trimmed but otherwise verbatim, so an unexpected vocabulary surfaces rather
 * than being silently mis-mapped.
 * </p>
 */
public final class MetadataNormalizer
{

    /** The per-attribute normalization rule. */
    public enum Normalization
    {

        /** Returned trimmed, otherwise verbatim (labels, codelists, names). */
        RAW,
        /**
         * Numeric-valued attribute (length, ordinal): trimmed string; the engine compares
         * numerically.
         */
        NUMERIC,
        /** Data type folded to {@code Char} / {@code Num}. */
        TYPE,
        /** Core folded to {@code Req} / {@code Exp} / {@code Perm}. */
        CORE,
        /** Mandatory folded to {@code Yes} / {@code No}. */
        MANDATORY,
        /** Role trimmed and title-cased. */
        ROLE,
        /**
         * Boolean folded to lower-case {@code true} / {@code false} so the accessor compares
         * correctly against a {@code true} / {@code false} literal (which stringifies to the same
         * lower-case token via {@code Boolean.toString}; see
         * {@code ScalarSemantics.equalsNumericAware}). A missing / unknown value normalizes to
         * {@code null} (treated as missing — D4).
         */
        BOOLEAN
    }

    private MetadataNormalizer()
    {
    }


    /**
     * Normalizes {@code value} under {@code mode}. A null / empty / whitespace-only input yields
     * {@code null} (missing).
     */
    public static @Nullable String normalize(Normalization mode, @Nullable String value)
    {
        if (value == null)
        {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty())
        {
            return null;
        }
        return switch (mode)
        {
        case RAW, NUMERIC -> v;
        case TYPE -> normalizeType(v);
        case CORE -> normalizeCore(v);
        case MANDATORY -> normalizeMandatory(v);
        case ROLE -> titleCase(v);
        case BOOLEAN -> normalizeBoolean(v);
        };
    }


    private static @Nullable String normalizeBoolean(String v)
    {
        return switch (v.toLowerCase(Locale.ROOT))
        {
        case "true", "yes", "y", "1" -> "true";
        case "false", "no", "n", "0" -> "false";
        default -> null;
        };
    }


    private static String normalizeType(String v)
    {
        return switch (v.toLowerCase(Locale.ROOT))
        {
        case "char", "text", "string" -> "Char";
        case "num", "integer", "float", "double", "decimal" -> "Num";
        // ISO-8601 date/time families are stored as character columns in CDISC tabulations.
        case "date", "datetime", "time", "partialdate", "partialtime", "partialdatetime", "incompletedatetime", "durationdatetime" -> "Char";
        default -> v;
        };
    }


    private static String normalizeCore(String v)
    {
        return switch (v.toLowerCase(Locale.ROOT))
        {
        case "req", "required" -> "Req";
        case "exp", "expected" -> "Exp";
        case "perm", "permissible" -> "Perm";
        default -> v;
        };
    }


    private static String normalizeMandatory(String v)
    {
        return switch (v.toLowerCase(Locale.ROOT))
        {
        case "yes", "y", "true" -> "Yes";
        case "no", "n", "false" -> "No";
        default -> v;
        };
    }


    /**
     * Capitalizes the first letter of each whitespace-separated word and lower-cases the rest,
     * collapsing runs of whitespace to a single space and trimming the ends. Implemented by a
     * character scan (not {@code String.split}, which Error Prone flags for surprising behavior).
     */
    private static String titleCase(String v)
    {
        StringBuilder sb = new StringBuilder(v.length());
        boolean atWordStart = true;
        boolean pendingSpace = false;
        for (int i = 0; i < v.length(); i++)
        {
            char c = v.charAt(i);
            if (Character.isWhitespace(c))
            {
                atWordStart = true;
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace)
            {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(atWordStart ? Character.toUpperCase(c) : Character.toLowerCase(c));
            atWordStart = false;
        }
        return sb.toString();
    }

}
