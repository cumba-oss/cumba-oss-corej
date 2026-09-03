package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.corej.core.metadata.MetadataLibraryProvider.DeclaredAdamProduct;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 of {@code plans/PLAN-metadata-product-selection.md} — <b>cross-product specificity</b>.
 *
 * <h2>What was wrong</h2>
 *
 * <p>
 * {@code adamStructuresForToken} returned on the <b>first declared product that had any structure
 * for the token</b>, and Phase 3's governing chain then ran only <em>inside</em> that product. So
 * {@code -mp adam/adamig-1-3,adam/adam-nca-1-0} with the token {@code BASIC DATA STRUCTURE}:
 * {@code adamig-1-3} publishes a base BDS ⇒ the method returned ⇒ {@code adam-nca-1-0}'s
 * {@code ADNCA} was <b>never seen</b>, and an NCA dataset silently resolved against the base
 * structure. It only <em>looked</em> right for {@code OCCDS} because {@code adamig-1-3} publishes
 * no occurrence structure at all — which is why Phase 3's proof run happened to declare the
 * supplement first.
 * </p>
 *
 * <h2>What is pinned here</h2>
 *
 * <p>
 * ⚠⚠ Phase 3's tests were written against supplement-first ordering, so a test that passes only
 * because the supplement is listed first proves nothing about this phase. <b>Every behavioural
 * assertion below is made in both declaration orders</b>, and the orders must agree: candidate
 * structures are pooled across all declared products, specificity decides the chain, and the user's
 * product order survives as the tie-break <em>among equal specificity only</em>.
 * </p>
 */
class MetadataLibraryProviderCrossProductSpecificityTest
{

    private static final String OCCDS_TOKEN = AdamDataStructureDetector.OCCDS;

    private static final String BDS_TOKEN = AdamDataStructureDetector.BDS;

    private static final String ADVERSE_EVENT = AdamSubclassDetector.ADVERSE_EVENT;

    private static final String NCA = AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS;

    private static final String POPPK = AdamSubclassDetector.POPULATION_PHARMACOKINETIC_ANALYSIS;

    private static final String IG_KEY = "standards/adam/adamig-1-3";

    private static final String NCA_KEY = "standards/adam/adam-nca-1-0";

    private static final String POPPK_KEY = "standards/adam/adam-poppk-1-0";

    private static final String OCCDS_KEY = "standards/adam/adam-occds-1-1";

    // ------------------------------------------------------------------
    // Fixtures — one structure per product, so a token only resolves across products
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


    private static Map<String, Object> structure(String name, @Nullable String className,
            @Nullable String subClass, List<Map<String, Object>> vars)
    {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Variables");
        set.put("ordinal", "1");
        set.put("analysisVariables", vars);

        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        ds.put("label", name);
        if (className != null)
        {
            ds.put("class", className);
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


    /** {@code adamig-1-3}'s shape for this test: a base BDS, no subclass of its own. */
    private static DeclaredAdamProduct igBds()
    {
        return new DeclaredAdamProduct(IG_KEY,
                product("adamig",
                        structure("BDS", "BASIC DATA STRUCTURE", null,
                                List.of(adamVar("USUBJID", "1", "Req"),
                                        adamVar("PARAM", "2", "Req"), adamVar("AFRLT", "3", "Cond"),
                                        adamVar("AVISIT", "4", "Cond")))));
    }


    /** {@code adam-nca-1-0}: {@code BASIC DATA STRUCTURE} published ONLY as a subclass. */
    private static DeclaredAdamProduct ncaSupplement()
    {
        return new DeclaredAdamProduct(NCA_KEY,
                product("adam-nca", structure("ADNCA", "BASIC DATA STRUCTURE", NCA,
                        List.of(adamVar("AFRLT", "1", "Req"), adamVar("AVISIT", "2", "Req")))));
    }


    /** {@code adam-poppk-1-0}: another subclass-only supplement on the same token. */
    private static DeclaredAdamProduct poppkSupplement()
    {
        return new DeclaredAdamProduct(POPPK_KEY, product("adam-poppk", structure("ADPPK",
                "BASIC DATA STRUCTURE", POPPK, List.of(adamVar("DOSEA", "1", "Req")))));
    }


    /**
     * The base half of {@code adam-occds-1-1}, in a product of its own: {@code CMTRT} is published
     * here and nowhere else, {@code --SEQ} / {@code --DECOD} are {@code Cond}, {@code ONTRTFL}
     * {@code Cond}.
     */
    private static DeclaredAdamProduct occdsBaseProduct()
    {
        return new DeclaredAdamProduct(IG_KEY,
                product("occds-base", structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Cond"),
                                adamVar("--DECOD", "3", "Cond"), adamVar("CMTRT", "4", "Req"),
                                adamVar("ONTRTFL", "5", "Cond")))));
    }


