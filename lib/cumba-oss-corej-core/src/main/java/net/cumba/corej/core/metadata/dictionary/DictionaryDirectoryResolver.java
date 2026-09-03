package net.cumba.corej.core.metadata.dictionary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the directory holding installed dictionaries, from the four sources an operator may
 * configure. Mirrors {@code RuleRepository}'s contract for the Define-XML rule corpus, deliberately
 * — an operator who has learned one has learned the other.
 *
 * <p>
 * <b>Precedence:</b> explicit argument (the CLI's {@code --dictionaries-dir}) &gt;
 * {@value #ENV_DIR} &gt; {@value #SP_DIR} &gt; {@value #DEFAULT_DIR}.
 * </p>
 *
 * <p>
 * <b>A configured directory that does not exist is a hard error</b>, while the conventional default
 * is presence-gated. That asymmetry is the whole point: an operator who spells
 * {@code --dictionaries-dir /opt/dictionarys} has said what they want and must be told they missed,
 * rather than silently receiving a run in which all 98 dictionary rules SKIP and the report looks
 * clean. Nobody configured the conventional default, so its absence is merely "no dictionaries
 * installed" — the normal state of a fresh install.
 * </p>
 */
public final class DictionaryDirectoryResolver
{

    /** Environment variable naming the installed-dictionary directory. */
    public static final String ENV_DIR = "COREJ_DICTIONARIES_DIR";

    /** System property naming the installed-dictionary directory. */
    public static final String SP_DIR = "corej.dictionariesDir";

    /** Conventional directory, relative to the process CWD, used when nothing else is set. */
    public static final String DEFAULT_DIR = "./dictionaries";

    private DictionaryDirectoryResolver()
    {
    }


    /**
     * Resolves against the ambient environment and system properties.
     *
     * @param aExplicitDir
     *            the CLI option, or {@code null}
     */
    public static Optional<Path> resolve(@Nullable String aExplicitDir)
    {
        return resolve(aExplicitDir, System.getenv(ENV_DIR), System.getProperty(SP_DIR));
    }


    /**
     * Resolves from explicitly supplied sources — the testable form; {@link #resolve(String)}
     * supplies the ambient ones.
     *
     * @throws IllegalStateException
     *             when a configured (non-default) directory does not exist or is not a directory
     */
    public static Optional<Path> resolve(@Nullable String aExplicitDir, @Nullable String aEnvDir,
            @Nullable String aSysPropDir)
    {
        Optional<Path> configured = firstConfigured(aExplicitDir, aEnvDir, aSysPropDir);
        if (configured.isPresent())
        {
            return configured;
        }
        Path conventional = Path.of(DEFAULT_DIR);
        return Files.isDirectory(conventional) ? Optional.of(conventional) : Optional.empty();
    }


    /** The first configured source that is set, validated to exist. */
    private static Optional<Path> firstConfigured(@Nullable String aExplicitDir,
            @Nullable String aEnvDir, @Nullable String aSysPropDir)
    {
        if (aExplicitDir != null && !aExplicitDir.isBlank())
        {
            return Optional.of(requireDirectory(aExplicitDir, "--dictionaries-dir"));
        }
        if (aEnvDir != null && !aEnvDir.isBlank())
        {
            return Optional
                    .of(requireDirectory(aEnvDir, "the " + ENV_DIR + " environment variable"));
        }
        if (aSysPropDir != null && !aSysPropDir.isBlank())
        {
            return Optional.of(requireDirectory(aSysPropDir, "-D" + SP_DIR));
        }
        return Optional.empty();
    }


    private static Path requireDirectory(String aValue, String aSource)
    {
        Path dir = Path.of(aValue);
        if (!Files.isDirectory(dir))
        {
            throw new IllegalStateException(aSource + " names '" + aValue
                    + "', which is not an existing directory. Install dictionaries there, or "
                    + "correct the path — a typo here would otherwise SKIP every dictionary rule "
                    + "and leave the report looking clean.");
        }
        return dir;
    }

}
