package net.cumba.corej.core.metadata;

import static net.cumba.corej.core.metadata.CustomDomainWalkFixture.dataset;
import static net.cumba.corej.core.metadata.CustomDomainWalkFixture.igViewProvider;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.StubMetadataProvider;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.SyntheticDataTable;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-var-custom-domains} E7 — the LIBRARY-level variable read
 * ({@code var_*("LIBRARY")}, and the {@code library_variable_*} cells a finding reports) for a
 * dataset whose domain the run's IG does <b>not</b> define.
 *
 * <p>
 * The read is keyed by {@code (domain, variable)}, and the provider's metadata library is the IG
 * product's own table view, which has no table for such a domain — so every tier answered
 * {@code {}}: the type and role rules passed silently, and {@code CDISC-SEND-0005}'s unguarded
 * {@code var_name("DEFINE") != var_name("LIBRARY")} fired on every Define-declared variable. The
 * read's last tier ({@link MetadataLibraryProvider#getIgAbsentVariableMetadata}) now serves the
 * model row of the class the <b>scope matcher</b> gives the dataset — {@code name},
 * {@code simpleDatatype} and {@code role}, and nothing else ({@code LVC-D1} (a)).
 * </p>
 *
 * <p>
 * The oracle is the forced-class walk
 * ({@link MetadataLibraryProvider#getStandardModelVariablesForClass}) at the scope class
 * ({@link ScopeClassLadder#classOf}) — the same walk with step 2 given the class, which for a
 * domain the IG does not define is the walk algorithm B takes. Every provider is built over the IG
 * product's table view, as production builds it ({@link CustomDomainWalkFixture#igViewProvider()}).
 * The real products and the shipped rules are exercised by the corpus repository's
 * {@code LibraryVarCustomDomainStoreTest}.
 * </p>
 */
class IgAbsentVariableMetadataTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** The only attributes the tier may serve ({@code LVC-D1} (a)). */
    private static final Set<String> SERVED = Set.of("name", "simpleDatatype", "role");

    /** The sponsor-domain class shapes: topic columns → the class the scope ladder gives them. */
    private static final Map<String, List<String>> SHAPES = shapes();

    private static Map<String, List<String>> shapes()
    {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("EVENTS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTERM", "XXDTC", "XXFOO"));
        s.put("INTERVENTIONS", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTRT", "XXDTC", "XXFOO"));
        s.put("FINDINGS",
                List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXORRES", "XXDTC", "XXFOO"));
        s.put("FINDINGS ABOUT", List.of("STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXOBJ",
                "XXORRES", "XXDTC", "XXFOO"));
        return s;
    }


    /** The LIBRARY variable read exactly as the Check and the finding enrichment make it. */
    private static Map<String, String> read(MetadataProvider aProvider, IDataTable aTable,
            DatasetResolver aResolver, String aVariable)
    {
        return ExprCompiler.libraryVariableMetadata(aProvider, aTable, aResolver,
                aTable.getMetaData().getName(), aVariable);
    }


    private static List<String> columns(IDataTable aTable)
    {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < aTable.getMetaData().getColumnCount(); i++)
        {
            out.add(aTable.getMetaData().getColumn(i).getName());
        }
        return out;
    }


    /** The forced-class walk rows at the scope class, by variable name — the oracle. */
    private static Map<String, Map<String, String>> scopeClassRows(MetadataProvider aProvider,
            IDataTable aTable, DatasetResolver aResolver, String aExpectedClass)
    {
        String member = aTable.getMetaData().getName();
        String scope = ScopeClassLadder.classOf(aProvider, member,
                CdiscDomainResolver.cdiscDomainOf(aTable), aTable, aResolver);
        assertEquals(aExpectedClass, scope, "scope class of " + member);
        List<Map<String, String>> forced = aProvider.getStandardModelVariablesForClass(aTable,
                aResolver, scope);
        assertNotNull(forced, "the forced walk serves " + scope);
        Map<String, Map<String, String>> byName = new LinkedHashMap<>();
        for (Map<String, String> row : forced)
        {
            byName.put(row.get("name"), row);
        }
        return byName;
    }


    /** A walk row restricted to the served attributes, as the tier must answer it. */
    private static Map<String, String> served(Map<String, String> aRow)
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : SERVED)
        {
            String value = aRow.get(key);
            if (value != null && !value.isEmpty())
            {
                out.put(key, value);
            }
        }
        return out;
    }


    /**
     * Every column of {@code aTable} reads the scope-class row's name, type and role, or nothing
     * when the model has no row for it; returns how many columns were served.
     */
    private static int assertServesTheScopeClassRows(MetadataProvider aProvider, IDataTable aTable,
            DatasetResolver aResolver, String aExpectedClass)
    {
        Map<String, Map<String, String>> rows = scopeClassRows(aProvider, aTable, aResolver,
                aExpectedClass);
        int served = 0;
        for (String column : columns(aTable))
        {
            Map<String, String> row = rows.get(column);
            Map<String, String> expected = row == null ? Map.of() : served(row);
            Map<String, String> actual = read(aProvider, aTable, aResolver, column);
            assertEquals(expected, actual, aTable.getMetaData().getName() + "." + column);
            if (!actual.isEmpty())
            {
                assertEquals(SERVED, actual.keySet(),
                        column + ": exactly name, type and role — never label or ordinal");
                served++;
            }
        }
        return served;
    }

    // ------------------------------------------------------------------
    // The tier serves the scope class's model row — name, type, role
    // ------------------------------------------------------------------


    @Test
    void aSponsorDomainOfEveryShapeReadsItsScopeClassRowsNameTypeAndRole()
    {
        MetadataLibraryProvider provider = igViewProvider();
        for (Map.Entry<String, List<String>> shape : SHAPES.entrySet())
        {
            IDataTable xx = dataset("XX", "XX", shape.getValue().toArray(String[]::new));
            int served = assertServesTheScopeClassRows(provider, xx, NO_RESOLVER, shape.getKey());
            // Every column but the sponsor's own XXFOO is a model variable of the class (DOMAIN
            // included, which the fixture adds).
            assertEquals(shape.getValue().size(), served, shape.getKey());
        }
    }


    @Test
    void theLabelTheOrdinalAndEveryOtherModelAttributeAreWithheld()
    {
        // LVC-D1 (a): the walk row carries label and ordinal, and the finding enrichment copies
        // EVERY key of the answer as a library_variable_* cell — so an allow-list, not "drop the
        // label", is what keeps the class ordinal and the model's class-level label out.
        MetadataLibraryProvider provider = igViewProvider();
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTERM", "XXDTC");
        Map<String, String> row = scopeClassRows(provider, xx, NO_RESOLVER, "EVENTS").get("XXTERM");
        assertNotNull(row);
        assertTrue(row.containsKey("label") && row.containsKey("ordinal"),
                "the oracle row carries what must be withheld: " + row);
        assertEquals(Map.of("name", "XXTERM", "simpleDatatype", "Char", "role", "Topic"),
                read(provider, xx, NO_RESOLVER, "XXTERM"));
        assertEquals(Map.of("name", "XXTERM", "simpleDatatype", "Char", "role", "Topic"),
                provider.getIgAbsentVariableMetadata(xx, NO_RESOLVER, "XX", "XXTERM"),
                "the provider's own answer is the allow-list too");
    }


    /**
     * The tier is keyed by the dataset's own spelling (the finding enrichment and the Check pass
     * the column name as the dataset carries it), and matches it to the model row ignoring letter
     * case like every other tier (owner ruling 2026-09-28, register {@code CIT §1}): a SAS-exported
     * {@code xxterm} reads {@code XXTERM}'s row. Compared case-sensitively the tier answered
     * {@code {}} for every lowercase column. The row names the variable in the model's spelling.
     * Mockito-free: a real {@link SyntheticDataTable}.
     */
    @Test
    void aLowercaseColumnReadsItsScopeClassRow()
    {
        MetadataLibraryProvider provider = igViewProvider();
        Map<String, String> expected = Map.of("name", "XXTERM", "simpleDatatype", "Char", "role",
                "Topic");
        for (List<String> columns : List.of(
                List.of("DOMAIN", "STUDYID", "USUBJID", "XXSEQ", "XXTERM", "XXDTC"),
                List.of("domain", "studyid", "usubjid", "xxseq", "xxterm", "xxdtc")))
        {
            IDataTable xx = new SyntheticDataTable("XX", columns, new String[]
            {
                    "XX"
            }, 1);
            String term = columns.get(4);
            assertEquals(expected, read(provider, xx, NO_RESOLVER, term), term);
            assertEquals(expected,
                    provider.getIgAbsentVariableMetadata(xx, NO_RESOLVER, "XX", term),
                    term + ": the provider's own answer");
        }
    }


    @Test
    void aSponsorVariableTheModelDoesNotDefineReadsNothing()
    {
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXFOO");
        assertEquals(Map.of(), read(igViewProvider(), xx, NO_RESOLVER, "XXFOO"));
    }


    @Test
    void anIgAbsentStandardDomainReadsItsCuratedClassRows()
    {
        // VS is not in the fixture IG; the curated DomainClassMap places it in FINDINGS before any
        // sniff (SENDIG-AR 1.0 / DART 1.1's left-out standard SEND domains take this path).
        IDataTable vs = dataset("VS", "VS", "STUDYID", "USUBJID", "VSSEQ", "VSTESTCD", "VSORRES",
                "VSDTC");
        assertEquals(7,
                assertServesTheScopeClassRows(igViewProvider(), vs, NO_RESOLVER, "FINDINGS"));
    }


    @Test
    void aSplitMemberOfASponsorDomainReadsLikeTheDomain()
    {
        // XX1 carries DOMAIN=XX: the member name misses, tier 2's key (the CDISC domain) is XX.
        MetadataLibraryProvider provider = igViewProvider();
        IDataTable xx1 = dataset("XX1", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXFOO");
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD", "XXFOO");
        assertEquals(5, assertServesTheScopeClassRows(provider, xx1, NO_RESOLVER, "FINDINGS"));
        for (String column : columns(xx))
        {
            assertEquals(read(provider, xx, NO_RESOLVER, column),
                    read(provider, xx1, NO_RESOLVER, column), column);
        }
    }


    @Test
    void anAssociatedPersonsDatasetReadsItsPresentParentsClassWithTheApIdentifiers()
    {
        // The class is the scope matcher's: the AP dataset inherits the class of its parent, which
        // only the resolver can hand back (PLAN-custom-domain-model-walk C10). The AP shim merges
        // the ASSOCIATED PERSONS identifiers.
        IDataTable apxx = dataset("APXX", "APXX", "STUDYID", "APID", "XXSEQ", "XXTERM");
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTERM");
        DatasetResolver parent = name -> "XX".equals(name) ? xx : null;
        MetadataLibraryProvider provider = igViewProvider();
        assertEquals(5, assertServesTheScopeClassRows(provider, apxx, parent, "EVENTS"));
        assertEquals(Map.of("name", "APID", "simpleDatatype", "Char", "role", "Identifier"),
                read(provider, apxx, parent, "APID"));
    }


    @Test
    void anApMemberWhoseDomainCellNamesTheParentIsClassifiedByItsOwnColumnsAsScopeDoes()
    {
        // Review round 1, P1: member APXX whose DOMAIN cell holds XX. The read's domain tier keys
        // the tier with APXX (the AP prefix re-added, so the associated-persons identifiers
        // resolve), but the CLASS is keyed by the dataset's own code XX, exactly as scope keys it:
        // XXTESTCD places it in FINDINGS by its own columns, with or without a parent in the
        // study, and even when the parent is an EVENTS dataset. Keyed by APXX, the ladder sniffed
        // APXXTESTCD, missed, and fell through to the absent parent: {} — where scope said
        // FINDINGS, and SEND-0005 kept reporting every Define-declared variable.
        IDataTable apxx = dataset("APXX", "XX", "STUDYID", "APID", "XXSEQ", "XXTESTCD", "XXFOO");
        IDataTable eventsParent = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTERM");
        MetadataLibraryProvider provider = igViewProvider();
        for (DatasetResolver resolver : List.<DatasetResolver> of(NO_RESOLVER,
                name -> "XX".equals(name) ? eventsParent : null))
        {
            assertEquals("FINDINGS",
                    ScopeClassLadder.classOf(provider, "APXX", "XX", apxx, resolver),
                    "scope's class");
            assertEquals(Map.of("name", "XXTESTCD", "simpleDatatype", "Char", "role", "Topic"),
                    read(provider, apxx, resolver, "XXTESTCD"));
            assertEquals(Map.of("name", "APID", "simpleDatatype", "Char", "role", "Identifier"),
                    read(provider, apxx, resolver, "APID"),
                    "the AP shim rides the domain tier's AP-prefixed key");
            assertEquals(Map.of(), read(provider, apxx, resolver, "XXFOO"));
        }
    }


    @Test
    void anAssociatedPersonsDatasetWhoseParentIsAbsentReadsNothing()
    {
        // Scope places it nowhere, so the tier serves nothing — today's answer.
        IDataTable apxx = dataset("APXX", "APXX", "STUDYID", "APID", "XXSEQ", "XXTERM");
        MetadataLibraryProvider provider = igViewProvider();
        assertNull(ScopeClassLadder.classOf(provider, "APXX", "APXX", apxx, NO_RESOLVER));
        for (String column : columns(apxx))
        {
            assertEquals(Map.of(), read(provider, apxx, NO_RESOLVER, column), column);
        }
    }

    // ------------------------------------------------------------------
    // The tier never answers where tiers 1-3 do, or where the IG defines the domain
    // ------------------------------------------------------------------


    @Test
    void anIgDefinedDomainsReadsAreTheDomainKeyedTiersAnswers()
    {
        // P6: the tier is last. An IG variable is answered by tier 1 (label included); a sponsor
        // variable of an IG-defined domain is answered by nobody — the tier returns before any
        // walk, because the IG defines the domain.
        MetadataLibraryProvider provider = igViewProvider();
        IDataTable lb = dataset("LB", "LB", "STUDYID", "USUBJID", "LBSEQ", "LBTESTCD", "LBFOO");
        Map<String, String> igRow = provider.getVariableMetadata("LB", "LBTESTCD");
        assertTrue(igRow.containsKey("label"), "tier 1 serves the IG row: " + igRow);
        assertEquals(igRow, read(provider, lb, NO_RESOLVER, "LBTESTCD"));
        assertEquals(Map.of(), read(provider, lb, NO_RESOLVER, "LBFOO"));
        assertEquals(Map.of(),
                provider.getIgAbsentVariableMetadata(lb, NO_RESOLVER, "LB", "LBTESTCD"),
                "the tier itself never answers for a domain the IG defines");

        // A split member of an IG domain: tier 2's answer, untouched.
        IDataTable lb1 = dataset("LB1", "LB", "STUDYID", "USUBJID", "LBSEQ", "LBTESTCD");
        assertEquals(igRow, read(provider, lb1, NO_RESOLVER, "LBTESTCD"));
        assertEquals(Map.of(),
                provider.getIgAbsentVariableMetadata(lb1, NO_RESOLVER, "LB", "LBFOO"));
    }


    @Test
    void aSupplementalDatasetReadsTheSuppqualAnswerOfTierOne()
    {
        // SUPPXX of a sponsor parent: tier 1 answers from the SUPPQUAL cascade, label included;
        // the tier itself answers {} for any SUPP-- name (tiers 1-3 own them).
        MetadataLibraryProvider provider = igViewProvider();
        IDataTable suppxx = dataset("SUPPXX", null, "STUDYID", "RDOMAIN", "USUBJID", "IDVAR",
                "IDVARVAL", "QNAM", "QVAL");
        Map<String, String> tierOne = provider.getVariableMetadata("SUPPXX", "QNAM");
        assertFalse(tierOne.isEmpty(), "tier 1 serves SUPPQUAL's QNAM");
        assertEquals(tierOne, read(provider, suppxx, NO_RESOLVER, "QNAM"));
        assertEquals(Map.of(),
                provider.getIgAbsentVariableMetadata(suppxx, NO_RESOLVER, "SUPPXX", "QNAM"));
    }


    @Test
    void anAssociatedPersonsDatasetOfAnIgDomainReadsTierOnesApShim()
    {
        MetadataLibraryProvider provider = igViewProvider();
        IDataTable apae = dataset("APAE", "APAE", "STUDYID", "APID", "AESEQ", "AETERM");
        IDataTable ae = dataset("AE", "AE", "STUDYID", "USUBJID", "AESEQ", "AETERM");
        DatasetResolver parent = name -> "AE".equals(name) ? ae : null;
        Map<String, String> tierOne = provider.getVariableMetadata("APAE", "AETERM");
        assertFalse(tierOne.isEmpty(), "tier 1 serves APAE.AETERM through the AP shim");
        assertEquals(tierOne, read(provider, apae, parent, "AETERM"));
        assertEquals(Map.of(), provider.getIgAbsentVariableMetadata(apae, parent, "APAE", "AEFOO"),
                "AE is IG-defined, so the tier answers nothing even for a sponsor variable");
    }

    // ------------------------------------------------------------------
    // No class, no answer — today's behaviour (LVC-D3 (a))
    // ------------------------------------------------------------------


    @Test
    void aSnifferMissReadsNothingAndThrowsNothing()
    {
        MetadataLibraryProvider provider = igViewProvider();
        // No topic column: nothing places it, although every column but XXFOO is a GENERAL
        // OBSERVATIONS identifier the model defines — without a class there is no row to serve.
        IDataTable noTopic = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXFOO");
        // No DOMAIN column, and a member name that is not the variables' prefix: the domain code
        // falls back to the member name, and the topic probes (XXNODOMTESTCD, ...) find nothing.
        IDataTable noDomain = dataset("XXNODOM", null, "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        for (IDataTable miss : List.of(noTopic, noDomain))
        {
            String member = miss.getMetaData().getName();
            String domain = CdiscDomainResolver.cdiscDomainOf(miss);
            assertNull(ScopeClassLadder.classOf(provider, member, domain, miss, NO_RESOLVER),
                    member);
            for (String column : columns(miss))
            {
                assertEquals(Map.of(), read(provider, miss, NO_RESOLVER, column), column);
                assertEquals(Map.of(),
                        provider.getIgAbsentVariableMetadata(miss, NO_RESOLVER, domain, column),
                        column);
            }
        }
    }

    // ------------------------------------------------------------------
    // Providers that cannot answer
    // ------------------------------------------------------------------


    private static AdamProduct adamProduct()
    {
        Map<String, Object> studyId = new LinkedHashMap<>();
        studyId.put("name", "STUDYID");
        studyId.put("ordinal", "1");
        studyId.put("simpleDatatype", "Char");
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Identifiers");
        set.put("ordinal", "1");
        set.put("analysisVariables", List.of(studyId));
        Map<String, Object> bds = new LinkedHashMap<>();
        bds.put("name", "BDS");
        bds.put("label", "Basic Data Structure");
        bds.put("class", "BASIC DATA STRUCTURE");
        bds.put("analysisVariableSets", List.of(set));
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "ADaMIG");
        product.put("version", "1-3");
        product.put("dataStructures", List.of(bds));
        return MapResource.of(product, AdamProduct.class);
    }


    private static MetadataLibraryProvider adamProvider()
    {
        IMetadataLibrary study = lib("study").table(table("ADXX").build()).build();
        return ApiModelLibraries.adamProvider(study, adamProduct(), "adamig", "1-3");
    }


    @Test
    void anAdamProviderReadsNothingThroughTheTier()
    {
        // S3.1: SDTM only. An ADaM provider has no SDTM product, so its label rules keep their own
        // path and the tier never answers.
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        assertEquals(Map.of(),
                adamProvider().getIgAbsentVariableMetadata(xx, NO_RESOLVER, "XX", "XXSEQ"));
    }


    @Test
    void aProviderThatDoesNotImplementTheTierAnswersNothing()
    {
        // The interface default — a leaf that has no model (a test double, the .cdt harness's
        // map-backed provider, the DEFINE provider) serves nothing, and the read stays {}.
        StubMetadataProvider stub = new StubMetadataProvider();
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        assertEquals(Map.of(), stub.getIgAbsentVariableMetadata(xx, NO_RESOLVER, "XX", "XXSEQ"));
        assertEquals(Map.of(), read(stub, xx, NO_RESOLVER, "XXSEQ"));
    }


    @Test
    void aDegradedProviderReadsNothingThroughTheTier()
    {
        IMetadataLibrary empty = lib("sdtmig").build();
        MetadataLibraryProvider degraded = MetadataLibraryProvider.degraded(empty,
                new IllegalStateException("fetch failed"));
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        assertEquals(Map.of(),
                degraded.getIgAbsentVariableMetadata(xx, NO_RESOLVER, "XX", "XXSEQ"));
    }


    @Test
    void theCompanionWrapperAsProductionBuildsItReadsNothingAndThrowsNothing()
    {
        // Production wraps ONLY an ADaM-family run: an ADaM base with an SDTM companion. The
        // variable read goes to the base, whose tier answers {}; the SDTM companion is never
        // consulted for it — even for an SDTM-shaped IG-absent dataset.
        CompanionDomainsProvider wrapped = new CompanionDomainsProvider(adamProvider(),
                igViewProvider());
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        for (String column : columns(xx))
        {
            assertEquals(Map.of(), read(wrapped, xx, NO_RESOLVER, column), column);
        }
    }


    @Test
    void theCompanionWrapperDelegatesTheTierToItsBase()
    {
        // EC-85 D-7 / Fix #368: an undelegated default hides the method behind the decorator. The
        // shape is not one production builds (an SDTM base), which is what makes the delegation
        // observable: the wrapped read must equal the bare base's, and that is not empty.
        MetadataLibraryProvider base = igViewProvider();
        CompanionDomainsProvider wrapped = new CompanionDomainsProvider(base, igViewProvider());
        IDataTable xx = dataset("XX", "XX", "STUDYID", "USUBJID", "XXSEQ", "XXTESTCD");
        Map<String, String> bare = base.getIgAbsentVariableMetadata(xx, NO_RESOLVER, "XX", "XXSEQ");
        assertEquals(Map.of("name", "XXSEQ", "simpleDatatype", "Char", "role", "Identifier"), bare);
        assertEquals(bare, wrapped.getIgAbsentVariableMetadata(xx, NO_RESOLVER, "XX", "XXSEQ"));
        assertEquals(bare, read(wrapped, xx, NO_RESOLVER, "XXSEQ"));
    }
}
