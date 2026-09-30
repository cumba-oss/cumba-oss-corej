package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.ColumnVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} phase 2 — {@code str}, {@code num}, {@code printf},
 * {@code lpad} and the scalar {@code colref} as ordinary functions, pinned END TO END: every rule
 * here is loaded through {@link RulePackageLoader} (so the load seams run) and executed through the
 * rule runner (so the context carries the rule's own type expectations, joins and Child pre-merge
 * exactly as in production). Each {@code colref} case is asserted against the <b>authored</b>
 * spelling of the same name on the same tables — the uniformity the plan rests on ({@code D72},
 * {@code NF §3a}, {@code UVC unqualified}).
 */
class DynamicColumnFunctionsTest
{

    // ------------------------------------------------------------------ fixtures

    /** The primary: two subjects, a numeric period driver, numeric period dates. */
    private static IDataTable adae()
    {
        return RealTables.of("ADAE").str("USUBJID", "S1", "S1", "S2").lng("APERIOD", 1L, 2L, 1L)
                .dbl("APERSDT", 100.0, 200.0, 300.0).str("BLANK", "", "", "")
                .str("NAMECOL", "APERSDT", null, "APERSDT").lng("NUMCOL", 1L, 2L, 3L)
                .lng("LONGCOL", 1L, 1L, 1L).str("TXT", "1.0", "1.0", "1.0").build();
    }


    /** ADSL: S1 only (S2 is unmatched), two period dates. */
    private static IDataTable adsl()
    {
        return RealTables.of("ADSL").str("USUBJID", "S1").dbl("AP01SDT", 100.0)
                .dbl("AP02SDT", 999.0).str("ONLYJOIN", "v").build();
    }


