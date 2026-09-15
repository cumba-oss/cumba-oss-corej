package net.cumba.corej.core.gen;

import java.util.List;

import net.cumba.corej.core.model.Rule;

/**
 * The rules that apply to one dataset, as resolved by
 * {@link net.cumba.corej.core.exec.DatasetRuleResolver}, together with a report documenting what
 * was delivered and which source rules were skipped.
 */
public class GeneratedRulePackage
{

    private final List<Rule> rules;

    private final List<SkippedSourceRule> skippedSourceRules;

    private final RuleGenerationReport report;

    public GeneratedRulePackage(List<Rule> rules, RuleGenerationReport report)
    {
        this(rules, List.of(), report);
    }


    public GeneratedRulePackage(List<Rule> rules, List<SkippedSourceRule> skippedSourceRules,
            RuleGenerationReport report)
    {
        this.rules = List.copyOf(rules);
        this.skippedSourceRules = List.copyOf(skippedSourceRules);
        this.report = report;
    }


    public List<Rule> getRules()
    {
        return rules;
    }


    /**
     * Static input rules that the generator considered for this dataset but rejected by scope —
     * non-matching domain, non-matching class, non-matching variables, or a template whose required
     * scope didn't match. Executability is not a rejection reason (mirrors Python; the field is
     * recorded on the validation result but does not gate execution). Each entry pairs the source
     * rule with a human-readable reason. Used by
     * {@link net.cumba.corej.core.report.LibraryValidator} to surface a {@code SKIPPED} runtime
     * entry per (rule, dataset) so the runtime report is a complete proof that every input rule was
     * considered.
     */
    public List<SkippedSourceRule> getSkippedSourceRules()
    {
        return skippedSourceRules;
    }


    public RuleGenerationReport getReport()
    {
        return report;
    }

}
