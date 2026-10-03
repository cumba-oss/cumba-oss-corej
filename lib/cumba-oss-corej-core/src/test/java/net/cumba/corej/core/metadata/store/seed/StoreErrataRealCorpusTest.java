package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.RealCorpusLocator;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredDomain;
import net.cumba.corej.core.metadata.store.StoredField;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ⭐ The errata register against the REAL seed source (PLAN-store-library-content-errata, D4): a
 * seed of the corpus's two standards pickles — the products only; the CT packages are not needed
 * and a directory holding just those two files enumerates none, which keeps this seed at seconds
 * rather than the minutes of {@link StoreSeederRealDataConformanceTest} — must apply <b>every</b>
 * register entry: zero stale, zero unreached. A stale entry means CDISC fixed the value upstream
 * (or a normalisation rule moved under the register) and the entry must be retired; an unreached
 * one means the register names a product or variable the source no longer carries. Either is a red
 * test, never a silent skip.
 *
 * <p>
 * The same seed proves the owner rule (no label ends in a footnote marker) over the whole corpus
 * and spot-checks one application per entry kind on the written store — the SDTM 1.7
 * domain-specific root {@code --EVDTYP} (D2) that the MH walk of SDTMIG 3.3 needs, the 2.0
 * "Variable(s) Qualified" column, a corrected label, a codelist link, an added CDASH field, a set
 * order and the removed TIG set.
 * </p>
 *
 * <p>
 * Skipped only when the machine carries no corpus ({@link RealCorpusLocator}); a corpus that is
 * named but broken fails loudly there.
 * </p>
 */
class StoreErrataRealCorpusTest
{

    @TempDir
    private static Path sharedTemp;

    private static @Nullable StoreSeedReport report;

    private static @Nullable MetadataStore store;

    @BeforeAll
    static void seedProductsOnly() throws IOException
    {
        assumeTrue(RealCorpusLocator.locate().isPresent(), RealCorpusLocator.ABSENT_MESSAGE);
        Path corpus = RealCorpusLocator.locate().orElseThrow();
        Path source = Files.createDirectories(sharedTemp.resolve("products-only"));
        for (String file : List.of("standards_details.pkl", "standards_models.pkl"))
        {
            Files.copy(corpus.resolve(file), source.resolve(file));
        }
        Path target = sharedTemp.resolve("store.zip");
        report = new PickleStoreSeeder(new LocalPickleSource(source))
                .seed(StoreSeedOptions.of(target));
        store = MetadataStore.open(target);
    }


    @AfterAll
    static void closeStore()
    {
        if (store != null)
        {
            store.close();
        }
    }


    private static StoreSeedReport report()
    {
        return java.util.Objects.requireNonNull(report);
    }


    private static StoredProduct product(String aKey)
    {
        return java.util.Objects.requireNonNull(store).product(aKey).orElseThrow();
    }


    @Test
    void everyRegisterEntryAppliesToTheRealSource()
    {
        StoreSeedReport r = report();
        assertEquals(List.of(), r.errataStale(),
                "D4: a stale entry is reported, not applied, and must be RETIRED from the register");
        assertEquals(List.of(), r.errataUnreached(),
                "the register names a product this source does not hold");
        assertEquals(List.of(), r.errataNotApplicable(),
                "every entry applies to a pickle seed (none is scoped to the Web API only)");
        assertEquals(List.of(), r.details(), "a conforming seed has no detail lines");
        assertFalse(r.summary().contains("stale errata"), r.summary());
        List<StoreErrata.Entry> entries = StoreErrata.load().entries();
        assertEquals(entries.size(), r.errataApplied().size());
        assertEquals(entries.stream().map(StoreErrata.Entry::id).toList(), r.errataApplied(),
                "applied in register order, every one");
        Set<String> issues = new TreeSet<>();
        entries.forEach(e -> issues.add(e.issue().substring(0, e.issue().indexOf('.'))));
        assertEquals(Set.of("#282", "#283", "#284"), issues,
                "the three applied issues, never #285");
        assertTrue(entries.size() > 500,
                "the register holds the three issues' rows: " + entries.size());
    }


    @Test
    void noLabelEndsInAFootnoteMarkerAfterTheErrata()
    {
        assertEquals(List.of(), report().footnoteLabels(),
                "owner rule 2026-10-03: no label references a printed footnote");
        assertTrue(report().warnings().stream().noneMatch(w -> w.contains("footnote")),
                report().warnings().toString());
    }


