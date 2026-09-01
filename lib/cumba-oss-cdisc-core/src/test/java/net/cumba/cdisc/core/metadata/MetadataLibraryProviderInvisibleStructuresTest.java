package net.cumba.cdisc.core.metadata;

import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.column;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.lib;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.ScopeMatcher;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider.DeclaredAdamProduct;
import net.cumba.cdisc.core.model.ClassScope;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * <b>Phase 6a of {@code plans/PLAN-metadata-product-selection.md} — the three structures no token
 * could reach.</b>
 *
 * <p>
 * Verified against the pickle cache on 2026-08-27, {@code adamStructuresForToken} could address
 * none of these:
 * </p>
 *
 * <table>
 * <caption>the three</caption>
 * <tr>
 * <th>product</th>
 * <th>structure</th>
 * <th>{@code class}</th>
 * <th>{@code subClass}</th>
 * </tr>
 * <tr>
 * <td>{@code adam-tte-1-0}</td>
 * <td>{@code BDS for TTE}</td>
 * <td><b>null</b></td>
 * <td>null</td>
 * </tr>
 * <tr>
 * <td>{@code adam-adae-1-0}</td>
 * <td>{@code ADAE}</td>
 * <td>{@code "ADAE"}</td>
 * <td>null</td>
 * </tr>
 * <tr>
 * <td>{@code tig/1-0/adam}</td>
 * <td>{@code REFERENDS}</td>
 * <td>{@code REFERENCE DATA STRUCTURE}</td>
 * <td>null</td>
 * </tr>
 * </table>
 *
 * <p>
 * Declaring any of them was a <b>silent no-op</b> — under ruling 1 worse than the metadata conflict
 * this plan set out to fix.
 * </p>
 *
 * <h2>⛔⛔ Why the {@code ADAE} alias and its subclass override are one change</h2>
 *
 * <p>
 * {@code ADAE} publishes {@code subClass == null}. Adding the {@code "ADAE" → OCCURRENCE DATA
 * STRUCTURE} class alias without the {@code ADVERSE EVENT} override makes it a <b>base</b>
 * occurrence structure, and {@code required_variables()} would then demand {@code AETERM},
 * {@code AEDECOD}, {@code AEBODSYS}, {@code AESER} and {@code AESEQ} of <em>every</em> occurrence
 * dataset — ADCM, ADMH, everything: the false-positive machine this plan exists to remove,
 * reintroduced through the back door. {@link #adcmShapedDataDoesNotGetAeVariables()} is the guard.
 * </p>
 */
class MetadataLibraryProviderInvisibleStructuresTest
{

    private static final String OCCDS_TOKEN = AdamDataStructureDetector.OCCDS;

    private static final String BDS_TOKEN = AdamDataStructureDetector.BDS;

    private static final String REFERENCE = AdamDataStructureDetector.REFERENCE_DATA_STRUCTURE;

    private static final String ADVERSE_EVENT = AdamSubclassDetector.ADVERSE_EVENT;

    private static final String TTE = AdamSubclassDetector.TIME_TO_EVENT;

    private static final String MD_TTE = AdamSubclassDetector.MEDICAL_DEVICE_TIME_TO_EVENT;

    private static final String ADAE_KEY = "standards/adam/adam-adae-1-0";

    private static final String TTE_KEY = "standards/adam/adam-tte-1-0";

    private static final String TIG_ADAM_KEY = "standards/tig/1-0/adam";

    // ------------------------------------------------------------------
    // Fixtures — shaped exactly like the real cached products
    // ------------------------------------------------------------------

    private static Map<String, Object> adamVar(String name, String ordinal, String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", name);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", "Char");
        v.put("core", core);
        return v;
    }


    /** {@code aClassName == null} reproduces {@code adam-tte-1-0}: no {@code class} key at all. */
    private static Map<String, Object> structure(String name, @Nullable String aClassName,
            @Nullable String subClass, List<Map<String, Object>> vars)
    {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Variables");
        set.put("ordinal", "1");
        set.put("analysisVariables", vars);

        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        ds.put("label", name);
        if (aClassName != null)
        {
            ds.put("class", aClassName);
        }
        if (subClass != null)
        {
            ds.put("subClass", subClass);
        }
        ds.put("analysisVariableSets", List.of(set));
        return ds;
    }


    @SafeVarargs

    @SuppressWarnings("varargs") // passes its own varargs array to List.of
    private static AdamProduct product(String name, Map<String, Object>... structures)
    {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", name);
        product.put("version", "1-0");
        product.put("dataStructures", List.of(structures));
        return net.cumba.web.api.dev.MapResource.of(product, AdamProduct.class);
    }


    /** {@code adam-adae-1-0}: one structure, class {@code "ADAE"}, {@code subClass} null. */
    private static AdamProduct adae10()
    {
        return product("adam-adae",
                structure("ADAE", "ADAE", null,
                        List.of(adamVar("STUDYID", "1", "Req"), adamVar("USUBJID", "2", "Req"),
                                adamVar("AETERM", "3", "Req"), adamVar("AEDECOD", "4", "Req"),
                                adamVar("AEBODSYS", "5", "Req"), adamVar("AESER", "6", "Req"),
                                adamVar("AESEQ", "7", "Req"), adamVar("ONTRTFL", "8", "Perm"))));
    }


    /** {@code adam-tte-1-0}: one structure, NO class, no subClass, carrying {@code CNSR}. */
    private static AdamProduct tte10()
    {
        return product("adam-tte",
                structure("BDS for TTE", null, null,
                        List.of(adamVar("STUDYID", "1", "Req"), adamVar("USUBJID", "2", "Req"),
                                adamVar("PARAM", "3", "Req"), adamVar("PARAMCD", "4", "Req"),
                                adamVar("AVAL", "5", "Req"), adamVar("TRTP", "6", "Req"),
                                adamVar("CNSR", "7", "Cond"))));
    }


    /** {@code tig/1-0/adam}: ADSL, BDS, OCCDS and the reference data structure. */
    private static AdamProduct tigAdam10()
    {
        return product("tig-adam",
                structure("ADSL", AdamDataStructureDetector.ADSL, null,
                        List.of(adamVar("USUBJID", "1", "Req"))),
                structure("BDS", BDS_TOKEN, null, List.of(adamVar("PARAMCD", "1", "Req"))),
                structure("OCCDS", OCCDS_TOKEN, null, List.of(adamVar("AESEQ", "1", "Cond"))),
                structure("REFERENDS", REFERENCE, null,
                        List.of(adamVar("STUDYID", "1", "Req"), adamVar("SRCVAR", "2", "Req"))));
    }


    private static IMetadataLibrary study()
    {
        return lib("study").table(
                table("ADAE").column(column("USUBJID", 0, DataValueType.STRING).build()).build())
                .build();
    }


    private static MetadataLibraryProvider provider(DeclaredAdamProduct... declared)
    {
        return new MetadataLibraryProvider(study(), List.of(declared), "adamig", "1-3");
    }

    // ------------------------------------------------------------------
    // REFERENCE DATA STRUCTURE
    // ------------------------------------------------------------------


    @Test
    void referendsIsReachableByTheNewToken()
    {
        // Before Phase 6a, REFERENCE DATA STRUCTURE was not a token at all, so no lookup could
        // ever ask for this structure — declaring tig/1-0/adam simply lost it.
        MetadataLibraryProvider p = provider(new DeclaredAdamProduct(TIG_ADAM_KEY, tigAdam10()));

        assertEquals(List.of("STUDYID", "SRCVAR"),
                p.getRequiredVariablesForStructure(REFERENCE, List.of()));
    }


    @Test
    void referenceDataStructureHasNoSupertypeAndDoesNotFoldOntoAdamOther()
    {
        // ⚠ Same reasoning the owner applied to DEVICE LEVEL ANALYSIS DATASET on 2026-08-09:
        // ADAM OTHER means structure-LESS, and a reference data structure is not structure-less.
        assertEquals(List.of(REFERENCE), AdamDataStructureDetector.structureSet(REFERENCE));
        assertFalse(AdamDataStructureDetector.structureSet(REFERENCE)
                .contains(AdamDataStructureDetector.OTHER));
    }


    @Test
    void aDeclaredReferenceClassStopsMatchingTheAdamOtherRules()
    {
        // ⚠ The one real behaviour change of Phase 6a, asserted rather than discovered. Before,
        // structureTokenFromDeclaredClass returned null for this declaration and the heuristic
        // landed on ADAM OTHER, so the 8 rules scoped Classes.Include:[ADAM OTHER] reached it.
        assertEquals(REFERENCE,
                AdamDataStructureDetector.structureTokenFromDeclaredClass(REFERENCE));

        List<String> detected = AdamDataStructureDetector.detectAll("REFERENDS",
                List.of("STUDYID", "SRCVAR"), REFERENCE, true);
        assertEquals(List.of(REFERENCE), detected);
        assertFalse(detected.contains(AdamDataStructureDetector.OTHER));

        // The ADaM arm of the Classes gate is judged from that structure set (Fix #206): a rule
        // matches when SOME detected token satisfies it.
        Rule adamOtherRule = classRule(List.of(AdamDataStructureDetector.OTHER));
        assertTrue(
                detected.stream().allMatch(
                        t -> ScopeMatcher.describeClassMismatch(adamOtherRule, t) != null),
                "a reference dataset must no longer be reached by the ADAM OTHER rules");
        // Control: the same dataset without the declaration still lands on ADAM OTHER.
        List<String> undeclared = AdamDataStructureDetector.detectAll("REFERENDS",
                List.of("STUDYID", "SRCVAR"), null, true);
        assertEquals(List.of(AdamDataStructureDetector.OTHER), undeclared);
        assertNull(ScopeMatcher.describeClassMismatch(adamOtherRule, undeclared.get(0)));
    }

    // ------------------------------------------------------------------
    // adam-adae-1-0 / ADAE — the alias and the override, together
    // ------------------------------------------------------------------


    @Test
    void adaeIsReachableByTheOccurrenceTokenOnAnAdverseEventDataset()
    {
        MetadataLibraryProvider p = provider(new DeclaredAdamProduct(ADAE_KEY, adae10()));

        assertEquals(
                List.of("STUDYID", "USUBJID", "AETERM", "AEDECOD", "AEBODSYS", "AESER", "AESEQ"),
                p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
    }


    @Test
    void adcmShapedDataDoesNotGetAeVariables()
    {
        // ⛔⛔ THE guard. An occurrence dataset with no AE signal resolves NO subclass, so the
        // chain has no applicable tier in this product: null ("no such structure here") and the
        // outer token chain SKIPs loudly. Without the ADVERSE EVENT override, ADAE would be read
        // as a BASE occurrence structure and this would return the five AE-specific Req names.
        MetadataLibraryProvider p = provider(new DeclaredAdamProduct(ADAE_KEY, adae10()));

        List<String> forAdcm = p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of());
        assertNull(forAdcm,
                () -> "an ADCM-shaped occurrence dataset must not resolve against ADAE; got "
                        + forAdcm);
    }


    @Test
    void theOverrideIsKeyedByProductNotByStructureName()
    {
        // A like-named structure in a DIFFERENT product must not pick the override up: keying on
        // the bare name would let it leak (adamig-1-3 also publishes a BDS, adam-occds-1-1 an AE).
        AdamProduct lookalike = product("adamig",
                structure("ADAE", OCCDS_TOKEN, null, List.of(adamVar("USUBJID", "1", "Req"))));
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adamig-1-3", lookalike));

        assertEquals(List.of("USUBJID"), p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()),
                "this ADAE publishes its own OCCURRENCE class and is a genuine base structure");
    }

    // ------------------------------------------------------------------
    // adam-tte-1-0 / BDS for TTE — the null class, supplied
    // ------------------------------------------------------------------


    @Test
    void bdsForTteGetsItsNullClassSuppliedAndItsSubclassRestored()
    {
        MetadataLibraryProvider p = provider(new DeclaredAdamProduct(TTE_KEY, tte10()));

        assertEquals(List.of("STUDYID", "USUBJID", "PARAM", "PARAMCD", "AVAL", "TRTP"),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(TTE)),
                "without the class override this structure is skipped outright (null class)");
        assertNull(p.getRequiredVariablesForStructure(BDS_TOKEN, List.of()),
                "it is a TIME-TO-EVENT specialisation, not a base BDS — a plain BDS dataset must "
                        + "not resolve against it");
    }


    @Test
    void aDeviceTteDatasetReachesTheTteTierThroughItsSupertype()
    {
        // ⭐ Phase 4 × Phase 3. AdamSubclassDetector.resolve now yields [MD TTE, TIME-TO-EVENT] for
        // a device time-to-event dataset, and the governing chain builds one tier per detected
        // token that some structure publishes — so the TTE structure governs. Before Phase 4 the
        // list was [MD TTE] alone, no tier matched, and the answer was null.
        MetadataLibraryProvider p = provider(new DeclaredAdamProduct(TTE_KEY, tte10()));

        assertNotNull(p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(MD_TTE, TTE)));
        assertEquals(p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(TTE)),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(MD_TTE, TTE)));
        assertNull(p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(MD_TTE)),
                "the control: without the supertype behind it, nothing in this product applies");
    }


    private static Rule classRule(List<String> include)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setInclude(include);
        scope.setClasses(cs);
        rule.setScope(scope);
        return rule;
    }
}
