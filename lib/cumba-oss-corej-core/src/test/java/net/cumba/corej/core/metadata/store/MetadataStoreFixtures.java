package net.cumba.corej.core.metadata.store;

import java.util.Comparator;
import java.util.List;

/**
 * Synthetic, fully deterministic store content for the P1 format tests. Deliberately NOT read from
 * the pickle cache or any real seed source — coupling the format tests to a seeder input would make
 * the format untestable on its own (seeding is P2).
 *
 * <p>
 * The content exercises the format's load-bearing corners: shared terms across packages (term
 * dedup), an identical codelist in two packages (codelist-version dedup), a codelist differing by
 * one term (near-duplicate versions), {@code null} vs empty synonym lists, unicode, all three
 * product shapes (IG / model / ADaM), multi-valued variable codelist refs, and a published CT
 * package the store does not hold.
 * </p>
 */
final class MetadataStoreFixtures
{

    static final String IG_KEY = "standards/sdtmig/3-4";

    static final String MODEL_KEY = "models/sdtm/2-0";

    static final String ADAM_KEY = "standards/adam/adamig-1-3";

    static final String PKG_SDTM_1 = "sdtmct-2023-06-30";

    static final String PKG_SDTM_2 = "sdtmct-2023-09-29";

    static final String PKG_QSFT = "qs-ftct-2020-05-08";

    /** Published upstream but NOT held by the store — publishedCtPackages() must still list it. */
    static final String PKG_PUBLISHED_ONLY = "sendct-2024-03-29";

    private MetadataStoreFixtures()
    {
    }


    /** A writer loaded with the full fixture content. */
    static MetadataStoreWriter populatedWriter()
    {
        return new MetadataStoreWriter().addProduct(igProduct()).addProduct(modelProduct())
                .addProduct(adamProduct()).addCtPackage(sdtmPackage1()).addCtPackage(sdtmPackage2())
                .addCtPackage(qsftPackage()).publishedCtPackages(publishedCtPackages())
                .productCatalogue(productCatalogue())
                .addProvenance(new StoreProvenance("pickle", "v0.17.1", "2026-09-08T00:00:00Z"))
                .addProvenance(new StoreProvenance("web-api", "https://library.cdisc.org", ""));
    }


    static List<String> publishedCtPackages()
    {
        return List.of(PKG_SDTM_1, PKG_SDTM_2, PKG_QSFT, PKG_PUBLISHED_ONLY);
    }


    static List<String> productCatalogue()
    {
        return List.of(IG_KEY, ADAM_KEY);
    }


    static StoredProduct igProduct()
    {
        StoredVariable studyid = StoredVariable.builder().name("STUDYID").label("Study Identifier")
                .ordinal("1").core("Req").role("Identifier").simpleDatatype("Char")
                .description("Unique identifier for a study.").build();
        StoredVariable sex = StoredVariable.builder().name("SEX").label("Sex").ordinal("2")
                .core("Exp").role("Record Qualifier").simpleDatatype("Char")
                .codelistSubmissionValues(List.of("SEX")).codelistIds(List.of("C66731")).build();
        StoredVariable lbtestcd = StoredVariable.builder().name("LBTESTCD")
                .label("Lab Test or Examination Short Name").ordinal("3").core("Req").role("Topic")
                .simpleDatatype("Char").valueList(List.of("GLUC", "ALB"))
                .describedValueDomain("Lab test codes").codelistIds(List.of("C65047", "C67154"))
                .build();
        StoredDataset dm = new StoredDataset("DM", "Demographics", "1", "One record per subject",
                List.of(studyid, sex));
        StoredDataset lb = new StoredDataset("LB", "Laboratory Test Results", "2",
                "One record per lab test per subject", List.of(lbtestcd));
        return StoredProduct.builder().key(IG_KEY).name("SDTMIG v3.4")
                .label("Study Data Tabulation Model Implementation Guide").version("3-4")
                .modelHref("/mdr/sdtm/2-0")
                .classes(List.of(
                        new StoredClass("SpecialPurpose", "Special-Purpose", "1", List.of(),
                                List.of(dm)),
                        new StoredClass("Findings", "Findings", "2", List.of(), List.of(lb))))
                .build();
    }


