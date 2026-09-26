package net.cumba.corej.core.model;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The <i>did you mean</i> hint of the unknown-key load errors ({@code PLAN-rule-unknown-keys-gate},
 * T1-3 a; review E2 / E3): one place, used by every gate that reports an unbound key — the loader's
 * walker, R2, the {@code Match_Datasets} gate, the package arms and the {@code Check} grammar.
 *
 * <p>
 * A near miss is a bound key with the same spelling ignoring case ({@code outcome} →
 * {@code Outcome}) or one edit away ignoring case — one character dropped, added, changed or
 * transposed ({@code Outcom}, {@code Mesage}, {@code Versoin}). Two candidates give no hint rather
 * than a wrong one, and a candidate the same object <b>already carries</b> is never offered
 * ({@code {"left": …, "lfet": …}} does not hint {@code left}; the author has it).
 * </p>
 */
public final class KeyHint
{

    private KeyHint()
    {
    }


    /**
     * The one bound key {@code key} is a near miss of, or {@code null} when none or several are.
     *
     * @param key
     *            the unknown key
     * @param bound
     *            the keys the block binds (its roster)
     * @param present
     *            the bound keys the same object already carries — never hinted
     * @param excluded
     *            bound keys never to hint (retired spellings, the loader's own identity key)
     * @return the hinted spelling, or {@code null}
     */
    public static @Nullable String nearest(String key, Set<String> bound, Set<String> present,
            Set<String> excluded)
    {
        String found = null;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String candidate : bound)
        {
            if (excluded.contains(candidate) || present.contains(candidate))
            {
                continue;
            }
            if (editDistance(lower, candidate.toLowerCase(Locale.ROOT)) <= 1)
            {
                if (found != null)
                {
                    return null;
                }
                found = candidate;
            }
        }
        return found;
    }


    /** The {@code ; did you mean 'X'?} clause, or the empty string when there is no hint. */
    public static String clause(String key, Set<String> bound, Set<String> present,
            Set<String> excluded)
    {
        String hint = nearest(key, bound, present, excluded);
        return hint == null ? "" : "; did you mean '" + hint + "'?";
    }


    /** Damerau–Levenshtein distance (optimal string alignment), capped at 2. */
    public static int editDistance(String a, String b)
    {
        if (Math.abs(a.length() - b.length()) > 1)
        {
            return 2;
        }
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++)
        {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++)
        {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            for (int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int best = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1))
                {
                    best = Math.min(best, d[i - 2][j - 2] + 1);
                }
                d[i][j] = best;
            }
        }
        return Math.min(d[a.length()][b.length()], 2);
    }
}
