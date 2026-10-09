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
 * A {@code date()} probe against a COMPILED binding's set, seen from a rule
 * ({@code PLAN-engine-gap-temporal-membership} §5, D81 for every set shape; runbook W2 TM1): a
 * per-row {@code group=} set is read per row, a dataset-level one once, and each member is compared
 * by the hull rule - the rule-B shape of the plan ({@code date(PCRFTDTC) not in $ex_dates} over
 * {@code distinct(EXSTDTC, group=[USUBJID])}). Before TM1 both answered by text, so a date-only
 * probe never matched a datetime dose of the same day.
 */
class TemporalBoundMembershipRuleTest
{

    /** D: the reference date; E: a dose date/time per record, grouped by subject. */
    private static IDataTable pc()
    {
        return RealTables.of("PC").str("USUBJID", "S1", "S1", "S2", "S2")
                .str("D", "2020-01-01", "2020-01-02", "2020-03-01", null)
                .str("E", "2020-01-01T09:15", "2020-01-05T08:00", "2020-03-01", "2020-03-02T10:00")
                .build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static List<Long> fired(String check, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-TM1\"},\"Sensitivity\":\"Record\"");
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
        String ruleJson = b.append(",\"Check\":{\"expression\":\"")
                .append(esc("not empty(USUBJID) and (" + check + ")"))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}")
                .toString();
        Rule r;
        try
        {
            r = RulePackageLoader
                    .loadFromString(
                            KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + ruleJson + "}}"))
                    .getRules().get("R");
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
        assertNotNull(r);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = pc();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary), primary.getMetaData().getName(), null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> ruleJson + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    @Test
    void aPerRowGroupSetIsReadPerRowByTheHullRule()
    {
        // S1's doses {2020-01-01T09:15, 2020-01-05T08:00}, S2's {2020-03-01, 2020-03-02T10:00}
        String[] doses =
        {
                "$doses", "distinct(E, group=[USUBJID])"
        };
        assertEquals(List.of(1L, 3L), fired("date(D) in $doses", doses),
                "row 1 (2020-01-01) is the day of S1's first dose; row 3 is S2's dose itself");
        assertEquals(List.of(2L, 4L), fired("date(D) not in $doses", doses),
                "row 2 is no dose day; row 4's blank probe is a member of nothing");
        assertEquals(List.of(1L, 3L), fired("date(D) in distinct(E, group=[USUBJID])"),
                "the inline call answers as its binding");
    }


    /**
     * Runbook W4 TM2 (a pin): a {@code date()} probe against a {@code list<number>} binding is the
     * rule's load error — stage A's armed {@code PARAMETER_TYPE} ({@code membership}), never a
     * per-row comparison of a date with numbers.
     */
    @Test
    void aDateProbeAgainstAListOfNumbersIsALoadError()
    {
        String error = SequenceFunctionTest.loadError("date(D) in $n", "$n", "[1, 2]");
        assertTrue(error.contains("disagrees with list<number>"), error);
        assertEquals("null", SequenceFunctionTest.loadError("date(D) in [date(\"2020-01-01\")]"));
    }


    @Test
    void aDatasetLevelSetIsReadOnceByTheHullRule()
    {
        String[] all =
        {
                "$all", "distinct(E)"
        };
        assertEquals(List.of(1L, 3L), fired("date(D) in $all", all),
                "row 1 matches 2020-01-01T09:15 at day precision - text would match row 3 only");
        assertEquals(List.of(2L, 4L), fired("date(D) not in $all", all));
    }
}
