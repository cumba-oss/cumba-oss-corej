package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.RegistryTestSeam;
import net.cumba.corej.core.expr.eval.UngatedProviderReachException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.ValueMapDictionary;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * The two dictionary functions of wave 1 phase 3 ({@code PLAN-function-surface-wave1}), beyond what
 * {@code DictionaryValidationTest} pins end to end (H1 blanks, case folding, the eager
 * no-dictionary SKIP): the <b>loud arm</b> of D-W1-3 (vii) — a function reached with no provider,
 * or with its type unavailable, throws instead of answering its {@code PREDICATE} default — and the
 * load-error negative controls of R1 / R6 / D-W1-3 (iv)–(v): a quoted term or parent column, a
 * missing target or companion column, a missing dictionary type, and a dictionary type that is not
 * a static string literal all fail to load. Mockito-free: real vectors, the checked-in dummy
 * dictionaries, the real loader.
 */
class DictionaryFunctionsTest
{

    private static final String UNII = "unii";

    // ------------------------------------------------------------------ the loud arm

    @Test
    void reachedWithNoProviderTheFunctionsThrowInsteadOfAnsweringFalse()
    {
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(ts()).build());
        List<Vector> pair = Arrays.asList(ConstVector.of("R16CO5Y76E"), ConstVector.of("ASPIRIN"),
                ConstVector.of(UNII), null);
        UngatedProviderReachException ex = assertThrows(UngatedProviderReachException.class,
                () -> DictionaryFunctions.codeTermPair(run, pair));
        assertTrue(ex.getMessage().contains("no external-dictionary provider")
                && ex.getMessage().contains(UNII), ex.getMessage());
        List<Vector> hierarchy = Arrays.asList(ConstVector.of("Headaches NEC"),
                ConstVector.of("Nervous system disorders"), ConstVector.of("meddra"), null);
        assertThrows(UngatedProviderReachException.class,
                () -> DictionaryFunctions.hierarchy(run, hierarchy));
    }


    @Test
    void reachedWithTheTypeNotLoadedTheFunctionsThrow() throws Exception
    {
        // A bundle holding only MedDRA: a provider is present, the rule's own type is not.
        RuntimeDictionaryProvider meddraOnly = new RuntimeDictionaryProvider(
                Map.of("meddra", ValueMapDictionary.load(Paths.get("dictionaries/meddra.json"))));
        EvaluationContext ctx = EvaluationContext.builder().table(ts())
                .dictionaryProvider(meddraOnly).build();
        EvalRun run = EvalRun.fullRange(ctx);
        List<Vector> pair = Arrays.asList(ConstVector.of("R16CO5Y76E"), ConstVector.of("ASPIRIN"),
                ConstVector.of(UNII), null);
        UngatedProviderReachException ex = assertThrows(UngatedProviderReachException.class,
                () -> DictionaryFunctions.codeTermPair(run, pair));
        assertTrue(ex.getMessage().contains(UNII), "the type and its state are named: " + ex);
    }


    @Test
    void aFunctionReachedPastItsGateMakesTheRuleReportError() throws Exception
    {
        // The rule-level half of D-W1-3 (vii): RuleRunner turns the tripwire into a rule ERROR
        // (with the message), never an escaped exception and never a verdict. A synthetic
        // registry function stands in for a dictionary function whose gate was bypassed, because
        // with the gates intact the real ones cannot be reached (the S3a sabotage is the
        // end-to-end proof, recorded in the plan's status file).
        FunctionDescriptor loud = new FunctionDescriptor("__w1_loud__", List.of(),
                FunctionKind.VALUE, (run, args) ->
                {
                    throw new UngatedProviderReachException("__w1_loud__",
                            "with no provider at all");
                });
        try (var _ = RegistryTestSeam.register(loud))
        {
            String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-LOUD\"},"
                    + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\"__w1_loud__()\"}],"
                    + "\"Check\":{\"expression\":\"$v == 1\"}}}}";
            Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
            assertNotNull(rule);
            assertNull(rule.getLoadError(), rule.getLoadError());
            RuleExecutionResult result = RuleRunnerCalls.execute(rule, ts());
            assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
            String message = result.getStatusMessage();
            assertTrue(message.contains("__w1_loud__ reached with no provider"), message);
            // Review round 2 L-11: the rules repository's ViolationNormaliser classifies this
            // ERROR by its fixed message TAIL (UNGATED_PROVIDER_REACH_MESSAGE_SUFFIX, matched
            // with endsWith), so any decoration RuleRunner appends would silently re-route it.
            assertTrue(message.endsWith(" — the provider gate should have skipped this rule"
                    + " before any row was read"), message);
            // …and RuleRunner's own catch passes the exception's message through VERBATIM —
            // status message and the __error__ sentinel alike.
            String thrown = new UngatedProviderReachException("__w1_loud__",
                    "with no provider at all").getMessage();
            assertEquals(thrown, message, "the ERROR status carries the tripwire's own message");
            assertEquals(1, result.getViolations().size(), "one ERROR sentinel");
            assertEquals(thrown, result.getViolations().get(0).getValues().get("__error__"),
                    "the sentinel carries the same message");
        }
    }

    // ------------------------------------------------------------------ negative controls


    @Test
    void aQuotedCompanionColumnFailsToLoad() throws Exception
    {
        // R1: the release note's retired spellings. A quoted name is a string literal, never a
        // column; the function's column parameters refuse it at compile time.
        for (String spelling : List.of(
                "valid_external_dictionary_code_term_pair(TSVALCD, \\\"TSVAL\\\","
                        + " external_dictionary_type=\\\"unii\\\")",
                "valid_external_dictionary_code_term_pair(TSVALCD,"
                        + " external_dictionary_term_variable=\\\"TSVAL\\\","
                        + " external_dictionary_type=\\\"unii\\\")",
                "valid_external_dictionary_hierarchy(AEHLT, dictionary_parent=\\\"AESOC\\\","
                        + " external_dictionary_type=\\\"meddra\\\")"))
        {
            String error = loadError(spelling);
            assertTrue(error.contains("column reference"), spelling
                    + " must fail on the literal-where-a-column-is-required seam: " + error);
        }
    }


    @Test
    void aMissingCompanionColumnIsAnArityLoadError() throws Exception
    {
        // R6 / P-Q8: a target alone bound silently on the operation surface.
        String error = loadError(
                "valid_external_dictionary_code_term_pair(TSVALCD, external_dictionary_type=\\\"unii\\\")");
        assertTrue(error.contains("external_dictionary_term_variable"), error);
        error = loadError(
                "valid_external_dictionary_hierarchy(AEHLT, external_dictionary_type=\\\"meddra\\\")");
        assertTrue(error.contains("dictionary_parent"), error);
    }


    @Test
    void aMissingOrNonLiteralDictionaryTypeIsALoadError() throws Exception
    {
        // D-W1-3 (iv): no type at all is the binder's arity error.
        String missing = loadError("valid_external_dictionary_code_term_pair(TSVALCD, TSVAL)");
        assertTrue(missing.contains("external_dictionary_type"), missing);
        // D-W1-3 (v): a type that is not a static string literal cannot be gated before the rows
        // are read, so the typeless-dictionary guard refuses it — for BOTH functions. The message
        // names the surface (the binding) and the defect (bound to a column, not a literal) —
        // review round 1 lane 2 L5: it used to say "declares no external_dictionary_type".
        for (String spelling : List.of(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=TSPARMCD)",
                "valid_external_dictionary_hierarchy(AEHLT, AESOC,"
                        + " external_dictionary_type=AESOC)"))
        {
            String error = loadError(spelling);
            assertTrue(
                    error.contains("in binding $v binds external_dictionary_type to the column"
                            + " reference") && error.contains("not a static string literal")
                            && !error.contains("declares no external_dictionary_type"),
                    spelling + ": " + error);
        }
        // Review round 2 L-4: a BLANK string literal is its own defect — the old wording read
        // "binds external_dictionary_type to the string literal , not a static string literal".
        for (String spelling : List.of(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\\\"\\\")",
                "valid_external_dictionary_hierarchy(AEHLT, AESOC,"
                        + " external_dictionary_type=\\\"  \\\")"))
        {
            String error = loadError(spelling);
            assertTrue(
                    error.contains("in binding $v declares a blank external_dictionary_type")
                            && !error.contains("not a static string literal"),
                    spelling + ": " + error);
        }
        // The same call inline in the Check names the Check as its surface.
        String inline = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-DICT\"},"
                + "\"Check\":{\"expression\":\"valid_external_dictionary_code_term_pair(TSVALCD,"
                + " TSVAL, external_dictionary_type=TSPARMCD) == false\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(inline).getRules().get("x");
        assertNotNull(rule);
        assertNotNull(rule.getLoadError(), "the inline non-literal type is a load error too");
        assertTrue(rule.getLoadError().contains("in the Check binds external_dictionary_type"),
                rule.getLoadError());
    }


    @Test
    void aNonLiteralCaseSensitiveIsALoadError() throws Exception
    {
        // Review round 1 lane 2 M1: the retired operation parsed case_sensitive as a boolean
        // field; the function reads the flag as Boolean.FALSE.equals(value(0)) — a column bound
        // there would silently mean "case-sensitive". The STATICNESS half of the compile seam:
        // a column (unknown to stage A) is refused at the compile sites, for both functions and
        // both argument forms (PLAN-stage-a-parameter-type-arming S2, the kept half).
        for (String spelling : List.of(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\\\"unii\\\", case_sensitive=TSPARMCD)",
                "valid_external_dictionary_hierarchy(AEHLT, AESOC,"
                        + " external_dictionary_type=\\\"meddra\\\", case_sensitive=AESOC)"))
        {
            String error = loadError(spelling);
            assertTrue(error.contains("case_sensitive") && error.contains("boolean literal"),
                    spelling + ": " + error);
        }
        assertNull(load("valid_external_dictionary_code_term_pair(TSVALCD, TSVAL, \\\"unii\\\","
                + " false)").getLoadError(), "the positional boolean literal binds");
    }


    @Test
    void aWrongTypedLiteralCaseSensitiveIsALoadError() throws Exception
    {
        // The LITERAL half (PLAN-stage-a-parameter-type-arming S2): a string or a number where a
        // boolean literal is required. In a BINDING the compile site answers first (the bindings
        // compile before stage A runs), so the seam's wording is pinned here; the same literal
        // inline in a Check is the armed stage A's (RegistryCallSeamsTest).
        for (String spelling : List.of(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\\\"unii\\\", case_sensitive=\\\"false\\\")",
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL, \\\"unii\\\", 0)"))
        {
            String error = loadError(spelling);
            assertTrue(error.contains("case_sensitive") && error.contains("boolean literal"),
                    spelling + ": " + error);
        }
    }


    @Test
    void theWellFormedCallsLoadCleanlyInBothArgumentForms() throws Exception
    {
        for (String spelling : List.of(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\\\"unii\\\")",
                "valid_external_dictionary_code_term_pair(TSVALCD,"
                        + " external_dictionary_term_variable=TSVAL,"
                        + " external_dictionary_type=\\\"unii\\\", case_sensitive=false)",
                "valid_external_dictionary_hierarchy(AEHLT, AESOC,"
                        + " external_dictionary_type=\\\"meddra\\\")",
                "valid_external_dictionary_hierarchy(AEHLT, dictionary_parent=AESOC,"
                        + " external_dictionary_type=\\\"meddra\\\")"))
        {
            Rule rule = load(spelling);
            assertNull(rule.getLoadError(), spelling + ": " + rule.getLoadError());
            assertEquals(List.of(spelling.contains("unii") ? UNII : "meddra"),
                    List.copyOf(ProviderNeeds.ofBindings(rule).dictionaryTypes()),
                    "the provider capability names the type for the gates");
        }
    }

    // ------------------------------------------------------------------ helpers


    private static IDataTable ts()
    {
        return RealTables.of("TS").str("TSPARMCD", "TRT").str("TSVALCD", "R16CO5Y76E")
                .str("TSVAL", "ASPIRIN").build();
    }


    private static Rule load(String binding) throws IOException
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-DICT\"},"
                + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\"" + binding + "\"}],"
                + "\"Check\":{\"expression\":\"$v == false\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        return rule;
    }


    private static String loadError(String binding) throws IOException
    {
        Rule rule = load(binding);
        assertNotNull(rule.getLoadError(), "expected a load error for " + binding);
        return rule.getLoadError();
    }

}
