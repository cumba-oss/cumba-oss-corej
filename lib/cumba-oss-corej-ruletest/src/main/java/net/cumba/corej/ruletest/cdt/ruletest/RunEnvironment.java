package net.cumba.corej.ruletest.cdt.ruletest;

import java.nio.file.Path;
import java.util.Objects;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link ScenarioRunner} run needs from its host and the scenario itself cannot carry: the
 * metadata store {@code #library-ref} scenarios resolve against, the external-dictionary bundle
 * {@code #dictionaries dummy} names, and the sidecar resolver.
 *
 * <p>
 * ⛔ The runner <b>never seeds</b> a store: the corpus's {@code MetadataStoreFixture} (pickle →
 * store) stays in the corpus test tree and hands the result in through {@link StoreAvailability};
 * an editor opens the store its deployment configured.
 * </p>
 *
 * @param store
 *            the store to resolve {@code #library-ref} against, or why there is none
 * @param dictionariesDir
 *            the directory {@code #dictionaries dummy} loads ({@code RuntimeDictionaryProvider}),
 *            or {@code null} when the host has no bundle — a scenario naming one then fails
 * @param sidecars
 *            the confined sidecar resolver, or {@code null} for a scenario that may name no sidecar
 */
public record RunEnvironment(StoreAvailability store, @Nullable Path dictionariesDir,
        @Nullable SidecarResolver sidecars)
{

    public RunEnvironment
    {
        Objects.requireNonNull(store, "store availability");
    }

    /**
     * The store a run can resolve {@code #library-ref} scenarios against.
     *
     * @param factory
     *            the opened store, or {@code null} when none is available
     * @param origin
     *            where it came from, for messages ({@code "the fixture store …"})
     * @param unavailableReason
     *            why there is none, for messages; empty when {@code factory} is set
     * @param fatal
     *            whether the unavailability must <b>fail</b> a {@code #library-ref} scenario rather
     *            than abort it (an old-format store: a stop, not a skip)
     */
    public record StoreAvailability(@Nullable StoreMetadataProviderFactory factory, String origin,
            String unavailableReason, boolean fatal)
    {

        public StoreAvailability
        {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(unavailableReason, "unavailable reason");
        }


        /** An available store. */
        public static StoreAvailability of(StoreMetadataProviderFactory aFactory, String aOrigin)
        {
            return new StoreAvailability(aFactory, aOrigin, "", false);
        }


        /** No store, with the reason; {@code fatal} when a scenario must fail rather than abort. */
        public static StoreAvailability unavailable(String aOrigin, String aReason, boolean aFatal)
        {
            return new StoreAvailability(null, aOrigin, aReason, aFatal);
        }
    }
}
