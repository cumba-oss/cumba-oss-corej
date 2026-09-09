package net.cumba.corej.core.gen;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * One controlled-terminology package a Define-XML 2.1 document declares conformance to — a
 * {@code <def:Standard Type="CT" PublishingSet="SDTM" Version="2023-12-15"/>} entry
 * (PLAN-define-driven-ct-selection.md §4.1).
 *
 * <p>
 * {@code packageId} is the derived cache/store id, {@code <publishingset-lowercase>ct-<version>}
 * (e.g. {@code sdtmct-2023-12-15}) — the same shape the Python reference engine derives and the
 * unified metadata store keys CT packages on. Use {@link #of(String, String, String)} to derive it
 * consistently.
 * </p>
 *
 * @param oid
 *            the declaring {@code def:Standard}'s {@code OID}, or {@code null} when the document
 *            omits it
 * @param publishingSet
 *            the {@code PublishingSet} attribute, verbatim (e.g. {@code SDTM}, {@code ADaM})
 * @param version
 *            the {@code Version} attribute, verbatim (e.g. {@code 2023-12-15})
 * @param packageId
 *            the derived CT package id (e.g. {@code sdtmct-2023-12-15})
 */
public record CtStandardRef(@Nullable String oid, String publishingSet, String version,
        String packageId)
{

    /**
     * Builds a reference from the three declared attributes, deriving {@code packageId} as
     * {@code publishingSet.toLowerCase() + "ct-" + version}.
     *
     * @param aOid
     *            the declaring standard's OID, or {@code null}
     * @param aPublishingSet
     *            the {@code PublishingSet} attribute; must be non-blank
     * @param aVersion
     *            the {@code Version} attribute; must be non-blank
     * @return the reference with its derived package id
     */
    public static CtStandardRef of(@Nullable String aOid, String aPublishingSet, String aVersion)
    {
        return new CtStandardRef(aOid, aPublishingSet, aVersion,
                aPublishingSet.toLowerCase(Locale.ROOT) + "ct-" + aVersion);
    }
}
