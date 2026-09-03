package net.cumba.corej.core.metadata.pickle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link PickleSource} over a directory that already exists on disk — a checked-out
 * {@code cdisc-rules-engine/resources/cache}, or any copy of it.
 *
 * <p>
 * Nothing is downloaded and nothing is deleted: {@link #close()} is a no-op, because the directory
 * belongs to the caller.
 * </p>
 */
public final class LocalPickleSource implements PickleSource
{

    private final Path dir;

    /**
     * Creates a source over an existing directory. The directory is validated on
     * {@link #resolve()}, not here, so construction never touches the filesystem.
     *
     * @param aDir
     *            the directory containing the {@code *.pkl} files.
     */
    public LocalPickleSource(Path aDir)
    {
        dir = Objects.requireNonNull(aDir, "dir");
    }


    @Override
    public Path resolve() throws IOException
    {
        if (!Files.exists(dir))
        {
            throw new NoSuchFileException(dir.toString(), null,
                    "pickle cache directory does not exist");
        }
        if (!Files.isDirectory(dir))
        {
            throw new NotDirectoryException(dir.toString());
        }
        return dir;
    }


    /** No-op — the directory is the caller's, so it is never removed. */
    @Override
    public void close()
    {
        // Intentionally empty: LocalPickleSource never owns the directory it points at.
    }


    @Override
    public String toString()
    {
        return "LocalPickleSource[" + dir + "]";
    }
}
