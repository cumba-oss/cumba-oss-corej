package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.web.api.dev.MapResource;
import org.jspecify.annotations.Nullable;

/**
 * {@code PLAN-custom-domain-model-walk} E4 — a small SDTMIG product, its linked SDTM model product,
 * and the provider built over them <b>the way production builds it</b>
 * ({@code StoreMetadataProviderFactory.forSdtm} →
 * {@code CdiscLibraryMetadataLibrary.fromStoredSdtm} →
 * {@code MetadataLibraryProvider.forStoredSdtm}): the provider's metadata library is the IG
 * product's own table view, the IG product owns the datasets, and the model product owns the class
 * variables.
 *
 * <p>
 * That shape is the point. A domain the IG does not define has <em>no table</em> in that view, so a
 * class resolver that reads the view — rather than the dataset — cannot place it in a class. The
 * older custom-domain tests built the provider over a hand-made <em>study</em> library that does
 * contain the custom table, a shape no production caller builds.
 * </p>
 *
 * <p>
 * ⚑ What this proves and what it does not: the fixture's products are small and hand-made, so E4
 * proves the walk's class <em>selection</em> and plumbing. The real products — every package, the
 * store's model/IG version skew, the shipped rules — are exercised by the corpus repository's
 * {@code CustomDomainModelWalkStoreTest} over the seeded store. {@code VS} is deliberately absent
 * from the IG: the curated {@code DomainClassMap} maps it to FINDINGS, which makes it this
 * product's IG-absent <em>standard</em> domain.
 * </p>
 */
public final class CustomDomainWalkFixture
{

    /** The standard name the provider is built for. */
    public static final String STANDARD = "sdtmig";

    /** The standard version the provider is built for. */
    public static final String VERSION = "3-4";

    private CustomDomainWalkFixture()
    {
    }


