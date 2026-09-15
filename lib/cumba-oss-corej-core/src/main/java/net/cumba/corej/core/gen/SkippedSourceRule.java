package net.cumba.corej.core.gen;

import net.cumba.corej.core.model.Rule;

/**
 * A static input rule that {@link net.cumba.corej.core.exec.DatasetRuleResolver} considered for a
 * given dataset but did not include in the executed rule set. Pairs the source {@link Rule} with a
 * short human-readable reason so callers (e.g. the runtime listener in
 * {@link net.cumba.corej.core.report.LibraryValidator}) can attribute the skip in reports.
 *
 * <p>
 * ⚑ This is now the <b>only</b> skip channel. It was once paired with {@code SkippedRuleInfo},
 * which documented generator-emitted rules that were not produced for dataset-side reasons; both
 * that record and every generator that wrote to it were deleted by
 * {@code plans/PLAN-remove-rule-generator.md}. Every rule this engine can skip came from the input
 * package, so every skip is a {@code SkippedSourceRule}.
 * </p>
 *
 * @param rule
 *            the source rule (as found in the input rule package)
 * @param reason
 *            short, single-line explanation suitable for inclusion in a per-rule runtime entry
 */
public record SkippedSourceRule(Rule rule, String reason)
{
}
