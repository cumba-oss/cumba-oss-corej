package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The load gates of {@code plans/PLAN-scope-requirements-split.md} &#167;4.7 — <b>R1</b> (retired
 * {@code Scope.Variables}), <b>R2</b> (unknown key under {@code Scope} / {@code Scope.Datasets} /
 * {@code Requirements} / {@code Requirements.Variables}), <b>R3</b> (empty entry), <b>R4</b>
 * (degenerate {@code Any}), <b>R5</b> (provider {@code declared ⇔ derived}), <b>R6</b> (expansion
 * token in a requirement), <b>R7</b> (undeclared {@code Match_Datasets} secondary) and <b>R8</b>
 * (authored {@code Precondition}).
 *
 * <p>
 * Every case is driven through {@link RulePackageLoader#loadFromString} — the production path, both
 * validator hosts, in the production order — rather than through one host's per-rule entry point. A
 * gate wired into the wrong host, or downstream of the injection pass, reds here and passes a
 * hand-built check; that is the whole reason the harness is the loader.
 * </p>
 */
class RequirementsLoadGateTest
{

    private static final String CHECK = "\"Check\":{\"all\":[{\"name\":\"AESEV\","
            + "\"operator\":\"var_exists\"}]}";

    private static String packageOf(String ruleBody)
    {
        return "{\"rules\":{\"rule-1\":{\"Core\":{\"Id\":\"TEST-REQ\"}," + ruleBody + "}}}";
    }


    /** Loads a one-rule corpus package through the production loader. */
    private static Rule load(String ruleBody) throws IOException
    {
        return RulePackageLoader.loadFromString(packageOf(ruleBody)).getRules().values().iterator()
                .next();
    }


    private static String errorOf(String ruleBody) throws IOException
    {
        return load(ruleBody).getLoadError();
    }

    // ---- R1 — the retired Scope.Variables spelling --------------------------

    @Nested
    @DisplayName("R1 — a surviving Scope.Variables after the migration")
    class RetiredScopeVariables
    {

        @Test
        @DisplayName("the key NO LONGER BINDS — it reaches Scope's unknown-key collector")
        void theRetiredKeyNoLongerBinds() throws IOException
        {
            Rule rule = load("\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]},"
                    + "\"Variables\":{\"Include\":[\"AESEV\"]}}," + CHECK);
            Scope scope = rule.getScope();
            assertNotNull(scope);
            assertTrue(scope.getUnknownKeys().contains("Variables"),
                    "phase 5 removed Scope's Variables property, so the authored block must reach"
                            + " the @JsonAnySetter collector — that is what arms R1");
        }


        /**
         * ⚠⚠ <b>This test used to pin the hole open.</b> It asserted only that a misspelled
         * {@code Scope} key is <em>recorded</em>, and stopped there — which read as "recording is
         * the intended behaviour" while {@code Scope: {Varibles: …}} loaded with no error at all.
         * It now asserts both halves: the recorder works (the plumbing R1 needs) <b>and</b> R2's
         * {@code Scope} arm turns the recording into a load error.
         */
        @Test
        @DisplayName("an unbound key under Scope is recorded AND rejected — R1's plumbing, R2's"
                + " gate")
        void anUnboundScopeKeyIsRecordedAndRejected() throws IOException
        {
            Rule rule = load("\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]},"
                    + "\"Varibles\":{\"Include\":[\"AESEV\"]}}," + CHECK);
            assertNotNull(rule.getScope());
            assertTrue(rule.getScope().getUnknownKeys().contains("Varibles"),
                    "without the @JsonAnySetter recorder a stray Scope key is invisible, and R1"
                            + " could never see a surviving Scope.Variables");
            String error = rule.getLoadError();
            assertNotNull(error, "a misspelled Scope facet binds to nothing — recording it and"
                    + " loading anyway is how the rule ends up running unrestricted");
            assertTrue(error.contains("'Varibles'"), error);
            assertTrue(error.contains("under 'Scope'"), error);
        }


        /**
         * ⭐ The gate biting <b>from JSON</b>, through {@link RulePackageLoader#loadFromString} —
         * the production load path, with no reflection anywhere.
         *
         * <p>
         * Until phase 5 this could only be driven by seeding the recorded key into {@link Scope}'s
         * collector reflectively, because the property still bound and <em>there was no way to
         * produce that state from JSON</em>. Dropping the binding is what made the state reachable;
         * the seeding is gone, and so is the risk that this class certifies a gate against a state
         * the loader cannot actually reach.
         * </p>
         */
        @Test
        @DisplayName("a Scope.Variables block on a CORPUS package is a load error, from JSON")
        void retiredSpellingIsRejectedOnACorpusPackage() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]},"
                    + "\"Variables\":{\"Include\":[\"AESEV\"]}}," + CHECK);
            assertNotNull(error, "a surviving Scope.Variables must not load silently");
            assertTrue(error.contains("Scope.Variables"), error);
            assertTrue(error.contains("Requirements.Variables"), error);
        }


        @Test
        @DisplayName("it fires on the Exclude spelling too — the whole block, not one facet")
        void retiredSpellingIsRejectedWhateverFacetItCarries() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Variables\":{\"Exclude\":[\"POOLID\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Scope.Variables"), error);
        }


        /**
         * ⚠ The failure R1 exists for: without it the retired block binds to nothing and the rule
         * runs with its requirement <b>deleted</b> — unrestricted, not skipped. This pins that the
         * requirement really is gone, so the error is the only thing standing between the corpus
         * and a silently unscoped rule.
         */
        @Test
        @DisplayName("the retired block contributes NO requirement — the error is the only guard")
        void theRetiredBlockYieldsNoRequirement() throws IOException
        {
            Rule rule = load("\"Scope\":{\"Variables\":{\"Include\":[\"AESEV\"]}}," + CHECK);
            assertNull(rule.effectiveVariableRequirement(),
                    "a retired Scope.Variables block binds to nothing: were R1 not to fire, the"
                            + " rule would run against every dataset");
        }

        // Fix #366 removed the second load path this class used to exercise. Until then the
        // loader had two entry points and one of them - the engine's own rules-templates.json -
        // was exempt from validateRetiredUnderscoreKeys; two tests here pinned that R1 was NOT
        // exempt on it and that the underscore guard was. Both subjects are gone with the file:
        // there is one load path, nothing is exempt from anything, and
        // retiredSpellingIsRejectedOnACorpusPackage above is now the whole guarantee.
    }

    // ---- R1a — retired with the binding it policed --------------------------


    @Nested
    @DisplayName("the single surviving spelling")
    class SingleSpelling
    {

        @Test
        @DisplayName("Requirements.Variables is the conforming shape")
        void newAlone() throws IOException
        {
            assertNull(errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"AESEV\"]}}," + CHECK));
        }


        /**
         * ⛔ Gate <b>R1a</b> ("declares both spellings") retired with the binding it policed. A rule
         * carrying both blocks is now diagnosed by <b>R1</b>: the {@code Scope} half is an unknown
         * key and cannot be silently preferred, because it binds to nothing at all. What this pins
         * is that the surviving diagnosis still names the retired half — a rule reaching here with
         * no error would be a rule running on a requirement its author never wrote.
         */
        @Test
        @DisplayName("declaring both is still a load error — now R1's, naming the retired half")
        void bothIsRejected() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Variables\":{\"Include\":[\"AESEV\"]}},"
                    + "\"Requirements\":{\"Variables\":{\"All\":[\"AEDECOD\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Scope.Variables"), error);
        }


        @Test
        @DisplayName("declaring neither is legal — the overwhelming majority of the corpus")
        void neitherIsLegal() throws IOException
        {
            assertNull(errorOf("\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}}," + CHECK));
        }


        @Test
        @DisplayName("effectiveVariableRequirement resolves the one spelling there is")
        void theReaderResolvesTheOneSpelling() throws IOException
        {
            Rule modern = load("\"Requirements\":{\"Variables\":{\"All\":[\"AEDECOD\"],"
                    + "\"Any\":[\"AESTDTC\",\"AEDTC\"],\"None\":[\"POOLID\"]}}," + CHECK);
            assertNotNull(modern.effectiveVariableRequirement());
            assertEquals(List.of("AEDECOD"), modern.effectiveVariableRequirement().getAll());
            assertEquals(List.of("AESTDTC", "AEDTC"),
                    modern.effectiveVariableRequirement().getAny());
            assertEquals(List.of("POOLID"), modern.effectiveVariableRequirement().getNone());

            assertNull(load(CHECK).effectiveVariableRequirement());
        }
    }

    // ---- R2 — unknown keys under the new blocks ------------------------------


    @Nested
    @DisplayName("R2 — an unknown key under Requirements / Requirements.Variables / Scope.Datasets")
    class UnknownKeys
    {

        @Test
        @DisplayName("the conforming shape carries no unknown key")
        void conforming() throws IOException
        {
            assertNull(errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"AESEV\"]},"
                    + "\"Datasets\":[\"EX\"]}," + CHECK));
        }


        @Test
        @DisplayName("a misspelled Requirements block is a load error, not a silent no-op")
        void misspelledRequirementsKey() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Dataset\":[\"EX\"]}," + CHECK);
            assertNotNull(error, "a lenient mapper would drop `Dataset` and require nothing");
            assertTrue(error.contains("'Dataset'"), error);
            assertTrue(error.contains("Requirements"), error);
        }


        @Test
        @DisplayName("a misspelled variable facet is a load error")
        void misspelledFacet() throws IOException
        {
            String error = errorOf(
                    "\"Requirements\":{\"Variables\":{\"AnyOf\":[\"A\",\"B\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("'AnyOf'"), error);
            assertTrue(error.contains("Requirements.Variables"), error);
        }


        @Test
        @DisplayName("include_split_datasets on Scope.Datasets is rejected — the axis has no such"
                + " tri-state")
        void splitFlagOnTheNameAxisIsRejected() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Datasets\":{\"Include\":[\"ADSL\"],"
                    + "\"include_split_datasets\":true}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("include_split_datasets"), error);
            assertTrue(error.contains("Scope.Datasets"), error);
        }


        /**
         * ⭐⭐ The failure this gate's contract names, on the axis it did not cover until now.
         *
         * <p>
         * {@code reportUnknownKeys} was called for {@code Scope.Datasets}, {@code Requirements} and
         * {@code Requirements.Variables} — never for {@code Scope} itself — and the only other
         * reader of {@code Scope.getUnknownKeys()} is gate R1, which tests
         * {@code contains("Variables")}: one literal key. So a misspelled <em>scope</em> facet was
         * the one unbound key in the family that loaded clean.
         * </p>
         *
         * <p>
         * ⚠ It is also the worst one to miss. A misspelled <em>requirement</em> under-restricts a
         * rule by one condition; a misspelled {@code Scope.Domains} removes the restriction that
         * says which datasets the rule is about, so the rule runs against <b>every dataset in the
         * study</b> and reports findings nobody asked for — silently, and looking exactly like a
         * correctly-scoped rule in every artifact.
         * </p>
         */
        @Test
        @DisplayName("⭐ a misspelled Scope facet is a load error — it would otherwise run the rule"
                + " against every dataset")
        void misspelledScopeFacetIsRejected() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Domians\":{\"Include\":[\"AE\"]}}," + CHECK);
            assertNotNull(error, "Scope.Domians binds to nothing: the rule is not narrowed to AE,"
                    + " it is narrowed to nothing at all and therefore runs everywhere");
            assertTrue(error.contains("'Domians'"), error);
            assertTrue(error.contains("under 'Scope'"), error);
        }


        @Test
        @DisplayName("every near-miss of a real Scope facet is caught, not just Domains")
        void theOtherScopeNearMissesAreCaughtToo() throws IOException
        {
            for (String key : List.of("Class", "Use_Cases", "Data_Structure", "Dataset",
                    "Subclass"))
            {
                String error = errorOf(
                        "\"Scope\":{\"" + key + "\":{\"Include\":[\"AE\"]}}," + CHECK);
                assertNotNull(error, "unbound Scope key not rejected: " + key);
                assertTrue(error.contains("'" + key + "'"), key + " -> " + error);
            }
        }


        /**
         * ⚠ R1 owns the retired spelling by name, and must keep owning it: its message tells the
         * author <em>where the block moved to</em>, which the generic unknown-key message cannot.
         * If the {@code Scope} arm ever stops excluding it, this rule would collect two errors and
         * the actionable one would be buried.
         */
        @Test
        @DisplayName("⚠ the retired Variables spelling still reports R1's message, not R2's generic"
                + " one")
        void theRetiredSpellingKeepsItsOwnMessage() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Variables\":{\"Include\":[\"AESEV\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("retired field 'Scope.Variables'"), error);
            assertFalse(error.contains("unknown key 'Variables'"),
                    "R2's generic arm must defer to R1 on this one key: " + error);
        }
    }

    // ---- R3 — empty entries ---------------------------------------------------


    @Nested
    @DisplayName("R3 — an empty or null entry in a requirement list")
    class EmptyEntries
    {

        @Test
        @DisplayName("non-empty entries are the conforming shape")
        void conforming() throws IOException
        {
            assertNull(errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"AESEV\"]},"
                    + "\"Datasets\":[\"EX\"]}," + CHECK));
        }


        @Test
        @DisplayName("an empty entry in a variable facet is rejected")
        void emptyVariableEntry() throws IOException
        {
            String error = errorOf(
                    "\"Requirements\":{\"Variables\":{\"All\":[\"AESEV\",\"\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Requirements.Variables.All"), error);
            assertTrue(error.contains("empty/null entry"), error);
        }


        /**
         * ⚠⚠ Only the {@code All} and {@code Datasets} arms were covered, so deleting either the
         * {@code Any} or the {@code None} call site red nothing. The {@code Any} arm is the one
         * that matters: R4's minimum counts entries, so {@code ["AESEV",""]} passes it and R3 is
         * the <b>sole</b> gate. Without it the empty entry reaches {@code ScopeMatcher}, whose
         * {@code scopePattern("")} returns null and degrades to an exact compare against a column
         * literally named {@code ""} — so the author's two-entry {@code Any} is really a one-entry
         * one. {@code ["",""]} is worse: it satisfies nothing on any dataset, and the rule is
         * skipped everywhere with no diagnostic at all.
         */
        @Test
        @DisplayName("an empty entry in Any and in None is rejected — R3's two uncovered arms")
        void emptyEntryInAnyAndNone() throws IOException
        {
            String any = errorOf(
                    "\"Requirements\":{\"Variables\":{\"Any\":[\"AESEV\",\"\"]}}," + CHECK);
            assertNotNull(any, "R4's minimum counts this list as two entries, so R3 is the only"
                    + " gate standing between an empty entry and a silently narrowed Any");
            assertTrue(any.contains("Requirements.Variables.Any"), any);
            assertTrue(any.contains("empty/null entry"), any);

            String none = errorOf(
                    "\"Requirements\":{\"Variables\":{\"None\":[\"POOLID\",\"  \"]}}," + CHECK);
            assertNotNull(none);
            assertTrue(none.contains("Requirements.Variables.None"), none);
            assertTrue(none.contains("empty/null entry"), none);
        }


        @Test
        @DisplayName("a null entry in Requirements.Datasets is rejected")
        void nullDatasetEntry() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Datasets\":[null]}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Requirements.Datasets"), error);
        }


        @Test
        @DisplayName("an empty Scope.Datasets entry is rejected, exactly as Scope.Domains' is")
        void emptyDatasetScopeEntry() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Datasets\":{\"Include\":[\"\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Scope.Datasets.Include"), error);
        }


        @Test
        @DisplayName("an invalid /regex/ in a requirement fails at load, not at match time")
        void invalidRegexInARequirement() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"/[/\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Requirements.Variables.All"), error);
            assertTrue(error.contains("not a valid pattern"), error);
        }


        @Test
        @DisplayName("an invalid /regex/ in Scope.Datasets fails at load too")
        void invalidRegexInTheDatasetAxis() throws IOException
        {
            String error = errorOf("\"Scope\":{\"Datasets\":{\"Include\":[\"/(/\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Scope.Datasets.Include"), error);
            assertTrue(error.contains("not a valid pattern"), error);
        }
    }

    // ---- R4 — the degenerate Any shapes (ruling Q9) ---------------------------


    @Nested
    @DisplayName("R4 — the six degenerate Any shapes, all load errors (ruling Q9)")
    class AnyShape
    {

        @Test
        @DisplayName("a two-entry Any is the conforming shape")
        void conforming() throws IOException
        {
            assertNull(errorOf(
                    "\"Requirements\":{\"Variables\":{\"Any\":[\"TEENRL\",\"TEDUR\"]}}," + CHECK));
        }


        @Test
        @DisplayName("All and Any together are legal — they are ANDed")
        void allAndAnyTogetherAreLegal() throws IOException
        {
            assertNull(errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"DSDECOD\"],"
                    + "\"Any\":[\"DSSTDTC\",\"DSDTC\"]}}," + CHECK));
        }


        @Test
        @DisplayName("a one-entry Any is rejected")
        void oneEntryAny() throws IOException
        {
            String error = errorOf(
                    "\"Requirements\":{\"Variables\":{\"Any\":[\"TEENRL\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("at least 2 distinct entries"), error);
        }


        @Test
        @DisplayName("an empty Any is rejected")
        void emptyAny() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"Any\":[]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("at least 2 distinct entries"), error);
        }


        @Test
        @DisplayName("an entry in both Any and All is rejected — the Any leg would be vacuous")
        void anyIntersectsAll() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"TEENRL\"],"
                    + "\"Any\":[\"TEENRL\",\"TEDUR\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("both Any and All"), error);
        }


        @Test
        @DisplayName("an entry in both Any and None is rejected — unsatisfiable")
        void anyIntersectsNone() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"None\":[\"TEENRL\"],"
                    + "\"Any\":[\"TEENRL\",\"TEDUR\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("both Any and None"), error);
        }


        @Test
        @DisplayName("an entry in both All and None is rejected — unsatisfiable")
        void allIntersectsNone() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"POOLID\"],"
                    + "\"None\":[\"POOLID\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("both All and None"), error);
        }

        // ---- the same three overlaps, spelled in DIFFERENT CASE ------------------
        //
        // ⭐⭐ Not one of the six cases above varied case, and the gate compared raw trimmed
        // entries — while every consumer of a variable requirement matches case-blind
        // (ScopeMatcher.scopePattern compiles CASE_INSENSITIVE; the literal arm lands on
        // DataTableMeta.equalsIgnoreCase). So all three overlaps escaped in their lower-case
        // spelling, and the sibling gate R7 in the same plan had folded case all along.


        /**
         * ⚠⚠ The headline failure: the rule requires {@code AESEV} to be <b>present and absent at
         * the same time</b>, so it matches no dataset ever and reports nothing, forever — and R4's
         * own message for exactly this shape ("the entry would have to be both present and absent")
         * was not emitted. A rule that silently never runs is indistinguishable in every artifact
         * from a rule that runs and finds nothing.
         */
        @Test
        @DisplayName("⭐ All and None differing only in CASE is rejected — the rule could never"
                + " match")
        void allIntersectsNoneAcrossCase() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"AESEV\"],"
                    + "\"None\":[\"aesev\"]}}," + CHECK);
            assertNotNull(error, "AESEV and aesev are the same column to every consumer: this is"
                    + " an unsatisfiable requirement, not two different columns");
            assertTrue(error.contains("both All and None"), error);
        }


        @Test
        @DisplayName("⭐ Any and All differing only in CASE is rejected — the Any leg goes vacuous")
        void anyIntersectsAllAcrossCase() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"teenrl\"],"
                    + "\"Any\":[\"TEENRL\",\"TEDUR\"]}}," + CHECK);
            assertNotNull(error, "the All leg already guarantees TEENRL, so the Any leg is"
                    + " satisfied unconditionally and says nothing — the author's intent is lost");
            assertTrue(error.contains("both Any and All"), error);
        }


        @Test
        @DisplayName("⭐ Any and None differing only in CASE is rejected — unsatisfiable")
        void anyIntersectsNoneAcrossCase() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Variables\":{\"None\":[\"TEENRL\"],"
                    + "\"Any\":[\"teenrl\",\"TEDUR\"]}}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("both Any and None"), error);
        }


        /**
         * The fold must not over-reach: two genuinely different columns stay legal however they are
         * cased. Without this the three tests above would also pass if the gate simply rejected
         * every {@code Any}/{@code All} pair.
         */
        @Test
        @DisplayName("⚠ mixed case on DIFFERENT columns stays legal — the fold is not a blanket"
                + " reject")
        void mixedCaseOnDifferentColumnsIsLegal() throws IOException
        {
            assertNull(errorOf("\"Requirements\":{\"Variables\":{\"All\":[\"dsdecod\"],"
                    + "\"Any\":[\"DSSTDTC\",\"dsdtc\"],\"None\":[\"PoolId\"]}}," + CHECK));
        }


        /**
         * ⚠ The minimum counts <b>distinct</b> entries. {@code ["AESEV","AESEV"]} is precisely the
         * degenerate one-column {@code Any} the message describes — "All with extra ceremony" — and
         * a raw {@code size() >= 2} let it through.
         */
        @Test
        @DisplayName("an Any of one column repeated is rejected — the minimum is distinct entries")
        void anyOfARepeatedEntryIsRejected() throws IOException
        {
            String error = errorOf(
                    "\"Requirements\":{\"Variables\":{\"Any\":[\"AESEV\",\"aesev\"]}}," + CHECK);
            assertNotNull(error, "two spellings of one column are one column to every consumer");
            assertTrue(error.contains("at least 2 distinct entries"), error);
        }
    }

    // ---- R5 — provider declared ⇔ derived (ruling Q4) -------------------------


    @Nested
    @DisplayName("R5 — Requirements.Library/.Define/.Dictionary ⟺ the derived dependency")
    class ProviderAgreement
    {

        private static final String LIBRARY_OP = "\"Operations\":[{\"id\":\"$req\","
                + "\"operator\":\"required_variables\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$req\",\"operator\":\"empty\"}]}";

        @Test
        @DisplayName("omitting the field is always legal — the corpus's state on day one")
        void omittedIsLegal() throws IOException
        {
            assertNull(errorOf("\"Requirements\":{\"Datasets\":[\"EX\"]}," + LIBRARY_OP));
        }


        @Test
        @DisplayName("a declaration that matches the derivation is legal")
        void declaredMatchingIsLegal() throws IOException
        {
            assertNull(errorOf("\"Requirements\":{\"Library\":true}," + LIBRARY_OP));
        }


        @Test
        @DisplayName("declaring Library on a rule that uses no Library is rejected")
        void declaredTrueWithoutDependency() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Library\":true}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("Requirements.Library is declared true"), error);
            assertTrue(error.contains("does NOT use"), error);
        }


        @Test
        @DisplayName("denying Library on a rule that does use it is rejected")
        void declaredFalseWithDependency() throws IOException
        {
            String error = errorOf("\"Requirements\":{\"Library\":false}," + LIBRARY_OP);
            assertNotNull(error);
            assertTrue(error.contains("Requirements.Library is declared false"), error);
            assertTrue(error.contains("DOES use"), error);
        }


        @Test
        @DisplayName("the operand surface counts too — a library_* operand with no Operation")
        void operandSurfaceCounts() throws IOException
        {
            String body = "\"Requirements\":{\"Library\":true},"
                    + "\"Check\":{\"all\":[{\"name\":\"library_variable_role\","
                    + "\"operator\":\"equal_to\",\"value\":\"Topic\"}]}";
            assertNull(errorOf(body),
                    "CORE-001081's shape: the dependency exists with no Operations entry at all");
        }


        @Test
        @DisplayName("Define and Dictionary are gated the same way")
        void defineAndDictionary() throws IOException
        {
            String defineOp = "\"Operations\":[{\"id\":\"$d\","
                    + "\"operator\":\"define_variable_names\"}],"
                    + "\"Check\":{\"all\":[{\"name\":\"$d\",\"operator\":\"empty\"}]}";
            assertNull(errorOf("\"Requirements\":{\"Define\":true}," + defineOp));
            String error = errorOf("\"Requirements\":{\"Define\":false}," + defineOp);
            assertNotNull(error);
            assertTrue(error.contains("Requirements.Define is declared false"), error);

            String dictError = errorOf("\"Requirements\":{\"Dictionary\":true}," + CHECK);
            assertNotNull(dictError);
            assertTrue(dictError.contains("Requirements.Dictionary is declared true"), dictError);
        }
    }

    // ---- R6 — an expansion token in a variable requirement --------------------


    @Nested
    @DisplayName("R6 — a declared Expansion token inside a variable requirement")
    class ExpansionTokenInRequirement
    {

        private static final String EXPANSION = "\"Expansion\":[{\"token\":\"&VAR\","
                + "\"over\":\"shared_variables\",\"with\":\"ADSL\"}],";

        @Test
        @DisplayName("a requirement with no token is the conforming shape")
        void conforming() throws IOException
        {
            assertNull(errorOf(EXPANSION + "\"Requirements\":{\"Variables\":{\"All\":[\"AESEV\"]}},"
                    + "\"Check\":{\"all\":[{\"name\":\"&VAR\",\"operator\":\"var_exists\"}]}"));
        }


        @Test
        @DisplayName("a token in Requirements.Variables.All is rejected")
        void tokenInAll() throws IOException
        {
            String error = errorOf(EXPANSION
                    + "\"Requirements\":{\"Variables\":{\"All\":[\"&VAR\"]}},"
                    + "\"Check\":{\"all\":[{\"name\":\"&VAR\",\"operator\":\"var_exists\"}]}");
            assertNotNull(error, "the requirement gate runs BEFORE expansion, so the token would"
                    + " be matched literally and the rule would skip on every dataset");
            assertTrue(error.contains("Requirements.Variables.All"), error);
        }


        @Test
        @DisplayName("a token in Any and in None is rejected too")
        void tokenInAnyAndNone() throws IOException
        {
            String any = errorOf(EXPANSION
                    + "\"Requirements\":{\"Variables\":{\"Any\":[\"&VAR\",\"AESEV\"]}},"
                    + "\"Check\":{\"all\":[{\"name\":\"&VAR\",\"operator\":\"var_exists\"}]}");
            assertNotNull(any);
            assertTrue(any.contains("Requirements.Variables.Any"), any);

            String none = errorOf(EXPANSION
                    + "\"Requirements\":{\"Variables\":{\"None\":[\"&VAR\"]}},"
                    + "\"Check\":{\"all\":[{\"name\":\"&VAR\",\"operator\":\"var_exists\"}]}");
            assertNotNull(none);
            assertTrue(none.contains("Requirements.Variables.None"), none);
        }


        /**
         * ⚠ R6 no longer scans {@code Scope}: phase 5 removed the property, so the bar cannot reach
         * a token there — but the rule is still rejected, by <b>R1</b>, and for the stronger
         * reason. This pins that the retirement did not open a hole: a token under the old spelling
         * is a load error either way, and the message names the retired field.
         */
        @Test
        @DisplayName("a token under the RETIRED spelling is still rejected — by R1, not R6")
        void tokenUnderTheRetiredSpellingIsStillRejected() throws IOException
        {
            String error = errorOf(EXPANSION + "\"Scope\":{\"Variables\":{\"Include\":[\"&VAR\"]}},"
                    + "\"Check\":{\"all\":[{\"name\":\"&VAR\",\"operator\":\"var_exists\"}]}");
            assertNotNull(error);
            assertTrue(error.contains("Scope.Variables"), error);
        }


        /**
         * ⚠ R6 was the only gate in this family whose message did not name its rule. {@code
         * loadError} is per-rule, but a package's diagnostics are read together, and an
         * unattributed finding in a multi-rule package is one a reader cannot act on.
         */
        @Test
        @DisplayName("⚠ R6's message names the offending rule, as every sibling gate's does")
        void theMessageNamesTheRule() throws IOException
        {
            String error = errorOf(EXPANSION
                    + "\"Requirements\":{\"Variables\":{\"All\":[\"&VAR\"]}},"
                    + "\"Check\":{\"all\":[{\"name\":\"&VAR\",\"operator\":\"var_exists\"}]}");
            assertNotNull(error);
            assertTrue(error.startsWith("[TEST-REQ]"), error);
        }
    }

    // ---- R7 — an undeclared Match_Datasets secondary --------------------------


    @Nested
    @DisplayName("R7 — a Match_Datasets secondary absent from Requirements.Datasets")
    class MatchDatasetGap
    {

        private static final String JOIN = "\"Match_Datasets\":[{\"Name\":\"EX\","
                + "\"Keys\":[\"USUBJID\"]}],";

        @Test
        @DisplayName("declaring the secondary closes the gap")
        void declaredIsClean() throws IOException
        {
            Rule rule = load(JOIN + "\"Requirements\":{\"Datasets\":[\"EX\"]}," + CHECK);
            assertNull(rule.getRequirementsGapWarning());
            assertNull(rule.getLoadWarning());
            assertNull(rule.getLoadError());
        }


        @Test
        @DisplayName("an undeclared secondary is recorded")
        void undeclaredIsRecorded() throws IOException
        {
            Rule rule = load(JOIN + CHECK);
            String gap = rule.getRequirementsGapWarning();
            assertNotNull(gap, "the gap must be countable, not invisible");
            assertTrue(gap.contains("EX"), gap);
        }


        @Test
        @DisplayName("⚠ it is NEVER an error, and never on the loadWarning channel")
        void itIsAdvisoryOnly() throws IOException
        {
            Rule rule = load(JOIN + CHECK);
            assertNull(rule.getLoadError(), "ruling Q5: a missing secondary stays a runtime no-op");
            assertNull(rule.getLoadWarning(),
                    "CrossCorpusDerivationTest holds the shipped corpus to zero loadWarnings, and"
                            + " all 923 carriers (247 ids) trip R7 on day one — routing it there"
                            + " would have forced"
                            + " that assertion to be weakened to accommodate a warning it was built"
                            + " to catch");
        }


        @Test
        @DisplayName("a blank secondary name is ignored; a second validation appends")
        // LibraryRuleMapper calls validateEnumFields on its own, so a rule can be validated
        // twice — the channel must accumulate rather than clobber.
        void blankNamesAreIgnoredAndTheChannelAccumulates() throws IOException
        {
            Rule rule = load("\"Match_Datasets\":[{\"Name\":\"EX\",\"Keys\":[\"USUBJID\"]},"
                    + "{\"Name\":\"  \",\"Keys\":[\"USUBJID\"]}]," + CHECK);
            String first = rule.getRequirementsGapWarning();
            assertNotNull(first);
            assertTrue(first.contains("EX"), first);
            assertFalse(first.contains(", ]") || first.contains("[, "),
                    "a blank name is not a gap: " + first);
            RulePackageLoader.validateEnumFields(rule);
            String second = rule.getRequirementsGapWarning();
            assertNotNull(second);
            assertTrue(second.length() > first.length(), "the channel appends: " + second);
        }


        @Test
        @DisplayName("a rule with no join is silent on this channel")
        void noJoinNoGap() throws IOException
        {
            assertNull(load(CHECK).getRequirementsGapWarning());
        }
    }

    // ---- R8 — an authored Precondition (ruling Q3) ----------------------------


    @Nested
    @DisplayName("R8 — Precondition is not an authorable field (ruling Q3)")
    class AuthoredPrecondition
    {

        @Test
        @DisplayName("a rule with no Precondition is the conforming shape — the whole corpus")
        void conforming() throws IOException
        {
            assertNull(errorOf(CHECK));
        }


        @Test
        @DisplayName("an authored Precondition is a load error")
        void authoredIsRejected() throws IOException
        {
            String error = errorOf("\"Precondition\":{\"all\":[{\"name\":\"AESEV\","
                    + "\"operator\":\"var_exists\"}]}," + CHECK);
            assertNotNull(error);
            assertTrue(error.contains("'Precondition' is not an authorable field"), error);
            assertTrue(error.contains("Requirements"), error);
        }


        /**
         * ⚠⚠ <b>This is a hand-authored gate test by construction, and it must be read as one.</b>
         * The shipped corpus inlines <em>zero</em> availability-dependent calls —
         * {@code CrossCorpusDerivationTest} asserts {@code getInjectedPreconditionGates() == null}
         * corpus-wide — so no shipped rule can ever exercise this path. A green here proves the
         * ORDERING works; it proves nothing about a shipped rule carrying it
         * ({@code [[hand-authored-gate-tests-are-vacuous]]}).
         *
         * <p>
         * What it does prove is the one thing that could silently break R8: the gate runs from
         * {@code validateOperandSubstitution}, which {@code finishLoad} calls <em>before</em>
         * {@code injectInlineOperationGates}. Wired downstream of the injection it would red every
         * rule the injection exists for — i.e. exactly the rules the mechanism is for. A test on a
         * hand-built {@link Rule} would pass while the shipped path reds, which is why this one
         * goes through {@link RulePackageLoader#loadFromString} end to end.
         * </p>
         */
        @Test
        @DisplayName("a dictionary-gated rule loads end-to-end with ZERO errors — the ordering pin")
        void theInjectedGateIsNotAnAuthoredPrecondition() throws IOException
        {
            Rule rule = load("\"Check\":{\"expression\":"
                    + "\"valid_external_dictionary_value(AEDECOD, dictionary_term_type="
                    + "\\\"PT\\\", external_dictionary_type=\\\"meddra\\\") == false\"}");
            assertNull(rule.getLoadError(),
                    "the loader's own injected availability gate must never trip R8: "
                            + rule.getLoadError());
            assertNotNull(rule.getInjectedPreconditionGates(),
                    "the fixture must actually inline a dictionary call, or this test is vacuous in"
                            + " a second, undocumented way");
            assertNotNull(rule.getPrecondition(),
                    "the injection writes the gate into Precondition — the very field R8 rejects");
        }


        /**
         * ⚠⚠ <b>This test used to be a duplicate whose name was false of its fixture.</b> Its rule
         * body was byte-identical to {@link #authoredIsRejected}'s — no inline operation call, so
         * injection would have injected nothing and "even when injection would run" described
         * nothing. Its only extra assertion, {@code assertFalse(contains("injected"))}, tested a
         * string no gate emits.
         *
         * <p>
         * The scenario it claimed is the one now driven: an authored {@code Precondition} on a rule
         * whose {@code Check} <em>does</em> trigger injection. That is the collision R8's ordering
         * exists to resolve — and it must resolve towards rejection, because R8 runs on the
         * authored document, before {@code injectInlineOperationGates}. It must also stay rejected
         * now that R8's {@code injectedPreconditionGates != null} bypass is gone.
         * </p>
         */
        @Test
        @DisplayName("⭐ an authored Precondition on a rule that DOES trigger injection is still"
                + " rejected")
        void anAuthoredPreconditionIsRejectedEvenWhenInjectionWouldRun() throws IOException
        {
            Rule rule = load("\"Precondition\":{\"all\":[{\"name\":\"AESEV\","
                    + "\"operator\":\"var_exists\"}]},\"Check\":{\"expression\":"
                    + "\"valid_external_dictionary_value(AEDECOD, dictionary_term_type="
                    + "\\\"PT\\\", external_dictionary_type=\\\"meddra\\\") == false\"}");
            String error = rule.getLoadError();
            assertNotNull(error, "the authored half must be diagnosed before the loader writes its"
                    + " own gate into the same field");
            assertTrue(error.contains("'Precondition' is not an authorable field"), error);
            assertNull(rule.getInjectedPreconditionGates(),
                    "injectInlineOperationGates returns early on a rule that already carries a"
                            + " loadError — which is why a second finishLoad pass could never see"
                            + " an injection mixed with an authored term, and why the bypass R8"
                            + " used to carry for that case was redundant as well as unreachable");
        }


        /**
         * ⭐⭐ The escape hatch that keeps R8 an <b>authoring</b> gate rather than a corpus gate,
         * driven for the first time.
         *
         * <p>
         * {@code OperationInliner.addLibraryPreconditionGate} bakes exactly this string into a
         * shipped rule <em>offline</em> ({@code OperationInlinerTest} pins the spelling). Such a
         * rule loads with {@code injectedPreconditionGates == null} — this process did not inject
         * it — so {@link RulePackageLoader}'s {@code isAvailabilityGateOnly} is the <b>only</b>
         * thing standing between it and a load error that says "not an authorable field" about a
         * field the engine itself wrote. Nothing exercised that branch before: no corpus rule
         * carries a {@code Precondition} (0 of 14 416), and
         * {@code theInjectedGateIsNotAnAuthoredPrecondition} returns at the earlier
         * {@code precondition == null} condition. Neutering the recogniser to {@code return false}
         * red nothing; it reds this.
         * </p>
         */
        @Test
        @DisplayName("⭐ a machine-shaped availability gate loads clean — the recogniser's true"
                + " branch")
        void anAvailabilityGateOnlyPreconditionIsNotAuthored() throws IOException
        {
            assertNull(
                    errorOf("\"Precondition\":{\"expression\":\"library_available() and"
                            + " available(domain_is_custom())\"}," + CHECK),
                    "this is the exact Precondition OperationInliner.addLibraryPreconditionGate"
                            + " emits offline; rejecting it would red every rule in the first"
                            + " family a regen inlines a library-dependent operation for");
        }


        /**
         * ⚠ The smuggling shape R8 exists for. {@code isAvailabilityGateOnly} requires
         * <em>every</em> AND-term to be a gate call; a relaxation to "any term is a gate" would
         * open a one-token bypass past the whole gate with a green suite.
         */
        @Test
        @DisplayName("⭐ a gate call AND-ed with a value term is still rejected — no one-token"
                + " bypass")
        void aValueTermSmuggledBehindAGateCallIsRejected() throws IOException
        {
            String error = errorOf("\"Precondition\":{\"expression\":\"library_available() and"
                    + " AESEV == \\\"MILD\\\"\"}," + CHECK);
            assertNotNull(error, "prefixing an authored value condition with an availability call"
                    + " must not launder it past R8");
            assertTrue(error.contains("'Precondition' is not an authorable field"), error);
        }
    }

}
