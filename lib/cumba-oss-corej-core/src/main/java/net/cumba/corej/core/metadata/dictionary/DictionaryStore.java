package net.cumba.corej.core.metadata.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.ValueMapDictionary;
import org.jspecify.annotations.Nullable;

/**
 * The on-disk dictionary store: several releases of a dictionary installed side by side, of which a
 * single validation binds exactly one per type.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 * &lt;dir&gt;/selected-versions.json      {"meddra": "27.0", "unii": "4Aug2026"}
 * &lt;dir&gt;/meddra/27.0/meddra.json
 * &lt;dir&gt;/meddra/26.1/meddra.json
 * &lt;dir&gt;/unii/4Aug2026/unii.json.gz
 * </pre>
 *
 * <p>
 * A <b>flat</b> directory of <i>type</i>{@code .json} files — the shipped dummy fixture, and any
 * hand-assembled unversioned set — is still loaded as-is, so nothing that works today stops
 * working; a caller-<em>requested</em> version still binds even there (a flat file whose declared
 * version differs is dropped, never silently substituted). The two layouts are told apart by shape,
 * never by a version guess; a store mixing both shapes is treated as versioned, with the stray flat
 * files warned about and ignored.
 * </p>
 *
 * <h2>⛔ Nothing is ever inferred</h2>
 *
 * <p>
 * A dictionary is used <b>only</b> when something names its version: a caller-supplied selection
 * (the CLI option, or a define.xml {@code ExternalCodeList/@Version}), else the manifest the
 * installer wrote. When nothing names one, that dictionary is <b>not loaded</b> and its rules SKIP
 * — <em>however many versions are installed</em>. One behaves exactly like ten.
 * </p>
 *
 * <p>
 * <b>Why no "use the only one installed" fallback.</b> It would make findings depend on incidental
 * disk state: installing a second MedDRA release would silently change the verdict for a study that
 * validated yesterday, with nothing in the report to explain it. And it is unsound for the same
 * reason a wrong version is — MedDRA term validity genuinely changes between releases, so
 * validating a study against a release it was not coded in produces false violations (terms retired
 * since coding) <em>and</em> false passes (terms that did not yet exist). Since the engine
 * deliberately performs no version <em>check</em>, nothing else would ever flag that. Skipping says
 * "not answerable"; a fallback would quietly answer wrongly.
 * </p>
 *
 * <p>
 * A consequence worth having: because no version is ever auto-picked, version strings are only ever
 * compared for <b>equality</b> against an installed directory name. The six vendor vocabularies
 * ({@code 27.0}, {@code SEP_2020}, {@code 2026.07.06}, {@code 4Aug2026}, {@code 2.80},
 * {@code MAIN/SNOMEDCT-US/2024-09-01}) are mutually incomparable, and this design never needs to
 * order, parse or normalise one.
 * </p>
 */
public final class DictionaryStore
{

    /** The installer-written selection manifest, at the store root. */
    public static final String MANIFEST = "selected-versions.json";

    private static final System.Logger LOGGER = System.getLogger(DictionaryStore.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DictionaryStore()
    {
    }


    /**
     * Loads the store at {@code dir}, binding one version per type.
     *
     * @param aDir
     *            the store root; a null or absent directory yields an empty provider
     * @param aRequested
     *            versions named by the caller (CLI option, then define.xml), keyed by lower-cased
     *            dictionary type — outranks the manifest
     */
    public static RuntimeDictionaryProvider load(@Nullable Path aDir,
            Map<String, String> aRequested)
        throws IOException
    {
        if (aDir == null || !Files.isDirectory(aDir))
        {
            return new RuntimeDictionaryProvider(Map.of());
        }
        List<Path> typeDirs = subdirectories(aDir);
        List<Path> flatFiles = rootDictionaryFiles(aDir);
        if (typeDirs.isEmpty())
        {
            // Flat, unversioned set (the shipped dummy fixture). Nothing to select between —
            // but a version REQUEST still binds (D6/Q12): see the filter.
            return filterFlatByRequestedVersions(RuntimeDictionaryProvider.loadDirectory(aDir),
                    aRequested);
        }
        if (!flatFiles.isEmpty())
        {
            // Both shapes at once: a versioned tree plus stray flat files at its root (a leftover
            // hand-copied meddra.json, say). Silently taking the flat branch would bypass the
            // whole versioned store — every selection ignored, the stray file answering with no
            // version named — and nothing in the report could ever explain it. Prefer the
            // versioned tree and say, loudly, which files were ignored.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dictionary store {0} mixes the versioned layout with flat dictionary "
                            + "file(s) at its root: {1}. The versioned tree is used and the flat "
                            + "file(s) are IGNORED — remove them, or install their content with "
                            + "the installer.",
                    aDir, flatFiles);
        }
        Map<String, String> manifest = readManifest(aDir);
        Map<String, ValueMapDictionary> loaded = new LinkedHashMap<>();
        Map<String, RuntimeDictionaryProvider.Unavailability> unavailable = new LinkedHashMap<>();
        for (Path typeDir : typeDirs)
        {
            bind(typeDir, aRequested, manifest, loaded, unavailable);
        }
        return new RuntimeDictionaryProvider(loaded, unavailable);
    }


