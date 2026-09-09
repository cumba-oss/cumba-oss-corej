package net.cumba.corej.core.metadata.store.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.PickleCacheSeeder;
import net.cumba.corej.core.metadata.pickle.SeedOptions;
import net.cumba.web.api.cache.GzipFileApiCache;
import net.razorvine.pickle.Pickler;

/**
 * Synthetic SOURCE documents for the seeder tests: a miniature pickle cache directory and the
 * equivalent recorded web-api cache, describing the same metadata the way the two real sources do
 * (pickle: boolean {@code extensible}, Python-only keys; API: string {@code extensible}, full
 * envelope).
 *
 * <p>
 * The web cache is generated FROM the pickle directory by the production {@code PickleCacheSeeder}
 * — the same converter that produced every real pre-seeded cache — plus a hand-written
 * {@code /mdr/products} document (which has no pickle source). That makes the pair "equivalent
 * input" in exactly the plan §5.1 sense, so the conformance test's byte-identity claim is about the
 * seeders, not about hand-tuned fixtures.
 * </p>
 *
 * <p>
 * Coverage corners: all three product shapes (IG with datasets, model with class variables AND
 * top-level datasets, ADaM with {@code class}/{@code subClass} structures), a TIG substandard
 * (bare-endpoint family, {@code integrated} href mapping), the irregular ADaM model key
 * ({@code /mdr/adam/adam-2-1} → {@code models/adam/2-1}), multi-valued variable codelist refs,
 * {@code null} vs empty synonym lists, shared terms across CT packages, and non-IG
 * {@code /mdr/products} entries that must NOT enter the catalogue.
 * </p>
 */
final class SeedFixtures
{

    /** Offline base URL: cache hits work, cache misses fail fast (nothing can leave the host). */
    static final String BASE_URL = "http://127.0.0.1:1/api/";

    static final String IG_KEY = "standards/sdtmig/3-4";

    static final String TIG_KEY = "standards/tig/1-0/sdtm";

    static final String ADAM_KEY = "standards/adam/adamig-1-3";

    static final String SDTM_MODEL_KEY = "models/sdtm/2-0";

    static final String ADAM_MODEL_KEY = "models/adam/2-1";

    static final String PKG_1 = "sdtmct-2023-06-30";

    static final String PKG_2 = "sdtmct-2024-09-27";

    static final String PKG_QS = "qs-ftct-2020-05-08";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SeedFixtures()
    {
    }


    /** Writes the miniature pickle cache into {@code aDir}. */
    static void writePickleDir(Path aDir) throws IOException
    {
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put(IG_KEY, igDoc());
        standards.put(TIG_KEY, tigDoc());
        standards.put(ADAM_KEY, adamDoc());
        Map<String, Object> models = new LinkedHashMap<>();
        models.put(SDTM_MODEL_KEY, sdtmModelDoc());
        models.put(ADAM_MODEL_KEY, adamModelDoc());
        writePickle(aDir.resolve("standards_details.pkl"), standards);
        writePickle(aDir.resolve("standards_models.pkl"), models);
        writePickle(aDir.resolve(PKG_1 + ".pkl"), ctPackage(PKG_1, false));
        writePickle(aDir.resolve(PKG_2 + ".pkl"), ctPackage(PKG_2, true));
        writePickle(aDir.resolve(PKG_QS + ".pkl"), qsPackage());
    }


    /** Serialises one pickle file the way the Python populator's cache holds it. */
    static void writePickle(Path aFile, Object aValue) throws IOException
    {
        Files.write(aFile, new Pickler().dumps(aValue));
    }


    /**
     * Generates the equivalent web-api cache from the pickle directory via the production
     * {@code PickleCacheSeeder}, then adds the {@code /mdr/products} document.
     */
    static void writeWebCache(Path aPickleDir, Path aCacheDir) throws IOException
    {
        new PickleCacheSeeder().seed(SeedOptions
                .builder(new LocalPickleSource(aPickleDir), aCacheDir, BASE_URL).build());
        new GzipFileApiCache(aCacheDir.toAbsolutePath(), ".json").write("/api/mdr/products",
                MAPPER.writeValueAsString(productsDoc()).getBytes(StandardCharsets.UTF_8));
    }


