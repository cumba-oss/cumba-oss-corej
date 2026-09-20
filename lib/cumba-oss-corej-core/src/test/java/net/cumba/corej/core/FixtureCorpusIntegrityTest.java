package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Integrity gate for the curated fixture corpus (PLAN-engine-rules-decoupling §4 / Phase 2): the
 * fixture tree under {@code src/test/resources/fixtures/rules/} must stay in lockstep with its
 * generated {@code manifest.json} — no orphan fixture files, no manifest id without a fixture, and
 * every trimmed package entry backed by a real file whose rules are declared. The fixtures are
 * (re)built from the corpus by the rules repository's {@code scripts/build-core-fixtures.py}.
 *
 * <p>
 * ⛔⛔ <b>This gate does NOT see corpus drift, and nothing else does either.</b> It compares the
 * fixture tree against its own manifest, so a fixture that has gone stale against the live corpus
 * passes here forever. Byte-level sync used to be asserted on the corpus side by
 * {@code RulesFixtureSyncTest}, which the monorepo split RETIRED (see the rules repository's
 * {@code src/test/java/net/cumba/corej/core/RETIRED-CROSS-REPO-GUARDS.md}) — it read
 * {@code ../corej-core}, a sibling that exists only in the monorepo. ⚠ Measured 2026-09-12:
 * {@code CDISC-SEND-0324} was still pinning the pre-split rule, and the hoist wave had turned seven
 * assertions in {@code CdiscAd0640To0646IntegrationTest} into vacuous zeros. Until a two-repo check
 * exists (the meta repo is the only place both trees are checked out), a corpus change that touches
 * a fixture id must be followed by a manual rebuild.
 * </p>
 */
class FixtureCorpusIntegrityTest
{

    private static final Path FIXTURES = Path.of("src", "test", "resources", "fixtures", "rules");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode manifest() throws IOException
    {
        return MAPPER.readTree(Files.readString(FIXTURES.resolve("manifest.json")));
    }


    @Test
    void checksMatchManifestExactly() throws IOException
    {
        Set<String> declared = new TreeSet<>();
        manifest().get("b1_ids").forEach(n -> declared.add(n.asText()));

        Set<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.walk(FIXTURES.resolve("checks")))
        {
            files.filter(p -> p.toString().endsWith(".yaml")).forEach(p -> present.add(stem(p)));
        }
        assertEquals(declared, present,
                "fixtures/rules/checks must hold exactly the manifest's b1_ids — rebuild via"
                        + " the rules repository's scripts/build-core-fixtures.py");
    }


    @Test
    void packagesMatchManifest() throws IOException
    {
        JsonNode byTuple = manifest().get("b2_by_tuple");
        JsonNode packages = MAPPER
                .readTree(Files.readString(FIXTURES.resolve("packages").resolve("packages.json")))
                .get("packages");
        assertTrue(packages.size() > 0, "trimmed packages.json must list entries");

        Set<String> listedFiles = new HashSet<>();
        for (JsonNode entry : packages)
        {
            String file = entry.get("file").asText();
            listedFiles.add(file);
            Path pkgFile = FIXTURES.resolve("packages").resolve(file);
            assertTrue(Files.exists(pkgFile), "manifest entry without package file: " + file);

            String tuple = enc(entry.get("standard").asText()) + "-"
                    + enc(entry.get("version").asText());
            JsonNode allowed = byTuple.get(tuple);
            assertTrue(allowed != null, "package tuple not declared in manifest: " + tuple);

            JsonNode rules = MAPPER.readTree(Files.readString(pkgFile)).get("rules");
            assertEquals(entry.get("ruleCount").asInt(), rules.size(),
                    "ruleCount out of sync for " + file);
            if (allowed.size() > 0)
            {
                Set<String> ids = new HashSet<>();
                allowed.forEach(n -> ids.add(n.asText()));
                rules.properties().forEach(e -> assertTrue(ids.contains(e.getKey()),
                        "undeclared rule " + e.getKey() + " in trimmed package " + file));
            }
            // An empty allowed list marks a package-wide-smoke tuple carrying a deterministic
            // per-family sample (see build-core-fixtures.py) — any ids are legitimate there.
        }

        try (Stream<Path> files = Files.list(FIXTURES.resolve("packages")))
        {
            files.map(FixtureCorpusIntegrityTest::fileName)
                    .filter(n -> n.startsWith("rules-") && n.endsWith(".json"))
                    .forEach(n -> assertTrue(listedFiles.contains(n),
                            "orphan trimmed package not in packages.json: " + n));
        }
    }


    private static String stem(Path p)
    {
        String n = fileName(p);
        return n.substring(0, n.length() - ".yaml".length());
    }


    private static String fileName(Path p)
    {
        Path fn = p.getFileName();
        return fn == null ? "" : fn.toString();
    }


    private static String enc(String s)
    {
        return s.toLowerCase(java.util.Locale.ROOT).replace('.', '-').replace(' ', '-');
    }
}
