package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} phase 5 — the differential of §2.3: {@code colref} over a
 * computed name against the {@code ${…}} operand resolving to the same name, on the same tables, in
 * every position. They answer identically everywhere EXCEPT the one ruled divergence, which is
 * asserted AS a divergence rather than excluded: a numeric position over an ABSENT column whose
 * resolved name is not otherwise numeric-expected in the rule — {@code colref} MIS (owner Q14 (b),
 * the authored name's default at that position), {@code ${…}} {@code ""} (owner Q8, unchanged). The
 * same site with the resolved name numeric-expected elsewhere is identical (both MIS,
 * {@code substitutedScalarCell}'s resolved-name read).
 */
class DynamicColumnDifferentialTest
{

    /** APERIOD 3 names ADSL.AP03SDT, which ADSL does not carry. */
    private static IDataTable adae()
    {
        return RealTables.of("ADAE").str("USUBJID", "S1", "S1", "S2", "S1")
                .lng("APERIOD", 1L, 2L, 1L, 3L).dbl("APERSDT", 100.0, 200.0, 300.0, 400.0)
                .lng("N", 1L, 2L, 1L, 3L).dbl("P1V", 7.0, 7.0, 7.0, 7.0)
                .dbl("P2V", 8.0, 8.0, 8.0, 8.0).build();
    }


    private static IDataTable adsl()
    {
        return RealTables.of("ADSL").str("USUBJID", "S1", "S2").dbl("AP01SDT", 100.0, 300.0)
                .dbl("AP02SDT", 999.0, 5.0).build();
    }

    private static final String DYNAMIC = "colref(concat(\"ADSL.AP\", printf(\"%02d\", APERIOD),"
            + " \"SDT\"))";

    private static final String TEMPLATE = "ADSL.AP${APERIOD:%02d}SDT";

    private static final String DYNAMIC_BARE = "colref(concat(\"P\", printf(\"%d\", N), \"V\"))";

    private static final String TEMPLATE_BARE = "P${N:%d}V";

    private static List<Long> fired(String check)
    {
        String json = "{\"Core\":{\"Id\":\"T-DIFF\"},\"Sensitivity\":\"Record\","
                + "\"Match_Datasets\":[{\"Name\":\"ADSL\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_Type\":\"left\"}],\"Check\":{\"expression\":\""
                + ("not empty(USUBJID) and (" + check + ")").replace("\\", "\\\\").replace("\"",
                        "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}";
        Rule r;
        try
        {
            r = RulePackageLoader
                    .loadFromString(KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + json + "}}"))
                    .getRules().get("R");
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
        assertNotNull(r);
        assertNull(r.getLoadError(), r.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(r, adae(),
                RealTables.inventoryOf(adae(), adsl()), "ADAE", null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " → " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /** Both spellings of {@code shape} (%s = the operand) answer the same rows. */
    private static List<Long> assertIdentical(String shape, String dynamic, String template)
    {
        List<Long> viaTemplate = fired(shape.formatted(template));
        assertEquals(viaTemplate, fired(shape.formatted(dynamic)), shape);
        return viaTemplate;
    }


    @Test
    void theTwoSpellingsAnswerIdenticallyInEveryOtherPosition()
    {
        assertEquals(List.of(2L, 4L), assertIdentical("APERSDT != %s", DYNAMIC, TEMPLATE),
                "a present joined column is read typed by both; AP03SDT is absent (a string"
                        + " context: \"\" != 400)");
        assertIdentical("%s > 150", DYNAMIC, TEMPLATE);
        assertIdentical("%s == 5", DYNAMIC, TEMPLATE);
        assertIdentical("%s != \"\"", DYNAMIC, TEMPLATE);
        assertIdentical("%s > 7.5", DYNAMIC_BARE, TEMPLATE_BARE);
        assertIdentical("%s != \"\"", DYNAMIC_BARE, TEMPLATE_BARE);
    }


    /**
     * ⭐ The ruled divergence, asserted: row 4 names ADSL.AP03SDT (absent) in a numeric position
     * that no other position of the rule numeric-expects — {@code colref} gives MIS (fires,
     * {@code MIS < 3}), {@code ${…}} gives {@code ""} (does not).
     */
    @Test
    void theOneExpectedDivergenceIsNumericPositionOverAnAbsentColumn()
    {
        List<Long> template = fired(TEMPLATE + " < 3");
        List<Long> dynamic = fired(DYNAMIC + " < 3");
        assertEquals(List.of(), template, "${…}: \"\" < 3 is no violation (Q8, unchanged)");
        assertEquals(List.of(4L), dynamic, "colref: MIS < 3 holds (Q14 (b))");
        assertNotEquals(template, dynamic);
        List<Long> bareTemplate = fired(TEMPLATE_BARE + " < 3");
        List<Long> bareDynamic = fired(DYNAMIC_BARE + " < 3");
        assertEquals(List.of(), bareTemplate);
        assertEquals(List.of(4L), bareDynamic, "row 4 names P3V, absent from the primary");
    }


    /** The same site with the resolved name numeric-expected elsewhere: both MIS, identical. */
    @Test
    void theSameSiteIsIdenticalWhenTheResolvedNameIsNumericExpectedElsewhere()
    {
        assertEquals(List.of(4L),
                assertIdentical("%s < 3 or ADSL.AP03SDT > 1000000000", DYNAMIC, TEMPLATE));
        assertEquals(List.of(4L),
                assertIdentical("%s < 3 or P3V > 1000000000", DYNAMIC_BARE, TEMPLATE_BARE));
    }
}
