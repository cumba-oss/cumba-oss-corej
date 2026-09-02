package net.cumba.cdisc.core.metadata.dictionary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A raw distribution the operator already holds — a MedDRA {@code MedAscii} directory, a WHODrug B3
 * set, an unpacked SNOMED release, a LOINC release directory.
 *
 * <p>
 * This is the only source available for the licensed dictionaries, and it is what makes the
 * commercial ones installable at all without coreJ ever handling their data.
 * </p>
 */
public final class LocalDictionarySource implements DictionarySource
{

    private final Path dir;

    public LocalDictionarySource(Path aDir)
    {
        this.dir = aDir;
    }


    @Override
    public Path resolve() throws IOException
    {
        if (!Files.isDirectory(dir))
        {
            throw new IOException("Not a directory: " + dir);
        }
        return dir;
    }


    @Override
    public String provenance()
    {
        return dir.toAbsolutePath().toString();
    }


    /**
     * Releases nothing. The directory belongs to the operator; deleting or altering it would be a
     * side effect they never asked for.
     */
    @Override
    public void close()
    {
        // Deliberately empty — see javadoc.
    }

}
