package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.function.Supplier;
import net.cumba.corej.core.gen.CtStandardRef;
import org.junit.jupiter.api.Test;

/**
 * PLAN-ct-packages-default-and-ts-source C7 / C10 — the four-argument
 * {@link CtSelection#resolve(List, List, java.util.Collection, Supplier)} in fold F1's five-step
 * order, and the "not read is literal" contract: the Trial Summary supplier is invoked only at step
 * 3.
 */
class CtSelectionTrialSummaryTest
{

    private static final List<String> SDTM_ROOTS = List.of("sdtmct");

    private static final CtSelection.TrialSummaryEvidence TS_2023 = new CtSelection.TrialSummaryEvidence(
            List.of(new CtSelection.TsRef("sdtmct-2023-12-15",
                    "TSVCDREF \"CDISC CT\", TSVCDVER \"2023-12-15\"")),
            List.of());

    /** A supplier that fails the test if the selection ever reads the TS. */
    private static CtSelection.TrialSummaryEvidence explode()
    {
        throw new AssertionError("the Trial Summary must not be read here");
    }


    /** Step 1: a user list wins outright; TS is not read (row 4 with a TS present). */
    @Test
    void step1_userListWins_tsNotRead()
    {
        CtSelection sel = CtSelection.resolve(List.of("sdtmct-2024-09-27"),
                List.of(CtStandardRef.of("SDTM", "2022-12-16")), SDTM_ROOTS,
                CtSelectionTrialSummaryTest::explode);

        assertEquals(CtSelection.Source.USER, sel.source());
        assertEquals(List.of("sdtmct-2024-09-27"), sel.packageIds());
        assertNull(sel.trialSummary());
    }


    /**
     * Step 2: a define naming a consumed root takes ALL its declared ids, exactly as today
     * (unconsumed-root-ignored.cdt), and TS is not read.
     */
    @Test
    void step2_consumedRootDeclarationWins_allIdsKept_tsNotRead()
    {
        CtSelection sel = CtSelection.resolve(List.of(),
                List.of(CtStandardRef.of("CDASH", "2024-09-27"),
                        CtStandardRef.of("SDTM", "2024-09-27")),
                SDTM_ROOTS, CtSelectionTrialSummaryTest::explode);

        assertEquals(CtSelection.Source.DEFINE, sel.source());
        assertEquals(List.of("cdashct-2024-09-27", "sdtmct-2024-09-27"), sel.packageIds());
        assertNull(sel.trialSummary());
    }


    /** Step 3: blank field, no consumed-root declaration, TS states CT → TS, exactly its ids. */
    @Test
    void step3_trialSummarySupplies()
    {
        CtSelection sel = CtSelection.resolve(List.of(), List.of(), SDTM_ROOTS, () -> TS_2023);

        assertEquals(CtSelection.Source.TS, sel.source());
        assertEquals(List.of("sdtmct-2023-12-15"), sel.packageIds());
        assertSame(TS_2023, sel.trialSummary());
    }


    /**
     * CQ5 (a): a define declaring only roots the run does not consume does not put the define in
     * play — TS fills in, and define and TS ids are never mixed.
     */
    @Test
    void step3_unconsumedOnlyDefine_tsFillsIn_neverMixed()
    {
        CtSelection sel = CtSelection.resolve(List.of(),
                List.of(CtStandardRef.of("DEFINE-XML", "2024-03-29")), SDTM_ROOTS, () -> TS_2023);

        assertEquals(CtSelection.Source.TS, sel.source());
        assertEquals(List.of("sdtmct-2023-12-15"), sel.packageIds(), "no define id joins");
    }


    /** Step 4: unconsumed-only define, TS yields nothing → DEFINE, unchanged from today (T3). */
    @Test
    void step4_unconsumedOnlyDefine_noTsRefs_staysDefine()
    {
        CtSelection.TrialSummaryEvidence none = CtSelection.TrialSummaryEvidence.EMPTY;
        CtSelection sel = CtSelection.resolve(List.of(),
                List.of(CtStandardRef.of("DEFINE-XML", "2024-03-29")), SDTM_ROOTS, () -> none);

        assertEquals(CtSelection.Source.DEFINE, sel.source());
        assertEquals(List.of("define-xmlct-2024-03-29"), sel.packageIds());
        assertSame(none, sel.trialSummary(), "the read ran, so its evidence rides along (F2)");
    }


    /** Step 4 without a TS read at all: a run whose root never reads TS (ADaM, UNKNOWN). */
    @Test
    void step4_rootNeverReadsTs_staysDefine()
    {
        CtSelection sel = CtSelection.resolve(List.of(),
                List.of(CtStandardRef.of("SDTM", "2024-03-29")), List.of(), null);

        assertEquals(CtSelection.Source.DEFINE, sel.source());
        assertEquals(List.of("sdtmct-2024-03-29"), sel.packageIds());
        assertNull(sel.trialSummary());
    }


    /** Step 5: nothing anywhere → NONE; the evidence of a read that ran still rides along (F3). */
    @Test
    void step5_nothing_none()
    {
        CtSelection.TrialSummaryEvidence malformedOnly = new CtSelection.TrialSummaryEvidence(
                List.of(), List.of("TSVCDREF \"CDISC CT\", TSVCDVER \"2023-12\""));
        CtSelection sel = CtSelection.resolve(List.of(), List.of(), SDTM_ROOTS,
                () -> malformedOnly);

        assertEquals(CtSelection.Source.NONE, sel.source());
        assertEquals(List.of(), sel.packageIds());
        assertSame(malformedOnly, sel.trialSummary());
        assertEquals(CtSelection.Source.NONE,
                CtSelection.resolve(List.of(), List.of(), List.of(), null).source());
    }


    /** The two-argument form is unchanged: it never consults a TS. */
    @Test
    void twoArgumentFormIsUnchanged()
    {
        CtSelection sel = CtSelection.resolve(List.of(),
                List.of(CtStandardRef.of("DEFINE-XML", "2024-03-29")));
        assertEquals(CtSelection.Source.DEFINE, sel.source());
        assertNull(sel.trialSummary());
    }
}
