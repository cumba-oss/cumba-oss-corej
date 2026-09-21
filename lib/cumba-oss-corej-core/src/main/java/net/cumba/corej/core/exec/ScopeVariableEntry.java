package net.cumba.corej.core.exec;

import java.util.Locale;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
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
 * <h2>Type suffix ({@code PLAN-variable-type-requirements}, rulings D5/D7)</h2>
 * <p>
 * An entry may end in {@code :N} / {@code :C} / {@code :Num} / {@code :Char} (case-insensitive),
 * demanding that the column is numeric or character as well as present. The tag is stripped
 * <b>before</b> the regex test and the qualifier split — {@code /^AE.*}{@code /:N} would otherwise
 * stop being recognised as a regex and be split on its first dot — and {@link #requiredKind()} is
 * {@code null} for every entry that carries none.
 * </p>
 * <p>
 * ⭐ <b>An entry with no tag parses exactly as it did before the feature existed</b> (ruling D5):
 * {@link #variable()} and {@link #raw()} are byte-for-byte what they were, so every message built
 * from them is unchanged. Only a <em>valid</em> trailing tag is stripped; anything else containing
 * a colon is left whole for {@link #malformedTypeSuffix} and loader gate R9 to reject, so the
 * matcher never silently reads {@code X:Z} as a column named {@code X}.
 * </p>
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
 * @param requiredKind
 *            the type the entry demands, or {@code null} when it carries no tag
 * @param raw
 *            the entry exactly as authored, used verbatim in mismatch messages
 */
public record ScopeVariableEntry(@Nullable String qualifier, String variable,
        ColumnTypeGate.@Nullable Kind requiredKind, String raw)
{

    /**
     * The four legal type tags, mapped to the ONE shipped classification
     * ({@link ColumnTypeGate#kindOf}'s vocabulary). ⛔ {@code Num}/{@code Char} are not a second
     * spelling with a second meaning — they collapse to the same {@link ColumnTypeGate.Kind}, so
     * nothing downstream can tell which the author typed (ruling D7).
     *
     * @param tag
     *            the text after the final {@code :}, already trimmed
     * @return the demanded kind, or {@code null} when {@code tag} is not a legal tag
     */
    private static ColumnTypeGate.@Nullable Kind kindOfTag(String tag)
    {
        return switch (tag.toUpperCase(Locale.ROOT))
        {
        case "N", "NUM" -> ColumnTypeGate.Kind.NUMERIC;
        case "C", "CHAR" -> ColumnTypeGate.Kind.CHARACTER;
        default -> null;
        };
    }

    /** The legal tags, for the loader's error message — one source, so the two cannot drift. */
    public static final String LEGAL_TYPE_TAGS = "N, C, Num, Char";

    /**
     * The kind a <b>valid</b> trailing tag demands, or {@code null} when the entry carries none.
     *
     * <p>
     * ⚠⚠ This and {@link #withoutTypeTag} must derive the colon the SAME way, and the first version
     * of round 1's finding-6 fix broke exactly that: {@link #parse} recovered the colon's index as
     * {@code body.length()}, which stopped being the colon once the body was
     * {@code stripTrailing()}ed — so {@code "AESEQ :N"} parsed as carrying no tag at all. Both now
     * route through this one {@code lastIndexOf}.
     * </p>
     *
     * @param raw
     *            the entry as authored
     * @return the demanded kind, or {@code null}
     */
    private static ColumnTypeGate.@Nullable Kind tagKind(String raw)
    {
        int colon = raw.lastIndexOf(':');
        return colon <= 0 ? null : kindOfTag(raw.substring(colon + 1).trim());
    }


    /**
     * Splits a <b>valid</b> trailing type tag off {@code raw}, or returns {@code raw} unchanged.
     * Shared by {@link #parse} and {@link #malformedTypeSuffix} so the two can never disagree about
     * what a tag is.
     *
     * <p>
     * ⚠ The remainder is {@code strip()}ed on BOTH sides (round 2 finding 5 — the first fix closed
     * only the trailing half, so {@code " AESEQ:N"} still asked for a column called
     * {@code " AESEQ"}): whitespace before the colon was tolerated on the tag's side but would
     * otherwise have become part of the column name, so {@code AESEQ :N} asked for a column
     * literally called {@code "AESEQ "} and skipped everywhere (review round 1, finding 6). Safe
     * for ruling D5 — this branch is reached only when a valid tag was found, and an untagged entry
     * returns {@code raw} untouched, spaces and all.
     * </p>
     *
     * @param raw
     *            the entry as authored
     * @return the entry without its type tag, or {@code raw} when it carries no valid one
     */
    private static String withoutTypeTag(String raw)
    {
        if (tagKind(raw) == null)
        {
            return raw;
        }
        return raw.substring(0, raw.lastIndexOf(':')).strip();
    }


    /**
     * Returns a description of the entry's malformed type suffix, or {@code null} when it has none.
     * Loader gate R9's detector.
     *
     * <p>
     * A colon is legal in exactly two places: as the separator of one valid trailing tag, and
     * inside a whole-entry {@code /…/} regex, where {@code (?:…)} is ordinary syntax. Anything else
     * — {@code X:}, {@code X:Z}, {@code X:NN}, {@code X:Numeric}, {@code A:B:C} — is an authoring
     * error, and {@code :Numeric} / {@code :Character} are the near-misses ruling D7 makes likely.
     * </p>
     *
     * @param raw
     *            the entry as authored
     * @return the problem, or {@code null} when the entry's colons are all legal
     */
    public static @Nullable String malformedTypeSuffix(String raw)
    {
        String body = withoutTypeTag(raw);
        if (body.indexOf(':') < 0 || isWholeEntryRegex(body))
        {
            return null;
        }
        return "entry '" + raw + "' has a malformed type suffix — the legal tags are "
                + LEGAL_TYPE_TAGS + " (case-insensitive), as a single trailing ':' tag";
    }


    /**
     * Whether the entry demands a column type at all — i.e. whether {@link #parse} would set
     * {@link #requiredKind()}. Read by loader gate R9's {@code None} arm (ruling D1).
     *
     * @param raw
     *            the entry as authored
     * @return whether a valid type tag is present
     */
    public static boolean hasTypeSuffix(String raw)
    {
        return !withoutTypeTag(raw).equals(raw);
    }


    /**
     * Parses a raw {@code Scope.Variables} entry per the splitting rule in the class javadoc.
     *
     * @param raw
     *            the entry as authored (never {@code null})
     * @return the parsed entry; qualified only when the entry carries a usable separator dot
     */
    public static ScopeVariableEntry parse(String raw)
    {
        // ⚠⚠ The tag comes off FIRST. isWholeEntryRegex tests the LAST character, so `/^AE.*/:N`
        // is not a regex until the `:N` is gone — and the dot split would then corrupt it. Every
        // test below therefore runs on `body`, while `raw` is preserved verbatim for the messages.
        String body = withoutTypeTag(raw);
        ColumnTypeGate.Kind kind = tagKind(raw);
        if (isWholeEntryRegex(body))
        {
            return new ScopeVariableEntry(null, body, kind, raw);
        }
        int dot = body.indexOf('.');
        if (dot <= 0 || dot >= body.length() - 1)
        {
            return new ScopeVariableEntry(null, body, kind, raw);
        }
        return new ScopeVariableEntry(body.substring(0, dot), body.substring(dot + 1), kind, raw);
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
