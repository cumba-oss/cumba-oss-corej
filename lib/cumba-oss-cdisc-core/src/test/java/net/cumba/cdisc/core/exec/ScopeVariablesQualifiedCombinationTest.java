package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Requirements;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Wave-22 lane C ({@code plans/done/PLAN-scope-variables-qualified-combination.md}): pins <b>how a
 * qualified {@code Requirements.Variables} entry combines with the rest of its list</b>, measured
 * end to end through {@link RuleRunner#execute} rather than through {@link ScopeMatcher} in
 * isolation.
 *
 * <p>
 * The design this gates (owner's Q17-b) declares a required foreign dataset as
 * {@code Requirements.Variables.All: ["DM.*"]} — <em>"DM has at least one variable"</em> ≡ <em>"DM
 * exists"</em>. That reading is only safe if the list combines with <b>AND</b>: adding
 * {@code "DM.*"} to a rule that already carries {@code ["AESTDY"]} must <em>narrow</em> the rule's
 * applicability, never widen it. An OR combination would silently enlarge every rule the design
 * touches — the shape that refuted the wave-20 manifest, where {@code Scope.Classes} and
 * {@code Scope.Data_Structures} turned out to be ANDed where an OR had been assumed.
 * </p>
 *
 * <p>
 * <b>Every probe is built as a matched pair</b>: the fixture differs in exactly the one fact under
 * test, and the two arms must land on opposite sides of the gate. A probe whose two arms agree pins
 * nothing, so a control arm accompanies each assertion rather than a bare one-way check.
 * </p>
 */
class ScopeVariablesQualifiedCombinationTest
{

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A trivial single-leaf rule ({@code AESTDY exists}) carrying the given variable scope. */
    private static Rule rule(@Nullable List<String> include, @Nullable List<String> exclude)
    {
        Rule r = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-QUALCOMBO");
        r.setCore(core);
        r.setCheck(CheckConditionLeaf.builder().name("AESTDY").operator("var_exists").build());
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(include);
        vr.setNone(exclude);
        Requirements req = new Requirements();
        req.setVariables(vr);
        r.setRequirements(req);
        return r;
    }


    /** The primary dataset under validation: AE, carrying USUBJID + AESTDY (and NOT AESTDTC). */
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


    private static RuleExecutionResult run(Rule r, DatasetResolver resolver)
    {
        return RuleRunner.execute(r, aeTable(), resolver, "AE", null);
    }


    private static void assertSkipped(RuleExecutionResult res, String reasonFragment)
    {
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus(),
                "expected the scope gate to skip; message was " + res.getStatusMessage());
        assertNotNull(res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains(reasonFragment),
                "skip reason should name " + reasonFragment + " but was " + res.getStatusMessage());
    }


    private static void assertRan(RuleExecutionResult res)
    {
        assertNotEquals(RuleExecutionStatus.SKIPPED, res.getStatus(),
                "expected the scope gate to pass; message was " + res.getStatusMessage());
    }


    // A DM that satisfies both a literal (ARM) and a glob (*) qualified entry.
    private static IDataTable dmWithArm()
    {
        return MockTable.of().name("DM").col("USUBJID", "S1").col("ARM", "A").build();
    }


    private static IDataTable exTable()
    {
        return MockTable.of().name("EX").col("USUBJID", "S1").col("EXDOSE", "5").build();
    }

    // ==================================================================
    // A1 — a QUALIFIED entry ANDs with an UNQUALIFIED one in the same list
    // ==================================================================


    /**
     * A1, arm 1: the unqualified half is satisfied (AE carries AESTDY) and the qualified half is
     * not (DM absent) ⇒ the rule is SKIPPED. Under an OR combination the satisfied unqualified
     * entry would carry the list and the rule would run.
     */
    @Test
    void a1_unqualifiedSatisfied_qualifiedNot_skips()
    {
        RuleExecutionResult res = run(rule(List.of("AESTDY", "DM.ARM"), null), inventory(map()));
        assertSkipped(res, "DM");

        // Control (the neuter): drop the qualified entry and the very same fixture must RUN.
        // Without this arm the assertion above could be satisfied by any unrelated skip.
        assertRan(run(rule(List.of("AESTDY"), null), inventory(map())));
    }


    /**
     * A1, arm 2: the qualified half is satisfied (DM.ARM present) and the unqualified half is not
     * (AE has no AESTDTC) ⇒ the rule is SKIPPED. Under an OR combination the satisfied qualified
     * entry would carry the list.
     */
    @Test
    void a1_qualifiedSatisfied_unqualifiedNot_skips()
    {
        RuleExecutionResult res = run(rule(List.of("AESTDTC", "DM.ARM"), null),
                inventory(map("DM", dmWithArm())));
        assertSkipped(res, "AESTDTC");

        // Control: drop the unsatisfied unqualified entry and the same fixture must RUN.
        assertRan(run(rule(List.of("DM.ARM"), null), inventory(map("DM", dmWithArm()))));
    }


    /**
     * A1, arm 3: both halves satisfied ⇒ the rule runs. With arms 1 and 2 this is the full truth
     * table for a two-entry list, and it reads AND, not OR.
     */
    @Test
    void a1_bothSatisfied_runs()
    {
        assertRan(run(rule(List.of("AESTDY", "DM.ARM"), null), inventory(map("DM", dmWithArm()))));
    }

    // ==================================================================
    // A2 — two QUALIFIED entries also AND
    // ==================================================================


    @Test
    void a2_twoQualified_secondMissing_skips()
    {
        RuleExecutionResult res = run(rule(List.of("DM.*", "EX.*"), null),
                inventory(map("DM", dmWithArm())));
        assertSkipped(res, "EX");

        // Control: with EX present as well, the identical rule runs.
        assertRan(run(rule(List.of("DM.*", "EX.*"), null),
                inventory(map("DM", dmWithArm(), "EX", exTable()))));
    }


    @Test
    void a2_twoQualified_firstMissing_skips()
    {
        assertSkipped(run(rule(List.of("DM.*", "EX.*"), null), inventory(map("EX", exTable()))),
                "DM");
    }

    // ==================================================================
    // A3 — "DM.*" as an existence guard: present => pass, absent => SKIP
    // ==================================================================


    @Test
    void a3_qualifiedGlob_datasetPresent_runs()
    {
        assertRan(run(rule(List.of("DM.*"), null), inventory(map("DM", dmWithArm()))));
    }


    @Test
    void a3_qualifiedGlob_datasetAbsent_skipsNamingTheDataset()
    {
        assertSkipped(run(rule(List.of("DM.*"), null), inventory(map())), "dataset DM");
    }


    /**
     * A3: the glob is evaluated against the FOREIGN dataset, never the primary. AE has columns, so
     * a leaked unqualified reading of {@code "*"} would pass; it must skip instead.
     */
    @Test
    void a3_qualifiedGlob_isNotSatisfiedByThePrimarysColumns()
    {
        // The inventory holds the primary AE and nothing else — "DM.*" must still skip.
        assertSkipped(run(rule(List.of("DM.*"), null), inventory(map("AE", aeTable()))),
                "dataset DM");
    }


    /**
     * A3: the dataset exists but carries no column matching the glob ⇒ SKIP with the "no variable
     * matching" wording rather than the "not available" one. Pins that "DM exists" and "DM has a
     * matching variable" are reported as distinct facts.
     */
    @Test
    void a3_qualifiedGlob_datasetPresentButNoMatchingColumn_skips()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        RuleExecutionResult res = run(rule(List.of("DM.AGE*"), null), inventory(map("DM", dm)));
        assertSkipped(res, "no variable matching");

        // Control: add the column the glob asks for and the same rule runs.
        IDataTable dmAge = MockTable.of().name("DM").col("USUBJID", "S1").col("AGEU", "YEARS")
                .build();
        assertRan(run(rule(List.of("DM.AGE*"), null), inventory(map("DM", dmAge))));
    }


    /**
     * A3: a two-character qualifier that no dataset is NAMED after still resolves through the
     * data-driven split-domain union ({@code DatasetResolver.WithInventory.tablesForDomain}), so
     * {@code "DM.*"} is satisfied by a member table whose {@code DOMAIN} cell is {@code DM}. The
     * design must not assume the study names its file {@code DM}.
     */
    @Test
    void a3_qualifiedGlob_resolvesViaSplitDomainUnion()
    {
        IDataTable dmMember = MockTable.of().name("dm1").col("DOMAIN", "DM").col("ARM", "A")
                .build();
        assertRan(run(rule(List.of("DM.*"), null), inventory(map("dm1", dmMember))));

        // Control: the same member carrying a DIFFERENT domain must not satisfy "DM.*".
        IDataTable notDm = MockTable.of().name("xx1").col("DOMAIN", "XX").col("ARM", "A").build();
        assertSkipped(run(rule(List.of("DM.*"), null), inventory(map("xx1", notDm))), "dataset DM");
    }


    /**
     * A3 trap: the qualifier is resolved through the RESOLVER, so naming the PRIMARY dataset in a
     * qualified entry is only satisfied when the primary is also reachable through the inventory. A
     * three-or-more-character qualifier gets no split-domain fallback at all
     * ({@code ScopeVariableSource.resolveMetas} bounds the walk to a 2-character domain code).
     */
    @Test
    void a3_qualifierLongerThanADomainCode_getsNoInventoryWalk()
    {
        IDataTable adsl = MockTable.of().name("adsl").col("DOMAIN", "ADSL").col("TRT01P", "A")
                .build();
        // "adsl" is in the inventory but not under the name ADSL, and ADSL is 4 chars, so the
        // tablesForDomain fallback is deliberately skipped.
        assertSkipped(run(rule(List.of("ADSL.*"), null), inventory(map("adsl", adsl))),
                "dataset ADSL");

        // Control: register it under the exact name and the same rule runs.
        assertRan(run(rule(List.of("ADSL.*"), null), inventory(map("ADSL", adsl))));
    }


    /**
     * A3, the load-bearing asymmetry: the SUPP-pivot back-door
     * ({@code ScopeVariableSource.existsViaSuppQnam}) is consulted only for a <b>literal</b>
     * variable half. So a study carrying {@code SUPPDM} but no {@code DM} satisfies
     * {@code "DM.USUBJID"} while {@code "DM.*"} still skips — the glob form is the stricter, and
     * therefore the more faithful, "the dataset itself exists" test.
     */
    @Test
    void a3_suppPivotBackDoorAppliesToLiteralsOnly()
    {
        IDataTable suppDm = MockTable.of().name("SUPPDM").col("USUBJID", "S1")
                .col("QNAM", "USUBJID").build();
        assertRan(run(rule(List.of("DM.USUBJID"), null), inventory(map("SUPPDM", suppDm))));
        assertSkipped(run(rule(List.of("DM.*"), null), inventory(map("SUPPDM", suppDm))),
                "dataset DM");
    }

    // ==================================================================
    // A4 — no inventory => the qualified entry is IGNORED (fails open)
    // ==================================================================


    /**
     * A4: a non-null resolver that is not {@link DatasetResolver.WithInventory} makes
     * {@code ScopeVariableSource.of} return null, and RuleRunner then ignores the qualified entry
     * entirely — the rule RUNS even though the dataset it declared as required is unreachable. The
     * design fails OPEN, not closed.
     */
    @Test
    void a4_resolverWithoutInventory_qualifiedGlobIgnored_ruleRuns()
    {
        assertRan(run(rule(List.of("DM.*"), null), _ -> null));

        // Control: the SAME entry against an inventory-capable resolver with no DM skips. The
        // difference is the resolver's TYPE alone, not the data.
        assertSkipped(run(rule(List.of("DM.*"), null), inventory(map())), "dataset DM");
    }


    /**
     * A4: fails-open is per-entry, not per-rule — the unqualified half of a mixed list still gates,
     * so an inventory-less run degrades a mixed rule to its primary-dataset half.
     */
    @Test
    void a4_resolverWithoutInventory_unqualifiedHalfStillGates()
    {
        assertSkipped(run(rule(List.of("AESTDTC", "DM.*"), null), _ -> null), "AESTDTC");
        assertRan(run(rule(List.of("AESTDY", "DM.*"), null), _ -> null));
    }

    // ==================================================================
    // A5 — Exclude is symmetric
    // ==================================================================


    @Test
    void a5_excludeQualifiedGlob_datasetPresent_skips()
    {
        assertSkipped(run(rule(null, List.of("DM.*")), inventory(map("DM", dmWithArm()))),
                "matches Requirements.Variables.None entry");
    }


    @Test
    void a5_excludeQualifiedGlob_datasetAbsent_runs()
    {
        assertRan(run(rule(null, List.of("DM.*")), inventory(map())));
    }


    /**
     * A5: Exclude ANDs its entries the same way Include does — any one matching entry skips, so the
     * list is a conjunction of negations.
     */
    @Test
    void a5_excludeTwoQualified_eitherPresent_skips()
    {
        assertSkipped(run(rule(null, List.of("DM.*", "EX.*")), inventory(map("EX", exTable()))),
                "EX");
        assertSkipped(run(rule(null, List.of("DM.*", "EX.*")), inventory(map("DM", dmWithArm()))),
                "DM");
        assertRan(run(rule(null, List.of("DM.*", "EX.*")), inventory(map())));
    }


    /**
     * A5: Include and Exclude themselves AND — an Include-satisfied rule still loses to Exclude.
     */
    @Test
    void a5_includeAndExcludeBothApply()
    {
        assertSkipped(run(rule(List.of("DM.*"), List.of("EX.*")),
                inventory(map("DM", dmWithArm(), "EX", exTable()))), "EX");
        assertRan(run(rule(List.of("DM.*"), List.of("EX.*")), inventory(map("DM", dmWithArm()))));
    }

    // ==================================================================
    // A6 — a bare "*" is an UNQUALIFIED glob and does not collide with "DM.*"
    // ==================================================================


    /**
     * A6: {@code "*"} carries no dot, so {@link ScopeVariableEntry#parse} leaves it unqualified and
     * it is a glob over the PRIMARY's columns — satisfied by any non-empty dataset. It has no "all
     * datasets" meaning that {@code "DM.*"} could shadow.
     */
    @Test
    void a6_bareStarIsAnUnqualifiedPrimaryGlob()
    {
        assertRan(run(rule(List.of("*"), null), inventory(map())));
        // and as an Exclude it rejects any dataset with at least one column
        assertSkipped(run(rule(null, List.of("*")), inventory(map())),
                "matches Requirements.Variables.None entry");
    }


    /**
     * A6: {@code --} resolution is confined to the unqualified leg. An unqualified {@code --*}
     * resolves the prefix and then globs ({@code --*} → {@code AE*}), while a qualified entry never
     * enters {@code resolveScopeVariable} at all, so the qualifier of {@code "DM.*"} cannot be
     * mangled by the primary's domain prefix.
     */
    @Test
    void a6_wildcardPrefixResolutionDoesNotReachTheQualifiedLeg()
    {
        // unqualified: --* resolves to AE* and AE carries AESTDY -> runs
        assertRan(run(rule(List.of("--*"), null), inventory(map())));
        // unqualified control: --X* resolves to AEX*, which AE does not carry -> skips
        assertSkipped(run(rule(List.of("--X*"), null), inventory(map())), "no variable matching");
        // qualified: the qualifier stays "DM" regardless of the primary being AE
        assertSkipped(run(rule(List.of("DM.*"), null), inventory(map())), "dataset DM");
    }


    /**
     * A6: {@code hasQualifiedVariableScope} is what triggers the foreign lookup at all, so it must
     * agree with the parse — a bare glob must NOT be treated as qualified.
     */
    @Test
    void a6_hasQualifiedVariableScopeAgreesWithTheParse()
    {
        assertFalse(ScopeMatcher.hasQualifiedVariableScope(rule(List.of("*"), null)),
                "a bare glob addresses the primary dataset");
        assertTrue(ScopeMatcher.hasQualifiedVariableScope(rule(List.of("DM.*"), null)),
                "a qualified glob needs the foreign source");
        assertTrue(ScopeMatcher.hasQualifiedVariableScope(rule(null, List.of("DM.*"))),
                "Exclude is inspected too");
    }

}
