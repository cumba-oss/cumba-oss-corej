package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ The <b>VALUE channel</b> of {@code ExprCompiler.substitutedScalarCell} — the
 * {@code ${…}}-substituted scalar operand — pinned per arm.
 *
 * <p>
 * ⚑ TARGET-INVARIANT(null-free-value-channel), Class B and Class A site 1. Two defects lived in
 * this one method and both were invisible to every gate:
 * </p>
 * <ol>
 * <li><b>Class B — identity collapse.</b> A PRESENT column's MISSING cell minted a fresh generic
 * {@link MissingValue#MIS}, so {@code ${VAR} == .A} answered <i>false for a cell that IS
 * {@code .A}</i> (D11/D12/D85c).</li>
 * <li><b>Class A — absent-vs-missing.</b> A column ABSENT from the primary table produced a
 * computed {@code MIS} where an absent column is a CONSTANT-VALUE column owing its rule-expected
 * default (D76: character ⇒ {@code ""}, numeric ⇒ {@code MIS}; D34 #3/#4).</li>
 * </ol>
 *
 * <p>
 * ⚠⚠ <b>Why the assertions below read the {@code IDataValue}, never the text.</b> The defect class
 * survived because two tests pinned {@link ScalarSemantics#resolvedString}'s {@code String}
 * contract and <b>nothing pinned the {@code IDataValue} one</b>. A text projection cannot see
 * either defect: {@code ""} and a collapsed {@code MIS} both render blank, and {@code .A} and
 * {@code MIS} are both "missing". Only the value channel distinguishes them.
 * </p>
 */
class SubstitutedScalarValueChannelTest
{

    /** {@code substitutedScalarCell} is private; the ratchet test reaches it the same way. */
    private static IDataValue cell(EvaluationContext ctx, String operand)
        throws ReflectiveOperationException
    {
        // ⚠ THREE parameters since 2026-09-21, not five. `namePosition` and `foldAbsentColumn`
        // were removed by PLAN-unqualified-name-primary-only: under the uniformity ruling a bare
        // name resolves identically in every position, so a per-position/per-caller switch had no
        // behaviour left to carry. ⛔ A reflective harness does not fail to COMPILE when the method
        // it reaches changes shape -- it fails at run time with NoSuchMethodError, which is why
        // seven tests in two classes went red together.
        Method m = ExprCompiler.class.getDeclaredMethod("substitutedScalarCell",
                OperandSubstitutor.Scalar.class, EvaluationContext.class, long.class);
        m.setAccessible(true);
        OperandSubstitutor.Scalar scalar = new OperandSubstitutor.Scalar(null,
                List.of(new OperandSubstitutor.Literal(operand)));
        IDataValue v = (IDataValue) m.invoke(null, scalar, ctx, 0L);
        assertNotNull(v, "⚑ the value channel never answers null — that IS the rule");
        return v;
    }


    /**
     * A one-row, one-column table whose only cell carries the SUPPLIED missing identity {@code .A}.
     * ⛔ {@code MockTable} cannot express this: {@code mockDataValue} stubs a blank character cell
     * as {@code getValue() → null} (an unrepaired precondition of the plan), and
     * {@code colSasMissing} mints the generic {@code MIS} — so neither can tell a carried identity
     * from a collapsed one, which is exactly the distinction under test.
     */
    private static IDataTable tableWithMisACell(String columnName)
    {
        IDataValue misA = new DataValueMissing(MissingValue.MIS_A);
        IDataTableColumn column = mock(IDataTableColumn.class);
        lenient().when(column.getValue(0L)).thenReturn(MissingValue.MIS_A);
        lenient().when(column.getDataValue(0L)).thenReturn(misA);

        DataTableColumnMeta columnMeta = mock(DataTableColumnMeta.class);
        lenient().when(columnMeta.getType()).thenReturn(DataValueType.STRING);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getColumnIndex(columnName)).thenReturn(0);
        lenient().when(meta.getColumn(0)).thenReturn(columnMeta);

        IDataTable table = mock(IDataTable.class);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getColumn(0)).thenReturn(column);
        lenient().when(table.getRowCount()).thenReturn(1L);
        return table;
    }


    /** Class B: the cell is handed through, identity and all — never a re-minted generic MIS. */
    @Test
    void aPresentColumnsMissingCellKeepsItsOwnIdentity() throws ReflectiveOperationException
    {
        EvaluationContext ctx = EvaluationContext.builder().table(tableWithMisACell("AVAL"))
                .build();

        IDataValue v = cell(ctx, "AVAL");

        assertTrue(v.isMissingOrInvalid(), "the cell is missing");
        assertSame(MissingValue.MIS_A, v.getValue(),
                "⛔ it must be the SUPPLIED identity .A, not a minted MIS — minting is what made"
                        + " `${VAR} == .A` false for a cell that IS .A (D11/D12/D85c)");
        assertSame(MissingValue.MIS_A, TypedValue.missingIdentityOf(v),
                "and the carrier must decode that same identity");
        // The control that names the defect: the value this arm used to produce.
        assertSame(MissingValue.MIS, ScalarSemantics.computedMissing().getValue(),
                "CONTROL: computedMissing() is MIS, so had this arm kept minting it the assertion"
                        + " above would read MIS and fail — the two values are distinguishable");
    }


    /**
     * Class A: an absent column with NO recorded numeric expectation is all-{@code ""} — a PRESENT
     * empty string, not a missing (D76's "otherwise char", D34 #3).
     */
    @Test
    void anAbsentColumnIsThePresentEmptyStringWhenNoNumericExpectationExists()
        throws ReflectiveOperationException
    {
        EvaluationContext ctx = EvaluationContext.builder()
                .table(MockTable.of().col("OTHER", "x").build()).build();

        IDataValue v = cell(ctx, "ABSENT");

        assertFalse(v.isMissingOrInvalid(),
                "⛔ an absent CHARACTER column is PRESENT and empty, never missing — the computed"
                        + " MIS it used to produce sorts below every value under D34 #5");
        assertEquals("", v.getValue(), "and its value is the empty string (D34 #3)");
        assertTrue(ctx.getAbsentColumnFolds().contains("ABSENT"),
                "the fold must be reported through the same channel nameRefPlan/valueRefPlan use,"
                        + " so RuleRunner's aggregated INFO line sees it");
    }


    /** Class A, numeric arm: the expectation is read off the RULE, and MIS is then correct. */
    @Test
    void anAbsentColumnTheRuleNumericExpectsIsMis() throws ReflectiveOperationException
    {
        EvaluationContext ctx = EvaluationContext.builder()
                .table(MockTable.of().col("OTHER", "x").build())
                .numericExpectedColumns(Set.of("ABSENT")).build();

        IDataValue v = cell(ctx, "ABSENT");

        assertTrue(v.isMissingOrInvalid(), "a numeric-expected absent column is all-MIS (D34 #4)");
        assertSame(MissingValue.MIS, v.getValue(), "the constant is MissingValue.MIS");
    }


    /**
     * ⭐ Rewritten 2026-09-21. This javadoc used to say <i>"⛔ The
     * {@code empty()}/{@code is_missing()} arm is NOT folded … {@code FIRES_ON_ABSENT_COLUMN}
     * compiles its argument with {@code foldAbsentColumn=false}"</i> — and the body six lines below
     * already said the uniformity ruling removed that flag. Same file, opposite claims; the doc was
     * the older half.
     */
    @Test
    void anAbsentNameAnswersTheSameThingInEveryPosition() throws ReflectiveOperationException
    {
        EvaluationContext ctx = EvaluationContext.builder()
                .table(MockTable.of().col("OTHER", "x").build()).build();

        // ⭐⭐ REWRITTEN 2026-09-21. This test used to assert that the SAME absent name answered
        // MissingValue.MIS with foldAbsentColumn=false and "" with it true -- i.e. that a caller's
        // flag decided what the name meant. The uniformity ruling (owner) removed that flag, so
        // there is exactly ONE answer now, and asserting the old pair would be asserting a
        // contradiction: both calls below are the same call.
        //
        // The one answer is the absent-column contract (D34 #3): no numeric expectation is declared
        // on this context, so an absent column is a CHARACTER column whose every row is the present
        // empty string -- and the fold is REPORTED, which is what makes it auditable.
        assertEquals("", cell(ctx, "ABSENT").getValue(),
                "an absent name is the present empty string, in EVERY position");
        assertTrue(ctx.getAbsentColumnFolds().contains("ABSENT"),
                "and the fold is reported -- it is a fold now, not a silent non-decision");
    }


    /**
     * The end-to-end verdict the fix moves, on the authored form: {@code X == ""} over an absent
     * character column was <b>false</b> and is now <b>true</b>. ⭐ Expected and intended (owner,
     * 2026-09-18) — {@code MIS == ""} is false, {@code "" == ""} is true.
     */
    @Test
    void anAbsentSubstitutedCharColumnNowEqualsTheEmptyStringEndToEnd()
    {
        IDataTable primary = MockTable.of().col("IDX", "1").col("PRESENT", "p").build();
        EvaluationContext ctx = EvaluationContext.builder().table(primary).build();
        // "ABSENT${IDX}" resolves to "ABSENT1" on row 0, which no column of the table carries.
        Expr expr = new Expr.Binary(BinOp.EQ,
                new Expr.Ref("ABSENT${IDX}", OperandKind.WILDCARD_COLUMN),
                new Expr.Lit(Expr.LitKind.STRING, ""));

        assertTrue(NativeExprEvaluator.isSupported(expr), "the operand must compile natively");
        BitSet fired = NativeExprEvaluator.evaluate(expr, ctx);

        assertEquals("{0}", fired.toString(),
                "an absent char column IS the empty string on every row, so `== \"\"` holds");
    }
}
