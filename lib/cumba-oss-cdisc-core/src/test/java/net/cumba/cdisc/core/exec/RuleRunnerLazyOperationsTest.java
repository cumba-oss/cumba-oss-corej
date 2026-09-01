package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #36 — verifies the lazy {@link LazyValue} wrapping of Operation results in
 * {@link RuleRunner#execute}. The tests rely on the fact that {@code valid_codelist_dates}
 * delegates to {@link MetadataProvider#getPublishedCtPackages()} — a probe that only fires when the
 * wrapping {@link LazyValue} is forced. By mocking the provider we count how often the supplier
 * (and therefore the underlying {@code OperationExecutor.executeOne} dispatch) actually runs.
 */
class RuleRunnerLazyOperationsTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Test 1 — Fix #42 phase 1 (RuleRunner.java:315-340) eagerly force-loads every library-
     * dependent Operation before Check evaluation, so it can detect {@code LIBRARY_NOT_AVAILABLE}
     * and SKIP the rule. Even when the Check would fold to a dataset-level constant FALSE (so the
     * Operation result is never consulted), the supplier is still invoked exactly once. Lazy
     * short-circuiting (Fix #36) now applies only to non-library Operations; this test pins the
     * eager-force behavior so a future change can't silently regress the early-SKIP semantic for
     * library-dependent ops.
     */
    @Test
    void libraryDependentOperationIsForceLoadedEvenWhenCheckFoldsAtDatasetLevel()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02")
                .col("RFICDTC", "2024-01-01", "2024-02-01").build();

        AtomicInteger libraryCalls = new AtomicInteger();
        MetadataProvider provider = new CountingLibraryProvider(libraryCalls);

        Operation op = new Operation();
        op.setId("$codelist_dates");
        op.setOperator("valid_codelist_dates");

        // Dataset-level guard that folds to FALSE so the surrounding all collapses before
        // the row-level $codelist_dates leaf is ever consulted by CheckEvaluator.
        // `dataset_name equal_to "ZZZ"` against this MockTable (no name set) is FALSE.
        CheckConditionLeaf datasetGuard = CheckConditionLeaf.builder().name("dataset_name")
                .operator("equal_to").value(MAPPER.valueToTree("ZZZ_NEVER_MATCHES"))
                .valueIsLiteral(true).build();

        // Row-level leaf referencing the Operation. Under Fix #36 alone the supplier would
        // stay cold; Fix #42 phase 1 forces it eagerly during the LIBRARY_NOT_AVAILABLE
        // probe regardless of the Check shape.
        CheckConditionLeaf opLeaf = CheckConditionLeaf.builder().name("RFICDTC")
                .operator("is_not_contained_by").value(MAPPER.valueToTree("$codelist_dates"))
                .build();

        Rule rule = buildRule("CORE-LAZY-001", "library-dependent op force-loaded",
                new CheckConditionAll(List.of(datasetGuard, opLeaf)), List.of("USUBJID"));
        rule.setOperations(List.of(op));

        DatasetResolver resolver = _ -> null;
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver, null, provider);

        assertNotNull(result);
        assertEquals(0, result.getViolationCount(), "guard folds to FALSE → no violations");
        assertEquals(1, libraryCalls.get(),
                "Library-dependent Operations are force-loaded once under Fix #42 phase 1, "
                        + "even when the Check would fold at dataset level — pins the eager "
                        + "LIBRARY_NOT_AVAILABLE probe.");
    }


    /**
     * Test 2 — the rule's Check actually consults the Operation result from two separate leaves (an
     * existence guard plus the comparison). Memoisation means the supplier is forced exactly once
     * across both consumers.
     *
     * <p>
     * Note: the same {@code $codelist_dates} reference appears twice. The first leaf checks via
     * {@code is_not_contained_by} (forces); a second leaf in an {@code all} forces again, but the
     * cached value is returned without re-running the supplier.
     * </p>
     */
    @Test
    void operationRunsOnceAcrossMultipleCheckLeaves()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02")
                .col("RFICDTC", "2024-01-01", "2024-02-01")
                .col("DTHDTC", "2024-01-01", "2024-02-01").build();

        AtomicInteger libraryCalls = new AtomicInteger();
        MetadataProvider provider = new CountingLibraryProvider(libraryCalls);

        Operation op = new Operation();
        op.setId("$codelist_dates");
        op.setOperator("valid_codelist_dates");

        CheckConditionLeaf rficdtcLeaf = CheckConditionLeaf.builder().name("RFICDTC")
                .operator("is_not_contained_by").value(MAPPER.valueToTree("$codelist_dates"))
                .build();
        CheckConditionLeaf dthdtcLeaf = CheckConditionLeaf.builder().name("DTHDTC")
                .operator("is_not_contained_by").value(MAPPER.valueToTree("$codelist_dates"))
                .build();

        Rule rule = buildRule("CORE-LAZY-002", "two consumers of the same op",
                new CheckConditionAll(List.of(rficdtcLeaf, dthdtcLeaf)), List.of("USUBJID"));
        rule.setOperations(List.of(op));

        DatasetResolver resolver = _ -> null;
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver, null, provider);

        assertNotNull(result);
        assertEquals(1, libraryCalls.get(),
                "memoised supplier should fire exactly once across multiple consumers");
    }


    /**
     * Test 3 — the same Operation referenced by two separate rule executions runs once per
     * execution. Each rule run owns its own EvaluationContext and therefore its own LazyValue;
     * cross-rule caching is out of scope.
     */
    @Test
    void operationResultIsNotCachedAcrossRules()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02")
                .col("RFICDTC", "2024-01-01", "2024-02-01").build();

        AtomicInteger libraryCalls = new AtomicInteger();
        MetadataProvider provider = new CountingLibraryProvider(libraryCalls);

        Operation op = new Operation();
        op.setId("$codelist_dates");
        op.setOperator("valid_codelist_dates");

        CheckConditionLeaf opLeaf = CheckConditionLeaf.builder().name("RFICDTC")
                .operator("is_not_contained_by").value(MAPPER.valueToTree("$codelist_dates"))
                .build();

        Rule rule = buildRule("CORE-LAZY-003", "fresh op per rule run",
                new CheckConditionAll(List.of(opLeaf)), List.of("USUBJID"));
        rule.setOperations(List.of(op));

        DatasetResolver resolver = _ -> null;
        RuleRunner.execute(rule, table, resolver, null, provider);
        RuleRunner.execute(rule, table, resolver, null, provider);

        assertEquals(2, libraryCalls.get(),
                "each rule execution owns its own LazyValue → counter increments by 2");
    }


    /**
     * Test 4 — the supplier throws on the first read; the cached exception bubbles on every
     * subsequent read within the same rule run, and the supplier itself is never re-invoked.
     */
    @Test
    void supplierExceptionIsCachedWithinRule()
    {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        LazyValue<List<String>> bomb = new LazyValue<>(() ->
        {
            calls.incrementAndGet();
            throw new IllegalStateException("supplier exploded");
        });

        // Multiple reads from the same LazyValue mirror what ValueResolver / OperatorRegistry
        // do across multiple per-row evaluations. Each get() hits the cached exception.
        for (int i = 0; i < 5; i++)
        {
            assertThrows(IllegalStateException.class, () ->
            {
                reads.incrementAndGet();
                bomb.get();
            });
        }
        assertEquals(5, reads.get(), "all 5 reads attempted");
        assertEquals(1, calls.get(), "supplier must run exactly once even though it threw");
    }


    /**
     * Sanity bridge — when the rule has no Operations at all, the new lazy path is a no-op.
     * Confirms the wrapping doesn't interfere with the simple path.
     */
    @Test
    void noOperationsPath_unaffected()
    {
        IDataTable table = MockTable.of().col("SEX", "M", "X").build();

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();

        Rule rule = buildRule("CORE-LAZY-NOOP", "no ops", new CheckConditionAll(List.of(leaf)),
                List.of("SEX"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertEquals(1, result.getViolationCount());
    }


    /**
     * Rules referencing a library-dependent op without a provider must SKIP eagerly so downstream
     * readers never see a {@code LazyValue} resolving to the
     * {@link OperationExecutor#LIBRARY_NOT_AVAILABLE} sentinel.
     */
    @Test
    void libraryDependentOpWithoutProvider_skipsEagerly()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01").col("RFICDTC", "2024-01-01")
                .build();

        Operation op = new Operation();
        op.setId("$codelist_dates");
        op.setOperator("valid_codelist_dates");

        CheckConditionLeaf opLeaf = CheckConditionLeaf.builder().name("RFICDTC")
                .operator("is_not_contained_by").value(MAPPER.valueToTree("$codelist_dates"))
                .build();

        Rule rule = buildRule("CORE-LAZY-NOLIB", "library missing",
                new CheckConditionAll(List.of(opLeaf)), List.of("USUBJID"));
        rule.setOperations(List.of(op));

        RuleExecutionResult result = RuleRunner.execute(rule, table, _ -> null, null, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertNotNull(result.getStatusMessage());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static Rule buildRule(String coreId, String message, CheckConditionAll check,
            List<String> outputVars)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage(message);
        outcome.setOutputVariables(outputVars);
        rule.setOutcome(outcome);
        rule.setCheck(check);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static com.fasterxml.jackson.databind.node.ArrayNode arrayNode(String... values)
    {
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        for (String v : values)
        {
            arr.add(v);
        }
        return arr;
    }

    /**
     * A test {@link MetadataProvider} that counts how many times {@link #getPublishedCtPackages()}
     * is invoked. Returns a minimal package list shaped to exercise
     * {@link OperationExecutor#evalValidCodelistDates}.
     */
    private static final class CountingLibraryProvider implements MetadataProvider
    {

        private final AtomicInteger calls;

        CountingLibraryProvider(AtomicInteger calls)
        {
            this.calls = calls;
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getModelColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public java.util.Map<String, String> getDatasetMetadata(String domain)
        {
            return null;
        }


        @Override
        public java.util.Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return null;
        }


        @Override
        public List<java.util.Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<java.util.Map<String, String>> getModelVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getPublishedCtPackages()
        {
            calls.incrementAndGet();
            return List.of("sdtmct-2024-09-27");
        }


        @Override
        public List<String> getCodelistTerms(String codelistId)
        {
            return List.of();
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return false;
        }


        @Override
        public java.util.Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return java.util.Map.of();
        }


        @Override
        public String getStandard()
        {
            return "sdtmig";
        }


        @Override
        public String getVersion()
        {
            return "3-4";
        }
    }

}
