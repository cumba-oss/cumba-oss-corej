package net.cumba.corej.core.metadata;

import java.io.Serial;
import java.util.List;

/**
 * <b>Ruling 3, at declaration time</b> — a declared metadata product ({@code --metadata-products})
 * whose published data structures map to <em>no</em>
 * {@link AdamDataStructureDetector#STRUCTURE_TOKENS} token at all, and which therefore cannot
 * contribute a single variable to the run.
 *
 * <p>
 * ⚠⚠ <b>The granularity is deliberate: every structure, not any.</b> A blanket "fail when any
 * structure maps to no token" would reject {@code tig/1-0/adam} — a currently-working run — over
 * its {@code REFERENDS} structure alone. A product some of whose structures map only WARNs; the
 * hard failure is reserved for the case where declaring the product is a guaranteed silent no-op,
 * which under ruling 1 is worse than the metadata conflict this plan set out to fix.
 * </p>
 *
 * <p>
 * ⚑ After the Phase 6a mappings ({@code REFERENCE DATA STRUCTURE}, the {@code ADAE} class alias and
 * the two {@code (cacheKey, structureName)} overrides) <b>all 12 cached ADaM/TIG products map
 * completely</b>, measured 2026-08-28 — nothing shipped reaches either branch. This check exists
 * for the <em>next</em> product CDISC publishes.
 * </p>
 *
 * <p>
 * ⛔ Deliberately <b>not</b> routed through {@code MetadataLibraryProvider.degraded(...)}: a fetch
 * that fails is an availability problem the run can degrade around, whereas a product that can
 * never answer is a bad declaration and the user has to see it.
 * </p>
 */
public class UnmappedMetadataProductException extends IllegalArgumentException
{

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param aCacheKey
     *            the declared product's {@code standards/...} cache key
     * @param aStructureNames
     *            the names of its published structures, none of which map to a token
     */
    public UnmappedMetadataProductException(String aCacheKey, List<String> aStructureNames)
    {
        super("Declared metadata product " + aCacheKey + " publishes no data structure this engine "
                + "can address: " + List.copyOf(aStructureNames) + " map to none of "
                + AdamDataStructureDetector.STRUCTURE_TOKENS
                + ". Declaring it would be a silent no-op; remove it from --metadata-products, or "
                + "add the mapping.");
    }

}