    /**
     * Q12 through the flat carve-out: a flat store has no versions to select between, but a
     * <em>requested</em> version (CLI option or define.xml) is still a statement about how the
     * study was coded — so a flat dictionary whose declared version differs from the request (or
     * that declares none) is dropped exactly as the versioned path would drop an uninstalled
     * version, and the mismatch is recorded. Answering from whatever the flat file happens to hold
     * would be the silent substitution the store's "⛔ Nothing is ever inferred" rule forbids. Types
     * nobody requested keep the historical flat behaviour unchanged.
     */
    private static RuntimeDictionaryProvider filterFlatByRequestedVersions(
            RuntimeDictionaryProvider aFlat, Map<String, String> aRequested)
    {
        if (aRequested.isEmpty())
        {
            return aFlat;
        }
        Map<String, ValueMapDictionary> kept = new LinkedHashMap<>();
        Map<String, RuntimeDictionaryProvider.Unavailability> unavailable = new LinkedHashMap<>(
                aFlat.unavailabilities());
        for (String type : aFlat.loadedTypes())
        {
            String requested = aRequested.get(type);
            String declared = aFlat.versionOf(type);
            if (requested == null || requested.equals(declared))
            {
                ValueMapDictionary dict = aFlat.dictionaryOf(type);
                if (dict != null)
                {
                    kept.put(type, dict);
                }
                continue;
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dictionary {0}: version {1} was requested, but the flat (unversioned) store "
                            + "holds {2} — it was NOT loaded and its rules will SKIP. Install the "
                            + "requested release into a versioned store, or drop the request.",
                    type, requested, declared != null ? declared : "a file declaring no version");
            unavailable.put(type, new RuntimeDictionaryProvider.Unavailability(
                    RuntimeDictionaryProvider.UnavailabilityReason.VERSION_NOT_INSTALLED,
                    "version " + requested + " (from requested) is not installed — the flat "
                            + "(unversioned) store holds "
                            + (declared != null ? declared : "a file declaring no version")
                            + "; install the requested release or select the present one"));
        }
        return new RuntimeDictionaryProvider(kept, unavailable);
    }


