package net.cumba.corej.core.exec;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.ColumnCachedDataTable;

import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Phase 6b (D3/D14 — R11 closed by construction): materialises an operation's COMPUTED target
 * expression ({@code Operation.getNameExpr()}) into a synthetic column appended to the resolved
 * target table, so {@code max(num(WEIGHT), group=[USUBJID])} runs the untouched {@code max} domain
 * logic over an ordinary column read. The operation's parameters thereby accept any expression of
 * the right type without the executor's 55 evaluators learning anything new — the exclusion R11
 * documented was {@code OperationExpressionParser.stringOf} throwing on any {@code Call}, a
 * positional artifact this class removes.
 *
 * <p>
 * The synthetic column keeps the expression's typed cells verbatim ({@link TypedValue#cell()} — the
 * exact cell for a cell-backed value, the memoised typed wrapper for a computed one), so the
 * declared-type-sensitive readers downstream — the column-type gate, numeric-vs-textual equality —
 * see the computed type, not a stringified round-trip (D84).
 * </p>
 */
public final class TargetExpressionMaterializer
{

    /** The appended column's name — reserved; suffixed if the table already carries it. */
    public static final String SYNTHETIC_NAME = "__computed_target__";

    private TargetExpressionMaterializer()
    {
    }


    /**
     * Evaluates {@code op}'s computed target against {@code targetTable} and returns the pair
     * (augmented table, op copy whose {@code name} is the synthetic column), or {@code null} when a
     * referenced column is unresolvable — the caller answers the operation's own
     * {@code EmptyResult}, exactly as over an absent target column.
     */
    static @Nullable Materialized materialize(Operation op, IDataTable targetTable,
            Map<String, Object> priorResults)
    {
        Expr expr = op.getNameExpr();
        if (expr == null)
        {
            throw new IllegalStateException("no computed target on operation " + op.getOperator());
        }
        // D76: the computed target is its own expression, so its own type expectations decide
        // the absent-column default (an arithmetic operand is numeric-expected ⇒ MIS).
        EvaluationContext ctx = EvaluationContext.builder().table(targetTable)
                .variables(priorResults)
                .numericExpectedColumns(net.cumba.corej.core.expr.typed.TypeExpectations
                        .of(java.util.List.of(expr)).numericDefaultColumns())
                .build();
        Vector v = ExprCompiler.evaluateValueExpression(expr, ctx);
        if (v == null)
        {
            return null;
        }
        int rowCount = (int) targetTable.getRowCount();
        // Materialise EAGERLY, on this thread: TypedValue.cell() memoises and ComputedVector is
        // one-chunk scoped, so a lazy view would not be safe to share; the copy also fixes the
        // cells so downstream readers see one immutable column.
        IDataValue[] cells = new IDataValue[rowCount];
        // ⚑ @Nullable elements, deliberately: null is this engine's encoding of a missing cell —
        // it is what Vector.resolvedObject answers and what VectorColumn.getValue is declared to
        // return — so the array type says so rather than letting the null travel undeclared.
        @Nullable
        Object[] resolved = new @Nullable Object[rowCount];
        for (int r = 0; r < rowCount; r++)
        {
            TypedValue tv = v.value(r);
            cells[r] = tv.cell();
            resolved[r] = tv.resolved();
        }
        String name = uniqueName(targetTable);
        // Compose the augmented view directly (base columns by reference + the synthetic column)
        // rather than through MergeDataTable: the merge view rebuilds column meta via toBuilder(),
        // which a provider-independent meta implementation (e.g. the testkit's mocks) need not
        // support. Only the executor-observable properties — index, name, label, declared type —
        // are carried; the operation evaluators read nothing else.
        DataTableMeta baseMeta = targetTable.getMetaData();
        int n = baseMeta.getColumnCount();
        DataTableColumnMeta[] metaCols = new DataTableColumnMeta[n + 1];
        net.cumba.datatable.IDataTableColumn[] cols = new net.cumba.datatable.IDataTableColumn[n
                + 1];
        for (int c = 0; c < n; c++)
        {
            DataTableColumnMeta cm = baseMeta.getColumn(c);
            String label = cm.getLabel() != null ? cm.getLabel() : cm.getName();
            metaCols[c] = DataTableColumnMeta.builder().index(c).name(cm.getName()).label(label)
                    .type(cm.getType()).build();
            cols[c] = targetTable.getColumn(c);
        }
        metaCols[n] = DataTableColumnMeta.builder().index(n).name(name).label(name)
                .type(v.declaredType()).build();
        cols[n] = new VectorColumn(n, cells, resolved);
        DataTableMeta mergedMeta = DataTableMeta.builder().name(baseMeta.getName())
                .label(baseMeta.getLabel() != null ? baseMeta.getLabel() : baseMeta.getName())
                .rowCount(rowCount).totalRowCount(rowCount).columns(metaCols).build();
        IDataTable merged = new ColumnCachedDataTable(mergedMeta, cols);
        return new Materialized(merged, withSyntheticName(op, name));
    }

    /** The augmented table and the operation rewritten onto the synthetic column. */
    record Materialized(IDataTable table, Operation op)
    {
    }

    private static String uniqueName(IDataTable table)
    {
        String name = SYNTHETIC_NAME;
        int i = 2;
        while (table.getMetaData().getColumnIndex(name) >= 0)
        {
            name = SYNTHETIC_NAME + i++;
        }
        return name;
    }


    /**
     * A full reflective shallow copy with {@code name} swapped to the synthetic column and the
     * expression cleared — reflective so a future {@link Operation} field cannot be silently
     * dropped here (the D93d field-by-field-clone lesson).
     */
    private static Operation withSyntheticName(Operation op, String syntheticName)
    {
        Operation copy = new Operation();
        for (Field f : Operation.class.getDeclaredFields())
        {
            if (Modifier.isStatic(f.getModifiers()))
            {
                continue;
            }
            try
            {
                f.setAccessible(true);
                f.set(copy, f.get(op));
            }
            catch (ReflectiveOperationException ex)
            {
                throw new IllegalStateException("cannot copy Operation field " + f.getName(), ex);
            }
        }
        copy.setName(syntheticName);
        copy.setNameExpr(null);
        return copy;
    }

    /**
     * An {@link AbstractDataTableColumn} over the eagerly-materialised cells: cell-backed values
     * pass through verbatim (type and {@code MissingValue} identity kept — never a text round-trip,
     * D84), computed values carry their derived typed wrapper.
     */
    private static final class VectorColumn extends AbstractDataTableColumn
    {

        private final IDataValue[] cells;

        private final @Nullable Object[] resolved;

        VectorColumn(int index, IDataValue[] cells, @Nullable Object[] resolved)
        {
            super(index);
            this.cells = cells;
            this.resolved = resolved;
        }


        @Override
        public long getRowCount()
        {
            return cells.length;
        }


        @Override
        public @Nullable Object getValue(long row)
        {
            return resolved[(int) row];
        }


        @Override
        public IDataValue getDataValue(long row)
        {
            return cells[(int) row];
        }
    }
}