    @Test
    void theNormalisationCountersReflectTheCorpus()
    {
        StoreSeedReport.Normalisations n = report().normalisations();
        assertTrue(n.literalNewlines() > 800, "literal backslash-n sites: " + n);
        assertTrue(n.htmlEntities() >= 17, "&quot; sites (cdashig 2-3 + tig cdash): " + n);
        assertTrue(n.shortFieldNewlines() >= 6, "line breaks in labels/prompts: " + n);
        assertTrue(report().summary().contains("errata applied"), report().summary());
    }


    @Test
    void theDomainSpecificRootsAreClassTableRowsWithAUsageRestriction()
    {
        StoredClass events17 = classOf("models/sdtm/1-7", "Events");
        StoredVariable evdtyp = variable(events17.classVariables(), "--EVDTYP");
        assertEquals("MH domain only", evdtyp.usageRestrictions());
        assertEquals("Medical History Event Date Type", evdtyp.label());
        assertEquals("Variable Qualifier", evdtyp.role());
        assertEquals("Variable Qualifier of MHSTDTC and/or MHENDTC", evdtyp.roleDescription());
        int decod = events17.classVariables()
                .indexOf(variable(events17.classVariables(), "--DECOD"));
        assertEquals(decod + 1, events17.classVariables().indexOf(evdtyp), "After MHDECOD");
        for (int i = 0; i < events17.classVariables().size(); i++)
        {
            assertEquals(Integer.toString(i + 1), events17.classVariables().get(i).ordinal(),
                    "ordinals renumbered contiguously after the insert");
        }
        StoredClass findings18 = classOf("models/sdtm/1-8", "Findings");
        List<String> names = findings18.classVariables().stream().map(StoredVariable::name)
                .toList();
        int test = names.indexOf("--TEST");
        assertEquals(List.of("--AGENT", "--CONC", "--CONCU"), names.subList(test + 1, test + 4),
                "the MS chain inserted in printed order after --TEST");
        assertEquals("Not in human clinical trials; EX domain only",
                variable(classOf("models/sdtm/1-5", "Interventions").classVariables(), "--METHOD")
                        .usageRestrictions());
        assertEquals("IC domain only",
                variable(classOf("models/sdtm/1-6", "Findings").classVariables(), "--IMPLBL")
                        .usageRestrictions(),
                "1.6 prints ICIMPLBL without the nonclinical sentence");
    }


    @Test
    void theVariablesQualifiedColumnLandsInRoleDescription()
    {
        StoredVariable dosu = variable(classOf("models/sdtm/2-0", "Interventions").classVariables(),
                "--DOSU");
        assertEquals("Variable Qualifier of --DOSE; --DOSTXT; --DOSTOT", dosu.roleDescription());
        StoredVariable lvldesc = variable(dataset("models/sdtm/2-1", "RELREF").variables(),
                "LVLDESC");
        assertFalse(lvldesc.definition().startsWith(" "), "283.37 leading space trimmed");
        assertEquals("Not in AE domain",
                variable(classOf("models/sdtm/2-0", "Events").classVariables(), "--STAT")
                        .usageRestrictions(),
                "282.14 normalised");
        for (String version : List.of("2-0", "2-1"))
        {
            String key = "models/sdtm/" + version;
            assertEquals("\"A/California/7/2009 (H1N1)\"",
                    variable(classOf(key, "General Observations").classVariables(), "NHOID")
                            .examples(),
                    "283.36 re-derived: a site the first register missed (" + key + ")");
            assertTrue(variable(classOf(key, "General Observations").classVariables(), "--EVLINT")
                    .examples().startsWith("\"-P2M\" to represent"), key);
            assertEquals("\"HYPOGLYCEMIC EVENT\"",
                    variable(dataset(key, "SM").variables(), "MIDSTYPE").examples(), key);
        }
    }


