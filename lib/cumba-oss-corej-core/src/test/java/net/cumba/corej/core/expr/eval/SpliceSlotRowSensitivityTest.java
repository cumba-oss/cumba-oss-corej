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
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.StudyRuleClassifier;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 SC (coordinator W2-D7, re-scoped after W1b P5b): a Check whose ONLY column names
 * arrive through a splice slot — a {@code $} binding holding a dataset-level name list, or that
 * expression written in place — answers per row exactly as the written names do, with
 * {@code Sensitivity} unauthored. Two seams decide it: {@code DomainScan} (the evaluation domain: a
 * grouped reader answers one value per row) and {@code RuleClassifier} (the derived Sensitivity:
 * without a Record usage the runner collapses N per-row findings to one, and a splice of a
 * study-level list function such as {@code minus} must not route the rule to the study anchor). One
 * row per splice slot family: the {@code group=} of record_count / max / max_date / min_date /
 * read_value / distinct / sequence (the classifier's grouped-call usage), the compiler-dispatched
 * readers' {@code group=} / {@code by=} / {@code within=} (BS2c), {@code values}' columns and
 * {@code is_unique_set}'s members (W1b P5b's two fixes, pinned).
 */
class SpliceSlotRowSensitivityTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A dataset-level expression holding the two group names (minus is a study-level function). */
    private static final String NAMES = "minus([\"USUBJID\", \"AETERM\", \"X\"], subtract=[\"X\"])";

    /** The same for the one name a single-column slot takes. */
    private static final String ONE = "minus([\"USUBJID\", \"X\"], subtract=[\"X\"])";

    /** The same for the sort key. */
    private static final String SEQ = "minus([\"AESEQ\", \"X\"], subtract=[\"X\"])";

    /**
     * Rows 1 / 2 share (USUBJID, AETERM); USUBJID has two rows per subject; AESEQ runs 1..4 in file
     * order, REV against it.
     */
    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2", "S2")
                .col("AETERM", "a", "a", "b", "c").col("AESEQ", "1", "2", "3", "4")
                .col("AESTDTC", "2020-01-01", "2020-01-02", "2020-01-03", "2020-01-04")
                .col("REV", "9", "8", "7", "6").build();
    }

    /**
     * The checks, each over @g (two names), @w (one name) and @s (the sort key), and each firing on
     * more than one row when written with the names.
     */
    private static final List<String> CHECKS = List.of("record_count(group=@g) > 1",
            "max(AESEQ, group=@g) == 2", "max_date(AESTDTC, group=@g) == \"2020-01-02\"",
            "min_date(AESTDTC, group=@g) == \"2020-01-01\"",
            "read_value(AESEQ, domain=AE, mode=\"MAX\", group=@g) == 2",
            "count(distinct(AESEQ, group=@g)) > 1", "count(sequence(AESEQ, group=@g)) > 1",
            "not is_last_in_group(by=@s, group=@w)", "count(values(@g)) > 1",
            "not is_unique_set(@g)", "present_on_multiple_rows_within(AETERM, within=@w)",
            "not is_sorted_by(REV, by=@s, within=@w)");

    /** One spelling of a check: how each placeholder is filled, and the bindings it declares. */
    private record Spelling(String label, String g, String w, String s)
    {

        String fill(String check)
        {
            return check.replace("@g", g).replace("@w", w).replace("@s", s);
        }
    }

    private static final List<Spelling> SPELLINGS = List.of(
            new Spelling("written", "[USUBJID, AETERM]", "USUBJID", "AESEQ"),
            new Spelling("bound", "$g", "$w", "$s"),
            new Spelling("bound, bracketed", "[$g]", "[$w]", "[$s]"),
            new Spelling("inline", NAMES, ONE, SEQ),
            new Spelling("inline, bracketed", "[" + NAMES + "]", "[" + ONE + "]", "[" + SEQ + "]"));

    @Test
    void aSpliceOnlyCheckFindsPerRowAsTheWrittenNamesDo() throws Exception
    {
        List<String> failures = new ArrayList<>();
        for (String template : CHECKS)
        {
            Map<String, Integer> fired = new LinkedHashMap<>();
            Map<String, Sensitivity> derived = new LinkedHashMap<>();
            for (Spelling spelling : SPELLINGS)
            {
                String check = spelling.fill(template);
                Rule rule = load(check);
                assertNull(rule.getLoadError(), check + " -> " + rule.getLoadError());
                fired.put(spelling.label(), violations(rule, ae()));
                derived.put(spelling.label(), rule.getSensitivity());
                if (StudyRuleClassifier.isAnchorEligible(rule))
                {
                    failures.add(check + ": routed to the study anchor");
                }
            }
            int written = fired.get("written");
            assertTrue(written > 1, template + ": the written names fire per row: " + fired);
            for (Map.Entry<String, Integer> e : fired.entrySet())
            {
                if (e.getValue() != written)
                {
                    failures.add(template + " [" + e.getKey() + "]: " + fired);
                }
                if (derived.get(e.getKey()) != Sensitivity.RECORD)
                {
                    failures.add(template + " [" + e.getKey() + "] derives " + derived);
                }
            }
        }
        assertEquals(List.of(), failures);
    }


    @Test
    void anUnscopedSpliceOfAStudyLevelListFunctionIsNotAStudyRule() throws Exception
    {
        // minus is a study-level list function: a classifier that read the splice as the
        // function's own usage derived Study, and an unscoped Study rule runs ONCE on the anchor
        for (String check : List.of("record_count(group=" + NAMES + ") > 1",
                "not is_unique_set(" + NAMES + ")", "max(AESEQ, group=[" + NAMES + "]) == 2"))
        {
            Rule rule = load(check);
            assertNull(rule.getLoadError(), rule.getLoadError());
            RuleClassifier.Derived<Sensitivity> d = RuleClassifier.deriveSensitivity(rule);
            assertEquals(Sensitivity.RECORD, d.value(), check + ": " + d.rationale());
            assertFalse(StudyRuleClassifier.isAnchorEligible(rule), check);
        }
    }


    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table,
                name -> "AE".equalsIgnoreCase(name) ? table : null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().size();
    }


    /** A rule with no Scope and no Sensitivity, declaring only the bindings its Check names. */
    private static Rule load(String check) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "SC-SPLICE"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (String[] b : List.of(new String[]
        {
                "$g", NAMES
        }, new String[]
        {
                "$w", ONE
        }, new String[]
        {
                "$s", SEQ
        }))
        {
            if (check.contains(b[0]))
            {
                declared.add(Map.of("name", b[0], "expression", b[1]));
            }
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
}
