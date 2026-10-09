package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Runbook W4 E6b ({@code PLAN-engine-gap-list-elementwise} §6.1 Q1, elementwise Q4; coordinator
 * W4-D7): {@code contains} / {@code does_not_contain} / {@code starts_with} / {@code ends_with}
 * take ONE needle at {@code value}. A list there is stage A's {@code PARAMETER_TYPE} with the hint
 * - {@code contains} "use contains_any(x, list)", {@code does_not_contain} "use not contains_any(x,
 * list)", {@code starts_with} / {@code ends_with} "has no list form" - and, when only the run knows
 * it is a list, the rule's ERROR with the same sentence. Before E6b the kernel rendered the list
 * ({@code "[a, b]"}) and probed that text: silently false. The haystack-list path
 * ({@code contains($blfl, "Y")}, membership) is unchanged.
 */
class SubstringNeedleListTest
{

    private static final String PROBE = "__e6b_list_probe__";

    /** An unknown-typed producer of a list known only at run time. */
    private static FunctionDescriptor probe()
    {
        return new FunctionDescriptor(PROBE, List.of(), FunctionKind.VALUE,
                (run, args) -> ConstVector.of(List.of("ACHE", "SEA")));
    }


    private static Vector cells(String... values)
    {
        return new ComputedVector(values.length, DataValueType.STRING, row -> values[row]);
    }


    private static IDataTable ae()
    {
        return MockTable.of().col("AETERM", "HEADACHE", "NAUSEA", "RASH").name("AE").build();
    }


    private static Rule load(String check, String... bindings)
    {
        java.util.Map<String, Object> rule = new java.util.LinkedHashMap<>();
        rule.put("Core", java.util.Map.of("Id", "T-E6B"));
        rule.put("Scope", java.util.Map.of("Domains", java.util.Map.of("Include", List.of("AE"))));
        List<java.util.Map<String, String>> declared = new java.util.ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(java.util.Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        if (!declared.isEmpty())
        {
            rule.put("Bindings", declared);
        }
        rule.put("Check", java.util.Map.of("expression", check));
        // a probe declares no result type: every binding is excluded from the report (!$x)
        rule.put("Outcome",
                java.util.Map.of("Message", "m", "Output_Variables",
                        declared.stream().filter(d -> d.get("expression").startsWith("__"))
                                .map(d -> "!" + d.get("name")).toList()));
        try
        {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of("rules", java.util.Map.of("x", rule)));
            Rule loaded = net.cumba.corej.core.RulePackageLoader.loadFromString(json).getRules()
                    .get("x");
            assertNotNull(loaded, "the rule parses");
            return loaded;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static void assertHint(String error, String function, String remedy)
    {
        assertTrue(error.contains("argument 'value' of '" + function + "' takes one value"), error);
        assertTrue(error.contains(remedy), error);
    }


    private static String remedy(String function)
    {
        return switch (function)
        {
        case "contains" -> "use contains_any(x, list)";
        case "does_not_contain" -> "use not contains_any(x, list)";
        default -> function + " has no list form";
        };
    }

    // ---- the kernel ----


    @Test
    void aListNeedleIsTheRulesErrorInTheKernel()
    {
        Vector x = cells("HEADACHE");
        Vector needle = ConstVector.of(List.of("ACHE", "SEA"));
        assertHint(
                assertThrows(RuleDefinitionException.class,
                        () -> Primitives.contains(x, needle, 1, false)).getMessage(),
                "contains", "use contains_any(x, list)");
        assertHint(
                assertThrows(RuleDefinitionException.class,
                        () -> Primitives.contains(x, needle, 1, true)).getMessage(),
                "does_not_contain", "use not contains_any(x, list)");
        assertHint(
                assertThrows(RuleDefinitionException.class,
                        () -> Primitives.startsWith(x, needle, 1)).getMessage(),
                "starts_with", "starts_with has no list form");
        assertHint(
                assertThrows(RuleDefinitionException.class, () -> Primitives.endsWith(x, needle, 1))
                        .getMessage(),
                "ends_with", "ends_with has no list form");
    }


    @Test
    void theHaystackListPathIsUnchanged()
    {
        // contains over a list haystack is membership (EC-28(a)); a scalar needle there is legal
        Vector haystack = ConstVector.of(List.of("Y", "N"));
        BitSet hit = Primitives.contains(haystack, ConstVector.of("Y"), 1, false);
        assertTrue(hit.get(0));
        assertTrue(Primitives.contains(haystack, ConstVector.of("YES"), 1, false).isEmpty());
    }

    // ---- stage A and the run ----


    @Test
    void aStaticListNeedleIsALoadErrorWithTheHint()
    {
        for (String function : List.of("contains", "does_not_contain", "starts_with", "ends_with"))
        {
            for (String[] form : new String[][]
            {
                    {
                            function + "(AETERM, [\"ACHE\", \"SEA\"])"
                    },
                    {
                            function + "(AETERM, $l)", "$l", "[\"ACHE\", \"SEA\"]"
                    },
                    {
                            function + "(AETERM, $l)", "$l", "split_by(AETERM, \",\")"
                    }
            })
            {
                String[] bindings = java.util.Arrays.copyOfRange(form, 1, form.length);
                String error = load(form[0], bindings).getLoadError();
                assertNotNull(error, form[0] + " must be a load error");
                assertHint(error, function, remedy(function));
            }
        }
    }


    @Test
    void aListNeedleKnownOnlyAtRunTimeErrorsTheRule()
    {
        try (RegistryTestSeam.Registration _ = RegistryTestSeam.register(probe()))
        {
            for (String function : List.of("contains", "does_not_contain", "starts_with",
                    "ends_with"))
            {
                Rule rule = load(function + "(AETERM, $u)", "$u", PROBE + "()");
                assertNull(rule.getLoadError(), rule.getLoadError());
                // the rule's ERROR: LibraryValidator.executeRule turns the throw into the result
                RuleDefinitionException error = assertThrows(RuleDefinitionException.class,
                        () -> RuleRunnerCalls.execute(rule, ae()), function);
                assertHint(error.getMessage(), function, remedy(function));
            }
        }
    }


    @Test
    void aScalarNeedleAndAListHaystackAnswerAsBefore()
    {
        Rule scalar = load("contains(AETERM, \"ACHE\")");
        RuleExecutionResult result = RuleRunnerCalls.execute(scalar, ae());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of(1L),
                result.getViolations().stream().map(Violation::getRowNumber).toList());
        // a list haystack with a scalar needle loads (membership)
        assertNull(load("contains($l, \"ACHE\")", "$l", "[\"ACHE\", \"SEA\"]").getLoadError());
    }
}
