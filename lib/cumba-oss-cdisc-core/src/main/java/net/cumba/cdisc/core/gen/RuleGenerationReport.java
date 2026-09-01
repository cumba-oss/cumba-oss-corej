package net.cumba.cdisc.core.gen;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Documents what rules were generated (and what was skipped) for a given domain, for transparency
 * and debugging.
 */
public class RuleGenerationReport
{

    private final String domainName;

    private final @Nullable String standard;

    private final @Nullable String version;

    private final @Nullable String ctPackageVersion;

    private final Instant timestamp;

    private final List<GeneratedRuleInfo> generatedRules = new ArrayList<>();

    private final List<SkippedRuleInfo> skippedRules = new ArrayList<>();

    public RuleGenerationReport(String domainName, @Nullable String standard,
            @Nullable String version, @Nullable String ctPackageVersion)
    {
        this.domainName = domainName;
        this.standard = standard;
        this.version = version;
        this.ctPackageVersion = ctPackageVersion;
        this.timestamp = Instant.now();
    }


    public void addGenerated(GeneratedRuleInfo info)
    {
        generatedRules.add(info);
    }


    public void addSkipped(SkippedRuleInfo info)
    {
        skippedRules.add(info);
    }


    public List<GeneratedRuleInfo> getGeneratedRules()
    {
        return generatedRules;
    }


    public List<SkippedRuleInfo> getSkippedRules()
    {
        return skippedRules;
    }


    public int getGeneratedCount()
    {
        return generatedRules.size();
    }


    public int getSkippedCount()
    {
        return skippedRules.size();
    }


    /**
     * Exports the report as human-readable markdown.
     */
    public String toMarkdown()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Rule Generation Report\n\n");
        sb.append("**Domain:** ").append(domainName).append("  \n");
        sb.append("**Standard:** ").append(standard).append(" v").append(version).append("  \n");
        if (ctPackageVersion != null)
        {
            sb.append("**CT Package:** ").append(ctPackageVersion).append("  \n");
        }
        sb.append("**Generated:** ").append(timestamp).append("\n\n");

        // Summary by category
        Map<RuleCategory, Integer> genCounts = new EnumMap<>(RuleCategory.class);
        Map<RuleCategory, Integer> skipCounts = new EnumMap<>(RuleCategory.class);
        for (GeneratedRuleInfo r : generatedRules)
        {
            genCounts.merge(r.category(), 1, Integer::sum);
        }
        for (SkippedRuleInfo r : skippedRules)
        {
            skipCounts.merge(r.category(), 1, Integer::sum);
        }

        sb.append("## Summary\n\n");
        sb.append("| Category | Generated | Skipped |\n");
        sb.append("|----------|-----------|--------|\n");
        for (RuleCategory cat : RuleCategory.values())
        {
            int gen = genCounts.getOrDefault(cat, 0);
            int skip = skipCounts.getOrDefault(cat, 0);
            if (gen > 0 || skip > 0)
            {
                sb.append("| ").append(cat.name()).append(" | ").append(gen).append(" | ")
                        .append(skip).append(" |\n");
            }
        }
        sb.append("| **Total** | **").append(generatedRules.size()).append("** | **")
                .append(skippedRules.size()).append("** |\n\n");

        // Generated rules
        if (!generatedRules.isEmpty())
        {
            sb.append("## Generated Rules\n\n");
            sb.append("| Rule ID | Category | Variable | Description |\n");
            sb.append("|---------|----------|----------|-------------|\n");
            for (GeneratedRuleInfo r : generatedRules)
            {
                sb.append("| ").append(r.ruleId()).append(" | ").append(r.category().name())
                        .append(" | ").append(r.variable() != null ? r.variable() : "—")
                        .append(" | ").append(r.description()).append(" |\n");
            }
            sb.append('\n');
        }

        // Skipped rules
        if (!skippedRules.isEmpty())
        {
            sb.append("## Skipped Rules\n\n");
            sb.append("| Category | Variable | Reason |\n");
            sb.append("|----------|----------|--------|\n");
            for (SkippedRuleInfo r : skippedRules)
            {
                sb.append("| ").append(r.category().name()).append(" | ")
                        .append(r.variable() != null ? r.variable() : "—").append(" | ")
                        .append(r.reason()).append(" |\n");
            }
            sb.append('\n');
        }

        return sb.toString();
    }

}
