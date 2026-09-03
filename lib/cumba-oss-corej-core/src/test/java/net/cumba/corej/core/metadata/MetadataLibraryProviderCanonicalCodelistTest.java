package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.codelist;
import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #373</b>, phase 1b — leg 2 ({@code buildResolvedSdtm}) rebuilds a variable from the
 * product's <b>class walk</b>, which carries no codelist binding, so a {@code SUPP--} /
 * {@code SQ--} / {@code AP--} member lost the entire codelist family that the CANONICAL name
 * publishes.
 *
 * <h2>Measured before the fix (product-backed sdtmig 3-4 + sdtmct-2024-09-27)</h2>
 *
 * <table>
 * <caption>Measured before the fix</caption>
 * <tr>
 * <td>{@code AE.AESEV}</td>
 * <td>11 keys, codelist {@code AESEV}</td>
 * <td>codeMap 3</td>
 * </tr>
 * <tr>
 * <td>{@code APAE.AESEV}</td>
 * <td><b>6 keys, no codelist</b></td>
 * <td>codeMap <b>0</b></td>
 * </tr>
 * <tr>
 * <td>{@code SUPPQUAL.RDOMAIN}</td>
 * <td>11 keys, codelist {@code DOMAIN}</td>
 * <td>83</td>
 * </tr>
 * <tr>
 * <td>{@code SUPPAE.RDOMAIN}</td>
 * <td><b>6 keys, no codelist</b></td>
 * <td><b>0</b></td>
 * </tr>
 * </table>
 *
 * <h2>⭐⭐ The rule the fix implements — and the trap it avoids</h2>
 *
 * <p>
 * The source is the CANONICAL name's <b>own</b> codelist, <b>never the parent domain's</b>. For
 * {@code AP--} the two coincide ({@code AP--} is structurally a copy of its parent). For
 * {@code SUPP--} they emphatically do not: {@code SUPPQUAL} publishes its own bindings for
 * {@code RDOMAIN} and {@code QEVAL} and <b>none</b> for {@code QVAL}, whose meaning varies per
 * {@code QNAM} row. Borrowing the parent's codelist for {@code SUPPAE.QVAL} would fire findings on
 * conforming data — {@link #suppQval_neverBorrowsTheParentsCodelist} is the test that pins it.
 * </p>
 */
class MetadataLibraryProviderCanonicalCodelistTest
{

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * The study-side library leg 1 reads. It publishes the CANONICAL names only — {@code AE} and
     * {@code SUPPQUAL} — never {@code APAE} or {@code SUPPAE}, exactly like the real Library.
     */
    private static IMetadataLibrary canonicalLibrary()
    {
        return lib("sdtmig")
                .table(table("AE").label("Adverse Events").className("Events")
                        .column(column("AESEV", 8, DataValueType.STRING).label("Severity/Intensity")
                                .role("Record Qualifier").core("Perm").codelist("AESEV").build())
                        .column(column("AETERM", 3, DataValueType.STRING).label("Reported Term")
                                .role("Topic").core("Req").build())
                        // ⭐ Deliberate trap, added after the review: AE also declares a QVAL bound
                        // to a codelist. Without it, a "strip SUPP and borrow the PARENT domain"
                        // implementation would simply miss (AE had no QVAL) and
                        // suppQval_neverBorrowsTheParentsCodelist would still pass — a vacuous
                        // absence assertion. With it, that wrong design attaches AESEV to
                        // SUPPAE.QVAL and the test fails, which is the whole point.
                        .column(column("QVAL", 9, DataValueType.STRING).label("Wrong QVAL")
                                .role("Result Qualifier").core("Perm").codelist("AESEV").build())
                        .build())
                .table(table("SUPPQUAL").label("Supplemental Qualifiers").className("Relationship")
                        .column(column("RDOMAIN", 2, DataValueType.STRING)
                                .label("Related Domain Abbreviation").role("Identifier").core("Req")
                                .codelist("DOMAIN").build())
                        .column(column("QVAL", 8, DataValueType.STRING).label("Data Value")
                                .role("Result Qualifier").core("Req").build())
                        .build())
                .codelist(codelist("AESEV").extensible(Boolean.FALSE)
                        .meta(MetadataKeys.CODELIST_CONCEPT_ID, "C66769")
                        .entry("MILD", "Mild", "C41338").entry("MODERATE", "Moderate", "C41339")
                        .entry("SEVERE", "Severe", "C41340").build())
                .codelist(codelist("DOMAIN").extensible(Boolean.TRUE)
                        .meta(MetadataKeys.CODELIST_CONCEPT_ID, "C66734")
                        .entry("AE", "Adverse", "C49562").entry("LB", "Lab", "C49563").build())
                .build();
    }


    private static Map<String, Object> sdtmVar(String name, String label, String ordinal,
            String type, String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", label);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", type);
        v.put("core", core);
        return v;
    }


    /** An SDTM product whose Events class contributes {@code --SEV} and {@code --TERM} to AE. */
    private static SdtmProduct sdtmProduct()
    {
        List<Map<String, Object>> eventsVars = new ArrayList<>();
        eventsVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        eventsVars.add(sdtmVar("--TERM", "Reported Term", "3", "Char", "Req"));
        eventsVars.add(sdtmVar("--SEV", "Severity/Intensity", "8", "Char", "Perm"));

        Map<String, Object> ae = new LinkedHashMap<>();
        ae.put("name", "AE");
        ae.put("label", "Adverse Events");
        ae.put("datasetStructure", "One record per event per subject");

        Map<String, Object> events = new LinkedHashMap<>();
        events.put("name", "Events");
        events.put("classVariables", eventsVars);
        events.put("datasets", List.of(ae));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(events));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static MetadataLibraryProvider provider()
    {
        return new MetadataLibraryProvider(canonicalLibrary(), sdtmProduct(), "sdtmig", "3-4");
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------


    @Test
    @DisplayName("baseline — the canonical name resolves through leg 1 with the codelist family")
    void canonicalName_carriesTheCodelistFamily()
    {
        Map<String, String> ae = provider().getVariableMetadata("AE", "AESEV");

        assertEquals("AESEV", ae.get("codelist"));
        assertEquals("C66769", ae.get("ccode"));
        assertEquals("false", ae.get("codelist_extensible"));
        assertEquals(3, provider().getCodelistCodeMap("AE", "AESEV").size());
    }


    @Test
    @DisplayName("⭐ AP-- inherits the PARENT domain's codelist (AP is a copy of its parent)")
    void apMember_inheritsParentCodelist()
    {
        Map<String, String> apae = provider().getVariableMetadata("APAE", "AESEV");

        assertEquals("Severity/Intensity", apae.get("label"), "leg 2's own answer is preserved");
        assertEquals("AESEV", apae.get("codelist"), "before Fix #373 this key was absent");
        assertEquals("C66769", apae.get("ccode"));
        assertEquals("false", apae.get("codelist_extensible"));
    }


    @Test
    @DisplayName("⭐ SUPP-- inherits SUPPQUAL's OWN codelist, not the related domain's")
    void suppMember_inheritsSuppqualOwnCodelist()
    {
        Map<String, String> suppae = provider().getVariableMetadata("SUPPAE", "RDOMAIN");

        assertEquals("DOMAIN", suppae.get("codelist"),
                "SUPPQUAL binds RDOMAIN to DOMAIN — AE has no such variable at all");
        assertEquals("C66734", suppae.get("ccode"));
        assertEquals("true", suppae.get("codelist_extensible"));
    }


    @Test
    @DisplayName("⭐⭐ THE HAZARD — SUPPAE.QVAL must NOT borrow the parent's codelist")
    void suppQval_neverBorrowsTheParentsCodelist()
    {
        Map<String, String> qval = provider().getVariableMetadata("SUPPAE", "QVAL");

        assertFalse(qval.isEmpty(), "QVAL still resolves — only the codelist family is at issue");
        assertFalse(qval.containsKey("codelist"),
                "SUPPQUAL publishes no codelist for QVAL: its meaning varies per QNAM row. A "
                        + "'borrow the parent domain' design would put AE's codelist here and fire "
                        + "on conforming data");
        assertFalse(qval.containsKey("ccode"));
        assertFalse(qval.containsKey("codelist_extensible"));
        assertTrue(provider().getCodelistCodeMap("SUPPAE", "QVAL").isEmpty());
    }


    @Test
    @DisplayName("no invention — a canonical variable with no codelist stays bare")
    void variableWithoutCodelist_staysBare()
    {
        Map<String, String> apTerm = provider().getVariableMetadata("APAE", "AETERM");

        assertFalse(apTerm.isEmpty());
        assertFalse(apTerm.containsKey("codelist"),
                "AE.AETERM has no codelist either — the enrichment must not fabricate one");
    }


    @Test
    @DisplayName("⭐ getCodelistCodeMap — the SECOND entry point gets the same canonical fallback")
    void codelistCodeMap_resolvesThroughTheCanonicalName()
    {
        assertEquals(3, provider().getCodelistCodeMap("APAE", "AESEV").size(),
                "backs library_variable_code_pair_matches (FDA-CT2003 / PMDA-CT2003), which was "
                        + "silent on every AP and SUPP dataset");
        assertEquals(2, provider().getCodelistCodeMap("SUPPAE", "RDOMAIN").size());
    }


    @Test
    @DisplayName("a non-derived name short-circuits — canonical == original, nothing to enrich")
    void nonDerivedName_isUntouched()
    {
        Map<String, String> direct = provider().getVariableMetadata("AE", "AESEV");
        Map<String, String> viaLegOne = provider().getVariableMetadata("AE", "AETERM");

        assertEquals("AESEV", direct.get("codelist"));
        assertFalse(viaLegOne.containsKey("codelist"));
    }


    @Test
    @DisplayName("gap — the SQ-- prefix canonicalises too (SQAE, not just SUPPAE)")
    void sqPrefix_alsoCanonicalises()
    {
        Map<String, String> sqae = provider().getVariableMetadata("SQAE", "RDOMAIN");

        assertEquals("DOMAIN", sqae.get("codelist"),
                "SQ-- is the second supplemental spelling and takes the same SUPPQUAL leg");
        assertEquals(2, provider().getCodelistCodeMap("SQAE", "RDOMAIN").size());
    }


    @Test
    @DisplayName("⭐ the length>2 guard — a bare \"AP\" / \"SQ\" is a domain, not a derived prefix")
    void twoCharacterName_isNotAPrefix()
    {
        // ⛔ Rewritten after the review. The previous version asserted on AE.AESEV, which resolves
        // through leg 1 and never reaches canonicalSdtmDomain at all — deleting the length>2 guard
        // left the whole class green. These inputs DO reach it: without the guard "AP" would strip
        // to "" and "SQ" would canonicalise to SUPPQUAL, so the provider would answer a
        // two-character domain from the wrong table entirely.
        assertTrue(provider().getVariableMetadata("AP", "AESEV").isEmpty(),
                "\"AP\" must stay \"AP\" — stripping it yields the empty key");
        assertTrue(provider().getCodelistCodeMap("AP", "AESEV").isEmpty());
        assertTrue(provider().getCodelistCodeMap("SQ", "RDOMAIN").isEmpty(),
                "\"SQ\" is two characters: it must NOT canonicalise to SUPPQUAL");
    }


    @Test
    @DisplayName("gap — leg 2 resolves a variable the canonical TABLE does not publish")
    void variableAbsentFromCanonicalTable_keepsLegTwoAnswer()
    {
        // STUDYID reaches leg 2 from the product's class walk, but the fixture's AE table (leg 1)
        // has no STUDYID column — so findColumn misses and the enrichment must leave leg 2's
        // answer exactly as it was rather than dropping it.
        Map<String, String> studyid = provider().getVariableMetadata("APAE", "STUDYID");

        assertEquals("Study Identifier", studyid.get("label"));
        assertFalse(studyid.containsKey("codelist"));
    }
}
