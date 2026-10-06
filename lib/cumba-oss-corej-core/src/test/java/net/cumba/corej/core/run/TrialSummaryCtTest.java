package net.cumba.corej.core.run;

import static net.cumba.corej.core.run.TsTestSupport.row;
import static net.cumba.corej.core.run.TsTestSupport.sdtmTs;
import static net.cumba.corej.core.run.TsTestSupport.sendTs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * PLAN-ct-packages-default-and-ts-source C5 / C10 — {@link TrialSummaryCt}: which TS rows name the
 * run's CT (CTD-2, CQ1 (a), CQ3 (a)), the strict date parse (CQ4 (a), fold F3), the merge order,
 * and the case-insensitive column lookup.
 */
class TrialSummaryCtTest
{

    private static List<String> ids(CtSelection.TrialSummaryEvidence aEvidence)
    {
        return aEvidence.refs().stream().map(CtSelection.TsRef::packageId).toList();
    }

    // ------------------------------------------------------------------
    // SDTM: TSVCDREF / TSVCDVER
    // ------------------------------------------------------------------


    @Test
    void cdiscCtRowsNameTheRunRootWithTheirVersion()
    {
        IDataTable ts = sdtmTs(List.of(row("S1", "TCNTRL", "PLACEBO", "CDISC CT", "2023-12-15"),
                row("S1", "AGEU", "YEARS", "CDISC CT", "2023-12-15")));

        CtSelection.TrialSummaryEvidence ev = TrialSummaryCt.read(ts, "sdtmct");

        assertEquals(List.of("sdtmct-2023-12-15"), ids(ev), "one package per distinct version");
        assertEquals("TSVCDREF \"CDISC CT\", TSVCDVER \"2023-12-15\"", ev.refs().get(0).evidence());
        assertEquals(List.of(), ev.ignored());
    }


    /** CQ3 (a): exactly the {@code get_codelist_attributes} predicate — "CDISC" / "CDISC CT". */
    @Test
    void onlyTheSharedCdiscCtPredicateCounts()
    {
        IDataTable ts = sdtmTs(List.of(row("S1", "A", "x", " CDISC ", "2024-03-29"),
                row("S1", "B", "x", "cdisc ct", "2022-01-01"),
                row("S1", "C", "x", "CDISC CT 2", "2021-01-01"),
                row("S1", "D", "x", "UNII", "2020-01-01"), row("S1", "E", "x", "MedDRA", "26.0")));

        CtSelection.TrialSummaryEvidence ev = TrialSummaryCt.read(ts, "sdtmct");

        assertEquals(List.of("sdtmct-2024-03-29"), ids(ev));
        assertEquals(List.of(), ev.ignored(),
                "a non-CDISC reference is not CT this source reads — never listed as ignored");
    }