    /**
     * Binds one type's chosen version into {@code aLoaded}, or logs why it could not — and records
     * that diagnosis in {@code aUnavailable} (D13 item 2), so the per-rule SKIP and the run-level
     * {@code Dictionary_Basis} line can tell the operator what to do instead of the catch-all "no
     * external dictionary loaded".
     */
    private static void bind(Path aTypeDir, Map<String, String> aRequested,
            Map<String, String> aManifest, Map<String, ValueMapDictionary> aLoaded,
            Map<String, RuntimeDictionaryProvider.Unavailability> aUnavailable)
        throws IOException
    {
        Path nameElement = aTypeDir.getFileName();
        if (nameElement == null)
        {
            return;
        }
        String type = nameElement.toString().toLowerCase(Locale.ROOT);
        String requested = aRequested.get(type);
        String source = "requested";
        if (requested == null)
        {
            requested = aManifest.get(type);
            source = "the " + MANIFEST + " manifest";
        }
        List<String> installed = installedVersions(aTypeDir);
        String installedList = String.join(", ", installed);
        if (requested == null)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dictionary {0}: no version selected, so it was NOT loaded and its rules will "
                            + "SKIP. Installed: {1}. Name one with {2}, add it to {3}, or "
                            + "supply a define.xml that declares it.",
                    type, installed, versionOptionName(type), MANIFEST);
            aUnavailable.put(type, new RuntimeDictionaryProvider.Unavailability(
                    RuntimeDictionaryProvider.UnavailabilityReason.NO_VERSION_SELECTED,
                    "is installed but no version is selected (installed: " + installedList
                            + ") — select one with " + versionOptionName(type) + ", add it to "
                            + MANIFEST + ", or supply a define.xml that declares it"));
            return;
        }
        if (!DictionaryInstaller.isValidVersionToken(requested))
        {
            // The requested version can come from a define.xml — i.e. from STUDY DATA — so it is
            // validated with the installer's own write-side test before it may become a path
            // segment: '..', separators, spaces and the like must never reach resolve(). The
            // installer refuses to create such a directory, so nothing valid is ever refused
            // here; a hand-crafted request degrades to the same loud SKIP as any other
            // not-installed version.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dictionary {0}: the selected version ''{1}'' (from {2}) is not a usable store "
                            + "version token, so it was NOT resolved against the store and the "
                            + "rules will SKIP. Installed: {3}.",
                    type, requested, source, installed);
            aUnavailable.put(type,
                    new RuntimeDictionaryProvider.Unavailability(
                            RuntimeDictionaryProvider.UnavailabilityReason.VERSION_NOT_INSTALLED,
                            "version '" + requested + "' (from " + source
                                    + ") is not a usable store version token (installed: "
                                    + installedList + ") — select an installed version"));
            return;
        }
        Path versionDir = aTypeDir.resolve(requested);
        if (!Files.isDirectory(versionDir))
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dictionary {0}: version {1} (from {2}) is not installed, so it was NOT loaded "
                            + "and its rules will SKIP. Installed: {3}.",
                    type, requested, source, installed);
            aUnavailable.put(type,
                    new RuntimeDictionaryProvider.Unavailability(
                            RuntimeDictionaryProvider.UnavailabilityReason.VERSION_NOT_INSTALLED,
                            "version " + requested + " (from " + source
                                    + ") is not installed (installed: " + installedList
                                    + ") — install it or select an installed version"));
            return;
        }
        RuntimeDictionaryProvider one;
        try
        {
            one = RuntimeDictionaryProvider.loadDirectory(versionDir);
        }
        catch (IOException e)
        {
            // One unreadable version directory degrades ONLY its own type: the loop over the
            // other types continues, and every dictionary that already bound stays bound.
            // Propagating would collapse the whole provider to null in the caller — every type
            // reporting "is not installed" while its store sits populated.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dictionary {0}: version {1} could not be read ({2}), so it was NOT loaded "
                            + "and its rules will SKIP ({3}).",
                    type, requested, e.getMessage(), versionDir);
            aUnavailable.put(type,
                    new RuntimeDictionaryProvider.Unavailability(
                            RuntimeDictionaryProvider.UnavailabilityReason.NO_USABLE_CONTENT,
                            "is installed but version " + requested + " could not be read ("
                                    + e.getMessage() + ") — reinstall it"));
            return;
        }
        ValueMapDictionary dict = one.dictionaryOf(type);
        if (dict == null)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dictionary {0}: version {1} is installed but carries no usable {0} file, so "
                            + "it was NOT loaded and its rules will SKIP ({2}).",
                    type, requested, versionDir);
            // Prefer the loader's own per-file diagnosis when it recorded one (an unreadable or
            // content-guarded file); otherwise cover the remaining case — a version directory
            // with no <type>.json at all. Either way the install is unusable.
            RuntimeDictionaryProvider.Unavailability recorded = one.unavailabilityOf(type);
            aUnavailable.put(type, recorded != null ? recorded
                    : new RuntimeDictionaryProvider.Unavailability(
                            RuntimeDictionaryProvider.UnavailabilityReason.NO_USABLE_CONTENT,
                            "is installed but version " + requested
                                    + " carries no usable terms (empty or malformed) — reinstall "
                                    + "it"));
            return;
        }
        aLoaded.put(type, dict);
    }


    /**
     * The CLI option that selects a version for {@code type}, for the operator-facing messages.
     * SNOMED's differs because {@code --snomed-version} is a Python-CLI compat option (an
     * API-lookup version string) that the Java CLI accepts-and-ignores; its selection option is
     * {@code --snomed-version-select}.
     */
    private static String versionOptionName(String aType)
    {
        return "snomed".equals(aType) ? "--snomed-version-select" : "--" + aType + "-version";
    }


    /**
     * The flat dictionary files ({@code *.json} / {@code *.json.gz}, manifest excluded) at the
     * store root. Together with {@link #subdirectories} this tells the two layouts apart by shape:
     * no subdirectories &rArr; flat; subdirectories &rArr; versioned, with any stray root files
     * warned about and ignored rather than silently taking over the whole store.
     */
    private static List<Path> rootDictionaryFiles(Path aDir) throws IOException
    {
        try (Stream<Path> entries = Files.list(aDir))
        {
            return entries.filter(DictionaryStore::isDictionaryFile).sorted().toList();
        }
    }


    private static boolean isDictionaryFile(Path aPath)
    {
        Path name = aPath.getFileName();
        if (name == null || !Files.isRegularFile(aPath))
        {
            return false;
        }
        String s = name.toString();
        return !MANIFEST.equals(s) && (s.endsWith(".json") || s.endsWith(".json.gz"));
    }


    private static List<Path> subdirectories(Path aDir) throws IOException
    {
        try (Stream<Path> entries = Files.list(aDir))
        {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }


    /** The version directory names installed for one type, for the operator-facing messages. */
    private static List<String> installedVersions(Path aTypeDir)
    {
        try
        {
            List<String> out = new ArrayList<>();
            for (Path p : subdirectories(aTypeDir))
            {
                Path name = p.getFileName();
                if (name != null)
                {
                    out.add(name.toString());
                }
            }
            return out;
        }
        catch (IOException _)
        {
            return List.of();
        }
    }


    /**
     * Reads {@value #MANIFEST}, or an empty map when it is absent or unreadable. A malformed
     * manifest is a warning, not a failure: it can only ever cause a SKIP, never a wrong answer.
     */
    private static Map<String, String> readManifest(Path aDir)
    {
        Path file = aDir.resolve(MANIFEST);
        if (!Files.isRegularFile(file))
        {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(file))
        {
            JsonNode root = MAPPER.readTree(in);
            if (!root.isObject())
            {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : root.properties())
            {
                if (e.getValue().isTextual() && !e.getValue().asText().isBlank())
                {
                    out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asText());
                }
            }
            return out;
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not read {0}: {1} — no version will be "
                            + "selected from it and the affected rules will SKIP",
                    file, e.getMessage());
            return Map.of();
        }
    }

}
