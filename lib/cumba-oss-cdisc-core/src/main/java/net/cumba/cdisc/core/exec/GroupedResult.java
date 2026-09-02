package net.cumba.cdisc.core.exec;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Holds the result of a grouped operation (e.g. {@code min_date} grouped by {@code USUBJID}). The
 * {@link #results} map is keyed by the joined group column values, and each entry holds the
 * aggregate result for that group.
 *
 * <p>
 * When used in a check, {@link #getForRow} resolves the correct per-row value by reading the group
 * columns from the evaluation table.
 * </p>
 *
 * <p>
 * {@link #missingKeyDefault} is the value an <em>absent</em> group key resolves to on every scalar
 * path (comparison LHS/RHS and report output). It is keyed to the operation, not the value type —
 * resolving by operation rather than by {@code instanceof Long} avoids mis-coalescing a
 * {@code max}/{@code min} over an integer (or epoch-backed date) column to 0.
 *
 * <p>
 * <b>Since EC-45 the operation <em>declares</em> that value</b>, as
 * {@code OperationType.getEmptyResult()}, and {@code OperationExecutor.declaredGrouped} is what
 * passes it here — so a construction site can no longer pick a default by copying whichever
 * constructor sat next door. Read the classification (codomain ⇒ value) there, not from a list
 * here: it moves, and a duplicated list rots. In outline: a count declares {@code 0L}, a set
 * {@code List.of()}, a boolean predicate {@code false}, a closed-world scalar lookup {@code ""},
 * and an extremum or derived value {@code null} — the calculation was not possible, which the
 * comparison folds to {@code ""} and the check fires over.
 * </p>
 */
public record GroupedResult(List<String> groupColumns, Map<String, Object> results,
        @Nullable Object missingKeyDefault)
{

    private static final String KEY_SEPARATOR = "\0";

    /**
     * Builds a grouped result whose absent group keys resolve to {@code null}.
     *
     * <p>
     * ⚠ <b>Not the default for "everything except {@code record_count}"</b> — that was the
     * pre-EC-45 convention and it is what let the same {@code distinct} operator answer
     * {@code List.of()} ungrouped and {@code null} grouped. {@code null} is now one declared value
     * among five ({@link net.cumba.cdisc.core.model.EmptyResult#MISSING}), correct only for the
     * extremum / derived-value codomain. New evaluators should go through
     * {@code OperationExecutor.declaredGrouped} and let the operator's classification choose; this
     * constructor remains for the two knowability-conditioned sites in {@code evalSuppQnamJoin} and
     * for direct construction in tests.
     * </p>
     */
    public GroupedResult(List<String> groupColumns, Map<String, Object> results)
    {
        this(groupColumns, results, null);
    }


    /**
     * Builds the group key for a given row in the given table by reading the values of the group
     * columns.
     */
    static String buildKey(DataTableMeta meta, net.cumba.datatable.IDataTable table,
            List<String> groupCols, long row)
    {
        StringJoiner sj = new StringJoiner(KEY_SEPARATOR);
        for (String col : groupCols)
        {
            int idx = meta.getColumnIndex(col);
            if (idx < 0)
            {
                sj.add("");
            }
            else
            {
                IDataValue dv = table.getColumn(idx).getDataValue(row);
                // ⚑ LOCKSTEP with IndexHelper.buildGroupKey. This is the per-row LOOKUP side of
                // the same key encoding: groupByPresent builds a block's key from its
                // representative row through buildGroupKey, and getForRow re-derives that key here
                // from an arbitrary row. If the two disagree about a cell's identity, every lookup
                // on a blank-keyed row silently misses and the operation reads "no value" — so
                // both must render the same KeyPart classification. Since W38-A1 (Fix #249) that
                // rendering keeps blank identities apart: "" for an empty cell, the marker token
                // for a genuine missing (KeyPart.reportingForm — presentation only, never
                // re-parsed).
                sj.add(GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(dv).reportingForm());
            }
        }
        return sj.toString();
    }


    /**
     * Builds a group key from pre-resolved string values, in the same encoding as
     * {@link #buildKey(DataTableMeta, net.cumba.datatable.IDataTable, List, long)} (the same
     * {@code KEY_SEPARATOR} join, {@code null} rendered as {@code ""}). Used when the key
     * components are read from a foreign dataset (e.g. the SUPP-QNAM join builds keys from
     * {@code USUBJID} + {@code IDVARVAL}) so they resolve against a parent-row lookup keyed on the
     * same columns.
     *
     * <p>
     * ⚠ This overload lives in the <b>string domain</b>: it can spell a real value or {@code ""},
     * never a {@code KeyPart.Missing} marker token ({@code W38-A1}). When a map keyed this way is
     * probed with the cell-classified
     * {@link #buildKey(DataTableMeta, net.cumba.datatable.IDataTable, List, long)} (the SUPP-QNAM
     * join: string-side keys from the supplemental rows, cell-side probes from the parent rows), a
     * probe whose cell is genuinely <em>missing</em> renders the marker token and misses by
     * construction — the ruled part-4 outcome (a missing cell equals no string value, including the
     * {@code ""} a foreign-side {@code null} folds to), and the group default answers. ⛔ A build
     * side that walks <em>the probed table itself</em> must not key through this overload — it must
     * use the cell-classified builder, or every blank-keyed row strands on the default (see the
     * LOCKSTEP notes at the dictionary evaluators in {@code OperationExecutor}).
     * </p>
     */
    static String buildKey(List<@Nullable String> values)
    {
        StringJoiner sj = new StringJoiner(KEY_SEPARATOR);
        for (String v : values)
        {
            sj.add(v == null ? "" : v);
        }
        return sj.toString();
    }


    /**
     * Resolves the grouped result for the given row by looking up the row's group key in the
     * results map.
     */
    public @Nullable Object getForRow(EvaluationContext ctx, long row)
    {
        String key = buildKey(ctx.getTable().getMetaData(), ctx.getTable(), groupColumns, row);
        return results.get(key);
    }


    /**
     * The value an absent group key resolves to on every scalar path — the operator's declared
     * {@link net.cumba.cdisc.core.model.EmptyResult}, e.g. {@code 0L} for {@code record_count} (a
     * group with zero matching rows counts as 0, not "no value"), {@code List.of()} for
     * {@code distinct}, {@code null} for a date extremum. The default is fixed at construction from
     * the operation — see {@link #missingKeyDefault} — so the comparison LHS/RHS and the report
     * output all coalesce identically and the two engines cannot drift.
     */
    public @Nullable Object defaultForMissingKey()
    {
        return missingKeyDefault;
    }


    /**
     * Like {@link #getForRow} but substitutes {@link #defaultForMissingKey()} for an absent key —
     * the per-row value a comparison operand reads, so a subject with zero {@code record_count}
     * matches behaves as 0 rather than being silently skipped. Both the legacy {@code forEachValue}
     * cascade and the native {@code ExprCompiler.variableVector} resolve through this single source
     * so the two engines cannot drift.
     */
    public @Nullable Object getForRowOrDefault(EvaluationContext ctx, long row)
    {
        Object v = getForRow(ctx, row);
        return v != null ? v : defaultForMissingKey();
    }

}
