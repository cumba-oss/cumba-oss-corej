package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code Requirements.Variables.All_Or_None} at {@code ScopeMatcher.describeVariablesMismatch}
 * ({@code plans/PLAN-join-key-pairing.md}, owner ruling D1 (A) of 2026-09-25): each group is
 * satisfied when <b>all</b> of its entries are present or <b>none</b> is, and a partly present
 * group is a SKIP naming both halves.
 *
 * <p>
 * The load-bearing fixture is the plan's §1 <b>four-row table</b>: {@code VISITDY} is {@code Perm}
 * in SV and in TV in every SDTMIG, so a study may carry it on both sides, on neither, or on one.
 * Rows 1–2 must run; rows 3–4 (one side only — the shape on which the join matches nothing and
 * every planned visit is reported) must skip. An {@code All} gate cannot express that: declaring
 * the column skips row 2, leaving it undeclared floods rows 3–4.
 * </p>
 *
 * <p>
 * ⚠ The rule under test carries the facet on a hand-built rule only — by the same ruling the engine
 * merely makes the skip <em>expressible</em>; whether {@code FDA-SD1018} adopts it is the authoring
 * gate's decision, not this plan's.
 * </p>
 */
class ScopeMatcherAllOrNoneTest
{

    /** The plan's own example: the VISITDY pairing of SV against TV. */
    private static final List<List<String>> VISITDY_PAIR = List
            .of(List.of("VISITDY", "TV.VISITDY"));

