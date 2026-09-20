package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.OperandSubstitutor;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.ast.Expr.BinOp;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * ⚑ TARGET-INVARIANT(null-free-value-channel), {@code PLAN-null-free-value-channel} phase 5: a
 * <b>dotted</b> operand whose dataset is <b>not supplied</b> answers the RULE-EXPECTED default —
 * numeric-expected ⇒ {@link MissingValue#MIS}, otherwise the present empty string {@code ""}.
 *
 * <p>
 * ⭐⭐ Owner ruling, 2026-09-18: <i>"a dotted variable should behave like a first-class variable, so
 * if the variable is absent and the expression expects a numeric variable, then use
 * {@code MissingValue.MIS} as default value"</i> — {@code D72} applied to the dotted form. Both
 * arms of the shape are covered here because they must not disagree:
 * </p>
 * <ul>
 * <li><b>{@code ExprCompiler.dottedVector}</b> — the AUTHORED form, {@code DM.AGE > 30}. This is
 * the ruling's own example's site, and it answered an unconditional all-{@code ""}.</li>
 * <li><b>{@code ExprCompiler.substitutedScalarCell}</b> — the {@code ${…}} form. It answered an
 * unconditional computed {@code MIS}, i.e. the two arms were wrong in OPPOSITE directions.</li>
 * </ul>
 *
 * <p>
 * ⚠⚠ <b>Every assertion below reads the typed value channel, never a blankness predicate.</b>
 * {@code Vector.isMissing} is the F3 fold and folds {@code ""} together with a {@link MissingValue}
 * on purpose, so an assertion routed through it answers the same thing for both defaults and proves
 * nothing. {@link TypedValue#missing()} and {@link TypedValue#resolved()} are what separate them.
 * </p>
 *
 * <p>
 * ⛔ The numeric arm is the change; the char arm is unchanged, and that is what keeps the D96a
 * absent-vs-blank regression closed — D96a was a CHARACTER problem, and {@code MIS} sorting below
 * every value under phase 6c's {@code D34 #5} order arm is correct for a numeric read.
 * </p>
 */
class DottedNotSuppliedDefaultTest
{

    /**
     * A one-row table carrying no dotted dataset and no join, so every dotted name is unsupplied.
     */
    private static IDataTable primary()
    {
        return MockTable.of().col("USUBJID", "S1").build();
    }


    private static EvaluationContext ctx(Set<String> numericExpected)
    {
        return EvaluationContext.builder().table(primary()).numericExpectedColumns(numericExpected)
                .build();
    }


    /**
     * {@code dottedVector} is private static; reached the way the sibling ratchets reach theirs.
     */
    private static Vector dottedVector(EvaluationContext ctx, String name)
        throws ReflectiveOperationException
    {
        Method m = ExprCompiler.class.getDeclaredMethod("dottedVector", EvaluationContext.class,
                int.class, String.class);
        m.setAccessible(true);
        Vector v = (Vector) m.invoke(null, ctx, 1, name);
        assertNotNull(v, "⚑ the value channel never answers null — that IS the rule");
        return v;
    }


    /** {@code substitutedScalarCell}, the {@code ${…}} arm of the very same shape. */
    private static IDataValue substitutedCell(EvaluationContext ctx, String operand)
        throws ReflectiveOperationException
    {
        Method m = ExprCompiler.class.getDeclaredMethod("substitutedScalarCell",
                OperandSubstitutor.Scalar.class, EvaluationContext.class, long.class, boolean.class,
                boolean.class);
        m.setAccessible(true);
        OperandSubstitutor.Scalar scalar = new OperandSubstitutor.Scalar(null,
                List.of(new OperandSubstitutor.Literal(operand)));
        IDataValue v = (IDataValue) m.invoke(null, scalar, ctx, 0L, false, false);
        assertNotNull(v, "⚑ the value channel never answers null — that IS the rule");
        return v;
    }

    // ------------------------------------------------------------------
    // The authored form — the ruling's own example's site
    // ------------------------------------------------------------------


    @Test
    void anUnsuppliedDottedRefTheRuleNumericExpectsIsMis() throws ReflectiveOperationException
    {
        TypedValue v = dottedVector(ctx(Set.of("DM.AGE")), "DM.AGE").value(0);

        assertTrue(v.isMissing(),
                "⛔ `DM.AGE > 30` with DM not joined: the ruling's own example. A first-class"
                        + " variable the rule numeric-expects is all-MissingValue.MIS when absent"
                        + " (D34 #4), and a dotted one behaves in EVERY respect like it (D72)");
        assertSame(MissingValue.MIS, v.missing(), "the constant is MissingValue.MIS");
        // ⭐ CONTROL that names the defect: the value this arm used to answer unconditionally.
        assertEquals("", ConstVector.of("").value(0).resolved(),
                "CONTROL: the all-\"\" this used to answer is a PRESENT empty string, so the"
                        + " assertions above would both fail against it — the two are"
                        + " distinguishable on the typed channel");
    }


    @Test
    void anUnsuppliedDottedRefWithNoNumericExpectationStaysThePresentEmptyString()
        throws ReflectiveOperationException
    {
        TypedValue v = dottedVector(ctx(Set.of()), "DM.ARM").value(0);

        assertFalse(v.isMissing(),
                "⛔ D96a STAYS CLOSED: with no numeric expectation the default is the present"
                        + " empty string, NOT a computed MIS that the D34 #5 order arm would sort"
                        + " below every value");
        assertEquals("", v.resolved(), "D34 #3 / D76a's \"otherwise char\"");
        // ⭐ CONTROL: a numeric expectation for a DIFFERENT dotted name must not reach DM.ARM —
        // the read is keyed on the full name, so one rule's numeric dotted ref cannot retype
        // another's.
        assertFalse(dottedVector(ctx(Set.of("DM.AGE")), "DM.ARM").value(0).isMissing(),
                "CONTROL: DM.AGE's expectation is DM.AGE's alone");
    }


    @Test
    void theTwoDefaultsAreDistinguishableAndDisagree() throws ReflectiveOperationException
    {
        // ⭐ The non-vacuity control for the whole class: the two arms must answer DIFFERENT
        // things for the same name under different expectations. Before phase 5 the authored form
        // answered "" for both, so every assertion that held for one held for the other.
        TypedValue numeric = dottedVector(ctx(Set.of("DM.AGE")), "DM.AGE").value(0);
        TypedValue character = dottedVector(ctx(Set.of()), "DM.AGE").value(0);
        assertTrue(numeric.isMissing() != character.isMissing(),
                "CONTROL: the expectation must CHANGE the answer — one unconditional default for"
                        + " both was the defect");
    }


    @Test
    void theVerdictMovesEndToEndForTheNumericArmOnly()
    {
        // The plan's named movement, on the authored form: `DM.AGE == ""` fires on every row while
        // the dotted default is "" and on none once the rule numeric-expects it.
        Expr expr = new Expr.Binary(BinOp.EQ, new Expr.Ref("DM.AGE", OperandKind.DOTTED_REF),
                new Expr.Lit(Expr.LitKind.STRING, ""));
        assertTrue(NativeExprEvaluator.isSupported(expr), "the operand must compile natively");

        BitSet asChar = NativeExprEvaluator.evaluate(expr, ctx(Set.of()));
        assertEquals("{0}", asChar.toString(),
                "an unsupplied dotted ref with no numeric expectation IS \"\" on every row");
        BitSet asNumeric = NativeExprEvaluator.evaluate(expr, ctx(Set.of("DM.AGE")));
        assertEquals("{}", asNumeric.toString(),
                "⛔ and a numeric-expected one is MissingValue.MIS, which is NOT \"\" — the verdict"
                        + " this phase moves, measured through the compiler, not the private arm");
    }

    // ------------------------------------------------------------------
    // The ${…} form — wrong in the OPPOSITE direction before this phase
    // ------------------------------------------------------------------


    @Test
    void anUnsuppliedSubstitutedDottedOperandTakesTheSameTwoDefaults()
        throws ReflectiveOperationException
    {
        IDataValue numeric = substitutedCell(ctx(Set.of("DM.AGE")), "DM.AGE");
        assertTrue(numeric.isMissingOrInvalid(), "numeric-expected ⇒ MissingValue.MIS (D34 #4)");
        assertSame(MissingValue.MIS, numeric.getValue());

        IDataValue character = substitutedCell(ctx(Set.of()), "DM.ARM");
        assertFalse(character.isMissingOrInvalid(),
                "⛔ otherwise ⇒ a PRESENT empty string. This arm answered a computed MIS"
                        + " UNCONDITIONALLY, so the ${…} and the authored form of one shape were"
                        + " wrong in OPPOSITE directions");
        assertEquals("", character.getValue(), "D34 #3");
        // ⭐ CONTROL: the value this arm used to answer, so the assertion above is a real change.
        assertSame(MissingValue.MIS, ScalarSemantics.computedMissing().getValue(),
                "CONTROL: computedMissing() is MIS — had this arm kept minting it unconditionally,"
                        + " isMissingOrInvalid() would read true and the assertion would fail");
    }


    @Test
    void aTemplatedDottedNameTakesTheOtherwiseBranchBecauseNoStaticWalkCouldSeeIt()
        throws ReflectiveOperationException
    {
        // ⚠ `ADSL.AP${…}SDT` resolves its column per row, so no expectation can be recorded for
        // it and the "otherwise" branch applies — the same "otherwise" a first-class variable
        // with no expectation gets, and the honest answer for an operand whose identity is not
        // statically known. Pinned so a later reader does not mistake it for a missed case.
        EvaluationContext withNumericSibling = ctx(Set.of("ADSL.APSDT"));
        IDataValue v = substitutedCell(withNumericSibling, "ADSL.AP01SDT");
        assertFalse(v.isMissingOrInvalid(), "no recorded expectation ⇒ the present empty string");
        assertEquals("", v.getValue());
    }

    // ------------------------------------------------------------------
    // The two siblings — the first one is now REACHED (§9c, 2026-09-18)
    // ------------------------------------------------------------------


    @Test
    void aSuppliedJoinStillRoutesThroughTheLookupAndIsUnaffected()
        throws ReflectiveOperationException
    {
        // ⛔ The guard that keeps this phase inside the not-supplied case: when the dataset IS
        // supplied, the cell comes from the JoinLookup and the expectation is not consulted at
        // all. Were the new read placed one frame too high it would overwrite a real joined cell —
        // and the expectation here (DM.AGE numeric) is precisely the one that WOULD.
        net.cumba.corej.core.exec.JoinLookup supplied = new net.cumba.corej.core.exec.JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                return "42";
            }


            @Override
            public String getDatasetName()
            {
                return "DM";
            }
        };
        EvaluationContext ctx = EvaluationContext.builder().table(primary())
                .numericExpectedColumns(Set.of("DM.AGE"))
                .joinedDatasets(java.util.Map.of("DM", supplied)).build();

        TypedValue v = dottedVector(ctx, "DM.AGE").value(0);

        assertFalse(v.isMissing(),
                "a SUPPLIED dataset's matched cell, not a default — the numeric expectation is"
                        + " not even consulted on this path");
        assertEquals("42", String.valueOf(v.resolved()));
    }


    /**
     * ⭐⭐ §9c DOTTED PARITY at the CALL SITE — the dataset is supplied but the COLUMN is absent from
     * it, which is the sibling defect this plan's §9c ruled and the one phase 5 could not reach:
     * {@code JoinLookup.lookupValue} was handed no expectation, so it answered an unconditional
     * {@code ""} while the same variable read as a primary column answered {@code MIS}. Both call
     * sites now pass the expectation as a parameter.
     *
     * <p>
     * ⛔⛔ <b>Part (b) is the control that proves the KEY, and it is the one that would have caught a
     * silently inert fix.</b> The expectation is recorded under the operand's FULL DOTTED name
     * ({@code DM.AGE}), which is what {@code TypeExpectations} files for a {@code DOTTED_REF}; had
     * either call site passed the bare column part ({@code AGE}) instead, part (a) would still pass
     * — the set would simply never match — and part (b) would flip to {@code MIS}. ⚠ A fix whose
     * only test is (a) is indistinguishable from no fix at all on a corpus where no rule records a
     * numeric expectation for a dotted name, which is today's corpus exactly.
     * </p>
     *
     * <p>
     * ⚑ This runs the REAL {@code DatasetLookup}, not a double, so the assertion covers the
     * production absent-column arm and the wiring in one step.
     * </p>
     */
    @Test
    void aSuppliedJoinWhoseColumnIsAbsentTakesTheRuleExpectedDefaultKeyedOnTheDottedName()
        throws ReflectiveOperationException
    {
        IDataTable dm = MockTable.of().col("USUBJID", "S1").name("DM").build();
        net.cumba.corej.core.exec.DatasetLookup lk = net.cumba.corej.core.exec.DatasetLookup
                .build("DM", dm, List.of("USUBJID"));
        assertNotNull(lk, "the fixture must join, or the arm under test is never reached");

        // (a) DM is joined, DM has no AGE, and the rule records DM.AGE as numeric ⇒ MIS, exactly
        // as an absent PRIMARY numeric column reads. Both arms of the operand shape.
        EvaluationContext numeric = EvaluationContext.builder().table(primary())
                .numericExpectedColumns(Set.of("DM.AGE")).joinedDatasets(java.util.Map.of("DM", lk))
                .build();
        assertTrue(dottedVector(numeric, "DM.AGE").value(0).isMissing(),
                "⛔ §9c: the authored form must answer MIS for a numeric-expected absent joined"
                        + " column");
        assertTrue(substitutedCell(numeric, "DM.AGE").isMissingOrInvalid(),
                "⛔ §9c: the ${…} form must agree with the authored one — the two must never hold"
                        + " different ideas of what an absent joined variable is");

        // (b) CONTROL — the same run with the BARE column name recorded instead. The dotted
        // operand is NOT keyed on it, so the char default stands.
        EvaluationContext bare = EvaluationContext.builder().table(primary())
                .numericExpectedColumns(Set.of("AGE")).joinedDatasets(java.util.Map.of("DM", lk))
                .build();
        TypedValue bareAuthored = dottedVector(bare, "DM.AGE").value(0);
        assertFalse(bareAuthored.isMissing(),
                "⛔ CONTROL: keyed on the FULL DOTTED name — a bare \"AGE\" must NOT match, or the"
                        + " fix is keyed wrongly and is inert on the real corpus");
        assertEquals("", bareAuthored.resolved());
        assertFalse(substitutedCell(bare, "DM.AGE").isMissingOrInvalid(),
                "⛔ CONTROL: same, for the ${…} form");
    }
}
