package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Locale;
import net.cumba.corej.core.model.MatchDataset;

/**
 * The join-key <b>type</b> axis: who is subject to it, and what the answer is
 * ({@code PLAN-join-key-type-identity}, rulings {@code D4-R1}/{@code D4-R5}).
 *
 * <h2>⭐⭐ Why this class exists at all</h2>
 *
 * <p>
 * The exclusion predicate below was, until 2026-09-22, a private {@code if} inside
 * {@link KeyMatchRowExpander#expandableEntries}. It is now shared, because the plan's own review
 * round caught its transcription into a second place having already <b>dropped a clause</b>
 * ({@code name == null}) — proving the warning it was written under: <i>"two copies of a
 * five-clause predicate is how the sixth clause gets added to one of them."</i> ⇒ every caller asks
 * <b>this</b> method; nobody restates it.
 * </p>
 *
 * <h2>⛔⛔ What the exclusion protects, and why it is not an optimisation</h2>
 *
 * <p>
 * {@code Child:true} / {@code RELREC} / {@code SUPP--} entries join a <b>text-carried foreign
 * key</b> against a typed column <b>by design</b>: {@code IDVARVAL} is {@code Char} in all 36
 * CDISC-library datasets that define it, and it is matched against the value of the variable
 * <em>named</em> by {@code IDVAR} — routinely a {@code --SEQ}, i.e. {@code Num}. Their coercions
 * are ruled elsewhere and already implemented ({@code JKM R6} —
 * {@code ChildMatchIndex.normalizeJoinToken} coerces the child token to the parent's type; and
 * {@code RelrecRowExpander.normKey} float-canonicalises).
 * </p>
 *
 * <p>
 * Measured 2026-09-22: <b>11 keyed entries in 5 rule files</b> are excluded — {@code CDISC-CG0043},
 * {@code FDA-SD1143}, {@code CDISC-CG0371}, {@code FDA-SD0077}, {@code PMDA-SD0077} — and all
 * eleven key on {@code [USUBJID, IDVAR, IDVARVAL]}. Applying {@code D4-R1} to them would error
 * eleven working, correctly-authored rules.
 * </p>
 *
 * <h2>⚑ Where the check actually runs</h2>
 *
 * <p>
 * In {@link KeyMatchRowExpander#keySpec} — <b>not</b> in {@code RuleRunner.buildJoinedDatasets},
 * which is the intuitive site and the wrong one: {@code RuleRunner:918-920} does
 * {@code removeAll(keyExpansion.expandedEntries())} <b>before</b> calling it, so every entry this
 * axis governs has already been removed by then. A check sited there would fire on nothing and
 * green every gate — a silent no-op. Proven exhaustively over all 214 corpus entries: 196
 * expandable, 11 excluded-and-keyed, 7 keyless, 0 violations of that invariant.
 * </p>
 */
public final class JoinKeyTypes
{

    private JoinKeyTypes()
    {
    }


    /**
     * Whether this entry is exempt from the join-key type identity of {@code D4-R1}/{@code D4-R2}.
     *
     * <p>
     * ⚠⚠ This is the <b>single</b> definition of that predicate. It is byte-equivalent to the test
     * {@link KeyMatchRowExpander#expandableEntries} applied inline before 2026-09-22 — including
     * the {@code name == null} clause, which is the one a hand transcription dropped.
     * </p>
     *
     * @param aMatch
     *            the {@code Match_Datasets} entry, never {@code null}.
     * @return {@code true} when the entry's keys must NOT be type-checked.
     */
    public static boolean excludedFromKeyTypeCheck(MatchDataset aMatch)
    {
        String name = aMatch.getName();
        return name == null || Boolean.TRUE.equals(aMatch.getChild())
                || "RELREC".equalsIgnoreCase(name) || name.contains("--")
                || isSuppOrQualifier(name);
    }


    /**
     * Whether the join-key type identity of {@code D4-R1}/{@code D4-R2} — and therefore
     * {@code Join_As_String} — applies to this entry at all.
     *
     * <p>
     * ⭐⭐ <b>This is the predicate, and it has TWO halves.</b> An entry is governed only when it is
     * not an excluded family <b>and</b> it actually carries keys: the flag is read at exactly one
     * site ({@code KeyMatchRowExpander.keySpec}), reached only through
     * {@link KeyMatchRowExpander#expandableEntries}, which requires both. ⚠ The loader's no-effect
     * guard originally asked only {@link #excludedFromKeyTypeCheck}, so
     * {@code Join_As_String: true} on a <b>keyless</b> entry loaded clean and did nothing — the
     * silent no-op that guard exists to prevent, in the guard itself. The corpus carries 7 keyless
     * entries, so the shape is real.
     * </p>
     *
     * @param aMatch
     *            the {@code Match_Datasets} entry, never {@code null}.
     * @return whether the entry's keys are subject to the type check.
     */
    public static boolean governedByKeyTypeCheck(MatchDataset aMatch)
    {
        List<String> keys = aMatch.getKeys();
        return !excludedFromKeyTypeCheck(aMatch) && keys != null && !keys.isEmpty();
    }


    /**
     * SUPP / SQ qualifier datasets, which Python merges by pivot rather than a plain key merge.
     *
     * @param aName
     *            the dataset name, never {@code null}.
     * @return whether it names a supplemental-qualifier dataset.
     */
    private static boolean isSuppOrQualifier(String aName)
    {
        String upper = aName.toUpperCase(Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQ");
    }

}
