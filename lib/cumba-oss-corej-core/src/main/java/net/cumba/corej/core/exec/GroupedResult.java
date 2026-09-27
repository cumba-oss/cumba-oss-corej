package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.GroupKey;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Holds the result of a grouped operation (e.g. {@code min_date} grouped by {@code USUBJID}). The
 * {@link #results} map is keyed by the group's key, and each entry holds the aggregate result for
 * that group.
 *
 * <p>
 * When used in a check, {@link #getForRow} resolves the correct per-row value by reading the group
 * columns from the evaluation table.
 * </p>
 *
 * <p>
 * ⭐ <b>One key identity</b> ({@code PLAN-grouping-key-identity}). In {@link KeyMode#IDENTITY} —
 * every result but the text-carried family — a group's result is stored under its {@link GroupKey},
 * built from the {@code KeyPart} identity of each group column
 * ({@link GroupKeyPolicy#keyIdentity}), and a row looks its group up under the same key. The groups
 * are formed on the datatable index, which partitions by that same identity (exact,
 * {@code -0.0}-aware — {@code KeyHashSupport}), so forming and looking up can no longer disagree.
 * Until this plan both sides rendered the key as text: two groups that render alike
 * ({@code 4.9999999999994} and {@code 5.0}, or {@code -0.0} and {@code 0.0}) shared one map key,
 * the later group's value overwrote the earlier one's, and the earlier group's rows read a value
 * that was not theirs.
 * </p>
 *
 * <p>
 * {@link KeyMode#TEXT} keeps the {@code "\0"}-joined {@link GroupKeyPolicy.KeyPart#reportingForm()}
 * key for the <b>text-carried</b> family only — the {@code SUPP--} QNAM join and the
 * {@code RDOMAIN}-keyed maps, whose build side is text by nature (register {@code D4-R5}: <i>"agre
 * to keep this special"</i>; {@code RRK E2-text}: <i>"In these situations we must still use a text
 * join."</i>).
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
 *
 * @param groupColumns
 *            the group columns, in key order
 * @param results
 *            the per-group results, keyed by {@link #keyMode}'s key
 * @param missingKeyDefault
 *            the value an absent group key resolves to
 * @param keyMode
 *            how the keys of {@code results} are built
 * @param keyTypes
 *            the declared kinds of the group columns on the table the result was grouped on — the
 *            Q2 type check's reference ({@link #requireCompatibleKeys}); {@code null} when unknown
 *            (a text-mode result, or one constructed directly)
 */
public record GroupedResult(List<String> groupColumns, Map<?, ?> results,
        @Nullable Object missingKeyDefault, KeyMode keyMode, @Nullable KeyTypes keyTypes)
{

    private static final String KEY_SEPARATOR = "\0";

    private static final GroupKeyPolicy KEY_POLICY = GroupKeyPolicy.KEEP_MISSING_KEYS;

    /**
     * How a result's keys are built — decided where the result is built, and the lookup follows it.
     */
    public enum KeyMode
    {

        /**
         * The {@link GroupKey} of the group columns' identities
         * ({@link GroupKeyPolicy#keyIdentity}; an absent column is {@code ""}). Every grouped
         * result but the text-carried family.
         */
        IDENTITY,

        /**
         * The {@code "\0"}-joined {@link GroupKeyPolicy.KeyPart#reportingForm()} of the group
         * columns (an absent column is {@code ""}) — the {@code SUPP--} QNAM join and the
         * {@code RDOMAIN}-keyed maps, whose build side is text (register {@code D4-R5}). ⛔ Never
         * for a result whose groups were formed on the datatable index: two groups that render
         * alike would share a key.
         */
        TEXT
    }


    /**
     * The declared kinds of a result's group columns on the table it was grouped on, for the
     * cross-table key-type check (owner 2026-09-27, Q2: <i>"Beside this, I agree to error
     * out."</i>).
     *
     * @param dataset
     *            the grouped table's name, for the error message
     * @param kinds
     *            {@link ColumnTypeGate#kindOf} of each group column's declared type, index-aligned
     *            with {@link GroupedResult#groupColumns()}; {@code null} for an absent column or a
     *            type the gate does not classify ({@code D4-R6a})
     */
    public record KeyTypes(String dataset, List<ColumnTypeGate.@Nullable Kind> kinds)
    {

        public KeyTypes
        {
            kinds = Collections.unmodifiableList(new ArrayList<>(kinds));
        }


        /**
         * The kinds of {@code aColumns} on {@code aTable}.
         *
         * @param aTable
         *            the table the result is grouped on
         * @param aColumns
         *            the group columns
         * @return the key types
         */
        static KeyTypes of(IDataTable aTable, List<String> aColumns)
        {
            DataTableMeta meta = aTable.getMetaData();
            List<ColumnTypeGate.@Nullable Kind> kinds = new ArrayList<>(aColumns.size());
            for (String column : aColumns)
            {
                kinds.add(kindOf(meta, column));
            }
            return new KeyTypes(String.valueOf(meta.getName()), kinds);
        }
    }

    /**
     * Builds an {@link KeyMode#IDENTITY} result with unknown key types whose absent group keys
     * resolve to {@code missingKeyDefault} — for direct construction in tests and by callers that
     * key a result themselves.
     *
     * @param groupColumns
     *            the group columns
     * @param results
     *            the per-group results, keyed by {@link #identityKey}
     * @param missingKeyDefault
     *            the value an absent group key resolves to
     */
    public GroupedResult(List<String> groupColumns, Map<?, ?> results,
            @Nullable Object missingKeyDefault)
    {
        this(groupColumns, results, missingKeyDefault, KeyMode.IDENTITY, null);
    }


    /**
     * Builds a grouped result whose absent group keys resolve to {@code null}.
     *
     * <p>
     * ⚠ <b>Not the default for "everything except {@code record_count}"</b> — that was the
     * pre-EC-45 convention and it is what let the same {@code distinct} operator answer
     * {@code List.of()} ungrouped and {@code null} grouped. {@code null} is now one declared value
     * among five ({@link net.cumba.corej.core.model.EmptyResult#MISSING}), correct only for the
     * extremum / derived-value codomain. New evaluators should go through
     * {@code OperationExecutor.declaredGrouped} and let the operator's classification choose; this
     * constructor remains for direct construction in tests.
     * </p>
     */
    public GroupedResult(List<String> groupColumns, Map<?, ?> results)
    {
        this(groupColumns, results, null);
    }


    /**
     * The {@link KeyMode#IDENTITY} key of {@code row} in {@code table} over {@code groupCols}: the
     * {@link GroupKey} of each column's {@link GroupKeyPolicy#keyIdentity} — {@code ""} for a
     * column the table lacks (EC-44: an absent column partitions nothing, on the building and the
     * probing side alike).
     *
     * <p>
     * ⚑ The one key derivation of a grouped result: the block side ({@code IndexHelper}) and the
     * probe side ({@link #getForRow}) both call it, so they cannot drift apart.
     * </p>
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


    private static Object identityOf(DataTableMeta meta, IDataTable table, String col, long row)
    {
        int idx = meta.getColumnIndex(col);
        return idx < 0 ? "" : KEY_POLICY.keyIdentity(table.getColumn(idx).getDataValue(row));
    }


    /**
     * The {@link KeyMode#TEXT} key of {@code row} in {@code table} over {@code groupCols}: the
     * {@code "\0"}-joined {@link GroupKeyPolicy.KeyPart#reportingForm()} of each column, {@code ""}
     * for a column the table lacks. ⛔ Text-carried results only ({@link KeyMode#TEXT}).
     */
    static String textKey(DataTableMeta meta, IDataTable table, List<String> groupCols, long row)
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
                // Presentation text, never re-parsed: a missing cell renders its SOH marker token,
                // which no text value from the build side can equal (ruling part 4).
                sj.add(KEY_POLICY.keyPart(dv).reportingForm());
            }
        }
        return sj.toString();
    }


    /**
     * Builds a {@link KeyMode#TEXT} key from pre-resolved string values, in the same encoding as
     * {@link #textKey} (the same {@code KEY_SEPARATOR} join, {@code null} rendered as {@code ""}).
     * Used when the key components are read from a foreign dataset as text — the SUPP-QNAM join
     * builds keys from {@code USUBJID} + {@code IDVARVAL} — so they resolve against a parent-row
     * probe keyed on the same columns.
     *
     * <p>
     * ⚠ This lives in the <b>string domain</b>: it can spell a real value or {@code ""}, never a
     * {@code KeyPart.Missing} marker token ({@code W38-A1}). A probe whose cell is genuinely
     * <em>missing</em> renders the marker token and misses by construction — the ruled part-4
     * outcome (a missing cell equals no string value, including the {@code ""} a foreign-side
     * {@code null} folds to), and the group default answers. ⛔ A result whose groups are formed on
     * the probed table itself must key through {@link #identityKey}, never through this.
     * </p>
     */
    static String textKey(List<@Nullable String> values)
    {
        StringJoiner sj = new StringJoiner(KEY_SEPARATOR);
        for (String v : values)
        {
            sj.add(v == null ? "" : v);
        }
        return sj.toString();
    }


    /**
     * The key of {@code row} of {@code table} in this result's {@link #keyMode}.
     */
    Object keyOf(IDataTable table, long row)
    {
        DataTableMeta meta = table.getMetaData();
        return keyMode == KeyMode.TEXT ? textKey(meta, table, groupColumns, row)
                : identityKey(meta, table, groupColumns, row);
    }


    /**
     * Resolves the grouped result for the given row by looking up the row's group key in the
     * results map.
     */
    public @Nullable Object getForRow(EvaluationContext ctx, long row)
    {
        return results.get(keyOf(ctx.getTable(), row));
    }


    /**
     * The value an absent group key resolves to on every scalar path — the operator's declared
     * {@link net.cumba.corej.core.model.EmptyResult}, e.g. {@code 0L} for {@code record_count} (a
     * group with zero matching rows counts as 0, not "no value"), {@code List.of()} for
     * {@code distinct}, {@code null} for a date extremum. The default is fixed at construction from
     * the operation — see {@link #missingKeyDefault} — so the comparison LHS/RHS and the report
     * output all coalesce identically, from one definition.
     */
    public @Nullable Object defaultForMissingKey()
    {
        return missingKeyDefault;
    }


    /**
     * Like {@link #getForRow} but substitutes {@link #defaultForMissingKey()} for an absent key —
     * the per-row value a comparison operand reads, so a subject with zero {@code record_count}
     * matches behaves as 0 rather than being silently skipped. {@code ExprCompiler.variableVector}
     * resolves through this single source.
     */
    public @Nullable Object getForRowOrDefault(EvaluationContext ctx, long row)
    {
        Object v = getForRow(ctx, row);
        return v != null ? v : defaultForMissingKey();
    }


    /**
     * ⭐ The cross-table key-type check (owner 2026-09-27, Q2: <i>"on relrec/ supp this might need
     * to be supported and we have a general flag for text merge. Beside this, I agree to error
     * out."</i>; register {@code D4-R1/R2}: <i>"I do not want a silent mismatch."</i>). A result
     * grouped on one table and read against another looks each row up by the typed identity, so a
     * group column that is {@code CHARACTER} on one side and {@code NUMERIC} on the other would
     * silently match nothing (a {@code String} never equals a {@code Double}). This throws instead,
     * and the rule ERRORs.
     *
     * <p>
     * Run it once where the result is bound to an evaluation table — never per row. It never throws
     * for: a {@link KeyMode#TEXT} result (the text joins: {@code SUPP--}, {@code RDOMAIN}, register
     * {@code D4-R5}); a result whose key types are unknown; a column absent on either side; a kind
     * the gate does not classify on either side (register {@code D4-R6a}: an all-NA {@code .rds}
     * column arrives as {@code BOOLEAN}). {@code LONG} against {@code DOUBLE} is {@code NUMERIC} on
     * both sides and matches.
     * </p>
     *
     * <p>
     * ⚑ {@code Join_As_String} — the owner's "general flag for text merge" — lives on
     * {@code Match_Datasets} entries, and no grouped operation reads it (measured 2026-09-27: its
     * one consumer is the key-match expander, and no rule in either corpus authors it), so it has
     * no arm here.
     * </p>
     *
     * @param evalTable
     *            the table the result is read against
     * @throws JoinKeyTypeMismatchException
     *             when a group column's kind differs between the two tables
     */
    public void requireCompatibleKeys(IDataTable evalTable)
    {
        KeyTypes types = keyTypes;
        if (keyMode == KeyMode.TEXT || types == null)
        {
            return;
        }
        DataTableMeta meta = evalTable.getMetaData();
        for (int i = 0; i < groupColumns.size(); i++)
        {
            ColumnTypeGate.Kind grouped = types.kinds().get(i);
            if (grouped == null)
            {
                continue;
            }
            String column = groupColumns.get(i);
            ColumnTypeGate.Kind evaluated = kindOf(meta, column);
            if (evaluated != null && evaluated != grouped)
            {
                throw JoinKeyTypeMismatchException.forGroupedLookup(column, types.dataset(),
                        grouped, column, String.valueOf(meta.getName()), evaluated);
            }
        }
    }


    /**
     * {@link #requireCompatibleKeys} for a cross-table map an evaluator keys itself (the
     * {@code date_diff_days} foreign subtrahend and minuend maps): the columns pair by position,
     * and the names may differ (sided keys).
     *
     * @param aGrouped
     *            the table the map is built on
     * @param aGroupedCols
     *            its key columns
     * @param aEvaluated
     *            the table the map is probed from
     * @param aEvaluatedCols
     *            the probe's key columns, index-aligned with {@code aGroupedCols}
     * @throws JoinKeyTypeMismatchException
     *             when a pair's kinds differ
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
            if (grouped != null && evaluated != null && grouped != evaluated)
            {
                throw JoinKeyTypeMismatchException.forGroupedLookup(aGroupedCols.get(i),
                        String.valueOf(groupedMeta.getName()), grouped, aEvaluatedCols.get(i),
                        String.valueOf(evaluatedMeta.getName()), evaluated);
            }
        }
    }


    private static ColumnTypeGate.@Nullable Kind kindOf(DataTableMeta aMeta, String aColumn)
    {
        int idx = aMeta.getColumnIndex(aColumn);
        return idx < 0 ? null : ColumnTypeGate.kindOf(aMeta.getColumn(idx).getType());
    }

}
