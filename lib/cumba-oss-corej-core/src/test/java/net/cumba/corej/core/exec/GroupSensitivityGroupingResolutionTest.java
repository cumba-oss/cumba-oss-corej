package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the Group-sensitivity grouping-variable resolution in
 * {@link RuleRunner#executeGrouped}, carried by a hand-written rule ({@link #GROUPED_RULE}):
 * {@code is_inconsistent_across_dataset(--STRESU, keys=[…])} under {@code Sensitivity: Group} with
 * five {@code --} grouping variables.
 *
 * <p>
 * Two engine bugs are covered:
 * </p>
 * <ol>
 * <li><b>{@code --} wildcard resolution.</b> {@code Grouping_Variables} are stored in {@code --}
 * wildcard form ({@code --TESTCD}); the table carries the domain-resolved column
 * ({@code LBTESTCD}). Before the fix, {@code executeGrouped} built the grouping index over the raw
 * {@code --} names, so {@code createIndex} found no matching column, returned {@code null}, and the
 * rule silently produced <b>zero</b> violations on every dataset. Today the names are resolved at
 * bind time by {@code RuleSpecialiser} (D77) — an unresolved {@code --} reaching the runner ERRORs
 * the rule — and {@code RuleRunner.resolveGroupingPrefixes} only re-applies the substitution to
 * names that are already concrete. The failure mode left to guard is a resolution to the WRONG
 * prefix: concrete names that match no column, which the absent-column fallback below silently
 * collapses into one group. That is why the resolution test carries TWO groups.</li>
 * <li><b>Silently dropping unavailable grouping columns.</b> A grouping column absent from the
 * dataset (e.g. the permissible {@code --SCAT}) must be dropped — grouping by the remaining present
 * columns — rather than nulling the whole index. When none remain, the whole dataset is a single
 * group.</li>
 * </ol>
 *
 * <p>
 * The rule is written for this mechanism, not copied from the corpus: the {@code Group} shape and
 * the five grouping variables are what make
 * {@link #wildcardGroupingVariablesResolveToDomainColumns()} the all-present case and
 * {@link #missingGroupingColumnIsDroppedNotZeroed()} the one-absent case. The operator's own
 * {@code keys=} name the same five columns, so "all present" holds on both axes. The all-absent
 * case is, since EC-44 / Fix #134, ONE consistency class over the whole dataset rather than the
 * empty verdict the operator's own guard used to return.
 * </p>
 */
class GroupSensitivityGroupingResolutionTest
{

    /**
     * Group sensitivity over five {@code --} grouping variables; fires on a group whose
     * {@code --STRESU} values disagree within the same five identity keys.
     */
    private static final String GROUPED_RULE = """
            {"rules":{"G1":{"Core":{"Id":"T-GROUP-STRESU"},"Sensitivity":"Group",
             "Scope":{"Domains":{"Include":["LB"]}},
             "Grouping_Variables":["--TESTCD","--CAT","--SCAT","--SPEC","--METHOD"],
             "Check":{"expression":
               "is_inconsistent_across_dataset(--STRESU, keys=[--TESTCD, --CAT, --SCAT, --SPEC, --METHOD])"},
             "Outcome":{"Message":"m","Output_Variables":["--TESTCD","--STRESU"]}}}}""";

    private static Rule groupedRule() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(GROUPED_RULE).getRules().get("G1");
        assertNull(rule.getLoadError(), "the hand-written rule must load: " + rule.getLoadError());
        assertEquals(Sensitivity.GROUP, rule.getSensitivity());
        return rule;
    }


    /**
     * Builds an LB table from an ordered column→(row0, row1, …) map; the row count is the first
     * column's length. {@code LBSEQ} is a LONG column numbered from 1 (its map value is ignored);
     * everything else is STRING. Each test gives rows it groups together intentionally inconsistent
     * {@code LBSTRESU} values, so the rule fires once per such group.
     */
    private static IDataTable lb(Map<String, String[]> cols)
    {
        int rows = cols.values().iterator().next().length;
        OverlayDataTable t = OverlayDataTable.empty("LB", "LB", rows);
        for (String c : cols.keySet())
        {
            t.addColumn(c, "LBSEQ".equals(c) ? DataValueType.LONG : DataValueType.STRING, c);
        }
        for (int r = 0; r < rows; r++)
        {
            for (Map.Entry<String, String[]> e : cols.entrySet())
            {
                if ("LBSEQ".equals(e.getKey()))
                {
                    t.setValue(r, e.getKey(), (long) (r + 1));
                }
                else
                {
                    t.setValue(r, e.getKey(), e.getValue()[r]);
                }
            }
        }
        return t;
    }


    private static int run(Rule rule, IDataTable table)
    {
        RuleExecutionResult res = RuleRunnerCalls.execute(rule, table, _ -> null, "LB", null);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), "rule should execute");
        return res.getViolationCount();
    }


    @Test
    void wildcardGroupingVariablesResolveToDomainColumns() throws IOException
    {
        // All five grouping columns present (domain-resolved); LBSTAT absent. Before the fix the
        // raw --TESTCD grouping names matched no column -> createIndex null -> 0 violations.
        //
        // TWO assessment groups (ALB and GLUC), each internally inconsistent in LBSTRESU. Only a
        // grouping index built over the correctly RESOLVED names (LBTESTCD, ...) separates them,
        // giving one group-level violation per group = 2. Were the names resolved to anything
        // that matches no column (a wrong prefix), every grouping column would read as absent,
        // the whole dataset would collapse into one group (the all-absent fallback), and the
        // group verdict would report 1 — so a single-group table could not tell the two apart.
        Map<String, String[]> cols = new LinkedHashMap<>();
        cols.put("STUDYID", new String[]
        {
                "S1", "S1", "S1", "S1"
        });
        cols.put("USUBJID", new String[]
        {
                "001", "001", "001", "001"
        });
        cols.put("LBSEQ", null);
        cols.put("LBTESTCD", new String[]
        {
                "ALB", "ALB", "GLUC", "GLUC"
        });
        cols.put("LBCAT", new String[]
        {
                "CHEM", "CHEM", "CHEM", "CHEM"
        });
        cols.put("LBSCAT", new String[]
        {
                "GEN", "GEN", "GEN", "GEN"
        });
        cols.put("LBSPEC", new String[]
        {
                "SERUM", "SERUM", "SERUM", "SERUM"
        });
        cols.put("LBMETHOD", new String[]
        {
                "ENZ", "ENZ", "ENZ", "ENZ"
        });
        cols.put("LBSTRESU", new String[]
        {
                "g/L", "mg/dL", "mg/dL", "mmol/L"
        });
        // Two failing groups -> one group-level violation each.
        assertEquals(2, run(groupedRule(), lb(cols)));
    }


    @Test
    void missingGroupingColumnIsDroppedNotZeroed() throws IOException
    {
        // LBSCAT (a permissible grouping variable) absent. The rule must group by the remaining
        // present columns and still fire; before the present-column filter, createIndex returned
        // null on the full list and the rule produced 0 violations.
        Map<String, String[]> cols = new LinkedHashMap<>();
        cols.put("STUDYID", new String[]
        {
                "S1", "S1"
        });
        cols.put("USUBJID", new String[]
        {
                "001", "001"
        });
        cols.put("LBSEQ", null);
        cols.put("LBTESTCD", new String[]
        {
                "ALB", "ALB"
        });
        cols.put("LBCAT", new String[]
        {
                "CHEM", "CHEM"
        });
        // no LBSCAT
        cols.put("LBSPEC", new String[]
        {
                "SERUM", "SERUM"
        });
        cols.put("LBMETHOD", new String[]
        {
                "ENZ", "ENZ"
        });
        cols.put("LBSTRESU", new String[]
        {
                "g/L", "mg/dL"
        });
        assertEquals(1, run(groupedRule(), lb(cols)));
    }


    @Test
    void groupFiresWhenAnyRowFlagged_majorityFirst() throws IOException
    {
        // Three records of ONE assessment group: the two majority rows (mg/dL) come first, the
        // deviating minority row (mmol/L) last. is_inconsistent_across_dataset flags only the
        // minority row; under the retired representative-row verdict the group's first row was
        // unflagged and the group reported NOTHING (record-order dependence). The group verdict
        // is "any row flagged" (2026-07-28): exactly one group-level violation, anchored at the
        // first flagged row.
        Map<String, String[]> cols = new LinkedHashMap<>();
        cols.put("STUDYID", new String[]
        {
                "S1", "S1", "S1"
        });
        cols.put("USUBJID", new String[]
        {
                "P1", "P2", "P3"
        });
        cols.put("LBTESTCD", new String[]
        {
                "GLUC", "GLUC", "GLUC"
        });
        cols.put("LBCAT", new String[]
        {
                "CHEM", "CHEM", "CHEM"
        });
        cols.put("LBSCAT", new String[]
        {
                "SUB", "SUB", "SUB"
        });
        cols.put("LBSPEC", new String[]
        {
                "SERUM", "SERUM", "SERUM"
        });
        cols.put("LBMETHOD", new String[]
        {
                "ENZ", "ENZ", "ENZ"
        });
        cols.put("LBSTRESU", new String[]
        {
                "mg/dL", "mg/dL", "mmol/L"
        });
        assertEquals(1, run(groupedRule(), lb(cols)));
    }


    @Test
    void allGroupingColumnsAbsentIsOneConsistencyClass() throws IOException
    {
        // None of the five identity columns are present (the --STAT cleanup, EC-11, removed the
        // sixth operator key that used to keep the grouping non-empty here).
        //
        // EC-44 (Fix #134): every grouping column absent now means ONE consistency class over the
        // whole dataset — no comparator can tell two rows apart, so they must agree — and the two
        // differing LBSTRESU values ("g/L" vs "mg/dL") are inconsistent within it. Until Fix #134
        // `validGroupCols empty` short-circuited to an empty result, which was introduced by
        // EC-25 / Fix #116 only to stop the fork's `groupby([])` raising on conformant data; one
        // group avoids that raise just as well.
        // This edge is unreachable on real FINDINGS data, which always carries --TESTCD.
        Map<String, String[]> cols = new LinkedHashMap<>();
        cols.put("STUDYID", new String[]
        {
                "S1", "S1"
        });
        cols.put("USUBJID", new String[]
        {
                "001", "001"
        });
        cols.put("LBSEQ", null);
        cols.put("LBSTRESU", new String[]
        {
                "g/L", "mg/dL"
        });
        assertEquals(1, run(groupedRule(), lb(cols)));
    }
}
