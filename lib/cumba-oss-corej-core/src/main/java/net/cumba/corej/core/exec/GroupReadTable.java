package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.AbstractDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The table a key / group reader reads its column NAMES on ({@code PLAN-merged-evaluation-table}
 * W3c: {@code N16}, and {@code N5} lifted per MQ8): the evaluated table, plus — only for the names
 * the reader was given that the evaluated table does not carry — the merged columns the rule's one
 * evaluation table holds under those names:
 *
 * <ul>
 * <li>a qualified {@code J.X} of a keyed {@code Match_Datasets} entry: the row's bound {@code J}
 * record's cell, read through the context's {@link JoinLookup} — on the merged-view arm a
 * {@link MergedViewLookup}, so the cell IS the view's ({@code W3a}); an unmatched row reads the
 * column's type default (the not-supplied marker, {@code D72a-1});</li>
 * <li>a bare name the primary lacks that its {@code SUPP--} delivers: the qualifier column
 * {@link SuppPivot#qualifierColumn} hands out — on the merged-view arm the view's SUPP column, with
 * its {@code MERGE_ERROR} read as the rule's ERROR (OD-7), exactly as the Check's own bare read of
 * it.</li>
 * </ul>
 *
 * <p>
 * Every other name keeps the reader's own machinery: a column of the evaluated table is read there,
 * and a name that resolves nowhere — a bare name neither the primary nor its {@code SUPP--}
 * carries, a qualified one whose join lacks the column or whose qualifier names no join — is simply
 * not a column of this table, so EC-44's "an absent group column partitions nothing" and every
 * reader's absent-subject bail apply to it unchanged (§3.3: <i>"partner lacks the column — the
 * absent-column machinery of a bare name"</i>). The rule-level {@code Grouping}'s qualified members
 * keep their own D-ABSENT ERROR (P-2) in {@code RuleRunner}.
 * </p>
 *
 * <p>
 * <b>Zero movement for every reader whose names all resolve on the evaluated table:</b> {@link #of}
 * then answers that table itself, the very instance, so the reader runs exactly as it did. The rows
 * are the evaluated table's rows one for one (same count, same order, the same
 * {@link #getRealRowIndex}), so a row set computed here is a row set of the evaluated table.
 * </p>
 *
 * <p>
 * Costs: built once per evaluation of a reader, and only when a name needs it — a few objects, no
 * per-row allocation of its own; a cell read is one array dispatch and the underlying reader's own
 * read (a view cell on the merged-view arm).
 * </p>
 */
public final class GroupReadTable extends AbstractDataTable
{

    private final IDataTable base;

    private final int baseColumns;

    private final IDataTableColumn[] extra;

    private GroupReadTable(IDataTable aBase, DataTableMeta aMeta, IDataTableColumn[] aExtra)
    {
        base = aBase;
        baseColumns = aBase.getMetaData().getColumnCount();
        extra = aExtra;
        setMetaData(aMeta);
    }


    /**
     * The table to read {@code names} on — {@link EvaluationContext#getTable()} itself when every
     * name is its own column or resolves nowhere.
     *
     * @param ctx
     *            the evaluation context
     * @param names
     *            the column names the reader reads, prefixes resolved ({@code null} entries are
     *            ignored)
     * @return the evaluated table, or it with the merged columns those names need appended
     * @throws MergeErrorReadException
     *             when a SUPP qualifier the names read carries a {@code MERGE_ERROR} (OD-7)
     */
    public static IDataTable of(EvaluationContext ctx, @Nullable String... names)
    {
        return of(ctx, Arrays.asList(names));
    }


    /**
     * {@link #of(EvaluationContext, String...)} over a collection of names.
     *
     * @param ctx
     *            the evaluation context
     * @param names
     *            the column names, prefixes resolved ({@code null} entries are ignored)
     * @return the evaluated table, or it with the merged columns those names need appended
     */
    public static IDataTable of(EvaluationContext ctx, Collection<? extends @Nullable String> names)
    {
        IDataTable table = ctx.getTable();
        DataTableMeta meta = table.getMetaData();
        List<String> added = null;
        List<Merged> merged = null;
        for (String name : names)
        {
            if (name == null || meta.getColumnIndex(name) >= 0
                    || (added != null && added.contains(name)))
            {
                continue;
            }
            Merged column = resolve(ctx, table, name);
            if (column == null)
            {
                continue;
            }
            if (added == null || merged == null)
            {
                added = new ArrayList<>(2);
                merged = new ArrayList<>(2);
            }
            added.add(name);
            merged.add(column);
        }
        if (added == null || merged == null)
        {
            return table;
        }
        List<DataTableColumnMeta> metas = new ArrayList<>(Arrays.asList(meta.getColumns()));
        IDataTableColumn[] columns = new IDataTableColumn[merged.size()];
        for (int i = 0; i < columns.length; i++)
        {
            String name = added.get(i);
            metas.add(DataTableColumnMeta.builder().index(metas.size()).name(name).label(name)
                    .type(merged.get(i).type()).build());
            columns[i] = merged.get(i).column();
        }
        DataTableMeta grown = meta.toBuilder().columns(metas.toArray(DataTableColumnMeta[]::new))
                .build();
        return new GroupReadTable(table, grown, columns);
    }


    /**
     * {@link #of} over an enumerated family — the base name, then the base name suffixed 1, 2, … up
     * to the first number that resolves nowhere (99 at most), exactly the columns
     * {@code inconsistent_enumerated_columns} reads ({@code GroupSemantics}).
     *
     * @param ctx
     *            the evaluation context
     * @param base
     *            the base name, prefix resolved, or {@code null}
     * @return the table to read the family on
     */
    public static IDataTable ofEnumerated(EvaluationContext ctx, @Nullable String base)
    {
        if (base == null)
        {
            return ctx.getTable();
        }
        IDataTable table = ctx.getTable();
        List<String> names = new ArrayList<>();
        names.add(base);
        for (int num = 1; num <= 99; num++)
        {
            String name = base + num;
            if (table.getMetaData().getColumnIndex(name) < 0 && resolve(ctx, table, name) == null)
            {
                break;
            }
            names.add(name);
        }
        return of(ctx, names);
    }

    /** A merged column and its declared type. */
    private record Merged(IDataTableColumn column, DataValueType type)
    {
    }

    private static @Nullable Merged resolve(EvaluationContext ctx, IDataTable table, String name)
    {
        String qualifier = MatchDataset.qualifierOf(name);
        if (qualifier != null)
        {
            JoinLookup lookup = ctx.getJoinedDatasets().get(qualifier);
            String column = name.substring(qualifier.length() + 1);
            if (lookup == null || lookup.lacksColumnOnEveryRow(column))
            {
                return null; // resolves nowhere: the reader's absent-column machinery (§3.3)
            }
            DataValueType type = lookup.declaredTypeOf(column);
            return new Merged(new JoinedColumn(table, lookup, column),
                    type == DataValueType.MISSING ? DataValueType.STRING : type);
        }
        IDataTableColumn qualifierColumn = SuppPivot.qualifierColumn(ctx, name);
        return qualifierColumn == null ? null : new Merged(qualifierColumn, suppType(ctx, name));
    }


    /** The SUPP qualifier's declared type: the view's column type, else the QVAL text. */
    private static DataValueType suppType(EvaluationContext ctx, String name)
    {
        MergedEvaluation.Built built = ctx.getMergedView();
        if (built != null && ctx.getTable() == built.table())
        {
            int idx = built.view().getColumnIndex(name);
            if (idx >= 0)
            {
                return built.view().getMetaData().getColumn(idx).getType();
            }
        }
        return DataValueType.STRING;
    }

    /** A qualified column read through its join lookup at the evaluated row. */
    private static final class JoinedColumn extends AbstractDataTableColumn
    {

        private final IDataTable primary;

        private final JoinLookup lookup;

        private final String column;

        private JoinedColumn(IDataTable aPrimary, JoinLookup aLookup, String aColumn)
        {
            super(-1);
            primary = aPrimary;
            lookup = aLookup;
            column = aColumn;
        }


        @Override
        public long getRowCount()
        {
            return primary.getRowCount();
        }


        @Override
        public @Nullable Object getValue(long aRow)
        {
            return getDataValue(aRow).getValue();
        }


        @Override
        public IDataValue getDataValue(long aRow)
        {
            return lookup.lookupValue(primary, aRow, column, false);
        }
    }

    @Override
    public long getRowCount()
    {
        return base.getRowCount();
    }


    @Override
    public @Nullable Object getValue(long aRow, int aColumn)
    {
        return aColumn < baseColumns ? base.getValue(aRow, aColumn)
                : extra[aColumn - baseColumns].getValue(aRow);
    }


    @Override
    public IDataValue getDataValue(long aRow, int aColumn)
    {
        return aColumn < baseColumns ? base.getDataValue(aRow, aColumn)
                : extra[aColumn - baseColumns].getDataValue(aRow);
    }


    @Override
    public IDataTableColumn getColumn(int aColumn)
    {
        // a merged column through the default per-index view, so its getIndex() is this table's
        return aColumn < baseColumns ? base.getColumn(aColumn) : super.getColumn(aColumn);
    }


    @Override
    public boolean isSupplied(long aRow, int aColumn)
    {
        return aColumn >= baseColumns || base.isSupplied(aRow, aColumn);
    }


    @Override
    public long getRealRowIndex(long aRowIndex)
    {
        return base.getRealRowIndex(aRowIndex);
    }
}
