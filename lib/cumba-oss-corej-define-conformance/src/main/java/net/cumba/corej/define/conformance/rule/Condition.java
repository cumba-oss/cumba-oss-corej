package net.cumba.corej.define.conformance.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.cumba.corej.define.conformance.eval.OidResolver;
import net.cumba.corej.define.conformance.tree.ElementNode;
import org.jspecify.annotations.Nullable;

/**
 * The {@code when:} conditional-guard mini-language (plan §3.3). One node is exactly one of:
 *
 * <ul>
 * <li>a combinator — {@code all:} / {@code any:} (list of conditions) or {@code not:} (one
 * condition);</li>
 * <li>a leaf clause — {@code path:} plus exactly one of {@code equals:} / {@code oneOf:} /
 * {@code exists:}.</li>
 * </ul>
 *
 * <p>
 * Leaf-clause semantics over the {@link PathResolver} result set: {@code equals} / {@code oneOf}
 * are true when <b>any</b> resolved value matches; {@code exists: true} is true when the path
 * resolves to at least one element (or present attribute), {@code exists: false} when it resolves
 * to none. A malformed node (no combinator and no {@code path}, or a {@code path} without a
 * predicate, or several forms at once) fails {@link #validate()} at rule-load time.
 * </p>
 */
public final class Condition
{

    @JsonProperty("all")
    @Nullable
    private List<Condition> all;

    @JsonProperty("any")
    @Nullable
    private List<Condition> any;

    @JsonProperty("not")
    @Nullable
    private Condition not;

    @JsonProperty("path")
    @Nullable
    private String path;

    @JsonProperty("equals")
    @Nullable
    private String equalsValue;

    @JsonProperty("oneOf")
    @Nullable
    private List<String> oneOf;

    @JsonProperty("exists")
    @Nullable
    private Boolean exists;

    @JsonProperty("matchesRegex")
    @Nullable
    private String matchesRegex;

    /** Compiled {@link #matchesRegex}, built once in {@link #validate()}. */
    @Nullable
    private Pattern matchesRegexPattern;

    /**
     * Value equality over the eight authored fields.
     *
     * <p>
     * A {@code when:} guard is a parsed value: two conditions with the same content are the same
     * condition. Without this, {@link ConformanceRule} — a record, and so advertising structural
     * equality — silently fell back to identity for every rule carrying a {@code when}, which is
     * most of the interesting ones. Nothing noticed until the corpus was packaged and a rule loaded
     * from JSON had to be compared against the same rule loaded from YAML.
     * </p>
     *
     * <p>
     * {@code matchesRegexPattern} is deliberately excluded: it is derived from {@code matchesRegex}
     * in {@link #validate()}, and {@link Pattern} itself has no value equality, so including it
     * would reintroduce the identity comparison this fixes.
     * </p>
     */
    @Override
    public boolean equals(@Nullable Object aOther)
    {
        if (this == aOther)
        {
            return true;
        }
        if (!(aOther instanceof Condition other))
        {
            return false;
        }
        return Objects.equals(all, other.all) && Objects.equals(any, other.any)
                && Objects.equals(not, other.not) && Objects.equals(path, other.path)
                && Objects.equals(equalsValue, other.equalsValue)
                && Objects.equals(oneOf, other.oneOf) && Objects.equals(exists, other.exists)
                && Objects.equals(matchesRegex, other.matchesRegex);
    }


    @Override
    public int hashCode()
    {
        return Objects.hash(all, any, not, path, equalsValue, oneOf, exists, matchesRegex);
    }