    private static String rule(String check, boolean joinAdsl, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-DCF\"},\"Sensitivity\":\"Record\"");
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
        return b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}")
                .toString();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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


    /** The fired rows (1-based) of {@code check}, asserting the rule loaded and executed. */
    private static List<Long> fired(String check, boolean joinAdsl, String... bindings)
    {
        return firedOn(adae(), check, joinAdsl, bindings);
    }


    /**
     * The fired rows (1-based) of {@code check} on {@code primary}, asserting the rule loaded and
     * executed. The check runs under a {@code not empty(USUBJID)} guard so it stays RECORD level: a
     * check the engine can fold to one dataset-level verdict (an absent-column comparison, a
     * literal-only call) would otherwise report once per dataset, and the dynamic and authored
     * spellings could not be compared row by row. The primary's name is the domain code, so the
     * bind-time specialisation runs (a {@code --} name resolves).
     */
    private static List<Long> firedOn(IDataTable primary, String check, boolean joinAdsl,
            String... bindings)
    {
        String guarded = "not empty(USUBJID) and (" + check + ")";
        Rule r = load(rule(guarded, joinAdsl, bindings));
        assertNull(r.getLoadError(), () -> check + " → load error " + r.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary, adsl()), primary.getMetaData().getName(), null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " → " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static void assertSameAsAuthored(String dynamic, String authored, boolean joinAdsl,
            List<Long> expected)
    {
        assertEquals(expected, fired(authored, joinAdsl), "authored: " + authored);
        assertEquals(expected, fired(dynamic, joinAdsl),
                "colref must answer as the authored form: " + dynamic + " vs " + authored);
    }


    private static String loadError(String check, boolean joinAdsl)
    {
        String error = load(rule(check, joinAdsl)).getLoadError();
        assertNotNull(error, () -> check + " must be a load error");
        return error;
    }

    // ------------------------------------------------------------------ str (§2.1, Q10)


    /** A table whose X column spans the rendering matrix and whose EXP column states str(X). */
    private static IDataTable renderingMatrix()
    {
        return RealTables.of("DM").str("USUBJID", "a", "b", "c", "d", "e", "f", "g")
                .dbl("X", 1.0, 10.0, 1.5, 12345678.5, 0.1 + 0.2, MissingValue.MIS_A.asDouble(), 0.0)
                .str("EXP", "1", "10", "1.5", "12345678.5", "0.3", "", "0")
                .lng("BIG", 9007199254740993L, 1L, 1L, 1L, 1L, 1L, 1L)
                .str("S", "abc", "abc", "abc", "abc", "abc", "abc", "abc").build();
    }


    @Test
    void strRendersTheEnginesOneNumberText()
    {
        IDataTable t = renderingMatrix();
        assertEquals(List.of(), firedOn(t, "not is_missing(X) and str(X) != EXP", false),
                "no .0, never scientific, 12345678.5 verbatim, the cleaned text of a noisy double");
        assertEquals(List.of(6L), firedOn(t, "is_missing(str(X))", false),
                "a missing value in gives that missing value out (D36, D85c)");
        assertEquals(List.of(6L), firedOn(t, "str(X) == X and is_missing(X)", false),
                "the missing keeps its identity: .A == .A (D34 #5-1)");
        assertEquals(List.of(1L), firedOn(t, "str(BIG) == \"9007199254740993\"", false),
                "a LONG above 2^53 is exact");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), firedOn(t, "str(S) == \"abc\"", false));
    }


    /**
     * ⭐ Q8 / Q10 — {@code str(A) == str(B)} as ordinary string equality of two converted values
     * answers EXACTLY as the retired {@code STR_MARKER} did. The oracle is the marker's own
     * computation, {@code Primitives.equality(…, typeInsensitive = true …)} over the raw operands,
     * for every pair of the matrix's typed columns, both polarities.
     */
    @Test
    void strEqualityAnswersAsTheRetiredMarkerOnTheMatrix()
    {
        IDataTable t = RealTables.of("DM").str("USUBJID", "a", "b", "c", "d", "e", "f", "g", "h")
                .dbl("D", 1.0, 10.0, 1.5, 12345678.5, 0.1 + 0.2, MissingValue.MIS_A.asDouble(), 0.0,
                        3.0)
                .lng("L", 1L, 10L, 2L, 12345678L, 0L, null, 0L, 9007199254740993L)
                .str("C", "1", "10", "1.5", "12345678.5", "0.3", "", "0", "x")
                .str("B", "", "", "", "", "", "", "", "")
                .dbl("M", MissingValue.MIS_A.asDouble(), MissingValue.MIS_A.asDouble(), 1.0, 1.0,
                        MissingValue.MIS_B.asDouble(), MissingValue.MIS_A.asDouble(), 0.0, 3.0)
                .build();
        List<String> cols = List.of("D", "L", "C", "B", "M", "ABSENT");
        for (String a : cols)
        {
            for (String b : cols)
            {
                for (String op : List.of("==", "!="))
                {
                    String check = "str(" + a + ") " + op + " str(" + b + ")";
                    BitSet oracle = Primitives.equality(operand(t, a), operand(t, b),
                            (int) t.getRowCount(), "!=".equals(op), false, true, false);
                    List<Long> expected = new ArrayList<>();
                    oracle.stream().forEach(i -> expected.add(i + 1L));
                    assertEquals(expected, firedOn(t, check, false), check);
                }
            }
        }
    }


    private static Vector operand(IDataTable t, String name)
    {
        int idx = t.getMetaData().getColumnIndex(name);
        return idx < 0 ? ConstVector.of("")
                : new ColumnVector(name, t.getColumn(idx),
                        t.getMetaData().getColumn(idx).getType());
    }


    @Test
    void numStaysTheNumericConversion()
    {
        IDataTable t = RealTables.of("DM").str("USUBJID", "a", "b").str("C", "1.50", "x").build();
        assertEquals(List.of(1L), firedOn(t, "num(C) == 1.5", false));
        assertEquals(List.of(2L), firedOn(t, "is_missing(num(C))", false),
                "a cell that will not convert is missing (D35)");
    }

    // ------------------------------------------------------------------ printf / lpad (§2.2)


    @Test
    void printfFormatsUnderLocaleRootWithoutTruncation()
    {
        IDataTable t = RealTables.of("DM").str("USUBJID", "a", "b", "c", "d")
                .dbl("X", 1.0, 12.0, 1.5, MissingValue.MIS_A.asDouble())
                .str("C", "3", "3", "3", "3").str("EXP", "01", "12", "", "").build();
        assertEquals(List.of(), firedOn(t,
                "not is_missing(X) and X != 1.5 and printf(\"%02d\", X)" + " != EXP", false));
        assertEquals(List.of(3L, 4L), firedOn(t, "is_missing(printf(\"%d\", X))", false),
                "1.5 is not integral (computed missing, never (long) d); .A propagates");
        assertEquals(List.of(1L, 2L, 4L), firedOn(t, "printf(\"%d\", X) == X", false),
                "\"1\" == 1.0 and \"12\" == 12.0 as text; the missing argument's own identity is"
                        + " the result (.A == .A)");
        assertEquals(List.of(1L, 2L, 3L, 4L), firedOn(t, "is_missing(printf(\"%d\", C))", false),
                "a present non-number at %d is a computed missing");
        assertEquals(List.of(3L), firedOn(t, "printf(\"P%sS\", X) == \"P1.5S\"", false),
                "%s renders through str");
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try
        {
            assertEquals(List.of(3L), firedOn(t, "printf(\"%.1f\", X) == \"1.5\"", false),
                    "Locale.ROOT under a German default locale: 1.5, never 1,5");
        }
        finally
        {
            Locale.setDefault(saved);
        }
    }


    @Test
    void printfStaticDefectsAreLoadErrors()
    {
        assertTrue(loadError("printf(USUBJID, 1) == \"x\"", false).contains("string literal"));
        assertTrue(loadError("printf(\"%n\") == \"x\"", false).contains("unsupported conversion"));
        assertTrue(loadError("printf(\"%tY\", 1) == \"x\"", false).contains("unsupported"));
        assertTrue(loadError("printf(\"%1$s\", 1) == \"x\"", false).contains("unsupported"));
        assertTrue(loadError("printf(\"%d %d\", 1) == \"x\"", false).contains("argument"));
        assertTrue(loadError("printf(\"%d\", \"a\") == \"x\"", false).contains("string literal"));
        assertTrue(loadError("printf(\"%-05d\", 1) == \"x\"", false).contains("not valid"));
    }


    @Test
    void lpadPadsAndNeverTruncates()
    {
        IDataTable t = RealTables.of("DM").str("USUBJID", "a", "b", "c")
                .str("V", "7", "12345", null).build();
        assertEquals(List.of(1L), firedOn(t, "lpad(V, 3, \"0\") == \"007\"", false));
        assertEquals(List.of(2L), firedOn(t, "lpad(V, 3, \"0\") == \"12345\"", false),
                "a longer value is returned unchanged");
        assertEquals(List.of(1L), firedOn(t, "lpad(V, 0, \"0\") == \"7\"", false));
        assertEquals(List.of(1L), firedOn(t, "lpad(V, 3) == \"  7\"", false),
                "fill defaults to a space");
        assertEquals(List.of(1L, 2L, 3L), firedOn(t, "lpad(\"\", 3, \"x\") == \"xxx\"", false));
        assertEquals(List.of(3L), firedOn(t, "is_missing(lpad(V, 3, \"0\"))", false),
                "a missing value keeps its identity");
    }

    // ------------------------------------------------------------------ colref (§2.3)


    @Test
    void aDottedNameReadsTheJoinAsTheAuthoredFormDoes()
    {
        assertSameAsAuthored("APERSDT != colref(\"ADSL.AP01SDT\")", "APERSDT != ADSL.AP01SDT", true,
                List.of(2L, 3L));
        assertSameAsAuthored(
                "APERSDT != colref(concat(\"ADSL.AP\", printf(\"%02d\", APERIOD), \"SDT\"))",
                "APERSDT != ADSL.AP${APERIOD:%02d}SDT", true, List.of(2L, 3L));
    }


    @Test
    void aBareNameReadsThePrimaryNeverARegularJoin()
    {
        assertSameAsAuthored("colref(\"APERSDT\") != APERSDT", "APERSDT != APERSDT", false,
                List.of());
        assertSameAsAuthored("colref(\"ONLYJOIN\") == \"v\"", "ONLYJOIN == \"v\"", true, List.of());
        assertEquals(List.of(), fired("colref(\"apersdt\") != APERSDT", false),
                "a column name matches case-insensitively (CIT §1)");
    }


    /** ⭐ Q14 (b): an absent column takes the default the AUTHORED name takes at that position. */
    @Test
    void anAbsentColumnTakesTheAuthoredDefaultAtItsPosition()
    {
        assertSameAsAuthored("colref(\"ZZ\") < 3", "ZZ < 3", false, List.of(1L, 2L, 3L));
        assertSameAsAuthored("colref(\"ADSL.ZZ\") < 3", "ADSL.ZZ < 3", true, List.of(1L, 2L, 3L));
        // `X != ""` tells MIS (fires: a missing is not the empty string, D12) from "" (does not).
        // ⚠ is_missing cannot: it is empty()'s alias today (register D50, enforcement unbuilt).
        assertSameAsAuthored("colref(\"ZZ\") == 3 or colref(\"ZZ\") != \"\"",
                "ZZ == 3 or ZZ != \"\"", false, List.of(1L, 2L, 3L));
        assertSameAsAuthored("colref(\"ZZ\") == \"Y\" or colref(\"ZZ\") != \"\"",
                "ZZ == \"Y\" or ZZ != \"\"", false, List.of());
        assertSameAsAuthored("colref(\"ZZ\") != BLANK", "ZZ != BLANK", false, List.of());
        assertSameAsAuthored("colref(\"ZZ\") != \"\" and abs(ZZ) != 5",
                "ZZ != \"\" and abs(ZZ) != 5", false, List.of(1L, 2L, 3L));
    }


    @Test
    void anAbsentColumnInAFilterSubContextTakesTheAuthoredDefault()
    {
        String authored = fired("$m == 300", false, "$m",
                "max(APERSDT, group=[USUBJID], filter=(ZZ < 3))").toString();
        String dynamic = fired("$m == 300", false, "$m",
                "max(APERSDT, group=[USUBJID], filter=(colref(\"ZZ\") < 3))").toString();
        assertEquals("[3]", authored, "MIS < 3 keeps every row: S2's max is 300");
        assertEquals(authored, dynamic);
    }


    /** The known limit (phase 0 D3): a $-reference carries no position kind, for either form. */
    @Test
    void aBindingCarriesNoPositionKindForEitherForm()
    {
        assertEquals(List.of(), fired("$x < 3", false, "$x", "ZZ"));
        assertEquals(List.of(), fired("$x < 3", false, "$x", "colref(\"ZZ\")"));
    }


    @Test
    void theCellKeepsItsColumnsType()
    {
        assertEquals(List.of(), fired("colref(\"LONGCOL\") != TXT", false),
                "a LONG cell compares numerically with \"1.0\" (a text read would say \"1\" != \"1.0\")");
    }


    @Test
    void aMissingOrNonStringFirstHopNamesNoColumn()
    {
        assertEquals(List.of(2L), fired("is_missing(colref(NAMECOL))", false),
                "a missing first hop is that missing");
        assertEquals(List.of(1L, 2L, 3L), fired("colref(NUMCOL) != \"\"", false),
                "Q7: a present non-string value names no column — a computed missing");
    }


    @Test
    void aLiteralDashNameIsSpecialised()
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1", "S1").lng("AESEQ", 1L, 2L).build();
        assertEquals(List.of(), firedOn(ae, "colref(\"--SEQ\") != AESEQ", false));
    }
    // ------------------------------------------------------------------ the IDVAR regression


    private static List<Long> cg0371On(IDataTable suppae, IDataTable ae)
    {
        String check = "not empty(IDVAR) and not empty(IDVARVAL)"
                + " and str(IDVARVAL) != str(colref(IDVAR))";
        String json = "{\"Core\":{\"Id\":\"CDISC-CG0371\"},\"Sensitivity\":\"Record\","
                + "\"Match_Datasets\":[{\"Name\":\"SUPP--\",\"Child\":true,\"Keys\":"
                + "[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}],\"Check\":{\"expression\":\"" + esc(check)
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"IDVAR\",\"IDVARVAL\"]}}";
        Rule r = load(json);
        assertNull(r.getLoadError(), r.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(r, suppae,
                RealTables.inventoryOf(suppae, ae), "SUPPAE", null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /**
     * §2.3 — the three shipped {@code colref(IDVAR)} rules (CG0371 / FDA- / PMDA-SD0077) change
     * from a TEXT read to a TYPED cell, wrapped in {@code str(…)}, which is itself now a registered
     * conversion. The sensitivity scenario the findings snapshot cannot give (conformant data
     * rarely carries these values, memory {@code findings-snapshot-blind-on-conformant-data}):
     * numeric {@code IDVAR} targets 1, 10, 1.5, 12345678.5 and a LONG above 2^53, each matched by
     * its {@code IDVARVAL} text, plus one orphan each — only the orphans fire, as they did on the
     * text read.
     */
    @Test
    void theIdvarRulesAnswerAsTheyDidOnNumericTargets()
    {
        IDataTable suppae = RealTables.of("SUPPAE").str("STUDYID", "S", "S", "S", "S", "S")
                .str("RDOMAIN", "AE", "AE", "AE", "AE", "AE")
                .str("USUBJID", "U1", "U1", "U1", "U1", "U1")
                .str("IDVAR", "AESEQ", "AESEQ", "AESEQ", "AESEQ", "AESEQ")
                .str("IDVARVAL", "1", "10", "1.5", "12345678.5", "2")
                .str("QNAM", "Q", "Q", "Q", "Q", "Q").str("QVAL", "v", "v", "v", "v", "v").build();
        IDataTable ae = RealTables.of("AE").str("STUDYID", "S", "S", "S", "S")
                .str("USUBJID", "U1", "U1", "U1", "U1").dbl("AESEQ", 1.0, 10.0, 1.5, 12345678.5)
                .build();
        assertEquals(List.of(5L), cg0371On(suppae, ae), "only the orphan IDVARVAL \"2\" fires");

        IDataTable suppLong = RealTables.of("SUPPAE").str("STUDYID", "S", "S")
                .str("RDOMAIN", "AE", "AE").str("USUBJID", "U1", "U1")
                .str("IDVAR", "AESEQ", "AESEQ")
                .str("IDVARVAL", "9007199254740993", "9007199254740992").str("QNAM", "Q", "Q")
                .str("QVAL", "v", "v").build();
        IDataTable aeLong = RealTables.of("AE").str("STUDYID", "S").str("USUBJID", "U1")
                .lng("AESEQ", 9007199254740993L).build();
        assertEquals(List.of(2L), cg0371On(suppLong, aeLong),
                "a LONG above 2^53 matches exactly; its neighbour is an orphan");
    }


    /**
     * A literal {@code colref("X")} has no column operand, so without its row-reader declaration
     * the level calculus read it DATASET and the rule broadcast row 0's cell to every row (the
     * {@code row_max} shape of XCUT H1). Unguarded on purpose: the guard the other tests carry
     * would hide exactly this.
     */
    @Test
    void aLiteralColrefIsReadPerRowNeverBroadcast()
    {
        Rule r = load(rule("colref(\"APERSDT\") > 150", false));
        assertNull(r.getLoadError(), r.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(r, adae(),
                RealTables.inventoryOf(adae(), adsl()), "ADAE", null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        assertEquals(List.of(2L, 3L), rows, "200 and 300 exceed 150; 100 does not");
    }
}
