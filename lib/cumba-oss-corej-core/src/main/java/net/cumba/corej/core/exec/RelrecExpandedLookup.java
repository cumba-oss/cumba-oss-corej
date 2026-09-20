package net.cumba.corej.core.exec;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * {@link JoinLookup} for a row-EXPANDED forward RELREC join. Unlike a one-lookup-per-primary-row
 * (first-wins) join, this lookup is keyed by the row index of the <em>expanded</em> evaluation
 * table produced by {@link RelrecRowExpander}: every expanded row binds to exactly one related row,
 * so a scalar lookup is exact.
 *
 * <p>
 * The {@code table} argument is ignored — the expanded row index alone selects the bound (target
 * table, target row). {@code **} prefixes resolve per-row against the bound target's domain prefix.
 * </p>
 */
final class RelrecExpandedLookup implements JoinLookup
{

    /** Distinct related (target) tables, indexed by ordinal. */
    private final List<IDataTable> targetTables;

    /** Per expanded row: ordinal into {@link #targetTables} (>= 0; expansion is inner-join). */
    private final int[] targetOrdinal;

    /** Per expanded row: the bound target row index. */
    private final long[] targetRow;

    /** Resolved column index cache per target ordinal (caches -1 for absent columns). */
    private final ConcurrentHashMap<Short, ConcurrentHashMap<String, Integer>> colCache = new ConcurrentHashMap<>();

    RelrecExpandedLookup(List<IDataTable> targetTables, int[] targetOrdinal, long[] targetRow)
    {
        this.targetTables = targetTables;
        this.targetOrdinal = targetOrdinal;
        this.targetRow = targetRow;
    }


