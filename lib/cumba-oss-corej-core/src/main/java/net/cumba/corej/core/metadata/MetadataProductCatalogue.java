package net.cumba.corej.core.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.CustomLog;
import net.cumba.corej.core.metadata.store.MetadataStore;
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

    private final List<String> sources;

    private MetadataProductCatalogue(Set<String> aKeys, List<String> aSources)
    {
        keys = Collections.unmodifiableSet(new LinkedHashSet<>(aKeys));
        sources = List.copyOf(aSources);
    }


    /**
     * The catalogue for the current configuration: the unified metadata store named by
     * {@code CDISC_METADATA_STORE} / {@code cdisc.metadata.store}, or an empty catalogue when none
     * is configured or the store cannot be read. Deliberately unmemoised — see the class javadoc.
     *
     * @param aIgnoredPickleDir
     *            pre-P4 pickle-cache override; ignored since cache P4 cut the pickle source.
     *            Retained so the P4b cross-repo lane can migrate callers deliberately rather than
     *            under a compile error; passing a value has no effect.
     * @param aIgnoredApiCacheDir
     *            pre-P4 API-cache override; ignored likewise.
     * @return the configured catalogue (possibly empty)
     */
    @Deprecated(since = "cache P4", forRemoval = true)
    public static MetadataProductCatalogue configured(@Nullable String aIgnoredPickleDir,
            @Nullable String aIgnoredApiCacheDir)
    {
        return configured();
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
            return new MetadataProductCatalogue(Set.of(), List.of());
        }
        try (MetadataStore opened = MetadataStore.open(store))
        {
            return new MetadataProductCatalogue(new LinkedHashSet<>(opened.productCatalogue()),
                    List.of("metadata store " + store));
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Metadata store {0} unavailable for --metadata-products resolution ({1}); "
                            + "only full-form keys will resolve.",
                    store, String.valueOf(e));
            return new MetadataProductCatalogue(Set.of(), List.of());
        }
    }


    /**
     * A catalogue over an explicit key set — the seam tests construct directly.
     *
     * @param aKeys
     *            {@code standards/...} keys
     * @param aSources
     *            human-readable source names, for messages
     * @return the catalogue
     */
    public static MetadataProductCatalogue of(Set<String> aKeys, List<String> aSources)
    {
        return new MetadataProductCatalogue(aKeys, aSources);
    }


    /** The {@code standards/...} product keys, in catalogue order. */
    public Set<String> keys()
    {
        return keys;
    }


    /** Human-readable source names, for failure messages ("resolved against …"). */
    public List<String> sources()
    {
        return sources;
    }
}
