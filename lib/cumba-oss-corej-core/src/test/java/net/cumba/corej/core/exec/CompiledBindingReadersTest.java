package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.OperationKinds;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.RegistryTestSeam;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Wave 0 ({@code PLAN-binding-expressions} §5.1): every reader that resolved a {@code $}-name
 * against {@code getOperations()} alone must see a <b>compiled</b> binding too. One negative
 * control per silent-hole row — each asserts the widened behaviour, which the pre-wave-0 reader
 * could not produce (the binding would have been invisible to it).
 */
class CompiledBindingReadersTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Rule load(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "W0-READERS"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        rule.put("Bindings", declared);
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", outputs));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        return Objects.requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
    }


    private static Rule loadClean(String check, String... bindings) throws Exception
    {
        Rule rule = load(check, List.of(), bindings);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1", "S2").col("AETERM", "A", "B")
                .col("AESEQ", "1", "2").col("EXSTDTC", "2020-01-01", "2020-01-02").build();
    }

    // ------------------------------------------------------------------ R2 polarity guard


    @Test
    void anIndeterminateOperationConsumedThroughACompiledBindingIsRejected() throws Exception
    {
        // `$c > EXSTDTC` names only $c; read through, it is `date($m) > EXSTDTC` — the silencing
        // positive leaf the guard exists for.
        Rule rule = load("$c > EXSTDTC", List.of(), "$m",
                "min_date(EXSTDTC, missing_values=\"indeterminate\")", "$c", "date($m)");
        assertNotNull(rule.getLoadError(), "R2: consumed through the compiled binding");
        assertTrue(rule.getLoadError().contains("positive-polarity leaf `greater_than`"),
                rule.getLoadError());
    }


    @Test
    void anIndeterminateInlineCallInsideACompiledBindingIsRejected() throws Exception
    {
        Rule rule = load("$c > EXSTDTC", List.of(), "$c",
                "date(min_date(EXSTDTC, missing_values=\"indeterminate\"))");
        assertNotNull(rule.getLoadError(), "R2: the inline call lives inside the binding");
        assertTrue(rule.getLoadError().contains("positive-polarity leaf"), rule.getLoadError());
    }


    @Test
    void aComparisonInsideAConditionBindingIsAConsumer() throws Exception
    {
        Rule rule = load("$b == true", List.of(), "$m",
                "min_date(EXSTDTC, missing_values=\"indeterminate\")", "$b", "date($m) > EXSTDTC");
        assertNotNull(rule.getLoadError(), "R2: the binding itself compares the extreme");
        assertTrue(rule.getLoadError().contains("positive-polarity leaf `greater_than`"),
                rule.getLoadError());
    }

    // ------------------------------------------------------------------ R4 inliner eligibility


    @Test
    void anOperationACompiledBindingReadsIsNeverInlinedAway() throws Exception
    {
        Rule rule = loadClean("$v == true and $x == true", "$v", "variable_exists(AETERM)", "$x",
                "$v");
        assertNotNull(rule.getOperations(), "R4: $v is read by $x, so it is not dropped");
        assertEquals("$v", rule.getOperations().get(0).getId());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "AETERM exists, so $x (= $v) is true — the dataset-level verdict fires once; a"
                        + " dropped $v would have left $x dangling and the rule silent");
    }

    // ------------------------------------------------------------------ R7 / R8 load gates


    @Test
    void anUnresolvedWildcardInsideACompiledBindingIsALoadError() throws Exception
    {
        Rule rule = load("$last == true", List.of(), "$last",
                "is_last_in_group(group=[USUBJID], ordering=\"--SEQ\") == true");
        assertNotNull(rule.getLoadError(),
                "R7: the nested operation call is on the inline surface");
        assertTrue(rule.getLoadError().contains("is not `--`-resolved"), rule.getLoadError());
    }


    @Test
    void aTypelessDictionaryCallInsideACompiledBindingIsALoadError() throws Exception
    {
        Rule rule = load("$ok == true", List.of(), "$ok",
                "valid_external_dictionary_value(AETERM) == true");
        assertNotNull(rule.getLoadError(), "R8: the nested dictionary call names no type");
        assertTrue(rule.getLoadError().contains("installing dictionaries cannot help"),
                rule.getLoadError());
    }

    // ------------------------------------------------------------------ R9 kinds


    @Test
    void aRecordLevelCompiledBindingIsPerRowForLevelInference() throws Exception
    {
        Rule rule = loadClean("$u == \"A\"", "$u", "upper(AETERM)", "$n", "record_count() + 0");
        OperationKinds kinds = OperationKinds.forRule(rule);
        assertEquals(OperationExecutor.ResultKind.PER_ROW, kinds.kindOf("$u"));
        assertEquals(OperationExecutor.ResultKind.SCALAR, kinds.kindOf("$n"));
    }

    // ------------------------------------------------------------------ R11 specialiser


    @Test
    void theSpecialiserResolvesACompiledBindingAndSetsItOnTheCopy() throws Exception
    {
        Rule template = loadClean("$u == \"A\"", "$u", "upper(--TERM)");
        Rule specialised = Objects.requireNonNull(RuleSpecialiser.specialise(template, ae(), "AE"));
        assertEquals("upper(AETERM)", net.cumba.corej.core.expr.ExpressionPrinter
                .print(specialised.compiledBinding("$u").expression()));
        assertEquals("upper(--TERM)",
                net.cumba.corej.core.expr.ExpressionPrinter
                        .print(template.compiledBinding("$u").expression()),
                "the template is never mutated — the copy carries the resolved list");
        RuleExecutionResult result = RuleRunnerCalls.execute(template, ae(), _ -> null, "AE");
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(), "AETERM=A on row 0");
    }

    // ------------------------------------------------------------------ R12–R14 absent datasets


    @Test
    void aDottedReadInsideACompiledBindingIsAForeignDatasetRead() throws Exception
    {
        Rule rule = loadClean("$d == \"X\"", "$d", "DM.RFSTDTC");
        Expr check = Objects.requireNonNull(rule.getCheckExpr());
        assertTrue(AbsentDatasetSkip.referencedDatasets(rule, check).contains("DM"),
                "R13: the binding's dotted reference is a candidate");
        assertTrue(AbsentDatasetSkip.readsAny(check, rule, Set.of("DM")),
                "R14: `$d == \"X\"` reads DM through the binding");
        assertFalse(AbsentDatasetSkip.readsAny(check, rule, Set.of("EX")));
    }


    @Test
    void aNestedDomainCallInsideACompiledBindingIsAForeignDatasetRead() throws Exception
    {
        Rule rule = loadClean("$n > 0", "$n", "record_count(domain=\"EX\") + 0");
        Expr check = Objects.requireNonNull(rule.getCheckExpr());
        assertTrue(AbsentDatasetSkip.referencedDatasets(rule, check).contains("EX"));
        assertTrue(AbsentDatasetSkip.readsAny(check, rule, Set.of("EX")));
    }

    // ------------------------------------------------------------------ R15 / R16 output variables


    @Test
    void aCompiledBindingDerivesItsIdAndItsTargetColumnsOnly() throws Exception
    {
        Rule rule = loadClean("$s == \"A\"", "$s", "substring(AETERM, 1, len(USUBJID))");
        List<String> effective = rule.getEffectiveOutputVariables();
        assertTrue(effective.contains("$s"), "D4a: " + effective);
        assertTrue(effective.contains("AETERM"), "D4b: the target column " + effective);
        assertFalse(effective.contains("USUBJID"),
                "a parameter column is not an input the finding reports: " + effective);
    }


    @Test
    void aListValuedCompiledBindingIsBulkAndNeverDerived() throws Exception
    {
        Rule rule = loadClean("contains($t, \"X\")", "$t", "split_by(AETERM, \"/\")");
        List<String> effective = rule.getEffectiveOutputVariables();
        assertFalse(effective.contains("$t"), "R15: a list result is bulk: " + effective);
        assertTrue(effective.contains("AETERM"), effective.toString());
    }

    // ------------------------------------------------------------------ R19 / R27 classifiers


    @Test
    void theClassifierReadsACompiledBindingThroughNotAsTheWorstCase() throws Exception
    {
        // `$dup == true` over a condition binding reads as the condition itself — the identity the
        // compiler applies (boolEqLiteral) — so it derives what the bare inline call derives, not
        // the UNRESOLVED worst case a miss against getOperations() used to degrade to.
        Rule bound = loadClean("$dup == true", "$dup", "is_not_unique_set([USUBJID])");
        Rule inline = loadClean("is_not_unique_set([USUBJID])");
        assertNotNull(inline.getSensitivity());
        assertEquals(inline.getSensitivity(), bound.getSensitivity(),
                "R19: the derived Sensitivity reads the binding like the inline leaf");
        Rule negated = loadClean("$dup == false", "$dup", "is_not_unique_set([USUBJID])");
        Rule negatedInline = loadClean("not is_not_unique_set([USUBJID])");
        assertEquals(negatedInline.getSensitivity(), negated.getSensitivity());
    }


    @Test
    void aStudyLevelCompiledBindingDoesNotReadThePrimaryDataset() throws Exception
    {
        Rule reads = loadClean("$u == \"A\"", "$u", "upper(AETERM)");
        assertTrue(StudyRuleClassifier.readsPrimaryDataset(reads));
        Rule studyLevel = loadClean("$n > 0", "$n", "record_count(domain=\"DM\") + 0");
        assertFalse(StudyRuleClassifier.readsPrimaryDataset(studyLevel),
                "R27: walked, not assumed the worst");
    }

    // ------------------------------------------------------------------ R17 / R20 / I1 providers


    @Test
    void aDictionaryCallNestedInACompiledBindingIsAProviderNeed() throws Exception
    {
        Rule rule = loadClean("$ok == true", "$ok",
                "valid_external_dictionary_value(AETERM, external_dictionary_type=\"meddra\")"
                        + " == true");
        ProviderNeeds needs = ProviderNeeds.ofBindings(rule);
        assertTrue(needs.dictionary());
        assertEquals(List.of("meddra"), List.copyOf(needs.dictionaryTypes()));
        assertTrue(ProviderRequirements.of(rule).dictionary(), "R20 surface 1");
        RuleExecutionResult none = RuleRunnerCalls.execute(rule, ae());
        assertEquals(RuleExecutionStatus.SKIPPED, none.getStatus(), "R10: no dictionary ⇒ SKIP");
        assertTrue(none.getStatusMessage().contains("meddra"), none.getStatusMessage());
    }


    @Test
    void aLibraryCapabilityInACompiledBindingSkipsWithNoProviderAndWithAnUnusableAnswer()
        throws Exception
    {
        FunctionDescriptor probe = libraryProbe("__w0_lib_binding__");
        try (var _ = RegistryTestSeam.register(probe))
        {
            Rule rule = loadClean("AETERM not in $l", "$l", "__w0_lib_binding__(AETERM)");
            assertNull(rule.getInjectedPreconditionGates(),
                    "I1 remedy: a $-bound call gets no injected gate — the runner's arms SKIP it");
            assertTrue(ProviderNeeds.ofBindings(rule).library());
            assertTrue(ProviderRequirements.of(rule).library(), "R20: the run forecast lists it");
            RuleExecutionResult noProvider = RuleRunnerCalls.execute(rule, ae());
            assertEquals(RuleExecutionStatus.SKIPPED, noProvider.getStatus());
            assertEquals("Rule skipped — no Library access", noProvider.getStatusMessage());
            RuleExecutionResult unusable = RuleRunnerCalls.execute(rule, ae(), _ -> null, null,
                    new StubMetadataProvider());
            assertEquals(RuleExecutionStatus.SKIPPED, unusable.getStatus(),
                    "the provider answered nothing usable ⇒ SKIPPED, never a PASS");
            assertEquals("Rule skipped — library returned no data for __w0_lib_binding__",
                    unusable.getStatusMessage());
        }
    }


    @Test
    void aLibraryCapabilityInlineInTheCheckGetsTheInjectedGateAndSkips() throws Exception
    {
        FunctionDescriptor probe = libraryProbe("__w0_lib_inline__");
        try (var _ = RegistryTestSeam.register(probe))
        {
            Rule rule = loadClean("AETERM not in __w0_lib_inline__(AETERM)");
            assertEquals("library_available() and available(__w0_lib_inline__(AETERM))",
                    rule.getInjectedPreconditionGates(),
                    "I1: gateTermsForCall reads the capability");
            assertTrue(ProviderRequirements.of(rule).library(), "R20 surface 3");
            RuleExecutionResult unusable = RuleRunnerCalls.execute(rule, ae(), _ -> null, null,
                    new StubMetadataProvider());
            assertEquals(RuleExecutionStatus.SKIPPED, unusable.getStatus(),
                    "§0.2 a: available(<call>) reads the unusable signal as not available");
            assertEquals("Rule skipped — precondition not met", unusable.getStatusMessage());
        }
    }


    /**
     * A LIBRARY-capable aggregate: answers ["A"] when the provider's standard is "usable", raises
     * the unusable signal otherwise (StubMetadataProvider reports "sdtmig").
     */
    private static FunctionDescriptor libraryProbe(String name)
    {
        return new FunctionDescriptor(name,
                List.of(Parameter.required("x", ExprType.Unknown.UNKNOWN)), FunctionKind.VALUE,
                (run, _) ->
                {
                    MetadataProvider library = run.ctx().getLibraryProvider();
                    if (library == null || !"usable".equals(library.getStandard()))
                    {
                        throw new UnusableProviderAnswerException(name, ProviderNeed.Kind.LIBRARY,
                                "no data");
                    }
                    return ConstVector.of(List.of("A"));
                }).withProvider(ProviderNeed.LIBRARY).aggregating();
    }

    // ------------------------------------------------------------------ I2 metadata scan


    @Test
    void aLibraryAccessorInsideACompiledBindingRequestsItsProvider() throws Exception
    {
        Rule rule = loadClean("$lab != var_label(varname(), \"DATA\")", "$lab",
                "var_label(varname(), \"LIBRARY\")");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae());
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "I2: the LIBRARY read lives in the binding, not the Check");
    }

    // ------------------------------------------------------------------ R23 rendering


    @Test
    void theRenderedRuleCarriesBothBindingKindsInAuthoredOrder() throws Exception
    {
        Rule rule = loadClean("$u == \"A\" and $n > 0", "$u", "upper(AETERM)", "$n",
                "record_count()");
        JsonNode bindings = MAPPER.readTree(
                net.cumba.corej.core.expr.convert.RulePackageExpressionJson.toExpressionJson(rule))
                .path("Bindings");
        assertEquals(2, bindings.size(), bindings.toString());
        assertEquals("$u", bindings.get(0).path("name").asText());
        assertEquals("upper(AETERM)", bindings.get(0).path("expression").asText());
        assertEquals("$n", bindings.get(1).path("name").asText());
    }

    // ------------------------------------------------------------------ R3 tuple guard (pin)


    @Test
    void aCompiledBindingNeitherCrashesNorFalseMatchesTheTupleGuard() throws Exception
    {
        Rule rule = loadClean("tuple(USUBJID, AESEQ) not in $k and $u == \"A\"", "$k",
                "distinct([USUBJID, AESEQ], domain=\"EX\")", "$u", "upper(AETERM)");
        assertNotNull(rule.compiledBinding("$u"));
    }

    // ------------------------------------------------------------------ BroadcastFold


    @Test
    void aPerRowCompiledBindingIsNeverBroadcastSafe() throws Exception
    {
        Rule rule = loadClean("$u == \"A\"", "$u", "upper(AETERM)");
        EvaluationContext ctx = EvaluationContext.builder().table(ae())
                .variables(Map.of("$u", new BindingValue(rule.compiledBinding("$u")))).build();
        assertTrue(ctx.resolveVariable("$u") instanceof Vector);
        assertFalse(net.cumba.corej.core.expr.eval.BroadcastFold
                .operationRefsSafe(Objects.requireNonNull(rule.getCheckExpr()), ctx, false));
    }

}
