package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * {@code referenced_record_count(dataset, value, key_variable=, keys=[…])} (runbook W1b VA,
 * {@code PLAN-engine-gap-value-addressed} V1, Q3 / Q4 / Q9): per row, how many records of the
 * dataset the row's {@code dataset} value names carry the row's {@code value} in the column
 * {@code key_variable} names and this row's {@code keys} — every matching record counts (the merged
 * evaluation table's {@code n:m}, never first-wins), a split family sums its members, an absent
 * dataset and a blank pointer answer missing (Q4), an absent pointer column answers 0, the pointer
 * compares as a join token (a numeric target column canonicalises the text,
 * {@code NumericKeyText}), {@code key_variable} is the row's own column name or a candidate list
 * resolved against the <b>target</b> ({@code ["--SEQ", "ASEQ"]}, Q3), and {@code keys=} splices a
 * bound name list exactly as the written one.
 */
class ReferencedRecordCountTest
{

    private static final String CALL = "referenced_record_count(RDOMAIN, IDVARVAL,"
            + " key_variable=IDVAR, keys=[USUBJID])";

    /** A WithInventory resolver keyed by member name, answering a domain's members by DOMAIN. */
    static final class Inventory implements DatasetResolver.WithInventory
    {

        private final Map<String, IDataTable> byName = new LinkedHashMap<>();

        Inventory(IDataTable... tables)
        {
            for (IDataTable t : tables)
            {
                byName.put(t.getMetaData().getName().toUpperCase(Locale.ROOT), t);
            }
        }


        @Override
        public @Nullable IDataTable resolve(String name)
        {
            return name == null ? null : byName.get(name.toUpperCase(Locale.ROOT));
        }


        @Override
        public Set<String> availableDatasets()
        {
            return byName.keySet();
        }
    }

