package net.cumba.corej.core.expr.eval;

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
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-union-function} §3.3 (runbook W1a U3, ruled by D3 / D16): an operand of {@code minus}
 * / {@code union} is judged by its <b>level</b>, never by its shape. Any expression whose value is
 * one dataset-level list — a {@code $}-binding, a list literal, {@code distinct(X)}, a nested set
 * function, {@code upper($names)} over a dataset-level list — is an operand, bound or inline. A
 * per-row or per-variable operand ({@code QNAM}, {@code split_by(QNAM, ",")},
 * {@code distinct(X, group=[…])}) is {@code OPERATION_READS_CURSOR_BINDING} with ONE message for
 * both spellings; and where stage A does not walk the rule (a Check-less rule's bindings, a
 * {@code Match_Datasets} Filter) the same judgement is the loader's, so a per-row {@code $} there
 * no longer reads row 0 silently. (Until U3 the compiler refused every inline operand by shape —
 * <i>"takes a dataset-level list — a $-binding of one or a list literal"</i> — and left the
 * Check-less {@code $} spelling unguarded.) Real loader, no Mockito.
 */
class MinusOperandLoadSeamTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DATASET_LIST = "distinct(TSPARMCD)";

    /** The level finding's text, shared by both spellings and both set functions. */
    static final String LEVEL_REFUSAL = "cannot read the per-row or per-variable operand";

    private static final List<String> PER_ROW_INLINE = List.of("minus(QNAM, subtract=$s)",
            "minus(get_parent_model_column_order(RDOMAIN), subtract=$s)",
            "minus(upper($r), subtract=$s)", "minus($s, subtract=QNAM)",
            "minus(split_by(QNAM, \",\"), subtract=$s)",
            "minus($s, subtract=distinct(QNAM, group=[TSSEQ]))", "union(QNAM, $s)",
            "union($s, upper($r))", "union($s, $s, split_by(QNAM, \",\"))",
            "union($s, distinct(QNAM, group=[TSSEQ]))");

    @Test
    void aPerRowInlineOperandIsTheLevelFindingInABinding() throws Exception
    {
        for (String call : PER_ROW_INLINE)
        {
            Rule rule = load("not empty($v)", "$s", DATASET_LIST, "$r", "upper(QNAM)", "$v", call);
            assertLevelRefusal(call, rule);
        }
    }


    @Test
    void aPerRowInlineOperandIsTheLevelFindingInTheCheck() throws Exception
    {
        for (String call : PER_ROW_INLINE)
        {
            Rule rule = load("not empty(" + call + ")", "$s", DATASET_LIST, "$r", "upper(QNAM)");
            assertLevelRefusal(call, rule);
        }
    }


    @Test
    void theBoundAndTheInlineSpellingGetTheSameFinding() throws Exception
    {
        for (String[] pair : List.of(new String[]
        {
                "split_by(QNAM, \",\")", "minus(%s, subtract=$s)"
        }, new String[]
        {
                // (a per-row LIST: a scalar-typed operand is also minus' PARAMETER_TYPE, W4 M1)
                "split_by(QNAM, \";\")", "minus($s, subtract=%s)"
        }, new String[]
        {
                "distinct(QNAM, group=[TSSEQ])", "union($s, %s)"
        }))
        {
            Rule bound = load("not empty(" + pair[1].formatted("$p") + ")", "$s", DATASET_LIST,
                    "$p", pair[0]);
            Rule inline = load("not empty(" + pair[1].formatted(pair[0]) + ")", "$s", DATASET_LIST);
            assertLevelRefusal(pair[1], bound);
            assertLevelRefusal(pair[1], inline);
            String boundText = String.valueOf(bound.getLoadError()).replace("`$p`",
                    "`" + pair[0] + "`");
            assertEquals(boundText, inline.getLoadError(),
                    "one message for both spellings: " + pair[1]);
        }
    }


    @Test
    void aDatasetLevelInlineOperandLoads() throws Exception
    {
        for (String call : List.of("minus(distinct(TSPARMCD), subtract=$s)",
                "minus($l, subtract=minus($s, subtract=[\"A\"]))", "minus(upper($l), subtract=$s)",
                "minus([\"AGEU\"], subtract=union($s, $l))", "union(distinct(TSPARMCD), $s)",
                "union($s, [\"A\"], upper($l))", "union($l, $s, minus($l, subtract=$s))",
                "union($l, dataset_names())"))
        {
            Rule rule = load("not empty($v)", "$l", "dataset_names()", "$s", DATASET_LIST, "$v",
                    call);
            assertNull(rule.getLoadError(), call + " → " + rule.getLoadError());
            Rule inline = load("not empty(" + call + ")", "$l", "dataset_names()", "$s",
                    DATASET_LIST);
            assertNull(inline.getLoadError(), call + " (Check) → " + inline.getLoadError());
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


    /**
     * A Check-less rule's bindings are never walked by stage A: the loader's seam pass judges the
     * level there — the inline spelling AND the {@code $} spelling, which read row 0 silently until
     * U3 (the plan's C3 asymmetry).
     */
    @Test
    void aCheckLessRuleGetsTheLoaderSideJudgement() throws Exception
    {
        Rule inline = loadCheckless("$s", DATASET_LIST, "$v", "minus(QNAM, subtract=$s)");
        assertLevelRefusal("Check-less inline", inline);
        Rule bound = loadCheckless("$s", DATASET_LIST, "$p", "upper(QNAM)", "$v", "union($s, $p)");
        assertLevelRefusal("Check-less $p", bound);
        assertTrue(String.valueOf(bound.getLoadError()).contains("`$p`"), bound.getLoadError());
        Rule clean = loadCheckless("$l", "dataset_names()", "$s", DATASET_LIST, "$v",
                "union(distinct(TSPARMCD), $l, minus($l, subtract=$s))");
        assertNull(clean.getLoadError(), "Check-less dataset-level → " + clean.getLoadError());
    }


    /** A {@code Match_Datasets} Filter is compiled at run time; its operands are judged at load. */
    @Test
    void aMatchDatasetsFilterGetsTheLoaderSideJudgement() throws Exception
    {
        Rule rule = loadWithFilter("not empty(minus(ARM, subtract=[\"A\"]))");
        assertLevelRefusal("Filter", rule);
        assertTrue(String.valueOf(rule.getLoadError()).contains("Filter"), rule.getLoadError());
        Rule clean = loadWithFilter("not empty(minus([\"B\"], subtract=[\"A\"]))");
        assertNull(clean.getLoadError(), "Filter dataset-level → " + clean.getLoadError());
    }


    private static void assertLevelRefusal(String call, Rule rule)
    {
        String error = rule.getLoadError();
        assertNotNull(error, call + " must be a load error: a per-row operand has no row to pick");
        assertTrue(error.contains(LEVEL_REFUSAL), call + " → " + error);
        assertTrue(error.contains("reads only dataset-level lists"), call + " → " + error);
    }


    private static Rule load(String check, String... bindings) throws Exception
    {
        return load(rule(check, bindings));
    }


    private static Rule loadCheckless(String... bindings) throws Exception
    {
        Map<String, Object> rule = rule(null, bindings);
        return load(rule);
    }


    private static Rule loadWithFilter(String filter) throws Exception
    {
        Map<String, Object> rule = rule("not empty(ARM)");
        rule.put("Requirements", Map.of("Variables", Map.of("All", List.of("USUBJID"),
                "All_Or_None", List.of(List.of("USUBJID", "DM.USUBJID")))));
        rule.put("Match_Datasets", List.of(Map.of("Name", "DM", "Keys", List.of("USUBJID"),
                "Join_Type", "left", "Filter", filter)));
        return load(rule);
    }


    private static Map<String, Object> rule(String check, String... bindings)
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CFX-MINUS"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        rule.put("Bindings", declared);
        if (check != null)
        {
            rule.put("Check", Map.of("expression", check));
        }
        rule.put("Outcome", Map.of("Message", "m"));
        return rule;
    }


    private static Rule load(Map<String, Object> rule) throws Exception
    {
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded, "the rule parses");
        return loaded;
    }
}
