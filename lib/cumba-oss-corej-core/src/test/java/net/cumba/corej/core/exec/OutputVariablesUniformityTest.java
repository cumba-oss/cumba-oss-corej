package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.joined;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.matchJ;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.primary;
import static net.cumba.corej.core.exec.QualifiedNameFixture.renamed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.rows;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static net.cumba.corej.core.exec.QualifiedNameFixture.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.report.ValidationReportBuilder;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeKind;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} phase 1 — the {@code Output_Variables} surface and
 * criterion C11: a report prints the qualified name AS WRITTEN and the SAME value the Check read.
 * The writers (JSON: {@code JsonReportWriter} serialises {@code Violation.getValues()} through
 * Jackson; XLSX: {@code XlsxReportWriter.writeCell} prints {@code String.valueOf}) transform
 * nothing, so the {@link Violation#getValues()} map IS what is printed — pinned here.
 *
 * <p>
 * Known reds recorded, not fixed (plan §2.4 / findings): <b>N2</b> — a lower-case qualifier in an
 * entry is matched exactly against the join map ({@code RuleRunner.extractOutputValues}, the
 * {@code ctx.joins().get(dsName)} read) and the entry is silently omitted, while a lower-case
 * COLUMN part is found (the datatable lookup ignores case); <b>N17</b> — an entry naming an
 * undeclared dataset is omitted at run time — Stage A files the observe-only
 * {@code DOTTED_REF_UNDECLARED} for it since phase 3.
 * </p>
 *
 * <p>
 * <b>W3b of {@code PLAN-merged-evaluation-table}</b> (the section at the end): every projection
 * site — the record-level row, a Group finding's distinct set, the name-only list of a non-record
 * finding — is served from the merged evaluation view. A planted row shift
 * ({@link MergedEvaluation#plantForTests}) under a Check that reads only primary columns moves the
 * printed values and no finding, so the projection, not the Check, is what reads the view; a
 * behaviour the view changed was shown against the legacy arm in the same test until W6 retired it
 * (the values both arms printed are pinned instead). The report location keeps real columns and
 * bare {@code QNAM} tokens: a merged name always carries a {@code .}
 * ({@code FindingLocations.isNonColumnToken}).
 * </p>
 */
class OutputVariablesUniformityTest
{

    private static final String FIRES_EVERYWHERE = "K != \"zz\"";

    private static Rule rule(String... outputVariables)
    {
        return loadClean(ruleJson("Record",
                matchJ("") + "," + check(FIRES_EVERYWHERE) + "," + outcome(outputVariables)), true);
    }


    private static List<Map<String, String>> report(Rule rule, IDataTable p, IDataTable j)
    {
        return QualifiedNameFixture.values(executed(rule, p, exactInventory(p, j)));
    }


    @Test
    void aQualifiedEntryPrintsTheNameAsWrittenAndTheValueTheCheckRead()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        List<Map<String, String>> bare = report(rule("S", "N", "L"), p, j);
        List<Map<String, String>> dotted = report(rule("J.S", "J.N", "J.L"), p, j);
        assertEquals(6, bare.size());
        assertEquals(List.of("J.S", "J.N", "J.L", "K"), List.copyOf(dotted.get(0).keySet()),
                "the qualified name is printed as written (K: the column the Check read)");
        assertEquals(bare, renamed(dotted, Map.of("J.S", "S", "J.N", "N", "J.L", "L")),
                "cell for cell the same text — a missing prints its marker on both sides");
        assertEquals(".A", bare.get(3).get("N"), "the fixture's .A is a marker, not blank");
        assertEquals("", bare.get(2).get("S"));
    }


    @Test
    void anUnmatchedJoinRowPrintsTheTypeDefaultLikeADefaultedPrimaryCell()
    {
        // J without k4 (row 3): J.S / J.N read "" / MIS there (D72a-1); the primary side carries
        // the same defaults at row 3.
        IDataTable pDefaulted = RealTables.of("P").str("K", "k1", "k2", "k3", "k4", "k5", "k6")
                .str("S", "a", "b", "", "", "b", "c").dbl("N", 1.0, 2.5,
                        MissingValue.MIS_UNKNOWN.asDouble(), MissingValue.MIS.asDouble(), 1.0, 3.0)
                .build();
        IDataTable jUnmatched = RealTables.of("J").str("K", "k1", "k2", "k3", "k5", "k6")
                .str("S", "a", "b", "", "b", "c")
                .dbl("N", 1.0, 2.5, MissingValue.MIS_UNKNOWN.asDouble(), 1.0, 3.0).build();
        List<Map<String, String>> bare = report(rule("S", "N"), pDefaulted, jUnmatched);
        List<Map<String, String>> dotted = report(rule("J.S", "J.N"), pDefaulted, jUnmatched);
        assertEquals(bare.get(3), renamed(dotted, Map.of("J.S", "S", "J.N", "N")).get(3));
        assertEquals("", bare.get(3).get("S"));
    }


    @Test
    void anAbsentColumnIsOmittedOnBothSides()
    {
        IDataTable p = table("P", "L");
        IDataTable j = table("J", "L");
        List<Map<String, String>> bare = report(rule("S", "L"), p, j);
        List<Map<String, String>> dotted = report(rule("J.S", "J.L"), p, j);
        assertEquals(List.of("S", "K"), List.copyOf(bare.get(0).keySet()),
                "the absent bare entry is omitted (UVC unqualified: never resolved from the join)");
        assertEquals(List.of("J.S", "K"), List.copyOf(dotted.get(0).keySet()),
                "the absent joined entry is omitted (JVA 2c)");
    }


    @Test
    void aLowerCaseColumnPartIsFoundOnBothSides()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        // The DATA spells the column in lower case; the entries are written upper case.
        IDataTable pLower = RealTables.of("P").str("K", "k1", "k2", "k3", "k4", "k5", "k6")
                .str("s", "a", "b", "", "A", "b", "c").build();
        IDataTable jLower = RealTables.of("J").str("K", "k1", "k2", "k3", "k4", "k5", "k6")
                .str("s", "a", "b", "", "A", "b", "c").build();
        List<Map<String, String>> bare = report(rule("S"), pLower, jLower);
        List<Map<String, String>> dotted = report(rule("J.S"), pLower, jLower);
        assertEquals(report(rule("S"), p, j), bare, "CIT §1 on the primary");
        assertEquals(bare, renamed(dotted, Map.of("J.S", "S")), "CIT §1 through the join");
    }


    /**
     * P-19 / MET #6 ({@code PLAN-merged-evaluation-table} W3d; was known red N2): the QUALIFIER is
     * matched ignoring case — a lower-case {@code j.S} is printed as written and reads the join
     * declared as {@code J}, exactly as the template form {@code adsl.TRTxxA} always did.
     */
    @Test
    void aLowerCaseQualifierPrintsAsWrittenAndReadsTheJoin()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        List<Map<String, String>> bare = report(rule("S"), p, j);
        List<Map<String, String>> dotted = report(rule("j.S"), p, j);
        assertEquals(List.of("j.S", "K"), List.copyOf(dotted.get(0).keySet()),
                "the entry as written");
        assertEquals(bare, renamed(dotted, Map.of("j.S", "S")),
                "j.S reads the join J, cell for cell the value the bare S reads");
    }


    /**
     * N17 (fixed at load, not at run time): an entry naming an undeclared dataset is still omitted
     * from every finding — there is no join to read — but Stage A now files the observe-only
     * {@code DOTTED_REF_UNDECLARED} the Check side files for the same spelling.
     */
    @Test
    void anUndeclaredQualifierIsOmittedAndReportedByStageA()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        Rule r = rule("Q.S", "S");
        List<Map<String, String>> dotted = report(r, p, j);
        assertFalse(dotted.get(0).containsKey("Q.S"), "no join Q: omitted at run time");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), rows(executed(r, p, exactInventory(p, j))));
        java.util.SequencedMap<net.cumba.datatable.report.Severity, net.cumba.corej.core.expr.ast.Expr> levels = new java.util.LinkedHashMap<>();
        levels.put(net.cumba.datatable.report.Severity.ERROR,
                net.cumba.corej.core.expr.CheckExpressionParser.parse(FIRES_EVERYWHERE));
        List<String> findings = net.cumba.corej.core.expr.typed.StageAChecker.check(r, levels)
                .findings().stream().map(String::valueOf).toList();
        assertEquals(1, findings.size(), String.valueOf(findings));
        assertTrue(findings.get(0).contains("DOTTED_REF_UNDECLARED")
                && findings.get(0).contains("Output_Variables entry Q.S"), findings.get(0));
    }

    // ------------------------------------------------------------------ W3b: the merged view


    @AfterEach
    void hooksOff()
    {
        MergedEvaluation.plantForTests(null);
    }


    private static RuleExecutionResult view(Rule rule, IDataTable p, IDataTable... others)
    {
        return executed(rule, p, inventory(p, others));
    }


    private static DatasetResolver inventory(IDataTable p, IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = p;
        System.arraycopy(others, 0, all, 1, others.length);
        return exactInventory(all);
    }


    private static Rule ruleOf(String sensitivity, String body)
    {
        return loadClean(ruleJson(sensitivity, body), true);
    }


    /** The name-only list of every violation, in report order. */
    private static List<List<String>> named(RuleExecutionResult result)
    {
        List<List<String>> out = new ArrayList<>();
        for (Violation v : result.getViolations())
        {
            out.add(v.getNamedColumns());
        }
        return out;
    }


    /** A single value column of {@code values}, in report order. */
    private static List<String> column(List<Map<String, String>> values, String name)
    {
        List<String> out = new ArrayList<>();
        for (Map<String, String> v : values)
        {
            out.add(v.get(name));
        }
        return out;
    }


    private static IDataTable ae()
    {
        return RealTables.of("AE").str("STUDYID", "S1", "S1", "S1").str("DOMAIN", "AE", "AE", "AE")
                .str("USUBJID", "01", "01", "02").lng("AESEQ", 1L, 2L, 1L)
                .str("AETERM", "A", "B", "C").build();
    }


    private static IDataTable dm()
    {
        return RealTables.of("DM").str("STUDYID", "S1").str("USUBJID", "01").lng("AGE", 54L)
                .build();
    }


    /** SUPPAE qualifying AE (01, AESEQ 1) with Y and (02, AESEQ 1) with N for AETRTEM. */
    private static IDataTable suppae()
    {
        return RealTables.of("SUPPAE").str("STUDYID", "S1", "S1").str("RDOMAIN", "AE", "AE")
                .str("USUBJID", "01", "02").str("IDVAR", "AESEQ", "AESEQ").str("IDVARVAL", "1", "1")
                .str("QNAM", "AETRTEM", "AETRTEM").str("QVAL", "Y", "N").build();
    }


    @Test
    void w3bARecordFindingsJoinedEntriesAreReadFromTheView()
    {
        // The Check reads K only, so the plant cannot move a finding — only the printed values,
        // which therefore come from the view.
        IDataTable p = primary();
        IDataTable j = joined();
        Rule r = rule("J.S", "J.N");
        List<Map<String, String>> clean = QualifiedNameFixture.values(view(r, p, j));
        assertEquals(List.of("a", "b", "", "A", "b", "c"), column(clean, "J.S"),
                "what both arms printed when W3b compared them");
        MergedEvaluation.plantForTests(MergeKind.KEYED);
        RuleExecutionResult planted = view(r, p, j);
        assertEquals(rows(view(r, p, j)), rows(planted), "the plant moves no finding");
        assertEquals(List.of("b", "", "A", "b", "c", "a"),
                column(QualifiedNameFixture.values(planted), "J.S"),
                "each row prints the next merged row's J.S: the projection read the view");
    }


    @Test
    void w3bARecordFindingsSuppQualifierIsReadFromTheView()
    {
        IDataTable ae = ae();
        Rule r = ruleOf("Record", check("AETERM != \"zz\"") + "," + outcome("AESEQ", "AETRTEM"));
        List<Map<String, String>> clean = QualifiedNameFixture.values(view(r, ae, suppae()));
        assertEquals(List.of("Y", "", "N"), column(clean, "AETRTEM"),
                "what both arms printed when W3b compared them");
        MergedEvaluation.plantForTests(MergeKind.SUPP);
        List<Map<String, String>> planted = QualifiedNameFixture.values(view(r, ae, suppae()));
        assertEquals(List.of("", "N", "Y"), column(planted, "AETRTEM"),
                "each row prints the next merged row's qualifier: the projection read the view");
        assertEquals(column(clean, "AESEQ"), column(planted, "AESEQ"));
    }


    @Test
    void w3bAGroupFindingsDistinctSetIsReadFromTheView()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        Rule r = ruleOf("Group", matchJ("") + ",\"Grouping\":{\"Variables\":[\"G\"]},"
                + check(FIRES_EVERYWHERE) + "," + outcome("G", "J.S"));
        List<Map<String, String>> clean = QualifiedNameFixture.values(view(r, p, j));
        assertEquals(List.of("[a, b]", "[, A]", "[b, c]"), column(clean, "J.S"),
                "what both arms printed when W3b compared them");
        MergedEvaluation.plantForTests(MergeKind.KEYED);
        List<Map<String, String>> planted = QualifiedNameFixture.values(view(r, p, j));
        assertEquals(List.of("[b, ]", "[A, b]", "[c, a]"), column(planted, "J.S"),
                "the distinct set over the block's rows shifted with the view");
        assertEquals(column(clean, "G"), column(planted, "G"));
    }


    @Test
    void w3bANonRecordFindingListsAViewColumnByNameAndOmitsAnAbsentOneOnAnEmptyPrimaryToo()
    {
        // Red-first (d71f27d, both arms): the column-level path asked the join at row 0 and, on
        // an empty primary, took the lookup's existence for the column's — J.Z, a column J does
        // not carry, was listed by name. "Output_Variables omits an absent column" (JVA 2c,
        // NK §1 case 1): the view answers from its schema, whatever the row count.
        IDataTable empty = RealTables.of("P").str("K").str("S").build();
        IDataTable full = primary();
        IDataTable j = joined();
        Rule column = ruleOf("Record", matchJ("")
                + ",\"Check\":{\"all\":[{\"expression\": \"ends_with(varname(), \\\"S\\\")\"},"
                + "{\"expression\": \"empty(var_format(\\\"DATA\\\"))\"}]},"
                + outcome("variable_name", "J.S", "J.Z"));
        assertEquals(List.of(List.of("J.S")), named(view(column, empty, j)), "empty primary");
        assertEquals(List.of(List.of("J.S")), named(view(column, full, j)), "populated primary");
        Rule verdict = ruleOf("Dataset",
                matchJ("") + "," + check("record_count() == 0") + "," + outcome("J.S", "J.Z", "S"));
        assertEquals(List.of(List.of("J.S", "S")), named(view(verdict, empty, j)),
                "a dataset verdict on an empty primary: listed by name, the absent one omitted");
    }


    @Test
    void w3bTheMatchedFlagIsAColumnOfTheViewAndIsReportedLikeOne()
    {
        // §3.1: <Name>._matched_ is a column of the merged schema, so an Output_Variables entry
        // naming it is a present column — printed on a record finding, listed by name on a
        // non-record one. The retired legacy arm had no such column and omitted the entry (W3b's
        // red-first evidence; no corpus rule reports the flag, so the corpus did not move).
        IDataTable p = primary();
        IDataTable j = RealTables.of("J").str("K", "k1", "k3").str("S", "a", "c").build();
        Rule r = rule("J._matched_");
        assertEquals(List.of("true", "false", "true", "false", "false", "false"),
                column(QualifiedNameFixture.values(view(r, p, j)), "J._matched_"));
        Rule verdict = ruleOf("Dataset",
                matchJ("") + "," + check("record_count() > 0") + "," + outcome("J._matched_"));
        assertEquals(List.of(List.of("J._matched_")), named(view(verdict, p, j)));
    }


    @Test
    void w3bAnExpandedRowReportsItsOwnPartnersValueAtTheRealRow()
    {
        // J carries k2 twice: AE row 2 is two merged rows, each a finding at real row 2 with its
        // own partner's value (MET #22's de-dup is W3e's; these two differ anyway).
        IDataTable p = primary();
        IDataTable j = RealTables.of("J").str("K", "k1", "k2", "k2").str("S", "a", "b", "c")
                .build();
        Rule r = rule("J.S");
        RuleExecutionResult on = view(r, p, j);
        assertEquals(List.of(1L, 2L, 2L, 3L, 4L, 5L, 6L), rows(on));
        assertEquals(List.of("a", "b", "c", "", "", "", ""),
                column(QualifiedNameFixture.values(on), "J.S"));
    }


    @Test
    void w3bTheReportLocationKeepsRealColumnsAndBareQualifiersOnly()
    {
        // The plan's §3.2: ValidationFindingLocation.variableNames keeps real columns only, as
        // today — every merged name but a primary SUPP qualifier carries a '.', so the location
        // drops DM.AGE and DM._matched_ and keeps the bare AETRTEM; the finding's schema keeps
        // every name as written.
        IDataTable ae = ae();
        String match = "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"STUDYID\",\"USUBJID\"],"
                + "\"Join_Type\":\"left\"}]";
        Rule r = ruleOf("Record", match + "," + check("AETERM != \"zz\"") + ","
                + outcome("AESEQ", "DM.AGE", "DM._matched_", "AETRTEM"));
        RuleExecutionResult result = view(r, ae, dm(), suppae());
        assertEquals(List.of("54", "54", "."),
                column(QualifiedNameFixture.values(result), "DM.AGE"));
        ValidationFinding f = only(r, result);
        assertTrue(
                f.getVariableNames()
                        .containsAll(List.of("AESEQ", "DM.AGE", "DM._matched_", "AETRTEM")),
                String.valueOf(f.getVariableNames()));
        assertEquals(List.of("AESEQ", "AETRTEM", "AETERM"), f.getLocation().getVariableNames());

        // A non-record finding (a Dataset rule decided per row, V5) lists the names; the location
        // highlights the bare qualifier and the real columns only.
        Rule perRow = ruleOf("Dataset", match + "," + check("AETERM == \"B\"") + ","
                + outcome("DM.AGE", "AETRTEM", "AESEQ"));
        ValidationFinding g = only(perRow, view(perRow, ae, dm(), suppae()));
        assertEquals(List.of("DM.AGE", "AETRTEM", "AESEQ", "AETERM"), g.getNamedColumns());
        assertEquals(List.of("AETRTEM", "AESEQ", "AETERM"), g.getLocation().getVariableNames());
    }


    private static ValidationFinding only(Rule rule, RuleExecutionResult result)
    {
        List<ValidationFinding> findings = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result).build().getMembers().get(0).getFindings();
        assertEquals(1, findings.size(), String.valueOf(findings));
        return findings.get(0);
    }
}
