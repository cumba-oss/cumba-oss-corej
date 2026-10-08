package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * The value form of the grouped readers' target ({@code distinct} / {@code max} / {@code max_date}
 * / {@code min_date} / {@code sequence}, the shared {@link GroupedAggregate#readTarget}) —
 * {@code PLAN-binding-substitutability} G1, runbook W1b DT (coordinator D21): a dataset-level
 * {@code $}-binding as the target is admitted like the expression it holds written inline
 * ({@code $x: upper("a"); distinct($x)} ≡ {@code distinct(upper("a"))}), pre-resolved against the
 * rule's dataset as a {@code $} inside {@code filter=} is (BS3); a per-row binding is the level
 * refusal at load; a binding holding a STRING LITERAL keeps the literal refusal (D-W8-6: a quoted
 * name is a string, never a column — the target is not a name slot).
 */
class AggregateTargetBindingTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static IDataTable ts()
    {
        return RealTables.of("TS").str("DOMAIN", "TS", "TS", "TS").str("TSPARM", "A", "B", "A")
                .str("TSVAL", "1", "2", "3").dbl("TSSEQ", 1d, 2d, 3d).build();
    }


    @Test
    void aDatasetLevelBindingAsTheTargetAnswersAsItsExpressionInline()
    {
        assertEquals(List.of(1L, 2L, 3L),
                fired("\"A\" in $v", "$x", "upper(\"a\")", "$v", "distinct($x)"));
        assertEquals(List.of(1L, 2L, 3L), fired("\"A\" in $v", "$v", "distinct(upper(\"a\"))"));
        assertEquals(List.of(1L, 2L, 3L),
                fired("count($v) == 1", "$x", "upper(\"a\")", "$v", "distinct($x)"),
                "one distinct value, not one per row");
        assertEquals(List.of(1L, 2L, 3L),
                fired("$m == 2", "$x", "1 + 1", "$m", "max($x, group=[TSPARM])"));
        assertEquals(List.of(1L, 2L, 3L), fired("$m == 2", "$m", "max(1 + 1, group=[TSPARM])"));
        assertEquals(List.of(1L, 2L, 3L), fired("$md == \"2020-01-15\"", "$d",
                "concat(\"2020-01-\", \"15\")", "$md", "max_date($d, group=[TSPARM])"));
        assertEquals(List.of(1L, 2L, 3L), fired("$md == \"2020-01-15\"", "$d",
                "concat(\"2020-01-\", \"15\")", "$md", "min_date($d, group=[TSPARM])"));
        assertEquals(List.of(1L, 2L, 3L), fired("contains($s, \"A\")", "$x", "upper(\"a\")", "$s",
                "sequence($x, group=[TSPARM])"));
        // a binding nested inside a computed target is pre-resolved the same way
        assertEquals(List.of(1L, 2L, 3L),
                fired("\"A1\" in $v", "$x", "upper(\"a\")", "$v", "distinct(concat($x, TSVAL))"));
        // the binding's value is the rule's dataset's, as inside filter= (BS3) — a grouped target
        // reads the group column of the target table
        assertEquals(List.of(1L, 2L, 3L), fired("count($v) == 1", "$x", "upper(\"a\")", "$v",
                "distinct($x, group=[TSPARM])"));
    }


    @Test
    void aPerRowBindingIsTheLevelRefusalWhileTheInlineExpressionReadsTheTargetsRows()
    {
        String bound = loadError("not empty($v)", "$p", "upper(TSPARM)", "$v", "distinct($p)");
        assertTrue(bound.contains("distinct"), bound);
        assertTrue(bound.contains("per-row or per-variable value `$p`"), bound);
        // the inline spelling is a computed target over the target table's own rows (D14)
        assertEquals(List.of(1L, 2L, 3L), fired("count($v) == 2", "$v", "distinct(upper(TSPARM))"));
        for (String fn : List.of("max", "max_date", "min_date", "sequence"))
        {
            String error = loadError("not empty($v)", "$p", "upper(TSPARM)", "$v",
                    fn + "($p, group=[TSPARM])");
            assertTrue(error.contains(fn), error);
            assertTrue(error.contains("per-row or per-variable value `$p`"), error);
        }
        // a Check-less rule's binding is judged by the loader's seam with the same sentence
        String checkless = loadError(null, "$p", "upper(TSPARM)", "$v", "distinct($p)");
        assertTrue(checkless.contains("per-row or per-variable value `$p`"), checkless);
    }


    @Test
    void aBindingHoldingAStringLiteralKeepsTheLiteralRefusal()
    {
        // symmetric with the refused distinct("AETERM"): the target is not a name slot
        String bound = loadError("not empty($v)", "$x", "\"TSPARM\"", "$v", "distinct($x)");
        assertTrue(bound.contains("takes a column reference, not the literal TSPARM"), bound);
        String inline = loadError("not empty($v)", "$v", "distinct(\"TSPARM\")");
        assertTrue(inline.contains("takes a column reference, not the literal TSPARM"), inline);
        String max = loadError("not empty($v)", "$x", "\"TSSEQ\"", "$v", "max($x, group=[TSPARM])");
        assertTrue(max.contains("takes a column reference, not the literal TSSEQ"), max);
    }

    // ---------------------------------------------------------------- plumbing


    private static List<Long> fired(String check, String... bindings)
    {
        // row-guarded: a dataset-level verdict alone is one Dataset-level finding
        Rule rule = load("not empty(TSPARM) and (" + check + ")", bindings);
        assertNull(rule.getLoadError(), () -> check + " -> " + rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ts());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(check, bindings).getLoadError());
    }


    private static Rule load(String check, String... bindings)
    {
        try
        {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("Core", Map.of("Id", "DT-TEST"));
            List<Map<String, String>> declared = new ArrayList<>();
            for (int i = 0; i < bindings.length; i += 2)
            {
                declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
            }
            if (!declared.isEmpty())
            {
                rule.put("Bindings", declared);
            }
            if (check != null)
            {
                rule.put("Check", Map.of("expression", check));
                // an explicit report column: an untyped binding (`1 + 1`) has no absence
                // spelling, and the loader refuses to auto-report it
                // ... and max(<computed>) — no column, no declared type — is excluded by name
                List<String> reported = new ArrayList<>(List.of("TSPARM"));
                for (Map<String, String> b : declared)
                {
                    if (b.get("expression").startsWith("max("))
                    {
                        reported.add("!" + b.get("name"));
                    }
                }
                rule.put("Outcome", Map.of("Message", "m", "Output_Variables", reported));
            }
            else
            {
                // a Check-less rule: judged by the loader's seam pass, never by stage A
                rule.put("Match_Datasets", List.of(Map.of("Name", "TS")));
                rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$v")));
            }
            String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
            Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
            assertNotNull(loaded, "the rule parses");
            return loaded;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

}
