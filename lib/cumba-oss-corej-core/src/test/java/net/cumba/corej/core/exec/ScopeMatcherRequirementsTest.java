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
 * {@code ScopeMatcher.describeVariablesMismatch} ({@code plans/PLAN-scope-requirements-split.md}
 * &#167;4.3).
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
 * ⚠ {@code None} ships with <b>zero</b> corpus carriers, exactly as {@code Scope.Variables.Exclude}
 * did. Every test of it here is a hand-authored gate test: it proves the engine works and never
 * that a shipped rule carries it ({@code [[hand-authored-gate-tests-are-vacuous]]}).
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
        vars.setAny(any);
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
        return ScopeMatcher.describeVariablesMismatch(rule, meta, null, null);
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
                    "no variable of Requirements.Variables.Any [TEENRL, TEDUR] present in"
                            + " dataset",
                    reason,
                    "no single entry is at fault in a disjunction, so the message names the list");
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
            assertNull(ScopeMatcher.describeVariablesMismatch(any, te, "TE", null));
            assertNull(ScopeMatcher.describeVariablesMismatch(all, te, "TE", null));
            assertNotNull(ScopeMatcher.describeVariablesMismatch(
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
            String reason = ScopeMatcher.describeVariablesMismatch(rule, te.getMetaData(), "TE",
                    foreign);
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
            assertNull(
                    ScopeMatcher.describeVariablesMismatch(rule, te.getMetaData(), "TE", foreign));
        }


        /**
         * ⭐ <b>The one real residual of the {@code Any} design — and it is WIDER than the plan
         * states.</b> Pinned here because it has zero carriers today and would otherwise be
         * discovered later.
         *
         * <p>
         * {@code foreign == null} is generation time: {@code RuleGenerator.describeScopeSkip}
         * passes null deliberately, so a resolver that cannot enumerate datasets does not skip
         * every qualified rule. There {@code describeIncludeEntry} answers "satisfied" for
         * <em>every</em> qualified entry.
         * </p>
         *
         * <p>
         * ⚠⚠ {@code plans/PLAN-scope-requirements-split.md} &#167;4.3 records this as <em>"an
         * {@code Any} list consisting <b>only</b> of qualified entries is vacuously
         * satisfied"</em>. <b>Measured: the "only" is too narrow.</b> {@code Any} is a disjunction
         * that short-circuits on the first satisfied entry, so <b>one</b> qualified entry anywhere
         * in the list satisfies the whole leg at generation time — a mixed list is vacuous too.
         * {@code All} does not widen the same way: it must satisfy every entry, so an unqualified
         * sibling still decides it.
         * </p>
         *
         * <p>
         * This remains a <em>property</em>, not a bug: it is the same conservative direction
         * {@code All} takes, it prevents generation-time skips, and none of the ten rules adopting
         * {@code Any} carries a qualified entry — so the residual has zero carriers on day one.
         * </p>
         */
        @Test
        @DisplayName("⭐ ONE qualified entry makes an Any leg vacuous at generation time")
        void anyWithAQualifiedEntryIsVacuousAtGenerationTime()
        {
            assertNull(describe(ruleWithRequirement(null, List.of("DM.ARM", "EX.EXDOSE"), null),
                    meta("TE", "TESEQ")), "qualified-only: the shape §4.3 names");
            assertNull(
                    describe(ruleWithRequirement(null, List.of("DM.ARM", "TEDUR"), null),
                            meta("TE", "TESEQ")),
                    "MIXED is vacuous too — the disjunction short-circuits on the qualified entry,"
                            + " so §4.3's 'consisting ONLY of qualified entries' is too narrow");
            // The control that keeps the two statements apart: with NO qualified entry the leg is
            // decidable at generation time and does report a mismatch.
            assertNotNull(describe(ruleWithRequirement(null, List.of("TEENRL", "TEDUR"), null),
                    meta("TE", "TESEQ")));
            // …and the conjunction does NOT widen the same way.
            assertNotNull(describe(ruleWithRequirement(List.of("DM.ARM", "TEDUR"), null, null),
                    meta("TE", "TESEQ")));
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
