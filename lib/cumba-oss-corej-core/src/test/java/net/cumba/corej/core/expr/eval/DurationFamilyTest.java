package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RuleLanguage;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RealTables;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * The {@code duration} comparison family end to end ({@code PLAN-engine-gap-dur-order} §5 / §6.1,
 * runbook W1b DO; coordinator D6): {@code duration(x)} marks a comparison, routed by the MARKER at
 * compile time and never by a cell's content; {@code duration_between(start, end)} is a
 * duration-family hull operand; {@code earliest_possible} / {@code latest_possible} over a
 * {@code duration(…)} operand answer the hull bounds as point durations; the
 * {@code has_next_corresponding_record} relation takes the duration arm only when both operands are
 * written with the marker (Q3, ruled explicit).
 */
class DurationFamilyTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The planned-interval pairs — the text-order counterexamples of the plan's §3. */
    private static IDataTable intervals()
    {
        return RealTables.of("XX").str("DOMAIN", "XX", "XX", "XX", "XX", "XX", "XX")
                .str("STINT", "PT9H", "P2D", "P1M", "PT90M", "", "P3D")
                .str("ENINT", "PT10H", "PT5H", "P30D", "PT1H30M", "P1D", "P1D").build();
    }


    /** {@code --DTC} / {@code --ENDTC} / {@code --DUR} rows for the between-instants hull. */
    private static IDataTable spans()
    {
        return RealTables.of("XX").str("DOMAIN", "XX", "XX", "XX", "XX", "XX", "XX", "XX")
                .str("XXDTC", "2020-01-01T00:00", "2020-01-01T00:00", "2020-01", "2020-01",
                        "2020-01-01", "2020-01-05", "2020-01-01")
                .str("XXENDTC", "2020-01-02T12:00", "2020-01-02T12:00", "2020-02-15", "2020-02-15",
                        "2020-01-03", "2020-01-03", "")
                .str("XXDUR", "P1DT12H", "P2D", "P1M", "P60D", "P2D", "-P2D", "P1D").build();
    }


    /** TD-shaped rows: four anchors, {@code TDSTOFF} in {@code TDORDER} order. */
    private static IDataTable td()
    {
        return RealTables.of("TD")
                .str("DOMAIN", "TD", "TD", "TD", "TD", "TD", "TD", "TD", "TD", "TD")
                .str("TDANCVAR", "A", "A", "A", "B", "B", "C", "C", "D", "D")
                .dbl("TDORDER", 1d, 2d, 3d, 1d, 2d, 1d, 2d, 1d, 2d)
                .str("TDSTOFF", "P0D", "P60W", "P120W", "P60W", "P30D", "P1W", "P7D", "P1M", "P30D")
                .build();
    }


    @Test
    void theMarkerOrdersDurationsByTheirHullNeverAsText()
    {
        // text order says "PT9H" > "PT10H" and "P2D" < "PT5H"; the hull says the opposite. The
        // plain comparison cannot even be authored on two character columns (the R9 column-type
        // gate: an order comparison expects a number) — the marker is the only duration order
        RuleExecutionResult plain = RuleRunnerCalls.execute(load("STINT > ENINT"), intervals());
        assertEquals(RuleExecutionStatus.ERROR, plain.getStatus());
        assertTrue(String.valueOf(plain.getStatusMessage()).contains("column-type mismatch"),
                plain.getStatusMessage());
        assertEquals(List.of(2L, 6L), fired(intervals(), "duration(STINT) > duration(ENINT)"));
        assertEquals(List.of(2L, 4L, 6L), fired(intervals(), "duration(STINT) >= duration(ENINT)"),
                "PT90M >= PT1H30M");
        assertEquals(List.of(1L), fired(intervals(), "duration(STINT) < duration(ENINT)"));
        assertEquals(List.of(4L), fired(intervals(), "duration(STINT) == duration(ENINT)"));
        // != : P1M vs P30D is not decided; "" is unpositionable, so != fires on it (H1b)
        assertEquals(List.of(1L, 2L, 5L, 6L),
                fired(intervals(), "duration(STINT) != duration(ENINT)"));
        // one marker is enough — and a literal on the other side is read as a duration
        assertEquals(List.of(2L, 6L), fired(intervals(), "duration(STINT) > ENINT"));
        assertEquals(List.of(2L, 6L), fired(intervals(), "STINT > duration(ENINT)"));
        assertEquals(List.of(2L, 3L, 6L), fired(intervals(), "duration(STINT) > \"P1D\""));
    }


    @Test
    void stageATypesTheFamily()
    {
        assertEquals("duration", ExprType.Primitive.DURATION.describe());
        String mixed = loadError("duration(STINT) > date(ENINT)");
        assertTrue(mixed.contains("comparison operands disagree: duration GT date"), mixed);
        String number = loadError("duration(1) > duration(ENINT)");
        assertTrue(number.contains("duration() converts a string, not number"), number);
        assertNull(load("duration(STINT) > duration(ENINT)").getLoadError());
        assertNull(load("duration(STINT) > \"P1D\"").getLoadError(),
                "a string literal beside the marker is the date family's observe-only pair");
    }


    @Test
    void theBoundsOverAMarkedOperandAreTheHullsPointDurations()
    {
        // Q6: P1M = [P28D, P31D]; the bound is rendered as a point duration, so the outer marker
        // reads it exactly as the date precedent reads a date bound
        assertEquals(List.of(3L), fired(intervals(),
                "not empty(STINT) and duration(latest_possible(duration(STINT))) > \"P30D\""));
        assertEquals(List.of(3L),
                fired(intervals(), "earliest_possible(duration(STINT)) == \"P28D\""));
        assertEquals(List.of(3L),
                fired(intervals(), "latest_possible(duration(STINT)) == \"P31D\""));
        assertEquals(List.of(2L), fired(intervals(), "latest_possible(duration(STINT)) == \"P2D\""),
                "a point's bounds are the point");
        // an unpositionable cell has no bound: the computed missing
        assertEquals(List.of(5L), fired(intervals(), "empty(earliest_possible(duration(STINT)))"));
        // ⭐ routed by the MARKER, never by content: without it the bound reads a DATE, and no
        // duration positions on the calendar — every row answers the computed missing
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                fired(intervals(), "empty(latest_possible(STINT))"));
    }


    @Test
    void durationBetweenIsAHullOperandOfTheFamily()
    {
        String populated = "not empty(XXDTC) and not empty(XXENDTC) and not empty(XXDUR) and ";
        // row E of the plan: --DUR must equal --ENDTC - --DTC — fires only where DEFINITELY
        // different: 1 agrees (P1DT12H); 2 disagrees (P2D); 3's hull [14 d, 46 d] meets P1M; 4's
        // P60D is disjoint from it; 5: two complete dates are a point, P2D agrees; 6: -P2D agrees
        assertEquals(List.of(2L, 4L),
                fired(spans(), populated + "duration(XXDUR) != duration_between(XXDTC, XXENDTC)"));
        assertEquals(List.of(1L, 5L, 6L),
                fired(spans(), populated + "duration(XXDUR) == duration_between(XXDTC, XXENDTC)"));
        // the hull routes the family from either side (D6), with a marker, a literal, a column
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                fired(spans(), populated + "duration_between(XXDTC, XXENDTC) > duration(\"P1D\")"));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                fired(spans(), populated + "duration_between(XXDTC, XXENDTC) > \"P1D\""));
        assertEquals(List.of(6L),
                fired(spans(), populated + "duration_between(XXDTC, XXENDTC) < \"P0D\""));
        assertEquals(List.of(2L, 4L),
                fired(spans(), populated + "XXDUR != duration_between(XXDTC, XXENDTC)"),
                "the hull operand alone selects the family");
        // the carrier: a point renders as a point duration, a range as lower/upper
        assertEquals(List.of(1L, 2L),
                fired(spans(), "duration_between(XXDTC, XXENDTC) == \"P1DT12H\""));
        assertEquals(List.of(5L), fired(spans(), "duration_between(XXDTC, XXENDTC) == \"P2D\""));
        // (a range is no point, so the family's == never holds on it: pin the text through str)
        assertEquals(List.of(3L, 4L), fired(spans(),
                "str(duration_between(XXDTC, XXENDTC)) == \"P14DT1S/P45DT23H59M59S\""));
        // a blank instant: the computed missing
        assertEquals(List.of(7L), fired(spans(), "empty(duration_between(XXDTC, XXENDTC))"));
        // Stage A: a duration
        String mixed = loadError("duration_between(XXDTC, XXENDTC) > date(XXDTC)");
        assertTrue(mixed.contains("comparison operands disagree: duration GT date"), mixed);
    }


    @Test
    void theRelationTakesTheDurationArmOnlyWhereBothOperandsAreMarked()
    {
        String tail = ", ordering=TDORDER, within=TDANCVAR";
        // row D of the plan (R-TD-012): TDSTOFF non-decreasing in TDORDER order. A conforms (the
        // IG's own P0D, P60W, P120W); B decreases; C's P1W / P7D are the same point; D's P1M is
        // not <= P30D
        assertEquals(List.of(4L, 8L), fired(td(), "not has_next_corresponding_record("
                + "duration(TDSTOFF), duration(TDSTOFF)" + tail + ", relation=\"<=\")"));
        assertEquals(List.of(1L, 2L, 8L), fired(td(), "not has_next_corresponding_record("
                + "duration(TDSTOFF), duration(TDSTOFF)" + tail + ", relation=\">=\")"));
        // without relation= the marker still reads the hull for identity: P1W corresponds to P7D
        assertEquals(List.of(1L, 2L, 4L, 8L),
                fired(td(), "not has_next_corresponding_record(duration(TDSTOFF), duration(TDSTOFF)"
                        + tail + ")"));
        // ⭐ UNMARKED, the relation keeps today's arm (Q3 ruled explicit): no duration positions on
        // the calendar, so every non-identical neighbour pair fires — the IG's conforming example
        // included. A content-sniffed duration arm in compareCells would silence rows 1, 2 and 6.
        assertEquals(List.of(1L, 2L, 4L, 6L, 8L),
                fired(td(), "not has_next_corresponding_record(TDSTOFF, TDSTOFF" + tail
                        + ", relation=\"<=\")"));
        assertEquals(List.of(1L, 2L, 4L, 6L, 8L),
                fired(td(), "not has_next_corresponding_record(TDSTOFF, TDSTOFF" + tail + ")"));
        // one side marked is a load error, never a silent text read
        for (String oneSided : List.of("duration(TDSTOFF), TDSTOFF", "TDSTOFF, duration(TDSTOFF)"))
        {
            String error = loadError(
                    "not has_next_corresponding_record(" + oneSided + tail + ", relation=\"<=\")");
            assertTrue(error.contains("duration("), error);
            assertTrue(error.contains("both"), error);
        }
    }


    @Test
    void theSurfaceListsTheFamily()
    {
        assertEquals(List.of("date", "date_part", "duration", "num", "time", "time_part"),
                new ArrayList<>(ExprCompiler.comparisonMarkers()));
        String surface = RuleLanguage.surface();
        assertTrue(surface.contains("\nmarkers: date date_part duration num time time_part\n"),
                "describe() lists the comparison markers (D6)");
        assertTrue(surface.contains("\nduration(x:unknown) kind=value\n"), surface);
        assertTrue(surface.contains("\nduration_between(start:unknown, end:unknown) kind=value\n"),
                surface);
    }

    // ---------------------------------------------------------------- plumbing


    private static List<Long> fired(IDataTable table, String check)
    {
        Rule rule = load(check);
        assertNull(rule.getLoadError(), () -> check + " -> " + rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String loadError(String check)
    {
        return String.valueOf(load(check).getLoadError());
    }


    private static Rule load(String check)
    {
        try
        {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("Core", Map.of("Id", "DO-TEST"));
            rule.put("Check", Map.of("expression", check));
            rule.put("Outcome", Map.of("Message", "m"));
            String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
            Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
            assertNotNull(loaded, "the rule parses");
            return loaded;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

}
