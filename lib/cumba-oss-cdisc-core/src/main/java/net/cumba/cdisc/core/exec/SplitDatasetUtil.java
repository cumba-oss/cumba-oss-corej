package net.cumba.cdisc.core.exec;

/**
 * Shared helper for split-dataset name detection and canonicalization.
 * <p>
 * SDTM splits datasets either by trailing digits ({@code LB1}, {@code LB2}) or — less commonly for
 * the SUPP-- family — by a trailing letter identifying the paired parent domain split
 * ({@code SUPPLBHM} = SUPP tied to LBHM). Both Fix #1 (dedup across split datasets during the
 * study-wide variable-count) and Fix #12 ({@link #isSplitDataset(String)}) must agree on the
 * canonical unsplit name of a dataset; routing both through a single helper prevents divergence.
 * <p>
 * This is the <em>name-pattern</em> heuristic, used for table-less callers and as a fallback. The
 * authoritative, data-driven split key (mirroring Python's
 * {@code SDTMDatasetMetadata.unsplit_name}, read from the {@code DOMAIN}/{@code RDOMAIN} columns)
 * is {@link OperationExecutor#unsplitNameFromData}; scope matching prefers that and only falls back
 * here when no dataset is available.
 * </p>
 */
public final class SplitDatasetUtil
{

    private SplitDatasetUtil()
    {
    }


    /**
     * Returns the canonical unsplit (base) name of a dataset. Strips trailing digits (e.g.,
     * {@code "LB1"} → {@code "LB"}, {@code "SUPPDM2"} → {@code "SUPPDM"}). For SDTM SUPP/AP
     * letter-suffix splits (e.g., {@code "SUPPLBHM"}, {@code "APFACM"}) strips the trailing letter.
     * Returns the name unchanged when it does not match a recognised split pattern.
     * <p>
     * The supported patterns mirror what {@link #isSplitDataset(String)} recognises as a split, so
     * {@code unsplitName} always returns a shorter string exactly when {@code isSplitDataset}
     * returns {@code true}.
     * </p>
     *
     * @param name
     *            the dataset name (may be {@code null})
     * @return the base/unsplit name, or {@code name} unchanged when it is not a split
     */
    public static String unsplitName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return name;
        }
        int digitStart = trailingDigitStart(name);
        int digitCount = name.length() - digitStart;
        if (digitCount >= 1 && digitCount <= 2)
        {
            String base = name.substring(0, digitStart);
            if (base.length() >= 2 && isAllUpperLetters(base))
            {
                return base;
            }
        }
        if (isSuppLetterSplit(name) || isApLetterSplit(name))
        {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }


    /**
     * Returns {@code true} when the name looks like a split dataset. Recognises SDTM digit-suffix
     * splits (e.g., {@code "LB1"}, {@code "SUPPDM2"}, {@code "APMH1"}) and SUPP/AP letter-suffix
     * splits (e.g., {@code "SUPPLBHM"}, {@code "APFACM"}).
     *
     * @param name
     *            the dataset name (may be {@code null})
     * @return true if the name matches a recognised split pattern
     */
    public static boolean isSplitDataset(String name)
    {
        if (name == null || name.length() < 3)
        {
            return false;
        }
        int digitStart = trailingDigitStart(name);
        int digitCount = name.length() - digitStart;
        if (digitCount >= 1 && digitCount <= 2)
        {
            String base = name.substring(0, digitStart);
            return base.length() >= 2 && isAllUpperLetters(base);
        }
        return isSuppLetterSplit(name) || isApLetterSplit(name);
    }


    /**
     * SUPP letter-suffix split: {@code SUPP} + 2-to-6 letter base + 1 suffix letter (total length
     * 7-11, all uppercase ASCII letters). E.g. {@code SUPPLBHM} (SUPP+LBH+M), {@code SUPPFACM}
     * (SUPP+FAC+M), {@code SUPPAEX} (SUPP+AE+X).
     */
    private static boolean isSuppLetterSplit(String name)
    {
        int len = name.length();
        if (len < 7 || len > 11)
        {
            return false;
        }
        if (!name.startsWith("SUPP"))
        {
            return false;
        }
        return isAllUpperLetters(name);
    }


    /**
     * AP letter-suffix split: {@code AP} + 2-to-4 letter base + 1 suffix letter (total length 5-7,
     * all uppercase ASCII letters). E.g. {@code APFAC}, {@code APFACM}.
     */
    private static boolean isApLetterSplit(String name)
    {
        int len = name.length();
        if (len < 5 || len > 7)
        {
            return false;
        }
        if (!name.startsWith("AP"))
        {
            return false;
        }
        return isAllUpperLetters(name);
    }


    private static int trailingDigitStart(String name)
    {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
        {
            i--;
        }
        return i;
    }


    private static boolean isAllUpperLetters(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z')
            {
                return false;
            }
        }
        return true;
    }

}