    /** Keyless offline access over a recorded cache directory. */
    static CoreLibraryAccess offlineAccess(Path aCacheDir)
    {
        return CoreLibraryAccess.open(null, BASE_URL, aCacheDir);
    }


    /**
     * The {@code /mdr/products} document: the three IGs plus a Foundational Model and a Terminology
     * entry that must never reach the catalogue.
     */
    static Map<String, Object> productsDoc()
    {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("data-tabulation",
                Map.of("_links",
                        Map.of("sdtmig", List.of(
                                link("/mdr/sdtmig/3-4", "SDTMIG v3.4", "Implementation Guide"),
                                link("/mdr/sdtm/2-0", "SDTM v2.0", "Foundational Model")))));
        links.put("data-analysis",
                Map.of("_links",
                        Map.of("adam", List.of(
                                link("/mdr/adam/adamig-1-3", "ADaMIG v1.3", "Implementation Guide"),
                                link("/mdr/adam/adam-2-1", "ADaM v2.1", "Foundational Model")))));
        links.put("integrated",
                Map.of("_links", Map.of("tig", List.of(link("/mdr/integrated/tig/1-0/sdtm",
                        "SDTM for TIG v1.0", "Implementation Guide")))));
        links.put("terminology", Map.of("_links", Map.of("packages",
                List.of(link("/mdr/ct/packages/" + PKG_1, "SDTM CT", "Terminology")))));
        return Map.of("_links", links);
    }


    private static Map<String, Object> link(String aHref, String aTitle, String aType)
    {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("href", aHref);
        link.put("title", aTitle);
        link.put("type", aType);
        return link;
    }


    static Map<String, Object> igDoc()
    {
        Map<String, Object> sex = variable("SEX", "Sex", "2", "Record Qualifier");
        sex.put("core", "Exp");
        sex.put("codelistSubmissionValues", List.of("SEX"));
        sex.put("_links",
                Map.of("codelist",
                        List.of(Map.of("href", "/mdr/root/ct/sdtmct/codelists/C66731", "type",
                                "Root Value Domain"),
                                Map.of("href", "/mdr/root/ct/sdtmct/codelists/C78735", "type",
                                        "Root Value Domain"))));
        Map<String, Object> lbtestcd = variable("LBTESTCD", "Lab Test Short Name", "1", "Topic");
        lbtestcd.put("core", "Req");
        lbtestcd.put("valueList", List.of("GLUC", "ALB"));
        lbtestcd.put("describedValueDomain", "Lab test codes");
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("name", "DM");
        dm.put("label", "Demographics");
        dm.put("ordinal", "1");
        dm.put("datasetStructure", "One record per subject");
        dm.put("description", "structural description, deliberately not stored");
        dm.put("datasetVariables", List.of(sex, lbtestcd));
        Map<String, Object> doc = product("SDTMIG v3.4",
                "Study Data Tabulation Model Implementation Guide", "3-4", "/mdr/sdtmig/3-4",
                "/mdr/sdtm/2-0");
        doc.put("classes",
                List.of(productClass("SpecialPurpose", "Special-Purpose", "1", null, List.of(dm))));
        doc.put("dataset_names", Set.of("DM"));
        return doc;
    }


    static Map<String, Object> tigDoc()
    {
        Map<String, Object> aeterm = variable("AETERM", "Reported Term", "1", "Topic");
        aeterm.put("core", "Req");
        Map<String, Object> ae = new LinkedHashMap<>();
        ae.put("name", "AE");
        ae.put("label", "Adverse Events");
        ae.put("ordinal", "1");
        ae.put("datasetStructure", "One record per event");
        ae.put("datasetVariables", List.of(aeterm));
        Map<String, Object> doc = product("SDTM for TIG v1.0", "SDTM for the TIG", "1-0",
                "/mdr/integrated/tig/1-0/sdtm", "/mdr/sdtm/2-0");
        doc.put("classes", List.of(productClass("Events", "Events", "1", null, List.of(ae))));
        doc.put("dataset_names", Set.of("AE"));
        return doc;
    }


