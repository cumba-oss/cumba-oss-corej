package net.cumba.cdisc.core.metadata.pickle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Supplies a local directory containing the Python engine's {@code *.pkl} metadata cache.
 *
 * <p>
 * Implementations may <em>materialise</em> the directory on {@link #resolve()} — downloading and
 * extracting it, for instance — so {@code resolve()} is the point at which network or disk work
 * happens, not construction. {@link #close()} releases anything the source created (a temporary
 * work directory); sources that merely point at an existing directory must leave it untouched.
 * </p>
 */
public interface PickleSource extends AutoCloseable
{

    /**
     * Materialises the pickle directory.
     *
     * @return the directory containing the {@code *.pkl} files.
     * @throws IOException
     *             when the source cannot be materialised (missing directory, download or extraction
     *             failure).
     */
    Path resolve() throws IOException;


    /**
     * Where the pickles came from, in a form worth recording in a {@link SeedReport}.
     *
     * <p>
     * A seeded cache is otherwise indistinguishable from any other — the entries carry no note of
     * their origin — so without this a run cannot afterwards say which upstream revision it
     * reproduced. Implementations that can identify their source exactly (a repository, a ref, a
     * commit id) should say so; the default is empty, which is the right answer for a source that
     * is just a directory a user pointed at.
     * </p>
     *
     * @return a human-readable source identifier, or empty when the source has none or has not been
     *         {@linkplain #resolve() resolved} yet.
     */
    default Optional<String> provenance()
    {
        return Optional.empty();
    }


    /**
     * Releases resources created by {@link #resolve()}. Implementations that wrap a pre-existing
     * directory must not delete it. Overridden to narrow the checked exception from
     * {@link AutoCloseable}.
     *
     * @throws IOException
     *             when cleanup fails.
     */
    @Override
    void close() throws IOException;
}
