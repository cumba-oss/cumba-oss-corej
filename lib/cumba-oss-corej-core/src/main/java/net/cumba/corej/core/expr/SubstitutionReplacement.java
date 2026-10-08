package net.cumba.corej.core.expr;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The load-time check of a substitution literal's replacement against its pattern
 * ({@code PLAN-engine-gap-string-extract} §3 "load errors", runbook W1b SX, coordinator decision
 * D12): every {@code $n} must name a group the pattern defines ({@code $0} the whole match), every
 * {@code ${name}} a named group, a backslash must escape a following character and a {@code $} must
 * start a group reference — exactly the rules {@code java.util.regex.Matcher.appendReplacement}
 * applies per row, read once here so a defective replacement is a load error instead of a per-row
 * {@code IllegalArgumentException} / {@code IndexOutOfBoundsException} at evaluation.
 *
 * <p>
 * The numbered form follows {@code Matcher}'s greedy reading: after the first digit, further digits
 * are taken while the number stays within the pattern's group count — {@code $11} with eleven
 * groups is group 11, with one group it is group 1 followed by a literal {@code 1}.
 * </p>
 */
public final class SubstitutionReplacement
{

    private SubstitutionReplacement()
    {
    }


    /**
     * The problem with {@code replacement} against {@code pattern}, or {@code null} when every
     * group reference resolves and every escape is complete.
     *
     * @param pattern
     *            the compiled pattern of the literal
     * @param replacement
     *            the replacement text as the lexer decoded it
     * @return a message naming the defect, or {@code null}
     */
    public static @Nullable String problem(Pattern pattern, String replacement)
    {
        int groups = pattern.matcher("").groupCount();
        int i = 0;
        int n = replacement.length();
        while (i < n)
        {
            char c = replacement.charAt(i);
            if (c == '\\')
            {
                if (i + 1 >= n)
                {
                    return "the replacement ends with a dangling backslash";
                }
                i += 2;
                continue;
            }
            if (c != '$')
            {
                i++;
                continue;
            }
            // a group reference
            if (i + 1 >= n)
            {
                return "the replacement has a bare '$' (write \\$ for a literal dollar)";
            }
            char next = replacement.charAt(i + 1);
            if (next == '{')
            {
                int close = replacement.indexOf('}', i + 2);
                if (close < 0)
                {
                    return "the replacement's '${' is not closed";
                }
                String name = replacement.substring(i + 2, close);
                if (!pattern.namedGroups().containsKey(name))
                {
                    return "the replacement refers to ${" + name
                            + "} but the pattern defines no group of that name";
                }
                i = close + 1;
                continue;
            }
            if (next < '0' || next > '9')
            {
                return "the replacement has a '$' that starts no group reference (write \\$ for"
                        + " a literal dollar)";
            }
            int number = next - '0';
            int j = i + 2;
            if (number > groups)
            {
                return "the replacement refers to $" + number + " but the pattern defines " + groups
                        + " group" + (groups == 1 ? "" : "s");
            }
            while (j < n)
            {
                char d = replacement.charAt(j);
                if (d < '0' || d > '9')
                {
                    break;
                }
                int wider = number * 10 + d - '0';
                if (wider > groups)
                {
                    break;
                }
                number = wider;
                j++;
            }
            i = j;
        }
        return null;
    }
}