    @Test
    void labelsCoresCodelistsAndOrdersAreCorrected()
    {
        assertEquals("Incl/Excl Criterion Short Name",
                variable(igDataset("standards/sdtmig/3-2", "TI").variables(), "IETESTCD").label());
        assertEquals("Sequence Identifier",
                variable(igDataset("standards/sdtmig/3-4", "GF").variables(), "GFSEQID").label());
        assertEquals("Perm",
                variable(igDataset("standards/sdtmig/3-3", "DS").variables(), "DSDY").core());
        assertEquals(List.of("C66786"),
                variable(igDataset("standards/sdtmig/3-2", "DM").variables(), "COUNTRY")
                        .codelistIds());
        assertEquals(List.of("C74559"),
                variable(igDataset("standards/sdtmig/3-1-2", "SC").variables(), "SCTESTCD")
                        .codelistIds());
        StoredDomain ae = cdashDomain("standards/cdashig/1-1-1", "AE");
        StoredField visendat = field(ae.fields(), "VISENDAT");
        assertEquals("304", visendat.ordinal());
        assertEquals("Visit End Date", visendat.prompt());
        assertNull(visendat.simpleDatatype(), "not printed, so not invented");
        StoredDataStructure tigBds = structure("standards/tig/1-0/adam", "BDS");
        List<String> sets = tigBds.variableSets().stream().map(StoredVariableSet::name).toList();
        assertEquals("Identifier", sets.get(0));
        assertFalse(sets.contains("Population Indicators"), "283.31 removed");
        assertEquals(
                List.of("10", "11", "12"), tigBds.variableSets().subList(0, 3).stream()
                        .map(StoredVariableSet::ordinal).toList(),
                "the structure keeps its ordinal range");
        StoredDataStructure bds13 = structure("standards/adam/adamig-1-3", "BDS");
        assertEquals("Identifier", bds13.variableSets().get(0).name());
        assertEquals("1", bds13.variableSets().get(0).ordinal());
        List<String> adsl12 = structure("standards/adam/adamig-1-2", "ADSL").variableSets().stream()
                .map(StoredVariableSet::name).toList();
        assertEquals(
                List.of("Identifier", "SubjectDemographics", "PopulationIndicator", "Treatment"),
                adsl12.subList(0, 4));
        StoredProduct cdash11 = product("models/cdash/1-1");
        StoredDomain ap = cdash11.domains().stream().filter(d -> "AP--".equals(d.name()))
                .findFirst().orElseThrow();
        assertEquals(List.of("APID", "SREL", "RSUBJID"),
                ap.fields().stream().map(StoredField::name).toList());
        StoredProduct cdash10 = product("models/cdash/1-0");
        StoredDomain fa = cdash10.domains().stream().filter(d -> "FA".equals(d.name())).findFirst()
                .orElseThrow();
        assertEquals(List.of("--OBJ"), fa.fields().stream().map(StoredField::name).toList());
        assertEquals("Study Day of Start of Observation",
                variable(structure("standards/adam/adam-occds-1-1", "OCCDS").variableSets().stream()
                        .filter(s -> "Timing".equals(s.name())).findFirst().orElseThrow()
                        .variables(), "--STDY").label(),
                "284.1 without the asterisk");
    }


    @Test
    void everyUsageRestrictionInEveryModelIsUnderstood()
    {
        Set<String> unparseable = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        for (String entry : java.util.Objects.requireNonNull(store).manifest().partHashes()
                .keySet())
        {
            if (!entry.startsWith("products/models/sdtm/"))
            {
                continue;
            }
            String key = entry.substring("products/".length(), entry.length() - ".json".length());
            for (StoredClass clazz : product(key).classes())
            {
                for (StoredVariable v : clazz.classVariables())
                {
                    String text = v.usageRestrictions();
                    if (text == null || !seen.add(text))
                    {
                        continue;
                    }
                    if (net.cumba.corej.core.metadata.UsageRestrictions.parse(text).isUnparseable())
                    {
                        unparseable.add(text);
                    }
                }
            }
        }
        assertTrue(seen.size() >= 20, "the restriction vocabulary: " + seen);
        assertEquals(Set.of(), unparseable,
                "every usageRestrictions text form is handled explicitly");
    }


