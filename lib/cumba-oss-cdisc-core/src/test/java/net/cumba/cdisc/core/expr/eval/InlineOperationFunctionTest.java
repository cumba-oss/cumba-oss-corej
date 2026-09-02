package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.OperationExecutor;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Form A — inline operation functions. An operation authored inline (e.g.
 * {@code variable_count(--LNKGRP) < 2}) must evaluate bit-for-bit identically to the equivalent
 * {@code $}-operation reference fed from the {@code Operations} pre-compute. Each parity case
 * computes the field-form operation through {@link OperationExecutor}, stuffs the result into the
 * context variables under a {@code $}-id, and compares the {@code $}-form's verdict to the inline
 * form's.
 */
class InlineOperationFunctionTest
{

    private static BitSet evalInline(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static BitSet evalRef(String refExpr, Operation fieldOp, EvaluationContext ctx)
    {
        Object result = OperationExecutor.executeOne(fieldOp, ctx.getTable(),
                ctx.getDatasetResolver(), ctx.getLibraryProvider(), Map.of(), null);
        EvaluationContext refCtx = ctx.toBuilder().variables(Map.of(fieldOp.getId(), result))
                .build();
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(refExpr), refCtx);
    }


    private static Operation fieldOp(String id, String operator, String name)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        op.setName(name);
        return op;
    }


    @Test
    void variableCountInlineMatchesRef()
    {
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1", "2").col("AELNKGRP", "G1", "G2")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae).domainPrefix("AE").build();

        BitSet inline = evalInline("variable_count(--LNKGRP) < 2", ctx);
        BitSet ref = evalRef("$VC < 2", fieldOp("$VC", "variable_count", "--LNKGRP"), ctx);

        assertEquals(ref, inline);
        assertTrue(inline.get(0) && inline.get(1), "AELNKGRP exists ⇒ count 1 < 2 fires every row");
    }


    @Test
    void recordCountFilterInlineMatchesRef()
    {
        IDataTable ts = MockTable.of().name("TS").col("TSPARMCD", "INDIC", "INDIC", "OTHER")
                .col("TSVALNF", "NA", "X", "NA").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ts).domainPrefix("TS").build();

        Operation op = fieldOp("$RC", "record_count", null);
        op.setFilter(Map.of("TSPARMCD", "INDIC", "TSVALNF", "NA"));

        BitSet inline = evalInline(
                "record_count(filter=filter(TSPARMCD=\"INDIC\", TSVALNF=\"NA\")) >= 1", ctx);
        BitSet ref = evalRef("$RC >= 1", op, ctx);
        assertEquals(ref, inline);
    }


    @Test
    void recordCountGroupedInlineMatchesRef()
    {
        IDataTable dm = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("AESEQ", "1", "2", "1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(dm).domainPrefix("AE").build();

        Operation op = fieldOp("$RC", "record_count", null);
        op.setGroup(java.util.List.of("USUBJID"));

        BitSet inline = evalInline("record_count(group=[USUBJID]) > 1", ctx);
        BitSet ref = evalRef("$RC > 1", op, ctx);

        assertEquals(ref, inline);
        // S1 has 2 records (>1) ⇒ rows 0,1 fire; S2 has 1 ⇒ row 2 does not.
        assertTrue(inline.get(0) && inline.get(1));
        assertFalse(inline.get(2));
    }


    @Test
    void bareRecordCountKeepsBuiltin()
    {
        // record_count() with no kwargs is the arity-0 builtin (table row count), NOT the operation
        // path — the collision guard (D5) must route it to the builtin so it still compiles.
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1", "2").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae).build();
        Expr expr = CheckExpressionParser.parse("record_count() < 5");
        assertTrue(NativeExprEvaluator.isSupported(expr));
        BitSet bits = NativeExprEvaluator.evaluate(expr, ctx);
        assertTrue(bits.get(0) && bits.get(1), "2 rows < 5 ⇒ broadcast true");
    }


    @Test
    void inlineGroupExpandsLazyDollarReference()
    {
        // group=[$G] where $G is a prior operation result wrapped in a LazyValue — the inline path
        // must unwrap it (forcedPriors) before expandGroupRefs, exactly as the $-ref form does.
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("AESEQ", "1", "2", "1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae).domainPrefix("AE")
                .variables(Map.of("$G", new net.cumba.cdisc.core.exec.LazyValue<>(
                        () -> java.util.List.of("USUBJID"))))
                .build();

        BitSet inline = evalInline("record_count(group=[$G]) > 1", ctx);
        // S1 has 2 records (>1) ⇒ rows 0,1 fire; S2 has 1 ⇒ row 2 does not.
        assertTrue(inline.get(0) && inline.get(1));
        assertFalse(inline.get(2));
    }


    @Test
    void libraryInlineCompilesCrossDatasetDoesNot()
    {
        // §9.C: a library-dependent operation now compiles inline; its SKIP-on-missing-Library is
        // restored by the library_available()/available() Precondition the converter adds.
        assertTrue(NativeExprEvaluator
                .isSupported(CheckExpressionParser.parse("required_variables() == AAA")));
        // cross_dataset_variable_metadata is still rejected (no inline operand surface) — author it
        // as a var_*(dataset=) accessor (§9.D).
        assertThrows(net.cumba.cdisc.core.expr.RuleDefinitionException.class,
                () -> NativeExprEvaluator.isSupported(CheckExpressionParser
                        .parse("cross_dataset_variable_metadata(\"label\") == AAA")));
    }

}
