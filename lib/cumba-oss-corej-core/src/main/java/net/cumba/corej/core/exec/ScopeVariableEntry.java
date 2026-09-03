package net.cumba.corej.core.exec;

import org.jspecify.annotations.Nullable;

/**
 * Fix #124: the parsed form of a single {@code Scope.Variables.Include} / {@code Exclude} entry.
 * <p>
 * An entry is <b>qualified</b> when it names a variable in a dataset <em>other</em> than the one
 * under validation, using the dot-qualified {@code DATASET.VARIABLE} form the {@code Check}
 * language already uses ({@code DM.ARM}, {@code AE.AESDTH}, {@code ADSL.TRTxxPN}). Otherwise the
 * entry addresses the primary dataset and {@link #qualifier()} is {@code null}.
 * </p>
 *
 * <h2>Splitting rule</h2>
 * <ul>
 * <li>A whole-entry {@code /…/} regex is <b>never</b> split — a regular expression contains dots by
 * construction, so treating one as a qualifier separator would corrupt every regex entry. The test
 * is the same one {@link ScopeMatcher#scopePattern} uses to recognise the regex form.</li>
 * <li>Otherwise the entry splits at the <b>first</b> {@code .} when that dot is neither the first
 * nor the last character.</li>
 * </ul>
 *
 * <p>
 * The variable half keeps every existing entry semantic: a literal name, a glob ({@code *}/
 * {@code ?}), a {@code /…/} regex, or an ADaM wildcard-marker template ({@code TRTxxPN}). A leading
 * {@code --} is <b>not</b> supported in the variable half of a qualified entry (its resolution
 * domain would be ambiguous — the primary's prefix or the qualifier's); {@code RulePackageLoader}
 * rejects that shape at load time.
 * </p>
 *
 * @param qualifier
 *            the dataset/domain naming the foreign variable, or {@code null} for a primary-dataset
 *            entry
 * @param variable
 *            the variable half — the whole entry when {@code qualifier} is {@code null}
 * @param raw
 *            the entry exactly as authored, used verbatim in mismatch messages
 */
public record ScopeVariableEntry(@Nullable String qualifier, String variable, String raw)
{

    /**
     * Parses a raw {@code Scope.Variables} entry per the splitting rule in the class javadoc.
     *
     * @param raw
     *            the entry as authored (never {@code null})
     * @return the parsed entry; qualified only when the entry carries a usable separator dot
     */
    public static ScopeVariableEntry parse(String raw)
    {
        if (isWholeEntryRegex(raw))
        {
            return new ScopeVariableEntry(null, raw, raw);
        }
        int dot = raw.indexOf('.');
        if (dot <= 0 || dot >= raw.length() - 1)
        {
            return new ScopeVariableEntry(null, raw, raw);
        }
        return new ScopeVariableEntry(raw.substring(0, dot), raw.substring(dot + 1), raw);
    }


    /**
     * Returns {@code true} when the entry names a variable in another dataset.
     *
     * @return whether {@link #qualifier()} is present
     */
    public boolean isQualified()
    {
        return qualifier != null;
    }


    /**
     * Returns {@code true} when {@code entry} is the whole-entry {@code /…/} regex form. Kept in
     * sync with {@link ScopeMatcher#scopePattern} — that method decides the same thing for pattern
     * compilation, and the two must never disagree about which entries may be split.
     *
     * @param entry
     *            the raw entry
     * @return whether the entry is a {@code /…/} regex
     */
    public static boolean isWholeEntryRegex(String entry)
    {
        return entry.length() > 2 && entry.startsWith("/") && entry.endsWith("/");
    }

}
