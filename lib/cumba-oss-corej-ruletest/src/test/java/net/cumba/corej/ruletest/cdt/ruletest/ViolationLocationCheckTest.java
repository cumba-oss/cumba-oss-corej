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

    /**
     * A dataset that really <em>has</em> a column named {@code $dataset_size}, holding the value a
     * {@code "$dataset_size"} pin declares. Without such a fixture a test cannot tell the
     * payload-only fix from its absence: the pre-fix table fallthrough would look up a column that
     * is not there, miss, and fail for the wrong reason.
     */
    private static final String DOLLAR_COLUMN_DATASET = """
            dataset AE
            col USUBJID      type=Char
            col $dataset_size type=Num
            ---
            001 | 6000000000
            ---
            """;

    private static RuleTestScenario scnWithDollarColumn(String aDirectives)
    {
        return RuleTestCdt.parse("#!RuleTest\n#test CORE-1 expect=violation domain=AE\n"
                + aDirectives + "\n" + DOLLAR_COLUMN_DATASET, "t");
    }


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


    /**
     * &#9940; The emitter must carry {@code $}-prefixed operation results. It used to skip them,
     * and this test used to assert that skip &mdash; pinning the bug as if it were the contract.
     * See {@link ViolationLocationCheck#toExpectations} for why skipping them deletes pins.
     */
    @Test
    void toExpectations_nonValueBased_carriesDollarPins()
    {
        Violation v = new Violation(0, Map.of("variable_name", "AEFOO", "$allowed", "[A, B]"));
        Expectations e = ViolationLocationCheck.toExpectations(List.of(v), 1, false, false, "AE");
        assertEquals(1, e.ats().size());
        ExpectedViolation ev = e.ats().get(0);
        assertNull(ev.getRow());
        assertEquals("AEFOO", ev.getConstraints().get("variable_name"));
        assertEquals("[A, B]", ev.getConstraints().get("$allowed"),
                "a $ operation result is payload-only — dropping it here ERASES the pin");
    }


    /**
     * &#9940; The value-based branch pins by {@code row=} plus record identity and never consults
     * the payload &mdash; so it, too, dropped every {@code $} pin. 16 of the 23 {@code $} pins in
     * the corpus carry {@code row=}, i.e. the majority of them live on this branch.
     */
    @Test
    void toExpectations_valueBased_carriesDollarPinsBesideTheIdentityPins()
    {
        Violation v = new Violation(2, Map.of("$dataset_size", "6000000000"), "002", "1");
        Expectations e = ViolationLocationCheck.toExpectations(List.of(v), 1, false, true, "AE");
        assertEquals(1, e.ats().size());
        ExpectedViolation ev = e.ats().get(0);
        assertEquals(Integer.valueOf(3), ev.getRow());
        assertEquals("002", ev.getConstraints().get("USUBJID"));
        assertEquals("1", ev.getConstraints().get("AESEQ"));
        assertEquals("6000000000", ev.getConstraints().get("$dataset_size"),
                "a row= pin does not carry the operation result — only this entry does");
    }


    /**
     * The emitted {@code $} pin is honoured by the checker, and still <em>discriminates</em>: an
     * emitted-but-inert pin would be an erased pin with extra steps.
     */
    @Test
    void toExpectations_dollarPin_roundTripsThroughChecker()
    {
        RuleTestScenario base = scn("#note plain");
        Violation v = new Violation(2, Map.of("$dataset_size", "6000000000"), "002", "1");
        Expectations e = ViolationLocationCheck.toExpectations(List.of(v), 1, false, true, "AE");
        RuleTestScenario withExp = base.toBuilder().expectViolationCount(e.count())
                .clearExpectedViolations().expectedViolations(e.ats()).build();
        assertTrue(
                ViolationLocationCheck.verify(withExp, List.of(v), 1, false, primary(base)).pass());

        // The teeth: the same violation without the operation result must NOT satisfy the pin.
        Violation noPayload = new Violation(2, Map.of(), "002", "1");
        assertFalse(
                ViolationLocationCheck.verify(withExp, List.of(noPayload), 1, false, primary(base))
                        .pass(),
                "an emitted $ pin that matches anything is an erased pin with extra steps");
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

    // ---- $-prefixed pins are payload-only -------------------------------------------


    /**
     * &#9940;&#9940; A {@code $} pin must never be resolved against a table column. The engine's
     * operation results are payload-only; when the engine stops projecting one, the pre-fix
     * fallthrough looked up a <em>column of that name</em> instead and the check passed in silence.
     */
    @Test
    void dollarPin_absentFromPayload_failsLoudly_insteadOfReadingASameNamedColumn()
    {
        RuleTestScenario s = scnWithDollarColumn(
                "#expectViolationAt row=1 \"$dataset_size\"=6000000000");
        // Non-vacuity control: the same-named column must really exist and hold the pinned value,
        // or a red here would prove nothing (a missing column misses for the wrong reason).
        assertTrue(primary(s).getMetaData().getColumnIndex("$dataset_size") >= 0,
                "fixture must HAVE the same-named column");
        assertEquals(Double.valueOf(6.0e9), primary(s).getDataValue(0, 1).getValue(),
                "fixture column must hold exactly the pinned value");

        // The engine projected nothing: an empty payload.
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s,
                List.of(new Violation(0, Map.of())), 1, false, primary(s));

        assertFalse(r.pass(), "a $ pin no violation carries must not be satisfied by a column");
        assertTrue(r.detail().contains("$dataset_size"), r.detail());
        assertTrue(r.detail().contains("payload-only"), r.detail());
    }


    /** The same pin, carried in the payload, still matches — the fix narrows nothing legitimate. */
    @Test
    void dollarPin_presentInPayload_stillMatches()
    {
        RuleTestScenario s = scnWithDollarColumn(
                "#expectViolationAt row=1 \"$dataset_size\"=6000000000");
        Violation v = new Violation(0, Map.of("$dataset_size", "6000000000"));
        assertTrue(ViolationLocationCheck.verify(s, List.of(v), 1, false, primary(s)).pass());
    }


    /**
     * The payload lookup is case-SENSITIVE while {@code DataTableMeta.getColumnIndex} is
     * case-INsensitive by default — so a mis-cased key used to change channel silently. It must now
     * fail loudly instead.
     */
    @Test
    void dollarPin_misCasedKey_failsLoudly_ratherThanChangingChannel()
    {
        RuleTestScenario s = scnWithDollarColumn(
                "#expectViolationAt row=1 \"$DATASET_SIZE\"=6000000000");
        Violation v = new Violation(0, Map.of("$dataset_size", "6000000000"));
        ViolationLocationCheck.Result r = ViolationLocationCheck.verify(s, List.of(v), 1, false,
                primary(s));
        assertFalse(r.pass(), r.detail());
        assertTrue(r.detail().contains("$DATASET_SIZE"), r.detail());
    }


    /**
     * &#9940; The non-{@code $} fallback to the table cell is RELIED ON and must be untouched: a
     * key absent from the payload is still resolved against the column of that name.
     */
    @Test
    void nonDollarPin_absentFromPayload_stillFallsBackToTheTableCell()
    {
        RuleTestScenario s = scn("#expectViolationAt row=3 AESER=Maybe");
        // A non-empty payload that does NOT contain AESER, so the table arm is the one under test.
        Violation v = new Violation(2, Map.of("variable_name", "AEFOO"));
        assertTrue(ViolationLocationCheck.verify(s, List.of(v), 1, false, primary(s)).pass(),
                "AESER must still resolve from the primary table at the fired row");
    }
}
