package net.cumba.cdisc.core.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The closed set of {@code Expansion[].over} sources — where an {@link ExpansionDirective} gets the
 * values it binds its token to.
 *
 * <p>
 * This enum is the <b>extension point</b> of the expansion mechanism. Adding a third way to
 * enumerate token values must cost exactly one constant here plus its resolver in
 * {@code net.cumba.cdisc.core.gen.ExpansionSources} — and nothing else. In particular the
 * substitution half is token-in / string-out and never inspects this value: it is handed a
 * {@code token -> value} binding and rewrites the rule, with no knowledge of how the binding was
 * derived.
 * </p>
 */
public enum ExpansionSource
{

    /**
     * Each variable the dataset under validation shares <em>by name</em> with the foreign dataset
     * named by {@code with:}. Needs an inventory-capable resolver
     * ({@code net.cumba.cdisc.core.exec.ScopeVariableSource}); when the foreign dataset is absent
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
    DOMAIN_FROM_VARIABLE("domain_from_variable");

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
