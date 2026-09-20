package net.cumba.corej.core.expr.typed;

import java.util.LinkedHashSet;
import java.util.Set;
import net.cumba.corej.core.model.Sensitivity;

/**
 * The <b>effective</b> finding granularity of one (rule, dataset), derived per SPEC §8 / D39b-REV:
 * {@code Sensitivity} is the <b>declared maximum</b>; the effective granularity follows the
 * bind-time derived expression level and may be coarser — Dataset, Study <b>or</b> Group (D39d) —
 * on a dataset where, for example, every column the check reads is absent (D39, D39a).
 *
 * <p>
 * ⛔ <b>Derive, don't invent</b> (D39b-REV): the granularity may land on {@code group(K)} only when
 * the key follows from the expressions — a {@link Granularity.Group} in the derived level — and is
 * never invented here. And the declared maximum caps fineness: a derived level <em>finer</em> than
 * the declaration (the routine case — a record-level expression under {@code Sensitivity: Dataset})
 * keeps the declared granularity, which is exactly D28b's emission collapse.
 * </p>
 *
 * <p>
 * Phase 5 derived and <b>observed</b> this value ({@code LevelInstrument}); this record still does
 * not drive emission. ⭐ The D39 fold itself <b>landed in phase 7 (D111)</b> — not through this
 * record but through {@code BroadcastFold}'s leaf classification ({@code bindColumnLevel}), which
 * is the same bind-time column-level fact this derivation reads: an all-absent check now evaluates
 * once and reports one dataset-level finding. What remains observation-only is the broader emission
 * routing (the per-variable cursor cases, the declared-vs-derived collapse).
 * </p>
 *
 * <p>
 * ⭐ <b>Phase 5b (D106g): the value carries the full effective LEVEL, not the granularity half.</b>
 * §8 is silent on the cursor, but for the per-variable rules the granularity half coarsens while
 * the multiplicity rides the cursor — {@code CG0310/0311/0359} derive {@code dataset × cursor} and
 * emit once per variable — so "effective granularity" alone under-describes effective emission. The
 * cursor is the derived level's own: {@code Sensitivity} is a granularity maximum and has no cursor
 * axis to cap it with (D30a's product, §1.3).
 * </p>
 *
 * @param granularity
 *            the effective granularity
 * @param cursor
 *            the effective cursor — the derived level's cursor, uncapped (D106g)
 * @param coarsened
 *            whether derivation coarsened below the declared maximum — the D39 / D39d population
 */
public record EffectiveGranularity(Granularity granularity, Cursor cursor, boolean coarsened)
{

    /**
     * Derives the effective granularity from the declared maximum and the derived expression level.
     *
     * @param declared
     *            the rule's {@code Sensitivity} — the declared maximum (D39b-REV)
     * @param declaredKeys
     *            the rule's grouping variables, used only when the declaration itself is the
     *            effective granularity and it is {@code GROUP}; empty otherwise is fine
     * @param derived
     *            the bind-time derived level of the rule's check expression
     */
    public static EffectiveGranularity of(Sensitivity declared, Set<String> declaredKeys,
            Level derived)
    {
        int declaredRank = rank(declared);
        Granularity d = derived.granularity();
        if (d.rank() <= declaredRank)
        {
            return new EffectiveGranularity(d, derived.cursor(), d.rank() < declaredRank);
        }
        return new EffectiveGranularity(declaredGranularity(declared, declaredKeys),
                derived.cursor(), false);
    }


    /** The declaration's position on the {@code study < dataset < group < record} chain. */
    private static int rank(Sensitivity declared)
    {
        return switch (declared)
        {
        case STUDY -> Granularity.Simple.STUDY.rank();
        case DATASET -> Granularity.Simple.DATASET.rank();
        case GROUP -> 2;
        case RECORD -> Granularity.Simple.RECORD.rank();
        };
    }


    /**
     * The declared sensitivity as a granularity, for the finer-than-declared branch. A
     * {@code GROUP} declaration with no keys cannot happen on a loaded rule (D28a's biconditional
     * is a load error in both directions); defensively it degrades to {@code dataset} rather than
     * throwing from an observation path.
     */
    private static Granularity declaredGranularity(Sensitivity declared, Set<String> declaredKeys)
    {
        return switch (declared)
        {
        case STUDY -> Granularity.Simple.STUDY;
        case DATASET -> Granularity.Simple.DATASET;
        case GROUP -> declaredKeys.isEmpty() ? Granularity.Simple.DATASET
                : new Granularity.Group(new LinkedHashSet<>(declaredKeys));
        case RECORD -> Granularity.Simple.RECORD;
        };
    }

}
