package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code Requirements.Variables} — the {@code All} / {@code Any} / {@code None} legs of
 * {@code ScopeMatcher.describeVariablesMismatch}
 * ({@code plans/done/PLAN-scope-requirements-split.md} &#167;4.3).
 *
 * <p>
 * {@code All} and {@code None} are the former {@code Scope.Variables.Include} / {@code .Exclude}
 * byte-for-byte, and their entry vocabularies are covered exhaustively by
 * {@code ScopeMatcherPatternTest} / {@code ScopeMatcherQualifiedTest}. What is new, and what this
 * class covers, is: the {@code Any} leg, the inertness of the retired {@code Scope.Variables}
 * spelling at this matcher (phase 5 deleted the dual-read shim), and the three-facet reach of
 * {@code hasQualifiedVariableScope}.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Corrected 2026-09-21.</b> This said {@code None} ships with <b>zero</b> corpus carriers, so
 * "every test of it here is a hand-authored gate test". <b>False</b> — measured at HEAD, <b>43</b>
 * {@code rules-src} rules carry a {@code None} facet and <b>221</b> shipped instances across
 * <b>25</b> packages do. The claim came from {@code VariableRequirement}'s javadoc, which carried
 * it too and is corrected in the same pass. What genuinely has zero carriers is the {@code :N} /
 * {@code :C} <em>type suffix</em> ({@code plans/PLAN-variable-type-requirements.md}), and it is
 * that — not the facet — whose tests are all hand-authored
 * ({@code [[hand-authored-gate-tests-are-vacuous]]}).
 * </p>
 */
class ScopeMatcherRequirementsTest
{

    private static Rule ruleWithRequirement(@Nullable List<String> all, @Nullable List<String> any,
            @Nullable List<String> none)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-REQ-VAR");
        rule.setCore(core);
        VariableRequirement vars = new VariableRequirement();
        vars.setAll(all);
        vars.setAnyGroups(any == null ? null : List.of(any));
        vars.setNone(none);
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);
        return rule;
    }


    private static DataTableMeta meta(String name, String... columns)
    {
        MockTable t = MockTable.of().name(name);
        for (String c : columns)
        {
            t = t.col(c, "");
        }
        return t.build().getMetaData();
    }


    private static @Nullable String describe(Rule rule, DataTableMeta meta)
    {
        return ScopeMatcherCalls.describeVariablesMismatch(rule, meta, null, null);
    }

    @Nested
    @DisplayName("Any — at least one entry must be present")
    class AnyLeg
    {

        /**
         * ⚠⚠ <b>The short-circuit trap, and it is why this test and its twin below both exist.</b>
         * {@code M3-J.5} records the conjunction's version — <em>"a two-entry Include list needs a
         * fixture that removes the SECOND entry to prove the iteration gets past the first"</em>.
         * The disjunction's is the mirror image: a two-entry {@code Any} needs a fixture that
         * removes the <b>first</b> entry, or an implementation that always answers on entry 1
         * passes.
         */
        @Test
        @DisplayName("the FIRST entry absent is still satisfied by the second")
        void firstEntryAbsentSecondPresent()
        {
            Rule rule = ruleWithRequirement(null, List.of("TEENRL", "TEDUR"), null);
            assertNull(describe(rule, meta("TE", "TEDUR")));
        }


        @Test
        @DisplayName("the SECOND entry absent is still satisfied by the first")
        void secondEntryAbsentFirstPresent()
        {
            Rule rule = ruleWithRequirement(null, List.of("TEENRL", "TEDUR"), null);
            assertNull(describe(rule, meta("TE", "TEENRL")));
        }


        @Test
        @DisplayName("both present is satisfied")
        void bothPresent()
        {
            Rule rule = ruleWithRequirement(null, List.of("TEENRL", "TEDUR"), null);
            assertNull(describe(rule, meta("TE", "TEENRL", "TEDUR")));
        }


        @Test
        @DisplayName("EVERY entry absent is the only unmet case, and the reason names the LIST")
        void everyEntryAbsent()
        {
            Rule rule = ruleWithRequirement(null, List.of("TEENRL", "TEDUR"), null);
            String reason = describe(rule, meta("TE", "TESEQ"));
            assertEquals(
                    "no variable of Requirements.Variables.Any group 1 [TEENRL, TEDUR] present"
                            + " in dataset",
                    reason, "no single entry is at fault in a disjunction, so the message names the"
                            + " group and its index");
        }


        @Test
        @DisplayName("All and Any are ANDed — a met Any does not rescue an unmet All")
        void allAndAnyAreAnded()
        {
            Rule rule = ruleWithRequirement(List.of("DSDECOD"), List.of("DSSTDTC", "DSDTC"), null);
            assertNull(describe(rule, meta("DS", "DSDECOD", "DSDTC")));
            String reason = describe(rule, meta("DS", "DSDTC"));
            assertNotNull(reason);
            assertTrue(reason.contains("Requirements.Variables.All"), reason);
            String anyReason = describe(rule, meta("DS", "DSDECOD"));
            assertNotNull(anyReason);
            assertTrue(anyReason.contains("Requirements.Variables.Any"), anyReason);
        }


        @Test
        @DisplayName("a pattern entry is satisfied when any column matches")
        void patternEntry()
        {
            Rule rule = ruleWithRequirement(null, List.of("*STDTC", "*DTC"), null);
            assertNull(describe(rule, meta("DS", "DSSTDTC")));
            assertNotNull(describe(rule, meta("DS", "DSSEQ")));
        }


        @Test
        @DisplayName("Any and All share ONE entry matcher — a `--` entry resolves identically")
        void anyUsesTheSameEntryMatcherAsAll()
        {
            Rule any = ruleWithRequirement(null, List.of("--ENRL", "--DUR"), null);
            Rule all = ruleWithRequirement(List.of("--DUR"), null, null);
            DataTableMeta te = meta("TE", "TEDUR");
            assertNull(ScopeMatcherCalls.describeVariablesMismatch(any, te, "TE", null));
            assertNull(ScopeMatcherCalls.describeVariablesMismatch(all, te, "TE", null));
            assertNotNull(ScopeMatcherCalls.describeVariablesMismatch(
                    ruleWithRequirement(null, List.of("--ENRL", "--XXX"), null), te, "TE", null));
        }
    }


    @Nested
    @DisplayName("Any — qualified entries and the generation-time residual")
    class AnyQualified
    {

        private static ScopeVariableSource sourceOf(Map<String, IDataTable> byName,
                IDataTable primary)
        {
            DatasetResolver.WithInventory resolver = new DatasetResolver.WithInventory()
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
            return ScopeVariableSource.of(resolver, primary);
        }


        @Test
        @DisplayName("a qualified entry whose dataset is ABSENT counts as absent, not as satisfied")
        void qualifiedEntryWithAbsentDataset()
        {
            IDataTable te = MockTable.of().name("TE").col("TESEQ", "").build();
            Rule rule = ruleWithRequirement(null, List.of("DM.ARM", "TEDUR"), null);
            ScopeVariableSource foreign = sourceOf(Map.of("TE", te), te);
            assertNotNull(foreign);
            String reason = ScopeMatcherCalls.describeVariablesMismatch(rule, te.getMetaData(),
                    "TE", foreign);
            assertNotNull(reason,
                    "an unavailable foreign dataset is a MISMATCH in describeIncludeEntry, which is"
                            + " exactly the 'counts as absent' behaviour a disjunction needs");
            assertTrue(reason.contains("Requirements.Variables.Any"), reason);
        }


        @Test
        @DisplayName("a qualified entry whose dataset IS available satisfies the leg")
        void qualifiedEntrySatisfies()
        {
            IDataTable te = MockTable.of().name("TE").col("TESEQ", "").build();
            IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
            Rule rule = ruleWithRequirement(null, List.of("DM.ARM", "TEDUR"), null);
            ScopeVariableSource foreign = sourceOf(Map.of("TE", te, "DM", dm), te);
            assertNotNull(foreign);
            assertNull(ScopeMatcherCalls.describeVariablesMismatch(rule, te.getMetaData(), "TE",
                    foreign));
        }


        /**
         * ⭐ <b>A qualified entry with no foreign source is UNDECIDABLE — never vacuous.</b>
         *
         * <p>
         * {@code foreign == null} means the resolver in effect cannot enumerate datasets
         * ({@code ScopeVariableSource.of} answers {@code null} for a bare {@code DatasetResolver}).
         * Under the one policy the engine has — owner ruling 2026-09-10,
         * {@code plans/PLAN-qualified-requirements-cross-standard.md} §8.4 disposition (b) — an
         * undecidable entry is a mismatch whose reason names the <em>resolver</em>, so the report
         * cannot be read as "the column was absent".
         * </p>
         *
         * <p>
         * ⚑ History. Until 2026-09-25 this test pinned the opposite for the qualified-blind
         * overloads ({@code QualifiedEntryPolicy.IGNORE}): "one qualified entry anywhere in an
         * {@code Any} list makes the leg vacuous at generation time" — the residual
         * {@code plans/done/PLAN-scope-requirements-split.md} §4.3 records, measured wider than the
         * plan stated. Those overloads had no production caller (every production path already
         * passed {@code SKIP}) and were retired with the enum
         * ({@code PLAN-retire-dead-multi-match-lookup} U1 / K6), so the residual no longer exists
         * anywhere: the same inputs now yield the undecidable reason, and this test pins that.
         * </p>
         */
        @Test
        @DisplayName("⭐ a qualified entry in an Any leg is undecidable without a foreign source")
        void anyWithAQualifiedEntryIsUndecidableWithoutAForeignSource()
        {
            String qualifiedOnly = describe(
                    ruleWithRequirement(null, List.of("DM.ARM", "EX.EXDOSE"), null),
                    meta("TE", "TESEQ"));
            assertNotNull(qualifiedOnly, "qualified-only: undecidable, not vacuously satisfied");
            assertTrue(qualifiedOnly.contains("could not be decided")
                    && qualifiedOnly.contains("DM.ARM"), qualifiedOnly);
            // MIXED: the unqualified sibling TEDUR is absent, so the leg is unmet — and the reason
            // is the undecidable one, not absence (the disjunction no longer short-circuits on the
            // qualified entry, which the IGNORE reading used to let it do).
            String mixed = describe(ruleWithRequirement(null, List.of("DM.ARM", "TEDUR"), null),
                    meta("TE", "TESEQ"));
            assertNotNull(mixed, "mixed: unmet");
            assertTrue(mixed.contains("could not be decided"), mixed);
            // The control that keeps the statements apart: with NO qualified entry the leg is
            // decidable and reports plain absence, never the undecidable wording.
            String unqualified = describe(
                    ruleWithRequirement(null, List.of("TEENRL", "TEDUR"), null),
                    meta("TE", "TESEQ"));
            assertNotNull(unqualified);
            assertFalse(unqualified.contains("could not be decided"), unqualified);
            // …and the conjunction is undecidable the same way.
            String all = describe(ruleWithRequirement(List.of("DM.ARM", "TEDUR"), null, null),
                    meta("TE", "TESEQ"));
            assertNotNull(all);
            assertTrue(all.contains("could not be decided"), all);
        }
    }


    @Nested
    @DisplayName("None, the retired spelling, and hasQualifiedVariableScope")
    class NoneAndRetiredSpelling
    {

        @Test
        @DisplayName("None rejects the dataset when an entry is present")
        void noneRejectsWhenPresent()
        {
            Rule rule = ruleWithRequirement(null, null, List.of("POOLID"));
            assertNull(describe(rule, meta("AE", "AESEQ")));
            String reason = describe(rule, meta("AE", "POOLID"));
            assertNotNull(reason);
            assertTrue(reason.contains("Requirements.Variables.None"), reason);
        }


        /**
         * ⛔ <b>Re-aimed, not deleted.</b> This test used to pin that the retired
         * {@code Scope.Variables} spelling reached this very matcher through the dual-read shim.
         * Phase 5 deleted the binding and the shim with it, so there is no second spelling left to
         * agree with — but the state the old spelling now produces (an unbound key on
         * {@link Scope}) is reachable from JSON, and the matcher half of it is what this pins: a
         * {@code Scope} carrying a {@code Variables} block contributes <b>nothing</b> to the
         * variable gate.
         *
         * <p>
         * That is precisely the premise loader gate R1 rests on — R1 exists because such a rule
         * would otherwise run with its requirement <em>deleted</em> rather than skipped
         * ({@code RequirementsLoadGateTest}, R1). Re-adding a {@code Scope}-side read here would
         * leave R1 firing while the corpus was quietly gated by a spelling the model claims not to
         * bind; this test goes red on that first.
         * </p>
         */
        @Test
        @DisplayName("⛔ the retired Scope.Variables spelling gates NOTHING at the matcher")
        void retiredSpellingContributesNoGate() throws JsonProcessingException
        {
            Rule retired = new Rule();
            RuleCore core = new RuleCore();
            core.setId("TEST-RETIRED");
            retired.setCore(core);
            Scope scope = new ObjectMapper().readValue(
                    "{\"Variables\":{\"Include\":[\"AESTDTC\"],\"Exclude\":[\"POOLID\"]}}",
                    Scope.class);
            retired.setScope(scope);

            // Fixture precondition: without this the test could pass on a Scope that never saw
            // the block at all, proving nothing ([[hand-authored-gate-tests-are-vacuous]]).
            assertTrue(scope.getUnknownKeys().contains("Variables"),
                    "the retired block must reach Scope's unknown-key collector");
            assertNull(retired.effectiveVariableRequirement(),
                    "the retired block binds to no requirement — there is no shim left");
            // Both facets are inert: under the old spelling AESTDTC absent would have been an
            // Include mismatch and POOLID present an Exclude mismatch. Neither is reported.
            assertNull(describe(retired, meta("AE", "AESEQ", "POOLID")),
                    "a retired Scope.Variables block must not gate the rule at all");
        }


        /**
         * ⚠ {@code hasQualifiedVariableScope} decides whether the lazy {@link ScopeVariableSource}
         * is built at all. A facet it does not scan means the source is not built for a rule that
         * needs it, and every qualified entry in that facet then silently answers "satisfied" — a
         * skip that quietly does not happen. All three facets must be scanned.
         */
        @Test
        @DisplayName("⚠ hasQualifiedVariableScope scans All, Any AND None")
        void qualifiedScanCoversAllThreeFacets()
        {
            assertTrue(ScopeMatcher
                    .hasQualifiedVariableScope(ruleWithRequirement(List.of("DM.ARM"), null, null)));
            assertTrue(ScopeMatcher.hasQualifiedVariableScope(
                    ruleWithRequirement(null, List.of("DM.ARM", "AESEQ"), null)));
            assertTrue(ScopeMatcher
                    .hasQualifiedVariableScope(ruleWithRequirement(null, null, List.of("DM.ARM"))));
            assertFalse(ScopeMatcher.hasQualifiedVariableScope(
                    ruleWithRequirement(List.of("AESEQ"), List.of("A", "B"), List.of("C"))));
            assertFalse(ScopeMatcher.hasQualifiedVariableScope(new Rule()));
        }
    }

}