    static Map<String, Object> adamDoc()
    {
        Map<String, Object> usubjid = variable("USUBJID", "Unique Subject Identifier", "1", null);
        usubjid.put("core", "Req");
        Map<String, Object> varset = new LinkedHashMap<>();
        varset.put("name", "Identifier");
        varset.put("label", "Identifier Variables");
        varset.put("ordinal", "1");
        varset.put("analysisVariables", List.of(usubjid));
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("name", "ADSL");
        structure.put("label", "Subject-Level Analysis Dataset");
        structure.put("ordinal", "1");
        structure.put("class", "SUBJECT LEVEL ANALYSIS DATASET");
        structure.put("subClass", "ADSL SUBCLASS");
        structure.put("analysisVariableSets", List.of(varset));
        Map<String, Object> doc = product("ADaMIG v1.3", "Analysis Data Model Implementation Guide",
                "1-3", "/mdr/adam/adamig-1-3", "/mdr/adam/adam-2-1");
        doc.put("dataStructures", List.of(structure));
        return doc;
    }


    static Map<String, Object> sdtmModelDoc()
    {
        Map<String, Object> dtc = variable("--DTC", "Date/Time of Collection", "1", "Timing");
        dtc.put("roleDescription", "Timing variable");
        dtc.put("definition", "Collection date and time.");
        dtc.put("notes", "ISO 8601.");
        // ⚠ A STRING, not a list. The audit called `examples` list-valued and this fixture was
        // written to match; measured over the real cache all 260 occurrences are JSON strings, and
        // the array-only reader this fixture was feeding returned null for every one of them. The
        // fixture was the only thing keeping the field alive.
        dtc.put("examples", "2003-12-15; 2003-12-15T13:14");
        dtc.put("usageRestrictions", "None");
        dtc.put("variableCcode", "C82515");
        Map<String, Object> domain = variable("DOMAIN", "Domain Abbreviation", "2", "Identifier");
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("name", "DM");
        dm.put("label", "Demographics");
        dm.put("ordinal", "1");
        dm.put("datasetVariables", List.of(domain));
        Map<String, Object> doc = product("SDTM v2.0", "Study Data Tabulation Model", "2-0",
                "/mdr/sdtm/2-0", null);
        doc.put("classes", List.of(productClass("GeneralObservations", "General Observations", "1",
                List.of(dtc, domain), null)));
        doc.put("datasets", List.of(dm));
        doc.put("standard_type", "sdtm");
        return doc;
    }


    static Map<String, Object> adamModelDoc()
    {
        Map<String, Object> doc = product("ADaM v2.1", "Analysis Data Model", "2-1",
                "/mdr/adam/adam-2-1", null);
        doc.put("classes", List.of(productClass("ADaMDatasets", "ADaM Datasets", "1",
                List.of(variable("PARAMCD", "Parameter Code", "1", "Topic")), null)));
        return doc;
    }


    /**
     * One CT package. {@code aRevised} distinguishes the two SDTM packages: the {@code NY} codelist
     * is byte-identical in both (codelist-version dedup) while {@code YESONLY} carries a revised
     * term definition (a distinct term despite the same concept id).
     */
    static Map<String, Object> ctPackage(String aId, boolean aRevised)
    {
        Map<String, Object> yes = term("Y", "C49488", "Yes",
                aRevised ? "The affirmative response (revised)." : "The affirmative response.",
                List.of("Yes"));
        Map<String, Object> no = term("N", "C49487", "No", null, List.of());
        Map<String, Object> unknown = term("U", "C17998", "Unknown", "Not known ≥ µg", null);
        Map<String, Object> ny = codelist("NY", "C66742", "No Yes Response",
                "A codelist of yes/no responses.", List.of("Yes No"), false, List.of(no, unknown));
        Map<String, Object> yesOnly = codelist("YESONLY", "C99999", "Yes Only", null, null, true,
                List.of(yes));
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("package", aId);
        pkg.put("codelists", List.of(ny, yesOnly));
        return pkg;
    }


