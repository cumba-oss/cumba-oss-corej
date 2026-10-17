package net.cumba.corej.core.exec;

import java.util.List;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.GroupKey;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The <b>one</b> derivation of a row's group-key identity that every grouped reader shares
 * ({@code PLAN-grouping-key-identity} GKI Q1 / Q3): the block-keyed evaluators
 * ({@code IndexHelper}), the per-row lookups of the grouped registry functions
 * ({@code GroupedAggregate}, {@code Distinct}, {@code RecordCount}) and the cross-table key-type
 * check (GKI Q2) all key by {@link #identityKey}, so a block's key and a primary row's lookup key
 * cannot be derived two ways.
 *
 * <p>
 * Runbook W8 ({@code PLAN-retire-operation-surface} D-W8-5): this is what remains of the retired
 * grouped-result object — the per-row runtime value an operation broadcast, keyed by
 * {@code getForRow}, with its own missing-key default and key mode. Runbook R2 made every ported
 * callable return a {@code Vector} already broadcast per row, so since W7 no producer built one and
 * no reader arm was reachable; the statics the functions share are kept under a name that says what
 * they are.
 * </p>
 */
public final class GroupKeyIdentity
{

    /**
     * The one key policy of the identity derivation: a missing key component keeps its identity
     * ({@code KEEP_MISSING_KEYS}) — a per-reader disposition ({@code keep_missings=}) is applied by
     * the reader's own grouping, never by the key.
     */
    private static final GroupKeyPolicy KEY_POLICY = GroupKeyPolicy.KEEP_MISSING_KEYS;

    private GroupKeyIdentity()
    {
    }


    /**
     * The identity key of {@code row}'s group over {@code groupCols} — the one derivation shared by
     * the block-keyed evaluators and the per-row lookups (GKI Q1 / Q3): a single column's key is
     * its cell's key identity ({@link GroupKeyPolicy#keyIdentity}), several columns' a
     * {@link GroupKey} over theirs; an absent column contributes {@code ""}.
     *
     * @param meta
     *            the table's metadata
     * @param table
     *            the table the row is read from
     * @param groupCols
     *            the group columns, in declaration order
     * @param row
     *            the row
     * @return the identity key
     */
    static Object identityKey(DataTableMeta meta, IDataTable table, List<String> groupCols,
            long row)
    {
        int n = groupCols.size();
        if (n == 1)
        {
            return identityOf(meta, table, groupCols.get(0), row);
        }
        Object[] parts = new Object[n];
        for (int i = 0; i < n; i++)
        {
            parts[i] = identityOf(meta, table, groupCols.get(i), row);
        }
        return GroupKey.of(parts);
    }


    /**
     * The column indices of {@code groupCols} in {@code meta}, resolved once for a per-row loop
     * (combined review of runbook W2–W8, XCUT PERF 3): {@code -1} for an absent column.
     *
     * @param meta
     *            the table's metadata
     * @param groupCols
     *            the group columns, in declaration order
     * @return the indices, position-wise
     */
    static int[] columnIndices(DataTableMeta meta, List<String> groupCols)
    {
        int[] out = new int[groupCols.size()];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = meta.getColumnIndex(groupCols.get(i));
        }
        return out;
    }


    /**
     * {@link #identityKey(DataTableMeta, IDataTable, List, long)} over column indices resolved once
     * by {@link #columnIndices} — the same derivation, component for component, without a name
     * lookup per row.
     *
     * @param table
     *            the table the row is read from
     * @param columns
     *            the group columns' indices ({@code -1} for an absent column)
     * @param row
     *            the row
     * @return the identity key
     */
    static Object identityKey(IDataTable table, int[] columns, long row)
    {
        int n = columns.length;
        if (n == 1)
        {
            return identityAt(table, columns[0], row);
        }
        Object[] parts = new Object[n];
        for (int i = 0; i < n; i++)
        {
            parts[i] = identityAt(table, columns[i], row);
        }
        return GroupKey.of(parts);
    }


    /**
     * The key identity of one cell ({@link GroupKeyPolicy#keyIdentity} under the one policy) — for
     * a reader that fetches the cell itself, such as the qualified record-side keyer of
     * {@code GroupedAggregate} (C2 of {@code PLAN-rprfdy-offset-tp-join}), which reads a component
     * through a join lookup rather than from a column of the table.
     *
     * @param cell
     *            the cell
     * @return its identity
     */
    static Object identityOf(IDataValue cell)
    {
        return KEY_POLICY.keyIdentity(cell);
    }


    /**
     * The key of already-derived component identities: the single identity for one component, a
     * {@link GroupKey} over several — exactly the shape {@link #identityKey} builds, so a key
     * composed here meets a block key derived there.
     *
     * @param parts
     *            the component identities, in declaration order
     * @return the key
     */
    static Object keyOf(Object[] parts)
    {
        return parts.length == 1 ? parts[0] : GroupKey.of(parts);
    }


    private static Object identityOf(DataTableMeta meta, IDataTable table, String col, long row)
    {
        return identityAt(table, meta.getColumnIndex(col), row);
    }


    static Object identityAt(IDataTable table, int idx, long row)
    {
        return idx < 0 ? "" : KEY_POLICY.keyIdentity(table.getColumn(idx).getDataValue(row));
    }


    /**
     * GKI Q2 — the cross-table key-type check: a grouping built on {@code aGrouped} over
     * {@code aGroupedCols} may be read from {@code aEvaluated} over {@code aEvaluatedCols} only
     * when every column pair agrees in kind (numeric / character); a mismatch ERRORs the rule
     * ({@link JoinKeyTypeMismatchException}) instead of silently matching no row. A column absent
     * on either side is not judged, and neither is a column <b>blank in every row</b>
     * ({@code SVY OQ11}, owner 2026-10-16: <i>"exempt in every key-kind check"</i>;
     * {@link #isBlank}): its type is the provider's guess (CSV types an all-blank column Char, XLSX
     * Num), not a property of the data — {@code RowMax}'s precedent. The scan is lazy: only a pair
     * whose kinds already disagree is asked.
     *
     * @param aGrouped
     *            the grouped (foreign or primary) table
     * @param aGroupedCols
     *            its key columns
     * @param aEvaluated
     *            the table the lookup reads from
     * @param aEvaluatedCols
     *            its key columns, position-wise
     */
    static void requireCompatibleKeyColumns(IDataTable aGrouped, List<String> aGroupedCols,
            IDataTable aEvaluated, List<String> aEvaluatedCols)
    {
        DataTableMeta groupedMeta = aGrouped.getMetaData();
        DataTableMeta evaluatedMeta = aEvaluated.getMetaData();
        for (int i = 0; i < aGroupedCols.size(); i++)
        {
            ColumnTypeGate.Kind grouped = kindOf(groupedMeta, aGroupedCols.get(i));
            ColumnTypeGate.Kind evaluated = kindOf(evaluatedMeta, aEvaluatedCols.get(i));
            if (grouped != null && evaluated != null && grouped != evaluated
                    && !isBlank(aGrouped, aGroupedCols.get(i))
                    && !isBlank(aEvaluated, aEvaluatedCols.get(i)))
            {
                throw JoinKeyTypeMismatchException.forGroupedLookup(aGroupedCols.get(i),
                        String.valueOf(groupedMeta.getName()), grouped, aEvaluatedCols.get(i),
                        String.valueOf(evaluatedMeta.getName()), evaluated);
            }
        }
    }


    /**
     * GKI Q2 for ONE qualified member (C2 of {@code PLAN-rprfdy-offset-tp-join}): the grouped
     * column's kind against the SOURCE column's declared type — the record side of
     * {@code DM.RPATHCD} lives in DM, read through the join, not in the evaluated table. The
     * {@code SVY OQ11} exemption asks the <b>source</b> column's content ({@code aSourceBlank},
     * {@link JoinLookup#sourceColumnIsBlank}), never the merged column the record side reads: an
     * unmatched merged row carries the type default (D72a-1), so a populated source column with no
     * matched row would read blank there and be wrongly exempt (B5 T7 / M2).
     *
     * @param aGrouped
     *            the grouped table
     * @param aGroupedCol
     *            its key column (the unqualified name)
     * @param aSource
     *            the source entry's name, for the message
     * @param aSourceType
     *            the source column's declared type ({@code MISSING} when unknown — not judged)
     * @param aSourceBlank
     *            whether the source column is blank in every row (then not judged)
     */
    static void requireCompatibleKeyColumn(IDataTable aGrouped, String aGroupedCol, String aSource,
            net.cumba.datatable.values.DataValueType aSourceType, boolean aSourceBlank)
    {
        DataTableMeta groupedMeta = aGrouped.getMetaData();
        ColumnTypeGate.Kind grouped = kindOf(groupedMeta, aGroupedCol);
        ColumnTypeGate.Kind source = ColumnTypeGate.kindOf(aSourceType);
        if (grouped != null && source != null && grouped != source && !aSourceBlank
                && !isBlank(aGrouped, aGroupedCol))
        {
            throw JoinKeyTypeMismatchException.forGroupedLookup(aGroupedCol,
                    String.valueOf(groupedMeta.getName()), grouped, aGroupedCol, aSource, source);
        }
    }


    /**
     * Whether {@code aColumn} of {@code aTable} holds no value in any row — every cell missing or
     * {@code ""} ({@link net.cumba.datatable.ColumnContent#isBlank()}, the one predicate every
     * key-kind check, {@code RowMax}, the column-type gate, the scope gate's typed requirement and
     * stage B's column-type observation share (ECT-Q5 ⓐ, PACK I E1); a 0-row table counts as blank,
     * harmlessly — it groups nothing). An absent column answers {@code false}: it has no content to
     * be blank, and is not judged anyway.
     */
    public static boolean isBlank(IDataTable aTable, String aColumn)
    {
        int idx = aTable.getMetaData().getColumnIndex(aColumn);
        return idx >= 0 && aTable.getColumnContent(idx).isBlank();
    }


    /**
     * Whether {@code aColumn} is blank in <b>every</b> member that carries it — the blank predicate
     * of a split domain or a SUPP family, asked by the scope gate's qualified and pivot arms (PACK
     * I E1): with ECT-Q7 an all-blank union resolves {@code STRING}, which is a default, not a
     * type. Answers {@code false} when no member carries the column (there is no content to be
     * blank, and the entry is absent or undecidable anyway).
     */
    public static boolean allBlank(List<IDataTable> aMembers, String aColumn)
    {
        boolean carried = false;
        for (IDataTable member : aMembers)
        {
            int idx = member.getMetaData().getColumnIndex(aColumn);
            if (idx < 0)
            {
                continue;
            }
            carried = true;
            if (!member.getColumnContent(idx).isBlank())
            {
                return false;
            }
        }
        return carried;
    }


    private static ColumnTypeGate.@Nullable Kind kindOf(DataTableMeta aMeta, String aColumn)
    {
        int idx = aMeta.getColumnIndex(aColumn);
        return idx < 0 ? null : ColumnTypeGate.kindOf(aMeta.getColumn(idx).getType());
    }

}
