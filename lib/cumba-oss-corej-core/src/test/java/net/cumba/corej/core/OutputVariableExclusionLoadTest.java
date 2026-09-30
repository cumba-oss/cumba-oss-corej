package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * E-3 of {@code PLAN-authoring-grammar-unique-set-and-output-exclusion} ({@code Fix #354}): the
 * five load-time checks on {@code !X} exclusion tokens, each with its rejecting AND its accepting
 * arm in the same class — a one-armed validator silently becomes the only arm. Plus the two doors
 * the subtraction must close outside {@code OutputVariableDeriver.derive}: the
 * {@code -Dcorej.autoOutputVariables=false} fallback and the three load-path {@code $}-id removers.
 */
class OutputVariableExclusionLoadTest
{

    private static Rule load(String body) throws Exception
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString(KeyedJoinFixtures.declared("{\"rules\":{\"R1\":" + body + "}}"));
        Rule rule = pkg.getRules().get("R1");
        assertNotNull(rule);
        return rule;
    }


    /** A record-level rule reading AETERM and AEDECOD, with the given authored OV. */
    private static String recordRule(String... outputVars)
    {
        return """
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"not empty(AETERM) and empty(AEDECOD)"},
                 "Outcome":{"Message":"m","Output_Variables":[%s]}}""".formatted(quote(outputVars));
    }


    private static String quote(String... items)
    {
        StringBuilder sb = new StringBuilder();
        for (String item : items)
        {
            sb.append(sb.length() == 0 ? "" : ",").append('"').append(item).append('"');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ check 1: names something derived


    @Test
    void exclusionOfADerivedNameIsAccepted() throws Exception
    {
        Rule rule = load(recordRule("AETERM", "!AEDECOD"));
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals(List.of("AETERM"), rule.getEffectiveOutputVariables());
        assertEquals(Set.of("AEDECOD"), rule.excludedOutputVariablesOrAuthored());
        // the authored list is untouched — source shape is never mutated
        assertEquals(List.of("AETERM", "!AEDECOD"), rule.getOutcome().getOutputVariables());
        // and the rationale names only the derived delta (nothing here: AEDECOD is excluded)
        assertTrue(rule.getDerivationRationale() == null
                || !rule.getDerivationRationale().containsKey("Output_Variables"));
    }


    @Test
    void exclusionOfANameTheRuleNeverDerivesIsALoadError() throws Exception
    {
        Rule rule = load(recordRule("AETERM", "!AEZZZ"));
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("!AEZZZ names nothing the rule derives"),
                rule.getLoadError());
        assertNull(rule.getEffectiveOutputVariables(), "a red rule gets no effective list");
    }


    @Test
    void excludingVariableNameIsLegalExactlyWhereItIsDerived() throws Exception
    {
        // Per-variable rule: variable_name is derived (D7), so `!variable_name` is valid and
        // removes the hoisted lead.
        Rule perVariable = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"all":[{"expression": "varname() =~ /^AE/"},
                                 {"expression": "empty(var_label(\\"DATA\\"))"}]},
                 "Outcome":{"Message":"m","Output_Variables":["!variable_name"]}}""");
        assertNull(perVariable.getLoadError(), perVariable.getLoadError());
        assertEquals(List.of("variable_label"), perVariable.getEffectiveOutputVariables());
        // Record rule: variable_name is not derived ⇒ the same token names nothing.
        Rule record = load(recordRule("!variable_name"));
        assertNotNull(record.getLoadError());
        assertTrue(record.getLoadError().contains("!variable_name names nothing"),
                record.getLoadError());
    }

    // ------------------------------------------------------------ check 2: contradiction


    @Test
    void authoredAndExcludedTogetherIsAContradiction() throws Exception
    {
        Rule rule = load(recordRule("AEDECOD", "!AEDECOD"));
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("AEDECOD is both authored and excluded"),
                rule.getLoadError());
    }


    @Test
    void authoredAloneIsNotAContradiction() throws Exception
    {
        Rule rule = load(recordRule("AEDECOD"));
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals(List.of("AEDECOD", "AETERM"), rule.getEffectiveOutputVariables());
    }

    // ------------------------------------------------------------ check 3: duplicates tolerated


    @Test
    void aRepeatedExclusionIsToleratedAndDeduped() throws Exception
    {
        Rule rule = load(recordRule("!AEDECOD", "!AEDECOD"));
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals(List.of("AETERM"), rule.getEffectiveOutputVariables());
        assertEquals(1, rule.excludedOutputVariablesOrAuthored().size());
    }

    // ------------------------------------------------------------ check 4: location variables


    @Test
    void excludingALocationVariableIsALoadError() throws Exception
    {
        Rule usubjid = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"empty(USUBJID) and empty(AETERM)"},
                 "Outcome":{"Message":"m","Output_Variables":["!USUBJID"]}}""");
        assertNotNull(usubjid.getLoadError());
        assertTrue(usubjid.getLoadError().contains("!USUBJID cannot exclude a location variable"),
                usubjid.getLoadError());
        Rule seq = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"empty(--SEQ) and empty(AETERM)"},
                 "Outcome":{"Message":"m","Output_Variables":["!--SEQ"]}}""");
        assertNotNull(seq.getLoadError());
        assertTrue(seq.getLoadError().contains("!--SEQ cannot exclude a location variable"),
                seq.getLoadError());
        Rule aseq = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"empty(ASEQ) and empty(AETERM)"},
                 "Outcome":{"Message":"m","Output_Variables":["!ASEQ"]}}""");
        assertNotNull(aseq.getLoadError());
        assertTrue(aseq.getLoadError().contains("!ASEQ cannot exclude"), aseq.getLoadError());
    }


    /**
     * {@code Fix #356} — check 4 must also reject the <em>substituted</em> spelling. The per-domain
     * expansion resolves {@code !--SEQ} to {@code !LBSEQ} and re-runs this validation on the
     * expanded rule; {@code LBSEQ} is not a verbatim member of {@code LOCATION_VARIABLES}, so
     * before the fix the token slipped through on the way out — a latent bypass (0 corpus carriers)
     * that the wildcard-resolving arm of
     * {@code OutputVariableDeriver#isLocationVariable(Rule, String)} closes.
     */
    @Test
    void excludingTheSubstitutedLocationVariableIsALoadErrorToo() throws Exception
    {
        // PRE-substitution — the authored form, rejected before the expansion can run.
        Rule authored = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Scope":{"Domains":{"Include":["LB"]}},
                 "Check":{"expression":"empty(--SEQ) and empty(--ORRES)"},
                 "Outcome":{"Message":"m","Output_Variables":["!--SEQ"]}}""");
        assertNotNull(authored.getLoadError());
        assertTrue(authored.getLoadError().contains("!--SEQ cannot exclude a location variable"),
                authored.getLoadError());

        // POST-substitution — the shape the per-domain expansion hands back to the derivation.
        Rule expanded = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Scope":{"Domains":{"Include":["LB"]}},
                 "Check":{"expression":"empty(LBSEQ) and empty(LBORRES)"},
                 "Outcome":{"Message":"m","Output_Variables":["!LBSEQ"]}}""");
        assertNotNull(expanded.getLoadError());
        assertTrue(expanded.getLoadError().contains("!LBSEQ cannot exclude a location variable"),
                expanded.getLoadError());
    }


    /**
     * The accepting arm of the wildcard-resolving half: a pinned domain does not turn every
     * {@code <D>}-prefixed exclusion into a location variable — only the {@code --SEQ} resolution.
     */
    @Test
    void aPinnedDomainDoesNotMakeEveryPrefixedExclusionALocationVariable() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Scope":{"Domains":{"Include":["LB"]}},
                 "Check":{"expression":"empty(LBSEQUENCE) and empty(LBORRES)"},
                 "Outcome":{"Message":"m","Output_Variables":["LBORRES","!LBSEQUENCE"]}}""");
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals(List.of("LBORRES"), rule.getEffectiveOutputVariables());
    }


    @Test
    void excludingAWildcardThatIsNotALocationVariableIsAccepted() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"empty(--STDTC) and empty(--TERM)"},
                 "Outcome":{"Message":"m","Output_Variables":["--TERM","!--STDTC"]}}""");
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals(List.of("--TERM"), rule.getEffectiveOutputVariables());
    }

    // ------------------------------------------------------------ check 5: malformed


    @Test
    void bareAndStackedMarkersAreLoadErrors() throws Exception
    {
        Rule bare = load(recordRule("AETERM", "!"));
        assertNotNull(bare.getLoadError());
        assertTrue(bare.getLoadError().contains("bare '!'"), bare.getLoadError());
        Rule stacked = load(recordRule("AETERM", "!!AEDECOD"));
        assertNotNull(stacked.getLoadError());
        assertTrue(stacked.getLoadError().contains("stacks the exclusion marker"),
                stacked.getLoadError());
    }


    @Test
    void aWellFormedMarkerOnADerivedDottedOrBindingNameIsAccepted() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Scope":{"Domains":{"Include":["AE"]}},
                 "Match_Datasets":[{"Name":"DM","Keys":["USUBJID"]}],
                 "Bindings":[{"name": "$n", "expression": "record_count(domain=\\"DM\\")"}],
                 "Check":{"expression":"not empty(AETERM) and DM.ARM != AETERM and $n > 1"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","!DM.ARM","!$n"]}}""");
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals(List.of("AETERM"), rule.getEffectiveOutputVariables());
        assertEquals(List.of("DM.ARM", "$n"),
                List.copyOf(rule.excludedOutputVariablesOrAuthored()));
    }


    @Test
    void everyFindingIsReportedInOneMessage() throws Exception
    {
        Rule rule = load(recordRule("AEDECOD", "!AEDECOD", "!", "!AEZZZ"));
        String err = rule.getLoadError();
        assertNotNull(err);
        assertTrue(err.startsWith("[R1] Outcome.Output_Variables: "), err);
        assertTrue(err.contains("both authored and excluded"), err);
        assertTrue(err.contains("bare '!'"), err);
        assertTrue(err.contains("!AEZZZ names nothing"), err);
    }


    @Test
    void aRuleWithoutAnOutcomeOrWithoutTokensIsUntouched() throws Exception
    {
        Rule none = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"not empty(AETERM)"}}""");
        assertNull(none.getLoadError());
        assertEquals(Set.of(), none.excludedOutputVariablesOrAuthored());
        Rule plain = load(recordRule("AEDECOD", "AETERM"));
        assertNull(plain.getLoadError());
        assertEquals(Set.of(), plain.excludedOutputVariablesOrAuthored());
    }

    // ------------------------------------------------------------ the kill-switch door


    @Test
    void killSwitchFallbackStripsTokensAndAppliesTheExclusion() throws Exception
    {
        System.setProperty("corej.autoOutputVariables", "false");
        try
        {
            Rule rule = load(recordRule("AETERM", "AESEV", "!AESEV", "!AEDECOD"));
            // E-3 still ran: AESEV is both authored and excluded.
            assertNotNull(rule.getLoadError());
            assertTrue(rule.getLoadError().contains("AESEV is both authored and excluded"),
                    rule.getLoadError());

            Rule ok = load(recordRule("AETERM", "AESEV", "!AEDECOD"));
            assertNull(ok.getLoadError(), ok.getLoadError());
            assertNull(ok.getEffectiveOutputVariables(), "derivation is off");
            // the fallback list carries neither the token nor the excluded name
            assertEquals(List.of("AETERM", "AESEV"), ok.effectiveOutputVariablesOrAuthored());
            assertEquals(Set.of("AEDECOD"), ok.excludedOutputVariablesOrAuthored());

            IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AETERM", "H")
                    .col("AEDECOD", "").col("AESEV", "MILD").build();
            RuleExecutionResult result = RuleRunnerCalls.execute(ok, ae);
            assertEquals(1, result.getViolationCount(), "status=" + result.getStatus());
            Map<String, String> values = result.getViolations().get(0).getValues();
            assertEquals("H", values.get("AETERM"));
            assertEquals("MILD", values.get("AESEV"));
            assertFalse(values.containsKey("AEDECOD"), values.toString());
            assertFalse(values.containsKey("!AEDECOD"), values.toString());
        }
        finally
        {
            System.clearProperty("corej.autoOutputVariables");
        }
    }

    // ------------------------------------------------------------ the three $-id removers


    @Test
    void anExclusionTokenOnACompiledBindingIsHonoured() throws Exception
    {
        // Runbook W2a: `$ae_present` is the compiled binding var_exists("AETERM") — no operation,
        // no inliner. An exclusion token `!$ae_present` is kept as authored and excludes the
        // report (E-2); nothing is dropped for it. The binding's quoted target AETERM is derived
        // (D4b, as a retained variable_exists(AETERM) operation's was) and an exclusion removes
        // only the excluded NAME — an excluded `!$grp` over distinct(X) keeps X the same way.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name": "$ae_present", "expression": "var_exists(\\"AETERM\\")"}],
                 "Check":{"all":[{"expression": "$ae_present == false"},
                                 {"expression": "empty(AESEV)"}]},
                 "Outcome":{"Message":"m","Output_Variables":["AESEV","!$ae_present"]}}""");
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertNotNull(rule.compiledBinding("$ae_present"), "the binding is a compiled one");
        assertEquals(List.of("AESEV", "!$ae_present"), rule.getOutcome().getOutputVariables());
        assertEquals(List.of("AESEV", "AETERM"), rule.getEffectiveOutputVariables(),
                "the exclusion removes the binding from the report, not its derived target");
    }


    @Test
    void aReportedCompiledBindingIsKeptAsAuthored() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name": "$ae_present", "expression": "var_exists(\\"AETERM\\")"}],
                 "Check":{"all":[{"expression": "$ae_present == false"},
                                 {"expression": "empty(AESEV)"}]},
                 "Outcome":{"Message":"m","Output_Variables":["AESEV","$ae_present"]}}""");
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertNotNull(rule.compiledBinding("$ae_present"), "the binding is a compiled one");
        assertEquals(List.of("AESEV", "$ae_present"), rule.getOutcome().getOutputVariables());
        // AETERM: the binding's D4b target, as the retained variable_exists(AETERM) operation's
        // was before W2a (CDISC-CG0105's rulespec pins the same on EXVAMT).
        assertEquals(List.of("AESEV", "$ae_present", "AETERM"), rule.getEffectiveOutputVariables());
    }


    /**
     * ⭐ Phase 7b changed this test's premise, and wave 0 changed it again
     * ({@code PLAN-binding-expressions}). {@code split_by} was never an operation (the
     * {@code OperationType} retired in runbook W8 had no {@code SPLIT_BY} — a broadcast operation
     * could not produce a per-row list), so after 7b a binding that declared it failed LOUD as an
     * unknown operation. Since wave 0 a {@code Bindings:} entry may hold ANY expression: the
     * declared form is a <b>compiled binding</b> — the per-row token list, exactly what the inline
     * value function computes — and loads. Being list-valued it is <b>bulk</b> (R15, as a list
     * operation's result was): its id is never derived as an output variable, so an {@code !$tok}
     * exclusion names nothing the rule derives and is the E-3.1 load error, as it was for a bulk
     * operation. The corpus authors the inline spelling (the corpus repo's
     * {@code SplitByOperationCorpusGateTest} held that line until runbook W8), and the inline
     * spelling keeps loading.
     */
    @Test
    void aDeclaredSplitByBindingIsACompiledBindingAndTheInlineSpellingLoads() throws Exception
    {
        Rule declared = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name": "$tok", "expression": "split_by(AESPEC, delimiter=\\"/\\")"}],
                 "Check":{"all":[{"expression": "contains($tok, \\"X\\")"},
                                 {"expression": "empty(AESEV)"}]},
                 "Outcome":{"Message":"m","Output_Variables":["AESEV"]}}""");
        assertNull(declared.getLoadError(), declared.getLoadError());
        assertNotNull(declared.compiledBinding("$tok"), "it is a compiled binding");
        assertFalse(declared.getEffectiveOutputVariables().contains("$tok"),
                "a list-valued binding is bulk: " + declared.getEffectiveOutputVariables());

        Rule excluded = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name": "$tok", "expression": "split_by(AESPEC, delimiter=\\"/\\")"}],
                 "Check":{"all":[{"expression": "contains($tok, \\"X\\")"},
                                 {"expression": "empty(AESEV)"}]},
                 "Outcome":{"Message":"m","Output_Variables":["AESEV","!$tok"]}}""");
        assertNotNull(excluded.getLoadError(), "a bulk id is never derived, so !$tok is E-3.1");
        assertTrue(excluded.getLoadError().contains("!$tok names nothing the rule derives"),
                excluded.getLoadError());

        Rule inline = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"all":[{"expression": "contains(split_by(AESPEC, \\"/\\"), \\"X\\")"},
                                 {"expression": "empty(AESEV)"}]},
                 "Outcome":{"Message":"m","Output_Variables":["AESEV"]}}""");
        assertNull(inline.getLoadError(), inline.getLoadError());
        assertEquals(List.of("AESEV"), inline.getOutcome().getOutputVariables());
    }
}
