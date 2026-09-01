package net.cumba.cdisc.core.gen;

import org.jspecify.annotations.Nullable;

/**
 * Documents a rule that was not generated, with the reason why.
 */
public record SkippedRuleInfo(RuleCategory category, @Nullable String variable, String reason)
{
}
