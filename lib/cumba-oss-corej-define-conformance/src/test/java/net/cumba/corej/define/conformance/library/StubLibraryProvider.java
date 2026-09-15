package net.cumba.corej.define.conformance.library;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic in-memory {@link LibraryProvider} for the library-gated rule tests, modelled on
 * {@code ct.StubCtProvider}. It knows exactly one implementation guide — SDTMIG 3.4 — plus SENDIG
 * 3.1, and answers empty for everything else, which is the interface's documented contract ("every
 * method answers empty for anything the loaded library does not know").
 *
 * <p>
 * Standard names are matched <b>hyphen-insensitively and case-insensitively</b>, as
 * {@link LibraryProvider}'s class javadoc requires of every implementation: Define-XML 2.1 spells
 * the IG {@code SDTMIG} in {@code def:Standard/@Name} while 2.0's {@code def:StandardName} CT uses
 * {@code SDTM-IG}. A stub that only knew the 2.1 spelling would make the 2.0 leg of every
 * library-backed rule silently unreachable, which is precisely the shape these tests exist to
 * detect.
 * </p>
 */
public final class StubLibraryProvider implements LibraryProvider
{

    private static final String SDTMIG_34 = "SDTMIG|3.4";

    private static final String SENDIG_31 = "SENDIG|3.1";

    private static final String ADAMIG_13 = "ADAMIG|1.3";

    /** Dataset labels, keyed "<standard>|<version>|<dataset>". */
    private static final Map<String, String> DATASET_LABELS = Map.of(SDTMIG_34 + "|DM",
            "Demographics", SDTMIG_34 + "|AE", "Adverse Events", SDTMIG_34 + "|SUPPDM",
            "Supplemental Qualifiers for DM", SENDIG_31 + "|DM", "Demographics", ADAMIG_13 + "|DM",
            "Demographics (ADaM spelling)");

    /** Variable labels, keyed "<standard>|<version>|<dataset>|<variable>". */
    private static final Map<String, String> VARIABLE_LABELS = Map.of(SDTMIG_34 + "|DM|SEX", "Sex",
            SDTMIG_34 + "|DM|USUBJID", "Unique Subject Identifier", SDTMIG_34 + "|AE|AETERM",
            "Reported Term for the Adverse Event");

    /** Variables the IG assigns a CT codelist, keyed like {@link #VARIABLE_LABELS}. */
    private static final Map<String, String> VARIABLE_CODELISTS = Map.of(SDTMIG_34 + "|DM|SEX",
            "C66731", SDTMIG_34 + "|AE|AESEV", "C66769");

    /** SDTM Event/Intervention qualifier fragments, keyed "<standard>|<version>|<fragment>". */
    private static final Map<String, String> QUALIFIERS = Map.of(SDTMIG_34 + "|OCCUR", "Occurrence",
            SDTMIG_34 + "|SEV", "Severity/Intensity");

    /** IG Core designations, keyed like {@link #VARIABLE_LABELS}. */
    private static final Map<String, String> CORE = Map.of(SDTMIG_34 + "|DM|USUBJID", "Req",
            SDTMIG_34 + "|DM|SEX", "Exp", SDTMIG_34 + "|AE|AETERM", "Req",
            ADAMIG_13 + "|DM|USUBJID", "Req");

    private static final Map<String, List<String>> VERSIONS = Map.of("SDTMIG",
            List.of("3.2", "3.3", "3.4"), "SENDIG", List.of("3.0", "3.1"));

    /**
     * The library's key normalisation: upper-cased and hyphen-stripped, so {@code SDTM-IG} and
     * {@code SDTMIG} are the same standard.
     */
    private static String normalise(String aName)
    {
        return aName.replace("-", "").toUpperCase(Locale.ROOT);
    }


    private static String key(String... aParts)
    {
        StringBuilder out = new StringBuilder(normalise(aParts[0]));
        for (int i = 1; i < aParts.length; i++)
        {
            out.append('|').append(aParts[i]);
        }
        return out.toString();
    }


    @Override
    public Optional<String> datasetLabel(String aStandardName, String aStandardVersion,
            String aDatasetName)
    {
        return Optional
                .ofNullable(DATASET_LABELS.get(key(aStandardName, aStandardVersion, aDatasetName)));
    }


    @Override
    public Optional<String> variableLabel(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return Optional.ofNullable(VARIABLE_LABELS
                .get(key(aStandardName, aStandardVersion, aDatasetName, aVariableName)));
    }


    @Override
    public Optional<String> variableCodelistCCode(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return Optional.ofNullable(VARIABLE_CODELISTS
                .get(key(aStandardName, aStandardVersion, aDatasetName, aVariableName)));
    }


    @Override
    public Optional<String> qualifierVariableLabel(String aStandardName, String aStandardVersion,
            String aFragment)
    {
        return Optional.ofNullable(QUALIFIERS.get(key(aStandardName, aStandardVersion, aFragment)));
    }


    @Override
    public Optional<String> variableCoreDesignation(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return Optional.ofNullable(
                CORE.get(key(aStandardName, aStandardVersion, aDatasetName, aVariableName)));
    }


    @Override
    public List<String> publishedStandardVersions(String aStandardName)
    {
        return VERSIONS.getOrDefault(normalise(aStandardName), List.of());
    }

}
