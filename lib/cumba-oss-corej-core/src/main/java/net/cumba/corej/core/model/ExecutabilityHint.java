package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Project-specific annotation that records, for a rule which is <em>not directly executable</em>,
 * what the engine must do to run it — or, for a genuinely non-executable rule, why it cannot be
 * run.
 *
 * <p>
 * The upstream CDISC {@link Executability} flag is coarse (and the engine does not consult it for
 * execution decisions). A rule whose {@link Executability} is
 * {@link Executability#FULLY_EXECUTABLE} may still require wildcard expansion or template
 * generation before it can run; this hint carries that "how". It is present only on rules that are
 * not directly executable:
 * </p>
 * <ul>
 * <li>{@code expanded} — the rule's {@code --} domain-prefix wildcards must be expanded to the
 * target dataset (handled by {@code WildcardExpander} / {@code RuleGenerator}).</li>
 * <li>{@code generated} — concrete rules must be generated from a wildcard/root-name template (e.g.
 * {@code *FL}) by {@code RuleGenerator} using dataset and CDISC Library variable metadata.</li>
 * <li>{@code not executable} — the rule cannot be run by the current engine (e.g. it needs dynamic
 * value-indexed variable resolution or multi-axis index expansion); {@link Executability} stays
 * {@link Executability#NOT_EXECUTABLE} and {@link #detail} explains the blocker.</li>
 * </ul>
 *
 * <p>
 * Directly-executable rules carry no hint.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutabilityHint
{

    /**
     * Category of handling required: {@code expanded}, {@code generated}, or
     * {@code not executable}.
     */
    @JsonProperty("Category")
    private @Nullable String category;

    /**
     * Human-readable description of what the engine must do to execute the rule, or — for a
     * {@code not executable} rule — why it cannot be run.
     */
    @JsonProperty("Detail")
    private @Nullable String detail;

}
