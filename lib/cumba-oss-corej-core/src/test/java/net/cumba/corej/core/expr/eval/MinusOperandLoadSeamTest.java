package net.cumba.corej.core.expr.eval;

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
import org.junit.jupiter.api.Test;

/**
 * Combined review of runbook W2–W8, W4 M3: {@code minus} reads each operand ONCE per call — a
 * constant list's elements, any other vector's row 0 — so an operand that is neither a
 * {@code $}-binding of a dataset-level list nor a list literal silently stood for every row:
 * {@code minus(QNAM, subtract=$s)}, {@code minus(get_parent_model_column_order(RDOMAIN), …)} and
 * {@code minus(upper($perRow), …)} all loaded and answered row 0's difference on every row. Each is
 * a load error now, in a binding and inline alike; a {@code $}-binding needing a cursor stays Stage
 * A's {@code OPERATION_READS_CURSOR_BINDING}. Real loader, no Mockito.
 */
class MinusOperandLoadSeamTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DATASET_LIST = "distinct(TSPARMCD)";

    @Test
    void anInlineOperandIsALoadErrorInABinding() throws Exception
    {
        for (String minus : List.of("minus(QNAM, subtract=$s)",
                "minus(get_parent_model_column_order(RDOMAIN), subtract=$s)",
                "minus(upper($r), subtract=$s)", "minus($s, subtract=QNAM)"))
        {
            Rule rule = load("not empty($v)", "$s", DATASET_LIST, "$r", "upper(QNAM)", "$v", minus);
            assertLoadError(minus, rule);
        }
    }


    @Test
    void anInlineOperandIsALoadErrorInTheCheck() throws Exception
    {
        for (String minus : List.of("minus(QNAM, subtract=$s)",
                "minus(get_parent_model_column_order(RDOMAIN), subtract=$s)",
                "minus(upper($r), subtract=$s)"))
        {
            Rule rule = load("not empty(" + minus + ")", "$s", DATASET_LIST, "$r", "upper(QNAM)");
            assertLoadError(minus, rule);
        }
    }


    @Test
    void aDatasetLevelBindingOrAListLiteralStillLoads() throws Exception
    {
        for (String minus : List.of("minus($l, subtract=$s)", "minus([\"AGEU\"], subtract=$s)",
                "minus(subtract=$s, value=$l)"))
        {
            Rule rule = load("not empty($v)", "$l", "dataset_names()", "$s", DATASET_LIST, "$v",
                    minus);
            assertNull(rule.getLoadError(), minus + " → " + rule.getLoadError());
        }
    }


    private static void assertLoadError(String minus, Rule rule)
    {
        String error = rule.getLoadError();
        assertNotNull(error, minus + " must be a load error: its operand would be read at row 0");
        assertTrue(error.contains("takes a dataset-level list"), minus + " → " + error);
    }


    private static Rule load(String check, String... bindings) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CFX-MINUS"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        rule.put("Bindings", declared);
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m"));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded, "the rule parses");
        return loaded;
    }
}
