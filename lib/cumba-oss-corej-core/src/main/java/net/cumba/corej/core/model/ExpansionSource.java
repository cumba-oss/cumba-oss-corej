package net.cumba.corej.core.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The closed set of {@code Expansion[].over} sources — where an {@link ExpansionDirective} gets the
 * values it binds its token to.
 *
 * <p>
 * This enum is the <b>extension point</b> of the expansion mechanism. Adding a third way to
 * enumerate token values must cost exactly one constant here plus its resolver in
 * {@code net.cumba.corej.core.gen.TokenExpander} — and nothing else. In particular the substitution
 * half is token-in / string-out and never inspects this value: it is handed a
 * {@code token -> value} binding and rewrites the rule, with no knowledge of how the binding was
 * derived.
 * </p>
 */
public enum ExpansionSource
{

    /**
     * Each variable the dataset under validation shares <em>by name</em> with the foreign dataset
     * named by {@code with:}. Needs an inventory-capable resolver
     * ({@code net.cumba.corej.core.exec.ScopeVariableSource}); when the foreign dataset is absent
     * or the resolver is blind the rule is <b>skipped with a stated reason</b>, never expanded to
     * zero rules.
     */
    SHARED_VARIABLES("shared_variables"),

    /**
     * Each column of the dataset under validation matching {@code pattern:} — the token binds to
     * the text the token position captured, i.e. the inferred domain code of a
     * {@code <XX>SEQ}-style parent reference. With {@code known_domain_only: true} a candidate
     * survives only when the captured text is a domain the library attests
     * ({@code MetadataProvider.getStandardDatasetNames}); that is what keeps ADaM's own
     * {@code ASEQ} / {@code SRCSEQ} / {@code RECSEQ} out, without a hand-maintained deny-list.
     */
    DOMAIN_FROM_VARIABLE("domain_from_variable"),

    /**
     * <b>Every column of the dataset under validation</b>, in column order — the token binds to the
     * column name, and the rule is emitted once per column ({@code CDISC-SEND-0049} over
     * {@code AETERM} ⇒ {@code CDISC-SEND-0049-AETERM}).
     *
     * <p>
     * This is the declared-token counterpart of the variable cursor, and it takes neither
     * {@code with:} nor {@code pattern:}. ⚠ It is deliberately <b>not</b> spelled as a bare
     * {@code *} wildcard: the engine-owned markers are matched <em>inside</em> a name and are
     * ambiguous by design, which is why {@code WildcardExpander} refuses a name-position bare
     * {@code *} outright (Fix #84). A declared token carries a mandatory sigil and cannot collide.
     * </p>
     */
    ALL_VARIABLES("all_variables"),

    /**
     * The columns of the dataset under validation whose loaded type folds to {@code "Num"} —
     * {@code LONG}, {@code DOUBLE} or {@code BOOLEAN}.
     *
     * <p>
     * ⛔ The fold is {@code MetadataNormalizer.charOrNum}, the <b>same</b> function that answers
     * {@code var_type("DATA")}. Never re-derive it: an expansion source that disagreed with the
     * accessor would do so <em>inside the very rule it expanded</em>, and silently.
     * </p>
     */
    ALL_NUMERIC_VARIABLES("all_numeric_variables"),

    /**
     * The columns of the dataset under validation whose loaded type folds to {@code "Char"} —
     * {@code STRING}.
     *
     * <p>
     * ⚠⚠ {@link #ALL_NUMERIC_VARIABLES} and this source do <b>not</b> partition
     * {@link #ALL_VARIABLES}. Every type that folds to neither — {@code MISSING} and {@code OTHER}
     * in every tree, plus {@code COMPLEX} and {@code VARIABLE} internally — is in
     * {@code all_variables} and in neither of these two. That is consistent with
     * {@code var_type("DATA")} answering missing for such a column, and it is pinned by a test so
     * nobody later "fixes" it into the character bucket.
     * </p>
     */
    ALL_CHARACTER_VARIABLES("all_character_variables");

    private final String jsonValue;

    ExpansionSource(String aJsonValue)
    {
        jsonValue = aJsonValue;
    }


    /**
     * The verbatim string an author writes in {@code over:}.
     *
     * @return the JSON/YAML value of this source
     */
    public String getJsonValue()
    {
        return jsonValue;
    }


    /**
     * Parses an authored {@code over:} value.
     *
     * @param raw
     *            the authored string, possibly {@code null}
     * @return the matching source, or {@code null} when {@code raw} is absent or unrecognised — the
     *         caller ({@code RulePackageLoader}) turns the latter into a load error rather than
     *         silently dropping the directive
     */
    public static @Nullable ExpansionSource fromJson(@Nullable String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        for (ExpansionSource s : values())
        {
            if (s.jsonValue.equals(normalised))
            {
                return s;
            }
        }
        return null;
    }

}
