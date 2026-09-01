package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #179 — the reason the set-valued structure is safe.</b>
 *
 * <p>
 * {@link AdamSubclassDetector} is the <em>only</em> place in the engine that branches on the
 * detected data structure ({@code bds} / {@code occds} in {@link AdamSubclassDetector#detect}, and
 * the BDS precondition in {@link AdamSubclassDetector#detectByName}). Before Fix #179 a
 * medical-device BDS dataset folded to {@code BASIC DATA STRUCTURE} and those booleans were true;
 * with the set {@code [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA STRUCTURE]} and a
 * contains-check they still are. This class asserts that identity <b>exhaustively over the whole
 * subclass signature matrix</b>, rather than on one hand-picked input — the claim being tested is a
 * universal one, so a spot check would not settle it.
 * </p>
 *
 * <p>
 * ⚠ Note what this does <em>not</em> claim: it does not say the device variants are interchangeable
 * with their bases everywhere, only that the subclass tier cannot tell them apart. The place where
 * they must differ is the {@code Scope.Data_Structures} gate, pinned by
 * {@code ScopeMatcherStructureSubclassTest}.
 * </p>
 */
class AdamSubclassDetectorStructureSetTest
{

    /**
     * Every column set that can reach a distinct branch of the subclass heuristic, plus the
     * negative shapes around each. If a branch is added without extending this list, the
     * exhaustiveness claim quietly narrows — {@link #theMatrixIsNotVacuous()} guards the count.
     */
    private static final List<List<String>> COLUMN_SHAPES = List.of(List.of(),
            List.of("STUDYID", "USUBJID"), List.of("PARAMCD", "AVAL"),
            List.of("PARAMCD", "AVAL", "CNSR"), List.of("PARAMCD", "AVAL", "CNSR", "SPDEVID"),
            List.of("PARAMCD", "DV", "MDV", "AMT"), List.of("PARAMCD", "DV", "AMT", "CMT"),
            List.of("PARAMCD", "NFRLT", "AFRLT"), List.of("PARAMCD", "NFRLT"),
            List.of("PARAMCD", "NFRLT", "AFRLT", "DV", "MDV", "AMT"), List.of("AETERM", "AEDECOD"),
            List.of("AETERM", "AESEQ"), List.of("AEDECOD", "AESEQ"), List.of("MHTERM", "MHDECOD"),
            List.of("CMTRT", "CMDECOD"), List.of("SPDEVID", "AVAL"));

    /** Dataset names that reach the Fix #154 last-resort name tier, and names that do not. */
    private static final List<String> DATASET_NAMES = List.of("ADPPK", "ADPPK01", "adppt",
            "ADPOPPK", "ADNCA01", "ADLBC", "ADTTE", "ADAE");

    /**
     * ⚠⚠ The variant → base table, <b>transcribed by hand from the pre-Fix-#175 fold</b>, and
     * deliberately <em>not</em> read back out of {@link AdamDataStructureDetector#structureSet}.
     *
     * <p>
     * The first version of this class derived the base as {@code structureSet(variant).getLast()},
     * which made every assertion below <b>vacuous under both neuters</b>: collapse the set either
     * way and the "variant" set and the "base" set become the same list, so the test compared a
     * value to itself and stayed green while the property it claims to prove was broken. Measured —
     * it passed under the restored fold and under a base-dropping collapse alike. The expected side
     * of an invariance assertion must not come from the code the assertion is about.
     * </p>
     */
    private static final List<Map.Entry<String, String>> VARIANT_TO_BASE = List.of(
            Map.entry("MEDICAL DEVICE BASIC DATA STRUCTURE", "BASIC DATA STRUCTURE"),
            Map.entry("MEDICAL DEVICE OCCURRENCE DATA STRUCTURE", "OCCURRENCE DATA STRUCTURE"),
            // ⚠ Phase 3a dropped this pair's supertype edge, so the left side is now a singleton
            // set. The row is KEPT because the claim it makes is still exactly right and still
            // load-bearing: a device-level dataset must decide the subclass exactly as a
            // structure-less one does. It just proves it now by carrying no BDS/OCCDS rather than
            // by carrying ADAM OTHER.
            Map.entry("DEVICE LEVEL ANALYSIS DATASET", "ADAM OTHER"));

    /**
     * ⚑⚑ <b>The invariance proof.</b> For every column shape, the device specialisation's set
     * decides exactly as its base token's singleton set does — in
     * {@link AdamSubclassDetector#detect} and in {@link AdamSubclassDetector#detectByName}, for
     * every dataset name.
     *
     * <p>
     * ⚠ <b>Neuter-and-watch (this is an N2 detector).</b> Collapse
     * {@link AdamDataStructureDetector#structureSet} to the variant alone —
     * {@code List.of(structureToken)}, i.e. drop the supertype — and every row of this matrix that
     * involves a device variant reddens, because the contains-check for
     * {@code BASIC DATA STRUCTURE} / {@code OCCURRENCE DATA STRUCTURE} then fails. That is the
     * silent regression this class exists to prevent: it is the same loss of coverage that would
     * hit the 78 shipped {@code Data_Structures} entries.
     * </p>
     */
    @Test
    void aDeviceVariantSetDecidesExactlyAsItsBase_fix179()
    {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> pair : VARIANT_TO_BASE)
        {
            List<String> variantSet = AdamDataStructureDetector.structureSet(pair.getKey());
            // ⚠ The hand-transcribed base, NOT variantSet.getLast() — see VARIANT_TO_BASE.
            List<String> baseSet = List.of(pair.getValue());
            pairs.add(pair.getKey());
            for (List<String> columns : COLUMN_SHAPES)
            {
                assertEquals(AdamSubclassDetector.detect(baseSet, columns),
                        AdamSubclassDetector.detect(variantSet, columns),
                        () -> "detect disagrees for " + variantSet + " vs " + baseSet + " on "
                                + columns);
                for (String name : DATASET_NAMES)
                {
                    assertEquals(AdamSubclassDetector.detectByName(name, baseSet),
                            AdamSubclassDetector.detectByName(name, variantSet),
                            () -> "detectByName disagrees for " + name + ", " + variantSet + " vs "
                                    + baseSet);
                    assertEquals(
                            AdamSubclassDetector.resolve(name, baseSet, columns, List.of(), true),
                            AdamSubclassDetector.resolve(name, variantSet, columns, List.of(),
                                    true),
                            () -> "resolve disagrees for " + name + ", " + variantSet + " on "
                                    + columns);
                }
            }
        }
        assertEquals(3, pairs.size(), "all three device specialisations must be covered");
    }


    /**
     * The matrix really does reach the branches it claims to. Without this, a column list that
     * stopped matching any signature would leave
     * {@link #aDeviceVariantSetDecidesExactlyAsItsBase_fix179()} comparing {@code null} to
     * {@code null} everywhere and asserting nothing at all — the masking-filter failure mode.
     */
    @Test
    void theMatrixIsNotVacuous()
    {
        List<String> bds = List.of(AdamDataStructureDetector.BDS);
        List<String> occds = List.of(AdamDataStructureDetector.OCCDS);
        List<String> reached = new ArrayList<>();
        for (List<String> columns : COLUMN_SHAPES)
        {
            for (List<String> structures : List.of(bds, occds))
            {
                String subclass = AdamSubclassDetector.detect(structures, columns);
                if (subclass != null && !reached.contains(subclass))
                {
                    reached.add(subclass);
                }
            }
        }
        assertEquals(AdamSubclassDetector.SUBCLASS_TOKENS.size(), reached.size(),
                "the column matrix must reach every subclass branch, reached: " + reached);
        // And the name tier is reached too (BDS + a popPK/NCA name, columns silent).
        assertEquals(AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS,
                AdamSubclassDetector.detectByName("ADPPK", bds));
        assertEquals(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS,
                AdamSubclassDetector.detectByName("ADNCA01", bds));
        assertNull(AdamSubclassDetector.detectByName("ADLBC", bds));
    }


    /**
     * The device-level analysis dataset reaches no subclass — neither {@code BDS} nor {@code OCCDS}
     * is in its set, so no branch's precondition holds.
     *
     * <p>
     * ⚠ <b>Phase 3a dropped its {@code ADAM OTHER} supertype</b> (owner decision 2026-08-09), so
     * the real set is now the singleton {@code [DEVICE LEVEL ANALYSIS DATASET]}. The verdict is
     * unchanged either way, which is the point: {@code ADAM OTHER} was never a subclass
     * precondition, so removing it from the set costs this detector nothing. Both shapes are
     * asserted so the test states that explicitly rather than relying on one of them.
     * </p>
     */
    @Test
    void deviceLevelAnalysisDatasetReachesNoSubclass_fix179()
    {
        assertEquals(List.of(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET),
                AdamDataStructureDetector
                        .structureSet(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET),
                "Phase 3a: DEVICE LEVEL ANALYSIS DATASET has no supertype");
        assertNull(AdamSubclassDetector.detect(
                List.of(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET),
                List.of("PARAMCD", "AVAL", "CNSR")));
        // The pre-Phase-3a shape decides identically.
        List<String> ddl = List.of(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET,
                AdamDataStructureDetector.OTHER);
        assertNull(AdamSubclassDetector.detect(ddl, List.of("PARAMCD", "AVAL", "CNSR")));
        assertNull(AdamSubclassDetector.detectByName("ADPPK", ddl));
    }
}
