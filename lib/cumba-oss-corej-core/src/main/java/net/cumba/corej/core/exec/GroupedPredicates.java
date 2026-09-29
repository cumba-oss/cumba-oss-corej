package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
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
     * The ranking of D-W1-2: a total order whose equality is exactly the key identity. A missing
     * ranks below every value and identical to the same missing marker (two markers order by their
     * text); two numeric cells compare as numbers ({@code -0.0} is {@code 0.0}); two textual cells
     * that both parse as numbers compare as numbers first and, where that says equal but the texts
     * differ ({@code 5} against {@code 5.0}), by their text; anything else compares by text.
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
        String as = Objects.requireNonNullElse(a.getValueAsString(), "");
        String bs = Objects.requireNonNullElse(b.getValueAsString(), "");
        Double ad = parse(as);
        Double bd = parse(bs);
        if (ad != null && bd != null)
        {
            int numeric = Double.compare(normalizeZero(ad), normalizeZero(bd));
            if (numeric != 0)
            {
                return numeric;
            }
        }
        return as.compareTo(bs);
    }


    private static double normalizeZero(double d)
    {
        return d == 0.0 ? 0.0 : d;
    }


    private static @Nullable Double parse(String text)
    {
        try
        {
            return Double.valueOf(text);
        }
        catch (NumberFormatException _)
        {
            return null;
        }
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
