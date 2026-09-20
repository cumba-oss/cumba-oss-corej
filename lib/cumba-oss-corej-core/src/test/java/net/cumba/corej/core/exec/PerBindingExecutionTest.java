package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 of {@code plans/PLAN-typed-expression-engine.md} — per-binding execution (D8/D41/D41b)
 * and the hoisting of binding-invariant subexpressions (D92e):
 * <ul>
 * <li>{@link BindingScope} is the sole dispatch's derivation, transcribed unchanged;</li>
 * <li>the variable-cursor loops carry a {@link BindingLedger} inside the one result per (rule,
 * dataset) — bindings iterated, plus a sparse outcome per noteworthy binding;</li>
 * <li>a pure subtree beside the cursor evaluates once per loop, not once per binding, and a
 * {@code $}-operation aggregate (the D92e flagship population) executes once per execution — both
 * pinned by read counts that must not grow with the number of bindings.</li>
 * </ul>
 */
class PerBindingExecutionTest
{

    private static Rule load(String id, String check, String extra) throws Exception
    {
        String body = "{\"Core\":{\"Id\":\"" + id + "\"}," + extra + "\"Scope\":{\"Domains\":{"
                + "\"Include\":[\"EX\"]}},\"Check\":{\"expression\":\""
                + check.replace("\"", "\\\"") + "\"},\"Outcome\":{\"Message\":\"m\"}}";
        return RulePackageLoader.loadFromString("{\"rules\":{\"" + id + "\":" + body + "}}")
                .getRules().get(id);
    }


    private static IDataTable ex()
    {
        return MockTable.of().name("EX").col("EXDOSE", "1", "0", "2").col("EXTRT", "A", "", "C")
                .colMeta("EXDOSE", "Dose", 8, "").colMeta("EXTRT", "Treatment", 40, "").build();
    }


    private static DatasetResolver with(IDataTable... tables)
    {
        return name ->
        {
            for (IDataTable t : tables)
            {
                if (t.getMetaData().getName().equals(name))
                {
                    return t;
                }
            }
            return null;
        };
    }

    // ------------------------------------------------------------------
    // BindingScope — the dispatch derivation
    // ------------------------------------------------------------------


    @Test
    void bindingScopeTranscribesTheLeafScopeDispatch() throws Exception
    {
        Rule cell = load("BS1", "value() != \"\"", "");
        assertEquals(BindingScope.PER_VARIABLE_ROW,
                BindingScope.of(Domain.CELL, cell.getCheckExpr()));
        Rule variable = load("BS2", "var_label(\"DATA\") != \"\"", "");
        assertEquals(BindingScope.PER_VARIABLE,
                BindingScope.of(Domain.VARIABLE, variable.getCheckExpr()));
        Rule metadata = load("BS3", "ds_label(\"DATA\") == \"x\"", "");
        assertEquals(BindingScope.BROADCAST_METADATA,
                BindingScope.of(Domain.DATASET, metadata.getCheckExpr()));
        Rule row = load("BS4", "EXTRT == \"A\"", "");
        assertEquals(BindingScope.ROW_PATH, BindingScope.of(Domain.ROW, row.getCheckExpr()));
        // A {} Check that is not pure metadata takes the row path and collapses — not broadcast.
        Rule presence = load("BS5", "ds_exists(\"SUPPAE\") and var_exists(\"EX.EXDOSE\")", "");
        assertEquals(BindingScope.ROW_PATH,
                BindingScope.of(Domain.DATASET, presence.getCheckExpr()));
    }

    // ------------------------------------------------------------------
    // The ledger — D41's carrier
    // ------------------------------------------------------------------


    @Test
    void perVariableExecutionCarriesOneOutcomePerFiringBinding() throws Exception
    {
        Rule r = load("PB1", "var_label(\"DATA\") != \"\"", "\"Sensitivity\":\"Record\",");
        RuleExecutionResult res = RuleRunner.execute(r, ex(), with(ex()));
        BindingLedger ledger = res.getBindings();
        assertNotNull(ledger, "a variable-cursor execution must carry the ledger");
        assertEquals(2, ledger.iterated());
        assertEquals(List.of("EXDOSE", "EXTRT"),
                ledger.outcomes().stream().map(BindingOutcome::variable).toList());
        assertTrue(ledger.outcomes().stream().allMatch(
                o -> o.status() == RuleExecutionStatus.EXECUTED && o.violationCount() == 1));
    }


    @Test
    void silentBindingsCountTowardIteratedOnly() throws Exception
    {
        Rule r = load("PB2", "var_label(\"DATA\") == \"NOPE\"", "\"Sensitivity\":\"Record\",");
        RuleExecutionResult res = RuleRunner.execute(r, ex(), with(ex()));
        BindingLedger ledger = res.getBindings();
        assertNotNull(ledger);
        assertEquals(2, ledger.iterated());
        assertEquals(List.of(), ledger.outcomes(), "silent bindings carry no outcome — sparse");
    }


