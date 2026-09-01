package net.cumba.cdisc.core.metadata;

import static net.cumba.cdisc.core.metadata.AdamDataStructureDetector.BDS;
import static net.cumba.cdisc.core.metadata.AdamDataStructureDetector.OCCDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fix #118: {@link AdamSubclassDetector} — house heuristic tier (signatures confirmed 2026-07-26),
 * hardened by Fix #140 / EC-50: {@code ADVERSE EVENT} accepts either AE topic variable, popPK needs
 * a NONMEM control column on top of the &ge;3-column signature, and no subclass is keyed off a
 * dataset name any more.
 */
class AdamSubclassDetectorTest
{

    /**
     * Fix #179: the detector's structure argument is the dataset's structure <b>set</b>
     * ({@link AdamDataStructureDetector#detectAll}), not a single token — these are the sets a
     * plain (non-device) dataset carries, so every assertion below reads exactly as it did before
     * the set landed. The device-variant sets, and the fact that they answer identically, are
     * pinned in {@link AdamSubclassDetectorStructureSetTest}.
     */
    private static final List<String> BDS_SET = List.of(BDS);

    private static final List<String> OCCDS_SET = List.of(OCCDS);

    private static final List<String> ADSL_SET = List.of(AdamDataStructureDetector.ADSL);

    private static final List<String> OTHER_SET = List.of(AdamDataStructureDetector.OTHER);

    /** No structure could be determined at all. */
    private static final List<String> NO_STRUCTURE = List.of();

