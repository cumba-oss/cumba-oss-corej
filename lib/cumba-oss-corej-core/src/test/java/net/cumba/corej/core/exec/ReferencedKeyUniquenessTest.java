package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code referenced_key_is_unique(dataset, key_variable=, keys=[…])} (runbook W1b VA,
 * {@code PLAN-engine-gap-value-addressed} V4, Q5): per row, whether the dataset the row's
 * {@code dataset} value names has at most one record per ({@code keys}, the column
 * {@code key_variable} names) — the {@code is_unique_set} policy: {@code ""} and a missing are
 * real, distinct key components, an absent column is dropped from the tuple (a stated answer); an
 * absent dataset, a blank dataset or a blank column name answer missing.
 */
class ReferencedKeyUniquenessTest
{

    private static final String CALL = "referenced_key_is_unique(RDOMAIN, key_variable=IDVAR,"
            + " keys=[USUBJID])";

    private static Rule load(String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"X-1\":{"
                    + "\"Core\":{\"Id\":\"X-1\"}," + "\"Check\":{\"expression\":\"" + check + "\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"RELID\"]}}}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + check, e);
        }
    }


    private static IDataTable ae()
    {
        return RealTableFixture.of("AE").str("DOMAIN", "AE", "AE", "AE")
                .str("USUBJID", "A", "A", "B").str("AESPID", "1", "1", "2")
                .str("AEGRPID", "G1", "G2", "G3").build();
    }


    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("DOMAIN", "DM", "DM").str("USUBJID", "A", "B").build();
    }


    /** RELREC's dataset-level rows: a blank USUBJID, the declaration per (RDOMAIN, IDVAR). */
    private static IDataTable relrec()
    {
        return RealTableFixture.of("RELREC").str("RELID", "1", "2", "3", "4", "5", "6", "7")
                .str("RDOMAIN", "AE", "DM", "AE", "ZZ", "AE", "", "AE")
                .str("USUBJID", "", "", "", "", "", "", "")
                .str("IDVAR", "AESPID", "USUBJID", "AEGRPID", "USUBJID", "", "USUBJID", "NOPE")
                .str("RELTYPE", "ONE", "ONE", "ONE", "ONE", "ONE", "ONE", "ONE").build();
    }


    private static List<@Nullable Boolean> rows(String call, IDataTable primary,
            IDataTable... others)
    {
        List<IDataTable> all = new ArrayList<>();
        all.add(primary);
        all.addAll(List.of(others));
        EvaluationContext ctx = EvaluationContext.builder().table(primary)
                .datasetResolver(
                        new ReferencedRecordCountTest.Inventory(all.toArray(IDataTable[]::new)))
                .build();
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        List<@Nullable Boolean> out = new ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add(v.value(row).cell().isMissingOrInvalid() ? null
                    : (Boolean) v.value(row).resolved());
        }
        return out;
    }


    @Test
    void theVerdictPerPointerRowAndTheRuledAbsentShapes()
    {
        List<@Nullable Boolean> unique = rows(CALL, relrec(), ae(), dm());
        assertEquals(false, unique.get(0), "A carries AESPID 1 twice");
        assertEquals(true, unique.get(1));
        assertEquals(true, unique.get(2), "AEGRPID is unique per subject");
        assertNull(unique.get(3), "an unknown dataset answers missing");
        assertNull(unique.get(4), "a blank column name answers missing");
        assertNull(unique.get(5), "a blank dataset value answers missing");
        assertEquals(false, unique.get(6),
                "an absent column is dropped from the tuple: USUBJID alone is not unique on AE");
    }


    @Test
    void withoutKeysTheNamedColumnAloneIsTheTuple()
    {
        List<@Nullable Boolean> unique = rows(
                "referenced_key_is_unique(RDOMAIN, key_variable=IDVAR)", relrec(), ae(), dm());
        assertEquals(false, unique.get(0));
        assertEquals(true, unique.get(2));
    }


    @Test
    void blankKeyComponentsAreRealAndDistinct()
    {
        IDataTable blanks = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "A", "A").str("AESPID", "", "").build();
        IDataTable blankAndMissing = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "A", "A").str("AESPID", "", null).build();
        IDataTable pointer = RealTableFixture.of("RELREC").str("RDOMAIN", "AE").str("USUBJID", "")
                .str("IDVAR", "AESPID").build();
        assertEquals(List.of(false), rows(CALL, pointer, blanks), "\"\" twice is a duplicate");
        assertEquals(List.of(true), rows(CALL, pointer, blankAndMissing),
                "\"\" and a missing are distinct components (Fix #249)");
    }


    @Test
    void aSplitFamilyIsJudgedAsTheOneDomainItIs()
    {
        // R5 P6-M2 (SDTMIG: a split domain is ONE domain — --SEQ / --SPID uniqueness spans its
        // split datasets; referenced_record_count already sums across them): the family's keys
        // are folded across every member
        IDataTable lbch = RealTableFixture.of("lbch").str("DOMAIN", "LB", "LB")
                .str("USUBJID", "A", "A").str("LBSPID", "1", "2").build();
        IDataTable lbhe = RealTableFixture.of("lbhe").str("DOMAIN", "LB", "LB")
                .str("USUBJID", "A", "A").str("LBSPID", "3", "3").build();
        IDataTable pointer = RealTableFixture.of("RELREC").str("RDOMAIN", "LB").str("USUBJID", "")
                .str("IDVAR", "LBSPID").build();
        assertEquals(List.of(false), rows(CALL, pointer, lbch, lbhe),
                "a duplicate inside one member of the family");
        IDataTable lbchSeq = RealTableFixture.of("lbch").str("DOMAIN", "LB").str("USUBJID", "A")
                .lng("LBSEQ", 1L).build();
        IDataTable lbheSeq = RealTableFixture.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "A")
                .lng("LBSEQ", 1L).build();
        IDataTable seqPointer = RealTableFixture.of("RELREC").str("RDOMAIN", "LB")
                .str("USUBJID", "").str("IDVAR", "LBSEQ").build();
        assertEquals(List.of(false), rows(CALL, seqPointer, lbchSeq, lbheSeq),
                "LBSEQ 1 of subject A in two members: not unique in the domain");
        IDataTable lbheOther = RealTableFixture.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "A")
                .lng("LBSEQ", 2L).build();
        assertEquals(List.of(true), rows(CALL, seqPointer, lbchSeq, lbheOther));
    }


    @Test
    void theCorpusShapeOfRowGFiresOnTheDeclarationRowsOnly()
    {
        Rule rule = load("RELTYPE == \\\"ONE\\\" and " + CALL.replace("\"", "\\\"") + " == false");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, relrec(),
                new ReferencedRecordCountTest.Inventory(relrec(), ae(), dm()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("1", "7"),
                result.getViolations().stream().map(f -> f.getValues().get("RELID")).toList());
    }


    @Test
    void aQuotedKeyIsALoadError()
    {
        Rule quoted = load(
                "referenced_key_is_unique(RDOMAIN, key_variable=IDVAR, keys=[\\\"USUBJID\\\"]) == false");
        assertNotNull(quoted.getLoadError());
    }
}
