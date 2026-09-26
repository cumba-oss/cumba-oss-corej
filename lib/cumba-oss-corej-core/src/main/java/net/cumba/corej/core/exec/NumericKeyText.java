package net.cumba.corej.core.exec;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * The exact-decimal canonical text of a numeric join-key token — the ONE rule behind the two
 * text-carried foreign-key joins: {@code RelrecRowExpander.normKey} (the RELREC {@code IDVAR} value
 * on both the record-level {@code IDVAR == IDVARVAL} filter and the dataset-level equi-join) and
 * {@link ChildMatchIndex#normalizeJoinToken} (the SUPP-- / CO / RELREC {@code Child} join token).
 * Ruled by PLAN-relrec-idvar-key-precision T1-1 (a), owner 2026-09-25: <i>"One lossless rule for
 * both sites"</i>.
 *
 * <p>
 * A token that parses as a decimal number ({@link BigDecimal#BigDecimal(String)}: an optional sign,
 * digits, an optional fraction, an optional exponent) canonicalises to its exact value with the
 * trailing fractional zeros removed, in plain notation: {@code "1"}, {@code "1.0"}, {@code "01"}
 * and {@code "1.00"} are {@code "1"}; {@code "5.0E-4"} is {@code "0.0005"}; {@code "1e20"} is
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
 * ⚠ Only the decimal spellings count as numeric. {@code Double.parseDouble} also accepted a
 * {@code d}/{@code f} suffix ({@code "1d"}), hexadecimal floats and {@code NaN}/{@code Infinity};
 * none of these is a clinical key value, and they now pass through as text.
 * </p>
 */
final class NumericKeyText
{

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
        if (isCanonicalInteger(token))
        {
            return token; // the common case (--SEQ values): already canonical, no BigDecimal
        }
        try
        {
            return new BigDecimal(token).stripTrailingZeros().toPlainString();
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }


    /**
     * An optional {@code -}, then digits with no leading zero — or {@code "0"} alone. Everything
     * else that might still be a number ({@code "-0"}, {@code "01"}, a fraction, an exponent) takes
     * the {@link BigDecimal} path, which yields the same text this fast path returns unchanged.
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
