package net.cumba.corej.core.gen;

import java.util.Locale;

/**
 * One controlled-terminology package a Define-XML 2.1 document declares conformance to — a
 * {@code <def:Standard Type="CT" PublishingSet="SDTM" Version="2023-12-15"/>} entry
 * (PLAN-define-driven-ct-selection.md §4.1).
 *
 * <p>
 * {@code packageId} is the derived cache/store id, {@code <publishingset-lowercase>ct-<version>}
 * (e.g. {@code sdtmct-2023-12-15}) — the same shape the Python reference engine derives and the
 * unified metadata store keys CT packages on. Use {@link #of(String, String)} to derive it
 * consistently.
 * </p>
 *
 * @param packageId
 *            the derived CT package id (e.g. {@code sdtmct-2023-12-15})
 */
public record CtStandardRef(String packageId)
{

    /**
     * Builds a reference from the two declared attributes that matter, deriving {@code packageId}
     * as {@code publishingSet.toLowerCase() + "ct-" + version}. (⚑ The record used to carry the
     * OID, publishing set and version verbatim as well; nothing read them —
     * PLAN-retire-dead-multi-match-lookup U12, C13.)
     *
     * @param aPublishingSet
     *            the {@code PublishingSet} attribute; must be non-blank
     * @param aVersion
     *            the {@code Version} attribute; must be non-blank
     * @return the reference with its derived package id
     */
    public static CtStandardRef of(String aPublishingSet, String aVersion)
    {
        return new CtStandardRef(aPublishingSet.toLowerCase(Locale.ROOT) + "ct-" + aVersion);
    }
}
