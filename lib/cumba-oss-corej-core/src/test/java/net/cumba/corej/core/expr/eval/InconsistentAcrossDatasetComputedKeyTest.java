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
 * {@code PLAN-engine-per-row-key-expression} (rule round 2026-10-15-b, batch 8 #9; catalog task
 * T3): a <b>per-row</b> member of {@code is_inconsistent_across_dataset}'s {@code keys=} list — the
 * coding key {@code coalesce(--MODIFY, --TERM)} — is a VALUE key component, exactly as a per-row
 * member of {@code is_(not_)unique_set} is (runbook W1b UC1): its per-row value keys the group with
 * the {@code KeyPart} identity a column cell holding that value gets, under the operator's own
 * missing-key policy ({@code DROP_MISSING_KEYS} default, {@code keep_missings=} declared). Bound
 * and inline spell the same key (G1); a dataset-level member still splices as names; a per-row list
 * is not a key component. An absent {@code --MODIFY} (CE has none) reads its absent-column default,
 * which {@code coalesce} skips — the key is {@code --TERM}, no special case. The column-only list
 * is unchanged.
 */
class InconsistentAcrossDatasetComputedKeyTest
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
     * AE: rows 1-3 share the coded term {@code HEADACHE} (two via {@code AEMODIFY}, row 3 via its
     * {@code AETERM}) and row 2 is coded apart; rows 4-5 share the verbatim {@code PAIN} but were
     * modified to two terms, coded apart legitimately; rows 6-7 have neither term — blank
     * {@code ""} — and are coded apart; rows 8-9 have neither term — missing — and are coded apart.
     * Column {@code K} is the coding key materialised by hand: what {@code coalesce(AEMODIFY,
     * AETERM)} answers per row (a computed missing where both are blank, CO1).
     */
    private static IDataTable ae()
    {
        return RealTables.of("AE")
                .str("DOMAIN", "AE", "AE", "AE", "AE", "AE", "AE", "AE", "AE", "AE")
                .str("USUBJID", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9")
                .str("AETERM", "HEAD ACHE", "HEADACHE!!", "HEADACHE", "PAIN", "PAIN", "", "", null,
                        null)
                .str("AEMODIFY", "HEADACHE", "HEADACHE", "", "BACK PAIN", "NECK PAIN", "", "", null,
                        null)
                .str("AEDECOD", "Headache", "Cephalalgia", "Headache", "Back pain", "Neck pain",
                        "X", "Y", "X", "Y")
                .str("K", "HEADACHE", "HEADACHE", "HEADACHE", "BACK PAIN", "NECK PAIN", null, null,
                        null, null)
                .build();
    }


    @Test
    void aCoalesceKeyGroupsByTheCodedTerm()
    {
        // HEADACHE: Headache x2 against Cephalalgia x1 - the minority row 2 fires; the PAIN
        // records are two coded terms, silent; the all-blank rows carry a missing key, dropped
        assertEquals(List.of(2L), fired(ae(),
                "is_inconsistent_across_dataset(AEDECOD, keys=[coalesce(AEMODIFY, AETERM)])"));
        // the verbatim key the rules carry today: the HEADACHE records are three groups
        // (silent), PAIN is one group coded two ways (a tie: both fire)
        assertEquals(List.of(4L, 5L),
                fired(ae(), "is_inconsistent_across_dataset(AEDECOD, keys=[AETERM])"));
        // the bare operand reads as the one-member list (C5)
        assertEquals(List.of(2L), fired(ae(),
                "is_inconsistent_across_dataset(AEDECOD, keys=coalesce(AEMODIFY, AETERM))"));
    }


    @Test
    void theComputedKeyKeysExactlyAsTheMaterialisedColumn()
    {
        // the differential against the column path: under the DROP default and with
        // keep_missings=true (the both-blank rows then form a group of their own, coded apart)
        assertEquals(fired(ae(), "is_inconsistent_across_dataset(AEDECOD, keys=[K])"), fired(ae(),
                "is_inconsistent_across_dataset(AEDECOD, keys=[coalesce(AEMODIFY, AETERM)])"));
        List<Long> kept = fired(ae(),
                "is_inconsistent_across_dataset(AEDECOD, keys=[K], keep_missings=true)");
        assertTrue(kept.containsAll(List.of(6L, 7L, 8L, 9L)), "premise: " + kept);
        assertEquals(kept, fired(ae(), "is_inconsistent_across_dataset(AEDECOD,"
                + " keys=[coalesce(AEMODIFY, AETERM)], keep_missings=true)"));
    }


    @Test
    void emptyAndMissingAreDistinctGroupsOfAComputedKey()
    {
        // D11's grouping half: "" and a missing are distinct groups. upper("") is "" and
        // upper(«missing») keeps the missing, so under keep_missings=true the computed key must
        // group exactly as the column it reads - "" coded X twice, the missing coded Y twice: two
        // consistent groups, silent; one conflated group would be a tie, all four fire
        IDataTable t = RealTables.of("AE").str("DOMAIN", "AE", "AE", "AE", "AE")
                .str("USUBJID", "S1", "S2", "S3", "S4").str("AETERM", "", "", null, null)
                .str("AEDECOD", "X", "X", "Y", "Y").build();
        List<Long> column = fired(t,
                "is_inconsistent_across_dataset(AEDECOD, keys=[AETERM], keep_missings=true)");
        assertEquals(List.of(), column, "premise: the column path");
        assertEquals(column, fired(t, "is_inconsistent_across_dataset(AEDECOD,"
                + " keys=[upper(AETERM)], keep_missings=true)"));
    }


    @Test
    void aComputedKeyBesideAColumnKeyKeysTheTuple()
    {
        // DOMAIN is one value throughout: the tuple partitions as the computed key alone, in
        // either order; a column absent from the table is dropped as it always was
        List<Long> alone = fired(ae(),
                "is_inconsistent_across_dataset(AEDECOD, keys=[coalesce(AEMODIFY, AETERM)])");
        assertEquals(alone, fired(ae(), "is_inconsistent_across_dataset(AEDECOD,"
                + " keys=[DOMAIN, coalesce(AEMODIFY, AETERM)])"));
        assertEquals(alone, fired(ae(), "is_inconsistent_across_dataset(AEDECOD,"
                + " keys=[coalesce(AEMODIFY, AETERM), DOMAIN, NOPE])"));
        // USUBJID is unique per row: every group is a single record, nothing fires
        assertEquals(List.of(), fired(ae(), "is_inconsistent_across_dataset(AEDECOD,"
                + " keys=[USUBJID, coalesce(AEMODIFY, AETERM)])"));
    }


    @Test
    void aBoundPerRowKeyAnswersAsTheInlineOne()
    {
        // P0 of the plan: the bound spelling is asserted first - before this plan it was the
        // loader's refusal too ("cannot splice the per-row member `$ck`"), so a per-row $-binding
        // was no way around the engine change
        List<Long> bound = fired(ae(), "is_inconsistent_across_dataset(AEDECOD, keys=[$ck])", "$ck",
                "coalesce(AEMODIFY, AETERM)");
        assertEquals(List.of(2L), bound);
        assertEquals(bound, fired(ae(),
                "is_inconsistent_across_dataset(AEDECOD, keys=[coalesce(AEMODIFY, AETERM)])"));
        assertEquals(bound, fired(ae(), "is_inconsistent_across_dataset(AEDECOD, keys=$ck)", "$ck",
                "coalesce(AEMODIFY, AETERM)"));
        // a dataset-level binding of names still splices as names - the two spellings agree
        assertEquals(fired(ae(), "is_inconsistent_across_dataset(AEDECOD, keys=[AETERM])"), fired(
                ae(), "is_inconsistent_across_dataset(AEDECOD, keys=[$n])", "$n", "[AETERM]"));
    }


    @Test
    void anAbsentModifyColumnReadsItsDefaultAndTheKeyIsTheTerm()
    {
        // CE carries no CEMODIFY: the absent column reads "" (D34 #3), which coalesce skips (CO1)
        // - the coding key is CETERM, no special case (the corpus's wildcard spelling
        // coalesce(--MODIFY, --TERM) is resolved by the bind-time specialiser, which this harness
        // does not run: the T3 CE scenarios cover it end to end)
        IDataTable ce = RealTables.of("CE").str("DOMAIN", "CE", "CE", "CE")
                .str("USUBJID", "S1", "S2", "S3").str("CETERM", "MIGRAINE", "MIGRAINE", "RASH")
                .str("CEDECOD", "Migraine", "Headache", "Rash").build();
        List<Long> byTerm = fired(ce, "is_inconsistent_across_dataset(CEDECOD, keys=[CETERM])");
        assertEquals(List.of(1L, 2L), byTerm, "premise: the verbatim key");
        assertEquals(byTerm, fired(ce,
                "is_inconsistent_across_dataset(CEDECOD, keys=[coalesce(CEMODIFY, CETERM)])"));
    }


    @Test
    void aPerRowListKeyIsNotAKeyComponent()
    {
        String inline = String.valueOf(load(
                "is_inconsistent_across_dataset(AEDECOD, keys=[USUBJID, colref([\"A\", \"B\"])])")
                        .getLoadError());
        String bound = String
                .valueOf(load("is_inconsistent_across_dataset(AEDECOD, keys=[USUBJID, $cl])", "$cl",
                        "colref([\"A\", \"B\"])").getLoadError());
        assertTrue(inline.contains("not a key component"), inline);
        assertTrue(bound.contains("not a key component"), bound);
    }


    @Test
    void theRelationshipReadersKeepRefusingAPerRowKey()
    {
        // the roster grew by is_inconsistent_across_dataset's keys= only: the relationship pair
        // shares keyMembers and still refuses the per-row member at load
        String error = String.valueOf(
                load("is_not_unique_relationship(AEDECOD, keys=[coalesce(AEMODIFY, AETERM)])")
                        .getLoadError());
        assertTrue(error.contains("cannot splice the per-row member"), error);
    }


    @Test
    void theColumnOnlyListIsUnchanged()
    {
        assertEquals(List.of(4L, 5L),
                fired(ae(), "is_inconsistent_across_dataset(AEDECOD, keys=[AETERM])"));
        assertEquals(List.of(2L), fired(ae(), "is_inconsistent_across_dataset(AEDECOD, keys=[K])"));
        assertEquals(List.of(), fired(ae(),
                "is_inconsistent_across_dataset(AEDECOD," + " keys=[AETERM, AEMODIFY])"));
    }
}