    private static Map<String, Object> variable(String aName, String aOrdinal, String aRole)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", aName);
        v.put("label", aName + " label");
        v.put("ordinal", aOrdinal);
        v.put("simpleDatatype", "Char");
        v.put("role", aRole);
        return v;
    }


    private static Map<String, Object> dataset(String aName, List<Map<String, Object>> aVariables)
    {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", aName);
        d.put("label", aName + " dataset");
        d.put("datasetStructure", "");
        d.put("datasetVariables", aVariables);
        return d;
    }


    private static Map<String, Object> observationClass(String aName,
            List<Map<String, Object>> aClassVariables, List<Map<String, Object>> aDatasets)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", aName);
        c.put("classVariables", aClassVariables);
        c.put("datasets", aDatasets);
        return c;
    }


    private static SdtmProduct product(String aName, List<Map<String, Object>> aClasses)
    {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", aName);
        product.put("version", VERSION);
        product.put("classes", aClasses);
        return MapResource.of(product, SdtmProduct.class);
    }


    /**
     * The SDTM <b>model</b> product: the observation classes and their class variables (GENERAL
     * OBSERVATIONS identifiers and timing, EVENTS, INTERVENTIONS, FINDINGS, FINDINGS ABOUT,
     * ASSOCIATED PERSONS). As in the store, the model — not the IG — carries the class variables.
     */
    public static SdtmProduct modelProduct()
    {
        List<Map<String, Object>> genObs = List.of(variable("STUDYID", "1", "Identifier"),
                variable("DOMAIN", "2", "Identifier"), variable("USUBJID", "3", "Identifier"),
                variable("--SEQ", "4", "Identifier"), variable("VISITNUM", "30", "Timing"),
                variable("--DTC", "31", "Timing"), variable("--DY", "32", "Timing"));
        List<Map<String, Object>> events = List.of(variable("--TERM", "10", "Topic"),
                variable("--DECOD", "11", "Synonym Qualifier"),
                variable("--CAT", "12", "Grouping Qualifier"));
        List<Map<String, Object>> interventions = List.of(variable("--TRT", "10", "Topic"),
                variable("--DOSE", "11", "Record Qualifier"));
        List<Map<String, Object>> findings = List.of(variable("--TESTCD", "10", "Topic"),
                variable("--TEST", "11", "Synonym Qualifier"),
                variable("--CAT", "12", "Grouping Qualifier"),
                variable("--ORRES", "13", "Result Qualifier"),
                variable("--REPNUM", "14", "Record Qualifier"));
        List<Map<String, Object>> findingsAbout = List.of(variable("--OBJ", "12", "Topic"));
        List<Map<String, Object>> associatedPersons = List.of(variable("APID", "5", "Identifier"),
                variable("USUBJID", "6", "Identifier"));
        List<Map<String, Object>> classes = new ArrayList<>();
        classes.add(observationClass("General Observations", genObs, List.of()));
        classes.add(observationClass("Events", events, List.of()));
        classes.add(observationClass("Interventions", interventions, List.of()));
        classes.add(observationClass("Findings", findings, List.of()));
        classes.add(observationClass("Findings About", findingsAbout, List.of()));
        classes.add(observationClass("Associated Persons", associatedPersons, List.of()));
        return product("SDTM", classes);
    }


    /**
     * The SDTMIG product: its observation classes own the IG datasets ({@code AE}, {@code CM},
     * {@code LB}, {@code FA}, {@code SUPPQUAL}) with their variables, and carry no class variables
     * of their own — the store's shape (the class variables are the linked model's).
     */
    public static SdtmProduct igProduct()
    {
        List<Map<String, Object>> suppqual = List.of(variable("STUDYID", "1", "Identifier"),
                variable("RDOMAIN", "2", "Identifier"), variable("USUBJID", "3", "Identifier"),
                variable("IDVAR", "4", "Identifier"), variable("IDVARVAL", "5", "Identifier"),
                variable("QNAM", "6", "Topic"), variable("QVAL", "7", "Result Qualifier"));
        List<Map<String, Object>> classes = new ArrayList<>();
        classes.add(observationClass("General Observations", List.of(), List.of()));
        classes.add(observationClass("Events", List.of(),
                List.of(dataset("AE", igVariables("AE", "--TERM")))));
        classes.add(observationClass("Interventions", List.of(),
                List.of(dataset("CM", igVariables("CM", "--TRT")))));
        classes.add(observationClass("Findings", List.of(),
                List.of(dataset("LB", igVariables("LB", "--TESTCD")))));
        classes.add(observationClass("Findings About", List.of(),
                List.of(dataset("FA", igVariables("FA", "--TESTCD")))));
        classes.add(observationClass("Relationship", List.of(),
                List.of(dataset("SUPPQUAL", suppqual))));
        return product("SDTMIG", classes);
    }


    /** An IG dataset's variables: the identifiers, its topic and one timing variable. */
    private static List<Map<String, Object>> igVariables(String aDomain, String aTopic)
    {
        return List.of(variable("STUDYID", "1", "Identifier"),
                variable("DOMAIN", "2", "Identifier"), variable("USUBJID", "3", "Identifier"),
                variable(aDomain + "SEQ", "4", "Identifier"),
                variable(aTopic.replace("--", aDomain), "10", "Topic"),
                variable(aDomain + "DTC", "31", "Timing"));
    }


    private static CtPackageRef emptyCt()
    {
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("name", "empty");
        ct.put("codelists", List.of());
        return CtPackageRef.anonymous(MapResource.of(ct, CtPackage.class));
    }


    /**
     * The provider as production builds it ({@code StoreMetadataProviderFactory.forSdtm}): over the
     * IG product's own table view — which carries no table for a domain the IG does not define —
     * with the IG product and its linked model product.
     */
    public static MetadataLibraryProvider igViewProvider()
    {
        SdtmProduct ig = igProduct();
        CdiscLibraryMetadataLibrary view = ApiModelLibraries.fromSdtm(STANDARD, VERSION, ig,
                emptyCt());
        return ApiModelLibraries.provider(view, ig, modelProduct(), STANDARD, VERSION);
    }


    /**
     * The same products over a hand-made <em>study</em> library — the shape the older custom-domain
     * tests built. No production caller builds a provider over a study library with products.
     */
    public static MetadataLibraryProvider studyLibraryProvider(IMetadataLibrary aStudy)
    {
        return ApiModelLibraries.provider(aStudy, igProduct(), modelProduct(), STANDARD, VERSION);
    }


    /**
     * A one-row dataset: member name {@code aMember}, a {@code DOMAIN} column holding
     * {@code aDomain} (omitted when {@code aDomain} is {@code null}), then {@code aColumns}.
     */
    public static IDataTable dataset(String aMember, @Nullable String aDomain, String... aColumns)
    {
        MockTable t = MockTable.of().name(aMember);
        if (aDomain != null)
        {
            t.col("DOMAIN", aDomain);
        }
        for (String column : aColumns)
        {
            t.col(column, "X");
        }
        return t.build();
    }
}
