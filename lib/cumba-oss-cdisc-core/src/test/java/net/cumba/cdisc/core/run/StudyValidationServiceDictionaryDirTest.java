package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.metadata.dictionary.DictionaryDirectoryResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Batch B1/B10 — how {@link StudyValidationService} composes the dictionary-store resolution.
 *
 * <p>
 * The defect this pins: the CLI used to hand {@code --dictionaries-dir} over as the
 * {@code corej.dictionariesDir} <em>system property</em> while the service resolved with
 * {@code resolve(null)} — and the resolver ranks the system property BELOW
 * {@code COREJ_DICTIONARIES_DIR}, which every Docker image sets. The flag then silently lost to the
 * container default: install mode wrote where you asked, validate read somewhere else, and all 98
 * dictionary rules SKIPped as "not installed" with the named store mounted and populated. Neither
 * existing test could see it (one asserted the env var was <em>unset</em>, the other only that the
 * property got set), so the decisive case here runs with the environment variable actually SET —
 * which needs a forked JVM, because the ambient environment cannot be mutated in-process.
 * </p>
 */
class StudyValidationServiceDictionaryDirTest
{

    private static final String MEDDRA_EXPLICIT = "{\"type\":\"meddra\",\"version\":\"27.0\","
            + "\"levels\":{\"PT\":{\"HEADACHE\":\"Headache\"}}}";

    private static final String MEDDRA_ENV = "{\"type\":\"meddra\",\"version\":\"27.0\","
            + "\"levels\":{\"PT\":{\"MIGRAINE\":\"Migraine\"}}}";

    /**
     * Forked-JVM entry point: builds the provider through the service's own composition with the
     * explicit directory from {@code args[0]}, and prints whether the EXPLICIT store's term
     * answered. The parent process sets {@value DictionaryDirectoryResolver#ENV_DIR} to a different
     * store holding a different term under the same type/version.
     */
    public static void main(String[] args)
    {
        RuntimeDictionaryProvider p = StudyValidationService.buildDictionaryProvider(args[0],
                Map.of("meddra", "27.0"));
        System.out.println("headache=" + (p != null && p.isValidTerm("meddra", "PT", "Headache"))
                + " migraine=" + (p != null && p.isValidTerm("meddra", "PT", "Migraine")));
    }


    /** ⛔ B1: the params directory must outrank {@code COREJ_DICTIONARIES_DIR}. */
    @Test
    void theExplicitDirectoryOutranksTheEnvironmentVariable(@TempDir Path dir) throws Exception
    {
        Path explicitStore = store(dir.resolve("explicit"), MEDDRA_EXPLICIT);
        Path envStore = store(dir.resolve("env"), MEDDRA_ENV);

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"),
                StudyValidationServiceDictionaryDirTest.class.getName(), explicitStore.toString());
        pb.environment().put(DictionaryDirectoryResolver.ENV_DIR, envStore.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "forked JVM did not finish: " + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("headache=true"), "the EXPLICIT store must answer even with "
                + DictionaryDirectoryResolver.ENV_DIR + " set: " + output);
        assertTrue(output.contains("migraine=false"),
                "the env-named store must NOT answer when an explicit directory is given: "
                        + output);
    }


    /**
     * B10: the resolver's hard error (a configured-but-missing store) must surface as
     * {@link StudyValidationException} — the type every caller reports as an operational
     * {@code Error:} line — never escape as a raw {@link IllegalStateException} stack trace.
     */
    @Test
    void aMissingExplicitDirectoryIsAStudyValidationException()
    {
        StudyValidationException e = assertThrows(StudyValidationException.class,
                () -> StudyValidationService.buildDictionaryProvider("/no/such/dictionaries",
                        Map.of()));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("--dictionaries-dir"), e.getMessage());
        assertTrue(e.getMessage().contains("/no/such/dictionaries"), e.getMessage());
    }


    private static Path store(Path aRoot, String aMeddraJson) throws IOException
    {
        Path versionDir = Files.createDirectories(aRoot.resolve("meddra").resolve("27.0"));
        Files.writeString(versionDir.resolve("meddra.json"), aMeddraJson);
        return aRoot;
    }

}