    /**
     * D2 on the real store: the model walk behind {@code get_model_column_order} no longer answers
     * AEEVDTYP for an AE dataset at SDTMIG 3.4 (SDTM 2.0, "MH domain only"), and answers MHEVDTYP
     * for MH at SDTMIG 3.3 now that the errata added {@code --EVDTYP} to SDTM 1.7.
     */
    @Test
    void theModelWalkHonoursTheRestrictionsOnTheRealStore()
    {
        net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory factory = new net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory(
                java.util.Objects.requireNonNull(store));
        net.cumba.corej.core.exec.MetadataProvider ig34 = factory
                .forSdtm("sdtmig", "3-4", List.of()).orElseThrow();
        List<String> ae34 = ig34.getStandardModelVariables(table("AE"), null);
        assertNotNull(ae34);
        assertFalse(ae34.contains("AEEVDTYP"), "SDTM 2.0: MH domain only - " + ae34);
        assertFalse(ae34.contains("AEOCCUR"), "SDTM 2.0: Not in AE domain - " + ae34);
        assertTrue(ae34.contains("AETERM"), ae34.toString());
        List<String> mh34 = ig34.getStandardModelVariables(table("MH"), null);
        assertNotNull(mh34);
        assertTrue(mh34.contains("MHEVDTYP"), mh34.toString());
        assertTrue(mh34.contains("MHOCCUR"), mh34.toString());
        net.cumba.corej.core.exec.MetadataProvider ig33 = factory
                .forSdtm("sdtmig", "3-3", List.of()).orElseThrow();
        List<String> mh33 = ig33.getStandardModelVariables(table("MH"), null);
        assertNotNull(mh33);
        assertTrue(mh33.contains("MHEVDTYP"), "SDTM 1.7 after errata 283.3: " + mh33);
        List<String> ae33 = ig33.getStandardModelVariables(table("AE"), null);
        assertNotNull(ae33);
        assertFalse(ae33.contains("AEEVDTYP"), ae33.toString());
        int evdtyp = mh33.indexOf("MHEVDTYP");
        assertEquals("MHDECOD", mh33.get(evdtyp - 1), "After MHDECOD: " + mh33);
        List<String> ex33 = ig33.getStandardModelVariables(table("EX"), null);
        assertNotNull(ex33);
        assertTrue(ex33.contains("EXMETHOD"), "SDTM 1.7 EXMETHOD, EX domain only: " + ex33);
        List<String> cm33 = ig33.getStandardModelVariables(table("CM"), null);
        assertNotNull(cm33);
        assertFalse(cm33.contains("CMMETHOD"), cm33.toString());
    }


    private static net.cumba.datatable.IDataTable table(String aDomain)
    {
        return net.cumba.datatable.testkit.MockTable.of().name(aDomain).col("DOMAIN", aDomain)
                .build();
    }

    // ------------------------------------------------------------------ helpers


    private static StoredClass classOf(String aKey, String aClass)
    {
        return product(aKey).classes().stream().filter(c -> aClass.equals(c.name())).findFirst()
                .orElseThrow(() -> new AssertionError(aKey + " has no class " + aClass));
    }


    private static StoredDataset dataset(String aKey, String aDataset)
    {
        return product(aKey).datasets().stream().filter(d -> aDataset.equals(d.name())).findFirst()
                .orElseThrow(() -> new AssertionError(aKey + " has no dataset " + aDataset));
    }


    private static StoredDataset igDataset(String aKey, String aDataset)
    {
        return product(aKey).classes().stream().flatMap(c -> c.datasets().stream())
                .filter(d -> aDataset.equals(d.name())).findFirst()
                .orElseThrow(() -> new AssertionError(aKey + " has no dataset " + aDataset));
    }


    private static StoredDataStructure structure(String aKey, String aName)
    {
        return product(aKey).dataStructures().stream().filter(s -> aName.equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(aKey + " has no structure " + aName));
    }


    private static StoredDomain cdashDomain(String aKey, String aDomain)
    {
        return product(aKey).classes().stream().flatMap(c -> c.domains().stream())
                .filter(d -> aDomain.equals(d.name())).findFirst()
                .orElseThrow(() -> new AssertionError(aKey + " has no domain " + aDomain));
    }


    private static StoredVariable variable(List<StoredVariable> aVariables, String aName)
    {
        StoredVariable found = aVariables.stream().filter(v -> aName.equals(v.name())).findFirst()
                .orElse(null);
        assertNotNull(found, "no variable " + aName + " in "
                + aVariables.stream().map(StoredVariable::name).toList());
        return found;
    }


    private static StoredField field(List<StoredField> aFields, String aName)
    {
        return aFields.stream().filter(f -> aName.equals(f.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + aName));
    }
}
