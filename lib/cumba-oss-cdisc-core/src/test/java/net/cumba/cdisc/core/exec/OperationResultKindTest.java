package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.List;
import net.cumba.cdisc.core.exec.OperationExecutor.ResultKind;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@link OperationExecutor#resultKind} transcribes {@code dispatch}; this keeps the transcription
 * honest by <b>executing</b> representative operations and comparing the runtime value's class with
 * the static classification — the same {@code instanceof} test {@code BroadcastFold} applies.
 */
class OperationResultKindTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation op(String operator)
    {
        Operation o = new Operation();
        o.setId("$x");
        o.setOperator(operator);
        return o;
    }


    private static ResultKind observed(Operation o, IDataTable t, MetadataProvider p)
    {
        Object v = OperationExecutor.executeOne(o, t, NO_RESOLVER, p, new HashMap<>());
        if (v instanceof GroupedResult)
        {
            return ResultKind.PER_ROW;
        }
        if (v instanceof VariableMetadataResult)
        {
            return ResultKind.PER_VARIABLE;
        }
        return ResultKind.SCALAR;
    }


    private static void assertKindMatchesRuntime(Operation o, IDataTable t)
    {
        assertKindMatchesRuntime(o, t, null);
    }


    private static void assertKindMatchesRuntime(Operation o, IDataTable t, MetadataProvider p)
    {
        assertEquals(observed(o, t, p), OperationExecutor.resultKind(o),
                () -> o.getOperator() + " group=" + o.getGroup());
    }


    @Test
    void groupedAggregatesArePerRowAndUngroupedOnesScalar()
    {
        IDataTable t = MockTable.of().name("SV").col("USUBJID", "S1", "S2")
                .col("VISITNUM", "1", "2").build();
        for (String operator : List.of("record_count", "distinct", "max", "max_date", "min_date"))
        {
            Operation ungrouped = op(operator);
            ungrouped.setName("VISITNUM");
            assertEquals(ResultKind.SCALAR, OperationExecutor.resultKind(ungrouped), operator);
            Operation grouped = op(operator);
            grouped.setName("VISITNUM");
            grouped.setGroup(List.of("USUBJID"));
            assertEquals(ResultKind.PER_ROW, OperationExecutor.resultKind(grouped), operator);
        }
        Operation cnt = op("record_count");
        cnt.setGroup(List.of("USUBJID"));
        assertKindMatchesRuntime(cnt, t);
        Operation d = op("distinct");
        d.setName("VISITNUM");
        d.setGroup(List.of("USUBJID"));
        assertKindMatchesRuntime(d, t);
        Operation ud = op("distinct");
        ud.setName("VISITNUM");
        assertKindMatchesRuntime(ud, t);
        // A dataset-constant group key still yields a GroupedResult object at runtime — the
        // classifier's "single group" reading is not the runtime's shape, and the runtime's is
        // what routes.
        Operation constantKey = op("record_count");
        constantKey.setGroup(List.of("STUDYID"));
        assertEquals(ResultKind.PER_ROW, OperationExecutor.resultKind(constantKey));
    }


    @Test
    void alwaysGroupedOperationsArePerRowWithoutAGroupKeyword()
    {
        IDataTable t = MockTable.of().name("SE").col("USUBJID", "S1", "S1").col("PARAMCD", "A", "A")
                .col("BASETYPE", "X", "").col("SESTDTC", "2020-01-01", "2020-02-01")
                .col("RFSTDTC", "2020-01-01", "2020-01-01").build();
        Operation mixed = op("has_mixed_emptiness_within_group");
        mixed.setName("BASETYPE");
        mixed.setGroup(List.of("PARAMCD"));
        assertKindMatchesRuntime(mixed, t);
        Operation last = op("is_last_in_group");
        last.setGroup(List.of("USUBJID"));
        last.setOrdering("SESTDTC");
        assertKindMatchesRuntime(last, t);
        Operation rowMax = op("row_max");
        rowMax.setNamePattern("SESTDTC");
        assertEquals(ResultKind.PER_ROW, OperationExecutor.resultKind(rowMax));
        for (OperationType type : List.of(OperationType.DY,
                OperationType.VALID_EXTERNAL_DICTIONARY_VALUE,
                OperationType.VALID_EXTERNAL_DICTIONARY_CODE,
                OperationType.VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR,
                OperationType.VALID_EXTERNAL_DICTIONARY_HIERARCHY,
                OperationType.DICTIONARY_HAS_DECODE,
                OperationType.INTERVAL_UNCERTAINTY_PRECISION_MISMATCH, OperationType.DATE_DIFF_DAYS,
                OperationType.ROW_MIN, OperationType.SUPP_QNAM_PRESENT,
                OperationType.SUPP_QNAM_VALUE))
        {
            assertEquals(ResultKind.PER_ROW, OperationExecutor.resultKind(op(type.getJsonValue())),
                    type.name());
        }
    }


    @Test
    void referencedDomainClassIsPerRowAtRuntime()
    {
        IDataTable t = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").build();
        Operation o = op("referenced_domain_class");
        o.setName("RDOMAIN");
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getDatasetClass("AE", "AE")).thenReturn("EVENTS");
        assertInstanceOf(GroupedResult.class,
                OperationExecutor.executeOne(o, t, NO_RESOLVER, p, new HashMap<>()));
        assertKindMatchesRuntime(o, t, p);
    }


    @Test
    void parentModelColumnOrderIsPerRowAtRuntime()
    {
        // Review finding 3 (2026-08-22): evalParentModelColumnOrder builds a GroupedResult keyed
        // on RDOMAIN unconditionally, but the static mirror had left the constant out — a
        // `{}`-domain Check over its $-ref would have reached the broadcast path over a per-row
        // value. The runtime here resolves the parent through a resolver, so observed() is
        // re-built with one.
        IDataTable supp = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").col("QNAM", "X")
                .build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AETERM", "T").build();
        MetadataProvider p = mock(MetadataProvider.class);
        lenient()
                .when(p.getStandardModelVariables(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("USUBJID", "AETERM"));
        Operation o = op("get_parent_model_column_order");
        Object v = OperationExecutor.executeOne(o, supp, name -> "AE".equals(name) ? ae : null, p,
                new HashMap<>());
        assertInstanceOf(GroupedResult.class, v);
        assertEquals(ResultKind.PER_ROW, OperationExecutor.resultKind(o));
    }


    @Test
    void distinctWithValueIsReferenceIsPerRow()
    {
        IDataTable t = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").col("QNAM", "X").build();
        Operation o = op("distinct");
        o.setName("QNAM");
        o.setValueIsReference(true);
        assertKindMatchesRuntime(o, t);
    }


    @Test
    void crossDatasetVariableMetadataIsPerVariableAndTheRestScalar()
    {
        assertEquals(ResultKind.PER_VARIABLE,
                OperationExecutor.resultKind(op("cross_dataset_variable_metadata")));
        IDataTable t = MockTable.of().name("DM").col("USUBJID", "S1").col("AGE", "1").build();
        for (String operator : List.of("variable_count", "dataset_names",
                "get_column_order_from_dataset", "variable_is_null", "dataset_domain",
                "study_domains"))
        {
            Operation o = op(operator);
            o.setName("AGE");
            assertKindMatchesRuntime(o, t);
        }
        Operation unknown = new Operation();
        unknown.setId("$u");
        unknown.setOperator("no_such_operation");
        assertEquals(ResultKind.SCALAR, OperationExecutor.resultKind(unknown));
    }
}
