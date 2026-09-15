package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #124 end-to-end: {@link RuleRunner#execute} applies a <b>qualified</b>
 * {@code Requirements.Variables} entry ({@code DM.ARM}) against the foreign dataset reachable
 * through the {@link DatasetResolver}, reporting {@link RuleExecutionStatus#SKIPPED} with a reason
 * naming the dataset instead of evaluating the rule against an unresolved join.
 */
class RuleRunnerQualifiedVariableScopeTest
{

    /**
     * A trivial single-leaf rule ({@code AESTDY exists}) carrying the given variable requirement.
     */
    private static Rule ruleWithVarScope(@Nullable List<String> all, @Nullable List<String> none)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-QUALSCOPE");
        rule.setCore(core);
        rule.setCheck(CheckConditionLeaf.builder().name("AESTDY").operator("var_exists").build());
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(all);
        vr.setNone(none);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1").col("AESTDY", "3").build();
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : byName.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }


    private static Map<String, IDataTable> map(Object... pairs)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            m.put((String) pairs[i], (IDataTable) pairs[i + 1]);
        }
        return m;
    }


    private static RuleExecutionResult run(Rule rule, DatasetResolver resolver)
    {
        return RuleRunner.execute(rule, aeTable(), resolver, "AE", null);
    }

    // ------------------------------------------------------------------
    // All (formerly Include)
    // ------------------------------------------------------------------


    @Test
    void includeQualified_datasetAbsent_skipsWithReason()
    {
        RuleExecutionResult res = run(ruleWithVarScope(List.of("DM.ARM"), null), inventory(map()));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus());
        assertNotNull(res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("DM.ARM"), res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("dataset DM not available"),
                res.getStatusMessage());
        assertTrue(res.getViolations().isEmpty(), "a skipped rule reports no violations");
    }


    @Test
    void includeQualified_columnAbsent_skipsWithReason()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        RuleExecutionResult res = run(ruleWithVarScope(List.of("DM.ARM"), null),
                inventory(map("DM", dm)));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus());
        assertNotNull(res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("not present in dataset DM"),
                res.getStatusMessage());
    }


    @Test
    void includeQualified_columnPresent_ruleRuns()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        RuleExecutionResult res = run(ruleWithVarScope(List.of("DM.ARM"), null),
                inventory(map("DM", dm)));
        assertNotEquals(RuleExecutionStatus.SKIPPED, res.getStatus(),
                "the gate is satisfied, so the rule must execute");
    }

    // ------------------------------------------------------------------
    // None (formerly Exclude)
    // ------------------------------------------------------------------


    @Test
    void excludeQualified_columnPresent_skipsWithReason()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        RuleExecutionResult res = run(ruleWithVarScope(null, List.of("DM.ARM")),
                inventory(map("DM", dm)));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus());
        assertNotNull(res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("present in dataset DM"),
                res.getStatusMessage());
    }


    @Test
    void excludeQualified_datasetAbsent_ruleRuns()
    {
        RuleExecutionResult res = run(ruleWithVarScope(null, List.of("DM.ARM")), inventory(map()));
        assertNotEquals(RuleExecutionStatus.SKIPPED, res.getStatus(),
                "an absent dataset excludes nothing");
    }

    // ------------------------------------------------------------------
    // Resolver without an inventory — the plain-.cdt preview NO_RESOLVER shape
    // ------------------------------------------------------------------


    /**
     * ⭐ <b>Inverted 2026-09-10 by owner ruling</b> (disposition (b) of
     * {@code plans/PLAN-qualified-requirements-cross-standard.md} §8.4). It used to assert the
     * opposite — <i>"a resolver without an inventory must not skip the rule"</i> — on the grounds
     * that skipping would silence every qualified rule in the plain-{@code .cdt} preview. That
     * reasoning was measured and found to cost more than it saved: with the entry ignored, a rule
     * whose {@code Check}-side {@code var_exists(DM.ARM)} guard has been hoisted into
     * {@code Requirements} runs here with <b>nothing</b> in the guard's place and floods. A skip
     * the reader can see beats a flood nobody attributes.
     *
     * <p>
     * ⚠ The production validation path is unaffected either way: {@code LibraryValidator} builds a
     * {@code DatasetResolver.WithInventory}, and so does the {@code .cdt} scenario runner
     * ({@code ScenarioResolver}). This arm is reached only by a resolver that cannot enumerate.
     * </p>
     */
    @Test
    void bareResolver_qualifiedEntryUndecidable_ruleSkipsNamingTheResolver()
    {
        // A NON-null resolver that resolves nothing: ScopeVariableSource.of returns null for it,
        // so the qualified entry cannot be decided at all.
        RuleExecutionResult res = run(ruleWithVarScope(List.of("DM.ARM"), null), _ -> null);
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus(),
                "an undecidable qualified entry must skip the rule, not let it run unguarded");
        assertNotNull(res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("could not be decided"),
                "the reason must say it could not be decided: " + res.getStatusMessage());
        assertFalse(res.getStatusMessage().contains("not available"),
                "⛔ and must not claim the dataset was absent: " + res.getStatusMessage());
    }

    // ------------------------------------------------------------------
    // Mixed scope and the unqualified regression
    // ------------------------------------------------------------------


    @Test
    void unqualifiedEntriesStillGateOnThePrimary()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        RuleExecutionResult res = run(ruleWithVarScope(List.of("AESTDTC", "DM.ARM"), null),
                inventory(map("DM", dm)));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus());
        assertNotNull(res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("AESTDTC"),
                "the primary-dataset half still applies: " + res.getStatusMessage());
    }


    @Test
    void qualifiedEntryIsNotSatisfiedByAPrimaryColumnOfTheSameName()
    {
        // The primary AE carries AESTDY; `DM.AESTDY` must be looked up in DM, not in AE.
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        RuleExecutionResult res = run(ruleWithVarScope(List.of("DM.AESTDY"), null),
                inventory(map("DM", dm)));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus(),
                "the qualifier must direct the lookup away from the primary");
    }

}
