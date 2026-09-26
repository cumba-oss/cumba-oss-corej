package net.cumba.corej.core.metadata.store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A metadata store whose on-disk format version is not the one this build reads. Typed, so every
 * surface can tell "old format, re-seed" from "missing or corrupt" without parsing a message
 * (PLAN-define-ct-evaluation T1-10 a).
 *
 * <p>
 * The message is deliberately NEUTRAL (T1-10 m1): it names the file, the version found and the
 * version known, and says the store must be re-seeded — never how, because the core cannot know the
 * surface. A menu path shown to a remote library's user, or a CLI flag shown in the GUI, is a wrong
 * instruction; each surface appends its own remedy where it turns this into user text.
 * </p>
 */
public final class StoreFormatException extends IOException
{

    private static final long serialVersionUID = 1L;

    private final String store;

    private final int foundVersion;

    private final int knownVersion;

    /**
     * @param aStore
     *            the store file
     * @param aFoundVersion
     *            the {@code formatVersion} its manifest declares
     * @param aKnownVersion
     *            the version this build reads
     */
    public StoreFormatException(Path aStore, int aFoundVersion, int aKnownVersion)
    {
        super(describe(aStore.toString(), aFoundVersion, aKnownVersion));
        store = aStore.toString();
        foundVersion = aFoundVersion;
        knownVersion = aKnownVersion;
    }


    /**
     * The neutral message, rebuilt from the fields — never {@code null}, unlike
     * {@link #getMessage()}'s contract, so a surface can wrap it without a null check.
     */
    public String describe()
    {
        return describe(store, foundVersion, knownVersion);
    }


    private static String describe(String aStore, int aFoundVersion, int aKnownVersion)
    {
        if (aFoundVersion > aKnownVersion)
        {
            return "metadata store " + aStore + " is format " + aFoundVersion + "; this build"
                    + " reads format " + aKnownVersion + "; it was written by a newer build -"
                    + " upgrade this tool (or re-seed the store with this one to downgrade it)";
        }
        return "metadata store " + aStore + " is format " + aFoundVersion + "; this build reads"
                + " format " + aKnownVersion + "; the store must be re-seeded";
    }


    /**
     * A detached copy — same store and versions, its own stack — for a holder that hands the
     * problem out without exposing the instance it keeps ({@code MetadataProductCatalogue}).
     */
    public StoreFormatException copy()
    {
        return new StoreFormatException(Path.of(store), foundVersion, knownVersion);
    }


    /** {@code true} when the store was written by a build NEWER than this one. */
    public boolean writtenByNewerBuild()
    {
        return foundVersion > knownVersion;
    }


    /** The format version the store's manifest declares. */
    public int foundVersion()
    {
        return foundVersion;
    }


    /** The format version this build reads. */
    public int knownVersion()
    {
        return knownVersion;
    }
}