    static Map<String, Object> qsPackage()
    {
        Map<String, Object> unknown = term("U", "C17998", "Unknown", "Not known ≥ µg", null);
        Map<String, Object> qscat = codelist("QSCAT", "C74559", "Category of Question", null,
                List.of(), null, List.of(unknown));
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("package", PKG_QS);
        pkg.put("codelists", List.of(qscat));
        return pkg;
    }


    private static Map<String, Object> product(String aName, String aLabel, String aVersion,
            String aSelfHref, String aModelHref)
    {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", Map.of("href", aSelfHref, "type", "Implementation Guide"));
        if (aModelHref != null)
        {
            links.put("model", Map.of("href", aModelHref, "type", "Foundational Model"));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_links", links);
        doc.put("name", aName);
        doc.put("label", aLabel);
        doc.put("version", aVersion);
        doc.put("effectiveDate", "2021-11-29");
        doc.put("registrationStatus", "Final");
        doc.put("source", "Prepared by the CDISC Submission Data Standards Team");
        return doc;
    }


    private static Map<String, Object> productClass(String aName, String aLabel, String aOrdinal,
            List<Map<String, Object>> aClassVariables, List<Map<String, Object>> aDatasets)
    {
        Map<String, Object> clazz = new LinkedHashMap<>();
        clazz.put("name", aName);
        clazz.put("label", aLabel);
        clazz.put("ordinal", aOrdinal);
        if (aClassVariables != null)
        {
            clazz.put("classVariables", aClassVariables);
        }
        if (aDatasets != null)
        {
            clazz.put("datasets", aDatasets);
        }
        return clazz;
    }


    private static Map<String, Object> variable(String aName, String aLabel, String aOrdinal,
            String aRole)
    {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", aName);
        variable.put("label", aLabel);
        variable.put("ordinal", aOrdinal);
        if (aRole != null)
        {
            variable.put("role", aRole);
        }
        variable.put("simpleDatatype", "Char");
        variable.put("description", "Description of " + aName + ".");
        return variable;
    }


    private static Map<String, Object> term(String aSubmissionValue, String aConceptId,
            String aPreferredTerm, String aDefinition, List<String> aSynonyms)
    {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("submissionValue", aSubmissionValue);
        term.put("conceptId", aConceptId);
        term.put("preferredTerm", aPreferredTerm);
        if (aDefinition != null)
        {
            term.put("definition", aDefinition);
        }
        if (aSynonyms != null)
        {
            term.put("synonyms", aSynonyms);
        }
        return term;
    }


    private static Map<String, Object> codelist(String aSubmissionValue, String aConceptId,
            String aPreferredTerm, String aDefinition, List<String> aSynonyms, Boolean aExtensible,
            List<Map<String, Object>> aTerms)
    {
        Map<String, Object> codelist = new LinkedHashMap<>();
        codelist.put("submissionValue", aSubmissionValue);
        codelist.put("conceptId", aConceptId);
        codelist.put("name", aPreferredTerm + " (display name, deliberately not stored)");
        codelist.put("preferredTerm", aPreferredTerm);
        if (aDefinition != null)
        {
            codelist.put("definition", aDefinition);
        }
        if (aSynonyms != null)
        {
            codelist.put("synonyms", aSynonyms);
        }
        if (aExtensible != null)
        {
            codelist.put("extensible", aExtensible);
        }
        codelist.put("terms", aTerms);
        return codelist;
    }
}
