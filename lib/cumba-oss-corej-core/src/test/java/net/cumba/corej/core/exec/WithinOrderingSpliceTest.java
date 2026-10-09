package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b BS2c ({@code PLAN-binding-substitutability} §3.2 C6 remainder, ruled Q2; coordinator
 * D4): the single-reference and {@code within=} slots the W1a BS2b round left — {@code within=} of
 * {@code has_multiple_values_for} (EC-24 nested components included),
 * {@code present_on_multiple_rows_within}, {@code has_next_corresponding_record} and
 * {@code is_sorted_by}; {@code by=} of {@code empty_within_except_last_row},
 * {@code has_next_corresponding_record} and {@code is_last_in_group}; and
 * {@code empty_within_except_last_row}'s single {@code group} operand — take a dataset-level
 * {@code $} binding of names (or a string) and the expression such a binding holds, written in
 * place, exactly as the written names: the two spellings load alike and answer alike. A per-row
 * member is refused at load with the one splice sentence; a spliced list of two names in a
 * single-reference slot is the rule's ERROR. The declared parameter types stay {@code unknown}:
 * stage A and the loader twin ask the splice roster ({@code GroupSplice.SPLICE_SLOTS}).
 */
class WithinOrderingSpliceTest
{

    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, String... bindings)
    {
        StringBuilder b = new StringBuilder("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"R\"}");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
            }
            b.append(']');
        }
        return b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}")
                .toString();
    }


    private static Rule load(String check, String... bindings)
    {
        try
        {
            Rule r = RulePackageLoader.loadFromString(json(check, bindings)).getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(check, bindings).getLoadError());
    }


    /** AE: S1 three rows (AESEQ order 1, 2, 3 is not AESTDTC order), S2 two rows. */
    static IDataTable ae()
    {
        return RealTableFixture.of("AE").str("DOMAIN", "AE", "AE", "AE", "AE", "AE")
                .str("USUBJID", "S1", "S1", "S1", "S2", "S2").dbl("AESEQ", 1.0, 2.0, 3.0, 1.0, 2.0)
                .str("AESTDTC", "2020-01-01", "2020-01-03", "2020-01-02", "2020-02-01",
                        "2020-02-03")
                .str("AEENDTC", "2020-01-05", "", "2020-01-09", "2020-02-02", "")
                .str("AETERM", "A", "B", "A", "C", "C").str("AEDECOD", "D1", "D1", "D2", "D3", "D3")
                .build();
    }


    private static RuleExecutionResult run(String check, String... bindings)
    {
        Rule r = load(check, bindings);
        assertNull(r.getLoadError(), () -> check + " -> " + r.getLoadError());
        return RuleRunnerCalls.execute(r, ae(), RealTables.inventoryOf(ae()), "AE", null);
    }


    private static List<Long> fired(String check, String... bindings)
    {
        RuleExecutionResult result = run(check, bindings);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /** The written form, the {@code $} form and the inline form answer the same rows. */
    private static List<Long> alike(String written, String bound, String inline, String... bindings)
    {
        List<Long> expected = fired(written);
        assertTrue(!expected.isEmpty(), "premise: the written form fires: " + written);
        assertEquals(expected, fired(bound, bindings), bound);
        assertEquals(expected, fired(inline, bindings), inline);
        return expected;
    }

    /** A dataset-level binding of one name, as a name list, and the expression it holds. */
    private static final String[] W =
    {
            "$w", "[USUBJID]", "$o", "[AESEQ]"
    };

    private static final String INLINE_W = "minus([\"USUBJID\", \"X\"], subtract=[\"X\"])";

    private static final String INLINE_O = "minus([\"AESEQ\", \"X\"], subtract=[\"X\"])";

    @Test
    void withinSplicesABoundNameListAndTheExpressionItHolds()
    {
        assertEquals(List.of(1L, 3L),
                alike("has_multiple_values_for(AEDECOD, AETERM, within=USUBJID)",
                        "has_multiple_values_for(AEDECOD, AETERM, within=$w)",
                        "has_multiple_values_for(AEDECOD, AETERM, within=" + INLINE_W + ")", W),
                "S1: AETERM A maps to D1 and D2");
        alike("has_multiple_values_for(AEDECOD, AETERM, within=[USUBJID])",
                "has_multiple_values_for(AEDECOD, AETERM, within=[$w])",
                "has_multiple_values_for(AEDECOD, AETERM, within=[" + INLINE_W + "])", W);
        alike("present_on_multiple_rows_within(AETERM, within=USUBJID)",
                "present_on_multiple_rows_within(AETERM, within=$w)",
                "present_on_multiple_rows_within(AETERM, within=" + INLINE_W + ")", W);
        alike("not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, by=AESEQ)",
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=$w, by=AESEQ)",
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=" + INLINE_W
                        + ", by=AESEQ)",
                W);
        alike("not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=USUBJID)",
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=$w)",
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=" + INLINE_W + ")", W);
    }


    @Test
    void aNestedWithinComponentSplicesTooEc24()
    {
        // EC-24: a nested list is a coalesce group; the spliced member stands inside it
        assertEquals(List.of(1L, 3L),
                alike("has_multiple_values_for(AEDECOD, AETERM, within=[[USUBJID, AETERM]])",
                        "has_multiple_values_for(AEDECOD, AETERM, within=[[$w, AETERM]])",
                        "has_multiple_values_for(AEDECOD, AETERM, within=[[" + INLINE_W
                                + ", AETERM]])",
                        W),
                "the coalesce group reads USUBJID (always populated)");
    }


    @Test
    void orderingAndTheSingleGroupOperandSpliceABoundNameAndTheExpressionItHolds()
    {
        alike("empty_within_except_last_row(AEENDTC, USUBJID, by=AESEQ)",
                "empty_within_except_last_row(AEENDTC, $w, by=$o)",
                "empty_within_except_last_row(AEENDTC, " + INLINE_W + ", by=" + INLINE_O + ")", W);
        alike("not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, by=AESEQ)",
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, by=$o)",
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, by=" + INLINE_O
                        + ")",
                W);
        alike("is_last_in_group(by=AESEQ, group=[USUBJID])",
                "is_last_in_group(by=$o, group=[USUBJID])",
                "is_last_in_group(by=" + INLINE_O + ", group=[USUBJID])", W);
        // a dataset-level STRING is one name (N29)
        assertEquals(fired("is_last_in_group(by=AESEQ, group=[USUBJID])"),
                fired("is_last_in_group(by=$s, group=[USUBJID])", "$s", "upper(\"aeseq\")"));
    }


    @Test
    void aPerRowMemberIsRefusedAtLoadBoundAndInlineAlike()
    {
        for (String[] pair : List.of(new String[]
        {
                "has_multiple_values_for(AEDECOD, AETERM, within=[$p])",
                "has_multiple_values_for(AEDECOD, AETERM, within=[upper(AETERM)])"
        }, new String[]
        {
                "present_on_multiple_rows_within(AETERM, within=$p)",
                "present_on_multiple_rows_within(AETERM, within=upper(AETERM))"
        }, new String[]
        {
                "empty_within_except_last_row(AEENDTC, USUBJID, by=$p)",
                "empty_within_except_last_row(AEENDTC, USUBJID, by=upper(AETERM))"
        }, new String[]
        {
                "empty_within_except_last_row(AEENDTC, $p, by=AESEQ)",
                "empty_within_except_last_row(AEENDTC, upper(AETERM), by=AESEQ)"
        }, new String[]
        {
                "is_last_in_group(by=$p, group=[USUBJID])",
                "is_last_in_group(by=upper(AETERM), group=[USUBJID])"
        }, new String[]
        {
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=[$p])",
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=[upper(AETERM)])"
        }))
        {
            String bound = loadError(pair[0], "$p", "upper(AETERM)");
            String inline = loadError(pair[1]);
            assertTrue(bound.contains("cannot splice the per-row member"), pair[0] + ": " + bound);
            assertEquals(bound.replace("$p", "upper(AETERM)"), inline, pair[1]);
        }
    }


    @Test
    void twoNamesInASingleReferenceSlotAreTheRulesError()
    {
        // a computed two-name list (a written name list is inlined at bind time, where the
        // strict reader refuses it as it refuses the written two-column list)
        String two = "minus([\"USUBJID\", \"AESEQ\", \"X\"], subtract=[\"X\"])";
        for (String check : List.of(
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=$t, by=AESEQ)",
                "not has_next_corresponding_record(AEENDTC, AESTDTC, within=USUBJID, by=$t)",
                "empty_within_except_last_row(AEENDTC, $t, by=AESEQ)",
                "empty_within_except_last_row(AEENDTC, USUBJID, by=$t)",
                "is_last_in_group(by=$t, group=[USUBJID])",
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=$t)"))
        {
            RuleExecutionResult result = run(check, "$t", two);
            assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                    () -> check + " -> " + result.getStatusMessage());
            assertTrue(String.valueOf(result.getStatusMessage()).contains("one column"),
                    result.getStatusMessage());
        }
    }


    @Test
    void theRosterNamesEverySlot()
    {
        for (String[] slot : List.of(new String[]
        {
                "has_multiple_values_for", "within"
        }, new String[]
        {
                "present_on_multiple_rows_within", "within"
        }, new String[]
        {
                "has_next_corresponding_record", "within"
        }, new String[]
        {
                "has_next_corresponding_record", "by"
        }, new String[]
        {
                "is_sorted_by", "within"
        }, new String[]
        {
                "empty_within_except_last_row", "group"
        }, new String[]
        {
                "empty_within_except_last_row", "by"
        }, new String[]
        {
                "is_last_in_group", "by"
        }))
        {
            assertTrue(GroupSplice.splices(slot[0], slot[1]), slot[0] + "." + slot[1]);
        }
    }
}