    @Test
    void datasetSensitivityStopsTheLoopAtTheFirstFiringBinding() throws Exception
    {
        Rule r = load("PB3", "var_label(\"DATA\") != \"\"", "\"Sensitivity\":\"Dataset\",");
        RuleExecutionResult res = RuleRunner.execute(r, ex(), with(ex()));
        BindingLedger ledger = res.getBindings();
        assertNotNull(ledger);
        assertEquals(1, ledger.iterated(), "the collapse stops after the first firing binding");
        assertEquals(1, ledger.outcomes().size());
        assertEquals("EXDOSE", ledger.outcomes().getFirst().variable());
    }


    @Test
    void perVariableRowExecutionCountsViolationsPerBinding() throws Exception
    {
        // Each column's non-empty cells at rows where EXTRT == "A" (row 0): EXDOSE fires at row 0
        // ("1"), EXTRT fires at row 0 ("A").
        Rule r = load("PB4", "value() != \"\" and EXTRT == \"A\"", "\"Sensitivity\":\"Record\",");
        assertEquals(Domain.CELL, r.getEvaluationDomain());
        RuleExecutionResult res = RuleRunner.execute(r, ex(), with(ex()));
        assertEquals(2, res.getViolationCount());
        BindingLedger ledger = res.getBindings();
        assertNotNull(ledger);
        assertEquals(2, ledger.iterated());
        assertEquals(List.of("EXDOSE", "EXTRT"),
                ledger.outcomes().stream().map(BindingOutcome::variable).toList());
        assertTrue(ledger.outcomes().stream().allMatch(o -> o.violationCount() == 1));
    }


    @Test
    void nonCursorPathsCarryNoLedger() throws Exception
    {
        Rule row = load("PB5", "EXTRT == \"A\"", "\"Sensitivity\":\"Record\",");
        assertNull(RuleRunner.execute(row, ex(), with(ex())).getBindings());
        Rule broadcast = load("PB6", "ds_label(\"DATA\") == \"x\"", "");
        assertNull(RuleRunner.execute(broadcast, ex(), with(ex())).getBindings(),
                "the broadcast binding is the dataset — the result itself is its carrier");
    }

    // ------------------------------------------------------------------
    // Hoisting — D92e
    // ------------------------------------------------------------------


    /** A table whose {@code getValue} reads of one column are counted. */
    private static IDataTable counting(IDataTable inner, String column, AtomicInteger reads)
    {
        int watched = inner.getMetaData().getColumnIndex(column);
        return new IDataTable()
        {

            @Override
            public DataTableMeta getMetaData()
            {
                return inner.getMetaData();
            }


            @Override
            public long getRowCount()
            {
                return inner.getRowCount();
            }


            @Override
            public @Nullable Object getValue(long aRow, int aColumn)
            {
                if (aColumn == watched)
                {
                    reads.incrementAndGet();
                }
                return inner.getValue(aRow, aColumn);
            }
        };
    }


    private static IDataTable flags(int extraColumns)
    {
        // EXFIRE fires exactly once (value() == "Y" at row 1, the one row where TRTEMFL != "Y");
        // the extra columns never fire, so the violation count — and with it the per-violation
        // output extraction, which also reads cells — is CONSTANT across widths. Only then does
        // read-count equality isolate the hoist.
        var b = MockTable.of().name("EX").col("TRTEMFL", "Y", "N", "Y").col("EXFIRE", "N", "Y",
                "N");
        for (int i = 0; i < extraColumns; i++)
        {
            b = b.col("EXC" + i + "FL", "N", "N", "N");
        }
        return b.build();
    }


    @Test
    void aPureConjunctIsHoistedAcrossTheBindingLoop() throws Exception
    {
        // The AD0647 shape: a cursor read beside a pure column conjunct. The pure conjunct's
        // column reads must NOT grow with the number of bindings the loop iterates — the memo
        // serves every binding after the first. The cursor's own visit to TRTEMFL (value() reads
        // it when TRTEMFL is the current variable) is identical in both runs, so equality pins
        // exactly the hoist.
        String check = "value() == \"Y\" and TRTEMFL != \"Y\"";
        Rule narrow = load("PH1", check, "\"Sensitivity\":\"Record\",");
        assertEquals(Domain.CELL, narrow.getEvaluationDomain());
        AtomicInteger narrowReads = new AtomicInteger();
        IDataTable narrowTable = counting(flags(2), "TRTEMFL", narrowReads);
        RuleExecutionResult narrowRes = RuleRunner.execute(narrow, narrowTable, with(narrowTable));
        assertEquals(RuleExecutionStatus.EXECUTED, narrowRes.getStatus());

        Rule wide = load("PH2", check, "\"Sensitivity\":\"Record\",");
        AtomicInteger wideReads = new AtomicInteger();
        IDataTable wideTable = counting(flags(8), "TRTEMFL", wideReads);
        RuleExecutionResult wideRes = RuleRunner.execute(wide, wideTable, with(wideTable));

        // Correctness first: only EXFIRE fires (row 1), whatever the width.
        assertEquals(1, narrowRes.getViolationCount());
        assertEquals(1, wideRes.getViolationCount());
        assertEquals(narrowReads.get(), wideReads.get(),
                "the pure conjunct must be evaluated once per loop — its reads may not grow "
                        + "with the binding count (narrow=" + narrowReads.get() + ", wide="
                        + wideReads.get() + ")");
    }