    @Override
    public @Nullable String lookup(IDataTable table, long row, String columnName)
    {
        if (columnName == null)
        {
            return null;
        }
        int r = (int) row;
        if (r < 0 || r >= targetRow.length)
        {
            return null;
        }
        int ord = targetOrdinal[r];
        if (ord < 0 || ord >= targetTables.size())
        {
            return null;
        }
        IDataTable target = targetTables.get(ord);
        int colIdx = columnIndex(target, ord, resolveColumn(target, columnName));
        if (colIdx < 0)
        {
            return null;
        }
        // A blank resolves per ScalarSemantics.resolvedString — type-INDEPENDENT: a missing cell
        // reads null whatever the column type (owner ruling 2026-09-18), a stored "" reads "".
        return ScalarSemantics.resolvedString(target, colIdx, targetRow[r]);
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * ⛔⛔ <b>This override is REQUIRED, and its absence was a live defect</b>
     * (PLAN-null-free-value-channel §3b). {@link JoinLookup}'s default wraps {@link #lookup}'s
     * {@code String} channel, which collapses all three not-supplied cases — "no such column", "no
     * bound row" and "present but MISSING" — into one {@code null} and therefore into one computed
     * {@code MissingValue.MIS}. That loses a SUPPLIED missing identity (D11/D12/D75a case 4) and
     * reads an absent CHARACTER column as missing where the constant {@code ""} is owed (D34 #3 /
     * D96a). {@link DatasetLookup} and {@link KeyMatchExpandedLookup} both rule these cases
     * properly (D72 / D72a-1 / D75a); this lookup was the one that did not.
     * </p>
     *
     * <p>
     * ⚠ <b>{@link #declaredTypeOf} is deliberately NOT overridden alongside it, and that is the
     * honest answer here rather than an omission.</b> {@link KeyMatchExpandedLookup} must override
     * both together because it holds ONE child table, so declaring a real {@code LONG}/
     * {@code DOUBLE} type while leaving the values on the text default would publish a vector whose
     * meta and cells disagree. This lookup binds a DIFFERENT target table per expanded row (and
     * resolves {@code **} per row against that target's domain), so no single declared type is
     * correct for the vector — {@code MISSING} ("unknown", D1/D2) is the truthful publication and
     * leaves the column un-gated by {@code ColumnTypeGate}, exactly as an absent column is. The
     * typed cells below are strictly better than the default either way: they keep the target
     * cell's own value and identity instead of round-tripping it through
     * {@code getAsDoubleCleaned}'s 12 significant digits.
     * </p>
     */
    @Override
    public IDataValue lookupValue(IDataTable table, long row, String columnName,
            boolean numericExpected)
    {
        int r = (int) row;
        if (columnName == null || r < 0 || r >= targetRow.length)
        {
            // Engine plumbing, not a value channel: no expanded row means no target cell and no
            // column to take a type from, so this is a genuinely COMPUTED non-result (D36 #8).
            return ScalarSemantics.computedMissing();
        }
        int ord = targetOrdinal[r];
        if (ord < 0 || ord >= targetTables.size())
        {
            return ScalarSemantics.computedMissing();
        }
        IDataTable target = targetTables.get(ord);
        int colIdx = columnIndex(target, ord, resolveColumn(target, columnName));
        if (colIdx < 0)
        {
            // ⭐⭐ §9c DOTTED PARITY (owner ruling, 2026-09-18): the column is absent from the
            // bound target ENTIRELY, so it takes the RULE's expected default exactly as an absent
            // primary column does (D72/D76/§1b): numeric -> MissingValue.MIS, otherwise the
            // CONSTANT "" — a present empty string, never a computed MIS for a char read (the D96a
            // absent-vs-blank regression, where phase 6c's D34 #5 order arm sorts a MIS below every
            // value). Identical to DatasetLookup's and KeyMatchExpandedLookup's arms, deliberately.
            //
            // ⭐ The per-row binding does NOT make the flag incoherent, and that is worth stating
            // because declaredTypeOf below reaches the opposite conclusion for itself: the
            // EXPECTATION is a property of the RULE and is constant for the whole vector regardless
            // of which target a row binds to, whereas a DECLARED TYPE is a property of the bound
            // target and therefore varies per row. Different question, different answer.
            //
            // ⚑ The previous rationale — "this seam cannot READ an expectation, lookupValue
            // receives no EvaluationContext" — was correct about the mechanism and wrong about the
            // conclusion: §9c makes it a reason to change the channel, which numericExpected is.
            return numericExpected ? ScalarSemantics.computedMissing()
                    : DataValueSupport.defaultForType(DataValueType.STRING);
        }
        // ⛔ D75a case 4: a bound target cell that is a genuine MissingValue is a SUPPLIED value,
        // distinct from "" (D11/D12), and passes through unchanged — which is precisely what the
        // inherited String-channel default could not express.
        return target.getColumn(colIdx).getDataValue(targetRow[r]);
    }


    /**
     * The {@code **}-resolved column name for {@code target}: a {@code **} prefix resolves per row
     * against the bound target's own domain prefix, every other name is taken verbatim. Extracted
     * so {@link #lookup}, {@link #lookupValue} and {@link #hasColumn} cannot drift apart on it.
     */
    private static String resolveColumn(IDataTable target, String columnName)
    {
        if (!columnName.startsWith("**"))
        {
            return columnName;
        }
        return java.util.Objects.requireNonNullElse(OperationExecutor.variableWildcardPrefix(target,
                OperationExecutor.domainPrefix(target)), "") + columnName.substring(2);
    }


    /** The cached column index of {@code resolvedCol} in target ordinal {@code ord} (-1 absent). */
    private int columnIndex(IDataTable target, int ord, String resolvedCol)
    {
        ConcurrentHashMap<String, Integer> cache = colCache.computeIfAbsent((short) ord,
                _ -> new ConcurrentHashMap<>());
        IDataTable t = target;
        return cache.computeIfAbsent(resolvedCol, c -> t.getMetaData().getColumnIndex(c));
    }


    @Override
    public boolean hasColumn(IDataTable table, long row, String columnName)
    {
        if (columnName == null)
        {
            return false;
        }
        int r = (int) row;
        if (r < 0 || r >= targetRow.length)
        {
            return false;
        }
        int ord = targetOrdinal[r];
        if (ord < 0 || ord >= targetTables.size())
        {
            return false;
        }
        IDataTable target = targetTables.get(ord);
        return target.getMetaData().getColumnIndex(resolveColumn(target, columnName)) >= 0;
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * RELREC expansion is an inner join — every expanded row binds to exactly one related row — so
     * any valid row is matched. Unreachable in practice: {@code _matched_} under an inner join is a
     * stage-A load error (D88e), so no loadable rule consumes the flag through this lookup;
     * answered honestly from the binding arrays regardless.
     * </p>
     */
    @Override
    public boolean matchedRow(IDataTable table, long row)
    {
        int r = (int) row;
        return r >= 0 && r < targetRow.length && targetOrdinal[r] >= 0
                && targetOrdinal[r] < targetTables.size();
    }


    @Override
    public String getDatasetName()
    {
        return "RELREC";
    }
}
