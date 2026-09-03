package net.cumba.corej.define.conformance.library;

import java.util.List;
import java.util.Optional;

/**
 * Optional CDISC implementation-guide library lookup backing the {@code Requires: library} rules
 * (plan define-library-provider). This module ships the SPI only — a concrete binding (over the
 * {@code net.cumba.cdisc.library.api} models) belongs to an integration layer, exactly like
 * {@code CtProvider}'s CT binding. When no provider is supplied, library-gated rules SKIP with
 * {@code SKIPPED_MISSING_LIBRARY}.
 *
 * <p>
 * Every method answers empty for anything the loaded library does not know — the library-backed
 * kinds treat an empty answer as "out of the rule's reach" (no finding), so a partial provider can
 * only under-report, never mis-fire.
 * </p>
 *
 * <p>
 * <b>Standard-name spellings:</b> the kinds pass the document's verbatim standard name, and the two
 * Define-XML versions spell it differently — 2.1 {@code def:Standard/@Name} uses
 * {@code SDTMIG}/{@code SENDIG}, while 2.0's {@code def:StandardName} CT uses {@code SDTM-IG}/
 * {@code SEND-IG}/{@code SEND-IG-AR}/… Implementations must accept both (treat the name
 * hyphen-insensitively).
 * </p>
 */
public interface LibraryProvider
{

    /**
     * The IG-defined label of a dataset (e.g. SDTMIG 3.4, {@code "DM"} → {@code "Demographics"}),
     * or empty when the standard/version/dataset is unknown to the library.
     */
    Optional<String> datasetLabel(String aStandardName, String aStandardVersion,
            String aDatasetName);


    /** The IG-defined label of a dataset variable, or empty when unknown. */
    Optional<String> variableLabel(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName);


    /**
     * The NCI c-code of the CT codelist the IG assigns to a variable, or empty when the IG assigns
     * none. Presence doubles as the "this variable requires controlled terminology" signal (PMDA
     * DD0124); the c-code itself is PMDA DD0118's comparison value.
     */
    Optional<String> variableCodelistCCode(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName);


    /**
     * The label of the SDTM Event/Intervention-class qualifier variable whose name minus the
     * {@code --} prefix equals the fragment (e.g. {@code "OCCUR"} → {@code "Occurrence"}), per the
     * SDTM model behind the named IG version; empty when the fragment names no such qualifier (PMDA
     * DD0116).
     */
    Optional<String> qualifierVariableLabel(String aStandardName, String aStandardVersion,
            String aFragment);


    /**
     * The IG-defined Core designation of a variable ({@code "Req"}/{@code "Exp"}/{@code "Perm"}),
     * or empty when unknown (CDISC 67: Core {@code Req} ⇒ ItemRef Mandatory must be {@code Yes}).
     *
     * <p>
     * A {@code default} (not abstract) method, like {@code CtProvider.codelistByName}: existing
     * implementations stay valid, and the empty default is conservative — the Core-keyed rule finds
     * nothing, it never mis-fires.
     * </p>
     */
    default Optional<String> variableCoreDesignation(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return Optional.empty();
    }


    /**
     * Every published version identifier of the named standard (e.g. {@code SDTMIG} →
     * {@code ["3.2","3.3","3.4"]}), or an empty list when the standard name is unknown to the
     * library — the version-keyed rule (CDISC 263) then finds nothing. Same conservative
     * {@code default} contract as {@link #variableCoreDesignation}.
     */
    default List<String> publishedStandardVersions(String aStandardName)
    {
        return List.of();
    }

}
