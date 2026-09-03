package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.ast.Expr.BinOp;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Epic B1 — strict native==legacy parity for {@code ${...}} operand-substitution operands the
 * native evaluator now resolves (previously it declined / fell back to the legacy engine):
 * <ul>
 * <li>a {@code ${VAR[:fmt]}} <b>scalar</b> substitution in value position (per-row dynamic column
 * read), and</li>
 * <li>a {@code ${*}} <b>wildcard</b> list operand with {@code is_not_contained_by} membership.</li>
 * </ul>
 * Each native verdict ({@link NativeExprEvaluator#evaluate}) is asserted against explicit expected
 * bits on a synthetic dataset with a {@code Match_Datasets}-style join carrying the foreign columns
 * (the legacy comparison oracle retired with the engine). A final case proves a Check combining
 * {@code ${VAR}} with the native-only {@code abs()} function now compiles AND evaluates natively
 * (the mixed-rule unblock).
 */
class NativeOperandSubstitutionParityTest
{

    private static Expr ref(String n, OperandKind kind)
    {
        return new Expr.Ref(n, kind);
    }


    /**
     * Row-aligned join lookup over {@code foreign} (test simplification: primary row i ↔ row i).
     */
    private static JoinLookup rowAlignedJoin(IDataTable foreign, String name)
    {
        return new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                int idx = foreign.getMetaData().getColumnIndex(columnName);
                return idx < 0 || row >= foreign.getRowCount() ? null
                        : valueOrNull(foreign, idx, row);
            }


            @Override
            public String getDatasetName()
            {
                return name;
            }
        };
    }


    private static String valueOrNull(IDataTable t, int colIdx, long row)
    {
        var dv = t.getColumn(colIdx).getDataValue(row);
        return dv.isMissingOrInvalid() ? null : dv.getValueAsString();
    }

    // ---------------------------------------------------------------------
    // Scalar ${VAR:%02d} value-position substitution
    // ---------------------------------------------------------------------


    @Test
    void scalarSubstitutionValuePositionMatchesLegacy()
    {
        // Primary: TRTP (name col) compared != ADSL.TRT${APERIOD:%02d}P (the foreign column whose
        // name is built from the row's APERIOD driver). Row 0: APERIOD=1 → ADSL.TRT01P; row 1:
        // APERIOD=2 → ADSL.TRT02P; row 2: APERIOD=3 → ADSL.TRT03P (absent → null).
        IDataTable primary = MockTable.of().col("TRTP", "DRUG", "PLACEBO", "DRUG")
                .colLong("APERIOD", 1L, 2L, 3L).build();
        IDataTable adsl = MockTable.of().name("ADSL").col("TRT01P", "DRUG", "DRUG", "DRUG")
                .col("TRT02P", "PLACEBO", "DRUG", "PLACEBO").build();
        DatasetResolver resolver = ds -> "ADSL".equals(ds) ? adsl : null;
        EvaluationContext ctx = EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of("ADSL", rowAlignedJoin(adsl, "ADSL"))).build();

        String operand = "ADSL.TRT${APERIOD:%02d}P";
        Expr expr = new Expr.Binary(BinOp.NEQ, ref("TRTP", OperandKind.COLUMN),
                ref(operand, OperandKind.WILDCARD_COLUMN));

        assertTrue(NativeExprEvaluator.isSupported(expr),
                "scalar ${VAR} value operand must compile natively");
        BitSet nativ = NativeExprEvaluator.evaluate(expr, ctx);
        // Row 0: TRTP=DRUG vs ADSL.TRT01P=DRUG → equal → no violation.
        // Row 1: TRTP=PLACEBO vs ADSL.TRT02P(row1)=DRUG → not equal → violation.
        // Row 2: TRTP=DRUG vs ADSL.TRT03P(absent)=null → one-missing → not equal → violation.
        assertEquals("{1, 2}", nativ.toString(), "expected violations on rows 1 and 2");
    }


    @Test
    void scalarSubstitutionLocalColumnMatchesLegacy()
    {
        // No foreign prefix: the substituted name resolves against the primary table directly.
        IDataTable primary = MockTable.of().col("AVAL", "5", "9", "5").col("VAL01", "5", "5", "5")
                .col("VAL02", "9", "9", "9").colLong("IDX", 1L, 2L, 1L).build();
        EvaluationContext ctx = EvaluationContext.builder().table(primary).build();
        String operand = "VAL${IDX:%02d}";
        Expr expr = new Expr.Binary(BinOp.EQ, ref("AVAL", OperandKind.COLUMN),
                ref(operand, OperandKind.WILDCARD_COLUMN));

        assertTrue(NativeExprEvaluator.isSupported(expr));
        // AVAL {5,9,5} vs VAL01/VAL02/VAL01 per IDX {1,2,1}: 5==5, 9==9, 5==5 → all rows equal.
        assertEquals("{0, 1, 2}", NativeExprEvaluator.evaluate(expr, ctx).toString(),
                "the per-row substituted column equals AVAL on every row");
    }

    // ---------------------------------------------------------------------
    // ${*} wildcard membership
    // ---------------------------------------------------------------------


    @Test
    void wildcardMembershipNotContainedByMatchesLegacy()
    {
        // is_not_contained_by: APHASE not in {ADSL.APHASE1, ADSL.APHASE2, ...}. The wildcard
        // ADSL.APHASE${*} enumerates the foreign columns APHASE1/APHASE2 and collects the row's
        // values; a row fires when its APHASE value is NOT among them.
        IDataTable primary = MockTable.of().col("APHASE", "SCREEN", "OTHER", "").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("APHASE1", "SCREEN", "SCREEN", "SCREEN")
                .col("APHASE2", "TREAT", "TREAT", "TREAT").col("APHASEX", "noise", "noise", "noise")
                .build();
        DatasetResolver resolver = ds -> "ADSL".equals(ds) ? adsl : null;
        EvaluationContext ctx = EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of("ADSL", rowAlignedJoin(adsl, "ADSL"))).build();

        String operand = "ADSL.APHASE${*}";
        Expr expr = new Expr.Binary(BinOp.NOT_IN, ref("APHASE", OperandKind.COLUMN),
                ref(operand, OperandKind.WILDCARD_COLUMN));

        assertTrue(NativeExprEvaluator.isSupported(expr), "${*} membership must compile natively");
        BitSet nativ = NativeExprEvaluator.evaluate(expr, ctx);
        // ${*} only matches the trailing-digit columns APHASE1/APHASE2 (APHASEX is excluded).
        // Row 0: SCREEN ∈ {SCREEN,TREAT} → contained → no violation.
        // Row 1: OTHER ∉ {SCREEN,TREAT} → not contained → violation.
        // Row 2: empty-string literal fix (A.1) — "" ∉ {SCREEN,TREAT} → not contained → violation.
        assertEquals("{1, 2}", nativ.toString(), "expected violations on rows 1 and 2 (\"\")");
    }


    @Test
    void wildcardMembershipContainedByMatchesLegacy()
    {
        // The positive is_contained_by surface (negate=false) over the same wildcard.
        IDataTable primary = MockTable.of().col("APHASE", "SCREEN", "OTHER").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("APHASE1", "SCREEN", "SCREEN")
                .col("APHASE2", "TREAT", "TREAT").build();
        DatasetResolver resolver = ds -> "ADSL".equals(ds) ? adsl : null;
        EvaluationContext ctx = EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of("ADSL", rowAlignedJoin(adsl, "ADSL"))).build();

        String operand = "ADSL.APHASE${*}";
        Expr expr = new Expr.Binary(BinOp.IN, ref("APHASE", OperandKind.COLUMN),
                ref(operand, OperandKind.WILDCARD_COLUMN));

        assertTrue(NativeExprEvaluator.isSupported(expr));
        BitSet nativ = NativeExprEvaluator.evaluate(expr, ctx);
        assertEquals("{0}", nativ.toString(), "expected a violation only on row 0");
    }


    @Test
    void multiStarWildcardMembershipMatchesLegacy()
    {
        // EC-16: two ${*} runs in one operand. ADSL.TR${*}PG${*} enumerates the foreign columns
        // whose names match ^TR\d+PG\d+$ (TR1PG1/TR2PG2), excluding the non-digit TRXPGY. Proves
        // the multi-star template lexes as one operand token and compiles identically on both
        // lanes (OperandSubstitutor.toColumnPattern).
        IDataTable primary = MockTable.of().col("VAL", "A", "Z").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("TR1PG1", "A", "A")
                .col("TR2PG2", "B", "B").col("TRXPGY", "Z", "Z").build();
        DatasetResolver resolver = ds -> "ADSL".equals(ds) ? adsl : null;
        EvaluationContext ctx = EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of("ADSL", rowAlignedJoin(adsl, "ADSL"))).build();

        String operand = "ADSL.TR${*}PG${*}";
        Expr expr = new Expr.Binary(BinOp.NOT_IN, ref("VAL", OperandKind.COLUMN),
                ref(operand, OperandKind.WILDCARD_COLUMN));

        assertTrue(NativeExprEvaluator.isSupported(expr),
                "multi-star ${*} membership must compile natively");
        BitSet nativ = NativeExprEvaluator.evaluate(expr, ctx);
        // Set = {A, B} (TRXPGY excluded): row0 "A" contained -> no fire; row1 "Z" not contained.
        assertEquals("{1}", nativ.toString(), "only row 1 (\"Z\") is not contained");
    }

    // ---------------------------------------------------------------------
    // Mixed rule: ${VAR} scalar + native-only abs() — the unblock
    // ---------------------------------------------------------------------


    @Test
    void mixedScalarSubstitutionWithNativeOnlyFunctionGoesNative()
    {
        // abs(AVAL) != ADSL.TGT${IDX:%d} — abs() is a native-only function (no legacy operator),
        // and ${IDX} is an operand substitution the native evaluator previously declined. Neither
        // backend could evaluate the WHOLE rule before: it would not lower (abs) and would not
        // compile natively (${...}). It must now compile AND evaluate natively.
        IDataTable primary = MockTable.of().colDouble("AVAL", -5.0, 9.0, -3.0)
                .colLong("IDX", 1L, 2L, 1L).build();
        // abs() yields a DOUBLE whose string form is "5.0"/"9.0"; the substituted target is read as
        // a string, so the equality is a string compare — match that with "5.0"/"9.0" targets.
        IDataTable adsl = MockTable.of().name("ADSL").col("TGT1", "5.0", "5.0", "5.0")
                .col("TGT2", "9.0", "9.0", "9.0").build();
        DatasetResolver resolver = ds -> "ADSL".equals(ds) ? adsl : null;
        EvaluationContext ctx = EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of("ADSL", rowAlignedJoin(adsl, "ADSL"))).build();

        Expr absCall = new Expr.Call("abs", java.util.List.of(ref("AVAL", OperandKind.COLUMN)),
                Map.of());
        Expr expr = new Expr.Binary(BinOp.NEQ, absCall,
                ref("ADSL.TGT${IDX:%d}", OperandKind.WILDCARD_COLUMN));

        assertTrue(NativeExprEvaluator.isSupported(expr),
                "mixed abs() + ${VAR} rule must now compile on the native backend");
        BitSet nativ = NativeExprEvaluator.evaluate(expr, ctx);
        // Row 0: abs(-5)=5 vs ADSL.TGT1=5 → equal → no violation.
        // Row 1: abs(9)=9 vs ADSL.TGT2(row1)=9 → equal → no violation.
        // Row 2: abs(-3)=3 vs ADSL.TGT1(row2)=5 → not equal → violation.
        assertEquals("{2}", nativ.toString(), "abs()+ ${VAR} verdict: only row 2 violates");
    }
}
