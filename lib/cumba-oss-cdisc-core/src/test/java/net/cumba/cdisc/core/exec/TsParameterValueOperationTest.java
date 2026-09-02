package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.eval.NativeExprEvaluator;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * T7 — {@code ts_parameter_value} operation. Resolves a single {@code TSVAL} scalar for a given
 * {@code TSPARMCD} from the TS dataset (via the {@link DatasetResolver}), broadcast to every row.
 * Covers: a matching parameter returns its value; a missing parameter / absent dataset / absent
 * column returns {@code null} (so the dependent comparison skips); and the scalar used natively in
 * a {@code date(...)} comparison.
 */
class TsParameterValueOperationTest
{

    private static IDataTable tsDataset()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "EXPSTDTC", "EXPENDTC", "SPECIES")
                .col("TSVAL", "2020-01-05", "2020-12-31", "RAT").build();
    }


    private static Operation tsParamOp(String id, String keyValue)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator("ts_parameter_value");
        op.setDomain("TS");
        op.setKeyName("TSPARMCD");
        op.setKeyValue(keyValue);
        op.setName("TSVAL");
        return op;
    }


    @Test
    void matchingParameterReturnsTsval()
    {
        IDataTable ex = MockTable.of().name("EX").col("EXSTDTC", "2020-06-01", "2020-06-02")
                .build();
        IDataTable ts = tsDataset();
        DatasetResolver resolver = name -> "TS".equals(name) ? ts : null;

        Map<String, Object> vars = OperationExecutor.execute(List.of(tsParamOp("$exp", "EXPSTDTC")),
                ex, resolver);

        assertEquals("2020-01-05", vars.get("$exp"));
    }


    @Test
    void missingParameterReturnsNull()
    {
        IDataTable ex = MockTable.of().name("EX").col("EXSTDTC", "2020-06-01").build();
        IDataTable ts = tsDataset();
        DatasetResolver resolver = name -> "TS".equals(name) ? ts : null;

        Map<String, Object> vars = OperationExecutor.execute(List.of(tsParamOp("$exp", "NOTAPARM")),
                ex, resolver);

        // No TSPARMCD == NOTAPARM row ⇒ null ⇒ the operation result is dropped (rule skips).
        assertFalse(vars.containsKey("$exp"));
    }


    @Test
    void absentTsDatasetReturnsNull()
    {
        IDataTable ex = MockTable.of().name("EX").col("EXSTDTC", "2020-06-01").build();
        DatasetResolver resolver = _ -> null; // TS not available

        Map<String, Object> vars = OperationExecutor.execute(List.of(tsParamOp("$exp", "EXPSTDTC")),
                ex, resolver);

        assertFalse(vars.containsKey("$exp"));
    }


    @Test
    void absentTargetColumnReturnsNull()
    {
        IDataTable ex = MockTable.of().name("EX").col("EXSTDTC", "2020-06-01").build();
        // TS present but without the TSVAL column.
        IDataTable ts = MockTable.of().name("TS").col("TSPARMCD", "EXPSTDTC").build();
        DatasetResolver resolver = name -> "TS".equals(name) ? ts : null;

        Map<String, Object> vars = OperationExecutor.execute(List.of(tsParamOp("$exp", "EXPSTDTC")),
                ex, resolver);

        assertNull(OperationExecutor.executeOne(tsParamOp("$exp", "EXPSTDTC"), ex, resolver, null,
                Map.of(), null));
        assertFalse(vars.containsKey("$exp"));
    }


    @Test
    void usedInDateComparisonFiresWhenBeforeWindow()
    {
        // FDA-SE1148 shape: EXSTDTC must be >= TS EXPSTDTC ⇒ fires when EXSTDTC < EXPSTDTC.
        // Row 0 (2020-01-01) is before EXPSTDTC (2020-01-05) ⇒ fires; row 1 (2020-06-01) does not.
        IDataTable ex = MockTable.of().name("EX").col("EXSTDTC", "2020-01-01", "2020-06-01")
                .build();
        IDataTable ts = tsDataset();
        DatasetResolver resolver = name -> "TS".equals(name) ? ts : null;
        EvaluationContext ctx = EvaluationContext.builder().table(ex).domainPrefix("EX")
                .datasetResolver(resolver).build();

        BitSet bits = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(
                "date(EXSTDTC) < ts_parameter_value(TSVAL, domain=\"TS\", key_name=\"TSPARMCD\","
                        + " key_value=\"EXPSTDTC\")"),
                ctx);

        assertTrue(bits.get(0), "EXSTDTC 2020-01-01 is before EXPSTDTC 2020-01-05 ⇒ fires");
        assertFalse(bits.get(1), "EXSTDTC 2020-06-01 is on/after EXPSTDTC ⇒ does not fire");
    }


    @Test
    void dateComparisonSkipsWhenParameterAbsent()
    {
        // No TS ⇒ ts_parameter_value is null ⇒ comparison operand null ⇒ no row fires (rule skips).
        IDataTable ex = MockTable.of().name("EX").col("EXSTDTC", "2020-01-01", "2020-06-01")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(ex).domainPrefix("EX")
                .datasetResolver(_ -> null).build();

        BitSet bits = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(
                "date(EXSTDTC) < ts_parameter_value(TSVAL, domain=\"TS\", key_name=\"TSPARMCD\","
                        + " key_value=\"EXPSTDTC\")"),
                ctx);

        assertTrue(bits.isEmpty(), "null window ⇒ no row fires");
    }

}
