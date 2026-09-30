package net.cumba.corej.core.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The single definition of the declared expansion-token grammar: a token is {@code &NAME&} with
 * {@code NAME = [A-Z][A-Z0-9]*}, the closing {@code &} mandatory ({@code &DOM&}, {@code &VAR&};
 * {@code &DOM&SEQ} is the token {@code &DOM&} followed by the literal {@code SEQ}).
 *
 * <p>
 * Everything that has an opinion about where a token starts and ends reads it from here — the
 * {@link ExpressionLexer} (a bare token inside an identifier), the loader's gates G1–G3
 * ({@code RulePackageLoader}), the substitution ({@code TokenExpander.substitute}) and the
 * post-expansion survivor check. Four sites each with their own notion of "token" is the drift
 * class the Python port of the lexer already shows; one scanner is the fix.
 * </p>
 *
 * <h2>Reading order (S1)</h2>
 *
 * <p>
 * The scan reads in the lexer's order. At each {@code &}: (1) a complete token
 * {@code &[A-Z][A-Z0-9]*&} if one starts here; else (2) if the next character is {@code &}, the
 * pair is the boolean operator {@code &&} and is consumed; else (3) a <em>stray</em> when the next
 * character is a letter. Overlapping occurrences share a delimiter, so in {@code &A&B&C&} the text
 * {@code &B&} also occurs at index 2 — a left-to-right scan reads {@code &A&}, {@code B},
 * {@code &C&}, which is what the lexer reads. Without step (2) the scan would find a "token"
 * {@code &B&} inside the valid operator text {@code A&&B&&C}.
 * </p>
 *
 * <h2>Adjacency (S3)</h2>
 *
 * <p>
 * A token directly followed by {@code &} ({@code &A&&B&}, {@code &A&&&COL}) and a token directly
 * after {@code &&} ({@code COL&&&A&}, {@code )&&&A&}) are reported as adjacency violations: nobody
 * can read either, so the author separates the token from the operator by a space. A letter between
 * two tokens ({@code &A&B&C&}) is legal.
 * </p>
 *
 * <p>
 * The NAME predicates are ASCII range checks, never {@link Character#isUpperCase} — a non-ASCII
 * upper-case letter is not a token character.
 * </p>
 */
public final class ExpansionTokens
{

    /** The whole-string form of a declared token. */
    public static final Pattern TOKEN = Pattern.compile("^&[A-Z][A-Z0-9]*&$");

    /** The S3 message, shared by the lexer and the loader gate. */
    public static final String ADJACENCY_MESSAGE = "separate an expansion token from '&' / '&&'"
            + " by a space";

    private ExpansionTokens()
    {
    }

    /**
     * One complete token found by {@link #scan}.
     *
     * @param start
     *            index of the opening {@code &}
     * @param end
     *            index one past the closing {@code &}
     * @param text
     *            the token text, {@code &NAME&}
     */
    public record Occurrence(int start, int end, String text)
    {
    }


    /**
     * A stray {@code &} followed by a letter that starts no complete token and is not part of the
     * {@code &&} operator — the old undelimited spelling ({@code &DOM}), a lower-case name
     * ({@code &dom&}) or a name with a character outside {@code [A-Z0-9]}.
     *
     * @param start
     *            index of the {@code &}
     * @param text
     *            the {@code &} plus the letter/digit/underscore run that follows it
     */
    public record Stray(int start, String text)
    {
    }


    /**
     * An S3 adjacency violation.
     *
     * @param start
     *            the index of the offending {@code &}
     * @param text
     *            the text around it, for the message
     */
    public record Adjacency(int start, String text)
    {
    }


    /**
     * The result of one {@link #scan}.
     *
     * @param occurrences
     *            every complete token, left to right, non-overlapping
     * @param strays
     *            every stray (see {@link Stray})
     * @param adjacencies
     *            every adjacency violation (see {@link Adjacency})
     */
    public record Scan(List<Occurrence> occurrences, List<Stray> strays,
            List<Adjacency> adjacencies)
    {

        /**
         * All three components are copied to immutable lists: a record over a mutable collection is
         * a value in name only, and SpotBugs says so ({@code EI_EXPOSE_REP} /
         * {@code EI_EXPOSE_REP2}, two findings per component).
         */
        public Scan
        {
            occurrences = List.copyOf(occurrences);
            strays = List.copyOf(strays);
            adjacencies = List.copyOf(adjacencies);
        }


        /** Whether the text carries no stray and no adjacency violation. */
        public boolean isClean()
        {
            return strays.isEmpty() && adjacencies.isEmpty();
        }
    }

    /** Whether {@code c} may start a token NAME: ASCII {@code A}–{@code Z}. */
    public static boolean isNameStart(char c)
    {
        return c >= 'A' && c <= 'Z';
    }


    /**
     * Whether {@code c} may continue a token NAME: ASCII {@code A}–{@code Z}, {@code 0}–{@code 9}.
     */
    public static boolean isNamePart(char c)
    {
        return isNameStart(c) || (c >= '0' && c <= '9');
    }


    /**
     * The index one past the closing {@code &} of the complete token starting at {@code at}, or
     * {@code -1} when no complete token starts there.
     *
     * @param text
     *            the text
     * @param at
     *            the index of a supposed opening {@code &}
     * @return the end index (exclusive), or {@code -1}
     */
    public static int tokenEndAt(CharSequence text, int at)
    {
        int n = text.length();
        if (at < 0 || at >= n || text.charAt(at) != '&' || at + 1 >= n
                || !isNameStart(text.charAt(at + 1)))
        {
            return -1;
        }
        int i = at + 2;
        while (i < n && isNamePart(text.charAt(i)))
        {
            i++;
        }
        return i < n && text.charAt(i) == '&' ? i + 1 : -1;
    }


    /**
     * Scans {@code text} in the lexer's order (class javadoc).
     *
     * @param text
     *            the text to scan; {@code null} scans as empty
     * @return the occurrences, strays and adjacency violations
     */
    public static Scan scan(String text)
    {
        List<Occurrence> occurrences = new ArrayList<>();
        List<Stray> strays = new ArrayList<>();
        List<Adjacency> adjacencies = new ArrayList<>();
        if (text == null || text.indexOf('&') < 0)
        {
            return new Scan(List.of(), List.of(), List.of());
        }
        int n = text.length();
        int i = 0;
        while (i < n)
        {
            if (text.charAt(i) != '&')
            {
                i++;
                continue;
            }
            int end = tokenEndAt(text, i);
            if (end >= 0)
            {
                occurrences.add(new Occurrence(i, end, text.substring(i, end)));
                if (end < n && text.charAt(end) == '&')
                {
                    // A token directly followed by '&': `&A&&B&`, `&A&&&COL`.
                    adjacencies.add(new Adjacency(end, around(text, i, end + 1)));
                }
                i = end;
                continue;
            }
            if (i + 1 < n && text.charAt(i + 1) == '&')
            {
                // The operator. A token starting right behind it is the other adjacency.
                if (tokenEndAt(text, i + 2) >= 0)
                {
                    adjacencies.add(new Adjacency(i + 2, around(text, i, tokenEndAt(text, i + 2))));
                }
                i += 2;
                continue;
            }
            if (i + 1 < n && Character.isLetter(text.charAt(i + 1)))
            {
                strays.add(new Stray(i, strayTextAt(text, i)));
            }
            i++;
        }
        return new Scan(occurrences, strays, adjacencies);
    }


    /**
     * The {@code &} at {@code at} plus the run of letters, digits and underscores that follows it —
     * the text a message names for a stray ({@code &DOM}, {@code &dom}, {@code &V_1}).
     *
     * @param text
     *            the text
     * @param at
     *            index of the {@code &}
     * @return the stray text
     */
    public static String strayTextAt(CharSequence text, int at)
    {
        int i = at + 1;
        while (i < text.length()
                && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_'))
        {
            i++;
        }
        return text.subSequence(at, i).toString();
    }


    /**
     * The message for a stray whose text is {@code stray} (as {@link #strayTextAt} returns it): a
     * name character outside {@code [A-Z][A-Z0-9]*} is named; otherwise the token is unterminated.
     * The lexer's form, which names no surface — the lexer only ever reads a Check.
     *
     * @param stray
     *            the stray text, starting with {@code &}
     * @return the message
     */
    public static String strayMessage(String stray)
    {
        return strayMessage(stray, null);
    }


    /**
     * {@link #strayMessage(String)} naming the surface the stray was found on, for the loader gate
     * G3: {@code unterminated expansion token '&DOM' in Outcome.Output_Variables (a token is …)},
     * {@code invalid expansion token '&dom' in Check: expansion token names are upper case (…),
     * found 'd'}.
     *
     * @param stray
     *            the stray text, starting with {@code &}
     * @param where
     *            the surface ({@code ExpansionSurfaces.Surface#where}), or {@code null} for none
     * @return the message
     */
    public static String strayMessage(String stray, @Nullable String where)
    {
        String at = where == null ? "" : " in " + where;
        String name = stray.length() > 1 ? stray.substring(1) : "";
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            boolean ok = i == 0 ? isNameStart(c) : isNamePart(c);
            if (!ok)
            {
                return "invalid expansion token '" + stray + "'" + at + ": expansion token names"
                        + " are upper case (a token is &NAME& with NAME = [A-Z][A-Z0-9]*), found '"
                        + c + "'";
            }
        }
        return "unterminated expansion token '" + stray + "'" + at + " (a token is &NAME& with"
                + " NAME = [A-Z][A-Z0-9]*; or did you mean the operator '&&'?)";
    }


    private static String around(String text, int from, int to)
    {
        return text.substring(Math.max(0, from), Math.min(text.length(), to));
    }

}
