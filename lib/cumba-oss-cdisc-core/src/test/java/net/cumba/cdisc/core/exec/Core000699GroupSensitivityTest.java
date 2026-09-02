package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the Group-sensitivity grouping-variable resolution in
 * {@link RuleRunner#executeGrouped}, pinned on the shipped rule CORE-000699 ({@code --STRESU}
 * inconsistent within {@code [--TESTCD, --CAT, --SCAT, --SPEC, --METHOD]}; {@code Sensitivity:
 * Group}).
 *
 * <p>
 * Two engine bugs are covered:
 * </p>
 * <ol>
 * <li><b>{@code --} wildcard resolution.</b> {@code Grouping_Variables} are stored in {@code --}
 * wildcard form ({@code --TESTCD}); the table carries the domain-resolved column
 * ({@code LBTESTCD}). Before the fix, {@code executeGrouped} built the grouping index over the raw
 * {@code --} names, so {@code createIndex} found no matching column, returned {@code null}, and the
 * rule silently produced <b>zero</b> violations on every dataset.</li>
 * <li><b>Silently dropping unavailable grouping columns (Python parity).</b> A grouping column
 * absent from the dataset (e.g. the permissible {@code --SCAT}) must be dropped — grouping by the
 * remaining present columns — rather than nulling the whole index. When none remain, the whole
 * dataset is a single group.</li>
 * </ol>
 *
 * <p>
 * The companion {@code .cdt} scenario {@code CORE-000699-invalid-LB} exercises (1) end-to-end;
 * these tests add direct, isolated coverage of both the wildcard resolution and the present-column
 * filter, including the all-absent case — which since EC-44 / Fix #134 is ONE consistency class
 * over the whole dataset rather than the empty verdict the operator's own guard used to return.
 * </p>
 */
class Core000699GroupSensitivityTest
{

    private static Rule core000699() throws Exception
    {
        Path rules = Path.of(System.getProperty("projectBasedir", "."),
                "src/test/resources/fixtures/rules/packages", "rules-sdtmig-3-4.json");
        RulePackage pkg = RulePackageLoader.loadCombined(rules);
        Rule rule = pkg.getRules().values().stream().filter(
                r -> r != null && r.getCore() != null && "CORE-000699".equals(r.getCore().getId()))
                .findFirst().orElseThrow();
        // The shipped rule is Sensitivity Record since 2026-07-28 (all inconsistent rows are
        // reported individually). This class pins the executeGrouped machinery (wildcard
        // grouping resolution, present-column filtering, group verdicts), so re-impose the
        // Group shape on the loaded copy.
        rule.setSensitivity(net.cumba.cdisc.core.model.Sensitivity.GROUP);
        rule.setGroupingVariables(
                java.util.List.of("--TESTCD", "--CAT", "--SCAT", "--SPEC", "--METHOD"));
        return rule;
    }


    /**
     * Builds a 2-row LB table from an ordered column→(row0,row1) map. {@code LBSEQ} is a LONG
     * column; everything else is STRING. The two {@code LBSTRESU} values are intentionally
     * inconsistent (g/L vs mg/dL) so any rule that groups the two rows together fires.
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
        RuleExecutionResult res = RuleRunner.execute(rule, table, _ -> null, "LB", null);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), "rule should execute");
        return res.getViolationCount();
    }


    @Test
    void wildcardGroupingVariablesResolveToDomainColumns() throws Exception
    {
        // All five grouping columns present (domain-resolved); LBSTAT absent. Before the fix the
        // raw --TESTCD grouping names matched no column -> createIndex null -> 0 violations.
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
        cols.put("LBSCAT", new String[]
        {
                "GEN", "GEN"
        });
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
        // One failing group (the two rows share all grouping keys) -> one group-level violation.
        assertEquals(1, run(core000699(), lb(cols)));
    }


    @Test
    void missingGroupingColumnIsDroppedNotZeroed() throws Exception
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
        assertEquals(1, run(core000699(), lb(cols)));
    }


    @Test
    void groupFiresWhenAnyRowFlagged_majorityFirst() throws Exception
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
        assertEquals(1, run(core000699(), lb(cols)));
    }


    @Test
    void allGroupingColumnsAbsentIsOneConsistencyClass() throws Exception
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
        assertEquals(1, run(core000699(), lb(cols)));
    }
}
