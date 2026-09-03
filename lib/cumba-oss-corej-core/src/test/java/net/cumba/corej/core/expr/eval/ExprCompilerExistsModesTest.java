package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Native-compiler tests for the exists family modes (Phase 1 of
 * {@code plans/PLAN-extend-expression-engine.md}): {@code ds_exists}/{@code ds_not_exists} (dataset
 * presence), {@code var_exists}/{@code var_not_exists} (column presence), and the string-literal
 * argument form now accepted by all six exists-family functions.
 */
@ExtendWith(MockitoExtension.class)
class ExprCompilerExistsModesTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static EvaluationContext recordDataCtx(IDataTable t, DatasetResolver resolver)
    {
        return EvaluationContext.builder().table(t).datasetResolver(resolver).build();
    }


    private static BitSet allRows(int n)
    {
        BitSet bs = new BitSet(n);
        bs.set(0, n);
        return bs;
    }

    // ---- string literal ≡ bareword ----------------------------------------


    @Test
    void stringLiteralAndBarewordArgsProduceIdenticalVerdicts()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01", "S02").col("AESTDTC", "2024", "")
                .build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;
        EvaluationContext c = recordDataCtx(ae, resolver);

        for (String fn : List.of("ds_exists", "ds_not_exists"))
        {
            assertEquals(NativeExprEvaluator.evaluate(parse(fn + "(DM)"), c),
                    NativeExprEvaluator.evaluate(parse(fn + "(\"DM\")"), c),
                    fn + ": bareword and string-literal arguments must agree");
        }
        for (String fn : List.of("var_exists", "var_not_exists"))
        {
            assertEquals(NativeExprEvaluator.evaluate(parse(fn + "(AESTDTC)"), c),
                    NativeExprEvaluator.evaluate(parse(fn + "(\"AESTDTC\")"), c),
                    fn + ": bareword and string-literal arguments must agree");
        }
    }

    // ---- ds_exists ----------------------------------------------------------


    @Test
    void dsExists_datasetPresence_andStructuralNegation()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01", "S02").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;
        EvaluationContext c = recordDataCtx(ae, resolver);

        assertEquals(allRows(2), NativeExprEvaluator.evaluate(parse("ds_exists(\"DM\")"), c),
                "DM in the submission → ds_exists fires every row");
        assertTrue(NativeExprEvaluator.evaluate(parse("ds_exists(EX)"), c).isEmpty(),
                "EX absent → ds_exists is false");

        // Structural negation `not ds_exists(X)` ≡ ds_not_exists(X) — broadcast verdict flips.
        assertEquals(NativeExprEvaluator.evaluate(parse("ds_not_exists(EX)"), c),
                NativeExprEvaluator.evaluate(parse("not ds_exists(EX)"), c),
                "not ds_exists(EX) must equal ds_not_exists(EX)");
        assertEquals(NativeExprEvaluator.evaluate(parse("ds_not_exists(DM)"), c),
                NativeExprEvaluator.evaluate(parse("not ds_exists(DM)"), c),
                "not ds_exists(DM) must equal ds_not_exists(DM)");
    }


    @Test
    void dsExists_contextIndependence_nativePath()
    {
        IDataTable probe = MockTable.of().col("USUBJID", "S01").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;

        Expr dsExists = parse("ds_exists(DM)");
        BitSet onRecordData = NativeExprEvaluator.evaluate(dsExists,
                recordDataCtx(probe, resolver));
        BitSet onDomainPresence = NativeExprEvaluator.evaluate(dsExists,
                EvaluationContext.builder().table(probe).datasetResolver(resolver).build());

        assertEquals(allRows(1), onRecordData, "dataset presence on Record Data");
        assertEquals(onRecordData, onDomainPresence, "identical verdict on every context");

        // The retired generic exists(DM) — the one call whose fact the retired Rule_Type decided —
        // is a
        // definitional error now, not a third reading.
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> ExprCompiler.compile(parse("exists(DM)")));
        assertTrue(ex.getMessage().contains("retired"), ex.getMessage());
        assertThrows(RuleDefinitionException.class,
                () -> ExprCompiler.compile(parse("not_exists(DM)")));
    }

    // ---- var_exists ----------------------------------------------------------


    @Test
    void varExists_columnPresence_andDomainPrefixResolution()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01", "S02").col("AESEQ", "1", "2")
                .col("AESTDTC", "2024", "2025").build();
        EvaluationContext c = EvaluationContext.builder().table(ae).datasetResolver(_ -> null)
                .domainPrefix("AE").build();

        assertEquals(allRows(2), NativeExprEvaluator.evaluate(parse("var_exists(\"AESTDTC\")"), c),
                "AESTDTC is a column → var_exists fires every row");
        assertTrue(NativeExprEvaluator.evaluate(parse("var_exists(AEENDTC)"), c).isEmpty(),
                "AEENDTC missing → var_exists is false");

        // `--SEQ` resolves against the context's domain prefix (AE → AESEQ).
        assertEquals(allRows(2), NativeExprEvaluator.evaluate(parse("var_exists(--SEQ)"), c),
                "--SEQ resolves to AESEQ via the AE domain prefix");
        assertEquals(allRows(2), NativeExprEvaluator.evaluate(parse("var_not_exists(--ENDTC)"), c),
                "--ENDTC resolves to AEENDTC, which is missing");
    }


    @Test
    void varExists_contextIndependence_nativePath()
    {
        // On Domain Presence Check, var_exists("DM") keeps column semantics — false even though
        // the DM DATASET is resolvable.
        IDataTable probe = MockTable.of().col("USUBJID", "S01").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;
        EvaluationContext domainPresence = EvaluationContext.builder().table(probe)
                .datasetResolver(resolver).build();

        assertTrue(NativeExprEvaluator.evaluate(parse("var_exists(DM)"), domainPresence).isEmpty(),
                "var_exists is column presence even on Domain Presence Check");
        assertEquals(allRows(1),
                NativeExprEvaluator.evaluate(parse("var_exists(USUBJID)"), domainPresence),
                "a real column is found on Domain Presence Check too");
    }

    // ---- compile-time argument validation -----------------------------------


    @Test
    void nonNameLiteralArguments_areCompileTimeErrors()
    {
        // number, regex and list literals are not names — every family member rejects them.
        assertThrows(ExpressionException.class, () -> ExprCompiler.compile(parse("ds_exists(5)")),
                "number literal must be rejected");
        assertThrows(ExpressionException.class,
                () -> ExprCompiler.compile(parse("var_exists(/AE.*/)")),
                "regex literal must be rejected");
        Expr listArg = new Expr.Call("var_exists", List.of(
                new Expr.Lit(Expr.LitKind.LIST, List.of(new Expr.Lit(Expr.LitKind.STRING, "A")))),
                Map.of());
        assertThrows(ExpressionException.class, () -> ExprCompiler.compile(listArg),
                "list literal must be rejected");
        assertThrows(ExpressionException.class,
                () -> ExprCompiler.compile(parse("var_not_exists(7)")),
                "number literal must be rejected on the negated twin too");
    }


    @Test
    void dsExists_rejectsNonPlainDatasetNames_atCompileTime()
    {
        // Dotted name, filter form, ${...} substitution and the -- prefix have no
        // dataset-presence surface — all are compile-time errors, on both twins.
        assertThrows(ExpressionException.class,
                () -> ExprCompiler.compile(parse("ds_exists(AE.AESTDY)")),
                "dotted name must be rejected");
        assertThrows(ExpressionException.class,
                () -> ExprCompiler.compile(parse("ds_exists(\"DS.DSDECOD=DEATH\")")),
                "filter form must be rejected");
        assertThrows(ExpressionException.class,
                () -> ExprCompiler.compile(parse("ds_exists(\"AP${APERIOD}SDT\")")),
                "${...} substitution must be rejected");
        assertThrows(ExpressionException.class,
                () -> ExprCompiler.compile(parse("ds_not_exists(\"--DM\")")),
                "-- prefix must be rejected");

        // The same shapes stay legal on the column-form family.
        assertTrue(NativeExprEvaluator.isSupported(parse("var_exists(AE.AESTDY)")),
                "dotted names stay legal on var_exists");
        assertTrue(NativeExprEvaluator.isSupported(parse("var_exists(\"DS.DSDECOD=DEATH\")")),
                "the filter form stays legal on var_exists");
    }

    // ---- broadcast fold participation ----------------------------------------


    @Test
    void newOperators_participateInDatasetLevelFolds()
    {
        IDataTable ae = MockTable.of().col("USUBJID", "S01").col("AESTDTC", "2024").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S01").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;
        EvaluationContext c = recordDataCtx(ae, resolver);

        assertEquals(BroadcastFold.Verdict.TRUE,
                BroadcastFold.fold(parse("ds_exists(DM)"), c, false),
                "ds_exists folds like exists at dataset level");
        assertEquals(BroadcastFold.Verdict.FALSE,
                BroadcastFold.fold(parse("ds_exists(EX)"), c, false), "absent dataset folds FALSE");
        assertEquals(BroadcastFold.Verdict.TRUE,
                BroadcastFold.fold(parse("var_exists(\"AESTDTC\")"), c, false),
                "var_exists with a string-literal arg folds like exists");
        assertEquals(BroadcastFold.Verdict.TRUE,
                BroadcastFold.fold(parse("var_not_exists(AEENDTC)"), c, false),
                "var_not_exists on a missing column folds TRUE");
    }

    // ---- ${...} placeholder names evaluate PER ROW (review F3) -----------------


    @Test
    void placeholderNames_evaluatePerRow_matchingLegacyBitForBit()
    {
        // APERIOD drives the substitution: row 0 resolves AP1SDT (present), row 1 resolves
        // AP2SDT (absent). The legacy engine routes ${...} names through the per-row
        // OperatorRegistry.existsPerRowBits; the native plan must produce the SAME per-row
        // BitSet — not a broadcast of row 0's verdict.
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02").col("APERIOD", "1", "2")
                .col("AP1SDT", "2024-01-01", "2024-01-02").build();
        EvaluationContext c = recordDataCtx(t, _ -> null);

        BitSet row0Only = new BitSet();
        row0Only.set(0);
        BitSet row1Only = new BitSet();
        row1Only.set(1);

        for (String fn : List.of("var_exists"))
        {
            BitSet nativeBits = NativeExprEvaluator.evaluate(parse(fn + "(\"AP${APERIOD}SDT\")"),
                    c);
            assertEquals(row0Only, nativeBits, fn + ": only row 0 resolves an existing column");
        }
        for (String fn : List.of("var_not_exists"))
        {
            BitSet nativeBits = NativeExprEvaluator.evaluate(parse(fn + "(\"AP${APERIOD}SDT\")"),
                    c);
            assertEquals(row1Only, nativeBits, fn + ": only row 1 resolves a missing column");
        }

        // Structural negation agrees with the negated twin on the per-row path too.
        assertEquals(NativeExprEvaluator.evaluate(parse("var_not_exists(\"AP${APERIOD}SDT\")"), c),
                NativeExprEvaluator.evaluate(parse("not var_exists(\"AP${APERIOD}SDT\")"), c),
                "not var_exists(${...}) must equal var_not_exists(${...})");
    }


    @Test
    void existsCallShape_acceptsStringLiteral_butNotPlaceholderNames()
    {
        Expr.Call litCall = new Expr.Call("var_exists",
                List.of(new Expr.Lit(Expr.LitKind.STRING, "AESTDTC")), Map.of());
        assertTrue(BroadcastFold.isExistsCall(litCall),
                "string-literal argument is an exists-call shape");
        Expr.Call refCall = new Expr.Call("ds_not_exists",
                List.of(new Expr.Ref("DM", OperandKind.COLUMN)), Map.of());
        assertTrue(BroadcastFold.isExistsCall(refCall), "bare reference stays an exists-call");

        // A ${...} operand template classifies ROW (per-row driver substitution) — the fold must
        // stay UNKNOWN for it, exactly like legacy exists.
        IDataTable t = MockTable.of().col("USUBJID", "S01").col("APERIOD", "1")
                .col("AP1SDT", "2024").build();
        EvaluationContext c = recordDataCtx(t, _ -> null);
        assertEquals(BroadcastFold.Verdict.UNKNOWN,
                BroadcastFold.fold(parse("var_exists(\"AP${APERIOD}SDT\")"), c, false),
                "${...} names stay per-row — never folded at dataset level");
    }
}
