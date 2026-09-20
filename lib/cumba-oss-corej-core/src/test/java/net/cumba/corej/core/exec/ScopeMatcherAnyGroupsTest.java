package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code Requirements.Variables.Any} as <b>groups</b> — an AND of ORs — at
 * {@code ScopeMatcher.describeVariablesMismatch} ({@code plans/done/PLAN-any-variable-sets.md}
 * phase 2).
 *
 * <p>
 * ⚠⚠ <b>The group-2-unmet fixture is the load-bearing deliverable of the whole plan</b> (§5.1
 * N1/N2): an implementation that flattens the groups — {@code anyUnion()} instead of iterating, or
 * answering on group 1 alone — satisfies "one of the union" on any fixture whose group 1 is
 * satisfiable, so only a fixture that satisfies group 1 and empties group 2 can tell the feature
 * from its absence. The single-group behaviour (short-circuit, message, entry vocabulary) is
 * {@code ScopeMatcherRequirementsTest}'s and unchanged.
 * </p>
 */
class ScopeMatcherAnyGroupsTest
{

    /** The carrier shape (§1.1): start trio in group 1, end trio in group 2. */
    private static final List<String> START = List.of("DSSTDTC", "DSSTRF");

    private static final List<String> END = List.of("DSENDTC", "DSENRF");

