package net.cumba.corej.core.exec;

import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code --} / {@code **} domain-prefix wildcards in textual operand names.
 * <p>
 * For example, when the domain prefix is {@code "AE"}, the name {@code "--STDTC"} is resolved to
 * {@code "AESTDTC"}. A dot-qualified {@code **} such as {@code "RELREC.**DECOD"} is deliberately
 * PRESERVED (Fix #5) for per-row resolution by {@code RelrecExpandedLookup}; only the dataset half
 * of such a reference is resolved here.
 * </p>
 * <p>
 * ⭐ Phase 7d of {@code PLAN-typed-expression-engine} (D121): the
 * {@link net.cumba.corej.core.model.CheckCondition}-tree half of this class is gone with the
 * operator-leaf model. An expression Check's wildcards are resolved on the {@code Expr} by
 * {@link ExprPrefixResolver}, which delegates the per-name policy to {@link #resolveTextWildcard} —
 * the one shared text policy this class still owns, also read by {@link RuleSpecialiser} for name
 * lists. ⚑ Its bare-name sibling {@code resolveWildcard} went with 7d as well (terminal review L2):
 * it lost both production callers there and survived only on its own unit tests, while this javadoc
 * still named an {@code OperationExecutor} caller it did not have.
 * </p>
 */
public final class CheckConditionTransformer
{

    private static final String WILDCARD = "--";

    private static final String DOUBLE_WILDCARD = "**";

    private CheckConditionTransformer()
    {
    }


    /**
     * Resolves the wildcards in one textual operand, or returns {@code null} when nothing changes.
     * Shared by the scalar and array paths so the same string can never resolve two different ways
     * depending on whether it sits in {@code value} or inside {@code value[]} (EC-36 — the array
     * branch previously had its own, narrower copy that left dot-qualified and non-dot {@code **}
     * elements untouched).
     */
    static @Nullable String resolveTextWildcard(String text, String prefix, String datasetPrefix)
    {
        // A dot-qualified reference has TWO independent halves: the dataset half is an identity and
        // keeps the CDISC domain code, the column half is a variable name. Resolving each on its
        // own prefix — rather than picking one and `replace`-ing the whole string — is what makes
        // `SUPP--.--QVAL` become `SUPPAPMH.MHQVAL` instead of `SUPPAPMH.APMHQVAL`.
        int dot = text.indexOf('.');
        if (dot >= 0 && (text.contains(WILDCARD) || text.contains(DOUBLE_WILDCARD)))
        {
            String dsHalf = text.substring(0, dot);
            String colHalf = text.substring(dot + 1);
            String newDs = dsHalf.replace(WILDCARD, datasetPrefix).replace(DOUBLE_WILDCARD,
                    datasetPrefix);
            // Fix #5: a `**` in the COLUMN half is deferred to RelrecExpandedLookup, which resolves
            // it per row against the RELREC-paired parent — a rule-prep substitution would collapse
            // cross-domain relationships into one. A `--` there is an ordinary variable name.
            String newCol = colHalf.contains(DOUBLE_WILDCARD) ? colHalf
                    : colHalf.replace(WILDCARD, prefix);
            return dsHalf.equals(newDs) && colHalf.equals(newCol) ? null : newDs + "." + newCol;
        }
        if (text.contains(DOUBLE_WILDCARD))
        {
            return text.replace(DOUBLE_WILDCARD, prefix);
        }
        if (text.startsWith(WILDCARD))
        {
            return prefix + text.substring(WILDCARD.length());
        }
        return null;
    }

}
