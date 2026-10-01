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

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
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
 * {@code ctx.getJoinedDatasets().get(dsName)} read) and the entry is silently omitted, while a
 * lower-case COLUMN part is found (the datatable lookup ignores case); <b>N17</b> — an entry naming
 * an undeclared dataset is omitted at run time — Stage A files the observe-only
 * {@code DOTTED_REF_UNDECLARED} for it since phase 3.
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


    /** ⚠ Known red N2 (owner-pending, plan §2.4): the QUALIFIER is matched exactly. */
    @Test
    void aLowerCaseQualifierIsSilentlyOmittedToday()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        List<Map<String, String>> dotted = report(rule("j.S", "S"), p, j);
        assertFalse(dotted.get(0).containsKey("j.S"),
                "RuleRunner.extractOutputValues: ctx.getJoinedDatasets().get(\"j\") is null for a"
                        + " join declared as J, and the entry is omitted — N2 (the template"
                        + " form adsl.TRTxxA, through FindVars.declaredQualifier, IS matched"
                        + " ignoring case). Remove this pin when N2 is ruled and fixed.");
        assertTrue(dotted.get(0).containsKey("S"));
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
}
