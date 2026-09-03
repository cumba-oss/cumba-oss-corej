package net.cumba.corej.core.metadata.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Installs one dictionary into the store: acquire &rarr; convert &rarr; <b>validate</b> &rarr;
 * write, plus its licence documents, its entry in the selection manifest, and its provenance
 * section in {@value #SOURCES_FILE}.
 *
 * <p>
 * <b>Validation is not optional and happens before the write.</b> A converted document that fails
 * either half of {@link HouseFormatValidator} is never written, because both failure modes are
 * silent at run time: a case-contract breach makes rules fire on conformant data, and an
 * incompleteness makes them answer nothing while still reporting the dictionary as available. An
 * installer that wrote first and checked later would be handing the operator exactly the artefact
 * this design exists to prevent.
 * </p>
 *
 * <p>
 * <b>Manifest discipline.</b> The installer writes a {@code selected-versions.json} entry only when
 * the type has none, unless {@code setDefault} is given. Adding a newer MedDRA therefore never
 * silently re-points a study that already validates — changing the bound version stays a deliberate
 * act, which is the same property the store's no-fallback rule protects.
 * </p>
 */
public final class DictionaryInstaller
{

    /**
     * The store-root provenance record: per installed dictionary type, where the raw distribution
     * came from, its release version, the retrieval date, and the SHA-256 of each raw artefact.
     * Updated per install — one section per type, so installing one dictionary never rewrites
     * another's entry.
     */
    public static final String SOURCES_FILE = "SOURCES.md";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Above this many bytes the document is written gzipped rather than plain. */
    private static final long GZIP_THRESHOLD_BYTES = 1_000_000L;

    /**
     * The shape a version must have to become a store path segment and manifest key. Conservative
     * by design: the six vendor version vocabularies ({@code 27.0}, {@code SEP_2020},
     * {@code 2026.07.06}, {@code 4Aug2026}, {@code 2.80}, {@code 20240901}) all fit, while
     * everything a converter can be fooled into returning by a malformed vendor file — a whole
     * prose line, a BOM, spaces, parentheses, path separators, {@code ..} — is rejected with a
     * message instead of becoming an untypeable (or traversing) directory name.
     */
    private static final java.util.regex.Pattern VERSION_TOKEN = java.util.regex.Pattern
            .compile("[A-Za-z0-9._-]{1,64}");

    private final Path storeDir;

    private final boolean dryRun;

    private final boolean skipInstalled;

    private final InstallReport report = new InstallReport();

    public DictionaryInstaller(Path aStoreDir)
    {
        this(aStoreDir, false);
    }


    /**
     * @param aDryRun
     *            when {@code true}, acquire, convert and <b>validate</b> exactly as a real install
     *            would — so a dry run's report is trustworthy — but write nothing: no document, no
     *            licence, no manifest entry, not even the store directory itself
     */
    public DictionaryInstaller(Path aStoreDir, boolean aDryRun)
    {
        this(aStoreDir, aDryRun, false);
    }


    /**
     * @param aDryRun
     *            when {@code true}, acquire, convert and <b>validate</b> exactly as a real install
     *            would — so a dry run's report is trustworthy — but write nothing
     * @param aSkipInstalled
     *            when {@code true}, a type whose resolved version is already present in the store
     *            is reported as skipped and left byte-for-byte alone — no conversion, no
     *            re-validation, no manifest touch. This is what makes a container entrypoint's
     *            boot-time auto-convert idempotent (D11): converting the same mounted distribution
     *            on every start would rewrite identical content, churn file mtimes (invalidating
     *            the D8 cache for nothing) and delay start-up. The check runs after
     *            {@link DictionarySource#resolve()}, because the version is read from the resolved
     *            artefact — a download source is therefore still fetched before the skip decides.
     */
    public DictionaryInstaller(Path aStoreDir, boolean aDryRun, boolean aSkipInstalled)
    {
        this.storeDir = aStoreDir;
        this.dryRun = aDryRun;
        this.skipInstalled = aSkipInstalled;
    }


    public InstallReport getReport()
    {
        return report;
    }


    /**
     * Installs one dictionary.
     *
     * @param aSource
     *            where the raw distribution comes from; closed by this method
     * @param aConverter
     *            the vendor-format reader for this dictionary type
     * @param aLicence
     *            the licence or terms-of-use text to write beside the data, or {@code null} when
     *            the authority publishes none (recorded as such in the report)
     * @param aSetDefault
     *            overwrite an existing manifest entry for this type
     * @return the installed version, or {@code null} when nothing was written
     */
    public @Nullable String install(DictionarySource aSource, DictionaryConverter aConverter,
            @Nullable String aLicence, boolean aSetDefault)
        throws IOException
    {
        return install(aSource, aConverter, aLicence, Map.of(), aSetDefault);
    }


    /**
     * Installs one dictionary, with additional licence documents beside the main notice.
     *
     * @param aSource
     *            where the raw distribution comes from; closed by this method
     * @param aConverter
     *            the vendor-format reader for this dictionary type
     * @param aLicence
     *            the licence or terms-of-use text to write beside the data, or {@code null} when
     *            the authority publishes none (recorded as such in the report)
     * @param aExtraLicenceFiles
     *            further documents to write into the {@code LICENSES} directory, keyed by plain
     *            file name — e.g. LOINC's verbatim required notice, which must ship as its own file
     *            rather than folded into the main notice
     * @param aSetDefault
     *            overwrite an existing manifest entry for this type
     * @return the installed version, or {@code null} when nothing was written
     */
    public @Nullable String install(DictionarySource aSource, DictionaryConverter aConverter,
            @Nullable String aLicence, Map<String, String> aExtraLicenceFiles, boolean aSetDefault)
        throws IOException
    {
        String type = aConverter.type().toLowerCase(Locale.ROOT);
        try (DictionarySource source = aSource)
        {
            Path raw = source.resolve();
            // Phase 8: the raw files are decoded with undecodable bytes REPLACED rather than
            // aborting the conversion — but never silently. Tally from here so version reading
            // and conversion are both covered. The dropped-term tally follows the same shape.
            RawDictionaryFiles.resetReplacementCount();
            RawDictionaryFiles.resetDroppedTermCount();
            String version = aConverter.versionOf(raw);
            if (version.isBlank())
            {
                report.skipped(type + ": the distribution declares no version, so it cannot be "
                        + "installed into a versioned store");
                return null;
            }
            if (!isValidVersionToken(version))
            {
                report.skipped(type + ": the distribution declares the version '" + version
                        + "', which is not a usable store version token (allowed: "
                        + VERSION_TOKEN.pattern() + ", and neither '.' nor '..'); it would become "
                        + "a directory name and manifest key that cannot be typed back or, worse, "
                        + "escapes the store — NOT installed. Fix the raw distribution's version "
                        + "declaration.");
                return null;
            }
            if (skipInstalled && alreadyInstalled(type, version))
            {
                report.skipped(type + " " + version
                        + ": already installed — left as-is (--skip-installed)");
                return null;
            }
            ObjectNode doc = aConverter.convert(raw);
            long replaced = RawDictionaryFiles.replacementCount();
            if (replaced > 0)
            {
                // A replaced byte costs one term instead of the whole dictionary — but a
                // substituted character yields a term that passes the case contract and matches
                // no real data, so the operator must be told it happened.
                report.warning(type + " " + version + ": " + replaced + " undecodable byte "
                        + "sequence(s) in the raw distribution were replaced with U+FFFD during "
                        + "conversion; every affected term differs from the vendor's spelling and "
                        + "will match no real data. Check the raw files' encoding and re-install.");
            }
            long dropped = RawDictionaryFiles.droppedTermCount();
            if (dropped > 0)
            {
                // Refusing a case-colliding spelling is correct (rewriting it would assert a form
                // the vendor never wrote) — but a concept whose only spelling was refused is now
                // code-only, so data reporting it will be flagged although the vendor publishes
                // it. The operator must be able to see that it happened.
                report.warning(type + " " + version + ": " + dropped + " term spelling(s) were "
                        + "not published because their case collides with an already-published "
                        + "term; each refused spelling is absent from its name level and pairs "
                        + "registry, so submitted data carrying it will be flagged as invalid.");
            }
            doc.put("type", type);
            doc.put("version", version);
            doc.put("source", source.provenance());
            // UTC, not the machine's zone: a provenance record must mean the same thing
            // wherever it is read back.
            doc.put("retrieved", LocalDate.now(ZoneOffset.UTC).toString());

            List<String> violations = HouseFormatValidator.validate(type, doc);
            if (!violations.isEmpty())
            {
                report.skipped(type + " " + version + ": NOT written — the converted dictionary "
                        + "failed validation (" + violations.size() + " violation(s)); first: "
                        + violations.get(0));
                violations.forEach(report::warning);
                return null;
            }
            // A5b — the cardinality floors are ADVISORY: a heuristic bound over hand-calibrated
            // release sizes must warn loudly, not hard-refuse a lawful release it was not
            // calibrated on. The entry counts also land in SOURCES.md via recordSource.
            for (String suspicion : HouseFormatValidator.cardinalityViolations(type, doc))
            {
                report.warning(suspicion + " — installed anyway; verify the raw files before "
                        + "trusting a validation run against this dictionary");
            }
            if (!dryRun)
            {
                write(type, version, doc, aLicence, aExtraLicenceFiles);
                recordSelection(type, version, aSetDefault);
                recordSource(type, version, source, doc);
            }
            report.installed(type, version);
            return version;
        }
    }


    /**
     * Whether {@code aVersion} may become a store path segment and manifest key — see
     * {@link #VERSION_TOKEN}. Public so the <em>select</em> side ({@code DictionaryStore}) can
     * apply the same test to a requested version, which may come from a define.xml — i.e. from
     * study data — before resolving it against the store's directory tree.
     */
    public static boolean isValidVersionToken(String aVersion)
    {
        return VERSION_TOKEN.matcher(aVersion).matches() && !".".equals(aVersion)
                && !"..".equals(aVersion);
    }


    /**
     * Whether {@code <store>/<type>/<version>/} already holds a dictionary file — the presence test
     * the CLI's post-install verification uses, so "installed" means the same thing on both sides
     * of the skip.
     */
    private boolean alreadyInstalled(String aType, String aVersion)
    {
        Path dir = storeDir.resolve(aType).resolve(aVersion);
        return Files.isRegularFile(dir.resolve(aType + ".json"))
                || Files.isRegularFile(dir.resolve(aType + ".json.gz"));
    }


    /** Writes the document, and the licence beside it, under {@code <store>/<type>/<version>/}. */
    private void write(String aType, String aVersion, ObjectNode aDoc, @Nullable String aLicence,
            Map<String, String> aExtraLicenceFiles)
        throws IOException
    {
        Path dir = Files.createDirectories(storeDir.resolve(aType).resolve(aVersion));
        byte[] json = MAPPER.writeValueAsBytes(aDoc);
        if (json.length > GZIP_THRESHOLD_BYTES)
        {
            try (OutputStream out = new GZIPOutputStream(
                    Files.newOutputStream(dir.resolve(aType + ".json.gz"))))
            {
                out.write(json);
            }
            Files.deleteIfExists(dir.resolve(aType + ".json"));
        }
        else
        {
            Files.write(dir.resolve(aType + ".json"), json);
            Files.deleteIfExists(dir.resolve(aType + ".json.gz"));
        }
        Path licences = Files.createDirectories(dir.resolve("LICENSES"));
        if (aLicence != null && !aLicence.isBlank())
        {
            Files.writeString(licences.resolve(aType.toUpperCase(Locale.ROOT) + ".txt"), aLicence,
                    StandardCharsets.UTF_8);
        }
        else
        {
            report.warning(aType + ": the authority publishes no licence or terms-of-use document; "
                    + "none was written. Record the basis for your own use of this data.");
        }
        for (Map.Entry<String, String> extra : aExtraLicenceFiles.entrySet())
        {
            String fileName = extra.getKey();
            if (fileName.contains("/") || fileName.contains("\\"))
            {
                throw new IllegalArgumentException(
                        "an extra licence file must be a plain file name: " + fileName);
            }
            Files.writeString(licences.resolve(fileName), extra.getValue(), StandardCharsets.UTF_8);
        }
    }


    /**
     * Rewrites this type's section of {@value #SOURCES_FILE}, leaving every other type's section
     * byte-for-byte alone. The provenance and artefact fingerprints must be captured while the
     * source is still open, which is why this runs inside {@code install}'s try-with-resources.
     */
    private void recordSource(String aType, String aVersion, DictionarySource aSource,
            ObjectNode aDoc)
        throws IOException
    {
        Path file = storeDir.resolve(SOURCES_FILE);
        List<String> section = new ArrayList<>();
        section.add("## " + aType);
        section.add("");
        section.add("- Source: " + aSource.provenance());
        section.add("- Version: " + aVersion);
        // The written entry counts, so an operator can see at a glance whether an install is
        // release-sized (levels.PT: 25391) or suspiciously small (levels.PT: 200).
        section.add("- Entries: " + entryCountsOf(aDoc));
        // UTC, not the machine's zone: a provenance record must mean the same thing wherever it
        // is read back.
        section.add("- Retrieved: " + LocalDate.now(ZoneOffset.UTC) + " (UTC)");
        for (DictionarySource.Artefact artefact : aSource.artefacts())
        {
            section.add("- Artefact: " + artefact.name() + " — SHA-256 " + artefact.sha256() + " — "
                    + artefact.url());
        }
        section.add("");
        List<String> lines;
        if (Files.isRegularFile(file))
        {
            lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
        else
        {
            lines = new ArrayList<>(List.of("# Dictionary sources", "",
                    "Written by the coreJ dictionary installer: one section per installed",
                    "dictionary type, rewritten on that type's next install and otherwise",
                    "left untouched.", ""));
        }
        Files.write(file, withSectionReplaced(lines, "## " + aType, section),
                StandardCharsets.UTF_8);
    }


    /**
     * The document's entry counts, one clause per inner section, e.g.
     * {@code levels.PT: 25391; levels.PTCD: 25391; hierarchy: 1737}.
     */
    private static String entryCountsOf(ObjectNode aDoc)
    {
        List<String> parts = new ArrayList<>();
        for (String sectionName : List.of("levels", "pairs", "attributes"))
        {
            JsonNode section = aDoc.path(sectionName);
            if (section.isObject())
            {
                for (Map.Entry<String, JsonNode> inner : section.properties())
                {
                    parts.add(sectionName + "." + inner.getKey() + ": " + inner.getValue().size());
                }
            }
        }
        JsonNode hierarchy = aDoc.path("hierarchy");
        if (hierarchy.isObject())
        {
            parts.add("hierarchy: " + hierarchy.size());
        }
        return parts.isEmpty() ? "none" : String.join("; ", parts);
    }


    /**
     * The lines with the section opened by {@code aHeading} replaced by {@code aSection} — a
     * section runs to the next {@code "## "} heading — or with {@code aSection} appended when no
     * such section exists yet.
     */
    private static List<String> withSectionReplaced(List<String> aLines, String aHeading,
            List<String> aSection)
    {
        int start = aLines.indexOf(aHeading);
        if (start < 0)
        {
            List<String> out = new ArrayList<>(aLines);
            out.addAll(aSection);
            return out;
        }
        int end = start + 1;
        while (end < aLines.size() && !aLines.get(end).startsWith("## "))
        {
            end++;
        }
        List<String> out = new ArrayList<>(aLines.subList(0, start));
        out.addAll(aSection);
        out.addAll(aLines.subList(end, aLines.size()));
        return out;
    }


    /**
     * Adds this type to the selection manifest, leaving an existing entry alone unless
     * {@code aSetDefault}.
     */
    private void recordSelection(String aType, String aVersion, boolean aSetDefault)
        throws IOException
    {
        Path file = storeDir.resolve(DictionaryStore.MANIFEST);
        Map<String, String> selections = new LinkedHashMap<>();
        if (Files.isRegularFile(file))
        {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            if (root.isObject())
            {
                for (Map.Entry<String, JsonNode> e : root.properties())
                {
                    selections.put(e.getKey(), e.getValue().asText());
                }
            }
        }
        String existing = selections.get(aType);
        if (existing != null && !aSetDefault)
        {
            report.warning(aType + ": kept the existing selection " + existing + "; " + aVersion
                    + " is installed but not bound. Pass --set-default to change it.");
            return;
        }
        selections.put(aType, aVersion);
        Files.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(selections));
    }

}
