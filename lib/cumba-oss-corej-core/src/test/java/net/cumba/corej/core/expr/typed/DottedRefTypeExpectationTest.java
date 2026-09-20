package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * ⚑ TARGET-INVARIANT(null-free-value-channel), {@code PLAN-null-free-value-channel} phase 4: a
 * <b>dotted</b> cross-dataset reference records its type expectation, exactly as a bare one does.
 *
 * <p>
 * ⭐⭐ Owner ruling, 2026-09-18: <i>"a dotted variable should behave like a first-class variable, so
 * if the variable is absent and the expression expects a numeric variable, then use
 * {@code MissingValue.MIS} as default value"</i> and <i>"if a dotted ref is used like
 * {@code DM.AGE > 30} then this should record the expected type to be numeric"</i>. That is
 * {@code D72} — <i>"a merged column behaves in EVERY respect like a primary column"</i> — applied
 * to the dotted form, so the former exclusion of
 * {@link net.cumba.corej.core.expr.OperandKind#DOTTED_REF} <em>was</em> the defect.
 * </p>
 *
 * <p>
 * The second half of this class is the phase's <b>blast-radius pin</b>. Recording an expectation
 * feeds the {@code StageBChecker} type gate, and a dotted name resolved against the PRIMARY
 * dataset's metadata is never found — so admitting one into {@code valueReadColumns()} or
 * {@code equalityPairs()} would file a false {@code ABSENT_COLUMN} or {@code COLUMN_TYPE_MISMATCH}
 * finding for every dotted reference in every rule. Those two sets are asserted to stay bare-only,
 * which is why this phase moves <b>no</b> stage-B finding.
 * </p>
 */
class DottedRefTypeExpectationTest
{

    private static TypeExpectations expectations(String expression)
    {
        Expr parsed = CheckExpressionParser.parse(expression);
        assertTrue(parsed != null, "the parser must yield an expression for: " + expression);
        return TypeExpectations.of(List.of(parsed));
    }

    // ------------------------------------------------------------------
    // The ruling: a dotted operand records, under its FULL name
    // ------------------------------------------------------------------


    @Test
    void anOrderComparisonOverADottedRefRecordsNumericUnderItsFullName()
    {
        TypeExpectations te = expectations("DM.AGE > 30");
        assertEquals(Set.of(TypeExpectations.Expectation.NUMERIC), te.expectationsOf("DM.AGE"),
                "the owner's own example: `DM.AGE > 30` expects a NUMERIC read of DM.AGE");
        assertTrue(te.numericExpected("DM.AGE"));
        assertTrue(te.numericDefaultColumns().contains("DM.AGE"),
                "and it must reach numericDefaultColumns(), the set the engine's not-supplied"
                        + " dotted arms read off the EvaluationContext");
        // ⭐ CONTROL: the bare half of the name records NOTHING. Were the expectation keyed on the
        // column part alone, an unrelated primary AGE column would inherit DM.AGE's expectation
        // and the D76 default of a genuinely absent primary AGE would flip with it.
        assertEquals(Set.of(), te.expectationsOf("AGE"), "keyed on the FULL dotted name, not AGE");
        assertEquals(Set.of(), te.expectationsOf("DM"));
    }


    @Test
    void aStringLiteralEqualityOverADottedRefRecordsCharacter()
    {
        TypeExpectations te = expectations("DM.ARM == \"PLACEBO\"");
        assertEquals(Set.of(TypeExpectations.Expectation.CHARACTER), te.expectationsOf("DM.ARM"));
        assertFalse(te.numericExpected("DM.ARM"),
                "a character expectation must NOT make the absent default MissingValue.MIS");
    }


    @Test
    void aNumericLiteralEqualityAndArithmeticOverADottedRefRecordNumeric()
    {
        assertTrue(expectations("DM.AGE == 42").numericExpected("DM.AGE"));
        assertTrue(expectations("DM.AGE + 1 > 5").numericExpected("DM.AGE"));
    }


    @Test
    void aNumericListMembershipOverADottedRefRecordsNumeric()
    {
        // The membership arm gated on bareColumn() and therefore skipped the dotted LHS entirely;
        // a first-class variable's `X in [1, 2]` records NUMERIC, so a dotted one must too.
        TypeExpectations numeric = expectations("DM.AGEGR in [1, 2]");
        assertTrue(numeric.numericExpected("DM.AGEGR"));
        TypeExpectations character = expectations("DM.AGEGR in [\"1\", \"2\"]");
        assertEquals(Set.of(TypeExpectations.Expectation.CHARACTER),
                character.expectationsOf("DM.AGEGR"));
        assertFalse(character.numericExpected("DM.AGEGR"));
    }


    @Test
    void aRegexSubjectAndADateConversionOverADottedRefRecordTheirKinds()
    {
        assertEquals(Set.of(TypeExpectations.Expectation.CHARACTER),
                expectations("DM.ARM =~ \"^PLA\"").expectationsOf("DM.ARM"));
        TypeExpectations iso = expectations("date(DM.DTHDTC) > date(\"2020-01-01\")");
        assertEquals(Set.of(TypeExpectations.Expectation.ISO_TEXT), iso.expectationsOf("DM.DTHDTC"),
                "date(...) over a dotted ref is an ISO-8601 TEXT read (D55), not a numeric one");
        assertFalse(iso.numericExpected("DM.DTHDTC"),
                "ISO_TEXT counts as \"otherwise\" ⇒ the absent default stays \"\" (D76a)");
    }

    // ------------------------------------------------------------------
    // The blast-radius pin: the two stage-B sets stay BARE-ONLY
    // ------------------------------------------------------------------


    @Test
    void aDottedRefNeverJoinsTheValueReadColumnsCandidateSet()
    {
        TypeExpectations te = expectations("DM.AGE > 30 and AGE > 30");
        assertEquals(Set.of("AGE"), te.valueReadColumns(),
                "⛔ valueReadColumns() is the PRIMARY dataset's absent-column fold candidate set."
                        + " StageBChecker probes every member with meta.getColumnIndex(...) against"
                        + " the primary metadata and files a < 0 answer as ABSENT_COLUMN — a dotted"
                        + " name is never a primary column name, so admitting one would file a"
                        + " FALSE absent-column finding for every dotted reference in every rule");
        // ⭐ CONTROL: the set is not empty for an unrelated reason — the bare sibling IS in it, so
        // this assertion distinguishes "dotted excluded" from "nothing recorded at all".
        assertTrue(te.valueReadColumns().contains("AGE"));
        assertTrue(te.numericExpected("DM.AGE"), "…while the dotted expectation IS recorded");
    }


    @Test
    void aDottedSideNeverFormsAnEqualityPair()
    {
        assertEquals(List.of(), expectations("DM.AGE == ADSL.AGE").equalityPairs(),
                "⛔ an equality PAIR is checked by reading both sides' declared kinds off the"
                        + " primary dataset's metadata, which holds no entry for a joined column");
        assertEquals(List.of(), expectations("DM.AGE == AGE").equalityPairs(),
                "…nor does a mixed pair: the dotted side is still unresolvable at that seam");
        // ⭐ CONTROL: a bare-vs-bare equality DOES form a pair, so the two assertions above are
        // not passing because equalityPairs() stopped working.
        assertEquals(List.of(new TypeExpectations.EqualityPair("AGE", "AGEGR")),
                expectations("AGE == AGEGR").equalityPairs());
    }


    @Test
    void everyDottedExpectationKeyCarriesItsDotSoItCannotCollideWithABareName()
    {
        // ⭐ The disjointness the four existing ctx.getNumericExpectedColumns().contains(<bare
        // name>) readers rely on: OperandClassifier's DOTTED pattern always yields one '.', and
        // BroadcastFold.isFoldableColumnReference rejects every bare name containing one. So
        // enlarging that set with dotted entries cannot change any bare membership answer.
        TypeExpectations te = expectations("DM.AGE > 30 and ADSL.AGE > 30 and AGE > 30");
        Set<String> numeric = te.numericDefaultColumns();
        assertEquals(Set.of("DM.AGE", "ADSL.AGE", "AGE"), numeric);
        for (String name : numeric)
        {
            boolean dotted = name.indexOf('.') >= 0;
            assertEquals(dotted,
                    !net.cumba.corej.core.expr.eval.BroadcastFold.isFoldableColumnReference(name),
                    "a key is dotted iff isFoldableColumnReference rejects it: " + name);
        }
    }
}
