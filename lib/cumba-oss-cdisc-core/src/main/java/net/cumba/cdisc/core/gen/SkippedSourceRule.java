package net.cumba.cdisc.core.gen;

import net.cumba.cdisc.core.model.Rule;

/**
 * A static input rule that {@link RuleGenerator} considered for a given dataset but did not include
 * in the executed rule set. Pairs the source {@link Rule} with a short human-readable reason so
 * callers (e.g. the runtime listener in {@link net.cumba.cdisc.core.report.LibraryValidator}) can
 * attribute the skip in reports.
 *
 * <p>
 * Distinct from {@link SkippedRuleInfo}, which documents <em>generator-emitted</em> rules (codelist
 * categories etc.) that were not produced because of dataset-side reasons (extensible codelist, no
 * terms found). {@code SkippedSourceRule} documents rules that came from the input package but were
 * filtered out before execution.
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
