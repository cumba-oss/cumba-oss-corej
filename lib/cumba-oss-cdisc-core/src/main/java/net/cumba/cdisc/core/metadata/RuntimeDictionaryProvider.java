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

    private final Map<String, ValueMapDictionary> byType;

    public RuntimeDictionaryProvider(Map<String, ValueMapDictionary> byType)
    {
        Map<String, ValueMapDictionary> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ValueMapDictionary> e : byType.entrySet())
        {
            normalized.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        this.byType = Map.copyOf(normalized);
    }


    /**
     * Loads every {@code *.json} house-format dictionary in {@code dir} into a provider. A
     * directory that does not exist yields an empty provider (all types unavailable ⇒ dictionary
     * rules SKIP).
     */
    public static RuntimeDictionaryProvider loadDirectory(Path dir) throws IOException
    {
        Map<String, ValueMapDictionary> loaded = new LinkedHashMap<>();
        if (dir != null && Files.isDirectory(dir))
        {
            try (Stream<Path> files = Files.list(dir))
            {
                for (Path file : files.filter(RuntimeDictionaryProvider::isJsonFile).sorted()
                        .toList())
                {
                    ValueMapDictionary dict = ValueMapDictionary.load(file);
                    String key = !dict.getType().isEmpty() ? dict.getType() : fileStem(file);
                    loaded.put(key.toLowerCase(Locale.ROOT), dict);
                }
            }
        }
        return new RuntimeDictionaryProvider(loaded);
    }


    private static boolean isJsonFile(Path p)
    {
        Path name = p.getFileName();
        return name != null && name.toString().endsWith(".json");
    }


    /** The file name without its extension, or "" for a path with no file-name element. */
    private static String fileStem(Path file)
    {
        Path name = file.getFileName();
        if (name == null)
        {
            return "";
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
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
