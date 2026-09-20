package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The shared {@code --} / {@code **} text-substitution policy of {@link CheckConditionTransformer}
 * — the surface {@link ExprPrefixResolver} and {@link RuleSpecialiser} resolve every operand name
 * through.
 *
 * <p>
 * Phase 7d (D121): the class's {@code CheckCondition}-tree half is gone with the operator-leaf
 * model (an expression Check's names are resolved on the {@code Expr}), so what these tests pin is
 * the one remaining text policy, exercised exactly as the live callers exercise it.
 * </p>
 */
class CheckConditionTransformerTest
{
    // ------------------------------------------------------------------ resolveTextWildcard

    @Test
    void textWithoutWildcardResolvesToNull()
    {
        // null means "nothing changed" — the callers keep the original reference.
        assertNull(CheckConditionTransformer.resolveTextWildcard("STUDYID", "AE", "AE"));
    }


    @Test
    void plainDoubleWildcardSubstitutesThePrefix()
    {
        assertEquals("AEDECOD",
                CheckConditionTransformer.resolveTextWildcard("**DECOD", "AE", "AE"));
    }


    @Test
    void plainLeadingWildcardSubstitutes()
    {
        assertEquals("AESTDTC",
                CheckConditionTransformer.resolveTextWildcard("--STDTC", "AE", "AE"));
    }


    @Test
    void dotQualifiedDoubleWildcardColumnHalfIsPreserved()
    {
        // Fix #5: RELREC.**DECOD keeps the ** column half for per-row resolution by
        // RelrecExpandedLookup — pre-resolving would collapse cross-domain relationships.
        assertNull(CheckConditionTransformer.resolveTextWildcard("RELREC.**DECOD", "AE", "AE"));
    }


    @Test
    void dotQualifiedDatasetHalfUsesTheDomainCodePrefix()
    {
        // EC-36: on an APMH primary the variable prefix is "MH" but the SUPP dataset keeps the
        // full domain code — SUPPAPMH, not SUPPMH.
        assertEquals("SUPPAPMH.QVAL",
                CheckConditionTransformer.resolveTextWildcard("SUPP--.QVAL", "MH", "APMH"));
    }


    @Test
    void dotQualifiedHalvesResolveOnTheirOwnPrefixes()
    {
        // SUPP--.--QVAL: dataset half takes the domain code, column half the variable prefix.
        assertEquals("SUPPAPMH.MHQVAL",
                CheckConditionTransformer.resolveTextWildcard("SUPP--.--QVAL", "MH", "APMH"));
    }

}