    @Test
    void tteByCnsr_requiresBds()
    {
        assertEquals(AdamSubclassDetector.TIME_TO_EVENT,
                AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "AVAL", "CNSR")));
        // CNSR without BDS structure is not TTE.
        assertNull(AdamSubclassDetector.detect(OCCDS_SET, List.of("CNSR")));
        assertNull(AdamSubclassDetector.detect(NO_STRUCTURE, List.of("CNSR")));
    }


    @Test
    void medicalDeviceTteWinsOverTte()
    {
        assertEquals(AdamSubclassDetector.MEDICAL_DEVICE_TIME_TO_EVENT, AdamSubclassDetector
                .detect(BDS_SET, List.of("PARAMCD", "AVAL", "CNSR", "SPDEVID")));
    }


    @Test
    void popPkByNonmemControlColumns()
    {
        // Fix #140: the name test is gone — an ADPPK-named dataset without the signature is not
        // popPK, on either engine.
        assertNull(AdamSubclassDetector.detect(OTHER_SET, List.of("STUDYID")));
        // >= 3 NONMEM-signature columns INCLUDING a control column (EVID/MDV), and BDS structure.
        assertEquals(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS,
                AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "DV", "MDV", "AMT")));
        // Only 2 hits — not popPK.
        assertNull(AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "DV", "AMT")));
        // Fix #140: 3 hits but NO control column — the generic DV/AMT/CMT trio no longer qualifies.
        assertNull(AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "DV", "AMT", "CMT")));
        // Fix #140: the NONMEM signature without BDS structure is not popPK either.
        assertNull(AdamSubclassDetector.detect(OTHER_SET, List.of("DV", "MDV", "AMT")));
    }


    @Test
    void ncaByRelativeTimeColumns_requiresBds()
    {
        // Fix #140: the ADNCA name test is gone — the columns decide.
        assertNull(AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "AVAL")));
        assertEquals(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS,
                AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "NFRLT", "AFRLT")));
        // One relative-time column is not enough.
        assertNull(AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "NFRLT")));
        // Relative-time columns without BDS structure are not NCA.
        assertNull(AdamSubclassDetector.detect(OTHER_SET, List.of("NFRLT", "AFRLT")));
    }


    @Test
    void popPkOrderedBeforeNca()
    {
        // ADPPK datasets routinely carry the relative-time columns too — popPK must win.
        assertEquals(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS, AdamSubclassDetector
                .detect(BDS_SET, List.of("PARAMCD", "NFRLT", "AFRLT", "DV", "MDV", "AMT")));
        // Fix #140: …but an NCA dataset carrying the generic NONMEM-ish columns WITHOUT a control
        // column is NCA, not popPK. This is the shadowing defect the control-column gate fixes.
        assertEquals(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS, AdamSubclassDetector
                .detect(BDS_SET, List.of("PARAMCD", "NFRLT", "AFRLT", "DV", "AMT", "CMT")));
    }

    // ---- The BDS branches are non-exclusive: the ORDER is the tie-break rule ------


    /**
     * ⚑⚑ <b>THE AMBIGUITY PIN (wave 19 / {@code PLAN-adam-subclass-ambiguity.md}).</b> The three
     * BDS branches key on different, <em>non-exclusive</em> column sets, so a dataset can satisfy
     * several at once and the first-match-wins chain resolves it by source order. That order is
     * deliberate — {@code CNSR} is ADaMIG-required in a time-to-event dataset and appears in no
     * other ADaM structure's signature, whereas the popPK signature leans on the generic
     * {@code DV}/{@code AMT}/{@code CMT} trio — and this test is what makes it enforceable rather
     * than a comment.
     *
     * <p>
     * ⚠⚠ <b>Neuter-and-watch.</b> Move the {@code CNSR} branch in
     * {@link AdamSubclassDetector#detect} below either of the other two BDS branches and the
     * matching assertion below reddens. Every fixture here carries <b>both</b> signatures at once —
     * a fixture carrying only one would pass under any ordering and pin nothing. The paired
     * {@code without CNSR} assertions are the other half of the proof: they show the competing
     * branch really would have claimed the very same dataset, so the first assertion is a genuine
     * precedence result and not just "branch 2 never matched".
     * </p>
     */
    @Test
    void tteOrderedBeforePopPkAndNca_deliberate()
    {
        // Branch 1 vs branch 2 — the full 5-of-5 NONMEM signature, both control columns, plus CNSR.
        List<String> tteAndPopPk = List.of("PARAMCD", "AVAL", "CNSR", "DV", "MDV", "AMT", "EVID",
                "CMT");
        assertEquals(AdamSubclassDetector.TIME_TO_EVENT,
                AdamSubclassDetector.detect(BDS_SET, tteAndPopPk),
                "CNSR outranks the NONMEM signature — reorder the branches and this flips");
        assertEquals(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS,
                AdamSubclassDetector.detect(BDS_SET,
                        List.of("PARAMCD", "AVAL", "DV", "MDV", "AMT", "EVID", "CMT")),
                "control: without CNSR the very same dataset IS popPK, so branch 2 was live");

        // Branch 1 vs branch 3 — the full 4-of-4 relative-time signature plus CNSR.
        List<String> tteAndNca = List.of("PARAMCD", "AVAL", "CNSR", "NFRLT", "AFRLT", "ARRLT",
                "NRRLT");
        assertEquals(AdamSubclassDetector.TIME_TO_EVENT,
                AdamSubclassDetector.detect(BDS_SET, tteAndNca),
                "CNSR outranks the relative-time signature");
        assertEquals(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS,
                AdamSubclassDetector.detect(BDS_SET,
                        List.of("PARAMCD", "AVAL", "NFRLT", "AFRLT", "ARRLT", "NRRLT")),
                "control: without CNSR the very same dataset IS NCA, so branch 3 was live");

        // All three branches satisfied simultaneously.
        List<String> allThree = List.of("PARAMCD", "AVAL", "CNSR", "DV", "MDV", "AMT", "EVID",
                "CMT", "NFRLT", "AFRLT");
        assertEquals(AdamSubclassDetector.TIME_TO_EVENT,
                AdamSubclassDetector.detect(BDS_SET, allThree));
        // …and the device sub-branch of branch 1 keeps its own precedence inside the ambiguity.
        assertEquals(AdamSubclassDetector.MEDICAL_DEVICE_TIME_TO_EVENT, AdamSubclassDetector.detect(
                BDS_SET, List.of("PARAMCD", "AVAL", "CNSR", "SPDEVID", "DV", "MDV", "AMT")));
    }


    /**
     * ⚑ <b>The tier-order pin</b> — what makes the plan's reachability claim durable rather than a
     * comment. {@link AdamSubclassDetector#resolve} puts the <em>declared</em> subclass above the
     * column heuristic whenever {@code corej.defineFirst} holds, and that preference defaults to
     * {@code true} (Fix #154). So declaring the subclass in the Define-XML settles the ambiguity
     * outright, and the branch order below only decides for undeclared datasets.
     *
     * <p>
     * ⚠ Non-vacuous by construction: the same call with {@code defineFirst=false} returns
     * {@link AdamSubclassDetector#TIME_TO_EVENT}, i.e. the declaration is genuinely doing the work
     * in the first assertion. The property is cleared and restored so the assertion on the
     * <em>default</em> cannot be poisoned by another test's {@code -Dcorej.defineFirst}.
     * </p>
     */
    @Test
    void resolve_declaredSubclassShieldsTheAmbiguousShape()
    {
        List<String> ambiguous = List.of("PARAMCD", "AVAL", "CNSR", "NFRLT", "AFRLT", "ARRLT");
        List<String> declaredNca = List.of(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS);
        String saved = System.getProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY);
        try
        {
            System.clearProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY);
            assertTrue(AdamDataStructureDetector.defineFirstPreference(),
                    "Fix #154: define-first is the DEFAULT — the whole reachability argument"
                            + " rests on it");
            assertEquals(declaredNca,
                    AdamSubclassDetector.resolve("ADNCA", BDS_SET, ambiguous, declaredNca,
                            AdamDataStructureDetector.defineFirstPreference()),
                    "tier 1 wins outright: the column ambiguity never gets to decide");
        }
        finally
        {
            if (saved == null)
            {
                System.clearProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY);
            }
            else
            {
                System.setProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY, saved);
            }
        }
        // …and under the documented opt-out the columns decide, so branch 1 claims it after all.
        assertEquals(List.of(AdamSubclassDetector.TIME_TO_EVENT),
                AdamSubclassDetector.resolve("ADNCA", BDS_SET, ambiguous, declaredNca, false),
                "columns-first: the ambiguity resolves to TIME-TO-EVENT — this is the shape the"
                        + " declaration was shielding");
        // Undeclared is the reachable case, in either preference mode.
        assertEquals(List.of(AdamSubclassDetector.TIME_TO_EVENT),
                AdamSubclassDetector.resolve("ADNCA", BDS_SET, ambiguous, List.of(), true));
    }


    @Test
    void adverseEventByEitherTopicVariable_requiresOccds()
    {
        assertEquals(AdamSubclassDetector.ADVERSE_EVENT,
                AdamSubclassDetector.detect(OCCDS_SET, List.of("AETERM", "AEDECOD")));
        // Fix #140: either topic variable alone is enough — this is what lets CDISC-AD0261
        // (AEDECOD absent) and CDISC-AD0620 (AETERM absent) still be selected and fire.
        assertEquals(AdamSubclassDetector.ADVERSE_EVENT,
                AdamSubclassDetector.detect(OCCDS_SET, List.of("AETERM", "AESEQ")));
        assertEquals(AdamSubclassDetector.ADVERSE_EVENT,
                AdamSubclassDetector.detect(OCCDS_SET, List.of("AEDECOD", "AESEQ")));
        // …but the widening does not reach past the AE prefix: a coded medical-history or
        // concomitant-medication OCCDS dataset detects nothing.
        assertNull(AdamSubclassDetector.detect(OCCDS_SET, List.of("MHTERM", "MHDECOD")));
        assertNull(AdamSubclassDetector.detect(OCCDS_SET, List.of("CMTRT", "CMDECOD")));
        // An AE topic variable without OCCDS structure is not ADVERSE EVENT.
        assertNull(AdamSubclassDetector.detect(BDS_SET, List.of("AEDECOD", "AVAL")));
    }


    @Test
    void resolve_defineFirstPrecedence()
    {
        // defineFirst=true: recognised declared tokens win (unrecognised are dropped).
        assertEquals(List.of(AdamSubclassDetector.TIME_TO_EVENT),
                AdamSubclassDetector.resolve(BDS_SET, List.of("PARAMCD", "AVAL"),
                        List.of("Time-To-Event".toUpperCase(java.util.Locale.ROOT), "BOGUS"),
                        true));
        // defineFirst=false: the heuristic decides when it finds something …
        assertEquals(List.of(AdamSubclassDetector.TIME_TO_EVENT),
                AdamSubclassDetector.resolve(BDS_SET, List.of("PARAMCD", "AVAL", "CNSR"),
                        List.of(AdamSubclassDetector.ADVERSE_EVENT), false));
        // … and the declaration fills in when the heuristic yields nothing.
        assertEquals(List.of(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS),
                AdamSubclassDetector.resolve(BDS_SET, List.of("PARAMCD", "AVAL"),
                        List.of(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS), false));
        // Multiple recognised declarations survive as a set, in order, deduplicated (defineFirst).
        // ⚠ Both tokens must be applicable to the DETECTED structure: since the plan's Phase 4 a
        // declaration is validated against it and dropped with a WARN otherwise. This case used to
        // read ["ADVERSE EVENT", "TIME-TO-EVENT", "ADVERSE EVENT"] on a BDS structure set — an
        // occurrence subclass on a basic-data-structure dataset, which the declared tier now
        // refuses (see AdamSubclassSupertypeTest).
        assertEquals(
                List.of(AdamSubclassDetector.TIME_TO_EVENT,
                        AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS),
                AdamSubclassDetector.resolve(BDS_SET, List.of(),
                        List.of("TIME-TO-EVENT", "NON-COMPARTMENTAL ANALYSIS", "TIME-TO-EVENT"),
                        true));
        // Nothing declared, nothing detected: empty.
        assertEquals(List.of(),
                AdamSubclassDetector.resolve(BDS_SET, List.of("PARAMCD"), List.of(), true));
        // Fix #140: a define-declared subclass is how a dataset the heuristic cannot reach (e.g.
        // ADPPT, which carries no NONMEM columns) still satisfies a Subclasses-scoped rule.
        assertEquals(List.of(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS),
                AdamSubclassDetector.resolve(BDS_SET, List.of("PARAMCD", "AVAL"),
                        List.of(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS), false));
    }

    // ---- Fix #154: the last-resort dataset-name tier -----------------------------


    @Test
    void detectByName_popPkAndNca_requireBds()
    {
        for (String name : List.of("ADPPK", "ADPPK01", "adppt", "ADPPT ", "ADPOPPK"))
        {
            assertEquals(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS,
                    AdamSubclassDetector.detectByName(name, BDS_SET), name);
        }
        assertEquals(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS,
                AdamSubclassDetector.detectByName("ADNCA01", BDS_SET));
        // Not BDS ⇒ no name hit, however suggestive the name.
        assertNull(AdamSubclassDetector.detectByName("ADPPK", OCCDS_SET));
        assertNull(AdamSubclassDetector.detectByName("ADPPK", OTHER_SET));
        assertNull(AdamSubclassDetector.detectByName("ADPPK", NO_STRUCTURE));
        // Names outside both prefix sets, and a null name.
        assertNull(AdamSubclassDetector.detectByName("ADPK01", BDS_SET));
        assertNull(AdamSubclassDetector.detectByName("ADLBC", BDS_SET));
        assertNull(AdamSubclassDetector.detectByName(null, BDS_SET));
    }


    /**
     * ⚠ The load-bearing ordering constraint. {@code ADPPK} datasets routinely carry the
     * relative-time columns too, so a name tier ABOVE the columns would flip an {@code ADPPK}-named
     * NCA dataset into popPK and cost it its own rules. Note the columns here are the NCA signature
     * <em>without</em> a NONMEM control column, so the popPK column tier cannot claim it either —
     * only the tier under test can decide the verdict.
     */
    @Test
    void resolve_nameTierSitsBelowTheColumns()
    {
        List<String> ncaColumns = List.of("PARAMCD", "AVAL", "NFRLT", "AFRLT");
        // The name says popPK …
        assertEquals(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS,
                AdamSubclassDetector.detectByName("ADPPK", BDS_SET));
        // … the columns say NCA, and the columns win.
        assertEquals(List.of(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS),
                AdamSubclassDetector.resolve("ADPPK", BDS_SET, ncaColumns, List.of(), true));
    }


    /**
     * ⚠ And below the declaration, in BOTH preference modes: the name is local-only evidence, used
     * when neither Define-XML nor the metadata library has anything to say.
     */
    @Test
    void resolve_nameTierSitsBelowTheDeclaration()
    {
        List<String> silentColumns = List.of("PARAMCD", "AVAL");
        List<String> declaredNca = List.of(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS);

        assertEquals(declaredNca,
                AdamSubclassDetector.resolve("ADPPK", BDS_SET, silentColumns, declaredNca, true));
        assertEquals(declaredNca,
                AdamSubclassDetector.resolve("ADPPK", BDS_SET, silentColumns, declaredNca, false));
        // Nothing declared at all ⇒ the name tier is finally reached.
        assertEquals(List.of(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS),
                AdamSubclassDetector.resolve("ADPPK", BDS_SET, silentColumns, List.of(), true));
        // An unrecognised declaration is not a declaration — the name tier still applies.
        assertEquals(List.of(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS),
                AdamSubclassDetector.resolve("ADPPK", BDS_SET, silentColumns, List.of("BOGUS"),
                        true));
    }


    /**
     * Fix #154 must not change the four-argument overload the parity harness ({@code SpecRunner})
     * calls — it passes no dataset name, so the name tier is off and both lanes still see the same
     * evidence.
     */
    @Test
    void resolve_fourArgOverload_hasNoNameTier()
    {
        assertEquals(List.of(),
                AdamSubclassDetector.resolve(BDS_SET, List.of("PARAMCD", "AVAL"), List.of(), true));
        assertNull(AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "AVAL")));
    }


    @Test
    void noSubclass_isNull()
    {
        assertNull(AdamSubclassDetector.detect(BDS_SET, List.of("PARAMCD", "AVAL")));
        assertNull(AdamSubclassDetector.detect(ADSL_SET, List.of("STUDYID", "USUBJID")));
        assertNull(AdamSubclassDetector.detect(NO_STRUCTURE, null));
    }

}
