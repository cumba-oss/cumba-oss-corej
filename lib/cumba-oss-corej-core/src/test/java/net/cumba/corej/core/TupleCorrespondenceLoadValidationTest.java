package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * {@code RulePackageLoader.validateTupleCorrespondence} — §4b of
 * {@code plans/PLAN-membership-as-equality.md}, owner rulings Q1 (arity mismatch is a load error)
 * and Q4 (*"Go with (a), reject permutations"*, 2026-09-21).
 *
 * <p>
 * ⚠⚠ <b>Both arms are asserted, and the ACCEPTING arm is the one that matters here.</b> The
 * corpus's normal use of composite membership pairs one dataset's columns against another's
 * <em>differently-named</em> pair — {@code CDISC-SEND-0333} probes {@code (PPNOMDY, PPTPTREF)}
 * against {@code (PCNOMDY, PCTPTREF)} — so a guard written as name equality would red a correct
 * rule. {@link #legalCrossPairingLoads()} is that rule's shape, and it is the control that keeps
 * the guard narrow.
 * </p>
 *
 * <p>
 * ⭐ <b>Reachability is the second thing proven here.</b> All 8 shipped sites bind the reference set
 * through a {@code $}-variable, never an inline {@code distinct(...)}, so a guard that only read an
 * inline call would stand down on every one of them and be vacuous. Every rejecting case below goes
 * through a {@code Bindings:} entry, which is the shipped shape.
 * </p>
 */
class TupleCorrespondenceLoadValidationTest
{

    private static String rule(String checkExpression, String bindingExpression)
    {
        return "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Bindings\":[{\"name\":\"$set\"," + "\"expression\":\""
                + bindingExpression.replace("\"", "\\\"") + "\"}]," + "\"Check\":{\"expression\":\""
                + checkExpression.replace("\"", "\\\"") + "\"}}}}";
    }


    private static Rule load(String checkExpression, String bindingExpression) throws IOException
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString(rule(checkExpression, bindingExpression));
        Rule loaded = pkg.getRules().get("X-1");
        assertNotNull(loaded, "fixture did not load");
        return loaded;
    }


    private static String rejected(String checkExpression, String bindingExpression)
        throws IOException
    {
        Rule rule = load(checkExpression, bindingExpression);
        String error = rule.getLoadError();
        assertNotNull(error, "must be a LOAD error: " + checkExpression);
        assertNull(rule.getCheckExpr(), "a rejected rule must not compile: " + checkExpression);
        return error;
    }


    private static void accepted(String checkExpression, String bindingExpression)
        throws IOException
    {
        Rule rule = load(checkExpression, bindingExpression);
        assertNull(rule.getLoadError(),
                "must load cleanly: " + checkExpression + " -> " + rule.getLoadError());
    }


    /**
     * The shape {@code CDISC-SEND-0333} and {@code PMDA-SD1143} ship: same arity, deliberately
     * different column names. ⛔ Must load — this is the feature working, not a slip.
     */
    @Test
    void legalCrossPairingLoads() throws IOException
    {
        accepted("tuple(PPNOMDY, PPTPTREF) not in $set",
                "distinct([PCNOMDY, PCTPTREF], domain=\"PC\")");
        accepted("tuple(USUBJID, AESEQ) not in $set",
                "distinct([USUBJID, IDVARVAL], domain=\"SUPPAE\")");
    }


    /** Identical columns in the identical order — the ordinary case. */
    @Test
    void identicalColumnsInOrderLoad() throws IOException
    {
        accepted("tuple(USUBJID, VISIT) not in $set", "distinct([USUBJID, VISIT], domain=\"SV\")");
    }


    /**
     * Q4 (a): the same multiset of names in a different order can never match, because the
     * comparison is positional. The corpus carries ZERO of these, so this is a guard against a
     * shape the engine could not previously refuse — latent, not live.
     */
    @Test
    void permutationIsALoadError() throws IOException
    {
        String error = rejected("tuple(ARMCD, ARM) not in $set",
                "distinct([ARM, ARMCD], domain=\"TA\")");
        assertTrue(error.contains("different order"), "the message must name the defect: " + error);
    }


    /** Q1 property 4: an arity mismatch is a load error, never a silent {@code false}. */
    @Test
    void arityMismatchIsALoadError() throws IOException
    {
        String error = rejected("tuple(USUBJID, VISIT, VISITNUM) not in $set",
                "distinct([USUBJID, VISIT], domain=\"SV\")");
        assertTrue(error.contains("arities must match"),
                "the message must name the defect: " + error);
    }


    /** The {@code in} spelling takes the identical guard — the defect is not polarity-specific. */
    @Test
    void positiveMembershipTakesTheSameGuard() throws IOException
    {
        assertTrue(rejected("tuple(ARMCD, ARM) in $set", "distinct([ARM, ARMCD], domain=\"TA\")")
                .contains("different order"));
    }


    /**
     * The partiality contract: where the reference set is not a statically-readable list literal,
     * the guard stands down rather than guessing. ⚠ Here the arities would even differ (2 vs a
     * scalar target), and it must STILL load — reporting known-vs-known conflicts only is what
     * keeps the guard from redding a rule it cannot actually read.
     */
    @Test
    void anUnreadableReferenceSetStandsDown() throws IOException
    {
        accepted("tuple(USUBJID, VISIT) not in $set", "distinct(USUBJID, domain=\"SV\")");
    }


    /**
     * A computed probe argument is not a plain column reference, so the probe side is not
     * statically known either. Same contract, other side.
     */
    @Test
    void anUnreadableProbeStandsDown() throws IOException
    {
        accepted("tuple(upper(ARMCD), ARM) not in $set", "distinct([ARM, ARMCD], domain=\"TA\")");
    }


    /**
     * ⛔⛔ <b>Review round 1, finding 6 — the gap that mattered most.</b> Every other fixture here is
     * a bare top-level {@code tuple(…) not in $set}, yet <b>all 8 shipped sites are nested inside
     * an {@code and}</b>: {@code not empty(ARM) and not empty(ARMCD) and tuple(ARMCD, ARM)
     * not in $ta_arm_pairs}. The {@code Expr.And} descent every real rule depends on was exercised
     * by nothing — so the guard could have stopped descending and this suite would have stayed
     * green while going vacuous on the entire corpus.
     */
    @Test
    void theGuardDescendsIntoAConjunction() throws IOException
    {
        String error = rejected(
                "not empty(ARM) and not empty(ARMCD) and tuple(ARMCD, ARM) not in $set",
                "distinct([ARM, ARMCD], domain=\"TA\")");
        assertTrue(error.contains("different order"), error);
    }


    /** The same descent through {@code or} and {@code not}, the other two composite shapes. */
    @Test
    void theGuardDescendsIntoDisjunctionAndNegation() throws IOException
    {
        assertTrue(rejected("empty(ARM) or tuple(ARMCD, ARM) not in $set",
                "distinct([ARM, ARMCD], domain=\"TA\")").contains("different order"));
        assertTrue(
                rejected("not (tuple(ARMCD, ARM) in $set)", "distinct([ARM, ARMCD], domain=\"TA\")")
                        .contains("different order"));
    }


    /**
     * ⭐ <b>Why the guard does NOT fold case, pinned at the layer that makes folding impossible.</b>
     * This test was written to exercise a case-folding rejection and it failed at the PARSER, which
     * refuses a lowercase operand outright — so the fold the guard carried could never have fired,
     * and it has been removed. The assertion now pins the constraint the guard rests on: if the
     * parser ever accepts lowercase column names, this test reds and the guard needs its fold back.
     */
    @Test
    void aLowercaseColumnNameCannotReachTheGuardAtAll()
    {
        JsonMappingException thrown = assertThrows(JsonMappingException.class,
                () -> load("tuple(arm, ARMCD) not in $set",
                        "distinct([ARMCD, ARM], domain=\"TA\")"));
        assertTrue(thrown.getMessage().contains("column names are upper-case"),
                "the guard's no-fold decision rests on this parser rule: " + thrown.getMessage());
    }


    /**
     * The INLINE right-hand side — {@code tupleSetColumns}' {@code listTargetColumns} fallback,
     * which no other case reaches because all 8 shipped sites bind through a {@code $}-variable. ⚠
     * Dead code in a guard is how the guard stops working without anything going red.
     */
    @Test
    void anInlineListTargetIsReadWithoutABinding() throws IOException
    {
        String json = "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":\""
                + "tuple(ARMCD, ARM) not in distinct([ARM, ARMCD], domain=\\\"TA\\\")" + "\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("X-1");
        assertNotNull(rule, "fixture did not load");
        String error = rule.getLoadError();
        assertNotNull(error, "an inline list target must be read too");
        assertTrue(error.contains("different order"), error);
    }
}
