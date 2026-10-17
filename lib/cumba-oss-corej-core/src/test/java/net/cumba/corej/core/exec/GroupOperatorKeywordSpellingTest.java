package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PACK I review fold, the H1 class closed for every operator: the compiles read their leading
 * operands from {@code c.args()} and the keyword parameters ({@code within=}, {@code by=},
 * {@code keys=}, …) from {@code c.kwargs()}, while stage A binds the authored call through the
 * descriptor — so a keyword spelling the binder accepts ({@code has_multiple_values_for(name=X,
 * key=K)}, {@code is_inconsistent_across_dataset(name=X, keys=[K])},
 * {@code empty_within_except_last_row(X, group=K, by=S)}) loaded clean with NO {@code checkExpr}
 * and ERRORed at every run. Since the fold {@code ExprCompiler.compileBoolCallPlan} normalises
 * every dispatched call ONCE to its bound canonical form ({@code canonicalCall}, the same
 * {@code ArgumentBinder} binding stage A uses): the spellings compile alike and give the same
 * verdict. A keyword parameter given by <em>position</em> stays what runbook W4 / W8 made it — a
 * documented refusal ("read by keyword only", "takes its sort key by keyword"; loud through a
 * compiled binding — {@code PositionalKeepMissingsTest}, {@code StrictColumnOperandTest}) — and is
 * not a spelling this test equates.
 */
class GroupOperatorKeywordSpellingTest
{

