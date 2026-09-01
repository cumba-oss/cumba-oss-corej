package net.cumba.cdisc.core.gen;

import org.jspecify.annotations.Nullable;

/**
 * Documents a single generated rule for the {@link RuleGenerationReport}.
 */
public record GeneratedRuleInfo(@Nullable String ruleId, RuleCategory category,
        @Nullable String variable, @Nullable String description, @Nullable String librarySource)
{
}