    static StoredProduct modelProduct()
    {
        StoredVariable timing = StoredVariable.builder().name("--DTC")
                .label("Date/Time of Collection").ordinal("1").role("Timing")
                .roleDescription("Timing variable").simpleDatatype("Char")
                .definition("Collection date and time.").notes("ISO 8601.")
                .examples("2003-12-15; 2003-12-15T13:14").usageRestrictions("None")
                .variableCcode("C82515").build();
        StoredVariable domain = StoredVariable.builder().name("DOMAIN").label("Domain Abbreviation")
                .ordinal("2").role("Identifier").simpleDatatype("Char").build();
        return StoredProduct.builder().key(MODEL_KEY).name("SDTM v2.0")
                .label("Study Data Tabulation Model").version("2-0")
                .classes(List.of(new StoredClass("GeneralObservations", "General Observations", "1",
                        List.of(timing, domain), List.of())))
                .datasets(List
                        .of(new StoredDataset("DM", "Demographics", "1", null, List.of(domain))))
                .build();
    }


    static StoredProduct adamProduct()
    {
        StoredVariable usubjid = StoredVariable.builder().name("USUBJID")
                .label("Unique Subject Identifier").ordinal("1").core("Req").simpleDatatype("Char")
                .build();
        return StoredProduct.builder().key(ADAM_KEY).name("ADaMIG v1.3")
                .label("Analysis Data Model Implementation Guide").version(
                        "1-3")
                .dataStructures(List.of(new StoredDataStructure("ADSL",
                        "Subject-Level Analysis Dataset", "1", "SUBJECT LEVEL ANALYSIS DATASET",
                        "ADSL SUBCLASS", List.of(new StoredVariableSet("Identifier",
                                "Identifier Variables", "1", List.of(usubjid))))))
                .build();
    }

    // -- CT fixture terms; NO, U and NEW_TERM are shared between packages (dedup). ---------------


    static StoredTerm termYes()
    {
        return new StoredTerm("Y", "C49488", "Yes", "The affirmative response.", List.of("Yes"));
    }


    /** Same concept as {@link #termYes()} but a revised definition — must dedup as DISTINCT. */
    static StoredTerm termYesRevised()
    {
        return new StoredTerm("Y", "C49488", "Yes", "The affirmative response (revised).",
                List.of("Yes"));
    }


    static StoredTerm termNo()
    {
        return new StoredTerm("N", "C49487", "No", null, List.of());
    }


    static StoredTerm termUnknown()
    {
        return new StoredTerm("U", "C17998", "Unknown", "Not known ≥ unbestimmt µg", null);
    }


    static StoredTerm termNewInSecondPackage()
    {
        return new StoredTerm("NA", "C48660", "Not Applicable", null, null);
    }


    static StoredCodelist codelistNyStable()
    {
        return new StoredCodelist("NY", "C66742", "No Yes Response",
                "A codelist of yes/no responses.", List.of("Yes No"), Boolean.FALSE,
                List.of(termNo(), termUnknown()));
    }


    static StoredCodelist codelistYesV1()
    {
        return new StoredCodelist("YESONLY", "C99999", "Yes Only", null, null, Boolean.TRUE,
                List.of(termYes()));
    }


    static StoredCodelist codelistYesV2()
    {
        return new StoredCodelist("YESONLY", "C99999", "Yes Only", null, null, Boolean.TRUE,
                List.of(termYesRevised(), termNewInSecondPackage()));
    }


    static StoredCodelist codelistQs()
    {
        return new StoredCodelist("QSCAT", "C74559", "Category of Question", null, List.of(), null,
                List.of(termUnknown(), termNewInSecondPackage()));
    }


    static StoredCtPackage sdtmPackage1()
    {
        return new StoredCtPackage(PKG_SDTM_1, List.of(codelistNyStable(), codelistYesV1()));
    }


    /**
     * Cumulative snapshot: {@code NY} identical to package 1 (version dedup), {@code YESONLY}
     * revised.
     */
    static StoredCtPackage sdtmPackage2()
    {
        return new StoredCtPackage(PKG_SDTM_2, List.of(codelistNyStable(), codelistYesV2()));
    }


    static StoredCtPackage qsftPackage()
    {
        return new StoredCtPackage(PKG_QSFT, List.of(codelistQs()));
    }

    // -- Canonical comparison helper -------------------------------------------------------------


    /**
     * The store keeps a codelist's terms as a sorted content-ordered set, so source term order is
     * not preserved. For equality assertions both sides are canonicalised with a comparator that is
     * deliberately test-local ({@code toString} order) — independent of the production content
     * order, so a production ordering bug cannot leak into the expectation.
     */
    static StoredCtPackage canonical(StoredCtPackage aPackage)
    {
        List<StoredCodelist> codelists = aPackage.codelists().stream()
                .map(c -> new StoredCodelist(c.submissionValue(), c.conceptId(), c.preferredTerm(),
                        c.definition(), c.synonyms(), c.extensible(), c.terms().stream()
                                .sorted(Comparator.comparing(StoredTerm::toString)).toList()))
                .toList();
        return new StoredCtPackage(aPackage.id(), codelists);
    }
}