    private static Rule ruleWithGroups(List<List<String>> groups)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-ALL-OR-NONE");
        rule.setCore(core);
        VariableRequirement vars = new VariableRequirement();
        vars.setAllOrNoneGroups(groups);
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);
        return rule;
    }


    private static IDataTable table(String name, String... columns)
    {
        MockTable t = MockTable.of().name(name);
        for (String c : columns)
        {
            t = t.col(c, "");
        }
        return t.build();
    }


    private static IDataTable sv(String... columns)
    {
        return table("SV", columns);
    }


    private static IDataTable tv(String... columns)
    {
        return table("TV", columns);
    }


    /** A {@code WithInventory} resolver over the given name → table map (exact-name lookup). */
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


    private static Map<String, IDataTable> map(IDataTable... tables)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (IDataTable t : tables)
        {
            m.put(t.getMetaData().getName(), t);
        }
        return m;
    }


    private static ScopeVariableSource sourceOf(IDataTable primary, IDataTable... foreign)
    {
        ScopeVariableSource src = ScopeVariableSource.of(inventory(map(foreign)), primary);
        assertNotNull(src, "an inventory-capable resolver must yield a source");
        return src;
    }


    /** The primary's own name is its {@code --} prefix, as the runner passes it. */
    private static @Nullable String check(Rule rule, IDataTable primary, IDataTable... foreign)
    {
        return ScopeMatcherCalls.describeVariablesMismatch(rule, primary.getMetaData(),
                primary.getMetaData().getName(), sourceOf(primary, foreign));
    }

    @Nested
    @DisplayName("§1 — the four-row table for a key optional on both sides")
    class FourRows
    {

        @Test
        @DisplayName("row 1 — present on both sides: the rule runs")
        void presentPresent()
        {
            assertNull(check(ruleWithGroups(VISITDY_PAIR), sv("VISITNUM", "VISIT", "VISITDY"),
                    tv("VISITNUM", "VISIT", "VISITDY")));
        }


        @Test
        @DisplayName("row 2 — absent on both sides: the rule runs (R7 drops the component)")
        void absentAbsent()
        {
            assertNull(
                    check(ruleWithGroups(VISITDY_PAIR), sv("VISITNUM", "VISIT"),
                            tv("VISITNUM", "VISIT")),
                    "an All gate would skip here — that is why the facet exists");
        }


        /**
         * ⭐ The common real-world shape: TV carries the planned study day, SV omits it. Today, with
         * nothing declared, the join contributes SV's type default, no TV row matches, and every
         * planned SV record is reported.
         */
        @Test
        @DisplayName("⭐ row 3 — SV without, TV with: SKIP naming both halves")
        void absentPresent()
        {
            String reason = check(ruleWithGroups(VISITDY_PAIR), sv("VISITNUM", "VISIT"),
                    tv("VISITNUM", "VISIT", "VISITDY"));
            assertEquals("Requirements.Variables.All_Or_None group 1 [VISITDY, TV.VISITDY] is only"
                    + " partly present — present: [TV.VISITDY], absent: [VISITDY]", reason);
        }


        @Test
        @DisplayName("row 4 — SV with, TV without: SKIP, the halves swapped")
        void presentAbsent()
        {
            String reason = check(ruleWithGroups(VISITDY_PAIR), sv("VISITNUM", "VISIT", "VISITDY"),
                    tv("VISITNUM", "VISIT"));
            assertEquals("Requirements.Variables.All_Or_None group 1 [VISITDY, TV.VISITDY] is only"
                    + " partly present — present: [VISITDY], absent: [TV.VISITDY]", reason);
        }


        @Test
        @DisplayName("no TV dataset at all: the foreign half is absent, so the pairing decides on"
                + " the primary alone")
        void foreignDatasetMissing()
        {
            assertNull(check(ruleWithGroups(VISITDY_PAIR), sv("VISITNUM", "VISIT")),
                    "absent on both sides — whether the join itself is required is"
                            + " Requirements.Datasets' question, not this facet's");
            String reason = check(ruleWithGroups(VISITDY_PAIR), sv("VISITNUM", "VISIT", "VISITDY"));
            assertNotNull(reason);
            assertTrue(reason.contains("absent: [TV.VISITDY]"), reason);
        }
    }


    @Nested
    @DisplayName("groups are ANDed — the first unmet group is the one named")
    class Groups
    {

        private final List<List<String>> two = List.of(VISITDY_PAIR.get(0),
                List.of("VISIT", "TV.VISIT"));

        @Test
        @DisplayName("both groups satisfied — one all-present, one all-absent")
        void bothSatisfied()
        {
            assertNull(check(ruleWithGroups(two), sv("VISITNUM", "VISITDY"),
                    tv("VISITNUM", "VISITDY")));
        }


        /** The mirror fixture: group 1 fine, group 2 mixed — a group-1-only reader answers null. */
        @Test
        @DisplayName("⭐ group 2 mixed is a mismatch naming group 2, with group 1 satisfied")
        void group2Mixed()
        {
            String reason = check(ruleWithGroups(two), sv("VISITNUM", "VISITDY", "VISIT"),
                    tv("VISITNUM", "VISITDY"));
            assertNotNull(reason, "a reader that answers on group 1 alone reports null here");
            assertTrue(
                    reason.startsWith(
                            "Requirements.Variables.All_Or_None group 2 [VISIT," + " TV.VISIT]"),
                    reason);
            assertTrue(reason.contains("present: [VISIT], absent: [TV.VISIT]"), reason);
        }


        @Test
        @DisplayName("both groups mixed: group 1 wins, group 2 is not mentioned")
        void firstUnmetWins()
        {
            String reason = check(ruleWithGroups(two), sv("VISITNUM", "VISITDY", "VISIT"),
                    tv("VISITNUM"));
            assertNotNull(reason);
            assertTrue(reason.contains("group 1 [VISITDY, TV.VISITDY]"), reason);
            assertFalse(reason.contains("group 2"), reason);
        }


        @Test
        @DisplayName("a three-entry group needs all three or none")
        void threeEntries()
        {
            Rule rule = ruleWithGroups(List.of(List.of("VISITDY", "TV.VISITDY", "SUPPSV.QNAM")));
            assertNull(check(rule, sv("VISITNUM"), tv("VISITNUM"), table("SUPPSV", "QVAL")));
            assertNull(check(rule, sv("VISITNUM", "VISITDY"), tv("VISITNUM", "VISITDY"),
                    table("SUPPSV", "QNAM")));
            String reason = check(rule, sv("VISITNUM", "VISITDY"), tv("VISITNUM", "VISITDY"),
                    table("SUPPSV", "QVAL"));
            assertNotNull(reason);
            assertTrue(reason.contains("present: [VISITDY, TV.VISITDY], absent: [SUPPSV.QNAM]"),
                    reason);
        }
    }


    @Nested
    @DisplayName("ANDed with All / Any / None, and evaluated after them")
    class OtherFacets
    {

        @Test
        @DisplayName("an unmet All keeps its own reason even when a group is mixed too")
        void allReasonFirst()
        {
            Rule rule = ruleWithGroups(VISITDY_PAIR);
            rule.getRequirements().getVariables().setAll(List.of("VISITNUM", "TV.VISITNUM"));
            String reason = check(rule, sv("VISIT"), tv("VISITNUM", "VISITDY"));
            assertNotNull(reason);
            assertTrue(reason.startsWith("Requirements.Variables.All variable VISITNUM"), reason);
        }


        @Test
        @DisplayName("a satisfied All does not rescue a mixed group")
        void allSatisfiedGroupMixed()
        {
            Rule rule = ruleWithGroups(VISITDY_PAIR);
            rule.getRequirements().getVariables().setAll(List.of("VISITNUM", "TV.VISITNUM"));
            String reason = check(rule, sv("VISITNUM"), tv("VISITNUM", "VISITDY"));
            assertNotNull(reason);
            assertTrue(reason.contains("All_Or_None group 1"), reason);
        }


        @Test
        @DisplayName("a violated None rejects with None's reason")
        void noneRejects()
        {
            Rule rule = ruleWithGroups(VISITDY_PAIR);
            rule.getRequirements().getVariables().setNone(List.of("POOLID"));
            String reason = check(rule, sv("VISITNUM", "POOLID"), tv("VISITNUM"));
            assertNotNull(reason);
            assertTrue(reason.contains("Requirements.Variables.None"), reason);
        }


        @Test
        @DisplayName("a satisfied Any leg and a satisfied group run together")
        void anyAndGroupBothSatisfied()
        {
            Rule rule = ruleWithGroups(VISITDY_PAIR);
            rule.getRequirements().getVariables()
                    .setAnyGroups(List.of(List.of("VISIT", "VISITNUM")));
            assertNull(check(rule, sv("VISITNUM"), tv("VISITNUM")));
        }
    }


    @Nested
    @DisplayName("entry vocabulary — `--`, patterns compared RESOLVED, the SUPP pivot")
    class Vocabulary
    {

        @Test
        @DisplayName("a `--` entry resolves against the primary and the label says so")
        void domainPrefix()
        {
            Rule rule = ruleWithGroups(List.of(List.of("--STDTC", "TV.TVSTRL")));
            assertNull(check(rule, sv("SVSTDTC"), tv("TVSTRL")));
            assertNull(check(rule, sv("VISITNUM"), tv("VISITNUM")));
            String reason = check(rule, sv("VISITNUM"), tv("TVSTRL"));
            assertNotNull(reason);
            assertTrue(reason.contains("absent: [--STDTC (resolved SVSTDTC)]"), reason);
        }


        /**
         * Owner, 2026-09-25: <i>"a {@code [[TRTxxP, ADSL.TRTxxP]]} only matches if either no TRTxxP
         * vars exist in any domain or if all exist in both."</i> Each entry resolves to the set of
         * concrete names it matches, and an all-present group needs equal sets.
         */
        @Test
        @DisplayName("⭐ marker-template entries: none anywhere, or the same ones on both sides")
        void markerTemplatesCompareResolved()
        {
            Rule rule = ruleWithGroups(List.of(List.of("TRTxxP", "ADSL.TRTxxP")));
            IDataTable adaeNone = table("ADAE", "USUBJID");
            IDataTable adaeBoth = table("ADAE", "USUBJID", "TRT01P", "TRT02P");
            assertNull(check(rule, adaeNone, table("ADSL", "USUBJID")), "no TRTxxP anywhere");
            assertNull(check(rule, adaeBoth, table("ADSL", "USUBJID", "TRT01P", "TRT02P")),
                    "the same two on both sides");

            String partial = check(rule, adaeBoth, table("ADSL", "USUBJID", "TRT01P"));
            assertEquals("Requirements.Variables.All_Or_None group 1 [TRTxxP, ADSL.TRTxxP]"
                    + " resolves unequally — TRTxxP matches [TRT01P, TRT02P] but ADSL.TRTxxP"
                    + " matches [TRT01P]", partial);

            String oneSide = check(rule, adaeNone, table("ADSL", "USUBJID", "TRT01P"));
            assertNotNull(oneSide);
            assertTrue(oneSide.contains("present: [ADSL.TRTxxP], absent: [TRTxxP]"), oneSide);
        }


        @Test
        @DisplayName("a glob takes the same set path; names compare case-blind")
        void globCompareResolved()
        {
            Rule rule = ruleWithGroups(List.of(List.of("TRT*P", "ADSL.TRT*P")));
            assertNull(check(rule, table("ADAE", "trt01p"), table("ADSL", "TRT01P")),
                    "the column inventories differ only in case");
            String reason = check(rule, table("ADAE", "TRT01P"), table("ADSL", "TRT01P", "TRT03P"));
            assertNotNull(reason);
            assertTrue(
                    reason.contains(
                            "TRT*P matches [TRT01P] but ADSL.TRT*P matches" + " [TRT01P, TRT03P]"),
                    reason);
        }


        @Test
        @DisplayName("a literal beside a pattern takes part by presence only")
        void literalWithPattern()
        {
            Rule rule = ruleWithGroups(List.of(List.of("TRTxxP", "ADSL.ARM")));
            assertNull(check(rule, table("ADAE", "TRT01P", "TRT02P"), table("ADSL", "ARM")));
            String reason = check(rule, table("ADAE", "TRT01P"), table("ADSL", "USUBJID"));
            assertNotNull(reason);
            assertTrue(reason.contains("present: [TRTxxP], absent: [ADSL.ARM]"), reason);
        }


        @Test
        @DisplayName("two differently named literals pair by presence — no set comparison")
        void differentLiteralsPairByPresence()
        {
            Rule rule = ruleWithGroups(List.of(List.of("VISITDY", "TV.TVSTRL")));
            assertNull(check(rule, sv("VISITDY"), tv("TVSTRL")));
            assertNull(check(rule, sv("VISITNUM"), tv("VISITNUM")));
            assertNotNull(check(rule, sv("VISITDY"), tv("VISITNUM")));
        }


        @Test
        @DisplayName("a qualified literal delivered by the SUPP-QNAM pivot counts as present")
        void suppPivot()
        {
            Rule rule = ruleWithGroups(List.of(List.of("CMTRT", "AE.AETRTEM")));
            IDataTable cm = table("CM", "USUBJID", "CMTRT");
            IDataTable ae = table("AE", "USUBJID");
            IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM").build();
            assertNull(check(rule, cm, ae, suppae), "AETRTEM arrives as a QNAM row of SUPPAE");
            String reason = check(rule, cm, ae);
            assertNotNull(reason);
            assertTrue(reason.contains("absent: [AE.AETRTEM]"), reason);
        }
    }


    @Nested
    @DisplayName("undecidable qualified entries, and the lazy foreign source")
    class Undecidable
    {

        @Test
        @DisplayName("no foreign source: the reason names the resolver, never 'absent'")
        void undecidableIsNotAbsent()
        {
            Rule rule = ruleWithGroups(VISITDY_PAIR);
            String reason = ScopeMatcherCalls.describeVariablesMismatch(rule,
                    sv("VISITNUM").getMetaData(), "SV", null);
            assertNotNull(reason, "with no source the group could read as all-absent and let the"
                    + " rule run on exactly the unresolved join the facet exists to stop");
            assertTrue(reason.contains("could not be decided"), reason);
            assertTrue(reason.contains("Requirements.Variables.All_Or_None entry TV.VISITDY"),
                    reason);
            assertFalse(reason.contains("absent:"), reason);
        }


        @Test
        @DisplayName("hasQualifiedVariableScope sees the facet — or the source is never built")
        void qualifiedScopeIsDetected()
        {
            assertTrue(ScopeMatcher.hasQualifiedVariableScope(ruleWithGroups(VISITDY_PAIR)),
                    "without this the lazy ScopeVariableSource stays null and every carrier"
                            + " skips as undecidable");
            assertFalse(ScopeMatcher.hasQualifiedVariableScope(
                    ruleWithGroups(List.of(List.of("VISITDY", "VISIT")))));
        }
    }
}
