package net.cumba.corej.define.conformance.ct;

import java.util.Optional;

/**
 * Optional CDISC Controlled Terminology lookup backing the {@code Requires: ct} rules (plan §3.6).
 * This module ships the SPI only; the product binding is {@code cumba-corej-core}'s
 * {@code StoreCtProvider} over the unified metadata store, selected per run by
 * {@code DefineStoreBinding} and wired in the CLI ({@code -vx}) and the data browser's define check
 * alike. When no provider is supplied, CT-gated rules SKIP with {@code SKIPPED_MISSING_CT} — and so
 * does a rule the supplied provider cannot serve (an explicit c-code it does not hold, a name-keyed
 * rule without {@link #hasNameLookup() name lookup}).
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


    /**
     * Whether {@link #codelistByName(String)} is a real lookup on this provider. The empty default
     * above is conservative for a rule that merely consults names, but the codelist-level
     * {@code nci_alias_required} rule (PMDA DD0031) is DEFINED by name lookup: executing it over a
     * provider that answers no name makes it fire on nothing forever, which reads exactly like a
     * conformant document. The evaluator therefore SKIPs that rule ({@code SKIPPED_MISSING_CT})
     * unless a provider says {@code true} here.
     */
    default boolean hasNameLookup()
    {
        return false;
    }

}
