package net.cumba.corej.define.conformance.ct;

import java.util.Optional;

/**
 * Optional CDISC Controlled Terminology lookup backing the {@code Requires: ct} rules (plan §3.6).
 * This module ships the SPI only; a concrete binding (over the CDISC library cache) is wired in the
 * {@code CdiscValidate} integration layer. When no provider is supplied, CT-gated rules SKIP with
 * {@code SKIPPED_MISSING_CT}.
 */
public interface CtProvider
{

    /** The CT codelist with this NCI c-code, or empty when unknown to the loaded CT package. */
    Optional<CtCodelist> codelistByCCode(String aCCode);


    /**
     * The CT codelist with this codelist name (e.g. {@code "Sex"}), or empty when unknown. Backs
     * the {@code nci_alias_required} codelist level (PMDA DD0031: "Codelist that is defined in
     * CDISC Controlled Terminology" — identified by name, since a missing nci:ExtCodeID alias is
     * exactly what that rule detects).
     *
     * <p>
     * A {@code default} (not abstract) method so existing lambda/minimal implementations stay
     * valid; the empty default is conservative — a provider without name lookup makes the
     * name-keyed rules find nothing, it never makes them mis-fire.
     * </p>
     */
    default Optional<CtCodelist> codelistByName(String aName)
    {
        return Optional.empty();
    }

}
