package net.cumba.corej.core.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.CustomLog;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreFormatException;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import org.jspecify.annotations.Nullable;

/**
 * The product catalogue a {@code --metadata-products} token is resolved against — since cache P4
 * ({@code plans/PLAN-metadata-cache-unification.md}) a single source: the <b>unified metadata
 * store</b>'s declarable product catalogue ({@link MetadataStore#productCatalogue()}, first-class
 * manifest data written by the seeders). The pre-P4 union of the pickle cache's
 * {@code standardKeys()} with the CDISC Library API's {@code /mdr/products} list is gone, and with
 * it both of this class's historical warts:
 *
 * <ul>
 * <li>the {@code http://127.0.0.1:1/} offline base URL — it existed only because a keyless API
 * client could still reach the network (plan §6); no API client is constructed here any more;</li>
 * <li>the process-wide memoisation with no invalidation (plan §8) — a store open plus one manifest
 * read is cheap enough to do per resolution, so a re-seeded store is picked up without a JVM
 * restart.</li>
 * </ul>
 *
 * <p>
 * A store that is configured but unreadable contributes nothing, with a WARN — resolution then
 * proceeds with an empty key set, where only full-key tokens resolve (see
 * {@code ProductKeyResolver}). The same holds when no store is configured at all.
 * </p>
 */
@CustomLog
public final class MetadataProductCatalogue
{

    private final Set<String> keys;

    private final @Nullable StoreFormatException storeFormatProblem;

    private MetadataProductCatalogue(Set<String> aKeys)
    {
        this(aKeys, null);
    }


    private MetadataProductCatalogue(Set<String> aKeys,
            @Nullable StoreFormatException aStoreFormatProblem)
    {
        keys = Collections.unmodifiableSet(new LinkedHashSet<>(aKeys));
        storeFormatProblem = aStoreFormatProblem;
    }


    /**
     * The catalogue of the ambiently configured unified metadata store, i.e.
     * {@link #configuredFrom(String) configuredFrom(null)}.
     */
    public static MetadataProductCatalogue configured()
    {
        return configuredFrom(null);
    }


    /**
     * The catalogue for the current configuration, with an explicit store file taking precedence
     * over the ambient one — the same explicit-first contract as
     * {@link StoreMetadataProviderFactory#resolveConfiguredFile(String)}: a surface that lets the
     * user <em>name</em> a store must not have that choice outranked by an ambient
     * {@code CDISC_METADATA_STORE} (F2). Empty when nothing resolves or the store cannot be read.
     * Deliberately unmemoised — see the class javadoc.
     *
     * @param aExplicitStore
     *            the caller's explicitly named store file (a dialog field, a CLI flag);
     *            {@code null}/blank resolves the ambient configuration
     * @return the configured catalogue (possibly empty)
     */
    public static MetadataProductCatalogue configuredFrom(@Nullable String aExplicitStore)
    {
        Path store = StoreMetadataProviderFactory.resolveConfiguredFile(aExplicitStore);
        if (store == null)
        {
            return new MetadataProductCatalogue(Set.of());
        }
        try (MetadataStore opened = MetadataStore.open(store))
        {
            return new MetadataProductCatalogue(new LinkedHashSet<>(opened.productCatalogue()));
        }
        catch (StoreFormatException e)
        {
            // The catalogue stays empty (S13) but the CAUSE travels with it
            // (storeFormatProblem), so a surface that offers the catalogue — the REST /meta
            // endpoint, the data browser's product picker, the CLI's -mp resolution — can say
            // "re-seed" instead of "no products" (PLAN-define-ct-evaluation review round 1,
            // engine L4). The run itself aborts on the same store (T1-10 a).
            LOGGER.log(System.Logger.Level.WARNING,
                    "Metadata store {0} is format {1} and this build reads format {2}; it must be"
                            + " re-seeded. Only full-form --metadata-products keys will resolve.",
                    store, e.foundVersion(), e.knownVersion());
            return new MetadataProductCatalogue(Set.of(), e);
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Metadata store {0} unavailable for --metadata-products resolution ({1}); "
                            + "only full-form keys will resolve.",
                    store, String.valueOf(e));
            return new MetadataProductCatalogue(Set.of());
        }
    }


    /**
     * A catalogue over an explicit key set — the seam tests construct directly. ⚑ It used to take a
     * list of human-readable source names too; no message ever named them (the resolver's not-found
     * text deliberately does not say which source was consulted), so the accessor and the plumbing
     * went with PLAN-retire-dead-multi-match-lookup U6 (D-B2 / D-B4, 2026-09-25).
     *
     * @param aKeys
     *            {@code standards/...} keys
     * @return the catalogue
     */
    public static MetadataProductCatalogue of(Set<String> aKeys)
    {
        return new MetadataProductCatalogue(aKeys);
    }


    /** The {@code standards/...} product keys, in catalogue order. */
    public Set<String> keys()
    {
        return keys;
    }


    /**
     * The typed refusal when the configured store is of another on-disk format — the catalogue is
     * then empty for a reason a surface must name ("re-seed"), not "no products". {@code null} when
     * the store opened, or none is configured.
     */
    public @Nullable StoreFormatException storeFormatProblem()
    {
        return storeFormatProblem == null ? null : storeFormatProblem.copy();
    }

}
