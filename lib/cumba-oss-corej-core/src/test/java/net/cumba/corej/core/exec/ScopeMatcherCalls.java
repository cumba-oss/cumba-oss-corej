package net.cumba.corej.core.exec;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * Test shorthand for {@link ScopeMatcher}: the boolean {@code matches*} forms and the defaulting
 * {@code describe*} overloads the tests call, forwarding to the production {@code describe*} entry
 * points with the defaults the removed overloads used to supply.
 *
 * <p>
 * ⭐ {@code PLAN-retire-dead-multi-match-lookup} U1 (A5–A9) removed them from {@code src/main}:
 * production calls only the reason-bearing full forms ({@code describeDomainMismatch} with the
 * data-derived unsplit name, {@code describeClassMismatch}, the 4-argument
 * {@code describeVariablesMismatch}, the set-valued {@code describeDataStructureMismatch} /
 * {@code describeSubclassMismatch}). The boolean API and the table-less / single-token /
 * qualified-blind conveniences had test callers only.
 * </p>
 *
 * <p>
 * ⚠ {@link #matchesDomain} / the 2-argument {@link #describeDomainMismatch} are the
 * <b>table-less</b> reading: the split base is derived from the name by
 * {@link SplitDatasetUtil#unsplitName}, which is what those overloads did. It does not always agree
 * with the data-driven base production computes (see the production javadoc); a test that needs the
 * data-driven verdict passes the unsplit name itself.
 * </p>
 *
 * <p>
 * ⛔ Pure forwarders only — see {@link RuleRunnerCalls}.
 * </p>
 */
public final class ScopeMatcherCalls
{

    private ScopeMatcherCalls()
    {
    }


    public static boolean matchesDomain(Rule rule, String domainName)
    {
        return describeDomainMismatch(rule, domainName) == null;
    }


    /** Table-less: the split base is the name-derived {@link SplitDatasetUtil#unsplitName}. */
    public static @Nullable String describeDomainMismatch(Rule rule, String domainName)
    {
        return describeDomainMismatch(rule, domainName,
                domainName == null ? null : SplitDatasetUtil.unsplitName(domainName));
    }


    public static @Nullable String describeDomainMismatch(Rule rule, String domainName,
            @Nullable String unsplitName)
    {
        return ScopeMatcher.describeDomainMismatch(rule, domainName, unsplitName);
    }


    public static boolean matchesClass(Rule rule, @Nullable String className)
    {
        return ScopeMatcher.describeClassMismatch(rule, className) == null;
    }


    public static boolean matchesVariables(Rule rule, DataTableMeta meta)
    {
        return describeVariablesMismatch(rule, meta, null) == null;
    }


    public static boolean matchesVariables(Rule rule, DataTableMeta meta,
            @Nullable String domainPrefix)
    {
        return describeVariablesMismatch(rule, meta, domainPrefix) == null;
    }


    public static @Nullable String describeVariablesMismatch(Rule rule, DataTableMeta meta)
    {
        return describeVariablesMismatch(rule, meta, null);
    }


    public static @Nullable String describeVariablesMismatch(Rule rule, DataTableMeta meta,
            @Nullable String domainPrefix)
    {
        return describeVariablesMismatch(rule, meta, domainPrefix, null);
    }


    /**
     * The production entry point, passed through. ⚠ With {@code foreign == null} every qualified
     * entry is undecidable and therefore a mismatch (the former 4-argument overload's
     * {@code IGNORE} reading — undecidable counts as satisfied — was retired with the
     * {@code QualifiedEntryPolicy} enum, K6, 2026-09-25).
     */
    public static @Nullable String describeVariablesMismatch(Rule rule, DataTableMeta meta,
            @Nullable String domainPrefix, @Nullable ScopeVariableSource foreign)
    {
        return ScopeMatcher.describeVariablesMismatch(rule, meta, domainPrefix, foreign);
    }


    public static boolean matchesDataStructure(Rule rule, @Nullable String detectedStructure)
    {
        return describeDataStructureMismatch(rule, detectedStructure) == null;
    }


    public static boolean matchesDataStructure(Rule rule, List<String> detectedStructures)
    {
        return describeDataStructureMismatch(rule, detectedStructures) == null;
    }


    /** Single token: a one-element set (no structure hierarchy applied); {@code null} = none. */
    public static @Nullable String describeDataStructureMismatch(Rule rule,
            @Nullable String detectedStructure)
    {
        return describeDataStructureMismatch(rule,
                detectedStructure == null ? List.of() : List.of(detectedStructure));
    }


    public static @Nullable String describeDataStructureMismatch(Rule rule,
            List<String> detectedStructures)
    {
        return ScopeMatcher.describeDataStructureMismatch(rule, detectedStructures);
    }


    /** Single token: a one-element set; {@code null} = no subclass detected. */
    public static @Nullable String describeSubclassMismatch(Rule rule,
            @Nullable String detectedSubclass)
    {
        return describeSubclassMismatch(rule,
                detectedSubclass == null ? List.of() : List.of(detectedSubclass));
    }


    public static @Nullable String describeSubclassMismatch(Rule rule,
            List<String> detectedSubclasses)
    {
        return ScopeMatcher.describeSubclassMismatch(rule, detectedSubclasses);
    }
}