    private static Rule load(String check) throws Exception
    {
        String body = "{\"Core\":{\"Id\":\"GKS-1\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}";
        return RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + body + "}}").getRules()
                .get("x");
    }


    private static int violations(String check, IDataTable table) throws Exception
    {
        Rule r = load(check);
        assertNull(r.getLoadError(), check + ": " + r.getLoadError());
        assertNotNull(r.getCheckExpr(), check + " must install a checkExpr");
        RuleExecutionResult result = RuleRunnerCalls.execute(r, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                check + ": " + result.getStatusMessage());
        return result.getViolationCount();
    }

    // ------------------------------------------------------------------
    // the four operators the fold measured, one fixture each
    // ------------------------------------------------------------------


    /** USUBJID 1 reports TESTCD A with two different TESTs; USUBJID 2 is consistent. */
    private static IDataTable tests()
    {
        return MockTable.of().name("LB").col("USUBJID", "1", "1", "1", "2")
                .col("LBTESTCD", "A", "A", "B", "A").col("LBTEST", "x", "y", "z", "x").build();
    }


    @Test
    @DisplayName("has_multiple_values_for: positional, keyword and mixed spellings agree")
    void hasMultipleValuesFor() throws Exception
    {
        int positional = violations("has_multiple_values_for(LBTEST, LBTESTCD)", tests());
        assertTrue(positional > 0, "TEST has two values for TESTCD A");
        assertEquals(positional,
                violations("has_multiple_values_for(name=LBTEST, key=LBTESTCD)", tests()));
        assertEquals(positional,
                violations("has_multiple_values_for(LBTEST, key=LBTESTCD)", tests()));
        int within = violations("has_multiple_values_for(LBTEST, LBTESTCD, within=USUBJID)",
                tests());
        assertEquals(within, violations(
                "has_multiple_values_for(name=LBTEST, key=LBTESTCD, within=USUBJID)", tests()));
    }


    @Test
    @DisplayName("is_inconsistent_across_dataset: positional, keyword and mixed spellings agree")
    void isInconsistentAcrossDataset() throws Exception
    {
        int positional = violations("is_inconsistent_across_dataset(LBTEST, keys=[LBTESTCD])",
                tests());
        assertTrue(positional > 0, "TEST differs for TESTCD A");
        assertEquals(positional, violations(
                "is_inconsistent_across_dataset(name=LBTEST, keys=[LBTESTCD])", tests()));
        assertEquals(positional,
                violations("is_inconsistent_across_dataset(LBTEST, LBTESTCD)", tests()));
        assertEquals(positional,
                violations("is_inconsistent_across_dataset(name=LBTEST, key=LBTESTCD)", tests()));
    }


    /** USUBJID 1 has DSDECOD "DEATH" on two rows (of three); USUBJID 2 has two single values. */
    private static IDataTable ds()
    {
        return MockTable.of().name("DS").col("USUBJID", "1", "1", "1", "2", "2")
                .col("DSDECOD", "DEATH", "DEATH", "OTHER", "DEATH", "FOO").build();
    }


    @Test
    @DisplayName("present_on_multiple_rows_within: positional, keyword and mixed spellings agree")
    void presentOnMultipleRowsWithin() throws Exception
    {
        // the shipped spelling (CDISC-CG0063 / CG0068 / CG0200 / SEND-0357)
        int shipped = violations("present_on_multiple_rows_within(DSDECOD, within=USUBJID)", ds());
        assertEquals(2, shipped, "DEATH is on two rows of USUBJID 1");
        assertEquals(shipped,
                violations("present_on_multiple_rows_within(name=DSDECOD, within=USUBJID)", ds()));
        // the negated spelling binds the same way
        int negated = violations("not present_on_multiple_rows_within(DSDECOD, within=USUBJID)",
                ds());
        assertEquals(3, negated, "the three single values");
        assertEquals(negated, violations(
                "not present_on_multiple_rows_within(name=DSDECOD, within=USUBJID)", ds()));
    }


    /** USUBJID 1: the first element (by SESTDTC) has no SEENDTC — the last may. */
    private static IDataTable se()
    {
        return MockTable.of().name("SE").col("USUBJID", "1", "1", "2", "2")
                .col("SESTDTC", "2020-01-01", "2020-02-01", "2020-01-01", "2020-02-01")
                .col("SEENDTC", "", "", "2020-01-31", "").build();
    }


    @Test
    @DisplayName("empty_within_except_last_row: positional, keyword and mixed spellings agree")
    void emptyWithinExceptLastRow() throws Exception
    {
        // the shipped spelling (CDISC-CG0209)
        int shipped = violations("empty_within_except_last_row(SEENDTC, USUBJID, by=SESTDTC)",
                se());
        assertTrue(shipped > 0, "USUBJID 1's first element has no SEENDTC");
        assertEquals(shipped, violations(
                "empty_within_except_last_row(name=SEENDTC, group=USUBJID, by=SESTDTC)", se()));
        assertEquals(shipped, violations(
                "empty_within_except_last_row(SEENDTC, group=USUBJID, by=SESTDTC)", se()));
        // a positional by= stays the documented load error ("takes its sort key by keyword")
        assertNotNull(
                load("empty_within_except_last_row(SEENDTC, USUBJID, SESTDTC)").getLoadError());
    }

    // ------------------------------------------------------------------
    // the class, over the registry
    // ------------------------------------------------------------------

    /**
     * The parameters the compiles read by keyword only ({@code ExprCompiler.KEYWORD_PARAMETERS}).
     */
    private static final Set<String> KEYWORD_PARAMETERS = Set.of("within", "by", "keys", "regex",
            "keep_missings", "include_empty", "nulls");

    /**
     * The one clean / load-error split that is a documented grammar, not the silent class: the
     * unique-set readers take ONE positional list operand ({@code is_unique_set([TARGET, KEY,
     * …])}, the collapsed single-list grammar of 2026-08-23); the loader refuses {@code members=}
     * by keyword with its own message before the compile. A refused spelling is loud.
     */
    private static final Set<String> DOCUMENTED_REFUSALS = Set.of(
            "is_not_unique_set: {positional=clean, keyword=load error, mixed=clean}",
            "is_unique_set: {positional=clean, keyword=load error, mixed=clean}");

    /** A filler the binder and the compile both take for the parameter's type. */
    private static String filler(Parameter p)
    {
        if (p.nameSlot())
        {
            return "X";
        }
        return switch (p.type())
        {
        case ExprType.Primitive prim -> switch (prim)
        {
        case STRING -> "\"a\"";
        case NUMBER -> "1";
        case BOOLEAN -> "true";
        case DATE -> "\"2020-01-01\"";
        case TIME -> "\"10:00\"";
        case DURATION -> "\"P1D\"";
        case REGEX -> "/a/";
        case COLUMN_REFERENCE -> "X";
        case DATASET_REFERENCE -> "DM";
        case METADATA_LEVEL -> "\"DATA\"";
        };
        case ExprType.ListOf list -> list.element() instanceof ExprType.Primitive prim
                && prim == ExprType.Primitive.COLUMN_REFERENCE ? "[X]" : "[\"a\"]";
        case ExprType.Unknown _ -> "X";
        };
    }


    /**
     * {@code clean} (loads and compiles), {@code no-checkExpr} (loads, compiles nothing — the
     * defect class), or {@code load error} with the error's first line after a {@code |} (the
     * verdict compared is the part before it: two spellings refused by different findings are
     * refused alike).
     */
    private static String outcome(String check) throws Exception
    {
        Rule r = load(check);
        if (r.getLoadError() != null)
        {
            return "load error | " + r.getLoadError().split("\n", 2)[0];
        }
        return r.getCheckExpr() != null ? "clean" : "no-checkExpr";
    }


    private static String verdict(String outcome)
    {
        return outcome.split(" \\| ", 2)[0];
    }


    /**
     * Every boolean function with a non-collector parameter list: the positional spelling (the
     * operands by position, the keyword parameters by name — a keyword parameter by position is the
     * documented W4 / W8 refusal, not a spelling the binder accepts alike), the all-keyword
     * spelling and the mixed one (first operand positional, the rest by name) load and compile
     * alike — no spelling loads clean with no {@code checkExpr} while another compiles (before the
     * fold the keyword and mixed spellings of the group operators did), and a clean / load-error
     * split is one of the {@link #DOCUMENTED_REFUSALS}. Two spellings refused (by whichever load
     * error) are alike. ⚠ A function whose EVERY spelling compiles nothing is not a spelling
     * asymmetry and is not a row here: measured 2026-10-17, the un-negated {@code is_sorted_by(…)}
     * (only {@code not is_sorted_by(…)} has a plan — the positive form reaches the dispatch-drift
     * sentinel and the loader swallows the refusal) — the structural "a compile refusal of a Check
     * loads silently" finding reported with the fold, guarded by the corpus census of rules without
     * a {@code checkExpr}.
     */
    @Test
    @DisplayName("every boolean function judges its positional, keyword and mixed spelling alike")
    void everyBooleanFunctionCompilesEverySpellingAlike() throws Exception
    {
        List<String> rows = new ArrayList<>();
        int visited = 0;
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            List<Parameter> params = d.parameters();
            if (d.kind() != FunctionKind.BOOLEAN || params.isEmpty()
                    || params.get(params.size() - 1).collector())
            {
                continue;
            }
            visited++;
            List<String> positional = new ArrayList<>();
            List<String> keyword = new ArrayList<>();
            List<String> mixed = new ArrayList<>();
            boolean byName = false;
            for (int i = 0; i < params.size(); i++)
            {
                String f = filler(params.get(i));
                byName |= KEYWORD_PARAMETERS.contains(params.get(i).name());
                positional.add(byName ? params.get(i).name() + "=" + f : f);
                keyword.add(params.get(i).name() + "=" + f);
                mixed.add(i == 0 ? f : params.get(i).name() + "=" + f);
            }
            Map<String, String> outcomes = new LinkedHashMap<>();
            outcomes.put("positional",
                    outcome(d.name() + "(" + String.join(", ", positional) + ")"));
            outcomes.put("keyword", outcome(d.name() + "(" + String.join(", ", keyword) + ")"));
            outcomes.put("mixed", outcome(d.name() + "(" + String.join(", ", mixed) + ")"));
            Map<String, String> verdicts = new LinkedHashMap<>();
            outcomes.forEach((spelling, o) -> verdicts.put(spelling, verdict(o)));
            boolean silentBySpelling = verdicts.containsValue("no-checkExpr")
                    && verdicts.containsValue("clean");
            boolean split = verdicts.values().stream().distinct().count() > 1
                    && !verdicts.containsValue("no-checkExpr")
                    && !DOCUMENTED_REFUSALS.contains(d.name() + ": " + verdicts);
            if (silentBySpelling || split)
            {
                rows.add(d.name() + ": " + verdicts + "  <= " + outcomes);
            }
        }
        assertTrue(visited >= 20, "the registry was walked: " + visited);
        assertEquals(List.of(), rows,
                () -> "a function judges its spellings differently:\n" + String.join("\n", rows));
    }
}
