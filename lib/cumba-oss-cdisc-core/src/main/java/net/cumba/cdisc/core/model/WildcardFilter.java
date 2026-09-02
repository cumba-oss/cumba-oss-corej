package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Fix #24: numeric range filter on a wildcard capture group, applied during
 * {@code WildcardExpander.expand} to drop tuples whose captured group value falls outside the
 * inclusive range.
 *
 * <p>
 * Engine extension beyond the upstream rule format; specified in
 * {@code the CORE rules specification}, Engine Fields &#167; {@code wildcards}.
 * </p>
 * <p>
 * <b>How widely it is used</b> (re-measured 2026-08-24). The {@code wildcards} directive is
 * <em>pervasive</em>, not exceptional: <b>360</b> rules in {@code rules-src/checks} declare one
 * ({@code 404} token entries), materialising as <b>885</b> {@code wildcards} occurrences across the
 * eight shipped ADaM packages ({@code rules-cdisc-adamig-1-0…1-3},
 * {@code rules-pmda-adamig-1-0…1-3}; {@code draft-adamig-1-3} carries one more, outside that set).
 * The bounds are mostly a declaration of the token's range as the sheet states it, and how much
 * they actually filter depends on the token's regex:
 * <ul>
 * <li>{@code y} → {@code (\d+)}, an <b>unbounded</b> digit run, so {@code {y: {min: 1, max: 99}}}
 * (238 entries), {@code {y: {min: 1, max: 9}}} (37) and {@code {y: {min: 10, max: 99}}} (3)
 * genuinely cap it — {@code B10IND} is rejected by the second;</li>
 * <li>{@code xx} / {@code zz} → {@code (\d{2})} and {@code w} → {@code (\d)} are already
 * width-bounded, so {@code {min: 1, max: 99}} (72 + 22 entries) and {@code {min: 1, max: 9}} (28)
 * exclude only the zero index.</li>
 * </ul>
 * <p>
 * The one bound that constrains beyond a token's own grammar is {@code {xx: {min: 2}}} — the "where
 * xx &gt; 01" criterion the wildcard grammar cannot express. It is carried by <b>4</b> rules:
 * {@code CDISC-AD0078}, {@code CDISC-AD0079} <em>and their PMDA twins</em> {@code PMDA-AD0078},
 * {@code PMDA-AD0079} (16 package occurrences, 4 ADaM versions × 2 families × 2 rules).
 * </p>
 */
@Data
@NoArgsConstructor
public class WildcardFilter
{

    /** Inclusive lower bound, or {@code null} for no lower bound. */
    @JsonProperty("min")
    private @Nullable Integer min;

    /** Inclusive upper bound, or {@code null} for no upper bound. */
    @JsonProperty("max")
    private @Nullable Integer max;

    /**
     * Returns {@code true} when {@code value} satisfies the configured min/max bounds. Both bounds
     * are inclusive; absent bounds are treated as &minus;infinity / +infinity.
     */
    public boolean accepts(int value)
    {
        if (min != null && value < min)
        {
            return false;
        }
        return !(max != null && value > max);
    }
}
