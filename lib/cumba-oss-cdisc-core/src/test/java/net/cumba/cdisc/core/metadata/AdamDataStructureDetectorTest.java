package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fix #117: {@link AdamDataStructureDetector} — the Java mirror of the Python engine's
 * {@code base_data_service.get_data_structure} (with the {@code ARAMCD}→{@code PARAMCD} typo
 * corrected, user decision 2026-07-26).
 */
class AdamDataStructureDetectorTest
{

    @Test
    void adslByName_caseInsensitive()
    {
        assertEquals(AdamDataStructureDetector.ADSL,
                AdamDataStructureDetector.detect("ADSL", List.of("STUDYID", "USUBJID")));
        assertEquals(AdamDataStructureDetector.ADSL,
                AdamDataStructureDetector.detect("adsl", List.of()));
        // The name wins even over BDS indicator columns (Python checks the name first).
        assertEquals(AdamDataStructureDetector.ADSL,
                AdamDataStructureDetector.detect("ADSL", List.of("PARAM", "AVAL")));
    }


    @Test
    void bdsByIndicatorColumns()
    {
        for (String indicator : List.of("PARAMCD", "PARAM", "AVAL", "AVALC"))
        {
            assertEquals(AdamDataStructureDetector.BDS,
                    AdamDataStructureDetector.detect("ADLBC", List.of("STUDYID", indicator)),
                    indicator);
        }
        // Case-insensitive column match.
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.detect("ADLBC", List.of("aval")));
    }


    @Test
    void paramcdTypoCorrection()
    {
        // PARAMCD counts as a BDS indicator (upstream's ARAMCD typo is corrected here) …
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.detect("ADX", List.of("PARAMCD")));
        // … and the literal typo column no longer matches anything.
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADX", List.of("ARAMCD")));
    }


    @Test
    void bdsWinsOverOccdsSuffix()
    {
        // A dataset with both signals is BDS (Python's check order).
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.detect("ADX", List.of("AETERM", "AVAL")));
    }


    @Test
    void occdsBySuffix()
    {
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect("ADAE", List.of("STUDYID", "AETERM")));
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect("ADCM", List.of("CMTRT")));
        // Suffix, not substring: TERMX does not match.
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADX", List.of("TERMX")));
        // A bare TERM column IS a suffix match (mirrors Python's endswith).
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect("ADX", List.of("TERM")));
    }


    @Test
    void nonAdamNameCarriesNoStructure_fix140()
    {
        // The column signatures are not standard-specific: a real SDTM AE dataset carries AETERM
        // and no BDS indicator, so without the AD*/AX* name gate it would detect as OCCDS and
        // satisfy every Subclasses:["ADVERSE EVENT"] rule. This is the gate that keeps the ADaM
        // scope axes from reaching SDTM.
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("AE", List.of("AETERM", "AEDECOD", "AESEQ")));
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("LB", List.of("LBTESTCD", "PARAMCD", "AVAL")));
        // The convention covers both ADaMIG's AD* and the AX* sponsor extension,
        // case-insensitively.
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect("ADAE01", List.of("AETERM")));
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect("axae", List.of("AETERM")));
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.detect("ADLBC", List.of("PARAMCD")));
        // A null name has no convention to judge, so the gate does not apply — this is what keeps
        // the column-only hasNoStructureIndicators predicate working.
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect(null, List.of("AETERM")));
    }


    @Test
    void declaredClassOutranksTheNameGate_fix140()
    {
        // A sponsor may name an ADaM dataset outside the convention and declare its class in
        // Define-XML. The declaration wins: it is explicit evidence, the name is only a convention.
        assertEquals(AdamDataStructureDetector.BDS, AdamDataStructureDetector.detect("MYANALYSIS",
                List.of("PARAMCD", "AVAL"), "BASIC DATA STRUCTURE", false));
        // …and with no declaration the same dataset stays structure-less.
        assertEquals(AdamDataStructureDetector.OTHER, AdamDataStructureDetector.detect("MYANALYSIS",
                List.of("PARAMCD", "AVAL"), null, false));
    }


    @Test
    void occdsByDecodSuffix_fix140()
    {
        // Fix #140 (EC-50): the dictionary-coded topic variable is an OCCDS signal in its own
        // right, so an AE analysis dataset that does not carry AETERM is still OCCDS.
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect("ADAE", List.of("STUDYID", "AEDECOD")));
        assertEquals(AdamDataStructureDetector.OCCDS,
                AdamDataStructureDetector.detect("ADMH", List.of("MHDECOD")));
        // Suffix, not substring.
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADX", List.of("DECODX")));
        // The addition is additive only: a BDS indicator still wins.
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.detect("ADX", List.of("AEDECOD", "AVAL")));
    }


    @Test
    void otherFallback_andNullTolerance()
    {
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADXX", List.of("STUDYID", "USUBJID")));
        assertEquals(AdamDataStructureDetector.OTHER, AdamDataStructureDetector.detect(null, null));
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADXX", java.util.Arrays.asList("X", null)));
    }


    /**
     * <b>Fix #179: the fold is gone.</b> Every one of the seven structure tokens now maps verbatim,
     * so a sponsor's {@code MEDICAL DEVICE BASIC DATA STRUCTURE} declaration survives as itself
     * instead of being rewritten to {@code BASIC DATA STRUCTURE}. The is-a relation it used to
     * express is carried by {@link AdamDataStructureDetector#structureSet} — see
     * {@link #structureSet_lastElementIsThePreFix175FoldedValue_fix179()}, which pins that nothing
     * was lost.
     */
    @Test
    void declaredClassMapsVerbatim_fix179()
    {
        assertEquals(AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.structureTokenFromDeclaredClass("BASIC DATA STRUCTURE"));
        assertEquals(AdamDataStructureDetector.MEDICAL_DEVICE_BDS, AdamDataStructureDetector
                .structureTokenFromDeclaredClass("MEDICAL DEVICE BASIC DATA STRUCTURE"));
        assertEquals(AdamDataStructureDetector.MEDICAL_DEVICE_OCCDS, AdamDataStructureDetector
                .structureTokenFromDeclaredClass("MEDICAL DEVICE OCCURRENCE DATA STRUCTURE"));
        assertEquals(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET,
                AdamDataStructureDetector
                        .structureTokenFromDeclaredClass("DEVICE LEVEL ANALYSIS DATASET"));
        assertEquals(AdamDataStructureDetector.ADSL, AdamDataStructureDetector
                .structureTokenFromDeclaredClass("Subject Level Analysis Dataset"));
        // Case-insensitive on the trimmed value, for a device variant too.
        assertEquals(AdamDataStructureDetector.MEDICAL_DEVICE_OCCDS, AdamDataStructureDetector
                .structureTokenFromDeclaredClass("  medical device occurrence data structure "));
        // SDTM classes and unknown values are not data structures.
        assertEquals(null, AdamDataStructureDetector.structureTokenFromDeclaredClass("EVENTS"));
        assertEquals(null, AdamDataStructureDetector.structureTokenFromDeclaredClass("bogus"));
        assertEquals(null, AdamDataStructureDetector.structureTokenFromDeclaredClass(null));
    }


    /**
     * Fix #179: the medical-device names were previously folded away and therefore <b>not
     * authorable in {@code Scope.Data_Structures} at all</b>, which is why 345 of the corpus's 1394
     * ADaM scope entries live in {@code Scope.Classes} instead. Phase 6a of
     * {@code plans/PLAN-metadata-product-selection.md} added the eighth token,
     * {@code REFERENCE DATA STRUCTURE} — {@code tig/1-0/adam}'s {@code REFERENDS}, whose absence
     * was an oversight rather than a decision.
     */
    @Test
    void structureTokens_gainedTheThreeDeviceVariants_fix179()
    {
        assertEquals(8, AdamDataStructureDetector.STRUCTURE_TOKENS.size(),
                "the four base tokens, the three device specialisations, and the TIG reference "
                        + "data structure");
        for (String token : List.of(AdamDataStructureDetector.ADSL, AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.OCCDS, AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.MEDICAL_DEVICE_BDS,
                AdamDataStructureDetector.MEDICAL_DEVICE_OCCDS,
                AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET,
                AdamDataStructureDetector.REFERENCE_DATA_STRUCTURE))
        {
            assertTrue(AdamDataStructureDetector.STRUCTURE_TOKENS.contains(token), token);
        }
    }


    /**
     * Fix #179: {@code structureSet} is the is-a relation, most-specific first. A device variant
     * carries its base; a base carries only itself.
     */
    @Test
    void structureSet_carriesTheSupertypeChain_fix179()
    {
        assertEquals(
                List.of(AdamDataStructureDetector.MEDICAL_DEVICE_BDS,
                        AdamDataStructureDetector.BDS),
                AdamDataStructureDetector
                        .structureSet(AdamDataStructureDetector.MEDICAL_DEVICE_BDS));
        assertEquals(
                List.of(AdamDataStructureDetector.MEDICAL_DEVICE_OCCDS,
                        AdamDataStructureDetector.OCCDS),
                AdamDataStructureDetector
                        .structureSet(AdamDataStructureDetector.MEDICAL_DEVICE_OCCDS));
        // ⚠⚠ Phase 3a (owner decision 2026-08-09): DEVICE LEVEL ANALYSIS DATASET has NO
        // supertype. Before Phase 3a it mapped onto ADAM OTHER, but only because the column
        // heuristic could not see device datasets; detectSpecific removed that blindness and with
        // it the mapping's justification. ADAM OTHER means structure-less, and a device-level
        // analysis dataset is a structure. Keeping the mapping would have made the 8 rules scoped
        // Classes.Include:[ADAM OTHER] start firing on device datasets once the corpus migrates.
        assertEquals(List.of(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET),
                AdamDataStructureDetector
                        .structureSet(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET));
        // A root token is its own singleton set …
        for (String root : List.of(AdamDataStructureDetector.ADSL, AdamDataStructureDetector.BDS,
                AdamDataStructureDetector.OCCDS, AdamDataStructureDetector.OTHER))
        {
            assertEquals(List.of(root), AdamDataStructureDetector.structureSet(root), root);
        }
        // … and so is an unrecognised one (the closed-vocabulary gate then simply never matches
        // it).
        assertEquals(List.of("NOT A STRUCTURE"),
                AdamDataStructureDetector.structureSet("NOT A STRUCTURE"));
    }


    /**
     * ⚑⚑ <b>The behaviour-preservation pin for Fix #179.</b> The <em>last</em> element of every
     * structure set is exactly the token the pre-Fix-#175 fold returned for that declaration. That
     * identity is why the change is invisible: every rule authored against the four original tokens
     * still sees the value it saw before, one position later in the list.
     *
     * <p>
     * The <em>expected</em> values are the pre-Fix-#175 {@code switch} transcribed by hand — a
     * literal table, not a second call into the code under test — so the assertion cannot be
     * satisfied by a change that moves both sides at once.
     * </p>
     */
    @Test
    void structureSet_lastElementIsThePreFix175FoldedValue_fix179()
    {
        // The exact table from AdamDataStructureDetector.structureTokenFromDeclaredClass before
        // Fix #179: declared value -> folded token.
        assertEquals(AdamDataStructureDetector.ADSL,
                foldedByPreFix175("SUBJECT LEVEL ANALYSIS" + " DATASET"));
        assertEquals(AdamDataStructureDetector.BDS, foldedByPreFix175("BASIC DATA STRUCTURE"));
        assertEquals(AdamDataStructureDetector.BDS,
                foldedByPreFix175("MEDICAL DEVICE BASIC DATA STRUCTURE"));
        assertEquals(AdamDataStructureDetector.OCCDS,
                foldedByPreFix175("OCCURRENCE DATA STRUCTURE"));
        assertEquals(AdamDataStructureDetector.OCCDS,
                foldedByPreFix175("MEDICAL DEVICE OCCURRENCE DATA STRUCTURE"));
        assertEquals(AdamDataStructureDetector.OTHER, foldedByPreFix175("ADAM OTHER"));
        // ⚠⚠ The ONE deliberate break in this pin, owner-decided 2026-08-09 (Phase 3a). The
        // pre-Fix-#175 fold sent DEVICE LEVEL ANALYSIS DATASET to ADAM OTHER; Phase 3a drops that
        // supertype, so the set's last element is now the token itself. This is the only structure
        // whose base a rule can no longer reach through the set — and it costs nothing, because
        // ZERO shipped Scope.Data_Structures entries name ADAM OTHER (measured over the assembled
        // rule packages: BASIC DATA STRUCTURE 74, OCCURRENCE DATA STRUCTURE 174, SUBJECT LEVEL
        // ANALYSIS DATASET 20, and nothing else). The other six rows above are untouched, which is
        // what keeps Fix #179's behaviour-preservation claim intact for every scoped token.
        assertEquals(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET,
                foldedByPreFix175("DEVICE LEVEL ANALYSIS DATASET"));
    }


    /** What the pre-Fix-#175 fold would have returned: the root of the token's supertype chain. */
    private static String foldedByPreFix175(String declaredClass)
    {
        String token = AdamDataStructureDetector.structureTokenFromDeclaredClass(declaredClass);
        assertTrue(token != null, declaredClass);
        return AdamDataStructureDetector.structureSet(token).getLast();
    }


    /**
     * Fix #179: {@code detectAll} is {@code detect} lifted through
     * {@link AdamDataStructureDetector#structureSet} — never empty, most-specific first.
     */
    @Test
    void detectAll_isTheDetectedTokenPlusItsSupertypes_fix179()
    {
        // A declared device BDS dataset carries both tokens (defineFirst, the default).
        assertEquals(
                List.of(AdamDataStructureDetector.MEDICAL_DEVICE_BDS,
                        AdamDataStructureDetector.BDS),
                AdamDataStructureDetector.detectAll("ADMDBDS", List.of("STUDYID"),
                        "MEDICAL DEVICE BASIC DATA STRUCTURE", true));
        // A plain declaration is unchanged — a singleton, exactly as before Fix #179.
        assertEquals(List.of(AdamDataStructureDetector.BDS), AdamDataStructureDetector
                .detectAll("ADLBC", List.of("STUDYID"), "BASIC DATA STRUCTURE", true));
        // Without a device identifier the heuristic still yields a singleton root token.
        assertEquals(List.of(AdamDataStructureDetector.OCCDS),
                AdamDataStructureDetector.detectAll("ADAE", List.of("AETERM"), null, true));
        assertEquals(List.of(AdamDataStructureDetector.OTHER),
                AdamDataStructureDetector.detectAll(null, null, null, true));
        // An SDTM class declaration is not a structure — the heuristic still decides.
        assertEquals(List.of(AdamDataStructureDetector.BDS),
                AdamDataStructureDetector.detectAll("ADLBC", List.of("PARAMCD"), "FINDINGS", true));
        // Phase 3a: the device-level analysis dataset stands alone — no ADAM OTHER supertype.
        assertEquals(List.of(AdamDataStructureDetector.DEVICE_LEVEL_ANALYSIS_DATASET),
                AdamDataStructureDetector.detectAll("ADDL", List.of("STUDYID"),
                        "DEVICE LEVEL ANALYSIS DATASET", true));
    }


    @Test
    void defineFirstPrecedence()
    {
        // defineFirst=true: the declared class wins over the column heuristic.
        assertEquals(AdamDataStructureDetector.OCCDS, AdamDataStructureDetector.detect("ADX",
                List.of("PARAMCD", "AVAL"), "OCCURRENCE DATA STRUCTURE", true));
        // defineFirst=false: heuristics decide when they find a structure …
        assertEquals(AdamDataStructureDetector.BDS, AdamDataStructureDetector.detect("ADX",
                List.of("PARAMCD", "AVAL"), "OCCURRENCE DATA STRUCTURE", false));
        // … and the declaration fills in when the heuristic yields OTHER.
        assertEquals(AdamDataStructureDetector.BDS, AdamDataStructureDetector.detect("ADX",
                List.of("STUDYID"), "BASIC DATA STRUCTURE", false));
        // Non-structure declaration (SDTM class): heuristic result stands, defineFirst or not.
        assertEquals(AdamDataStructureDetector.OTHER,
                AdamDataStructureDetector.detect("ADX", List.of("STUDYID"), "EVENTS", true));
    }


    /**
     * Fix #154: the {@code corej.defineFirst} default flipped to {@code true}, and the property is
     * an <b>opt-out</b> — only an explicit {@code false} turns it off, unlike
     * {@link Boolean#getBoolean} which the three call sites used before.
     */
    @Test
    void defineFirstPreference_defaultsToTrueAndIsOptOut()
    {
        String saved = System.getProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY);
        try
        {
            System.clearProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY);
            assertTrue(AdamDataStructureDetector.defineFirstPreference(),
                    "Fix #154: absent property must mean define-first");

            System.setProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY, "false");
            assertFalse(AdamDataStructureDetector.defineFirstPreference());
            System.setProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY, "  FaLsE  ");
            assertFalse(AdamDataStructureDetector.defineFirstPreference(),
                    "case- and whitespace-insensitive opt-out");

            System.setProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY, "true");
            assertTrue(AdamDataStructureDetector.defineFirstPreference());
            // The asymmetry with Boolean.getBoolean, stated: garbage is NOT an opt-out.
            System.setProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY, "yes");
            assertTrue(AdamDataStructureDetector.defineFirstPreference());
            System.setProperty(AdamDataStructureDetector.DEFINE_FIRST_PROPERTY, "");
            assertTrue(AdamDataStructureDetector.defineFirstPreference());
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
    }


    @Test
    void hasNoStructureIndicators_mirrorsDetection()
    {
        assertTrue(AdamDataStructureDetector.hasNoStructureIndicators(List.of("STUDYID")));
        assertTrue(AdamDataStructureDetector.hasNoStructureIndicators(null));
        assertTrue(AdamDataStructureDetector.hasNoStructureIndicators(List.of()));
        assertFalse(AdamDataStructureDetector.hasNoStructureIndicators(List.of("PARAMCD")));
        assertFalse(AdamDataStructureDetector.hasNoStructureIndicators(List.of("AETERM")));
        // Fix #140 (EC-50): the DECOD suffix widens this predicate too, which is what narrows the
        // FU-4 "ADAM OTHER" class sentinel in MetadataLibraryProvider — a dataset carrying a
        // coded-term column is not structure-less. Pinned end-to-end by
        // MetadataLibraryProviderProductsTest.getDatasetClass_adamCodedTermColumn_isNoLongerAdamOther_fix140.
        assertFalse(AdamDataStructureDetector.hasNoStructureIndicators(List.of("AEDECOD")));
    }

}
