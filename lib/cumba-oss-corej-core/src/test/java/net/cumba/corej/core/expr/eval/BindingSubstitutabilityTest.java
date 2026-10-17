package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-binding-substitutability} §5 (runbook W1a BS1, C9–C11, ruled Q4): every static
 * parameter the compiler held to a literal because it is <b>read once per call</b> — and whose
 * value is read at evaluation, not at plan-build time — takes any <b>dataset-level</b> expression
 * of its declared type, bound ({@code $x}) or written inline, and the two spellings load alike and
 * evaluate alike (D3 / D16). A per-row or per-variable value is the LEVEL finding, one message for
 * both spellings; a wrong-typed literal stays a type finding.
 *
 * <p>
 * The widened parameters: {@code extract_metadata} / {@code variable_count} {@code name},
 * {@code get_dataset_filtered_variables} / {@code get_model_filtered_variables} {@code key_value}
 * (C9, the open-vocabulary strings); the five dictionary functions' {@code case_sensitive} (C10);
 * {@code codelist_terms} {@code codelists}, {@code valid_codelist_dates} {@code ct_package_types}
 * (C11). NOT widened: {@code keep_missings} (consumed at plan-build time — owner question pending,
 * coordinator decision 8), the closed vocabularies and the regex parameters (G2, C7 / C8), and the
 * name slots {@code column_series_metadata.name} / {@code cross_dataset_variable_metadata.domain}
 * (class X, coordinator decision 1).
 * </p>
 */
class BindingSubstitutabilityTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * (parameter, the call with {@code ‹arg›} at the widened parameter, a dataset-level expression
     * of its type) — the roster BS1 widens.
     */
    private static final List<String[]> WIDENED = List.of(new String[]
    {
            "name", "extract_metadata(name=‹arg›)", "lower(\"DATASET_NAME\")"
    }, new String[]
    {
            "name", "variable_count(name=‹arg›)", "upper(\"--lnkgrp\")"
    }, new String[]
    {
            "key_value", "get_dataset_filtered_variables(key_name=\"role\", key_value=‹arg›)",
            "upper(\"identifier\")"
    }, new String[]
    {
            "key_value", "get_model_filtered_variables(key_name=\"core\", key_value=‹arg›)",
            "upper(\"req\")"
    }, new String[]
    {
            "codelists", "codelist_terms(codelists=‹arg›, level=\"term\")",
            "union([\"DOMAIN\"], [\"ND\"])"
    }, new String[]
    {
            "ct_package_types", "valid_codelist_dates(ct_package_types=‹arg›)",
            "minus([\"SDTM\", \"ADAM\"], subtract=[\"ADAM\"])"
    }, new String[]
    {
            "case_sensitive",
            "dictionary_has_decode(CMTRT, external_dictionary_type=\"whodrug\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive",
            "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\"meddra\","
                    + " dictionary_term_type=\"PT\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive",
            "valid_external_dictionary_code(AEDECOD, external_dictionary_type=\"meddra\","
                    + " dictionary_term_type=\"PT\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive", "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                    + " external_dictionary_type=\"unii\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive", "valid_external_dictionary_hierarchy(AEHLT, AESOC,"
                    + " external_dictionary_type=\"meddra\", case_sensitive=‹arg›)",
            "(1 == 2)"
    });

    @Test
    void aDatasetLevelExpressionLoadsBoundAndInlineAlike() throws Exception
    {
        for (String[] row : WIDENED)
        {
            String call = row[1];
            String expr = row[2];
            Rule bound = loadCall(call.replace("‹arg›", "$x"), "$x", expr);
            assertNull(bound.getLoadError(), call + " bound → " + bound.getLoadError());
            Rule inline = loadCall(call.replace("‹arg›", expr));
            assertNull(inline.getLoadError(), call + " inline → " + inline.getLoadError());
        }
    }


    @Test
    void aPerRowValueIsTheSameLevelFindingBoundAndInline() throws Exception
    {
        for (String[] row : WIDENED)
        {
            String parameter = row[0];
            String call = row[1];
            String bound = String
                    .valueOf(loadCall(call.replace("‹arg›", "$x"), "$x", "AETERM").getLoadError());
            String inline = String
                    .valueOf(loadCall(call.replace("‹arg›", "AETERM")).getLoadError());
            assertTrue(bound.contains("OPERATION_READS_CURSOR_BINDING"),
                    call + " bound → " + bound);
            assertTrue(bound.contains("argument '" + parameter + "'"), call + " bound → " + bound);
            assertTrue(bound.contains("read once per call"), call + " bound → " + bound);
            assertEquals(bound.replace("`$x`", "`AETERM`"), inline,
                    call + ": one level finding for both spellings");
        }
    }


    @Test
    void aPerRowValueInACheckLessRuleIsTheLoadersFinding() throws Exception
    {
        // The paths stage A never walks (G3): a Check-less rule's bindings meet the same judgement
        // in the loader's seam pass, so a per-row value never silently reads row 0 there.
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "BS1-CHECKLESS"));
        rule.put("Bindings", List.of(Map.of("name", "$k", "expression", "AETERM"),
                Map.of("name", "$v", "expression", "extract_metadata(name=$k)")));
        rule.put("Match_Datasets", List.of(Map.of("Name", "AE")));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$v")));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded);
        String error = String.valueOf(loaded.getLoadError());
        assertTrue(error.contains("argument 'name' of 'extract_metadata'"), error);
        assertTrue(error.contains("read once per call"), error);
        assertTrue(error.contains("the binding $v"), error);
    }


    @Test
    void aWrongTypedLiteralStaysATypeFinding() throws Exception
    {
        // The type half of the retired seams stays where stage A does not walk (a binding compiles
        // before stage A runs): a string where a boolean is read, a string where a list is read.
        String flag = String.valueOf(load(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\"unii\", case_sensitive=$x)",
                "$x", "\"false\"").getLoadError());
        // (a typed $x: "false" in a Check is stage A's PARAMETER_TYPE "takes boolean, not string";
        // the compile-site wording "takes a boolean (true or false)" is DictionaryFunctionsTest's)
        assertTrue(flag.contains("argument 'case_sensitive'") && flag.contains("boolean"), flag);
        String list = String.valueOf(
                load("not empty($v)", "$v", "codelist_terms(codelists=\"DOMAIN\", level=\"term\")")
                        .getLoadError());
        assertTrue(list.contains("argument 'codelists'") && list.contains("takes a list"), list);
    }


    @Test
    void theNameSlotsAndTheClosedVocabulariesStayLiteral() throws Exception
    {
        // Coordinator decision 1 (class X): column_series_metadata.name and
        // cross_dataset_variable_metadata.domain are name slots; the closed vocabularies and the
        // regex parameters are literal types (G2). The widening does not reach them.
        for (String call : List.of(
                "column_series_metadata(name=lower(\"COVAL\"), name_pattern=\"^COVAL\\\\d+$\")",
                "cross_dataset_variable_metadata(\"label\", domain=lower(\"ADSL\"))",
                "codelist_terms(codelists=[\"DOMAIN\"], level=lower(\"TERM\"))",
                "get_dataset_filtered_variables(key_name=lower(\"ROLE\"), key_value=\"x\")",
                "variable_count(name_pattern=lower(\"^.+FL$\"))"))
        {
            String error = String.valueOf(load("not empty($v)", "$v", call).getLoadError());
            assertTrue(error.contains("static string literal"), call + " → " + error);
        }
    }


    @Test
    void aComputedMetadataKeyEvaluatesAsTheLiteralDoes() throws Exception
    {
        IDataTable dm = MockTable.of().col("USUBJID", "S1", "S2").name("DM").build();
        Rule literal = load("$n == \"DM\"", "$n", "extract_metadata(name=\"dataset_name\")");
        Rule bound = load("$n == \"DM\"", "$k", "lower(\"DATASET_NAME\")", "$n",
                "extract_metadata(name=$k)");
        Rule inline = load("$n == \"DM\"", "$n", "extract_metadata(name=lower(\"DATASET_NAME\"))");
        int expected = -1;
        for (Rule rule : List.of(literal, bound, inline))
        {
            assertNull(rule.getLoadError(), rule.getLoadError());
            RuleExecutionResult result = RuleRunnerCalls.execute(rule, dm);
            assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                    result.getStatusMessage());
            assertFalse(result.getViolations().isEmpty(), "the key resolves to the dataset name");
            expected = expected < 0 ? result.getViolations().size() : expected;
            assertEquals(expected, result.getViolations().size(),
                    "the three spellings answer alike");
        }
    }

    // ------------------------------------------------------------------ BS2: the splice slots

    /**
     * (the Check with {@code ‹arg›} at the splice slot) — the two splicing readers, the list member
     * and the bare operand (C5: {@code f($keys)} reads as {@code f([$keys])}).
     */
    private static final List<String> SPLICE_SLOTS = List.of("is_unique_set([USUBJID, ‹arg›])",
            "not is_not_unique_set([USUBJID, ‹arg›])", "record_count(group=[USUBJID, ‹arg›]) > 1",
            "is_unique_set(‹arg›)", "record_count(group=‹arg›) > 1",
            // the third splicing reader (runbook W1a E1): values' columns, list and bare operand
            "\"S1\" in values([USUBJID, ‹arg›])", "\"S1\" in values(‹arg›)",
            // W1a BS2b (C6, ruled Q2: every list<column-reference> slot splices): the group= of
            // the grouped readers — max / max_date / min_date, read_value, distinct — list member
            // and bare operand alike
            "empty(max(AESEQ, group=[USUBJID, ‹arg›]))", "empty(max(AESEQ, group=‹arg›))",
            "empty(max_date(AESTDTC, group=[USUBJID, ‹arg›]))",
            "empty(max_date(AESTDTC, group=‹arg›))",
            "empty(min_date(AESTDTC, group=[USUBJID, ‹arg›]))",
            "empty(min_date(AESTDTC, group=‹arg›))",
            "AETERM in distinct(AETERM, group=[USUBJID, ‹arg›])",
            "AETERM in distinct(AETERM, group=‹arg›)",
            "empty(read_value(TSVAL, domain=TS, mode=\"FIRST\", group=[TSPARM, ‹arg›]))",
            "empty(read_value(TSVAL, domain=TS, mode=\"FIRST\", group=‹arg›))",
            // BS2b (keys=): the compiler-dispatched readers' name lists — is_last_in_group's
            // group=, has_mixed_emptiness_within_group's group= / qualifying_any_populated=, the
            // keys= of is_(not_)unique_relationship and is_inconsistent_across_dataset
            "is_last_in_group(by=AESEQ, group=[USUBJID, ‹arg›])",
            "is_last_in_group(by=AESEQ, group=‹arg›)",
            "has_mixed_emptiness_within_group(AETERM, group=[USUBJID, ‹arg›])",
            "has_mixed_emptiness_within_group(AETERM, group=[USUBJID], qualifying_any_populated=‹arg›)",
            "is_unique_relationship(AETERM, keys=[USUBJID, ‹arg›])",
            "not is_not_unique_relationship(AETERM, keys=‹arg›)",
            "is_inconsistent_across_dataset(AETERM, keys=[USUBJID, ‹arg›])",
            "is_inconsistent_across_dataset(AETERM, keys=‹arg›)");

    @Test
    void aDatasetLevelListOfNamesSplicesBoundAndInlineAlike() throws Exception
    {
        // C4 (Q1, ruled): a $ member splices; the expression it holds, written as the member, is
        // spliced exactly as the $ member is — a list of names and a single name alike.
        for (String slot : SPLICE_SLOTS)
        {
            for (String expr : List.of("natural_key_variables()", "upper(\"aeterm\")"))
            {
                Rule bound = load(slot.replace("‹arg›", "$x"), "$x", expr);
                assertNull(bound.getLoadError(), slot + " bound → " + bound.getLoadError());
                Rule inline = load(slot.replace("‹arg›", expr));
                assertNull(inline.getLoadError(), slot + " inline → " + inline.getLoadError());
            }
        }
    }


    @Test
    void aPerRowSpliceMemberIsTheSameFindingBoundAndInline() throws Exception
    {
        for (String slot : SPLICE_SLOTS)
        {
            String bound = String.valueOf(
                    load(slot.replace("‹arg›", "$x"), "$x", "upper(AETERM)").getLoadError());
            String inline = String
                    .valueOf(load(slot.replace("‹arg›", "upper(AETERM)")).getLoadError());
            if (slot.contains("unique_set(") || slot.startsWith("is_inconsistent_across_dataset("))
            {
                // W1b UC1 (PLAN-engine-gap-unique-computed Q2): at is_(not_)unique_set's members
                // — and since PLAN-engine-per-row-key-expression at is_inconsistent_across_
                // dataset's keys= — the per-row member is a VALUE key component: both spellings
                // load
                assertEquals("null", bound, slot + " bound → " + bound);
                assertEquals("null", inline, slot + " inline → " + inline);
                continue;
            }
            assertTrue(bound.contains("PARAMETER_TYPE"), slot + " bound → " + bound);
            assertTrue(bound.contains("cannot splice the per-row"), slot + " bound → " + bound);
            assertEquals(bound.replace("$x", "upper(AETERM)"), inline,
                    slot + ": one level finding for both spellings");
        }
    }


    @Test
    void aPerRowSpliceMemberInACheckLessRuleIsTheLoadersFinding() throws Exception
    {
        for (String call : List.of("record_count(group=[USUBJID, ‹m›])",
                // W1a BS2b: the loader's seam reads the widened roster too
                "max(AESEQ, group=[USUBJID, ‹m›])", "distinct(AETERM, group=‹m›)",
                // R5 review BS2b MED-1: a slot bound by POSITION is read by parameter index
                "is_last_in_group(AESEQ, [USUBJID, ‹m›])"))
            for (String member : List.of("$k", "upper(AETERM)"))
            {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("Core", Map.of("Id", "BS2-CHECKLESS"));
                rule.put("Bindings", List.of(Map.of("name", "$k", "expression", "upper(AETERM)"),
                        Map.of("name", "$v", "expression", call.replace("‹m›", member))));
                rule.put("Match_Datasets", List.of(Map.of("Name", "AE")));
                rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$v")));
                String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
                Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
                assertNotNull(loaded);
                String error = String.valueOf(loaded.getLoadError());
                assertTrue(error.contains("cannot splice the per-row"), member + " → " + error);
                assertTrue(error.contains("the binding $v"), member + " → " + error);
            }
    }


    @Test
    void aSplicedExpressionEvaluatesAsTheBindingDoes() throws Exception
    {
        // Two tables: on UNIQUE every (USUBJID, AETERM) tuple occurs once while USUBJID alone and
        // AETERM alone repeat — so a splice that read the wrong names would fire there; on DUP rows
        // 0 / 1 share the tuple. Every spelling must answer 0 on the first and alike on the second.
        IDataTable unique = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("AETERM", "a", "b", "a").build();
        IDataTable dup = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("AETERM", "a", "a", "b").build();
        String names = "minus([\"USUBJID\", \"AETERM\", \"X\"], subtract=[\"X\"])";
        String term = "minus([\"AETERM\", \"X\"], subtract=[\"X\"])";
        List<Rule> uniqueness = List.of(load("not is_unique_set([USUBJID, AETERM])"),
                load("not is_unique_set([USUBJID, $k])", "$k", "upper(\"aeterm\")"),
                load("not is_unique_set([USUBJID, upper(\"aeterm\")])"),
                load("not is_unique_set([USUBJID, $t])", "$t", term),
                load("not is_unique_set([USUBJID, " + term + "])"));
        // C5: the bare operand reads as the one-member list — compared with THAT spelling and with
        // the binding holding the same expression. A rule whose only column names arrive by a
        // splice (bound or inline) is a Record-level rule like its written twin, one finding per
        // row: W1b P5b (is_unique_set's members — RuleClassifier reads a computed splice member as
        // the unresolved usage a $ member is) and W2 SC (record_count's group= — the grouped-call
        // usage keeps a spliced key); this used to read "a Dataset-level rule (ONE collapsed
        // finding)", which the family-internal comparison below could not see.
        List<Rule> bare = List.of(load("not is_unique_set([$k])", "$k", names),
                load("not is_unique_set($k)", "$k", names),
                load("not is_unique_set(" + names + ")"),
                load("not is_unique_set([" + names + "])"));
        List<Rule> counts = List.of(load("$c > 1", "$c", "record_count(group=[USUBJID, AETERM])"),
                load("$c > 1", "$k", "upper(\"aeterm\")", "$c",
                        "record_count(group=[USUBJID, $k])"),
                load("$c > 1", "$c", "record_count(group=[USUBJID, upper(\"aeterm\")])"),
                load("$c > 1", "$t", term, "$c", "record_count(group=[USUBJID, $t])"),
                load("$c > 1", "$c", "record_count(group=[USUBJID, " + term + "])"));
        List<Rule> bareCounts = List.of(load("$c > 1", "$k", names, "$c", "record_count(group=$k)"),
                load("$c > 1", "$k", names, "$c", "record_count(group=[$k])"),
                load("$c > 1", "$c", "record_count(group=" + names + ")"),
                load("$c > 1", "$c", "record_count(group=[" + names + "])"));
        Map<String, Integer> fired = new LinkedHashMap<>();
        for (List<Rule> family : List.of(uniqueness, bare, counts, bareCounts))
        {
            for (Rule rule : family)
            {
                assertNull(rule.getLoadError(), rule.getLoadError());
                assertEquals(0, violations(rule, unique), "every tuple is unique");
                fired.put(spelling(rule), violations(rule, dup));
            }
        }
        // every family against the written names, so a splice-only family that collapsed to one
        // finding is visible (W2 SC): rows 0 and 1 share the tuple — two findings
        for (Map.Entry<String, Integer> e : fired.entrySet())
        {
            assertEquals(2, e.getValue(), "the spellings answer per row alike: " + fired);
        }
    }


    /**
     * W1a BS2b (C6): the spliced {@code group=} of {@code max}, {@code distinct} and
     * {@code read_value} reads the SAME columns the written names do — bound, inline, and the bare
     * operand. On UNIQUE every (USUBJID, AETERM) tuple occurs once, so a per-group read keyed by
     * both answers each row its own cell and nothing fires, while a group keyed by USUBJID alone
     * (the planted wrong names) fires; on DUP rows 0 / 1 share the tuple and fire alike. Two
     * families per reader — a bare {@code USUBJID} beside the splice, and the splice-only spellings
     * — compared with each other: a Check whose only column names arrive through a splice finds per
     * row like its written twin (W1b P5b for the compiler-dispatched readers, W2 SC for the grouped
     * calls' {@code group=}; this used to read "a Dataset-level rule (ONE collapsed finding)",
     * which a family-internal comparison could not see). Every slot is pinned with the derived
     * Sensitivity in {@link SpliceSlotRowSensitivityTest}.
     */
    @Test
    void aSplicedGroupReadsTheWrittenNamesColumns() throws Exception
    {
        IDataTable unique = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("AETERM", "a", "b", "a").col("AESEQ", "1", "2", "3").build();
        IDataTable dup = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("AETERM", "a", "a", "b").col("AESEQ", "1", "2", "3").build();
        String names = "minus([\"USUBJID\", \"AETERM\", \"X\"], subtract=[\"X\"])";
        String term = "minus([\"AETERM\", \"X\"], subtract=[\"X\"])";
        for (String check : List.of("max(AESEQ, group=‹g›) != AESEQ",
                "count(distinct(AESEQ, group=‹g›)) > 1",
                "read_value(AESEQ, domain=AE, mode=\"MAX\", group=‹g›) != AESEQ",
                // BS2b (keys=): AESEQ is 1:1 with the (USUBJID, AETERM) tuple on UNIQUE and every
                // row is the last of its own group; on DUP the shared tuple breaks both
                "not is_unique_relationship(AESEQ, keys=‹g›)",
                "not is_last_in_group(by=AESEQ, group=‹g›)"))
        {
            List<Rule> mixed = List.of(load(check.replace("‹g›", "[USUBJID, AETERM]")),
                    load(check.replace("‹g›", "[USUBJID, $k]"), "$k", "upper(\"aeterm\")"),
                    load(check.replace("‹g›", "[USUBJID, upper(\"aeterm\")]")),
                    load(check.replace("‹g›", "[USUBJID, $t]"), "$t", term),
                    load(check.replace("‹g›", "[USUBJID, " + term + "]")));
            List<Rule> spliceOnly = List.of(load(check.replace("‹g›", "$n"), "$n", names),
                    load(check.replace("‹g›", "[$n]"), "$n", names),
                    load(check.replace("‹g›", names)),
                    load(check.replace("‹g›", "[" + names + "]")));
            // the planted control: the wrong names are visible on UNIQUE
            Rule wrong = load(check.replace("‹g›", "[USUBJID]"));
            assertNull(wrong.getLoadError(), wrong.getLoadError());
            assertTrue(violations(wrong, unique, study(unique)) > 0, check + ": sensitive");
            Map<String, Integer> fired = new LinkedHashMap<>();
            for (List<Rule> family : List.of(mixed, spliceOnly))
            {
                for (Rule rule : family)
                {
                    assertNull(rule.getLoadError(), check + " → " + rule.getLoadError());
                    assertEquals(0, violations(rule, unique, study(unique)),
                            check + ": every tuple is unique for " + spelling(rule));
                    fired.put(spelling(rule), violations(rule, dup, study(dup)));
                }
            }
            int first = fired.values().iterator().next();
            assertTrue(first > 0, "rows 0 and 1 share the tuple: " + fired);
            for (Map.Entry<String, Integer> e : fired.entrySet())
            {
                assertEquals(first, e.getValue(), check + ": the spellings answer alike: " + fired);
            }
        }
    }


    private static String spelling(Rule rule)
    {
        StringBuilder sb = new StringBuilder();
        rule.bindingOrder()
                .forEach(b -> sb.append(b.name()).append(": ").append(b.expression()).append("; "));
        return sb.append(rule.getCheck()).toString();
    }


    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().size();
    }


    @Test
    void aSplicedExpressionHoldingNoNamesIsTheRulesError() throws Exception
    {
        // The run-time refusal of the one splice (GroupSplice), bound and inline alike: a value
        // that is neither a list of names nor a name ERRORs the rule — never a silent drop.
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S1").col("AETERM", "a", "a")
                .build();
        Rule bound = load("not is_unique_set([USUBJID, $k])", "$k", "coalesce(5, 6)");
        Rule inline = load("not is_unique_set([USUBJID, coalesce(5, 6)])");
        for (Rule rule : List.of(bound, inline))
        {
            assertNull(rule.getLoadError(), rule.getLoadError());
            RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae);
            assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), result.getStatusMessage());
            assertTrue(String.valueOf(result.getStatusMessage()).contains("must hold a list"),
                    result.getStatusMessage());
        }
    }

    // ---- C16 (W1a BS3, ruled Q6): a dataset-level $-binding inside filter=(…) ----------------


    /** The primary: two subjects, {@code FLAG} is {@code Y} on every row. */
    private static IDataTable dm()
    {
        return MockTable.of().name("DM").col("USUBJID", "S1", "S2").col("FLAG", "Y", "Y").build();
    }


    /**
     * The target under {@code domain=TS}: {@code FLAG} is {@code N} on every row and {@code PARENT}
     * names {@code DM} once and {@code TS} twice — so a binding over the PRIMARY ({@code $c:
     * distinct(FLAG)} = {@code ["Y"]}, {@code $ds: extract_metadata(name="dataset_name")} =
     * {@code "DM"}) answers differently from the same expression evaluated against TS
     * ({@code ["N"]}, {@code "TS"}), and a filter that read the wrong table is visible.
     */
    private static IDataTable ts()
    {
        return MockTable.of().name("TS").col("TSSEQ", "1", "2", "3").col("TSVAL", "Y", "N", "N")
                .col("TSPARM", "A", "B", "C").col("FLAG", "N", "N", "N")
                .col("PARENT", "DM", "TS", "TS").build();
    }


    private static DatasetResolver study(IDataTable... tables)
    {
        return name ->
        {
            for (IDataTable t : tables)
            {
                if (t.getMetaData().getName().equalsIgnoreCase(name))
                {
                    return t;
                }
            }
            return null;
        };
    }


    private static int violations(Rule rule, IDataTable primary, DatasetResolver resolver)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, primary, resolver);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().size();
    }


    @Test
    void aDatasetLevelBindingInsideFilterLoadsOnEveryFilteringFunction() throws Exception
    {
        // C16: the `$` the shape check refused is admitted — bound to a dataset-level value — on
        // every function with a filter= (the five grouped readers and read_value).
        for (String call : List.of("record_count(domain=TS, filter=(TSVAL in $c))",
                "record_count(filter=(FLAG in $c))",
                "distinct(TSVAL, domain=TS, filter=(TSVAL in $c))",
                "max(TSSEQ, domain=TS, group=[TSVAL], filter=(TSVAL in $c))",
                "max_date(TSSEQ, domain=TS, group=[TSVAL], filter=(PARENT == $ds))",
                "min_date(TSSEQ, domain=TS, group=[TSVAL], filter=(PARENT == $ds and TSVAL in $c))",
                "read_value(TSPARM, domain=TS, filter=(PARENT == $ds), mode=\"FIRST\")"))
        {
            Rule rule = load("not empty($v)", "$c", "distinct(FLAG)", "$ds",
                    "extract_metadata(name=\"dataset_name\")", "$v", call);
            assertNull(rule.getLoadError(), call + " → " + rule.getLoadError());
        }
    }


    @Test
    void aDatasetLevelBindingInsideFilterIsTheRulesOwnValueNotTheTargetTables() throws Exception
    {
        // Coordinator decision 2: the filter runs in the target table's context, so the binding's
        // value is resolved BEFORE (against the rule's dataset) and handed in as a constant. Were
        // the rule's variables passed through, the lazily evaluated binding would read TS —
        // `distinct(FLAG)` = ["N"] (2 rows match) and dataset_name = "TS" (2 rows) — and both
        // Checks below would answer 0 findings.
        IDataTable dm = dm();
        DatasetResolver resolver = study(dm, ts());
        Rule inList = load("not empty(USUBJID) and $n == 1", "$c", "distinct(FLAG)", "$n",
                "record_count(domain=TS, filter=(TSVAL in $c))");
        assertNull(inList.getLoadError(), inList.getLoadError());
        assertEquals(2, violations(inList, dm, resolver), "TSVAL in [\"Y\"] matches one TS row");
        Rule scalar = load("not empty(USUBJID) and $p == \"A\"", "$ds",
                "extract_metadata(name=\"dataset_name\")", "$p",
                "read_value(TSPARM, domain=TS, filter=(PARENT == $ds), mode=\"FIRST\")");
        assertNull(scalar.getLoadError(), scalar.getLoadError());
        assertEquals(2, violations(scalar, dm, resolver), "PARENT == \"DM\" is TS row 1 → A");
        // the control for the fixture itself: the literal the binding holds answers the same
        Rule literal = load("not empty(USUBJID) and $n == 1", "$n",
                "record_count(domain=TS, filter=(TSVAL in [\"Y\"]))");
        assertEquals(2, violations(literal, dm, resolver));
        // and without domain= the target IS the primary: FLAG in ["Y"] on DM counts both rows
        Rule own = load("not empty(USUBJID) and $n == 2", "$c", "distinct(FLAG)", "$n",
                "record_count(filter=(FLAG in $c))");
        assertEquals(2, violations(own, dm, resolver));
    }


    @Test
    void aPerRowBindingInsideFilterIsTheLevelFinding() throws Exception
    {
        // A per-row `$` has no one value a filter over ANOTHER table's rows could take: the level
        // finding (G3's derivation, ListOperandLevel), one sentence, stage A's kind.
        for (String call : List.of("record_count(domain=TS, filter=(TSVAL == $p))",
                "distinct(TSVAL, domain=TS, filter=(TSVAL == $p))",
                "max(TSSEQ, domain=TS, group=[TSVAL], filter=(TSVAL == $p))",
                "read_value(TSPARM, domain=TS, filter=(TSVAL == $p), mode=\"FIRST\")"))
        {
            String error = String
                    .valueOf(load("not empty($v)", "$p", "FLAG", "$v", call).getLoadError());
            String fn = call.substring(0, call.indexOf('('));
            assertTrue(error.contains("OPERATION_READS_CURSOR_BINDING"), call + " → " + error);
            assertTrue(error.contains("argument 'filter' of '" + fn + "'"), call + " → " + error);
            assertTrue(error.contains("per-row or per-variable value `$p`"), call + " → " + error);
            assertTrue(error.contains("the binding $v"), call + " → " + error);
        }
    }


    @Test
    void aPerRowBindingInANestedFilterIsTheInnerCallsOneFinding() throws Exception
    {
        // The outer filter's walk does not descend into the nested call's own filter=: one
        // finding, attributed to the function that reads the `$`, never a second one for the outer.
        String error = String.valueOf(load("not empty($v)", "$p", "FLAG", "$v",
                "max(TSSEQ, domain=TS, group=[TSVAL],"
                        + " filter=(record_count(domain=TS, filter=(TSVAL == $p)) > 0))")
                                .getLoadError());
        assertTrue(error.contains("argument 'filter' of 'record_count'"), error);
        assertTrue(!error.contains("argument 'filter' of 'max'"), error);
    }


    @Test
    void aPerRowBindingInsideFilterInACheckLessRuleIsTheLoadersFinding() throws Exception
    {
        // The path stage A never walks: the loader's seam pass files the same sentence.
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "BS3-CHECKLESS"));
        rule.put("Bindings", List.of(Map.of("name", "$p", "expression", "FLAG"), Map.of("name",
                "$v", "expression", "record_count(domain=TS, filter=(TSVAL == $p))")));
        rule.put("Match_Datasets", List.of(Map.of("Name", "DM")));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$v")));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded);
        String error = String.valueOf(loaded.getLoadError());
        assertTrue(error.contains("argument 'filter' of 'record_count'"), error);
        assertTrue(error.contains("per-row or per-variable value `$p`"), error);
        assertTrue(error.contains("the binding $v"), error);
    }


    @Test
    void theExpressionInlineInsideFilterIsScopedToTheTargetTable() throws Exception
    {
        // NOT substitutable by construction (like C15): every reference written inside filter=(…)
        // is a column of the TARGET table (functions.md, the filter rows), so `distinct(FLAG)`
        // inline reads TS's FLAG (["N"], two rows) where the binding read DM's (["Y"], one row).
        // Pinned so that the asymmetry is a documented scope rule, never a silent surprise.
        IDataTable dm = dm();
        DatasetResolver resolver = study(dm, ts());
        Rule inline = load("not empty(USUBJID) and $n == 2", "$n",
                "record_count(domain=TS, filter=(TSVAL in distinct(FLAG)))");
        assertNull(inline.getLoadError(), inline.getLoadError());
        assertEquals(2, violations(inline, dm, resolver));
    }


    /**
     * A registry call as a rule: a BOOLEAN function is the Check itself, a value function is bound
     * to {@code $v} and the Check reads {@code not empty($v)}.
     */
    private static Rule loadCall(String call, String... bindings) throws Exception
    {
        String name = call.substring(0, call.indexOf('('));
        FunctionDescriptor d = FunctionRegistry.descriptor(name);
        assertNotNull(d, name);
        if (d.kind() == FunctionKind.BOOLEAN)
        {
            return load(call, bindings);
        }
        List<String> all = new ArrayList<>(List.of(bindings));
        all.add("$v");
        all.add(call);
        return load("not empty($v)", all.toArray(new String[0]));
    }


    private static Rule load(String check, String... bindings) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "BS1-PAIR"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        if (!declared.isEmpty())
        {
            rule.put("Bindings", declared);
        }
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m"));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded, "the rule parses");
        return loaded;
    }


    @Test
    void aPerRowSpliceMemberOfUnknownTypeIsTheLevelFindingToo() throws Exception
    {
        // R5 review BS2b LOW-4: stage A skipped the level of a spliced member it could not type
        // (coalesce answers unknown), so the computed per-row member read row 0 silently
        assertPerRowSplice("record_count(group=[USUBJID, coalesce(AETERM, AEDECOD)]) > 1");
        // W1b UC1 (PLAN-engine-gap-unique-computed Q2): is_(not_)unique_set's members read the
        // per-row member as a VALUE key component instead — it loads (UniqueSetComputedMemberTest)
        assertNull(load("not is_unique_set([USUBJID, coalesce(AETERM, AEDECOD)])").getLoadError());
        // control: a dataset-level member of unknown type splices
        assertNull(
                load("not is_unique_set([USUBJID, coalesce(\"AETERM\", \"X\")])").getLoadError());
    }


    @Test
    void aPerRowBareSpliceOperandOfUnknownTypeIsTheLevelFindingToo() throws Exception
    {
        // the same gap at the bare operand (f(<expression>) reads as f([<expression>]), C5) —
        // and since W1b UC1 the same admission: the bare per-row operand is the one-member value
        // key
        assertPerRowSplice("record_count(group=coalesce(AETERM, AEDECOD)) > 1");
        assertNull(load("not is_unique_set(coalesce(AETERM, AEDECOD))").getLoadError());
        assertNull(load("not is_unique_set(coalesce(\"AETERM\", \"X\"))").getLoadError());
    }


    private static void assertPerRowSplice(String check) throws Exception
    {
        String error = String.valueOf(load(check).getLoadError());
        assertTrue(
                error.contains("PARAMETER_TYPE") && error
                        .contains("cannot splice the per-row member `coalesce(AETERM, AEDECOD)`"),
                check + " → " + error);
    }
}