    /** The {@code AE} specialisation, in a product of its own. */
    private static DeclaredAdamProduct aeSupplement()
    {
        return new DeclaredAdamProduct(OCCDS_KEY,
                product("adam-occds",
                        structure("AE", "OCCURRENCE DATA STRUCTURE", ADVERSE_EVENT, List.of(
                                adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Req"),
                                adamVar("--DECOD", "3", "Req"), adamVar("ONTRTFL", "4", "Perm")))));
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
    // ⭐ The defect: a supplement's specialisation must govern in EITHER order
    // ------------------------------------------------------------------


    @Test
    void baseFirst_theSupplementsSubclassStillGoverns()
    {
        // ⭐⭐ THE PHASE 8 TEST. -mp adam/adamig-1-3,adam/adam-nca-1-0 — the base product first.
        // Before pooling, adamig-1-3's BDS matched, adamStructuresForToken returned, and ADNCA was
        // never consulted: AFRLT and AVISIT came back Cond (absent) for an NCA dataset.
        List<String> required = provider(igBds(), ncaSupplement())
                .getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA));

        assertEquals(List.of("AFRLT", "AVISIT", "USUBJID", "PARAM"), required,
                "ADNCA governs its two names, the IG's base supplies the rest");
    }


    @Test
    void supplementFirstAndBaseFirstGiveTheIdenticalAnswer()
    {
        // Specificity, not order. The two declarations differ only in the order of two products
        // and must resolve identically — this is the assertion Phase 3's ordering hid.
        List<String> baseFirst = provider(igBds(), ncaSupplement())
                .getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA));
        List<String> supplementFirst = provider(ncaSupplement(), igBds())
                .getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA));

        assertEquals(supplementFirst, baseFirst);
        assertEquals(List.of("AFRLT", "AVISIT", "USUBJID", "PARAM"), supplementFirst);
    }


    @Test
    void aPlainBdsDatasetGetsTheBaseInEitherOrder_theSupplementIsNotAppliedToIt()
    {
        // The control for the two tests above: with no detected subclass the ADNCA tier is not in
        // the chain at all, so the six conflicting names stay Cond whichever product is first.
        List<String> baseFirst = provider(igBds(), ncaSupplement())
                .getRequiredVariablesForStructure(BDS_TOKEN, List.of());
        List<String> supplementFirst = provider(ncaSupplement(), igBds())
                .getRequiredVariablesForStructure(BDS_TOKEN, List.of());

        assertEquals(supplementFirst, baseFirst);
        assertEquals(List.of("USUBJID", "PARAM"), baseFirst);
    }


    @Test
    void theOccdsCmtrtEvidenceHoldsInBothOrders()
    {
        // Phase 3's end-to-end evidence, split across two products: the AE specialisation governs
        // --SEQ / --DECOD (Cond in the base) and demotes ONTRTFL, while CMTRT — published only by
        // the base, in the OTHER product — still resolves (govern ≠ replace, across products).
        // Under the per-product short-circuit, base-first lost --SEQ/--DECOD and supplement-first
        // lost CMTRT: neither order could produce this list.
        List<String> baseFirst = provider(occdsBaseProduct(), aeSupplement())
                .getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT));
        List<String> supplementFirst = provider(aeSupplement(), occdsBaseProduct())
                .getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT));

        assertEquals(supplementFirst, baseFirst, "the answer must not depend on declaration order");
        assertNotNull(baseFirst);
        assertTrue(baseFirst.contains("--SEQ") && baseFirst.contains("--DECOD"),
                () -> "the AE tier governs the names the base made Cond; got " + baseFirst);
        assertTrue(baseFirst.contains("CMTRT"),
                () -> "CMTRT is published only by the base product and must survive; got "
                        + baseFirst);
        assertFalse(baseFirst.contains("ONTRTFL"),
                () -> "AE demotes ONTRTFL to Perm and governs it; got " + baseFirst);
    }


    @Test
    void twoSupplementsAndABase_eachSubclassGovernsItsOwnDataset()
    {
        // Three products, one token, one base: the tier chosen is the dataset's, not the first
        // declared product's.
        MetadataLibraryProvider p = provider(igBds(), ncaSupplement(), poppkSupplement());

        assertEquals(List.of("AFRLT", "AVISIT", "USUBJID", "PARAM"),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA)));
        assertEquals(List.of("DOSEA", "USUBJID", "PARAM"),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(POPPK)));
    }

    // ------------------------------------------------------------------
    // Product order survives as the tie-break among EQUAL specificity
    // ------------------------------------------------------------------


    @Test
    void equalSpecificityTieBreaksByDeclarationOrderAndSaysSo()
    {
        // Two products publishing an equally-specific BASE structure for the same token are two
        // competing descriptions of the same thing, and there is no specificity with which to
        // choose: ruling 1's declaration order decides, and the loser is named in the log rather
        // than dropped silently.
        DeclaredAdamProduct otherBase = new DeclaredAdamProduct(POPPK_KEY,
                product("other", structure("BDS", "BASIC DATA STRUCTURE", null,
                        List.of(adamVar("ZZTOP", "1", "Req")))));

        List<String> logged = new ArrayList<>();
        assertEquals(List.of("USUBJID", "PARAM"),
                captureInto(logged,
                        () -> provider(igBds(), otherBase)
                                .getRequiredVariablesForStructure(BDS_TOKEN, List.of())),
                "the first declared product governs the base level");
        assertEquals(List.of("ZZTOP"),
                provider(otherBase, igBds()).getRequiredVariablesForStructure(BDS_TOKEN, List.of()),
                "…and the reverse declaration gives the reverse answer: the order is the user's");

        assertTrue(
                logged.stream()
                        .anyMatch(m -> m.contains(IG_KEY) && m.contains(POPPK_KEY)
                                && m.contains("declaration order")),
                () -> "the losing product must be named; got " + logged);
    }


    @Test
    void theTieBreakIsPerSpecificityLevel_notPerToken()
    {
        // The distinction the defect blurred. The IG wins the BASE level (declared first), and
        // the supplement still wins the ADVERSE EVENT level — one token, two products, two levels.
        DeclaredAdamProduct igOccds = new DeclaredAdamProduct(IG_KEY,
                product("adamig", structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                        List.of(adamVar("ZZTOP", "1", "Req")))));
        DeclaredAdamProduct supplementWithBase = new DeclaredAdamProduct(OCCDS_KEY,
                product("adam-occds",
                        structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                                List.of(adamVar("CMTRT", "1", "Req"))),
                        structure("AE", "OCCURRENCE DATA STRUCTURE", ADVERSE_EVENT,
                                List.of(adamVar("--SEQ", "1", "Req")))));

        List<String> required = provider(igOccds, supplementWithBase)
                .getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT));

        assertNotNull(required);
        assertTrue(required.contains("--SEQ"),
                () -> "the supplement's specialisation governs even though it is declared second; "
                        + "got " + required);
        assertTrue(required.contains("ZZTOP"),
                () -> "the first-declared product wins the equally-specific base level; got "
                        + required);
        assertFalse(required.contains("CMTRT"),
                () -> "the second product's BASE structure lost the tie-break at that level; got "
                        + required);
    }

    // ------------------------------------------------------------------
    // ⛔ §3d item 1 — the empty chain is still null, now across the whole pool
    // ------------------------------------------------------------------


    @Test
    void aSubclassOnlyProductNoLongerHidesAnotherProductsBase()
    {
        // The §3d null rule × pooling. adam-nca-1-0 publishes BASIC DATA STRUCTURE only as
        // NON-COMPARTMENTAL ANALYSIS, so for a plain BDS dataset its chain is empty. Before
        // pooling that null was the whole answer — declared first, it shadowed adamig-1-3's base
        // BDS and the rule SKIPped although the run had a perfectly good base structure.
        assertEquals(List.of("USUBJID", "PARAM"), provider(ncaSupplement(), igBds())
                .getRequiredVariablesForStructure(BDS_TOKEN, List.of()));
    }


    @Test
    void anEmptyChainAcrossEveryDeclaredProductIsStillNullNotAnEmptyList()
    {
        // ⛔ The rule must survive pooling: when NO declared product has an applicable structure,
        // the answer is null ("no such structure here"), never List.of() ("requires nothing"),
        // which would pass the rule vacuously. Both subclass-only products are inapplicable to a
        // plain BDS dataset, and the log must name both.
        MetadataLibraryProvider p = provider(ncaSupplement(), poppkSupplement());

        List<String> logged = new ArrayList<>();
        assertNull(captureInto(logged,
                () -> p.getRequiredVariablesForStructure(BDS_TOKEN, List.of())));

        assertTrue(
                logged.stream()
                        .anyMatch(m -> m.contains("only under subclasses") && m.contains(NCA_KEY)
                                && m.contains(POPPK_KEY)),
                () -> "the null must name every product it consulted; got " + logged);
        // …and it is a statement about applicability, not about the token: an applicable subclass
        // resolves from the same pool.
        assertEquals(List.of("DOSEA"),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(POPPK)));
    }

    // ------------------------------------------------------------------
    // ⚠⚠ Provenance must survive pooling
    // ------------------------------------------------------------------


    @Test
    void provenanceNamesTheProductBehindEachContributingStructure()
    {
        // ⚠⚠ A pooled answer can mix products — the governing tier from a supplement, the base
        // behind it from the IG — and StructureMatch's single cache key could no longer describe
        // it. Every contributing structure is logged with the product that published it, or a
        // finding stops being traceable to the metadata that produced it.
        List<String> logged = new ArrayList<>();
        captureInto(logged, () -> provider(igBds(), ncaSupplement())
                .getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA)));

        assertTrue(
                logged.stream()
                        .anyMatch(m -> m.contains("ADNCA@" + NCA_KEY) && m.contains("BDS@" + IG_KEY)
                                && m.contains(NCA)),
                () -> "both contributing structures must be named with their product; got "
                        + logged);
    }


    @Test
    void aProductKeyedOverrideIsResolvedAgainstEachStructuresOwnProduct()
    {
        // ⚠⚠ Provenance is load-bearing INSIDE the pool, not only in the logs. SUBCLASS_OVERRIDES
        // is keyed by (cacheKey, structureName) precisely so it cannot fire on a like-named
        // structure elsewhere — and once candidates are pooled, "elsewhere" is in the same list.
        // Resolving every pooled structure's subclass against one product's key would either lose
        // adam-adae-1-0's ADVERSE EVENT identity or leak it onto the look-alike, and the
        // look-alike would stop being the base it genuinely is.
        DeclaredAdamProduct lookalike = new DeclaredAdamProduct(IG_KEY,
                product("adamig", structure("ADAE", "OCCURRENCE DATA STRUCTURE", null,
                        List.of(adamVar("ZZBASE", "1", "Req")))));
        DeclaredAdamProduct realAdae = new DeclaredAdamProduct("standards/adam/adam-adae-1-0",
                product("adam-adae",
                        structure("ADAE", "ADAE", null, List.of(adamVar("AETERM", "1", "Req")))));

        for (MetadataLibraryProvider p : List.of(provider(lookalike, realAdae),
                provider(realAdae, lookalike)))
        {
            assertEquals(List.of("AETERM", "ZZBASE"),
                    p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                    "adam-adae-1-0's ADAE keeps its overridden ADVERSE EVENT identity in the pool");
            assertEquals(List.of("ZZBASE"),
                    p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()),
                    "…and the override must not leak onto another product's like-named base");
        }
    }


    @Test
    void aProductKeyedClassOverrideIsAlsoResolvedAgainstItsOwnProduct()
    {
        // The same provenance requirement on the OTHER override map. STRUCTURE_CLASS_OVERRIDES
        // supplies adam-tte-1-0/"BDS for TTE"'s missing class; keyed against the wrong product it
        // either makes that structure invisible to BASIC DATA STRUCTURE (so a TTE dataset silently
        // resolves against the plain base) or hands a class to a like-named structure that
        // published none — and a structure CDISC gave no class is not one this engine may invent.
        DeclaredAdamProduct tte = new DeclaredAdamProduct("standards/adam/adam-tte-1-0", product(
                "adam-tte",
                structure("BDS for TTE", null, null, List.of(adamVar("CNSR", "1", "Req")))));
        DeclaredAdamProduct lookalike = new DeclaredAdamProduct(POPPK_KEY, product("other",
                structure("BDS for TTE", null, null, List.of(adamVar("ZZINVIS", "1", "Req")))));
        List<String> timeToEvent = List.of(AdamSubclassDetector.TIME_TO_EVENT);

        assertEquals(List.of("CNSR", "USUBJID", "PARAM"),
                provider(igBds(), tte).getRequiredVariablesForStructure(BDS_TOKEN, timeToEvent),
                "the override belongs to adam-tte-1-0 wherever in the list it is declared");

        List<String> withLookalike = provider(tte, lookalike)
                .getRequiredVariablesForStructure(BDS_TOKEN, timeToEvent);
        assertNotNull(withLookalike);
        assertTrue(withLookalike.contains("CNSR"));
        assertFalse(withLookalike.contains("ZZINVIS"),
                () -> "a classless structure in another product stays invisible; got "
                        + withLookalike);
    }


    @Test
    void aSingleProductChainIsNotAnnouncedAsPooled()
    {
        // The provenance line exists for the case pooling created. A one-product answer — the
        // overwhelming majority, and byte-for-byte today's behaviour — must not start logging.
        List<String> logged = new ArrayList<>();
        captureInto(logged,
                () -> provider(igBds()).getRequiredVariablesForStructure(BDS_TOKEN, List.of()));

        assertTrue(logged.stream().noneMatch(m -> m.contains("resolves across")),
                () -> "a single-product chain is not a pooled one; got " + logged);
    }


    @Test
    void expectedMirrorsRequiredAcrossProductsToo()
    {
        MetadataLibraryProvider p = provider(igBds(), ncaSupplement());
        assertEquals(p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA)),
                p.getExpectedVariablesForStructure(BDS_TOKEN, List.of(NCA)));
    }

    // ------------------------------------------------------------------
    // ⛔⛔ Phase 11 finding F1 — the DOMAIN-keyed accessors had the very same defect
    //
    // Phase 8 pooled candidate structures inside adamNamesWhereCore only. Its neighbours
    // findAdamDataStructureByClassName / adamDataStructureFor had also been widened from one
    // product to N (Phase 2) and were left as plain first-match-wins walks over the raw published
    // `class` string — no specificity, no alias table, no product-keyed overrides. They feed
    // buildResolvedAdam -> getStandardModelVariables* -> GET_MODEL_COLUMN_ORDER, and
    // adamClassForDomain -> getDatasetClass. Every assertion below is made in BOTH declaration
    // orders wherever order can matter, exactly as the Phase 8 block above.
    // ------------------------------------------------------------------


    @Test
    void theClassNameFallbackIsOrderIndependent_aPlainBdsDatasetGetsTheBase()
    {
        // ⭐⭐ THE F1 TEST. ADVS is AD-prefixed and not ADSL, so buildResolvedAdam's conventional
        // fallback asks for BASIC DATA STRUCTURE. Before the fix the walk returned the FIRST
        // product publishing that class string: declaring the NCA supplement first answered with
        // ADNCA's subclass-only vocabulary, declaring the IG first answered with the base BDS.
        List<String> baseFirst = provider(igBds(), ncaSupplement())
                .getStandardModelVariables(mockTable("ADVS"), null);
        List<String> supplementFirst = provider(ncaSupplement(), igBds())
                .getStandardModelVariables(mockTable("ADVS"), null);

        assertEquals(baseFirst, supplementFirst, "the answer must not depend on declaration order");
        assertEquals(List.of("USUBJID", "PARAM", "AFRLT", "AVISIT"), baseFirst,
                "a plain BDS dataset resolves against the base structure, in ordinal order");
    }


    @Test
    void theClassNameFallbackReachesAProductThatSpellsItsClassTheOldWay()
    {
        // The alias half of F1. adamig-1-0/1-1/1-2 spell the class `BDS`, adamig-1-3 spells it
        // `BASIC DATA STRUCTURE`. The old raw-string comparison against the conventional fallback
        // token matched 1-3 and silently NOTHING in the three older products — the same
        // silent-empty shape as the lint-rules.py version-key bug.
        DeclaredAdamProduct oldSpelling = new DeclaredAdamProduct("standards/adam/adamig-1-0",
                product("adamig-1-0", structure("BDS", "BDS", null,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("AVAL", "2", "Req")))));

        assertEquals(List.of("USUBJID", "AVAL"),
                provider(oldSpelling).getStandardModelVariables(mockTable("ADVS"), null),
                "the class-name fallback must go through ADAM_CLASS_ALIASES, not compare raw");
    }


    @Test
    void aSubclassOnlyProductDoesNotAnswerForAPlainDataset_andSaysSo()
    {
        // §3d, now on the class-keyed path too: adam-nca-1-0 publishes BASIC DATA STRUCTURE ONLY
        // under the NCA subclass, so for a dataset with no such subclass the structure is absent —
        // not "a structure that happens to be the only one mentioning the token". Before the fix
        // this returned ADNCA's [AFRLT, AVISIT] for any AD-prefixed dataset.
        List<String> logged = new ArrayList<>();
        List<String> resolved = captureInto(logged,
                () -> provider(ncaSupplement()).getStandardModelVariables(mockTable("ADVS"), null));

        assertNotNull(resolved);
        assertTrue(resolved.isEmpty(),
                () -> "a subclass-only structure must not answer for a plain dataset; got "
                        + resolved);
        // ⭐ Proves the class-keyed path runs the SHARED implementation rather than a second copy
        // of it: this log line is emitted by governingTiers and by nothing else.
        assertTrue(logged.stream()
                .anyMatch(m -> m.contains("only under subclasses this dataset does not have")
                        && m.contains(NCA_KEY)),
                () -> "the §3d line must name the declared product; got " + logged);
    }


    @Test
    void theClassNameFallbackPoolsAcrossProductsForTheAllowedVariableUniverse()
    {
        // govern ≠ replace on the domain-keyed side: with the dataset's subclass unknown only the
        // base tier governs, but two products publishing an equally-specific base still tie-break
        // by declaration order rather than one of them vanishing.
        DeclaredAdamProduct otherBase = new DeclaredAdamProduct(POPPK_KEY,
                product("other", structure("BDS", "BASIC DATA STRUCTURE", null,
                        List.of(adamVar("ZZTOP", "1", "Req")))));

        assertEquals(List.of("USUBJID", "PARAM", "AFRLT", "AVISIT"),
                provider(igBds(), otherBase).getStandardModelVariables(mockTable("ADVS"), null));
        assertEquals(List.of("ZZTOP"),
                provider(otherBase, igBds()).getStandardModelVariables(mockTable("ADVS"), null));
    }


    @Test
    void getDatasetClassCanonicalisesTheDeclaringProductsClassSpelling()
    {
        // The secondary half of F1. adam-adae-1-0 publishes its single structure under the class
        // string "ADAE". adamClassForDomain returned that verbatim, so declaring the product made
        // getDatasetClass("ADAE") answer a token that is in NO consumer's vocabulary — worse than
        // the ADAM OTHER sentinel the same dataset got before the product could be declared.
        DeclaredAdamProduct adae = new DeclaredAdamProduct("standards/adam/adam-adae-1-0",
                product("adam-adae",
                        structure("ADAE", "ADAE", null, List.of(adamVar("AETERM", "1", "Req")))));

        assertEquals(AdamDataStructureDetector.OCCDS, provider(adae).getDatasetClass("ADAE"),
                "the published class spelling must be canonicalised to a STRUCTURE_TOKENS token");
        assertTrue(
                AdamDataStructureDetector.STRUCTURE_TOKENS
                        .contains(provider(adae).getDatasetClass("ADAE")),
                "whatever getDatasetClass answers on the ADaM product tier must be a known token");
    }


    @Test
    void getDatasetClassFallsThroughWhenTheProductsClassMapsToNoToken()
    {
        // The other side of the same coin: a class the detector vocabulary does not know must
        // yield null from the product tier so the remaining tiers still get their turn — here the
        // FU-4 ADAM OTHER sentinel. Returning the unknown string verbatim (the old behaviour)
        // both skipped those tiers and handed ScopeMatcher a token it cannot match.
        DeclaredAdamProduct odd = new DeclaredAdamProduct("standards/adam/adam-odd-1-0",
                product("odd", structure("ADZZZ", "SOME UNMAPPED CLASS", null,
                        List.of(adamVar("ZZVAR", "1", "Req")))));

        assertEquals(AdamDataStructureDetector.OTHER, provider(odd).getDatasetClass("ADZZZ"));
    }


    @Test
    void theClassNameFallbackReadsTheStudyDeclaredSubclass_andGovernStillDoesNotReplace()
    {
        // The dataset's own columns are not reachable on the domain-keyed path, so the specificity
        // chain is fed the study-declared def:SubClass — the same tier
        // AdamStructureContext.declaredSubClassesOf reads. ADNCA's tier then governs and the IG's
        // base still contributes behind it: govern ≠ replace, across products, on this path too.
        IMetadataLibrary declaring = lib("study").table(
                table("ADPC").className(AdamDataStructureDetector.BDS).subClassNames(NCA).build())
                .build();
        List<String> baseFirst = new MetadataLibraryProvider(declaring,
                List.of(igBds(), ncaSupplement()), "adamig", "1-3")
                        .getStandardModelVariables(mockTable("ADPC"), null);
        List<String> supplementFirst = new MetadataLibraryProvider(declaring,
                List.of(ncaSupplement(), igBds()), "adamig", "1-3")
                        .getStandardModelVariables(mockTable("ADPC"), null);

        assertEquals(baseFirst, supplementFirst, "the answer must not depend on declaration order");
        assertEquals(List.of("AFRLT", "AVISIT", "USUBJID", "PARAM"), baseFirst,
                "the NCA tier first, then the base names it does not govern");
    }


    @Test
    void twoProductsPublishingAStructureOfTheSameNameTieBreakByDeclarationOrder()
    {
        // ⚠ Deliberate, and NOT the F1 defect: an exact name match cannot produce structures of
        // different specificity, so there is nothing for specificity to decide and ruling 1's
        // declaration order is the whole answer. Pinned so a future reader does not "fix" it.
        DeclaredAdamProduct first = new DeclaredAdamProduct(IG_KEY, product("a", structure("ADSL",
                "SUBJECT LEVEL ANALYSIS DATASET", null, List.of(adamVar("AAA", "1", "Req")))));
        DeclaredAdamProduct second = new DeclaredAdamProduct(POPPK_KEY,
                product("b", structure("ADSL", "SUBJECT LEVEL ANALYSIS DATASET", null,
                        List.of(adamVar("BBB", "1", "Req")))));

        assertEquals(List.of("AAA"),
                provider(first, second).getStandardModelVariables(mockTable("ADSL"), null));
        assertEquals(List.of("BBB"),
                provider(second, first).getStandardModelVariables(mockTable("ADSL"), null));
    }


    /** A minimal {@link IDataTable} whose only interesting property is its name. */
    private static IDataTable mockTable(String aName)
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(aName);
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(-1);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(0L);
        return table;
    }


    /** Runs {@code aBody} with the provider's JUL output collected into {@code aSink}. */
    private static <T> T captureInto(List<String> aSink, java.util.function.Supplier<T> aBody)
    {
        Logger logger = Logger.getLogger(MetadataLibraryProvider.class.getName());
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Level previous = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try
        {
            return aBody.get();
        }
        finally
        {
            logger.removeHandler(handler);
            logger.setLevel(previous);
            aSink.addAll(handler.formatted());
        }
    }

    /** Collects the records the provider's {@link System.Logger} routes through JUL. */
    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

        List<String> formatted()
        {
            return records.stream()
                    .map(r -> MessageFormat.format(r.getMessage(), r.getParameters())).toList();
        }


        @Override
        public void publish(LogRecord logRecord)
        {
            records.add(logRecord);
        }


        @Override
        public void flush()
        {
            // nothing buffered
        }


        @Override
        public void close()
        {
            // nothing to release
        }


        @Override
        public boolean isLoggable(LogRecord logRecord)
        {
            return logRecord.getLevel().intValue() >= Level.INFO.intValue();
        }
    }
}
