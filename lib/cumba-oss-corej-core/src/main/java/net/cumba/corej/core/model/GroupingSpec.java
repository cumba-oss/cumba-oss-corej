package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A rule's {@code Grouping:} block — the rule-level grouping key plus its missing-key disposition.
 *
 * <pre>
 * Grouping:
 *   Variables: ["USUBJID", "PARAMCD"]
 *   keep_missings: true          # optional; default is the engine's per-site default
 * </pre>
 *
 * <p>
 * <b>Why a block rather than two flat keys.</b> A flat {@code Grouping_Variables:} beside a flat
 * {@code Grouping_Keep_Missings:} lets a rule carry the parameter with no variables at all —
 * meaningless, and silently so. Nesting makes that state unrepresentable, and it matches the
 * existing {@code Scope.Domains.Include} house shape.
 * </p>
 *
 * <p>
 * ⚠ <b>The mixed casing is deliberate</b> and follows the corpus's own split: structural rule-level
 * keys are PascalCase ({@code Scope}, {@code Domains}, {@code Include}, {@code Variables}) while
 * parameters are snake_case ({@code value_is_literal}, {@code missing_values},
 * {@code keep_missings}). It is not a typo and must not be "corrected".
 * </p>
 *
 * <p>
 * The flat {@code Grouping_Variables:} form remains accepted — see
 * {@link Rule#effectiveGroupingVariables()} — so the corpus is never ahead of the engine. Declaring
 * both shapes on one rule is a load error.
 * </p>
 */
@Data
@NoArgsConstructor
public class GroupingSpec
{

    /**
     * The grouping key columns, in declared order. The {@code --} domain-prefix wildcard is
     * resolved at execution time exactly as it is for the flat {@code Grouping_Variables:} form.
     */
    @JsonProperty("Variables")
    private @Nullable List<String> variables;

    /**
     * Whether a row whose grouping key carries a missing value stays in its group (folded under the
     * blank key) or is dropped along with its whole group.
     *
     * <p>
     * {@code null} — the shipped state of every rule — means "engine default", which for the
     * rule-level grouping surface is <b>drop</b>. A missing value is a valid value of a variable,
     * so the intended direction is {@code true}; the default is not flipped here so that this phase
     * moves no findings.
     * </p>
     *
     * <p>
     * ⚠ Not to be confused with {@code missing_values} on an {@link Operation}, which governs a
     * different axis: how a missing <em>input</em> affects an <em>operation's result</em>. This one
     * governs whether a row <em>participates in a group</em>.
     * </p>
     */
    @JsonProperty("keep_missings")
    private @Nullable Boolean keepMissings;

}