    private static Rule ruleWithAnyGroups(List<List<String>> groups)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-ANY-GROUPS");
        rule.setCore(core);
        VariableRequirement vars = new VariableRequirement();
        vars.setAnyGroups(groups);
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);
        return rule;
    }


    private static DataTableMeta meta(String... columns)
    {
        MockTable t = MockTable.of().name("DS");
        for (String c : columns)
        {
            t = t.col(c, "");
        }
        return t.build().getMetaData();
    }


    private static @Nullable String describe(Rule rule, DataTableMeta meta)
    {
        return ScopeMatcher.describeVariablesMismatch(rule, meta, null, null);
    }

    @Nested
    @DisplayName("the groups are ANDed — each must be satisfied on its own")
    class Anding
    {

        @Test
        @DisplayName("one entry from EACH group satisfies the facet")
        void bothGroupsSatisfied()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, END));
            assertNull(describe(rule, meta("DSSTRF", "DSENDTC")));
        }


        @Test
        @DisplayName("group 1 unmet is a mismatch naming group 1")
        void group1Unmet()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, END));
            String reason = describe(rule, meta("DSENDTC", "DSENRF"));
            assertEquals("no variable of Requirements.Variables.Any group 1 [DSSTDTC, DSSTRF]"
                    + " present in dataset", reason);
        }


        /**
         * ⭐ <b>The mirror fixture — the plan's load-bearing deliverable.</b> Group 1 is satisfied
         * and group 2 is empty: a flattened implementation ("one of the union") answers "satisfied"
         * here, so this is the test that distinguishes the feature from its absence (negative
         * controls N1/N2).
         */
        @Test
        @DisplayName("⭐ group 2 unmet is a mismatch — even with group 1 fully satisfied")
        void group2Unmet()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, END));
            String reason = describe(rule, meta("DSSTDTC", "DSSTRF"));
            assertEquals(
                    "no variable of Requirements.Variables.Any group 2 [DSENDTC, DSENRF]"
                            + " present in dataset",
                    reason,
                    "a flattening implementation reports null here — satisfied by the union");
        }


        @Test
        @DisplayName("both groups unmet: D5 — the FIRST unmet group is the one named")
        void firstUnmetGroupWins()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, END));
            String reason = describe(rule, meta("DSSEQ"));
            assertNotNull(reason);
            assertTrue(reason.contains("group 1 [DSSTDTC, DSSTRF]"), reason);
            assertFalse(reason.contains("group 2"), reason);
        }


        @Test
        @DisplayName("three groups: the middle one alone can fail the facet")
        void threeGroupsMiddleUnmet()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, List.of("DSDECOD", "DSTERM"), END));
            String reason = describe(rule, meta("DSSTDTC", "DSENDTC"));
            assertNotNull(reason);
            assertTrue(reason.contains("group 2 [DSDECOD, DSTERM]"), reason);
        }


        @Test
        @DisplayName("a group satisfied only by its LAST entry is satisfied — per-group M3-J.5")
        void groupSatisfiedByLastEntry()
        {
            Rule rule = ruleWithAnyGroups(List.of(List.of("DSSTDTC", "DSSTRF", "DSSTRTPT"), END));
            assertNull(describe(rule, meta("DSSTRTPT", "DSENRF")),
                    "the within-group iteration must get past every earlier absent entry");
        }


        @Test
        @DisplayName("`--` entries resolve inside each group, exactly as in a flat Any")
        void domainPrefixEntriesInsideGroups()
        {
            Rule rule = ruleWithAnyGroups(
                    List.of(List.of("--STDTC", "--STRF"), List.of("--ENDTC", "--ENRF")));
            assertNull(ScopeMatcher.describeVariablesMismatch(rule, meta("DSSTRF", "DSENDTC"), "DS",
                    null));
            assertNotNull(ScopeMatcher.describeVariablesMismatch(rule, meta("DSSTRF"), "DS", null));
        }
    }


    @Nested
    @DisplayName("interaction with All / None — unchanged ANDing across facets")
    class OtherFacets
    {

        @Test
        @DisplayName("a satisfied Any does not rescue an unmet All, and vice versa")
        void allAndAnyGroupsAreAnded()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, END));
            VariableRequirement vars = rule.getRequirements().getVariables();
            assertNotNull(vars);
            vars.setAll(List.of("DSDECOD"));
            assertNull(describe(rule, meta("DSDECOD", "DSSTDTC", "DSENDTC")));
            String allReason = describe(rule, meta("DSSTDTC", "DSENDTC"));
            assertNotNull(allReason);
            assertTrue(allReason.contains("Requirements.Variables.All"), allReason);
            String anyReason = describe(rule, meta("DSDECOD", "DSSTDTC"));
            assertNotNull(anyReason);
            assertTrue(anyReason.contains("Any group 2"), anyReason);
        }


        @Test
        @DisplayName("a violated None rejects even when every Any group is satisfied")
        void noneStillRejects()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, END));
            VariableRequirement vars = rule.getRequirements().getVariables();
            assertNotNull(vars);
            vars.setNone(List.of("POOLID"));
            String reason = describe(rule, meta("DSSTDTC", "DSENDTC", "POOLID"));
            assertNotNull(reason);
            assertTrue(reason.contains("None"), reason);
        }
    }


    @Nested
    @DisplayName("qualified entries — the per-group residual and the per-group undecidable state")
    class Qualified
    {

        /**
         * The {@code IGNORE} residual, narrowed: one qualified entry makes <em>that group</em>
         * vacuously satisfied under {@code foreign == null}, no longer the whole facet — an
         * all-unqualified sibling group still decides for itself.
         */
        @Test
        @DisplayName("IGNORE: a qualified entry satisfies only ITS group — group 2 still decides")
        void ignoreResidualIsPerGroup()
        {
            Rule rule = ruleWithAnyGroups(List.of(List.of("DM.ARM", "DSSTDTC"), END));
            String reason = describe(rule, meta("DSSEQ"));
            assertNotNull(reason,
                    "pre-groups, ONE qualified entry vacuously satisfied the WHOLE facet; with"
                            + " groups it must satisfy only group 1");
            assertTrue(reason.contains("group 2 [DSENDTC, DSENRF]"), reason);
        }


        /**
         * ⚠⚠ The undecidable memory is <b>per group</b>: an undecidable qualified entry in a
         * <em>satisfied</em> group 1 must not decorate group 2's genuinely-absent answer — the
         * report would then say "could not be decided" about columns that were looked for and are
         * simply not there.
         */
        @Test
        @DisplayName("SKIP: group 1's undecidable entry does not leak into group 2's absence")
        void undecidableMemoryIsPerGroup()
        {
            Rule rule = ruleWithAnyGroups(List.of(List.of("DM.ARM", "DSSTDTC"), END));
            String reason = ScopeMatcher.describeVariablesMismatch(rule, meta("DSSTDTC"), null,
                    null, ScopeMatcher.QualifiedEntryPolicy.SKIP);
            assertEquals(
                    "no variable of Requirements.Variables.Any group 2 [DSENDTC, DSENRF]"
                            + " present in dataset",
                    reason, "group 1 is satisfied by DSSTDTC; group 2 is absent, not undecidable");
        }


        /**
         * The other half: a group that cannot be decided must NOT be reported as absent — "I could
         * not look" and "I looked and it was not there" are one word apart in a log and mean
         * different things (see {@code undecidableQualifiedReason}).
         */
        @Test
        @DisplayName("SKIP: an undecidable group reports the undecidable reason, never 'absent'")
        void anUndecidableGroupIsNotReportedAsAbsent()
        {
            Rule rule = ruleWithAnyGroups(List.of(START, List.of("DM.ARM", "DSENDTC")));
            String reason = ScopeMatcher.describeVariablesMismatch(rule, meta("DSSTDTC"), null,
                    null, ScopeMatcher.QualifiedEntryPolicy.SKIP);
            assertNotNull(reason);
            assertTrue(reason.contains("could not be decided"), reason);
            assertTrue(reason.contains("DM.ARM"), reason);
            assertFalse(reason.contains("present in dataset"),
                    "an undecidable group must not masquerade as an absent one: " + reason);
        }
    }
}
