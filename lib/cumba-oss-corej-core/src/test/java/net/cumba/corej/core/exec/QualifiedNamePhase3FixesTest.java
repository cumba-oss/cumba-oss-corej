package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.joined;
import static net.cumba.corej.core.exec.QualifiedNameFixture.load;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.primary;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} phase 3 — the fix-here findings that the harness
 * ({@code expr.eval.QualifiedNameUniformityTest}) and the per-surface classes do not already pin as
 * a row: each test here was RED before its fix.
 */
class QualifiedNamePhase3FixesTest
{

    /** A Record rule over {@code P} that fires on every row iff {@code predicate} holds. */
    private static Rule firesIf(String predicate)
    {
        return loadClean(
                ruleJson("Record", check("K != \"zz\" and " + predicate) + "," + outcome("K")),
                false);
    }


    private static int fired(String predicate, IDataTable p, IDataTable j)
    {
        return executed(firesIf(predicate), p, exactInventory(p, j)).getViolations().size();
    }


    /**
     * N3 (column half, {@code CIT §1}): the COLUMN part of a dotted {@code var_exists} string is
     * matched ignoring case, as every other column lookup is. RED before: the
     * {@code DOTTED_DATASET_COLUMN} form admitted an upper-case column part only, so {@code "J.s"}
     * fell through to a primary-schema lookup of the literal text {@code J.s} and answered
     * {@code false} — while {@code "J.S"} answered {@code true} for the same column.
     */
    @Test
    void aLowerCaseColumnPartOfADottedVarExistsNameIsFound()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        assertEquals(6, fired("var_exists(\"J.S\")", p, j), "control: the upper-case spelling");
        assertEquals(6, fired("var_exists(\"J.s\")", p, j), "CIT §1: the column part");
        assertEquals(0, fired("var_not_exists(\"J.s\")", p, j), "the negation agrees");
        assertEquals(0, fired("var_exists(\"J.nope\")", p, j), "an absent column stays absent");
        // ⚠ The QUALIFIER half is owner-pending N2 (plan §2.4): a lower-case qualifier is still
        // matched exactly and answers false. Pinned so this fix cannot widen it silently.
        assertEquals(0, fired("var_exists(\"j.S\")", p, j), "owner-pending N2: qualifier exact");
    }


    /**
     * N4 ({@code vlm_*}): the value-level metadata accessors read the Define-XML VLM of the
     * EVALUATED dataset's variable, its WhereClause evaluated against the evaluated row, so a
     * qualified name never matched — a silent never-fires. A joined column's VLM is not served, so
     * the qualified spelling is refused at load, naming the bare one, for the reference and the
     * string form alike. RED before: both rules loaded clean.
     */
    @Test
    void aQualifiedVlmAccessorNameIsRefusedAtLoad()
    {
        for (String call : new String[]
        {
                "vlm_data_type(J.S)", "vlm_length(\"J.S\")"
        })
        {
            Rule r = load(ruleJson("Record", QualifiedNameFixture.matchJ("") + ","
                    + check(call + " == \"text\"") + "," + outcome("K")), true);
            String error = r.getLoadError();
            assertNotNull(error, call + " must not load clean");
            assertTrue(error.contains("write it bare (S)"), error);
        }
    }


    /**
     * Lane-2 N19, checked and DROPPED: the claim was that {@code DatasetLookup} inherits
     * {@link JoinLookup#hasColumn}'s {@code true} default, so a SUPP / SQ / {@code --}-named join
     * would print {@code ""} for an absent dotted output column instead of omitting it (JVA 2c).
     * {@code DatasetLookup} overrides it ({@code PLAN-joined-value-accessor} §2c, MED-4), and every
     * named entry — a SUPP-named one included — is built as one. Pinned here so the premise stays
     * checked: the absent {@code SUPPJ.X} is omitted, the present {@code SUPPJ.S} reported.
     */
    @Test
    void anAbsentDottedOutputColumnOfASuppNamedJoinIsOmitted()
    {
        IDataTable p = primary();
        IDataTable suppj = QualifiedNameFixture.table("SUPPJ", "N");
        Rule r = loadClean(ruleJson("Record",
                "\"Match_Datasets\":[{\"Name\":\"SUPPJ\",\"Keys\":[\"K\"],"
                        + "\"Join_Type\":\"left\"}]," + check("K != \"zz\"") + ","
                        + outcome("SUPPJ.N", "SUPPJ.S")),
                true);
        java.util.List<java.util.Map<String, String>> values = QualifiedNameFixture
                .values(executed(r, p, exactInventory(p, suppj)));
        assertEquals(6, values.size());
        assertEquals(java.util.List.of("SUPPJ.S", "K"),
                java.util.List.copyOf(values.get(0).keySet()),
                "SUPPJ.N is absent from SUPPJ: omitted, never printed as \"\"");
    }


    /**
     * N13: a {@code var_*} accessor names a variable of the dataset under evaluation, so a
     * qualified {@code "J.S"} was looked up verbatim there and answered a missing at every level —
     * at LIBRARY a missing compares as {@code ""}, so {@code var_core("J.S", "LIBRARY") != "Req"}
     * was a FALSE POSITIVE on every row (phase 2 lane 1). Refused at load, naming the bare spelling
     * and, for the four accessors that have it, the {@code dataset=} channel. RED before: every
     * rule below loaded clean.
     */
    @Test
    void aQualifiedVarAccessorNameIsRefusedAtLoadAtEveryLevel()
    {
        String[][] cases =
        {
                {
                        "var_core(\"J.S\", \"LIBRARY\") != \"Req\"", null
                },
                {
                        "var_label(\"J.S\", \"DEFINE\") == \"x\"", null
                },
                {
                        "var_type(\"J.S\", \"DATA\") == \"Char\"", "dataset=\"J\""
                },
                {
                        "var_ordinal(\"J.S\", \"DATA\") == 1", null
                }
        };
        for (String[] c : cases)
        {
            Rule r = load(ruleJson("Record",
                    QualifiedNameFixture.matchJ("") + "," + check(c[0]) + "," + outcome("K")),
                    true);
            String error = r.getLoadError();
            assertNotNull(error, c[0] + " must not load clean");
            assertTrue(error.contains("write it bare (\"S\")"), error);
            if (c[1] != null)
            {
                assertTrue(error.contains(c[1]), error);
            }
        }
    }


    /** A rule whose Check reads {@code expression} with the binding {@code $b: binding}. */
    private static Rule withBinding(String binding, String expression)
    {
        return loadClean(ruleJson("Record", QualifiedNameFixture.matchJ("") + ","
                + "\"Bindings\":[{\"name\":\"$b\",\"expression\":\"" + binding.replace("\"", "\\\"")
                + "\"}]," + check(expression) + "," + outcome("K")), true);
    }


    /**
     * N29: {@code is_unique_set} / {@code is_not_unique_set} spliced a {@code $} member through a
     * private copy of the splice that had drifted from {@code record_count}'s three ways — a
     * qualified name, a {@code String} binding and a non-list binding all passed RAW and were then
     * dropped as an absent member, silently. One splice now ({@code GroupSplice}): a qualified name
     * or a non-list binding is the rule's ERROR, a {@code String} binding is one name. RED before:
     * the qualified splice EXECUTED (J.G dropped as an absent primary member).
     */
    @Test
    void aUniqueSetSpliceIsTheRecordCountSplice()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        RuleExecutionResult qualified = QualifiedNameFixture.run(
                withBinding("find_vars(\"J.G\")", "is_not_unique_set([K, $b])"), p,
                exactInventory(p, j));
        assertEquals(RuleExecutionStatus.ERROR, qualified.getStatus(),
                qualified.getStatusMessage());
        assertTrue(String.valueOf(qualified.getStatusMessage())
                .contains("splices the qualified name J.G"), qualified.getStatusMessage());
        RuleExecutionResult scalar = QualifiedNameFixture
                .run(withBinding("5", "is_not_unique_set([K, $b])"), p, exactInventory(p, j));
        assertEquals(RuleExecutionStatus.ERROR, scalar.getStatus(), scalar.getStatusMessage());
        assertTrue(String.valueOf(scalar.getStatusMessage()).contains("must hold a list"),
                scalar.getStatusMessage());
        // S holds a duplicate on rows 2 / 5 only (b, b); a DROPPED member would leave the empty
        // tuple, which fires every row (D-4's degenerate contract).
        String check = "K != \"zz\" and is_not_unique_set([$b])";
        assertEquals(java.util.List.of(2L, 5L),
                QualifiedNameFixture
                        .rows(executed(withBinding("\"S\"", check), p, exactInventory(p, j))),
                "a String binding is one name");
        assertEquals(java.util.List.of(2L, 5L),
                QualifiedNameFixture.rows(
                        executed(withBinding("find_vars(\"S\")", check), p, exactInventory(p, j))),
                "control: a list binding");
    }


    /**
     * N20: a dataset-level finding on an EMPTY primary has no row to read. A present bare output
     * column prints {@code ""} (the row guard of {@code RuleRunner.extractOutputValues}); a dotted
     * one read row 0 through the join anyway and printed the numeric column's missing marker. One
     * guard for both arms now. RED before: {@code J.N} printed {@code "."}.
     */
    @Test
    void aDatasetLevelFindingOnAnEmptyPrimaryPrintsDottedOutputsLikeBareOnes()
    {
        IDataTable empty = RealTables.of("P").str("K").str("S").dbl("N").build();
        IDataTable j = joined();
        Rule r = loadClean(ruleJson("Dataset", QualifiedNameFixture.matchJ("") + ","
                + check("var_not_exists(\"TRTSDT\")") + "," + outcome("S", "N", "J.S", "J.N")),
                true);
        java.util.List<java.util.Map<String, String>> values = QualifiedNameFixture
                .values(executed(r, empty, exactInventory(empty, j)));
        assertEquals(1, values.size(), "the dataset-level finding fires on the empty dataset");
        assertEquals(java.util.Map.of("S", "", "N", "", "J.S", "", "J.N", ""), values.get(0));
    }


    /**
     * Phase 2 lane 1 aside: {@code ds_name} / {@code ds_label} / {@code ds_domain(<dataset>,
     * "DATA")} ignored the dataset argument and answered about the dataset under evaluation —
     * silently about the wrong dataset. They now read the named one through the study inventory (a
     * dataset the study lacks is a missing); {@code ds_class}, whose DATA-level value is not on the
     * context for another dataset, refuses an argument at load. RED before: {@code ds_name("J",
     * "DATA")} answered {@code P}, and the {@code ds_class} rule loaded clean.
     */
    @Test
    void aDataLevelDatasetAccessorReadsTheNamedDataset()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        assertEquals(6, fired("ds_name(\"J\", \"DATA\") == \"J\"", p, j));
        assertEquals(6, fired("ds_name(\"DATA\") == \"P\"", p, j), "control: no argument");
        assertEquals(6, fired("empty(ds_name(\"Q\", \"DATA\"))", p, j),
                "a dataset the study lacks is a missing");
        Rule r = load(ruleJson("Record",
                check("ds_class(\"J\", \"DATA\") == \"X\"") + "," + outcome("K")), false);
        assertNotNull(r.getLoadError(), "ds_class(<dataset>, DATA) must not load clean");
        assertTrue(r.getLoadError().contains("ds_class(\"DATA\")"), r.getLoadError());
    }
}
