package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The two grouped, per-row boolean plans of wave 1 phase 4 ({@code PLAN-function-surface-wave1}
 * D-W1-4), ported from the retired {@code IS_LAST_IN_GROUP} and
 * {@code HAS_MIXED_EMPTINESS_WITHIN_GROUP} operations. They are compiler-dispatched (their
 * descriptors carry no {@code fn}; {@code ExprCompiler} compiles the call to a plan that resolves
 * the column names and calls in here), receive their columns as <b>names</b> — EC-44 is defined
 * over names: an absent group column is dropped, and all absent means one whole-table group — and
 * group through the same groupers the retired evaluators used, so plan 16's {@code GroupKey}
 * identity and both missing-key defaults survive unchanged. Each answers a {@link BitSet} already
 * broadcast per row (runbook R2): a row in no block, or in a group that does not hold, is
 * {@code false}, the retired {@code PREDICATE} default.
 */
public final class GroupedPredicates
{

    private GroupedPredicates()
    {
    }


    /**
     * {@code is_last_in_group(ordering=, group=[…], keep_missings=)}: {@code true} on every row
     * whose ordering value is <b>identical</b> to its group's maximum (D-W1-2). "Identical" has one
     * meaning, the datatable's key identity: {@code -0.0} is {@code 0.0}, {@code 4.9999999999994}
     * is not {@code 5.0}, a CHAR {@code 5} is not a CHAR {@code 5.0}, and a missing is identical to
     * the same missing marker. Missings rank lowest ({@code D34 #5}), so a group whose ordering
     * values are all missing answers {@code true} on every row. Every tied row is last — where the
     * retired operation collapsed the tie to {@code false} on every row (the DS 1:N copies, a
     * duplicate {@code SESEQ}, an all-missing group).
     *
     * @param table
     *            the evaluated table
     * @param groupColumns
     *            the group column names (absent ones are ignored; all absent ⇒ the empty result,
     *            EC-44's one exception for this operator)
     * @param orderingColumn
     *            the ordering column name (absent ⇒ the empty result)
     * @param policy
     *            the missing-key policy ({@code DROP_MISSING_KEYS} by default,
     *            {@code keep_missings=} declared)
     * @return the rows that are last in their group
     */
    public static BitSet isLastInGroup(IDataTable table, List<String> groupColumns,
            String orderingColumn, GroupKeyPolicy policy)
    {
        DataTableMeta meta = table.getMetaData();
        int ordIdx = meta.getColumnIndex(orderingColumn);
        BitSet out = new BitSet((int) table.getRowCount());
        if (ordIdx < 0 || groupColumns.stream().noneMatch(g -> meta.getColumnIndex(g) >= 0))
        {
            return out;
        }
        IDataTableColumn ordering = table.getColumn(ordIdx);
        for (int[] group : GroupSemantics.group(table, groupColumns, policy))
        {
            if (group.length == 0)
            {
                continue;
            }
            int max = group[0];
            for (int row : group)
            {
                if (compareOrdering(ordering.getDataValue(row), ordering.getDataValue(max)) > 0)
                {
                    max = row;
                }
            }
            IDataValue top = ordering.getDataValue(max);
            for (int row : group)
            {
                if (compareOrdering(ordering.getDataValue(row), top) == 0)
                {
                    out.set(row);
                }
            }
        }
        return out;
    }