    @Test
    void theHoistDeclinesWhenAColumnCollidesWithTheCursorKey() throws Exception
    {
        // A dataset column literally named `variable_name` would be shadowed per binding by the
        // injected cursor key, so arming is declined — and the verdicts stay correct.
        IDataTable odd = MockTable.of().name("EX").col("TRTEMFL", "Y", "N")
                .col("variable_name", "a", "b").build();
        Rule r = load("PH3", "value() == \"Y\" and TRTEMFL != \"Y\"",
                "\"Sensitivity\":\"Record\",");
        RuleExecutionResult res = RuleRunner.execute(r, odd, with(odd));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(0, res.getViolationCount(),
                "no cell is 'Y' on a row where TRTEMFL != 'Y' except TRTEMFL's own — which the "
                        + "conjunct excludes");
    }


    @Test
    void aBindingInvariantOperationAggregateExecutesOncePerExecution() throws Exception
    {
        // The D92e flagship shape: a cursor read compared against a binding-invariant
        // cross-dataset aggregate. The operation is binding-invariant; the LazyValue memoisation
        // is what hoists it, and this pin holds that fact against regression: the foreign
        // dataset's resolution count may not grow with the number of bindings. (`distinct` rather
        // than the library-backed `variable_names`, so no provider gate interferes.)
        String extra = "\"Sensitivity\":\"Record\",\"Bindings\":[{\"name\": \"$dm_arms\", \"expression\": \"distinct(ARM, domain=\\\"DM\\\")\"}],";
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").col("ARM", "A").build();

        AtomicInteger narrowResolves = new AtomicInteger();
        Rule narrow = load("PO1", "varname() not in $dm_arms", extra);
        IDataTable narrowEx = flags(2);
        RuleExecutionResult narrowRes = RuleRunner.execute(narrow, narrowEx,
                countingResolver(narrowResolves, narrowEx, dm));
        assertEquals(RuleExecutionStatus.EXECUTED, narrowRes.getStatus());
        assertEquals(4, narrowRes.getViolationCount(), "4 EX columns, none of them a DM arm");

        AtomicInteger wideResolves = new AtomicInteger();
        Rule wide = load("PO2", "varname() not in $dm_arms", extra);
        IDataTable wideEx = flags(8);
        RuleExecutionResult wideRes = RuleRunner.execute(wide, wideEx,
                countingResolver(wideResolves, wideEx, dm));
        assertEquals(10, wideRes.getViolationCount());
        assertEquals(narrowResolves.get(), wideResolves.get(),
                "the aggregate executes once per (rule × dataset), never per binding");
    }


    private static DatasetResolver countingResolver(AtomicInteger dmResolves, IDataTable... tables)
    {
        DatasetResolver inner = with(tables);
        return name ->
        {
            if ("DM".equals(name))
            {
                dmResolves.incrementAndGet();
            }
            return inner.resolve(name);
        };
    }


    @Test
    void armingDeclinesOnEachGuard()
    {
        // Guard 1: a dataset column named like the injected cursor key.
        IDataTable shadowed = MockTable.of().name("EX").col("variable_name", "a").build();
        EvaluationContext ctx1 = EvaluationContext.builder().table(shadowed).build();
        assertNull(RuleRunner.armBindingHoist(ctx1).getBindingHoist());
        // Guard 2: a context-variable key without the $ prefix.
        IDataTable plain = MockTable.of().name("EX").col("TRTEMFL", "Y").build();
        EvaluationContext ctx2 = EvaluationContext.builder().table(plain)
                .variables(java.util.Map.of("oddkey", 1)).build();
        assertNull(RuleRunner.armBindingHoist(ctx2).getBindingHoist());
        // The ordinary case arms.
        EvaluationContext ctx3 = EvaluationContext.builder().table(plain)
                .variables(java.util.Map.of("$ok", 1)).build();
        assertNotNull(RuleRunner.armBindingHoist(ctx3).getBindingHoist());
    }

}
