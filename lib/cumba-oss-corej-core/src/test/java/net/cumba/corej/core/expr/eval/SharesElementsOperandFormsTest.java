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
import net.cumba.corej.core.exec.RealTables;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code shares_no_elements_with} / {@code shares_elements_with} with a non-{@code $} operand
 * ({@code PLAN-binding-substitutability} G1, runbook W1b SH — coordinator D21): the operand a
 * binding holds, written in place, answers exactly as the binding does (a dataset-level value
 * evaluated once over the rule's dataset, as the splice evaluates a computed member), and a per-row
 * operand is refused at load — bound and inline alike, with the same level message.
 */
class SharesElementsOperandFormsTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static IDataTable ae()
    {
        return RealTables.of("AE").str("DOMAIN", "AE", "AE", "AE").str("USUBJID", "S1", "S2", "S3")
                .str("FLAG", "Y", "N", "Y").str("AETERM", "a", "b", "c").build();
    }


    @Test
    void theExpressionABindingHoldsAnswersAsTheBindingDoesOnBothOperands()
    {
        // the name operand
        assertEquals(List.of(),
                fired("shares_no_elements_with($a, keys=[\"Y\"])", "$a", "distinct(FLAG)"));
        assertEquals(List.of(), fired("shares_no_elements_with(distinct(FLAG), keys=[\"Y\"])"));
        assertEquals(List.of(1L, 2L, 3L),
                fired("shares_no_elements_with($a, keys=[\"Z\"])", "$a", "distinct(FLAG)"));
        assertEquals(List.of(1L, 2L, 3L),
                fired("shares_no_elements_with(distinct(FLAG), keys=[\"Z\"])"));
        // the value operand
        assertEquals(List.of(1L, 2L, 3L), fired("shares_no_elements_with($a, $t)", "$a",
                "distinct(FLAG)", "$t", "distinct(AETERM)"));
        assertEquals(List.of(1L, 2L, 3L),
                fired("shares_no_elements_with($a, distinct(AETERM))", "$a", "distinct(FLAG)"));
        assertEquals(List.of(), fired("shares_no_elements_with(distinct(FLAG), $y)", "$y",
                "minus([\"Y\", \"Q\"], subtract=[\"Q\"])"));
        assertEquals(List.of(), fired(
                "shares_no_elements_with(distinct(FLAG), minus([\"Y\", \"Q\"], subtract=[\"Q\"]))"));
        // a dataset-level scalar is the singleton set, as the binding's value is
        assertEquals(List.of(),
                fired("shares_no_elements_with(upper(\"y\"), $a)", "$a", "distinct(FLAG)"));
        // the positive twin
        assertEquals(List.of(1L, 2L, 3L),
                fired("shares_elements_with(distinct(FLAG), keys=[\"Y\"])"));
        assertEquals(List.of(), fired("shares_elements_with(distinct(FLAG), keys=[\"Z\"])"));
        assertEquals(List.of(1L, 2L, 3L),
                fired("not shares_elements_with(distinct(FLAG), distinct(AETERM))"));
    }


    @Test
    void aPerRowOperandIsTheSameLevelRefusalBoundAndInline()
    {
        String bound = loadError("shares_no_elements_with($p, keys=[\"Y\"])", "$p", "upper(FLAG)");
        String inline = loadError("shares_no_elements_with(upper(FLAG), keys=[\"Y\"])");
        for (String error : List.of(bound, inline))
        {
            assertTrue(error.contains("shares_no_elements_with"), error);
            assertTrue(error.contains("cannot read the per-row or per-variable operand"), error);
        }
        assertEquals(bound.replace("$p", "upper(FLAG)"), inline, "one sentence for both spellings");
        // the value operand too, and the positive twin
        String value = loadError("shares_elements_with($a, $p)", "$a", "distinct(FLAG)", "$p",
                "upper(FLAG)");
        assertTrue(value.contains("shares_elements_with"), value);
        assertTrue(value.contains("cannot read the per-row or per-variable operand `$p`"), value);
        // a bare column is a per-row value as well
        String column = loadError("shares_no_elements_with(FLAG, keys=[\"Y\"])");
        assertTrue(column.contains("cannot read the per-row or per-variable operand `FLAG`"),
                column);
        // a Check-less rule's binding is judged by the loader's seam with the same sentence
        String checkless = loadError(null, "$r", "shares_no_elements_with($p, keys=[\"Y\"])", "$p",
                "upper(FLAG)");
        assertTrue(checkless.contains("cannot read the per-row or per-variable operand `$p`"),
                checkless);
    }


    /**
     * T2 fold (review L1-M1): the {@code keys=} list of the set predicates holds literal names
     * ({@code ExprCompiler.keyLiterals}: a bare name contributes its text), never an operand, so
     * the SH level judgement reads the positional operands only - {@code keys=[DM]}
     * (rule-step-by-step 7.3.13) loads and answers again; a per-row positional operand stays
     * refused.
     */
    @Test
    void theKeysListIsLiteralNamesNotAnOperand()
    {
        Rule doc = load("not shares_no_elements_with($datasets, keys=[DM]) and empty(DM.USUBJID)",
                "$datasets", "dataset_names()");
        assertNull(doc.getLoadError(), () -> String.valueOf(doc.getLoadError()));
        String check = "not shares_no_elements_with($datasets, keys=[DM]) and empty(USUBJID)";
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1", "", "S3").build();
        assertEquals(List.of(2L),
                firedWith(check, ae, inventory(ae, "AE", "DM"), "$datasets", "dataset_names()"),
                "DM is in the study: the blank USUBJID row fires");
        assertEquals(List.of(),
                firedWith(check, ae, inventory(ae, "AE"), "$datasets", "dataset_names()"),
                "DM is not in the study: nothing fires");
        assertNull(load("shares_elements_with($d, keys=[DM, AE])", "$d", "dataset_names()")
                .getLoadError(), "the positive twin");
        String perRow = loadError("shares_no_elements_with(upper(FLAG), keys=[DM])");
        assertTrue(perRow.contains("cannot read the per-row or per-variable operand `upper(FLAG)`"),
                perRow);
    }


    private static net.cumba.corej.core.exec.DatasetResolver.WithInventory inventory(
            IDataTable primary, String... names)
    {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(List.of(names));
        return new net.cumba.corej.core.exec.DatasetResolver.WithInventory()
        {

            @Override
            public @org.jspecify.annotations.Nullable IDataTable resolve(String name)
            {
                return primary.getMetaData().getName().equals(name) ? primary : null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return all;
            }
        };
    }


    private static List<Long> firedWith(String check, IDataTable table,
            net.cumba.corej.core.exec.DatasetResolver resolver, String... bindings)
    {
        Rule rule = load(check, bindings);
        assertNull(rule.getLoadError(), () -> check + " -> " + rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, resolver);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }

    // ---------------------------------------------------------------- plumbing


    private static List<Long> fired(String check, String... bindings)
    {
        // row-guarded: a broadcast verdict alone is one Dataset-level finding
        Rule rule = load("not empty(USUBJID) and (" + check + ")", bindings);
        assertNull(rule.getLoadError(), () -> check + " -> " + rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae());
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
            rule.put("Core", Map.of("Id", "SH-TEST"));
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
                rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("USUBJID")));
            }
            else
            {
                // a Check-less rule: judged by the loader's seam pass, never by stage A
                rule.put("Match_Datasets", List.of(Map.of("Name", "AE")));
                rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$r")));
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