    /**
     * The ranking of D-W1-2, whose equality is exactly the key identity. The ordering value's
     * <b>type</b> decides how it compares (owner ruling 2026-09-29, {@code D-W1-2a}: <i>"A string
     * is a string"</i>): two numeric cells compare as numbers ({@code -0.0} is {@code 0.0},
     * {@code D84}); two textual cells compare as text — {@code "10" < "9"}, and {@code "5"} and
     * {@code "5.0"} are two distinct values that never tie, as are {@code "-0.0"} and {@code "0.0"}
     * — the {@code ±0} fold is numeric only (owner, same day). A textual value is never parsed as a
     * number; numeric order of a text column is the author's {@code num(X)} — not yet spellable as
     * an {@code ordering}, which takes a plain column (review round 2, FINDINGS §3). A missing
     * ranks below every value ({@code D34 #5}) and identical to the same missing marker; two
     * markers order by their value byte ({@code D34 #5-1}, the order
     * {@code Primitives.compareWithMissing} ships for {@code <}), so {@code ._} ({@code MIS__},
     * byte 60) and {@code .} ({@code MIS}, byte 64) never tie, and {@code .} is the higher one
     * where their text would say the opposite.
     *
     * <p>
     * This is a total order on every column — one type per column, so a numeric column is ordered
     * numerically and a textual one lexically (a present blank {@code ""} lowest, {@code D36b}) —
     * and its equality is exactly {@code GroupKey} identity, so the maximum, and hence which rows
     * are "last", never depends on the row order. Before the ruling a textual cell that parsed as a
     * number compared numerically, inherited from the retired {@code orderingCompare}; on a column
     * mixing parsable and non-parsable text that cycled ({@code 3 < 10} numeric, {@code 10 < 2x}
     * text, {@code 2x < 3} text).
     * </p>
     *
     * @param a
     *            the left cell
     * @param b
     *            the right cell
     * @return the comparison
     */
    static int compareOrdering(IDataValue a, IDataValue b)
    {
        boolean aMissing = a.isMissingOrInvalid();
        boolean bMissing = b.isMissingOrInvalid();
        if (aMissing || bMissing)
        {
            if (aMissing && bMissing)
            {
                if (a.getValue() instanceof MissingValue am
                        && b.getValue() instanceof MissingValue bm)
                {
                    return Integer.compare(am.getValue(), bm.getValue());
                }
                return Objects.requireNonNullElse(a.getValueAsString(), "")
                        .compareTo(Objects.requireNonNullElse(b.getValueAsString(), ""));
            }
            return aMissing ? -1 : 1;
        }
        Object av = a.getValue();
        Object bv = b.getValue();
        if (av instanceof Number an && bv instanceof Number bn)
        {
            if (an instanceof Long al && bn instanceof Long bl)
            {
                return Long.compare(al, bl);
            }
            return Double.compare(normalizeZero(an.doubleValue()), normalizeZero(bn.doubleValue()));
        }
        return Objects.requireNonNullElse(a.getValueAsString(), "")
                .compareTo(Objects.requireNonNullElse(b.getValueAsString(), ""));
    }


    private static double normalizeZero(double d)
    {
        return d == 0.0 ? 0.0 : d;
    }


    /**
     * {@code has_mixed_emptiness_within_group(name, group=[…], qualifying_any_populated=[…],
     * keep_missings=)}: {@code true} on every row of a group in which the subject column is
     * populated on some rows and unpopulated on others. EC-45 §1.3(2): an absent subject column is
     * all-unpopulated, so no group is mixed; §1.3(3): no group column, or none present, is one
     * whole-table group. EC-23: with qualifiers, a row on which none of the (present) qualifier
     * columns is populated is out of scope, and an all-absent qualifier list qualifies no row.
     *
     * @param table
     *            the evaluated table
     * @param subjectColumn
     *            the column whose emptiness is tallied
     * @param groupColumns
     *            the group column names, possibly empty
     * @param qualifierColumns
     *            the {@code qualifying_any_populated} names, possibly empty (= every row in scope)
     * @param policy
     *            the missing-key policy ({@code KEEP_MISSING_KEYS} by default,
     *            {@code keep_missings=} declared)
     * @param logContext
     *            the label for the EC-44 "group column absent" diagnostic, or {@code null}
     * @return the rows of every mixed group
     */
    public static BitSet hasMixedEmptiness(IDataTable table, String subjectColumn,
            List<String> groupColumns, List<String> qualifierColumns, GroupKeyPolicy policy,
            @Nullable String logContext)
    {
        DataTableMeta meta = table.getMetaData();
        BitSet out = new BitSet((int) table.getRowCount());
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, groupColumns, logContext,
                policy);
        if (grouping == null)
        {
            return out;
        }
        int subjectIdx = meta.getColumnIndex(subjectColumn);
        int[] qualifierIdx = qualifierColumns.stream().mapToInt(meta::getColumnIndex)
                .filter(idx -> idx >= 0).toArray();
        boolean hasQualifier = !qualifierColumns.isEmpty();
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            boolean hasPopulated = false;
            boolean hasUnpopulated = false;
            for (int row : block.rows())
            {
                if (hasQualifier && !rowQualifies(table, qualifierIdx, row))
                {
                    continue;
                }
                if (populated(table, subjectIdx, row))
                {
                    hasPopulated = true;
                }
                else
                {
                    hasUnpopulated = true;
                }
                if (hasPopulated && hasUnpopulated)
                {
                    break;
                }
            }
            if (hasPopulated && hasUnpopulated)
            {
                for (int row : block.rows())
                {
                    out.set(row);
                }
            }
        }
        return out;
    }


    private static boolean populated(IDataTable table, int columnIdx, long row)
    {
        if (columnIdx < 0)
        {
            return false;
        }
        IDataValue dv = table.getColumn(columnIdx).getDataValue(row);
        return !dv.isMissingOrInvalid() && dv.getValueAsString() != null
                && !dv.getValueAsString().isEmpty();
    }


    private static boolean rowQualifies(IDataTable table, int[] qualifierIdx, long row)
    {
        for (int idx : qualifierIdx)
        {
            IDataValue dv = table.getColumn(idx).getDataValue(row);
            if (!dv.isMissingOrInvalid())
            {
                String s = dv.getValueAsString();
                if (s != null && !s.isBlank())
                {
                    return true;
                }
            }
        }
        return false;
    }

}
