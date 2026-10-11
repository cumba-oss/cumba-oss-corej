package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The quantifier family over the joined rows of the current primary record
 * ({@code PLAN-cg0218-transition-day-precision} P1 / P2 / P8): {@code any(joined(c))} — some row of
 * the record satisfies {@code c}; {@code all(joined(c))} — every row does; {@code none(joined(c))}
 * — no row does. {@code joined(c)} is the per-record list of {@code c}'s answers over the rows of
 * the evaluation table that stand for the same primary record ({@link RealRows}); the three
 * quantifiers fold that list two-valued (P3: a {@code BoolPlan} answers true / false per row, so
 * the fold is plain OR / AND / NOR), and {@code ExprCompiler} compiles the pair into one fused plan
 * — no {@code list<boolean>} is ever materialised.
 *
 * <p>
 * <b>The domain</b> (P2) is the record's rows in the evaluation table: on a row-mapped table that
 * repeats a record ({@link RealRows#ofRepeating}) every row whose {@link RealRows#representative}
 * equals the current row's; on a table that is not row-mapped, or repeats no record, the record is
 * its one row, so {@code any} / {@code all} answer {@code c} itself and {@code none} answers
 * {@code ¬c}. An unmatched {@code Join_Type: left} record keeps its one placeholder row, which is
 * in the domain (the author guards with {@code J._matched_}, D88); the empty conventions
 * ({@code any([])} false, {@code all([])} true, {@code none([])} true) never arise, because a
 * record with no row is not evaluated.
 * </p>
 *
 * <p>
 * <b>Shape</b> (P8, the shape of {@code RealRowAggregates.bool}): one {@link BitSet} over
 * {@code [0, count())} indexed by the representative — the records with a true row ({@code any} /
 * {@code none}) or with a false row ({@code all}, walked with {@code nextClearBit}, no copy) —
 * mapped back per evaluation row into a <b>new</b> output set. O(rows), two bit sets, no boxing, no
 * hash map. ⛔ Never keyed on {@code getRealRowIndex}: it maps through a sorted or filtered
 * primary's own real rows and is not a position (T2 L1). ⛔ Never mutates {@code c}'s bit set: a
 * pure {@code c} can be a shared, read-only {@code ExpressionResultCache} entry.
 * </p>
 */
public final class JoinedRowFold
{

    /** The list producer's name: {@code joined(<boolean>)}. */
    public static final String JOINED = "joined";

    /** The three quantifiers, by their call names. */
    public enum Quantifier
    {

        /** {@code any(joined(c))}: some row of the record satisfies {@code c}. */
        ANY,
        /** {@code all(joined(c))}: every row of the record satisfies {@code c}. */
        ALL,
        /** {@code none(joined(c))}: no row of the record satisfies {@code c}. */
        NONE;

        /** The call name ({@code any}, {@code all}, {@code none}). */
        public String callName()
        {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private JoinedRowFold()
    {
    }


    /**
     * The quantifier a call name spells, or {@code null} for any other name.
     *
     * @param name
     *            a call name
     * @return the quantifier, or {@code null}
     */
    public static @Nullable Quantifier quantifier(String name)
    {
        return switch (name)
        {
        case "any" -> Quantifier.ANY;
        case "all" -> Quantifier.ALL;
        case "none" -> Quantifier.NONE;
        default -> null;
        };
    }


    /**
     * Whether {@code name} is one of the three quantifiers.
     *
     * @param name
     *            a call name
     * @return whether it is {@code any}, {@code all} or {@code none}
     */
    public static boolean isQuantifier(String name)
    {
        return quantifier(name) != null;
    }


    /**
     * Folds a per-row condition over the rows of each primary record.
     *
     * @param quantifier
     *            the quantifier
     * @param condition
     *            the rows where the condition is true, over {@code [0, rowCount)} — read only
     * @param ctx
     *            the evaluation context (its table carries the real-row projection)
     * @param rowCount
     *            the rows of this run ({@code EvalRun.rowCount()})
     * @return a new set: the rows whose record satisfies the quantified condition
     */
    public static BitSet fold(Quantifier quantifier, BitSet condition, EvaluationContext ctx,
            int rowCount)
    {
        RealRows real = RealRows.ofRepeating(ctx.getTable());
        BitSet out = new BitSet(rowCount);
        if (real == null || rowCount > ctx.rowCount())
        {
            // no record repeats (or the run exceeds the table, RealRowAggregates' guard): every
            // record is its one row, so the quantified condition is the condition itself
            out.or(condition);
            if (quantifier == Quantifier.NONE)
            {
                out.flip(0, rowCount);
            }
            return out;
        }
        // the records with a true row (any / none), or with a false row (all)
        BitSet records = new BitSet(real.count());
        if (quantifier == Quantifier.ALL)
        {
            for (int row = condition.nextClearBit(0); row < rowCount; row = condition
                    .nextClearBit(row + 1))
            {
                records.set(real.representative(row));
            }
        }
        else
        {
            condition.stream().takeWhile(row -> row < rowCount)
                    .forEach(row -> records.set(real.representative(row)));
        }
        boolean setWhenMarked = quantifier == Quantifier.ANY;
        for (int row = 0; row < rowCount; row++)
        {
            if (records.get(real.representative(row)) == setWhenMarked)
            {
                out.set(row);
            }
        }
        return out;
    }
}
