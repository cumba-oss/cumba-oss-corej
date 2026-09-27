package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.web.api.dev.MapResource;
import org.jspecify.annotations.Nullable;

/**
 * {@code PLAN-custom-domain-model-walk} E4 — a small SDTMIG product and the provider built over it
 * <b>the way production builds it</b>: the provider's metadata library is the IG product's own
 * table view ({@code StoreMetadataProviderFactory.forSdtm} →
 * {@code CdiscLibraryMetadataLibrary.fromStoredSdtm} →
 * {@code MetadataLibraryProvider.forStoredSdtm}).
 *
 * <p>
 * That shape is the point. A domain the IG does not define has <em>no table</em> in that view, so a
 * class resolver that reads the view — rather than the dataset — cannot place it in a class. The
 * older custom-domain tests built the provider over a hand-made <em>study</em> library that does
 * contain the custom table, a shape no production caller builds.
 * </p>
 *
 * <p>
 * The product: GENERAL OBSERVATIONS (identifiers and timing), EVENTS ({@code AE}), INTERVENTIONS
 * ({@code CM}), FINDINGS ({@code LB}), FINDINGS ABOUT ({@code FA}), SUPPQUAL and ASSOCIATED
 * PERSONS. Every variable carries a role, so the natural-key and timing selections have something
 * to select. {@code VS} is deliberately absent: the curated {@code DomainClassMap} maps it to
 * FINDINGS, which makes it this product's IG-absent <em>standard</em> domain.
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


    /** The SDTMIG product (see the class javadoc). */
    public static SdtmProduct product()
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
        List<Map<String, Object>> suppqual = List.of(variable("STUDYID", "1", "Identifier"),
                variable("RDOMAIN", "2", "Identifier"), variable("USUBJID", "3", "Identifier"),
                variable("IDVAR", "4", "Identifier"), variable("IDVARVAL", "5", "Identifier"),
                variable("QNAM", "6", "Topic"), variable("QVAL", "7", "Result Qualifier"));
        List<Map<String, Object>> associatedPersons = List.of(variable("APID", "5", "Identifier"),
                variable("USUBJID", "6", "Identifier"));

        List<Map<String, Object>> classes = new ArrayList<>();
        classes.add(observationClass("General Observations", genObs, List.of()));
        classes.add(observationClass("Events", events, List.of(dataset("AE", List.of()))));
        classes.add(observationClass("Interventions", interventions,
                List.of(dataset("CM", List.of()))));
        classes.add(observationClass("Findings", findings, List.of(dataset("LB", List.of()))));
        classes.add(observationClass("Findings About", findingsAbout,
                List.of(dataset("FA", List.of()))));
        classes.add(observationClass("SUPPQUAL", suppqual, List.of(dataset("SUPPQUAL", suppqual))));
        classes.add(observationClass("Associated Persons", associatedPersons, List.of()));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", VERSION);
        product.put("classes", classes);
        return MapResource.of(product, SdtmProduct.class);
    }


    /**
     * The provider as production builds it: over the IG product's own table view, which carries no
     * table for a domain the IG does not define.
     */
    public static MetadataLibraryProvider igViewProvider()
    {
        SdtmProduct product = product();
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("name", "empty");
        ct.put("codelists", List.of());
        CdiscLibraryMetadataLibrary view = ApiModelLibraries.fromSdtm(STANDARD, VERSION, product,
                CtPackageRef.anonymous(MapResource.of(ct, CtPackage.class)));
        return ApiModelLibraries.provider(view, product, STANDARD, VERSION);
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
