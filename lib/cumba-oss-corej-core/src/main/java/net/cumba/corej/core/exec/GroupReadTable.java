package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import net.cumba.corej.core.expr.eval.DataValues;
import net.cumba.corej.core.expr.eval.IsoDurationBounds;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
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
 * record's cell, read through the context's {@link JoinLookup} — a {@link MergedViewLookup}, so the
 * cell IS the view's ({@code W3a}); an unmatched row reads the column's type default (the
 * not-supplied marker, {@code D72a-1});</li>
 * <li>a bare name the primary lacks that its {@code SUPP--} delivers: the qualifier column
 * {@link EvaluationContext#suppQualifier} hands out — the view's SUPP column, with its
 * {@code MERGE_ERROR} read as the rule's ERROR (OD-7), exactly as the Check's own bare read of
 * it.</li>
 * </ul>
 *
 * <p>
 * Every other name keeps the reader's own machinery: a column of the evaluated table is read there,
 * and a name that resolves nowhere — a bare name neither the primary nor its {@code SUPP--}
 * carries, a qualified one whose join lacks the column or whose qualifier names no join — is simply
 * not a column of this table, so EC-44's "an absent group column partitions nothing" and every
 * reader's absent-subject bail apply to it unchanged (§3.3: <i>"partner lacks the column — the
 * absent-column machinery of a bare name"</i>) — for the rule-level {@code Grouping}'s qualified
 * members too ({@code REG-Q4} QK4: their D-ABSENT ERROR is gone), and under {@code domain=} a
 * member absent here is the record side of {@code GroupedAggregate.SidedKey}'s one-side drop.
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

    /**
     * The group operators and extremes whose key / name / members / within readers read a qualified
     * operand as a merged column ({@code PLAN-merged-evaluation-table} W3c, N5 lifted per MQ8) —
     * the roster the loader's D-SRC gate judges: a qualifier among their operands must name a
     * declared {@code Match_Datasets} entry of the rule
     * ({@code RulePackageLoader.qualifiedSourceError}; the owner's 2026-10-05 <i>"apply D-SRC to
     * the group operators too"</i> — the ordinary / {@code inner} half, {@code MET D-SRC-ops} — was
     * withdrawn 2026-10-09, {@code REG-Q4} QK12). {@code read_value} is not listed: its
     * {@code domain=} names the dataset itself (W3c), and a call carrying {@code domain=} is not
     * judged for any member. The positive twins {@code is_unique_value} /
     * {@code is_unique_relationship} read through the same readers as their negatives (T2 review,
     * lane 2 M2), so they are judged too.
     */
    public static final java.util.Set<String> GROUP_OPERATORS = java.util.Set.of("is_unique_set",
            "is_not_unique_set", "has_multiple_values_for", "present_on_multiple_rows_within",
            "is_inconsistent_across_dataset", "is_not_unique_relationship",
            "is_unique_relationship", "is_not_unique_value", "is_unique_value", "has_same_values",
            "empty_within_except_last_row", "has_next_corresponding_record", "is_sorted_by",
            "is_last_in_group", "has_mixed_emptiness_within_group", "contains_all",
            "not_contains_all", "inconsistent_enumerated_columns", "max", "max_date", "min_date",
            "record_count", "distinct", "sequence", GroupSplice.NOT_UNIQUE_MEMBER_SET);

    /**
     * The prefix of a value member's reserved column name (runbook W1b UC1, coordinator D3): no
     * authored column name starts with {@code #}, so the per-row key columns {@link #withValues}
     * appends never shadow or collide with a dataset's own.
     */
    private static final String VALUE_COLUMN_PREFIX = "#value";

    /**
     * The operators that count records ({@code PLAN-merged-evaluation-table} OD-6): every
     * {@link #GROUP_OPERATORS} member but {@code inconsistent_enumerated_columns}, which reads a
     * column family beyond its written operand and judges each row on its own. On a table a
     * one-to-many merge expanded, an operator of this roster whose operands are all primary columns
     * counts each primary record once ({@code RealRowAggregates}); the stage-A lint
     * {@code AGGREGATE_OVER_REPEATED_ROWS} reports the operands it cannot judge statically.
     */
    public static final java.util.Set<String> REAL_ROW_OPERATORS = GROUP_OPERATORS.stream()
            .filter(n -> !"inconsistent_enumerated_columns".equals(n))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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
     * The table to read {@code names} on — the context's evaluated table itself when every name is
     * its own column or resolves nowhere.
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
     * The reserved column name of the {@code index}-th value member of a key list (W1b UC1).
     *
     * @param index
     *            the member's position in the authored list
     * @return the name
     */
    public static String valueColumnName(int index)
    {
        return VALUE_COLUMN_PREFIX + index;
    }


    /**
     * {@code table} grown by one column per {@code vectors} entry, named by {@code names} (W1b UC1,
     * {@code PLAN-engine-gap-unique-computed} — coordinator D3): the per-row VALUE key members of
     * {@code is_(not_)unique_set}, materialised as columns of the reader's table so
     * {@code GroupSemantics.uniqueSetViolations} and its {@code KeyPart} identity run unchanged
     * over them — the column path stays byte-for-byte by construction. {@code table} may itself be
     * the merged-column table {@link #of} answered. A hull-keyed vector (a {@code duration(…)}
     * member, dur-order Q5 (a)) keys a positionable cell by its canonical hull text, an
     * unpositionable one by its text; a missing keeps its identity.
     *
     * @param table
     *            the table to grow
     * @param names
     *            the reserved names ({@link #valueColumnName}), parallel to {@code vectors}
     * @param vectors
     *            the per-row vectors over {@code table}'s rows
     * @param hullKeyed
     *            per vector, whether it keys by the duration hull
     * @return {@code table} when there is nothing to add, else it with the value columns appended
     */
    public static IDataTable withValues(IDataTable table, List<String> names, List<Vector> vectors,
            List<Boolean> hullKeyed)
    {
        if (names.isEmpty())
        {
            return table;
        }
        DataTableMeta meta = table.getMetaData();
        List<DataTableColumnMeta> metas = new ArrayList<>(Arrays.asList(meta.getColumns()));
        IDataTableColumn[] columns = new IDataTableColumn[names.size()];
        for (int i = 0; i < columns.length; i++)
        {
            Vector vector = vectors.get(i);
            DataValueType type = vector.declaredType() == DataValueType.MISSING
                    ? DataValueType.STRING
                    : vector.declaredType();
            metas.add(DataTableColumnMeta.builder().index(metas.size()).name(names.get(i))
                    .label(names.get(i)).type(type).build());
            columns[i] = new VectorColumn(table, vector, hullKeyed.get(i));
        }
        DataTableMeta grown = meta.toBuilder().columns(metas.toArray(DataTableColumnMeta[]::new))
                .build();
        return new GroupReadTable(table, grown, columns);
    }


    /**
     * Whether every name is a column of the evaluated table itself or resolves nowhere — exactly
     * when {@link #of} would answer the evaluated table: no name reads a merged column (a
     * {@code SUPP--} qualifier, a qualified {@code J.X}). OD-6
     * ({@code PLAN-merged-evaluation-table} W3e) counts such an operator by real row.
     *
     * @param ctx
     *            the evaluation context
     * @param names
     *            the column names, prefixes resolved
     * @return whether no name needs a merged column
     * @throws MergeErrorReadException
     *             when a SUPP qualifier a name reads carries a {@code MERGE_ERROR} (OD-7), as
     *             {@link #of} would
     */
    public static boolean readsOnlyItsOwnColumns(EvaluationContext ctx, Collection<String> names)
    {
        IDataTable table = ctx.getTable();
        DataTableMeta meta = table.getMetaData();
        for (String name : names)
        {
            if (meta.getColumnIndex(name) < 0 && resolve(ctx, table, name) != null)
            {
                return false;
            }
        }
        return true;
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
            JoinLookup lookup = ctx.joins().get(qualifier);
            String column = name.substring(qualifier.length() + 1);
            if (lookup == null || lookup.lacksColumnOnEveryRow(column))
            {
                return null; // resolves nowhere: the reader's absent-column machinery (§3.3)
            }
            DataValueType type = lookup.declaredTypeOf(column);
            return new Merged(new JoinedColumn(table, lookup, column),
                    type == DataValueType.MISSING ? DataValueType.STRING : type);
        }
        IDataTableColumn qualifierColumn = ctx.suppQualifier(name);
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

    /**
     * A qualified column read through its join lookup at the evaluated row — its name resolved once
     * per reader, not per cell ({@link JoinLookup#columnReader}, T1).
     */
    private static final class JoinedColumn extends AbstractDataTableColumn
    {

        private final IDataTable primary;

        private final java.util.function.LongFunction<IDataValue> reader;

        private JoinedColumn(IDataTable aPrimary, JoinLookup aLookup, String aColumn)
        {
            super(-1);
            primary = aPrimary;
            reader = aLookup.columnReader(aPrimary, aColumn, false);
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
            return reader.apply(aRow);
        }
    }


    /**
     * A value member's column (W1b UC1): the member's per-row cell, handed through so a
     * cell-sourced value keeps the exact cell and a missing its identity; hull-keyed, a
     * positionable duration is replaced by its canonical hull text. A per-row LIST is not a key
     * component — stage A refuses the statically typed one, an untyped one is the rule's ERROR here
     * (D35: never a silent text key).
     */
    private static final class VectorColumn extends AbstractDataTableColumn
    {

        private final IDataTable primary;

        private final Vector vector;

        private final boolean hullKeyed;

        private VectorColumn(IDataTable aPrimary, Vector aVector, boolean aHullKeyed)
        {
            super(-1);
            primary = aPrimary;
            vector = aVector;
            hullKeyed = aHullKeyed;
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
            TypedValue tv = vector.value((int) aRow);
            IDataValue cell = tv.cell();
            if (tv.isMissing())
            {
                return cell;
            }
            if (tv.resolved() instanceof Collection<?>)
            {
                throw new GroupSplice.GroupSpliceException("a value member of is_(not_)unique_set"
                        + " answered a list at row " + (aRow + 1)
                        + " — a list is not a key component; key on one value per row");
            }
            if (hullKeyed)
            {
                IsoDurationBounds.Hull hull = IsoDurationBounds.hull(cell.getValueAsString());
                if (hull != null)
                {
                    return DataValues.of(IsoDurationBounds.renderHull(hull.lower(), hull.upper()));
                }
            }
            return cell;
        }
    }

    @Override
    public long getRowCount()
    {
        return base.getRowCount();
    }


    @Override
    protected long sourceContentGeneration()
    {
        return base.contentGeneration();
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
