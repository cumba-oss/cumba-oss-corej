package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The de-duplication identity of a <b>record-level</b> finding on an expanded table
 * ({@code PLAN-merged-evaluation-table} §3.8, MET #22, P-4, P-18): two findings of one (rule,
 * dataset, level) execution are <b>one</b> when equal in their unit (the real row; + the column of
 * a per-(column, row) finding), the full {@code values} map (its key set included) and the
 * <em>value identity</em> of every entry that can differ between two rows of one primary record.
 *
 * <p>
 * <b>Which entries can differ.</b> Two merged rows of one real row read the same primary record, so
 * every primary column, every dataset fact and every metadata value is the same cell on both — the
 * real row decides them, and with it {@code USUBJID}, {@code SEQ} and the EC-40 keys, all read from
 * the primary's own columns (which is why they are not in the key: the real row is). What can
 * differ is what the merge put beside the record: a qualified {@code J.X} (the related record's
 * cell), a {@code SUPP--} qualifier (read through the same merge), and a per-row {@code $} binding
 * (which may read either). For those the key carries the <b>cell's identity</b>, read at the emit
 * site — a missing is its {@link MissingValue} (so {@code MIS} and {@code MIS_A} are two values,
 * D11), a present value its text, a collection the collection itself — never the rendered string,
 * which makes a missing and a literal {@code "."} equal (P-4, P-18).
 * </p>
 *
 * <p>
 * A Group finding is not keyed here: one is emitted per block, and blocks are formed by the
 * grouping key's identity ({@code Violation.Unit.Group}), so two of them never report the same
 * thing (owner Q-A: the anchor row's identity is not the finding's).
 * </p>
 *
 * <p>
 * Costs: built once per execution, and only on a table that repeats a real row
 * ({@link RealRows#ofRepeating}); per candidate finding one key object, plus one small array when
 * the projection reads a merged entry. The identities of the stored findings are what the sink
 * keeps.
 * </p>
 */
final class FindingIdentity
{

    /** The identity of a merged entry the row's merge does not carry (omitted from values). */
    private static final Object ABSENT = new Object()
    {

        @Override
        public String toString()
        {
            return "<absent>";
        }
    };

    /** One Output_Variables entry whose value can differ between two rows of one record. */
    private sealed interface Entry
    {

        Object identityAt(IDataTable table, int row);
    }


    /** A qualified {@code J.X} read through the join's lookup (the view's cell). */
    private record Joined(JoinLookup lookup, String column) implements Entry
    {

        @Override
        public Object identityAt(IDataTable table, int row)
        {
            if (!lookup.hasColumn(table, row, column))
            {
                return ABSENT;
            }
            return cellIdentity(lookup.lookupValue(table, row, column, false));
        }
    }


    /** A bare {@code SUPP--} qualifier column. */
    private record Qualifier(IDataTableColumn column) implements Entry
    {

        @Override
        public Object identityAt(IDataTable table, int row)
        {
            return cellIdentity(column.getDataValue(row));
        }
    }


    /** A per-row {@code $} binding's vector. */
    private record PerRowBinding(Vector vector) implements Entry
    {

        @Override
        public Object identityAt(IDataTable table, int row)
        {
            TypedValue value = vector.value(row);
            return value.resolved() instanceof Collection<?> c ? c : cellIdentity(value.cell());
        }
    }


    /**
     * The key: equal exactly when the two findings are one.
     *
     * @param realRow
     *            the real row
     * @param column
     *            the column of a per-(column, row) finding, else {@code null}
     * @param values
     *            the finding's values map
     * @param cells
     *            the identities of the merged entries, in effective order, or {@code null} when the
     *            projection reads none
     */
    private record Key(long realRow, @Nullable String column, Map<String, String> values,
            @Nullable List<Object> cells)
    {
    }

    private final Entry[] entries;

    private FindingIdentity(Entry[] aEntries)
    {
        entries = aEntries;
    }


    /**
     * The identity of the findings of one execution.
     *
     * @param ctx
     *            the execution's context
     * @param outputVars
     *            the effective Output_Variables (unresolved; resolved here as the projection
     *            resolves them)
     * @return the identity
     */
    static FindingIdentity of(EvaluationContext ctx, List<String> outputVars)
    {
        if (outputVars == null || outputVars.isEmpty())
        {
            return new FindingIdentity(new Entry[0]);
        }
        IDataTable table = ctx.getTable();
        List<Entry> out = new ArrayList<>();
        for (String name : RuleRunner.resolveOutputEntries(outputVars, ctx))
        {
            Entry entry = entryOf(ctx, table, name);
            if (entry != null)
            {
                out.add(entry);
            }
        }
        return new FindingIdentity(out.toArray(Entry[]::new));
    }


    private static @Nullable Entry entryOf(EvaluationContext ctx, IDataTable table, String name)
    {
        if (name.startsWith("$"))
        {
            Object raw = ctx.getVariables().get(name);
            Object value = raw instanceof BindingValue compiled ? compiled.reportedHandOver(ctx)
                    : raw;
            // A dataset-scope binding is one value for the execution: the real row decides nothing
            // and the values map already carries it.
            return value instanceof Vector perRow ? new PerRowBinding(perRow) : null;
        }
        String qualifier = MatchDataset.qualifierOf(name);
        if (qualifier != null)
        {
            JoinLookup lookup = ctx.joins().get(qualifier);
            return lookup == null ? null
                    : new Joined(lookup, name.substring(qualifier.length() + 1));
        }
        if (table.getMetaData().getColumnIndex(name) >= 0)
        {
            return null; // a primary column: decided by the real row
        }
        IDataTableColumn column = ctx.suppQualifier(name);
        return column == null ? null : new Qualifier(column);
    }


    /**
     * The finding's identity at evaluation row {@code row}.
     *
     * @param table
     *            the evaluation table
     * @param row
     *            the evaluation (merged) row
     * @param realRow
     *            its real row
     * @param column
     *            the column of a per-(column, row) finding, else {@code null}
     * @param values
     *            the finding's values map
     * @return the key
     */
    Object at(IDataTable table, int row, long realRow, @Nullable String column,
            Map<String, String> values)
    {
        if (entries.length == 0)
        {
            return new Key(realRow, column, values, null);
        }
        Object[] cells = new Object[entries.length];
        for (int i = 0; i < cells.length; i++)
        {
            cells[i] = entries[i].identityAt(table, row);
        }
        return new Key(realRow, column, values, Arrays.asList(cells));
    }


    /** A cell's value identity: its missing marker, else its text (P-4, D11). */
    static Object cellIdentity(IDataValue cell)
    {
        MissingValue missing = TypedValue.missingIdentityOf(cell);
        return missing != null ? missing : cell.getValueAsString();
    }
}
