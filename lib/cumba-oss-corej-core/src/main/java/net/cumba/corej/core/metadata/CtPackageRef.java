package net.cumba.corej.core.metadata;

import java.util.Objects;

import net.cumba.cdisc.library.api.model.ct.CtPackage;
import org.jspecify.annotations.Nullable;

/**
 * A CT package together with the <em>id it was requested under</em> (e.g.
 * {@code sdtmct-2024-09-27}).
 *
 * <p>
 * The id must come from the request, never from the response body. {@link CtPackage#name()} carries
 * the CDISC Library's display <em>label</em> — {@code "SDTM CT 2024-09-27"} — not the id. Deriving
 * the id from {@code name()} silently breaks {@code valid_codelist_dates}, which prefix-matches
 * {@code sdtmct} / {@code adamct} and parses the effective date out of the id, mirroring the Python
 * engine ({@code cache_populator_service.load_available_ct_packages} →
 * {@code href.split("/")[-1]}).
 * </p>
 *
 * <p>
 * Pairing the two in one value keeps them inseparable across the factories that take more than one
 * CT package (see {@code CdiscLibraryMetadataLibrary.fromAdam}, which takes an ADaM package plus an
 * optional SDTM fallback), so an id can never be attached to the wrong package.
 * </p>
 *
 * @param id
 *            the requested CT package id, or {@code null} when the package was not requested by id
 *            (the pickle path substitutes an empty package when no CT is configured). A
 *            {@code null} id contributes nothing to {@code CtVersion} /
 *            {@code PublishedCtPackages}.
 * @param pkg
 *            the materialised CT package; never {@code null}.
 */
public record CtPackageRef(@Nullable String id, CtPackage pkg)
{

    /** Canonical constructor: {@code pkg} is mandatory, {@code id} is optional. */
    public CtPackageRef
    {
        Objects.requireNonNull(pkg, "pkg");
    }


    /**
     * Creates a reference with no known id — the package contributes codelists but nothing to
     * {@code CtVersion} / {@code PublishedCtPackages}.
     *
     * @param aPackage
     *            the CT package.
     * @return the reference.
     */
    public static CtPackageRef anonymous(CtPackage aPackage)
    {
        return new CtPackageRef(null, aPackage);
    }
}
