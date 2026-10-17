package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code SVY OQ6} / B5-Q1 (a) (owner 2026-10-16: <i>"agreed as proposed, the load error only is
 * ok"</i>): {@code upper} / {@code upcase} / {@code lower} / {@code lowcase} are <b>plain value
 * functions</b> everywhere. The former case-insensitive membership surface — {@code upper(X) in
 * […]} folding the <em>members</em> too — is gone: a member is matched as written, and a rule that
 * wants a case-insensitive membership folds both sides, {@code upper(X) in upper($list)}. The six
 * differences between the retired surface and the plain function are pinned here (B5 §6, C13), and
 * the load error that guards the literal lists: a fold probe compared by {@code in} /
 * {@code not in} / {@code ==} / {@code !=} with a literal not in the fold's case can never match.
 */
class FoldProbePlainFunctionTest
{

    private static BitSet eval(String expression, IDataTable t)
    {
        return eval(expression, t, Map.of());
    }


    private static BitSet eval(String expression, IDataTable t, Map<String, Object> variables)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(t).variables(new LinkedHashMap<>(variables))
                        .build());
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    private static Rule load(String check) throws java.io.IOException
    {
        String body = "{\"Core\":{\"Id\":\"FP-1\"},\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Bindings\":[{\"name\":\"$set\",\"expression\":\"[\\\"A\\\", \\\"B\\\"]\"}],"
                + "\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\"}}";
        return RulePackageLoader.loadFromString("{\"rules\":{\"FP-1\":" + body + "}}").getRules()
                .get("FP-1");
    }

    // ---------------------------------------------------------------- the plain function


    @Test
    @DisplayName("the members are matched as written: upper(X) in [\"a\"] matches nothing")
    void membersAreNotFolded()
    {
        IDataTable t = MockTable.of().col("X", "a", "A", "c").build();
        assertEquals(bits(), eval("upper(X) in [\"a\"]", t), "\"A\" is not \"a\"");
        assertEquals(bits(0, 1), eval("upper(X) in [\"A\"]", t));
        assertEquals(bits(0, 1), eval("upcase(X) in [\"A\"]", t), "upcase is upper's alias");
        assertEquals(bits(2), eval("upper(X) not in [\"A\"]", t));
        assertEquals(bits(0, 1), eval("lower(X) in [\"a\"]", t));
        assertEquals(bits(), eval("lowcase(X) in [\"A\"]", t));
        // the idiom: fold both sides
        Map<String, Object> v = Map.of("$set", List.of("a", "C"));
        assertEquals(bits(0, 1, 2), eval("upper(X) in upper($set)", t, v));
        assertEquals(bits(2), eval("upper(X) in $set", t, v),
                "an un-folded set matches as written: only \"C\" is a member");
    }


    @Test
    @DisplayName("(1) a Num-typed probe: upper over a number is its text, matched as text")
    void aNumericProbeColumnIsText()
    {
        // Under the surface the numeric-member arm was skipped for upper(...) (the flag guarded
        // it); the plain function yields a STRING vector, so a numeric list literal compares the
        // text form — the function's own answer, pinned.
        IDataTable t = MockTable.of().colLong("N", 10L, 25L, null).build();
        assertEquals(bits(0), eval("upper(N) in [\"10\"]", t));
        assertEquals(bits(0), eval("upper(N) == \"10\"", t));
    }


    @Test
    @DisplayName("a fold inside a list member is evaluated, not read as a probe")
    void aFoldInsideAListMemberIsEvaluated()
    {
        // B5 review ② L4: the member upper("abc") is the value "ABC" — it matches the upper-case
        // cell and not the lower-case one (the surface used to fold the members too, SVY OQ6)
        IDataTable cased = MockTable.of().col("X", "ABC", "abc").build();
        assertEquals(bits(0), eval("X in [upper(\"abc\")]", cased), "upper(\"abc\") is \"ABC\"");
        assertEquals(bits(1), eval("X not in [upper(\"abc\")]", cased));
    }


    @Test
    @DisplayName("(2) a missing-identity probe stays its missing through the fold")
    void aMissingIdentityProbeKeepsItsIdentity()
    {
        // upper(.A) is .A (D36) and upper("") is "" (D34 #1): a missing probe is a member only of
        // a set holding the same missing — never of "A" — and the blank is the present "".
        IDataTable t = MissingCellTables.strings("X", "a", MissingValue.MIS_A, "");
        assertEquals(bits(0), eval("upper(X) in [\"A\"]", t));
        assertEquals(bits(1, 2), eval("upper(X) not in [\"A\"]", t));
        assertEquals(bits(2), eval("upper(X) in [\"\"]", t), "the blank folds to the present \"\"");
        assertEquals(bits(1), eval("empty(upper(X)) and upper(X) not in [\"\"]", t),
                "the .A row is missing, not the present \"\"");
    }


    @Test
    @DisplayName("(3) a list-valued probe: any folded element a member (D81d)")
    void aListValuedProbeIsElementWise()
    {
        IDataTable t = MockTable.of().col("A", "x", "y").col("B", "b", "q").build();
        assertEquals(bits(0), eval("upper(values([A, B])) in [\"B\"]", t));
        assertEquals(bits(), eval("upper(values([A, B])) in [\"b\"]", t), "members as written");
    }


    @Test
    @DisplayName("(4) numeric and missing elements inside upper($set) keep their classification")
    void numericAndMissingElementsInAFoldedSet()
    {
        IDataTable t = MissingCellTables.strings("X", "7", "a", MissingValue.MIS);
        Map<String, Object> v = Map.of("$set", List.of(7L, "A", MissingValue.MIS));
        // upper lifts element-wise: a number's text, a folded string, a missing kept as a missing
        assertEquals(bits(0, 1, 2), eval("upper(X) in upper($set)", t, v));
        assertEquals(bits(0, 1), eval("upper(X) in upper($set) and not empty(X)", t, v));
    }


    @Test
    @DisplayName("(5) an absent-column probe folds to \"\" and decides")
    void anAbsentColumnProbeDecides()
    {
        IDataTable t = MockTable.of().name("AE").col("X", "a", "b").build();
        assertEquals(bits(0, 1), eval("upper(NOPE) in [\"\"]", t), "the absent default is \"\"");
        assertEquals(bits(), eval("upper(NOPE) in [\"A\"]", t));
    }


    @Test
    @DisplayName("(6) the column-type gate does not see a folded probe (a computed value)")
    void theGateDoesNotReachAFoldedProbe()
    {
        // upper(N) is a ComputedVector without a gated name: a Num column folded and compared with
        // a string list is NOT the R9 ERROR a bare `N in ["10"]` is — pinned as intended: the fold
        // is an explicit text reading, as str() is.
        IDataTable t = MockTable.of().colLong("N", 10L, 25L).build();
        assertThrows(ColumnTypeMismatchException.class, () -> eval("N in [\"10\"]", t));
        assertEquals(bits(0), eval("upper(N) in [\"10\"]", t));
    }

    // ---------------------------------------------------------------- the load error


    @Test
    @DisplayName("a fold probe against a literal not in that case is a load error")
    void aLiteralNotInTheFoldsCaseIsALoadError() throws Exception
    {
        for (String check : List.of("upper(X) in [\"a\"]", "upcase(X) not in [\"A\", \"b\"]",
                "upper(X) == \"Mild\"", "\"mild\" != upper(X)", "lower(X) in [\"A\"]",
                "lowcase(X) == \"Mild\"", "upper(X) in [\"ß\"]"))
        {
            Rule r = load(check);
            assertNotNull(r.getLoadError(), check + " must fail to load");
            assertTrue(r.getLoadError().contains("can never match"),
                    check + ": " + r.getLoadError());
        }
    }


    @Test
    @DisplayName("literals in the fold's case, with no cased character, or non-literal members load")
    void matchingLiteralsAndNonLiteralMembersLoad() throws Exception
    {
        for (String check : List.of("upper(X) in [\"A\", \"B C\"]", "upper(X) == \"MILD\"",
                "upper(X) not in [\"1\", \"-\", \"\"]", "lower(X) in [\"a\"]", "upper(X) in $set",
                "upper(X) in upper($set)", "upper(X) != upper(Y)", "upper(X) in [upper(\"a\")]",
                "X in [\"a\"]", "upper(X) in [\"10\"]",
                // PI-C3 (PACK I E2): the membership over a computed list is supported as written
                "upper(value()) in upper(var_codelist_coded_values(\"LIBRARY\"))"))
        {
            Rule r = load(check);
            assertNull(r.getLoadError(), check + ": " + r.getLoadError());
        }
    }
}
