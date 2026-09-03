package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck.Expectations;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ViolationLocationCheck}: exact-set location matching, value
 * normalization, the truncation guard, and the {@link ViolationLocationCheck#toExpectations}
 * emitter (shared by capture and back-fill).
 */
class ViolationLocationCheckTest
{

    // AE: row 1 = (001,1,Y), row 2 = (001,2,N), row 3 = (002,1,Maybe). AESEQ is Num.
    private static final String DATASET = """
            dataset AE
            col USUBJID type=Char
            col AESEQ   type=Num
            col AESER   type=Char
            ---
            001 | 1 | Y
            001 | 2 | N
            002 | 1 | Maybe
            ---
            """;

    private static RuleTestScenario scn(String aDirectives)
    {
        return RuleTestCdt.parse("#!RuleTest\n#test CORE-1 expect=violation domain=AE\n"
                + aDirectives + "\n" + DATASET, "t");
    }


    private static OverlayDataTable primary(RuleTestScenario aScenario)
    {
        OverlayDataTable t = aScenario.primaryTable();
        if (t == null)
        {
            throw new IllegalStateException("no primary table");
        }
        return t;
    }


    private static Violation row(long aRowIndex)
    {
        return new Violation(aRowIndex, Map.of());
    }

    // ---- no directives -------------------------------------------------------------


    @Test
    void noDirectives_passesRegardless()
    {
        RuleTestScenario s = scn("#note plain");
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s, List.of(row(0), row(1)),
                2, false, primary(s));
        assertTrue(r.pass(), r.detail());
    }

    // ---- count ---------------------------------------------------------------------


    @Test
    void count_match()
    {
        RuleTestScenario s = scn("#expectViolationCount 2");
        assertTrue(ViolationLocationCheck.verify(s, List.of(row(0), row(2)), 2, false, primary(s))
                .pass());
    }


    @Test
    void count_mismatch()
    {
        RuleTestScenario s = scn("#expectViolationCount 1");
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s, List.of(row(0), row(2)),
                2, false, primary(s));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("expected 1") && r.detail().contains("fired 2"), r.detail());
    }

    // ---- single location -----------------------------------------------------------


    @Test
    void positional_hit()
    {
        RuleTestScenario s = scn("#expectViolationAt row=3");
        assertTrue(ViolationLocationCheck.verify(s, List.of(row(2)), 1, false, primary(s)).pass());
    }


    @Test
    void positional_miss()
    {
        RuleTestScenario s = scn("#expectViolationAt row=3");
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s, List.of(row(0)), 1,
                false, primary(s));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("missing") && r.detail().contains("unexpected"), r.detail());
    }


    @Test
    void valuePin_hit_withNumericNormalization()
    {
        // AESEQ is Num: stored 1.0 must match the directive pin AESEQ=1.
        RuleTestScenario s = scn("#expectViolationAt USUBJID=002 AESEQ=1");
        assertTrue(ViolationLocationCheck.verify(s, List.of(row(2)), 1, false, primary(s)).pass());
    }


    @Test
    void valuePin_numeric_acceptsTrailingZero()
    {
        RuleTestScenario s = scn("#expectViolationAt AESEQ=1.0");
        assertTrue(ViolationLocationCheck.verify(s, List.of(row(2)), 1, false, primary(s)).pass());
    }


    @Test
    void valuePin_miss()
    {
        RuleTestScenario s = scn("#expectViolationAt USUBJID=999");
        assertFalse(ViolationLocationCheck.verify(s, List.of(row(2)), 1, false, primary(s)).pass());
    }


    @Test
    void valuePin_stringId_notNumericallyCoerced()
    {
        // USUBJID "002" is a Char cell — a numeric pin "2" must NOT match it (exact-set integrity).
        RuleTestScenario s = scn("#expectViolationAt USUBJID=2");
        assertFalse(ViolationLocationCheck.verify(s, List.of(row(2)), 1, false, primary(s)).pass());
    }


    @Test
    void outputVariablePin_hit_keyNotAColumn()
    {
        RuleTestScenario s = scn("#expectViolationAt variable_name=AEFOO");
        Violation v = new Violation(0, Map.of("variable_name", "AEFOO"));
        assertTrue(ViolationLocationCheck.verify(s, List.of(v), 1, false, primary(s)).pass());
    }


    @Test
    void rowPlusPin_hit()
    {
        RuleTestScenario s = scn("#expectViolationAt row=3 AESER=Maybe");
        assertTrue(ViolationLocationCheck.verify(s, List.of(row(2)), 1, false, primary(s)).pass());
    }

    // ---- exact set -----------------------------------------------------------------


    @Test
    void exactSet_extraObserved_fails()
    {
        RuleTestScenario s = scn("#expectViolationAt row=3");
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s, List.of(row(2), row(0)),
                2, false, primary(s));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("unexpected"), r.detail());
    }


    @Test
    void exactSet_missingExpected_fails()
    {
        RuleTestScenario s = scn("""
                #expectViolationAt row=1
                #expectViolationAt row=3""");
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s, List.of(row(0)), 1,
                false, primary(s));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("missing"), r.detail());
    }


    @Test
    void exactSet_duplicateRow_matchedBijectively()
    {
        RuleTestScenario s = scn("""
                #expectViolationCount 2
                #expectViolationAt row=3
                #expectViolationAt row=3""");
        assertTrue(ViolationLocationCheck.verify(s, List.of(row(2), row(2)), 2, false, primary(s))
                .pass());
    }

    // ---- truncation ----------------------------------------------------------------


    @Test
    void truncation_withAtDirective_fails()
    {
        RuleTestScenario s = scn("#expectViolationAt row=3");
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s, List.of(row(2)), 50,
                true, primary(s));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("truncated"), r.detail());
    }

    // ---- RuleExecutionResult overload ----------------------------------------------


    @Test
    void resultOverload_readsViolationsAndCount()
    {
        RuleTestScenario s = scn("#expectViolationAt row=3");
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-1")
                .violations(List.of(row(2))).status(RuleExecutionStatus.EXECUTED).build();
        assertTrue(ViolationLocationCheck.verify(s, result, primary(s)).pass());
    }

    // ---- toExpectations ------------------------------------------------------------


    @Test
    void toExpectations_valueBased_emitsRowAndIdentityPins()
    {
        Violation v = new Violation(2, Map.of(), "002", "1");
        Expectations e = ViolationLocationCheck.toExpectations(List.of(v), 1, false, true, "AE");
        assertEquals(Integer.valueOf(1), e.count());
        assertEquals(1, e.ats().size());
        ExpectedViolation ev = e.ats().get(0);
        assertEquals(Integer.valueOf(3), ev.getRow());
        assertEquals("002", ev.getConstraints().get("USUBJID"));
        assertEquals("1", ev.getConstraints().get("AESEQ"));
    }


    @Test
    void toExpectations_nonValueBased_pinsOutputVarsSkippingDollar()
    {
        Violation v = new Violation(0, Map.of("variable_name", "AEFOO", "$allowed", "[A, B]"));
        Expectations e = ViolationLocationCheck.toExpectations(List.of(v), 1, false, false, "AE");
        assertEquals(1, e.ats().size());
        ExpectedViolation ev = e.ats().get(0);
        assertNull(ev.getRow());
        assertEquals("AEFOO", ev.getConstraints().get("variable_name"));
        assertFalse(ev.getConstraints().containsKey("$allowed"));
    }


    @Test
    void toExpectations_truncated_countOnly()
    {
        Expectations e = ViolationLocationCheck.toExpectations(List.of(row(2)), 99, true, true,
                "AE");
        assertEquals(Integer.valueOf(99), e.count());
        assertTrue(e.ats().isEmpty());
    }


    @Test
    void toExpectations_countMismatch_fallsBackToCountOnly()
    {
        // Two non-value-based violations but only one yields a pin -> ats can't enumerate the
        // count.
        Violation noPin = new Violation(0, Map.of());
        Violation pinned = new Violation(1, Map.of("variable_name", "X"));
        Expectations e = ViolationLocationCheck.toExpectations(List.of(noPin, pinned), 2, false,
                false, "AE");
        assertEquals(Integer.valueOf(2), e.count());
        assertTrue(e.ats().isEmpty());
    }


    @Test
    void toExpectations_roundTripsThroughChecker()
    {
        // Emitter output, fed back as a scenario's expectations, verifies as a pass.
        RuleTestScenario base = scn("#note plain");
        Violation v = new Violation(2, Map.of(), "002", "1");
        Expectations e = ViolationLocationCheck.toExpectations(List.of(v), 1, false, true, "AE");
        RuleTestScenario withExp = base.toBuilder().expectViolationCount(e.count())
                .clearExpectedViolations().expectedViolations(e.ats()).build();
        assertTrue(
                ViolationLocationCheck.verify(withExp, List.of(v), 1, false, primary(base)).pass());
    }
}
