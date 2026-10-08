package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RealTables;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b UC1 ({@code PLAN-engine-gap-unique-computed} §5, §6.1 Q1–Q6; coordinator D1–D3): a
 * <b>per-row</b> expression written as a member of {@code is_(not_)unique_set} is a VALUE key
 * component with the {@code KeyPart} identity a column cell gets ({@code ""} and each missing
 * distinct); the level decides (Q2, D2): a dataset-level member still splices as names, bound and
 * inline alike (G1). A {@code duration(…)} member keys by its hull — {@code P1W} and {@code P7D}
 * are one key, an unpositionable text keys by its text (dur-order Q5 (a), D1). {@code regex=}
 * beside a value member is a load error (Q4); a per-row list member is a {@code PARAMETER_TYPE}
 * finding (a list is not a key component). The column path is unchanged: a column-only list answers
 * exactly as before, an absent member beside a computed one is dropped as it always was.
 */
class UniqueSetComputedMemberTest
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


    private static List<Long> fired(IDataTable table, String check, String... bindings)
    {
        Rule r = load(check, bindings);
        assertNull(r.getLoadError(), () -> check + " -> " + r.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(r, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /**
     * PC (row C of the plan): S1's records carry no {@code PCDTC} — the observation time is the
     * nominal {@code PCELTM}; S2's carry {@code PCDTC}. Rows 1-2 repeat S1's {@code PT1H}; rows 5-6
     * repeat S2's instant; row 7 is S2 at another instant with S1's elapsed time.
     */
    private static IDataTable pc()
    {
        return RealTables.of("PC").str("DOMAIN", "PC", "PC", "PC", "PC", "PC", "PC", "PC")
                .str("USUBJID", "S1", "S1", "S1", "S2", "S2", "S2", "S2")
                .str("PCTESTCD", "T", "T", "T", "T", "T", "T", "T")
                .str("PCDTC", null, null, null, "2020-01-01T08", "2020-01-01T09", "2020-01-01T09",
                        "2020-01-01T10")
                .str("PCELTM", "PT1H", "PT1H", "PT2H", "PT0H", "PT1H", "PT1H", "PT1H").build();
    }


    /** TD (dur-order row C): anchors with offsets whose text differs where the hull agrees. */
    private static IDataTable td()
    {
        return RealTables.of("TD")
                .str("DOMAIN", "TD", "TD", "TD", "TD", "TD", "TD", "TD", "TD", "TD", "TD", "TD")
                .str("STUDYID", "S", "S", "S", "S", "S", "S", "S", "S", "S", "S", "S")
                .str("TDANCVAR", "A", "A", "A", "B", "B", "C", "C", "D", "D", "E", "E")
                .str("TDSTOFF", "P0D", "P60W", "P120W", "P60W", "P30D", "P1W", "P7D", "P1M", "P30D",
                        "abc", "abc")
                .build();
    }


    @Test
    void aCoalesceMemberIsAPerRowKeyComponent()
    {
        // S1: PT1H twice (rows 1-2); S2: 09h twice (rows 5-6); row 7 shares S1's PT1H but its
        // PCDTC is the key — not a duplicate
        assertEquals(List.of(1L, 2L, 5L, 6L),
                fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, coalesce(PCDTC, PCELTM)])"));
        // the column form the plan calls the defensible interim under-reports S1's duplicate
        // (PCELTM is a second member, which S1's rows 1-2 share too) and keys S2 on both
        assertEquals(List.of(1L, 2L, 5L, 6L),
                fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, PCDTC, PCELTM])"));
        // keyed on PCDTC alone (CDISC-SEND-0192's shape) S1's rows all share the missing — the
        // false fire the computed member removes
        assertEquals(List.of(1L, 2L, 3L, 5L, 6L),
                fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, PCDTC])"));
        // the bare operand reads as the one-member list (C5)
        assertEquals(fired(pc(), "not is_unique_set([coalesce(PCDTC, PCELTM)])"),
                fired(pc(), "not is_unique_set(coalesce(PCDTC, PCELTM))"));
    }


    @Test
    void emptyAndMissingAreRealDistinctComponentsOfAComputedMember()
    {
        // upper("") is "" and upper(«missing») keeps the missing (D34 / D36), so the computed
        // member must key exactly as the column it reads — "" ↔ "", missing ↔ missing, never
        // across
        IDataTable t = RealTables.of("AE").str("DOMAIN", "AE", "AE", "AE", "AE", "AE", "AE")
                .str("USUBJID", "S1", "S1", "S1", "S1", "S2", "S2")
                .str("AETERM", "", "", null, null, "", null).build();
        List<Long> column = fired(t, "not is_unique_set([USUBJID, AETERM])");
        assertEquals(List.of(1L, 2L, 3L, 4L), column, "premise: the column path");
        assertEquals(column, fired(t, "not is_unique_set([USUBJID, upper(AETERM)])"));
    }


    @Test
    void anAbsentColumnBesideAComputedMemberIsDroppedAsBefore()
    {
        assertEquals(fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, coalesce(PCDTC, PCELTM)])"),
                fired(pc(),
                        "not is_unique_set([USUBJID, NOPE, PCTESTCD, coalesce(PCDTC, PCELTM)])"));
        assertEquals(fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, coalesce(PCDTC, PCELTM)])"),
                fired(pc(),
                        "not is_unique_set([USUBJID, PCTESTCD, coalesce(PCDTC, PCELTM), NOPE])"));
    }


    @Test
    void aBoundPerRowMemberAnswersAsTheInlineOne()
    {
        List<Long> inline = fired(pc(),
                "not is_unique_set([USUBJID, PCTESTCD, coalesce(PCDTC, PCELTM)])");
        assertEquals(inline, fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, $t])", "$t",
                "coalesce(PCDTC, PCELTM)"));
        assertEquals(fired(pc(), "not is_unique_set(coalesce(PCDTC, PCELTM))"),
                fired(pc(), "not is_unique_set($t)", "$t", "coalesce(PCDTC, PCELTM)"));
        // a dataset-level binding of names still splices as names — the two spellings agree
        assertEquals(fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, PCELTM])"),
                fired(pc(), "not is_unique_set([USUBJID, PCTESTCD, $n])", "$n", "[PCELTM]"));
    }


    @Test
    void regexBesideAComputedMemberIsALoadErrorBoundAndInlineAlike()
    {
        String inline = loadError(
                "not is_unique_set([USUBJID, coalesce(PCDTC, PCELTM)], regex=\"^\\\\d{4}\")");
        String bound = loadError("not is_unique_set([USUBJID, $t], regex=\"^\\\\d{4}\")", "$t",
                "coalesce(PCDTC, PCELTM)");
        assertTrue(inline.contains("applies to column members only")
                && inline.contains("coalesce(PCDTC, PCELTM)"), inline);
        assertTrue(bound.contains("applies to column members only") && bound.contains("$t"), bound);
        // regex= beside a dataset-level $ name list keeps loading — CDISC-CG0562's shape
        assertNull(load(
                "is_unique_set([--REPNUM, USUBJID, --TESTCD, $TIMING_VARIABLES],"
                        + " regex=\"^\\\\d{4}-\\\\d{2}-\\\\d{2}\")",
                "$TIMING_VARIABLES",
                "get_dataset_filtered_variables(key_name=\"role\", key_value=\"Timing\")")
                        .getLoadError());
    }


    @Test
    void aPerRowListMemberIsNotAKeyComponent()
    {
        String inline = loadError("not is_unique_set([USUBJID, colref([\"A\", \"B\"])])");
        String bound = loadError("not is_unique_set([USUBJID, $cl])", "$cl",
                "colref([\"A\", \"B\"])");
        assertTrue(inline.contains("PARAMETER_TYPE") && inline.contains("not a key component"),
                inline);
        assertTrue(bound.contains("PARAMETER_TYPE") && bound.contains("not a key component"),
                bound);
    }


    @Test
    void aDurationMemberKeysByItsHull()
    {
        // C: P1W and P7D are one hull; D: P1M (28-31 days) is not P30D; E: an unpositionable
        // text keys by its text; the text form keys C apart
        assertEquals(List.of(6L, 7L, 10L, 11L),
                fired(td(), "not is_unique_set([STUDYID, TDANCVAR, duration(TDSTOFF)])"));
        assertEquals(List.of(), fired(td(), "not is_unique_set([STUDYID, TDANCVAR, TDSTOFF])")
                .stream().filter(r -> r == 6L || r == 7L).toList());
        // bound ≡ inline (G1): the hull key travels through the binding
        assertEquals(fired(td(), "not is_unique_set([STUDYID, TDANCVAR, duration(TDSTOFF)])"),
                fired(td(), "not is_unique_set([STUDYID, TDANCVAR, $d])", "$d",
                        "duration(TDSTOFF)"));
    }


    @Test
    void anUnpositionableDurationKeysByItsText()
    {
        // E: "abc" twice — equal text, one key; against a positionable neighbour never equal
        List<Long> rows = fired(td(), "not is_unique_set([STUDYID, TDANCVAR, duration(TDSTOFF)])");
        assertTrue(rows.contains(10L) && rows.contains(11L), rows.toString());
        IDataTable mixed = RealTables.of("TD").str("DOMAIN", "TD", "TD", "TD")
                .str("TDANCVAR", "A", "A", "A").str("TDSTOFF", "abc", "abd", "P1W").build();
        assertEquals(List.of(), fired(mixed, "not is_unique_set([TDANCVAR, duration(TDSTOFF)])"));
    }


    @Test
    void theColumnOnlyListIsUnchanged()
    {
        // a differential against the primitive: no value member, the same reader as before
        IDataTable t = pc();
        List<Long> rows = fired(t, "not is_unique_set([USUBJID, PCTESTCD, PCELTM])");
        assertEquals(List.of(1L, 2L, 5L, 6L, 7L), rows);
        assertEquals(List.of(), fired(t, "not is_unique_set([USUBJID, PCTESTCD, PCELTM, PCDTC])")
                .stream().filter(r -> r == 7L).toList());
    }
}
