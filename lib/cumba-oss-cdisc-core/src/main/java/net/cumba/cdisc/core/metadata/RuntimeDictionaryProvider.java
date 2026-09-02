package net.cumba.cdisc.core.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * T1 — runtime provider of external medical-dictionary lookups, backed by a bundle of
 * {@link ValueMapDictionary} value-maps keyed by dictionary type ({@code meddra}, {@code whodrug},
 * {@code loinc}, {@code unii}, {@code snomed}, {@code neoplasm}, …). It is the SPI the dictionary
 * {@code OperationExecutor} arms delegate to; the same interface later fronts real licensed
 * dictionary data.
 *
 * <p>
 * A dictionary type that is not loaded is <b>not available</b>: {@link #isAvailable(String)}
 * returns {@code false} and the corresponding rules SKIP rather than false-passing. Two paths
 * deliver that, and {@link #isAvailable(String)} is the test in both: a <b>declared</b>
 * ({@code $}-ref) dictionary operation — the form the whole shipped corpus uses — is caught by
 * {@code RuleRunner}'s eager dictionary arm ({@code Fix #268}), an <b>inlined</b> one by the
 * {@code dictionary_available(type)} precondition gate the converter injects for it. This mirrors
 * the CDISC-Library skip-gate. Note that a non-null provider proves nothing on its own: a bundle
 * holding only MedDRA leaves a UNII rule exactly as unanswerable as no bundle at all.
 * </p>
 */
public final class RuntimeDictionaryProvider
{

    /**
     * D13 item 2 — why a dictionary type is not loaded, recorded so the per-rule SKIP can tell the
     * operator what to <em>do</em> rather than repeating one catch-all "no external dictionary
     * loaded" for four different situations. Only operator-fixable states appear here: an
     * <em>operation</em> that names no dictionary type at all is an authoring defect and is
     * rejected at load ({@code RulePackageLoader.validateDictionaryOperationTypes}), never reported
     * as an installation problem.
     */
    public enum UnavailabilityReason
    {
        /** No installation of the type was found at all — the implicit default. */
        NOT_INSTALLED,
        /** Installed, but the D10 content guard dropped it: empty or malformed. */
        NO_USABLE_CONTENT,
        /** Installed (possibly several versions), but nothing selected a version. */
        NO_VERSION_SELECTED,
        /** A version was selected, but that version is not installed. */
        VERSION_NOT_INSTALLED
    }


    /**
     * One unavailable dictionary type's diagnosis: the {@link UnavailabilityReason} for callers
     * that branch, and the operator-actionable {@code detail} for callers that report. The detail
     * is a predicate continuing "external dictionary {@code <type>} …" (e.g. <em>"is installed but
     * carries no usable terms (empty or malformed) — reinstall it"</em>), composed by whoever
     * diagnosed the state — {@link #loadDirectory} for the content guard, {@code DictionaryStore}
     * for the version-selection states — because only they know the specifics (which versions are
     * installed, where the selection came from).
     */
    public record Unavailability(UnavailabilityReason reason, String detail)
    {
    }

    /**
     * The {@link Unavailability#detail} predicate for a type nobody recorded a richer diagnosis
     * for: it simply is not installed. Shared with callers that hold no provider at all (a run with
     * no dictionary directory), where every type is unavailable for exactly this reason.
     */
    public static String notInstalledDetail()
    {
        return "is not installed — install it into the dictionaries directory";
    }

    private static final System.Logger LOGGER = System
            .getLogger(RuntimeDictionaryProvider.class.getName());

    private final Map<String, ValueMapDictionary> byType;

    private final Map<String, Unavailability> unavailable;

    public RuntimeDictionaryProvider(Map<String, ValueMapDictionary> byType)
    {
        this(byType, Map.of());
    }


    /**
     * @param byType
     *            the loaded dictionaries, keyed by type (case-insensitive)
     * @param unavailable
     *            per-type diagnoses for types that could <em>not</em> be loaded, keyed by type
     *            (case-insensitive); a type absent from both maps reads as
     *            {@link UnavailabilityReason#NOT_INSTALLED}
     */
    public RuntimeDictionaryProvider(Map<String, ValueMapDictionary> byType,
            Map<String, Unavailability> unavailable)
    {
        Map<String, ValueMapDictionary> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ValueMapDictionary> e : byType.entrySet())
        {
            normalized.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        this.byType = Map.copyOf(normalized);
        Map<String, Unavailability> normalizedUnavailable = new LinkedHashMap<>();
        for (Map.Entry<String, Unavailability> e : unavailable.entrySet())
        {
            normalizedUnavailable.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        this.unavailable = Map.copyOf(normalizedUnavailable);
    }


    /**
     * Loads every {@code *.json} / {@code *.json.gz} house-format dictionary in {@code dir} into a
     * provider. A directory that does not exist yields an empty provider (all types unavailable
     * &rArr; dictionary rules SKIP).
     *
     * <p>
     * <b>A dictionary that carries no usable content is not loaded at all.</b> Every
     * {@link ValueMapDictionary} reader degrades a mis-shaped section to empty silently, so a
     * truncated download or a converter bug yields a file that parses cleanly and answers nothing.
     * Registering it would make {@link #isAvailable} report {@code true} and bypass the engine's
     * eager SKIP arm — the membership rules would fire on every row and the pair rules would pass
     * vacuously. Dropping it here routes those rules back onto the established "unavailable &rArr;
     * SKIP, never false-pass" path, whatever produced the file. Each drop is logged at WARNING,
     * because a present-but-unusable file is an operator problem they can only fix if they are
     * told.
     * </p>
     *
     * <p>
     * <b>An <em>unreadable</em> file degrades the same way — per file, never whole-directory.</b> A
     * file that fails to read at all (a truncated {@code .gz} that is not valid gzip, a JSON parse
     * error, a permissions problem) is caught here, logged at WARNING and recorded as
     * {@link UnavailabilityReason#NO_USABLE_CONTENT} for that key — and the loop continues, so one
     * corrupt {@code unii.json.gz} costs the run its UNII rules and nothing else. Letting the
     * {@link IOException} propagate would discard every sibling that had already loaded fine and
     * collapse the whole provider to "not installed" for every type — the exact catch-all message
     * this class's diagnoses exist to eliminate.
     * </p>
     */
    public static RuntimeDictionaryProvider loadDirectory(Path dir) throws IOException
    {
        Map<String, ValueMapDictionary> loaded = new LinkedHashMap<>();
        Map<String, Unavailability> unavailable = new LinkedHashMap<>();
        if (dir != null && Files.isDirectory(dir))
        {
            try (Stream<Path> files = Files.list(dir))
            {
                for (Path file : files.filter(RuntimeDictionaryProvider::isJsonFile).sorted()
                        .toList())
                {
                    // D8: the parse is cached per file, validated against size+mtime, so a
                    // per-request caller (the REST service) does not re-decompress and re-parse
                    // a multi-MB store on every run while an install is still picked up.
                    ValueMapDictionary dict;
                    try
                    {
                        dict = DictionaryFileCache.load(file);
                    }
                    catch (IOException e)
                    {
                        String key = fileStem(file);
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Dictionary file {0} could not be read and was NOT loaded — "
                                        + "reinstall it; its rules will SKIP ({1})",
                                file, e.getMessage());
                        unavailable.put(key.toLowerCase(Locale.ROOT),
                                new Unavailability(UnavailabilityReason.NO_USABLE_CONTENT,
                                        "is installed but its file could not be read ("
                                                + e.getMessage() + ") — reinstall it"));
                        continue;
                    }
                    String key = !dict.getType().isEmpty() ? dict.getType() : fileStem(file);
                    if (!dict.hasContent())
                    {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Dictionary {0} carries no usable terms and was NOT loaded — "
                                        + "reinstall it; its rules will SKIP ({1})",
                                key, file);
                        // D13 item 2 — remember WHY, so the per-rule SKIP can distinguish "you
                        // never installed X" from "your installed X is unusable".
                        unavailable.put(key.toLowerCase(Locale.ROOT),
                                new Unavailability(UnavailabilityReason.NO_USABLE_CONTENT,
                                        "is installed but carries no usable terms (empty or "
                                                + "malformed) — reinstall it"));
                        continue;
                    }
                    loaded.put(key.toLowerCase(Locale.ROOT), dict);
                }
            }
        }
        return new RuntimeDictionaryProvider(loaded, unavailable);
    }


    private static boolean isJsonFile(Path p)
    {
        Path name = p.getFileName();
        if (name == null)
        {
            return false;
        }
        String s = name.toString();
        return s.endsWith(".json") || s.endsWith(".json.gz");
    }


    /**
     * The file name without its extension, or "" for a path with no file-name element. A trailing
     * {@code .gz} is stripped first, so {@code unii.json.gz} yields {@code unii} and not
     * {@code unii.json} — the latter would key the provider under a type no rule ever names, and
     * every rule of that dictionary would SKIP silently.
     */
    private static String fileStem(Path file)
    {
        Path name = file.getFileName();
        if (name == null)
        {
            return "";
        }
        String s = name.toString();
        if (s.endsWith(".gz"))
        {
            s = s.substring(0, s.length() - ".gz".length());
        }
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }


    /** The dictionary types actually loaded, lower-cased — what a run can answer. */
    public java.util.Set<String> loadedTypes()
    {
        return byType.keySet();
    }


    /**
     * Every recorded diagnosis, keyed by lower-cased type — for a caller that re-composes a
     * provider (the {@code DictionaryStore} flat carve-out filtering a requested version) and must
     * carry the already-diagnosed types forward unchanged. Immutable.
     */
    public Map<String, Unavailability> unavailabilities()
    {
        return unavailable;
    }


    /**
     * The recorded diagnosis for a type that is not loaded, or {@code null} when nothing richer
     * than "not installed" is known (including for a type that IS loaded — check
     * {@link #isAvailable} first).
     */
    public @Nullable Unavailability unavailabilityOf(@Nullable String type)
    {
        return type == null ? null : unavailable.get(type.toLowerCase(Locale.ROOT));
    }


    /**
     * The operator-actionable predicate for an unavailable {@code type}, continuing "external
     * dictionary {@code <type>} …" — the recorded {@link Unavailability#detail} when one exists,
     * else {@link #notInstalledDetail()}. Callers (the {@code RuleRunner} eager SKIP arm, the
     * run-level {@code Dictionary_Basis} line) share this so the log, the per-rule reason and the
     * report never disagree about why a dictionary did not answer.
     */
    public String unavailabilityDetail(@Nullable String type)
    {
        Unavailability u = unavailabilityOf(type);
        return u != null ? u.detail() : notInstalledDetail();
    }


    /** The recorded release of a loaded dictionary, or {@code null} when absent or undeclared. */
    public @Nullable String versionOf(@Nullable String type)
    {
        ValueMapDictionary d = get(type);
        return d != null ? d.getVersion() : null;
    }


    /**
     * The loaded dictionary of a type, or {@code null}. Lets a caller that composes a provider from
     * several directories — {@code DictionaryStore}, binding one installed version per type — lift
     * an already-validated dictionary out rather than re-reading and re-checking the file.
     */
    public @Nullable ValueMapDictionary dictionaryOf(@Nullable String type)
    {
        return get(type);
    }


    private @Nullable ValueMapDictionary get(@Nullable String type)
    {
        return type == null ? null : byType.get(type.toLowerCase(Locale.ROOT));
    }


    /** Whether a dictionary of the given type is loaded — the {@code dictionary_available} gate. */
    public boolean isAvailable(@Nullable String type)
    {
        return get(type) != null;
    }


    /** Membership check — is {@code term} a valid term of {@code type} at {@code level}? */
    public boolean isValidTerm(@Nullable String type, @Nullable String level, @Nullable String term)
    {
        ValueMapDictionary d = get(type);
        return d != null && d.isValidTerm(level, term);
    }


    /** Case check — does {@code term} match the preferred case in {@code type} / {@code level}? */
    public boolean caseMatches(@Nullable String type, @Nullable String level, @Nullable String term)
    {
        ValueMapDictionary d = get(type);
        return d != null && d.caseMatches(level, term);
    }


    /**
     * Hierarchy-path check — is {@code parent} a parent of {@code term} in {@code type}?
     * {@code caseSensitive} selects the verbatim (D-TA-3 default) or case-folded comparison.
     */
    public boolean onHierarchyPath(@Nullable String type, @Nullable String term,
            @Nullable String parent, boolean caseSensitive)
    {
        ValueMapDictionary d = get(type);
        return d != null && d.onHierarchyPath(term, parent, caseSensitive);
    }


    /**
     * code&harr;decode pairing — does {@code code} decode to {@code decode} in {@code type}?
     * {@code caseSensitive} selects the verbatim (D-TA-3 default) or case-folded comparison.
     */
    public boolean codeDecodePair(@Nullable String type, @Nullable String reg,
            @Nullable String code, @Nullable String decode, boolean caseSensitive)
    {
        ValueMapDictionary d = get(type);
        return d != null && d.codeDecodePair(reg, code, decode, caseSensitive);
    }


    /**
     * E8 — decode-presence: whether the {@code type} dictionary holds any decode for {@code code}
     * (the {@code dictionary_has_decode} precondition). {@code false} when the type is not loaded.
     * {@code caseSensitive} selects the verbatim (D-TA-3 default) or case-folded code lookup.
     */
    public boolean hasDecode(@Nullable String type, @Nullable String reg, @Nullable String code,
            boolean caseSensitive)
    {
        ValueMapDictionary d = get(type);
        return d != null && d.hasDecode(reg, code, caseSensitive);
    }


    /** Per-term attribute lookup in {@code type}, or {@code null} when absent. */
    public @Nullable String termAttribute(@Nullable String type, @Nullable String attr,
            @Nullable String term)
    {
        ValueMapDictionary d = get(type);
        return d != null ? d.termAttribute(attr, term) : null;
    }

}
