package net.cumba.corej.core.exec;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * The exact-decimal canonical text of a numeric join-key token — the ONE rule behind the numeric
 * arm of the two text-carried foreign-key joins: {@code RelrecRowExpander.normKey} (the RELREC
 * {@code IDVAR} value on both the record-level {@code IDVAR == IDVARVAL} filter and the
 * dataset-level equi-join) and {@link ChildMatchIndex#normalizeJoinToken} (the SUPP-- / CO / RELREC
 * {@code Child} join token). Ruled by PLAN-relrec-idvar-key-precision T1-1 (a), owner 2026-09-25:
 * <i>"One lossless rule for both sites"</i>. ⚠ This class decides only what a <b>numeric</b> token
 * canonicalises to; what each caller does with a token that is <b>not</b> a number (the RELREC site
 * keeps the unstripped text, the Child site keeps it stripped) is that caller's rule, unchanged
 * here, and the difference is on record in the plan's open questions.
 *
 * <p>
 * A token spelt with ASCII digits, an optional sign, an optional fraction and an optional exponent
 * ({@link BigDecimal#BigDecimal(String)}'s decimal grammar, nothing else — no {@code d}/{@code f}
 * suffix, no hexadecimal float, no {@code NaN}/{@code Infinity}, no non-ASCII digit; all of which
 * pass through as text) canonicalises to its exact value with the trailing fractional zeros
 * removed, in plain notation: {@code "1"}, {@code "1.0"}, {@code "01"} and {@code "1.00"} are
 * {@code "1"}; {@code "5.0E-4"} is {@code "0.0005"}; {@code "1e20"} is
 * {@code "100000000000000000000"}; {@code "-0"} is {@code "0"}. Every digit is kept, which is why
 * this replaced {@code Double.parseDouble}: {@code 9007199254740993} and {@code 9007199254740992}
 * are one {@code double} and used to be one key ({@code RRK E2}: two different values must not
 * share a key). So {@code "1"}, {@code "1.0"} and a numeric {@code 1} still join ({@code D4-R5}:
 * these families are text joins with their own coercion), and the canonical text of a DOUBLE cell's
 * own text ({@code DataValueSupport.toCleanText} — plain, lossless) is that text again, so a cell
 * and an authored {@code IDVARVAL} meet in one form.
 * </p>
 *
 * <p>
 * ⚠ Beyond a {@code |scale|} of {@link #MAX_PLAIN_SCALE} the canonical text is
 * {@link BigDecimal#toString()} (scientific) instead of plain: {@code "1E999999999"},
 * {@code "1E-999999999"} or {@code "1E2147483647"} in one cell would otherwise build a string of up
 * to {@code 2^31} characters and kill the run. The bound lies past the {@code double} range, so no
 * value the old coercion parsed finitely is affected, and the mapping stays injective on the
 * stripped value ({@code RRK E2}): both arms render the EXACT decimal value — the plain arm always,
 * the scientific arm as {@code toString}, which is E-free itself when the precision exceeds the
 * scale by more than about six and is then the same exact decimal — so two different stripped
 * values never share a text, whichever arm each takes. An exponent {@code BigDecimal} itself cannot
 * hold — while parsing ({@code "1E-2147483648"}) or while stripping the zeros
 * ({@code "10000E2147483645"}) — is text.
 * </p>
 */
final class NumericKeyText
{

    /**
     * Beyond this {@code |scale|} the canonical text is scientific, never plain — and a canonical
     * integer longer than this takes the {@link BigDecimal} arm too, so that every spelling of one
     * value lands in the same arm.
     */
    static final int MAX_PLAIN_SCALE = 400;

    private NumericKeyText()
    {
    }


    /**
     * Canonicalises one join-key token.
     *
     * @param token
     *            the token, already stripped of surrounding whitespace.
     * @return its canonical decimal text, or {@code null} when it is not a decimal number.
     */
    static @Nullable String canonicalOrNull(String token)
    {
        if (token.length() <= MAX_PLAIN_SCALE && isCanonicalInteger(token))
        {
            // The common case (--SEQ values): already canonical, no BigDecimal. Bounded by the
            // plain limit, so an integer with more than MAX_PLAIN_SCALE trailing zeros takes the
            // scientific arm like every other spelling of its value ("1E401" and "1" followed by
            // 401 zeros are one key).
            return token;
        }
        if (!isDecimalSpelling(token))
        {
            // A text id with any character outside [0-9+-.eE] ("L1", "AE-001") never reaches the
            // parser; one that is spelt only of those ("1-2") still throws once per row -- an
            // accepted cost, such ids are rare.
            return null;
        }
        try
        {
            BigDecimal bd = new BigDecimal(token).stripTrailingZeros();
            return bd.scale() > MAX_PLAIN_SCALE || bd.scale() < -MAX_PLAIN_SCALE ? bd.toString()
                    : bd.toPlainString();
        }
        catch (NumberFormatException | ArithmeticException _)
        {
            return null; // not a decimal number, or an exponent BigDecimal cannot represent
        }
    }


    /**
     * ASCII digits, {@code +}, {@code -}, {@code .}, {@code e} and {@code E} only, at least one
     * character. A superset of the decimal grammar (the parser still decides), a strict subset of
     * what {@link BigDecimal} would accept: it also takes non-ASCII digits such as fullwidth
     * {@code "１２"}, which are not a decimal spelling of a key.
     */
    static boolean isDecimalSpelling(String token)
    {
        int n = token.length();
        if (n == 0)
        {
            return false;
        }
        for (int j = 0; j < n; j++)
        {
            char c = token.charAt(j);
            if ((c < '0' || c > '9') && c != '+' && c != '-' && c != '.' && c != 'e' && c != 'E')
            {
                return false;
            }
        }
        return true;
    }


    /**
     * An optional {@code -}, then ASCII digits with no leading zero — or {@code "0"} alone.
     * Everything else that might still be a number ({@code "-0"}, {@code "01"}, a fraction, an
     * exponent) takes the {@link BigDecimal} path, which yields the same text this fast path
     * returns unchanged.
     */
    private static boolean isCanonicalInteger(String token)
    {
        int n = token.length();
        int i = n > 0 && token.charAt(0) == '-' ? 1 : 0;
        if (i >= n)
        {
            return false;
        }
        if (token.charAt(i) == '0')
        {
            return n == 1;
        }
        for (int j = i; j < n; j++)
        {
            char c = token.charAt(j);
            if (c < '0' || c > '9')
            {
                return false;
            }
        }
        return true;
    }
}
