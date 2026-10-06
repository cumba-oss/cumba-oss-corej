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
        // Fix #5: a dotted ** column half is never resolved on the primary's prefix — it is the
        // joined side's; since W5 of PLAN-merged-evaluation-table nothing resolves it per row
        // and a RELREC.** read is a stage-A load error, but the text policy is unchanged.
        assertNull(CheckConditionTransformer.resolveTextWildcard("RELREC.**DECOD", "AE", "AE"));
    }


    @Test
    void dotQualifiedDatasetHalfUsesTheDomainCodePrefix()
    {
        // EC-36: on an APMH primary the variable prefix is "MH" but the SUPP dataset keeps the
        // full domain code — never SUPPMH. ⚑ Re-pinned by PLAN-split-supp-resolution D10: the
        // dataset half SUPP-- is the IG's supplemental family of the code, SQAPMH (SDTMIG-AP;
        // SDTMIG v3.4 sec. 8.4.2), not the never-used SUPPAPMH.
        assertEquals("SQAPMH.QVAL",
                CheckConditionTransformer.resolveTextWildcard("SUPP--.QVAL", "MH", "APMH"));
        assertEquals("SQAPMH.QVAL",
                CheckConditionTransformer.resolveTextWildcard("SQAP--.QVAL", "MH", "APMH"),
                "SQAP-- is a synonym (D10)");
        assertEquals("SUPPAE.QVAL",
                CheckConditionTransformer.resolveTextWildcard("SUPP--.QVAL", "AE", "AE"));
    }


    @Test
    void dotQualifiedHalvesResolveOnTheirOwnPrefixes()
    {
        // SUPP--.--QVAL: dataset half takes the domain code (the supplemental family of APMH,
        // SQAPMH — PLAN-split-supp-resolution D10), column half the variable prefix.
        assertEquals("SQAPMH.MHQVAL",
                CheckConditionTransformer.resolveTextWildcard("SUPP--.--QVAL", "MH", "APMH"));
    }

}
