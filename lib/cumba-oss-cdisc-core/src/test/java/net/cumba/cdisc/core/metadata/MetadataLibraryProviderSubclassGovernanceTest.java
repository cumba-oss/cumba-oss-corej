package net.cumba.cdisc.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider.DeclaredAdamProduct;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 of {@code plans/PLAN-metadata-product-selection.md} (ruling 2) — the published
 * {@code subClass} selects the <b>governing</b> ADaM data structure, and the unconditional
 * most-strict-wins union is gone.
 *
 * <h2>What was wrong</h2>
 *
 * <p>
 * {@code adamNamesWhereCore} used to return the union of every structure whose {@code class} maps
 * to the requested token. In {@code adam-occds-1-1} that is {@code OCCDS} ∪ {@code AE}, and the two
 * disagree on {@code core} for 40 of the 111 names they share — {@code --SEQ} and {@code --DECOD}
 * are {@code Cond} in {@code OCCDS} and {@code Req} in {@code AE}. Union therefore meant
 * <em>"Required if Required in any contributor"</em>, so {@code required_variables()} demanded
 * {@code AEDECOD} of <b>every</b> occurrence dataset, announced by a single INFO line.
 * </p>
 *
 * <p>
 * ⭐ {@link #occurrenceDatasetWithNoAeSignal_baseOnly_theUnionIsGone()} is the test that proves the
 * union is gone: same products, same token, no detected subclass ⇒ the base structure answers
 * alone.
 * </p>
 */
class MetadataLibraryProviderSubclassGovernanceTest
{

    private static final String OCCDS_TOKEN = AdamDataStructureDetector.OCCDS;

    private static final String BDS_TOKEN = AdamDataStructureDetector.BDS;

    private static final String ADVERSE_EVENT = AdamSubclassDetector.ADVERSE_EVENT;

    private static final String NCA = AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS;

    private static final String TTE = AdamSubclassDetector.TIME_TO_EVENT;

    // ------------------------------------------------------------------
    // Fixtures — minimal ADaM products, shaped like the real cached ones
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


    private static Map<String, Object> structure(String name, String className,
            @Nullable String subClass, List<Map<String, Object>> vars)
    {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Variables");
        set.put("ordinal", "1");
        set.put("analysisVariables", vars);

        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        ds.put("label", name);
        ds.put("class", className);
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
        product.put("version", "1-1");
        product.put("dataStructures", List.of(structures));
        return net.cumba.web.api.dev.MapResource.of(product, AdamProduct.class);
    }


    /**
     * The {@code adam-occds-1-1} shape, reduced to the names that matter: a base {@code OCCDS} and
     * an {@code AE} specialisation that disagree on {@code --SEQ} / {@code --DECOD}, plus one name
     * ({@code CMTRT}) the base alone publishes and one ({@code ONTRTFL}) that conflicts the other
     * way ({@code Cond} in the base, {@code Perm} in {@code AE}).
     */
    private static AdamProduct occds11()
    {
        return product("adam-occds",
                structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Cond"),
                                adamVar("--DECOD", "3", "Cond"), adamVar("CMTRT", "4", "Req"),
                                adamVar("ONTRTFL", "5", "Cond"))),
                structure("AE", "OCCURRENCE DATA STRUCTURE", ADVERSE_EVENT,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Req"),
                                adamVar("--DECOD", "3", "Req"), adamVar("ONTRTFL", "4", "Perm"))));
    }


    /**
     * {@code BASIC DATA STRUCTURE} drawn from a base {@code BDS} and an {@code ADNCA}
     * specialisation, disagreeing on the six names the plan measured ({@code AFRLT}, {@code ALLOQ},
     * {@code AVALU}, {@code AVISIT}, {@code DOSEA}, {@code DOSEU}).
     */
    private static AdamProduct bdsWithNca()
    {
        return product("adam-nca",
                structure("BDS", "BASIC DATA STRUCTURE", null,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("PARAM", "2", "Req"),
                                adamVar("AFRLT", "3", "Cond"), adamVar("ALLOQ", "4", "Cond"),
                                adamVar("AVALU", "5", "Cond"), adamVar("AVISIT", "6", "Cond"),
                                adamVar("DOSEA", "7", "Cond"), adamVar("DOSEU", "8", "Cond"))),
                structure("ADNCA", "BASIC DATA STRUCTURE", NCA,
                        List.of(adamVar("AFRLT", "1", "Req"), adamVar("ALLOQ", "2", "Req"),
                                adamVar("AVALU", "3", "Req"), adamVar("AVISIT", "4", "Req"),
                                adamVar("DOSEA", "5", "Req"), adamVar("DOSEU", "6", "Req"))));
    }


    /** The {@code adam-nca-1-0} shape: the token exists ONLY as a subclass, with no base behind. */
    private static AdamProduct ncaOnly()
    {
        return product("adam-nca", structure("ADNCA", "BASIC DATA STRUCTURE", NCA,
                List.of(adamVar("AFRLT", "1", "Req"), adamVar("USUBJID", "2", "Req"))));
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


    private static MetadataLibraryProvider occdsProvider()
    {
        return provider(new DeclaredAdamProduct("standards/adam/adam-occds-1-1", occds11()));
    }

    // ------------------------------------------------------------------
    // The chain governs
    // ------------------------------------------------------------------


    @Test
    void aeSubclassGovernsTheNamesItPublishes_notAUnion()
    {
        // AE says --SEQ and --DECOD are Req; the base OCCDS says Cond and is OVERRULED, not
        // merged with. ONTRTFL runs the other way (Cond in the base, Perm in AE) and is likewise
        // decided by AE — so it is absent, which a union could never produce.
        List<String> required = occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN,
                List.of(ADVERSE_EVENT));

        assertNotNull(required);
        assertTrue(required.contains("--SEQ"), () -> "AE governs --SEQ; got " + required);
        assertTrue(required.contains("--DECOD"), () -> "AE governs --DECOD; got " + required);
        assertFalse(required.contains("ONTRTFL"),
                () -> "AE demotes ONTRTFL to Perm and governs it; got " + required);
    }


    @Test
    void aBaseOnlyNameStillResolvesWhenASubclassGoverns()
    {
        // ⚠ Govern ≠ replace. CMTRT is published only by the base OCCDS, so it survives the AE
        // tier in front of it. A "most specific structure wins outright" implementation would
        // silently drop it — and drop every other name the specialisation happens not to restate.
        List<String> required = occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN,
                List.of(ADVERSE_EVENT));

        assertEquals(List.of("USUBJID", "--SEQ", "--DECOD", "CMTRT"), required,
                "the governing tier's names first, then the names only the base publishes");
    }


    @Test
    void occurrenceDatasetWithNoAeSignal_baseOnly_theUnionIsGone()
    {
        // ⭐⭐ THE PROOF. Same product, same token, no detected subclass ⇒ the base answers alone.
        // Under the old union this returned [USUBJID, --SEQ, --DECOD, CMTRT] — AEDECOD and --SEQ
        // demanded of every occurrence dataset, including the ones OCCDS only ever made Cond.
        List<String> required = occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN,
                List.of());

        assertEquals(List.of("USUBJID", "CMTRT"), required);
        assertFalse(required.contains("--SEQ"), "the union is gone");
        assertFalse(required.contains("--DECOD"), "the union is gone");
    }


    @Test
    void theOneArgConvenienceIsTheNoSubclassCase()
    {
        // The one-arg overload must be exactly "base structures only" — never a back door to the
        // old union, and never the interface's "cannot answer" null.
        MetadataLibraryProvider p = occdsProvider();
        assertEquals(p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()),
                p.getRequiredVariablesForStructure(OCCDS_TOKEN));
    }


    @Test
    void aSubclassNoStructurePublishesFallsThroughToTheBase()
    {
        // TIME-TO-EVENT is a real token, but no structure under OCCURRENCE DATA STRUCTURE carries
        // it. The tier is empty and omitted; the base still answers.
        assertEquals(List.of("USUBJID", "CMTRT"),
                occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(TTE)));
    }


    @Test
    void subclassTokensAreMatchedCaseAndSpaceInsensitively()
    {
        assertEquals(
                occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN,
                        List.of(ADVERSE_EVENT)),
                occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN,
                        List.of("  adverse event  ")));
    }


    @Test
    void nullAndBlankSubclassTokensAreIgnoredRatherThanMatchingTheBase()
    {
        List<String> tokens = new ArrayList<>();
        tokens.add(null);
        tokens.add("   ");
        tokens.add(ADVERSE_EVENT);
        assertEquals(List.of("USUBJID", "--SEQ", "--DECOD", "CMTRT"),
                occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN, tokens));
    }


    @Test
    void ncaGovernsItsSixConflictingNames()
    {
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adam-nca-1-0", bdsWithNca()));

        assertEquals(
                List.of("AFRLT", "ALLOQ", "AVALU", "AVISIT", "DOSEA", "DOSEU", "USUBJID", "PARAM"),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA)));
        // …and a plain BDS dataset under the same product gets none of them.
        assertEquals(List.of("USUBJID", "PARAM"),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of()));
    }


    @Test
    void mostSpecificSubclassFirst_theFirstTierGovernsTheNamesItShares()
    {
        // Two detected subclasses (a declared def:SubClass list can carry several). The chain is
        // the caller's order, most specific first — the first tier that publishes a name decides.
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adam-occds-1-1", occds11()));
        assertEquals(p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT, TTE)));
    }


    @Test
    void aRepeatedSubclassTokenContributesOneTierOnly()
    {
        // A declared def:SubClass list may repeat a name (or repeat it in another case). The tier
        // must be built once — a second identical tier would put the structure's own names into
        // `governed` twice, which is harmless today but is exactly the kind of duplicate a later
        // change turns into a wrong answer.
        assertEquals(
                occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN,
                        List.of(ADVERSE_EVENT)),
                occdsProvider().getRequiredVariablesForStructure(OCCDS_TOKEN,
                        List.of(ADVERSE_EVENT, "adverse event", ADVERSE_EVENT)));
    }


    @Test
    void theCompanionDecoratorForwardsTheSubclassToTheRunProvider()
    {
        // ⚠⚠ CompanionDomainsProvider wraps the run provider on EVERY ADaM validation. It is the
        // wrapper that hid Fix #368 for a whole measurement cycle by inheriting the interface
        // defaults; forwarding only the one-arg convenience here would strip the subclass and
        // quietly resolve every list from the base structure.
        MetadataLibraryProvider base = occdsProvider();
        CompanionDomainsProvider wrapped = new CompanionDomainsProvider(base,
                new MetadataLibraryProvider(study()));

        assertEquals(base.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                wrapped.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        assertEquals(base.getExpectedVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                wrapped.getExpectedVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        // …and the answer really does differ by subclass through the wrapper, which is what
        // proves the argument survived the delegation rather than being dropped for List.of().
        assertEquals(List.of("USUBJID", "CMTRT"),
                wrapped.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()));
    }

    // ------------------------------------------------------------------
    // ⛔ An inapplicable token is null, never an empty list
    // ------------------------------------------------------------------


    @Test
    void aTokenPublishedOnlyUnderAnUnmatchedSubclassIsNullNotEmpty()
    {
        // adam-nca-1-0 publishes BASIC DATA STRUCTURE only as NON-COMPARTMENTAL ANALYSIS. For a
        // plain BDS dataset the chain is empty — and an empty LIST would say "this structure
        // requires nothing", passing the rule vacuously. null says "no such structure here", which
        // lets OperationExecutor try the next token and then SKIP loudly.
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adam-nca-1-0", ncaOnly()));

        assertNull(p.getRequiredVariablesForStructure(BDS_TOKEN, List.of()));
        assertEquals(List.of("AFRLT", "USUBJID"),
                p.getRequiredVariablesForStructure(BDS_TOKEN, List.of(NCA)));
    }


    @Test
    void anUnknownTokenIsStillNull()
    {
        assertNull(occdsProvider().getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL,
                List.of(ADVERSE_EVENT)));
    }

    // ------------------------------------------------------------------
    // Expected mirrors Required; ruling 1 still decides the product
    // ------------------------------------------------------------------


    @Test
    void expectedMirrorsRequiredBecauseAdamHasNoExpCore()
    {
        MetadataLibraryProvider p = occdsProvider();
        assertEquals(p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                p.getExpectedVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        assertEquals(p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()),
                p.getExpectedVariablesForStructure(OCCDS_TOKEN, List.of()));
    }


    @Test
    void productOrderTieBreaksEqualSpecificityOnly_theSubclassGovernsFromEitherProduct()
    {
        // ⚠⚠ RE-CUT BY PHASE 8. This test used to assert "a later product is not consulted once
        // an earlier one matched", which was the defect: it made the answer depend on which
        // product happened to be declared first, and it hid a supplement's specialisation behind
        // any base product listed ahead of it. Ruling 2 is about STRUCTURES; the user's order is
        // only the tie-break among EQUAL specificity. So both orders are asserted here, and the
        // ZZTOP base — equally specific to the supplement's own base — is decided by order while
        // the AE tier is decided by specificity, whichever product carries it.
        AdamProduct other = product("adamig", structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                List.of(adamVar("ZZTOP", "1", "Req"))));
        DeclaredAdamProduct supplement = new DeclaredAdamProduct("standards/adam/adam-occds-1-1",
                occds11());
        DeclaredAdamProduct ig = new DeclaredAdamProduct("standards/adam/adamig-1-3", other);

        List<String> supplementFirst = provider(supplement, ig)
                .getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT));
        assertNotNull(supplementFirst);
        assertFalse(supplementFirst.contains("ZZTOP"),
                () -> "the supplement's own base wins the equally-specific base level when it is "
                        + "declared first; got " + supplementFirst);
        assertTrue(supplementFirst.contains("CMTRT"),
                () -> "…and that base's own names are the ones behind the AE tier; got "
                        + supplementFirst);

        List<String> igFirst = provider(ig, supplement)
                .getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT));
        assertNotNull(igFirst);
        assertTrue(igFirst.contains("--SEQ") && igFirst.contains("--DECOD"),
                () -> "⭐ the AE specialisation still governs when its product is declared "
                        + "SECOND — the specificity axis does not care about product order; got "
                        + igFirst);
        assertTrue(igFirst.contains("ZZTOP"),
                () -> "the base level now goes to the first-declared product; got " + igFirst);
        assertFalse(igFirst.contains("CMTRT"),
                () -> "…and the supplement's base lost that tie-break; got " + igFirst);
    }

    // ------------------------------------------------------------------
    // Equal specificity still unions, and still logs
    // ------------------------------------------------------------------


    @Test
    void equalSpecificityContributorsStillUnionAndStillLog()
    {
        // Two structures of the SAME class and the SAME subClass are equally entitled to speak;
        // there is no evidence with which to prefer either, so the union survives inside the tier
        // — and it is announced, so a product where two equally-specific structures genuinely
        // disagree surfaces rather than averaging quietly.
        AdamProduct twoAes = product("adam-occds",
                structure("AE", "OCCURRENCE DATA STRUCTURE", ADVERSE_EVENT,
                        List.of(adamVar("AEDECOD", "1", "Req"))),
                structure("AE2", "OCCURRENCE DATA STRUCTURE", ADVERSE_EVENT,
                        List.of(adamVar("AESER", "1", "Req"))));
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adam-occds-1-1", twoAes));

        List<String> logged = captureInfo(() -> assertEquals(List.of("AEDECOD", "AESER"),
                p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT))));

        assertTrue(logged.stream()
                .anyMatch(m -> m.contains("unioned") && m.contains("standards/adam/adam-occds-1-1")
                        && m.contains(ADVERSE_EVENT)),
                () -> "the surviving union must still be logged, with its product and subclass; got "
                        + logged);
    }


    @Test
    void tiersOfDifferentSpecificityAreNotLoggedAsAUnion()
    {
        // The whole point: OCCDS + AE is no longer a union, so it must no longer be announced as
        // one. A log line saying "unioned" here would be reporting behaviour that has been
        // removed.
        List<String> logged = captureInfo(() -> occdsProvider()
                .getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));

        assertTrue(logged.stream().noneMatch(m -> m.contains("unioned")),
                () -> "a governed chain is not a union; got " + logged);
    }


    @Test
    void anInapplicableTokenSaysSoInTheLog()
    {
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adam-nca-1-0", ncaOnly()));

        List<String> logged = captureInfo(
                () -> assertNull(p.getRequiredVariablesForStructure(BDS_TOKEN, List.of())));

        assertTrue(
                logged.stream()
                        .anyMatch(m -> m.contains("standards/adam/adam-nca-1-0")
                                && m.contains("only under subclasses")),
                () -> "the null must be explained, or it reads as a missing product; got "
                        + logged);
    }


    /** Runs {@code aBody} with the provider's JUL output captured, and returns the INFO lines. */
    private static List<String> captureInfo(Runnable aBody)
    {
        Logger logger = Logger.getLogger(MetadataLibraryProvider.class.getName());
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Level previous = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try
        {
            aBody.run();
        }
        finally
        {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return handler.formatted();
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
