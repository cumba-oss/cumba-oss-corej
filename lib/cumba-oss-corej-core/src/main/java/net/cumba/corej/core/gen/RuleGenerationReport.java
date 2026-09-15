package net.cumba.corej.core.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * Documents which of the caller's loaded rules were materialised for a given domain — wildcard
 * children and {@code --}-prefix substitutions — for transparency and debugging.
 *
 * <p>
 * ⚑ The "skipped" half of this report was deleted with the in-Java generators
 * ({@code plans/PLAN-remove-rule-generator.md}); every one of its writers was a generator body. A
 * rule dropped because it did not match the dataset's scope is reported instead as a
 * {@link SkippedSourceRule} on the {@link GeneratedRulePackage}, which is the channel
 * {@code LibraryValidator} actually reads.
 * </p>
 *
 * <p>
 * ⚑ The header fields — domain, standard, version, CT package, timestamp — went at the same time.
 * Their only reader was {@code toMarkdown()}, the generation-report renderer, which described a
 * generation that no longer happens; once it went they were write-only, which is what PMD's
 * {@code UnusedPrivateField} reported. Re-adding them means adding getters nobody calls.
 * </p>
 */
public class RuleGenerationReport
{

    private final List<GeneratedRuleInfo> generatedRules = new ArrayList<>();

    public void addGenerated(GeneratedRuleInfo info)
    {
        generatedRules.add(info);
    }


    public List<GeneratedRuleInfo> getGeneratedRules()
    {
        return generatedRules;
    }


    public int getGeneratedCount()
    {
        return generatedRules.size();
    }

}