    /**
     * Structural validation, called once at rule-load time; throws {@link IllegalStateException}
     * naming the defect so a malformed rule file fails fast instead of mis-evaluating.
     */
    public void validate()
    {
        int forms = (all != null ? 1 : 0) + (any != null ? 1 : 0) + (not != null ? 1 : 0)
                + (path != null ? 1 : 0);
        if (forms != 1)
        {
            throw new IllegalStateException(
                    "a when-condition must be exactly one of all/any/not/path, got " + forms
                            + " forms");
        }
        if (path != null)
        {
            int predicates = (equalsValue != null ? 1 : 0) + (oneOf != null ? 1 : 0)
                    + (exists != null ? 1 : 0) + (matchesRegex != null ? 1 : 0);
            if (predicates != 1)
            {
                throw new IllegalStateException(
                        "clause for path '" + path + "' must have exactly one of "
                                + "equals/oneOf/exists/matchesRegex, got " + predicates);
            }
            if (matchesRegex != null)
            {
                try
                {
                    matchesRegexPattern = Pattern.compile(matchesRegex);
                }
                catch (PatternSyntaxException e)
                {
                    throw new IllegalStateException(
                            "matchesRegex does not compile: " + e.getMessage(), e);
                }
            }
        }
        else if (equalsValue != null || oneOf != null || exists != null || matchesRegex != null)
        {
            throw new IllegalStateException(
                    "equals/oneOf/exists/matchesRegex are only valid together with a path");
        }
        if (all != null)
        {
            all.forEach(Condition::validate);
        }
        if (any != null)
        {
            any.forEach(Condition::validate);
        }
        if (not != null)
        {
            not.validate();
        }
    }


    /** Evaluates this condition from the given context node (no deref support). */
    public boolean matches(ElementNode aContext)
    {
        return matches(aContext, null);
    }


    /**
     * Deref-aware evaluation: with a resolver, clause paths may use the {@code @Attr->Element@Key}
     * dereference segment (plan §3.35 — e.g. a guard on the referenced {@code def:Standard}'s
     * Name). A deref path without a resolver fails loudly.
     */
    public boolean matches(ElementNode aContext, @Nullable OidResolver aResolver)
    {
        if (all != null)
        {
            return all.stream().allMatch(c -> c.matches(aContext, aResolver));
        }
        if (any != null)
        {
            return any.stream().anyMatch(c -> c.matches(aContext, aResolver));
        }
        if (not != null)
        {
            return !not.matches(aContext, aResolver);
        }
        return matchesClause(aContext, aResolver);
    }


    private boolean matchesClause(ElementNode aContext, @Nullable OidResolver aResolver)
    {
        // validate() guarantees path != null with exactly one predicate here.
        String clausePath = path;
        if (clausePath == null)
        {
            throw new IllegalStateException("clause without path — validate() not called?");
        }
        if (exists != null)
        {
            String lastStep = clausePath.substring(clausePath.lastIndexOf('/') + 1);
            // Attribute existence mirrors the check kinds' presence semantics: a
            // present-but-blank attribute counts as missing.
            boolean present = (lastStep.startsWith("@") || clausePath.contains("->"))
                    ? resolve(aContext, clausePath, aResolver).stream().anyMatch(v -> !v.isBlank())
                    : !PathResolver.nodes(aContext, clausePath).isEmpty();
            return exists == present;
        }
        List<String> values = resolve(aContext, clausePath, aResolver);
        if (equalsValue != null)
        {
            return values.contains(equalsValue);
        }
        Pattern pattern = matchesRegexPattern;
        if (pattern != null)
        {
            return values.stream().anyMatch(v -> pattern.matcher(v).matches());
        }
        List<String> allowed = oneOf;
        if (allowed == null)
        {
            throw new IllegalStateException("clause without predicate — validate() not called?");
        }
        return values.stream().anyMatch(allowed::contains);
    }


    private static List<String> resolve(ElementNode aContext, String aPath,
            @Nullable OidResolver aResolver)
    {
        if (!aPath.contains("->"))
        {
            return PathResolver.values(aContext, aPath);
        }
        if (aResolver == null)
        {
            throw new IllegalStateException(
                    "deref path '" + aPath + "' needs an OidResolver-backed evaluation");
        }
        return PathResolver.valuesWithDeref(aContext, aPath, aResolver);
    }

}
