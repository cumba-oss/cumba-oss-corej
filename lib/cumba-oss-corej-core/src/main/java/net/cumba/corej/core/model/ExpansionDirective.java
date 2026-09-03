package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a rule's {@code Expansion:} block — "bind this token to every value the named source
 * yields, and emit one concrete rule per value".
 *
 * <p>
 * The token is <b>declared by the author</b>, not fixed by the engine. That is the difference from
 * the {@code xx} / {@code y} / {@code zz} / {@code w} markers {@link ExpansionSource} sits beside:
 * those are engine-owned and ambiguous by design (they match <em>inside</em> a name, so
 * {@code TRTxxPN} works), whereas a declared token is substituted as an exact string wherever it
 * occurs. A token must therefore carry a non-alphanumeric sigil so it can never collide with a real
 * CDISC variable name ({@code [A-Z][A-Z0-9]*}); {@code &VAR} is the house default.
 * </p>
 *
 * <pre>{@code
 * Expansion:
 * - token: "&VAR"
 *   over: "shared_variables"
 *   with: "ADSL"
 * - token: "&DOM"
 *   over: "domain_from_variable"
 *   pattern: "&DOMSEQ"
 *   known_domain_only: true
 * }</pre>
 *
 * <p>
 * Engine extension beyond the upstream rule format; specified in
 * {@code the CORE rules specification}, Engine Fields &#167; {@code Expansion}. The Python
 * reference engine ignores the block and therefore reads the template literally — an accepted,
 * filed divergence, not an accident.
 * </p>
 */
@Data
@NoArgsConstructor
public class ExpansionDirective
{

    /**
     * The exact string the expander substitutes wherever it occurs in the rule body. Author-chosen;
     * validated at load to carry a non-alphanumeric sigil (see {@code RulePackageLoader}).
     */
    @JsonProperty("token")
    private @Nullable String token;

    /**
     * Typed {@code over}; {@code null} when the field is absent <em>or</em> carried an unrecognised
     * string. The raw text is kept in {@link #rawOver} so the loader can fail loud on a
     * present-but-invalid value while an absent field keeps the {@code null} semantics. Mirrors the
     * raw/typed binding contract of {@link Rule#getSensitivity()}.
     */
    @JsonIgnore
    private @Nullable ExpansionSource over;

    /** Raw JSON {@code over} string, kept verbatim for load-time validation / round-trip. */
    @JsonIgnore
    private @Nullable String rawOver;

    /**
     * {@link ExpansionSource#SHARED_VARIABLES} only — the foreign dataset whose variables are
     * intersected with the dataset under validation ({@code "ADSL"}).
     */
    @JsonProperty("with")
    private @Nullable String with;

    /**
     * {@link ExpansionSource#DOMAIN_FROM_VARIABLE} only — the column-name shape the token appears
     * in ({@code "&DOMSEQ"}). The token's position in the pattern is the capture; the rest is
     * literal.
     */
    @JsonProperty("pattern")
    private @Nullable String pattern;

    /**
     * {@link ExpansionSource#DOMAIN_FROM_VARIABLE} only — when {@code true}, a candidate survives
     * only if the captured text is a dataset name the library attests. See
     * {@link ExpansionSource#DOMAIN_FROM_VARIABLE} for why this is not optional in practice.
     */
    @JsonProperty("known_domain_only")
    private @Nullable Boolean knownDomainOnly;

    /**
     * Typed programmatic setter; keeps {@link #rawOver} in sync (see {@link Rule#setSensitivity}).
     */
    public void setOver(@Nullable ExpansionSource aOver)
    {
        this.over = aOver;
        this.rawOver = aOver != null ? aOver.getJsonValue() : null;
    }


    /** Jackson binding for {@code over}: stores the raw string and the parsed enum. */
    @JsonSetter("over")
    public void setOverJson(@Nullable String raw)
    {
        this.rawOver = raw;
        this.over = ExpansionSource.fromJson(raw);
    }


    /**
     * Jackson serialization of {@code over}: the canonical enum value when valid, otherwise the raw
     * string verbatim.
     *
     * @return the JSON value for {@code over}, or {@code null} when absent
     */
    @JsonGetter("over")
    public @Nullable String getOverJson()
    {
        return over != null ? over.getJsonValue() : rawOver;
    }

}
