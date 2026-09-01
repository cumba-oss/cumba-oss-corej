package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>Phase 3a.1</b> — the device-token column heuristic
 * ({@link AdamDataStructureDetector#detectSpecific}) and the {@code SUPERTYPES} change that goes
 * with it.
 *
 * <p>
 * Until Phase 3a the three medical-device structure tokens were <b>declaration-only</b>: no
 * heuristic could produce them, so the 345 corpus entries that name them
 * ({@code MEDICAL DEVICE BASIC DATA STRUCTURE} 206, {@code DEVICE LEVEL ANALYSIS DATASET} 87,
 * {@code MEDICAL DEVICE OCCURRENCE DATA STRUCTURE} 52) could only ever match a dataset whose
 * Define-XML declared them. This class pins the heuristic that removes that blindness, and — more
 * importantly — pins what it must <em>not</em> do.
 * </p>
 *
 * <h2>⚠⚠ Neuter-and-watch (run in both directions, 2026-08-10)</h2>
 *
 * <ul>
 * <li><b>N1 — remove the refinement</b> ({@code detectSpecific} → {@code return base;}):
 * {@link #columnsAloneNowReachAllThreeDeviceTokens()},
 * {@link #theRefinementIsAdditiveForTheTwoSpecialisations()},
 * {@link #detectAllPromotesADeviceDatasetThroughTheHeuristic()} and
 * {@link #declarationLosesToAConfidentDeviceHeuristicUnderTheOptOut()} redden.</li>
 * <li><b>N2 — restore {@code DEVICE LEVEL ANALYSIS DATASET → ADAM OTHER} in
 * {@code SUPERTYPES}}</b>: {@link #aDeviceLevelDatasetGainsItsTokenWithoutGainingAdamOther()} and
 * {@code AdamDataStructureDetectorTest.structureSet_lastElementIsThePreFix175FoldedValue_fix179}
 * redden.</li>
 * </ul>
 */
class AdamDeviceStructureHeuristicTest
{

    private static final String SPDEVID = "SPDEVID";

    /**
     * ⚑ The refinement table, <b>hand-transcribed from the plan's §7 item 7 decision table</b> and
     * deliberately not read back out of the code under test: base verdict → verdict once the
     * dataset also carries {@code SPDEVID}.
     */
    private static final List<Map.Entry<String, String>> BASE_TO_DEVICE = List.of(
            Map.entry("BASIC DATA STRUCTURE", "MEDICAL DEVICE BASIC DATA STRUCTURE"),
            Map.entry("OCCURRENCE DATA STRUCTURE", "MEDICAL DEVICE OCCURRENCE DATA STRUCTURE"),
            Map.entry("ADAM OTHER", "DEVICE LEVEL ANALYSIS DATASET"),
            // ADaM defines no device subject-level structure — ADSL is never refined.
            Map.entry("SUBJECT LEVEL ANALYSIS DATASET", "SUBJECT LEVEL ANALYSIS DATASET"));

    /** A column shape for each of the four base verdicts, keyed by the verdict it produces. */
    private static Map.Entry<String, List<String>> shapeFor(String aBase)
    {
        return switch (aBase)
        {
        case AdamDataStructureDetector.BDS -> Map.entry("ADDVBDS",
                List.of("STUDYID", "USUBJID", "PARAMCD", "AVAL"));
        case AdamDataStructureDetector.OCCDS -> Map.entry("ADDVOC",
                List.of("STUDYID", "USUBJID", "MDDECOD"));
        case AdamDataStructureDetector.ADSL -> Map.entry("ADSL", List.of("STUDYID", "USUBJID"));
        default -> Map.entry("ADDV", List.of("STUDYID", "USUBJID", "DEVGR1"));
        };
    }

    // ---- the refinement itself ---------------------------------------------------


    /**
     * ⚑⚑ The point of Phase 3a.1: the three device tokens are no longer declaration-only. For each
     * base verdict, adding {@code SPDEVID} yields the hand-transcribed device token, and removing
     * it yields the base back.
     */
    @Test
    void columnsAloneNowReachAllThreeDeviceTokens()
    {
        List<String> produced = new ArrayList<>();
        for (Map.Entry<String, String> row : BASE_TO_DEVICE)
        {
            Map.Entry<String, List<String>> shape = shapeFor(row.getKey());
            String name = shape.getKey();
            List<String> plain = shape.getValue();
            List<String> withDevice = new ArrayList<>(plain);
            withDevice.add(SPDEVID);

            assertEquals(row.getKey(), AdamDataStructureDetector.detectSpecific(name, plain),
                    () -> "base verdict for " + plain);
            assertEquals(row.getValue(), AdamDataStructureDetector.detectSpecific(name, withDevice),
                    () -> "refined verdict for " + withDevice);
            produced.add(row.getValue());
        }
        // Not vacuous: all three device tokens really are reachable from columns alone.
        assertTrue(produced.contains(AdamDataStructureDetector.MEDICAL_DEVICE_BDS));
        assertTrue(produced.contains(AdamDataStructureDetector.MEDICAL_DEVICE_OCCDS));
        assertTrue(produced.contains(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET));
    }


    /** The discriminator is matched case-insensitively and after trimming, like every column. */
    @Test
    void theDeviceIdentifierIsMatchedCaseInsensitively()
    {
        for (String spelling : List.of("SPDEVID", "spdevid", "SpDevId", "  SPDEVID  "))
        {
            assertEquals(AdamDataStructureDetector.MEDICAL_DEVICE_BDS,
                    AdamDataStructureDetector.detectSpecific("ADDV", List.of("PARAMCD", spelling)),
                    spelling);
        }
    }


    /**
     * ⚠ The Fix #140 (EC-50) dataset-name gate applies to the refinement too. Every SDTM device
     * domain ({@code DI}, {@code DO}, {@code DU}, {@code DX}) carries {@code SPDEVID}, and none of
     * them is an ADaM dataset — promoting one out of {@code ADAM OTHER} would hand it the ADaM
     * device rules.
     */
    @Test
    void aNonAdamNamedDatasetIsNeverPromoted_fix140()
    {
        for (String sdtmDevice : List.of("DI", "DO", "DU", "DX"))
        {
            assertEquals(AdamDataStructureDetector.OTHER, AdamDataStructureDetector.detectSpecific(
                    sdtmDevice, List.of("STUDYID", SPDEVID, "PARAMCD")), sdtmDevice);
        }
        // … while the AD*/AX* convention is honoured on both prefixes.
        assertEquals(AdamDataStructureDetector.MEDICAL_DEVICE_BDS, AdamDataStructureDetector
                .detectSpecific("AXDV", List.of("STUDYID", SPDEVID, "PARAMCD")));
    }

    // ---- what must NOT move ------------------------------------------------------


    /**
     * ⚠⚠ <b>The Python mirror is untouched.</b>
     * {@link AdamDataStructureDetector#detect(String, java.util.Collection)} is the declared mirror
     * of {@code base_data_service.get_data_structure} and the parity harness ({@code SpecRunner})
     * calls it as exactly that — it must still return only the four root tokens, whatever the
     * columns say.
     */
    @Test
    void theTwoArgHeuristicStillReturnsOnlyTheFourPythonTokens()
    {
        List<List<String>> shapes = List.of(List.of("STUDYID", SPDEVID),
                List.of("PARAMCD", SPDEVID), List.of("AVAL", SPDEVID), List.of("MDDECOD", SPDEVID),
                List.of("AETERM", SPDEVID), List.of(SPDEVID));
        for (String name : List.of("ADSL", "ADDV", "ADLBC", "ADAE"))
        {
            for (List<String> columns : shapes)
            {
                String verdict = AdamDataStructureDetector.detect(name, columns);
                assertTrue(List
                        .of(AdamDataStructureDetector.ADSL, AdamDataStructureDetector.BDS,
                                AdamDataStructureDetector.OCCDS, AdamDataStructureDetector.OTHER)
                        .contains(verdict), () -> name + " " + columns + " -> " + verdict);
            }
        }
    }


    /**
     * ⚠ {@link AdamDataStructureDetector#hasNoStructureIndicators} gates the FU-4 {@code ADAM
     * OTHER} <em>class</em> sentinel, which 8 shipped rules scope. Phase 3a's acceptance is zero
     * corpus movement, so the predicate stays defined on the two-argument mirror and the device
     * identifier does not move it either way.
     */
    @Test
    void theStructureAbsencePredicateIsUnmovedByTheDeviceIdentifier()
    {
        assertTrue(AdamDataStructureDetector.hasNoStructureIndicators(List.of("STUDYID")));
        assertTrue(AdamDataStructureDetector.hasNoStructureIndicators(List.of("STUDYID", SPDEVID)),
                "a device-level dataset is still structure-less to the FU-4 class sentinel");
        assertFalse(AdamDataStructureDetector.hasNoStructureIndicators(List.of("PARAMCD")));
        assertFalse(AdamDataStructureDetector.hasNoStructureIndicators(List.of("PARAMCD", SPDEVID)),
                "a device BDS dataset must not become structure-less");
        assertFalse(AdamDataStructureDetector.hasNoStructureIndicators(List.of("AETERM", SPDEVID)));
    }

    // ---- additivity --------------------------------------------------------------


    /**
     * ⚑⚑ <b>The additivity pin.</b> For the two genuine specialisations, a dataset promoted by the
     * heuristic keeps every token it held before — the base is still in the set, so every rule
     * scoped to the base still covers it. The expected sets are written out literally.
     */
    @Test
    void theRefinementIsAdditiveForTheTwoSpecialisations()
    {
        assertEquals(List.of("MEDICAL DEVICE BASIC DATA STRUCTURE", "BASIC DATA STRUCTURE"),
                AdamDataStructureDetector.detectAll("ADDV", List.of("PARAMCD", SPDEVID), null,
                        true));
        assertEquals(
                List.of("MEDICAL DEVICE OCCURRENCE DATA STRUCTURE", "OCCURRENCE DATA STRUCTURE"),
                AdamDataStructureDetector.detectAll("ADDV", List.of("MDDECOD", SPDEVID), null,
                        true));
        // The un-promoted shapes are exactly what they were.
        assertEquals(List.of("BASIC DATA STRUCTURE"),
                AdamDataStructureDetector.detectAll("ADDV", List.of("PARAMCD"), null, true));
        assertEquals(List.of("OCCURRENCE DATA STRUCTURE"),
                AdamDataStructureDetector.detectAll("ADDV", List.of("MDDECOD"), null, true));
    }


    /**
     * ⚠⚠ <b>The one deliberate non-additive case</b>, owner decision 2026-08-09: a device-level
     * dataset gains {@code DEVICE LEVEL ANALYSIS DATASET} <b>without</b> gaining
     * {@code ADAM OTHER}. {@code ADAM OTHER} means <em>structure-less</em>; it is the heuristic's
     * fall-through, not a supertype, and leaving the mapping in place would have made the 8 rules
     * scoped {@code Classes.Include:[ADAM OTHER]} start firing on device datasets once the corpus
     * migrates (Phase 3b).
     *
     * <p>
     * ⚑ It costs nothing today: measured over the assembled rule packages, the only
     * {@code Scope.Data_Structures} tokens in use are {@code BASIC DATA STRUCTURE} (74),
     * {@code OCCURRENCE DATA STRUCTURE} (174) and {@code SUBJECT LEVEL ANALYSIS DATASET} (20).
     * <b>Zero</b> entries name {@code ADAM OTHER}, so no shipped rule can lose a dataset by this.
     * </p>
     */
    @Test
    void aDeviceLevelDatasetGainsItsTokenWithoutGainingAdamOther()
    {
        List<String> detected = AdamDataStructureDetector.detectAll("ADDV",
                List.of("STUDYID", "DEVGR1", SPDEVID), null, true);

        assertEquals(List.of("DEVICE LEVEL ANALYSIS DATASET"), detected);
        assertFalse(detected.contains(AdamDataStructureDetector.OTHER),
                "ADAM OTHER is a fall-through, not a supertype — owner decision 2026-08-09");
        // The same dataset without the device identifier is unchanged.
        assertEquals(List.of("ADAM OTHER"), AdamDataStructureDetector.detectAll("ADDV",
                List.of("STUDYID", "DEVGR1"), null, true));
    }

    // ---- the declared tier still outranks the columns ----------------------------


    /**
     * ⚑ Phase 3a.1 is a <em>fallback-coverage</em> change only. {@code corej.defineFirst} defaults
     * to {@code true}, so a declaration is returned before the heuristic runs at all — a study that
     * declares its structures is entirely unaffected by this phase.
     */
    @Test
    void aDeclarationStillWinsOutrightUnderTheDefault()
    {
        // Declared BDS, device-shaped columns: the declaration decides, un-refined.
        assertEquals(List.of("BASIC DATA STRUCTURE"), AdamDataStructureDetector.detectAll("ADDV",
                List.of("PARAMCD", SPDEVID), "BASIC DATA STRUCTURE", true));
        // Declared device token, silent columns: the declaration still decides.
        assertEquals(List.of("DEVICE LEVEL ANALYSIS DATASET"), AdamDataStructureDetector
                .detectAll("ADDV", List.of("STUDYID"), "DEVICE LEVEL ANALYSIS DATASET", true));
    }


    /**
     * ⚠ The one genuinely narrow case the plan asked to pin (§7 item 6): under the
     * {@code -Dcorej.defineFirst=false} opt-out a <b>confident non-{@code ADAM OTHER}</b> heuristic
     * beats the declaration — and Phase 3a.1 makes {@code DEVICE LEVEL ANALYSIS DATASET} such a
     * confident verdict, where the same dataset used to fall through to {@code ADAM OTHER} and let
     * the declaration fill in. That is the documented opt-out behaving as specified; pinned here so
     * a future change cannot alter it silently.
     */
    @Test
    void declarationLosesToAConfidentDeviceHeuristicUnderTheOptOut()
    {
        assertEquals(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET,
                AdamDataStructureDetector.detect("ADDV", List.of("STUDYID", SPDEVID),
                        "BASIC DATA STRUCTURE", false));
        // Without the device identifier the heuristic yields ADAM OTHER and the declaration fills
        // in — the pre-Phase-3a behaviour, unchanged.
        assertEquals(AdamDataStructureDetector.BDS, AdamDataStructureDetector.detect("ADDV",
                List.of("STUDYID"), "BASIC DATA STRUCTURE", false));
    }


    /** {@code detectAll} really does route through the refinement, not just {@code detect}. */
    @Test
    void detectAllPromotesADeviceDatasetThroughTheHeuristic()
    {
        assertEquals(AdamDataStructureDetector.MEDICAL_DEVICE_BDS, AdamDataStructureDetector
                .detectAll("ADDV", List.of("PARAMCD", SPDEVID), null, true).getFirst());
    }

    // ---- the subclass detector is unmoved ----------------------------------------


    /**
     * <b>Phase 3a.2, stated as a measurement.</b> {@link AdamSubclassDetector} is the only place in
     * the engine that branches on the structure, and every device set the new heuristic can produce
     * decides exactly as the set it produced before. The <em>expected</em> side is the pre-Phase-3a
     * structure set, written out by hand.
     */
    @Test
    void everyHeuristicDeviceSetDecidesTheSubclassExactlyAsBefore()
    {
        List<List<String>> columnShapes = List.of(List.of("PARAMCD", SPDEVID, "CNSR"),
                List.of("PARAMCD", SPDEVID, "EVID", "DV", "AMT"),
                List.of("PARAMCD", SPDEVID, "NFRLT", "AFRLT"),
                List.of("MDDECOD", SPDEVID, "AEDECOD"), List.of("MDDECOD", SPDEVID, "AETERM"),
                List.of("STUDYID", SPDEVID, "DEVGR1"));
        for (List<String> columns : columnShapes)
        {
            List<String> now = AdamDataStructureDetector.detectAll("ADDV", columns, null, true);
            // The pre-Phase-3a set for the same columns: the un-refined heuristic, expanded.
            List<String> before = AdamDataStructureDetector
                    .structureSet(AdamDataStructureDetector.detect("ADDV", columns));
            assertEquals(AdamSubclassDetector.detect(before, columns),
                    AdamSubclassDetector.detect(now, columns),
                    () -> "subclass moved for " + columns + ": " + before + " -> " + now);
            assertEquals(AdamSubclassDetector.detectByName("ADPPK", before),
                    AdamSubclassDetector.detectByName("ADPPK", now), () -> "byName " + columns);
        }
        // Not vacuous: the matrix really does reach a non-null subclass on both sides.
        assertEquals(AdamSubclassDetector.MEDICAL_DEVICE_TIME_TO_EVENT,
                AdamSubclassDetector.detect(
                        AdamDataStructureDetector.detectAll("ADDV",
                                List.of("PARAMCD", SPDEVID, "CNSR"), null, true),
                        List.of("PARAMCD", SPDEVID, "CNSR")));
        assertNull(AdamSubclassDetector.detect(AdamDataStructureDetector.detectAll("ADDV",
                List.of("STUDYID", SPDEVID), null, true), List.of("STUDYID", SPDEVID)));
    }

}