    private static Rule load(String bindings, String check, String outputVariable)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString(
                    "{\"rules\":{\"X-1\":{" + "\"Core\":{\"Id\":\"X-1\"}," + "\"Bindings\":["
                            + bindings + "]," + "\"Check\":{\"expression\":\"" + check + "\"},"
                            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\""
                            + outputVariable + "\"]}}}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + check, e);
        }
    }


    private static IDataTable dv()
    {
        return RealTableFixture.of("DV").str("DOMAIN", "DV", "DV", "DV", "DV")
                .str("USUBJID", "A", "A", "B", "C").str("DVSPID", "1", "1", "2", "3")
                .lng("DVSEQ", 1L, 2L, 3L, 4L).build();
    }


    /**
     * The pointer rows: two DV records for A / DVSPID 1; one for B / 2; none for B / 9; DVREFID is
     * no DV column; DVSEQ is numeric ({@code "04"} is the token of 4); a blank and an unknown
     * dataset.
     */
    private static IDataTable suppdv()
    {
        return RealTableFixture.of("SUPPDV").str("SUPPSEQ", "1", "2", "3", "4", "5", "6", "7", "8")
                .str("RDOMAIN", "DV", "DV", "DV", "DV", "DV", "", "ZZ", "DV")
                .str("USUBJID", "A", "B", "B", "A", "C", "A", "A", "A").str("IDVAR", "DVSPID",
                        "DVSPID", "DVSPID", "DVREFID", "DVSEQ", "DVSPID", "DVSPID", "DVSPID")
                .str("IDVARVAL", "1", "2", "9", "x", "04", "1", "1", "").build();
    }


    private static List<@Nullable Object> rows(String call, EvaluationContext ctx)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v);
        List<@Nullable Object> out = new ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add(v.value(row).cell().isMissingOrInvalid() ? null
                    : ((Number) v.value(row).resolved()).longValue());
        }
        return out;
    }


    private static EvaluationContext ctx(IDataTable primary, IDataTable... others)
    {
        List<IDataTable> all = new ArrayList<>();
        all.add(primary);
        all.addAll(List.of(others));
        return EvaluationContext.builder().table(primary)
                .datasetResolver(new Inventory(all.toArray(IDataTable[]::new))).build();
    }


    @Test
    void everyMatchingRecordCountsAndTheAbsentShapesAnswerAsRuled()
    {
        List<@Nullable Object> counts = rows(CALL, ctx(suppdv(), dv()));
        assertEquals(2L, counts.get(0), "two DV records carry DVSPID 1 for A — never first-wins");
        assertEquals(1L, counts.get(1));
        assertEquals(0L, counts.get(2), "no DV record carries DVSPID 9");
        assertEquals(0L, counts.get(3), "DVREFID is no column of DV: nothing can match");
        assertEquals(1L, counts.get(4), "\"04\" is the join token of the numeric DVSEQ 4");
        assertNull(counts.get(5), "a blank dataset value answers missing");
        assertNull(counts.get(6), "an unknown dataset answers missing (Q4)");
        assertNull(counts.get(7), "a blank pointer value answers missing");
    }


    @Test
    void theKeysMustAgreeToo()
    {
        // B's pointer 1 names A's records only: the keys narrow the match
        IDataTable supp = RealTableFixture.of("SUPPDV").str("RDOMAIN", "DV").str("USUBJID", "B")
                .str("IDVAR", "DVSPID").str("IDVARVAL", "1").build();
        assertEquals(List.of(0L), rows(CALL, ctx(supp, dv())));
        // and without keys= the pointer alone is matched
        assertEquals(List.of(2L), rows(
                "referenced_record_count(RDOMAIN, IDVARVAL, key_variable=IDVAR)", ctx(supp, dv())));
    }


    @Test
    void aNumericTargetColumnComparesTheJoinTokenATextOneTheStrippedText()
    {
        IDataTable target = RealTableFixture.of("DV").str("DOMAIN", "DV", "DV")
                .str("USUBJID", "A", "A").dbl("DVNUM", 1.0, 2.5).str("DVTXT", "01", "2").build();
        IDataTable supp = RealTableFixture.of("SUPPDV").str("RDOMAIN", "DV", "DV", "DV", "DV", "DV")
                .str("USUBJID", "A", "A", "A", "A", "A")
                .str("IDVAR", "DVNUM", "DVNUM", "DVNUM", "DVTXT", "DVTXT")
                .str("IDVARVAL", "1.0", " 1E0 ", "1.5", "1", " 01 ").build();
        assertEquals(List.of(1L, 1L, 0L, 0L, 1L), rows(CALL, ctx(supp, target)),
                "numeric: 1.0 / 1E0 are the token 1, 1.5 is not; text: exact after stripping");
    }


    @Test
    void theCandidateListResolvesAgainstTheTargetAndTheFirstPresentWins()
    {
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE").str("USUBJID", "A", "A")
                .lng("AESEQ", 1L, 2L).build();
        IDataTable adlb = RealTableFixture.of("ADLB").str("USUBJID", "A").lng("ASEQ", 7L).build();
        IDataTable adae = RealTableFixture.of("ADAE").str("ASEQ", "1", "2", "3", "4")
                .str("USUBJID", "A", "A", "A", "A").str("SRCDOM", "AE", "AE", "ADLB", "AE")
                .str("SRCSEQ", "2", "3", "7", "").build();
        List<@Nullable Object> counts = rows(
                "referenced_record_count(SRCDOM, SRCSEQ,"
                        + " key_variable=[\"--SEQ\", \"ASEQ\"], keys=[USUBJID])",
                ctx(adae, ae, adlb));
        assertEquals(1L, counts.get(0), "--SEQ is AESEQ on AE");
        assertEquals(0L, counts.get(1));
        assertEquals(1L, counts.get(2), "ADLB has no --SEQ: ASEQ wins");
        assertNull(counts.get(3));
        // the sketch of row A: the broken pointer fires, the blank one does not
        Rule rule = load("",
                "not empty(SRCSEQ) and referenced_record_count(SRCDOM, SRCSEQ,"
                        + " key_variable=[\\\"--SEQ\\\", \\\"ASEQ\\\"], keys=[USUBJID]) == 0",
                "ASEQ");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, adae,
                new Inventory(adae, ae, adlb));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("2"),
                result.getViolations().stream().map(f -> f.getValues().get("ASEQ")).toList());
    }


    @Test
    void aSingleStringKeyVariableIsTheOneCandidateList()
    {
        // R5 P6-M1: key_variable="--SEQ" as ONE string literal is the one-element candidate list,
        // resolved against the TARGET as the list form is — it used to reach run time as the
        // column name "--SEQ" itself, so every row counted 0
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE").str("USUBJID", "A", "A")
                .lng("AESEQ", 1L, 2L).build();
        IDataTable adae = RealTableFixture.of("ADAE").str("USUBJID", "A", "A", "A")
                .str("SRCDOM", "AE", "AE", "AE").str("SRCSEQ", "2", "3", "").build();
        List<@Nullable Object> list = rows("referenced_record_count(SRCDOM, SRCSEQ,"
                + " key_variable=[\"--SEQ\"], keys=[USUBJID])", ctx(adae, ae));
        assertEquals(1L, list.get(0), "premise: the list form finds AESEQ 2");
        assertEquals(list, rows("referenced_record_count(SRCDOM, SRCSEQ,"
                + " key_variable=\"--SEQ\", keys=[USUBJID])", ctx(adae, ae)));
        assertEquals(list,
                rows("referenced_record_count(SRCDOM, SRCSEQ,"
                        + " key_variable=\"AESEQ\", keys=[USUBJID])", ctx(adae, ae)),
                "a plain name is its own one candidate");
    }


    @Test
    void aQualifiedKeyIsALoadError()
    {
        // R5 P6-L1: keys= names a column of BOTH the row's dataset and the referenced one; the
        // referenced side is a raw dataset with no join, so `J.USUBJID` never matched and the key
        // silently dropped
        Rule dotted = load("", "referenced_record_count(RDOMAIN, IDVARVAL, key_variable=IDVAR,"
                + " keys=[DM.USUBJID]) != 1", "SUPPSEQ");
        assertNotNull(dotted.getLoadError(), "a qualified key");
        assertTrue(dotted.getLoadError().contains("qualified"), dotted.getLoadError());
    }


    @Test
    void aSplitFamilySumsItsMembers()
    {
        IDataTable lbch = RealTableFixture.of("lbch").str("DOMAIN", "LB").str("USUBJID", "A")
                .lng("LBSEQ", 1L).build();
        IDataTable lbhe = RealTableFixture.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "A")
                .lng("LBSEQ", 1L).build();
        IDataTable adlb = RealTableFixture.of("ADLB").str("USUBJID", "A", "A")
                .str("SRCDOM", "LB", "LB").str("SRCSEQ", "1", "2").build();
        assertEquals(List.of(2L, 0L),
                rows("referenced_record_count(SRCDOM, SRCSEQ, key_variable=[\"--SEQ\"],"
                        + " keys=[USUBJID])", ctx(adlb, lbch, lbhe)),
                "LBSEQ 1 is carried by both members of LB");
    }


    @Test
    void theCorpusShapeOfRowFFiresPerPointerRow()
    {
        Rule rule = load("", "IDVAR in [\\\"DVREFID\\\", \\\"DVSPID\\\"] and "
                + CALL.replace("\"", "\\\"") + " != 1", "SUPPSEQ");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, suppdv(),
                new Inventory(suppdv(), dv()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<String> fired = result.getViolations().stream().map(f -> f.getValues().get("SUPPSEQ"))
                .toList();
        // register: `X != "Y"` on a missing value fires — the three missing answers (a blank
        // dataset, an unknown dataset, a blank pointer) fire too; a rule that must not report
        // them guards with not empty(...) (the row-A sketch's `== 0` is silent on a missing)
        assertEquals(List.of("1", "3", "4", "6", "7", "8"), fired,
                "two matches, none, an absent column and the missing answers fire; one match"
                        + " does not");
    }


    @Test
    void aBoundNameListAtKeysSplicesAsTheWrittenOne()
    {
        String inline = CALL.replace("\"", "\\\"") + " != 1";
        Rule written = load("", inline, "SUPPSEQ");
        Rule bound = load("{\"name\":\"$k\",\"expression\":\"[USUBJID]\"}",
                "referenced_record_count(RDOMAIN, IDVARVAL, key_variable=IDVAR, keys=$k) != 1",
                "SUPPSEQ");
        assertNull(bound.getLoadError(), bound.getLoadError());
        Inventory inventory = new Inventory(suppdv(), dv());
        List<String> a = RuleRunnerCalls.execute(written, suppdv(), inventory).getViolations()
                .stream().map(f -> f.getValues().get("SUPPSEQ")).toList();
        List<String> b = RuleRunnerCalls.execute(bound, suppdv(), inventory).getViolations()
                .stream().map(f -> f.getValues().get("SUPPSEQ")).toList();
        assertEquals(a, b);
        assertEquals(List.of("1", "3", "4", "6", "7", "8"), a);
    }


    @Test
    void theRefusedShapesAreLoadErrors()
    {
        Rule quoted = load("", "referenced_record_count(RDOMAIN, IDVARVAL, key_variable=IDVAR,"
                + " keys=[\\\"USUBJID\\\"]) != 1", "SUPPSEQ");
        assertNotNull(quoted.getLoadError(), "a quoted key is a string, never a column");
        assertTrue(quoted.getLoadError().contains("column"), quoted.getLoadError());
        Rule bareCandidates = load("", "referenced_record_count(RDOMAIN, IDVARVAL,"
                + " key_variable=[AESEQ, ASEQ], keys=[USUBJID]) != 1", "SUPPSEQ");
        assertNotNull(bareCandidates.getLoadError(),
                "the candidate templates are strings resolved against the target");
        assertTrue(bareCandidates.getLoadError().contains("string"), bareCandidates.getLoadError());
        Rule perRow = load("{\"name\":\"$p\",\"expression\":\"upper(IDVAR)\"}",
                "referenced_record_count(RDOMAIN, IDVARVAL, key_variable=IDVAR, keys=[$p]) != 1",
                "SUPPSEQ");
        assertNotNull(perRow.getLoadError(), "a per-row member cannot splice");
        assertTrue(perRow.getLoadError().contains("per-row"), perRow.getLoadError());
        Rule missingValue = load("", "referenced_record_count(RDOMAIN) != 1", "SUPPSEQ");
        assertNotNull(missingValue.getLoadError(), "value and key_variable are required");
    }
}