    /** CTD-2: several distinct dates are all kept, merged newest first (D1 "never first-wins"). */
    @Test
    void severalDatesMergeNewestFirst()
    {
        IDataTable ts = sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "2022-12-16"),
                row("S1", "B", "x", "CDISC", "2024-09-27"),
                row("S1", "C", "x", "CDISC CT", "2023-12-15"),
                row("S1", "D", "x", "CDISC CT", "2024-09-27")));

        assertEquals(List.of("sdtmct-2024-09-27", "sdtmct-2023-12-15", "sdtmct-2022-12-16"),
                ids(TrialSummaryCt.read(ts, "sdtmct")));
    }


    /**
     * CQ4 (a) + fold F3: a value that is not one valid ISO date is ignored and listed, never taken
     * — including the well-shaped but impossible {@code 2023-02-30}; a blank version is no
     * statement at all.
     */
    @Test
    void malformedVersionsAreIgnoredAndListed_blankOnesAreNotStatements()
    {
        IDataTable ts = sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "2023-12"),
                row("S1", "B", "x", "CDISC CT", "Dec 2023"),
                row("S1", "C", "x", "CDISC CT", "2023-02-30"),
                row("S1", "D", "x", "CDISC CT", "  "), row("S1", "E", "x", "CDISC CT", null),
                row("S1", "F", "x", null, "2023-12-15")));

        CtSelection.TrialSummaryEvidence ev = TrialSummaryCt.read(ts, "sdtmct");

        assertEquals(List.of(), ids(ev));
        assertEquals(List.of("TSVCDREF \"CDISC CT\", TSVCDVER \"2023-12\"",
                "TSVCDREF \"CDISC CT\", TSVCDVER \"Dec 2023\"",
                "TSVCDREF \"CDISC CT\", TSVCDVER \"2023-02-30\""), ev.ignored());
    }


    /**
     * Owner ruling 2026-09-28: names match case-insensitively. The table here is
     * {@code columnNameCaseSensitive}, so {@code getColumnIndex("TSVCDREF")} would miss the
     * lower-case column — the reader must walk the names itself.
     */
    @Test
    void columnsAreFoundCaseInsensitivelyEvenInACaseSensitiveTable()
    {
        IDataTable ts = TsTestSupport.table("ts", true,
                List.of("studyid", "tsparmcd", "tsval", "TsVcdRef", "tsvcdver"),
                List.of(row("S1", "A", "x", "CDISC CT", "2023-12-15")));
        assertEquals(-1, ts.getMetaData().getColumnIndex("TSVCDREF"),
                "control: the table really is case-sensitive");

        assertEquals(List.of("sdtmct-2023-12-15"), ids(TrialSummaryCt.read(ts, "sdtmct")));
    }


    /** CQ6: a TS without the CT columns (an SDTMIG 3.1.2 TS) names nothing and lists nothing. */
    @Test
    void aTsWithoutTheCtColumnsNamesNothing()
    {
        IDataTable ts = TsTestSupport.table("TS", false, List.of("STUDYID", "TSPARMCD", "TSVAL"),
                List.of(row("S1", "SNDCTVER", "2024-09-27")));

        assertEquals(CtSelection.TrialSummaryEvidence.EMPTY, TrialSummaryCt.read(ts, "sdtmct"),
                "an SDTM run never reads SNDCTVER");
    }

    // ------------------------------------------------------------------
    // SEND: SNDCTVER (CQ1 (a)), and sponsor-added TSVCDREF rows
    // ------------------------------------------------------------------


    /** P0-measured spellings: the bare date and the "SEND CT <date>" prefix form. */
    @Test
    void sndctverTsvalYieldsTheSendRoot_bareOrPrefixed()
    {
        IDataTable ts = sendTs(List.of(row("S1", "SNDCTVER", "SEND CT 2024-09-27"),
                row("S1", "SNDCTVER", "2023-12-15"), row("S1", "SNDIGVER", "SENDIG 3.1.1"),
                row("S1", "SPECIES", "RAT")));

        CtSelection.TrialSummaryEvidence ev = TrialSummaryCt.read(ts, "sendct");

        assertEquals(List.of("sendct-2024-09-27", "sendct-2023-12-15"), ids(ev));
        assertEquals("TSPARMCD \"SNDCTVER\", TSVAL \"SEND CT 2024-09-27\"",
                ev.refs().get(0).evidence());
    }


    /** CQ4 on the SEND family: no date, two dates, or an impossible one — ignored and listed. */
    @Test
    void sndctverWithoutExactlyOneValidDateIsIgnored()
    {
        IDataTable ts = sendTs(List.of(row("S1", "SNDCTVER", "SEND CT Dec 2023"),
                row("S1", "SNDCTVER", "2023-12-15 / 2024-03-29"),
                row("S1", "SNDCTVER", "SEND CT 2023-02-30"), row("S1", "SNDCTVER", "")));

        CtSelection.TrialSummaryEvidence ev = TrialSummaryCt.read(ts, "sendct");

        assertEquals(List.of(), ids(ev));
        assertEquals(3, ev.ignored().size(), ev.ignored().toString());
        assertTrue(ev.ignored().get(0).contains("SEND CT Dec 2023"), ev.ignored().toString());
    }


    /** CQ1 (a): TSVCDREF rows a sponsor added to a SEND TS are honoured too, under sendct. */
    @Test
    void aSendRunAlsoHonoursTsvcdrefRows()
    {
        IDataTable ts = sdtmTs(List.of(row("S1", "SNDCTVER", "2023-12-15", null, null),
                row("S1", "SPECIES", "RAT", "CDISC CT", "2024-03-29")));

        assertEquals(List.of("sendct-2024-03-29", "sendct-2023-12-15"),
                ids(TrialSummaryCt.read(ts, "sendct")));
    }


    /** CQ2 (a): a SEND date never names an sdtmct companion, and an SDTM run never reads it. */
    @Test
    void theSendFamilyIsReadOnlyForTheSendRoot()
    {
        IDataTable ts = sendTs(List.of(row("S1", "SNDCTVER", "2023-12-15")));

        assertEquals(List.of("sendct-2023-12-15"), ids(TrialSummaryCt.read(ts, "sendct")));
        assertEquals(List.of(), ids(TrialSummaryCt.read(ts, "sdtmct")));
    }
}
