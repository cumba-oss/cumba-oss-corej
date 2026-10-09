package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code values(list<column-reference>)} — the per-row list of the named columns' cells
 * ({@code PLAN-elementwise-list-functions} §3.2 / §3.7, owner Q14; runbook W1a unit E1).
 *
 * <p>
 * Every check runs under a {@code not empty(USUBJID)} guard so the rule stays RECORD level and the
 * fired rows can be compared row by row (as {@code DynamicColumnFunctionsTest} does). The primary
 * is named {@code AE}, so the bind-time specialiser resolves a {@code --} name.
 * </p>
 */
class ValuesFunctionTest
{

    // ------------------------------------------------------------------ fixtures

    /**
     * The primary: three rows. {@code A} and {@code B} carry the probed texts (row 2 holds
     * {@code "y"} only in {@code B}, row 3 holds {@code "c"} in both), {@code A} is missing on row
     * 2 for the identity case; {@code AETERM} answers the {@code --TERM} spelling; {@code NUM} is
     * numeric.
     */
    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1", "S1", "S2").str("A", "a", null, "c")
                .str("B", "b", "y", "c").str("AETERM", "t1", "t2", "t3").lng("NUM", 1L, 2L, 3L)
                .build();
    }


    /** ADSL: S1 only (S2 is unmatched), one arm column. */
    private static IDataTable adsl()
    {
        return RealTables.of("ADSL").str("USUBJID", "S1").str("ARM", "PLACEBO").build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, List<String> outputs, boolean joinAdsl,
            String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-VALUES\"},\"Sensitivity\":\"Record\"");
        if (joinAdsl)
        {
            b.append(",\"Match_Datasets\":[{\"Name\":\"ADSL\",\"Keys\":[\"USUBJID\"],")
                    .append("\"Join_Type\":\"left\"}]");
        }
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
        b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[");
        for (int i = 0; i < outputs.size(); i++)
        {
            b.append(i == 0 ? "" : ",").append('"').append(esc(outputs.get(i))).append('"');
        }
        return b.append("]}}").toString();
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            Rule r = RulePackageLoader
                    .loadFromString(
                            KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + ruleJson + "}}"))
                    .getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static RuleExecutionResult run(IDataTable primary, String ruleJson)
    {
        Rule r = load(ruleJson);
        assertNull(r.getLoadError(), r.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary, adsl()), primary.getMetaData().getName(), null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> ruleJson + " → " + result.getStatusMessage());
        return result;
    }


    /** The fired rows (1-based) of a row-guarded {@code check} on {@link #ae()}. */
    private static List<Long> fired(String check, String... bindings)
    {
        return firedOn(ae(), check, false, bindings);
    }


    private static List<Long> firedOn(IDataTable primary, String check, boolean joinAdsl,
            String... bindings)
    {
        RuleExecutionResult result = run(primary, json("not empty(USUBJID) and (" + check + ")",
                List.of("USUBJID"), joinAdsl, bindings));
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String loadError(String check, String... bindings)
    {
        return String
                .valueOf(load(json(check, List.of("USUBJID"), false, bindings)).getLoadError());
    }


    /** What {@code $v} renders on every row, as the report renders the binding. */
    private static List<String> rendered(String binding)
    {
        RuleExecutionResult result = run(ae(),
                json("not empty(USUBJID)", List.of("$v"), false, "$v", binding));
        List<String> out = new ArrayList<>();
        result.getViolations().forEach(v -> out.add(v.getValues().get("$v")));
        return out;
    }

    // ------------------------------------------------------------------ the value


    @Test
    void theListHoldsTheNamedColumnsCellsPerRowInInputOrder()
    {
        // row 2: "y" is in B only; row 3: "c" is in both — one membership per row, never row 0's
        // list broadcast (A: a / «missing» / c, B: b / y / c)
        assertEquals(List.of(2L), fired("\"y\" in values([A, B])"));
        assertEquals(List.of(3L), fired("\"c\" in values([A, B])"));
        assertEquals(List.of(1L), fired("\"a\" in values([A, B])"));
        // the rendered list keeps the input order, B before A when written so; the members are
        // carried exactly as colref(list) carries them (a missing member renders as its missing)
        assertEquals(List.of("[a, b]", "[., y]", "[c, c]"), rendered("values([A, B])"));
        assertEquals(List.of("[b, a]", "[y, .]", "[c, c]"), rendered("values([B, A])"));
        assertEquals(rendered("colref([\"A\", \"B\"])"), rendered("values([A, B])"));
    }


    @Test
    void aMissingMemberIsKeptAtItsPositionAndTheListKeepsItsLength()
    {
        // row 2's A is missing: the list is still two members long (count is the cardinality),
        // and B's "y" is still found beside the missing member
        assertEquals(List.of(1L, 2L, 3L), fired("count(values([A, B])) == 2"));
        assertEquals(List.of(2L), fired("\"y\" in values([A, B])"));
    }


    @Test
    void anAbsentColumnContributesItsDefault()
    {
        // ZZ is not a column of AE: in a character position it reads "" on every row (D34 #3),
        // so the list is still two members long and "" is a member everywhere
        assertEquals(List.of(1L, 2L, 3L), fired("count(values([A, ZZ])) == 2"));
        assertEquals(List.of(1L, 2L, 3L), fired("\"\" in values([A, ZZ])"));
    }


    @Test
    void aPrefixNameAndAQualifiedNameReadAsTheAuthoredNamesDo()
    {
        // --TERM is specialised to AETERM (the primary is AE)
        assertEquals(List.of(2L), fired("\"t2\" in values([--TERM, B])"));
        // ADSL.ARM reads through the join: S1's rows see PLACEBO, S2 is unmatched
        assertEquals(List.of(1L, 2L), firedOn(ae(), "\"PLACEBO\" in values([ADSL.ARM, B])", true));
    }


    @Test
    void aNumericMemberKeepsItsCellsType()
    {
        // NUM is a LONG column: its cell is carried typed (never rendered text), so 2 matches on
        // the row holding 2 only
        assertEquals(List.of(2L), fired("2 in values([NUM, A])"));
        assertEquals(List.of("[1, a]", "[2, .]", "[3, c]"), rendered("values([NUM, A])"));
        assertEquals(rendered("colref([\"NUM\", \"A\"])"), rendered("values([NUM, A])"));
    }

    // ------------------------------------------------------------------ membership shapes


    @Test
    void theListIsAMembershipSetOnEitherSideOfIn()
    {
        // on the right: v in values([…]) is a per-row set; on the left: any member in the set
        assertEquals(List.of(2L), fired("\"y\" in values([A, B])"));
        assertEquals(List.of(1L, 2L), fired("values([A, B]) in [\"y\", \"a\"]"));
        assertEquals(List.of(1L, 3L), fired("\"y\" not in values([A, B])"));
    }


    @Test
    void aListAgainstAScalarIsRefusedAtLoad()
    {
        // Q8: lifted-list == scalar is PARAMETER_TYPE territory — stage A's comparison refuses a
        // list against a scalar
        String error = loadError("values([A, B]) == \"a\"");
        assertTrue(error.contains("list"), error);
    }

    // ------------------------------------------------------------------ the spliced spelling


    @Test
    void aBoundOrComputedListOfNamesSplicesLikeTheWrittenList()
    {
        // G1 / C4 / C5: a $ binding holding the names, the expression it holds written in place,
        // the bare operand (values($k) ≡ values([$k])) and a mixed list all read as values([A, B])
        String names = "minus([\"A\", \"B\", \"X\"], subtract=[\"X\"])";
        List<Long> written = fired("\"y\" in values([A, B])");
        assertEquals(List.of(2L), written);
        assertEquals(written, fired("\"y\" in values($k)", "$k", names));
        assertEquals(written, fired("\"y\" in values([$k])", "$k", names));
        assertEquals(written, fired("\"y\" in values(" + names + ")"));
        assertEquals(written, fired("\"y\" in values([" + names + "])"));
        assertEquals(written, fired("\"y\" in values([A, $b])", "$b", "upper(\"b\")"));
        assertEquals(written, fired("\"y\" in values([A, upper(\"b\")])"));
        // a string-literal list binding is no list of names (runbook W4 E3, D1-B): the load
        // error names the remedy, the bare names
        String strings = loadError("\"y\" in values($k)", "$k", "[\"A\", \"B\"]");
        assertTrue(strings.contains("write the columns as names: [A, B]"), strings);
        assertEquals(written, fired("\"y\" in values($k)", "$k", "[A, B]"));
    }


    @Test
    void aPerRowMemberCannotBeSplicedBoundOrInline()
    {
        // the level judgement of a splice member, one sentence for both spellings (BS2)
        String bound = loadError("\"y\" in values([A, $p])", "$p", "upper(AETERM)");
        String inline = loadError("\"y\" in values([A, upper(AETERM)])");
        assertTrue(bound.contains("cannot splice the per-row member"), bound);
        assertTrue(bound.contains("values"), bound);
        assertEquals(bound.replace("$p", "upper(AETERM)"), inline);
        // and the bare operand, C5
        String bare = loadError("\"y\" in values($p)", "$p", "upper(AETERM)");
        assertTrue(bare.contains("cannot splice the per-row member"), bare);
    }


    @Test
    void valuesItselfIsAPerRowMemberOfAnotherSpliceSlot()
    {
        // lane 5's reading of §3.2 / §3.7: values(…) is a row reader, so as a MEMBER of another
        // splice list it is the per-row member the G3 arm refuses — never a splice of names
        // (since W1b UC1 is_(not_)unique_set admits a per-row VALUE member, so there the
        // refusal is the list one: a list is not a key component)
        String error = loadError("not is_unique_set([USUBJID, values([A])])");
        assertTrue(error.contains("a list is not a key component"), error);
        assertTrue(error.contains("values([A])"), error);
        String group = loadError("record_count(group=[USUBJID, values([A, B])]) > 1");
        assertTrue(group.contains("cannot splice the per-row member"), group);
    }

    // ------------------------------------------------------------------ the level (readingRows)


    @Test
    void aSplicedValuesBindingIsPerRowNeverRowZeroBroadcast()
    {
        // values($k) has no column operand the level calculus can see: without the row-reader flag
        // the binding would be DATASET level and hand row 0's list [a, b] to every row — so "y"
        // (row 2's B) would never be found. The planted control (drop .readingRows()) reds here.
        String names = "minus([\"A\", \"B\", \"X\"], subtract=[\"X\"])";
        assertEquals(List.of(2L), fired("\"y\" in $v", "$k", names, "$v", "values($k)"));
        assertEquals(List.of(3L), fired("\"c\" in $v", "$k", names, "$v", "values($k)"));
    }


    @Test
    void aZeroRowTableAnswersNothingWithoutAnError()
    {
        IDataTable empty = RealTables.of("AE").str("USUBJID").str("A").str("B").build();
        String names = "minus([\"A\", \"B\", \"X\"], subtract=[\"X\"])";
        assertEquals(List.of(),
                firedOn(empty, "\"y\" in $v", false, "$k", names, "$v", "values($k)"));
        assertEquals(List.of(), firedOn(empty, "\"y\" in values([A, B])", false));
    }

    // ------------------------------------------------------------------ refusals


    @Test
    void anythingButAListOfColumnNamesIsALoadError()
    {
        // a quoted name is a string, never a column (stage A PARAMETER_TYPE at the element)
        String quoted = loadError("\"y\" in values([\"A\", B])");
        assertTrue(quoted.contains("list<column-reference>"), quoted);
        // a bare column is not a list — the operand must be a list literal (or a spliced list)
        String bare = loadError("\"y\" in values(A)");
        assertTrue(bare.contains("values"), bare);
        // an empty list names no columns
        String empty = loadError("\"y\" in values([])");
        assertTrue(empty.contains("values"), empty);
        // arity
        String none = loadError("\"y\" in values()");
        assertTrue(none.contains("values"), none);
        String two = loadError("\"y\" in values([A], [B])");
        assertTrue(two.contains("values"), two);
        // a nested list is not a column reference
        String nested = loadError("\"y\" in values([A, [B]])");
        assertTrue(nested.contains("values"), nested);
    }
}
