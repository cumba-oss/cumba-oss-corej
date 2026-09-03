package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.cumba.corej.core.exec.GroupKeyPolicy.KeyPart;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.index.DataTableIndexFactory;
import net.cumba.datatable.index.IDataTableIndex;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.view.IDataTableView;
import org.jspecify.annotations.Nullable;

/**
 * Shared, parity-critical <i>group</i> semantics for the multi-row operators whose verdict depends
 * on cross-row state within a {@code within}-keyed partition (e.g.
 * {@code has_multiple_values_for}).
 *
 * <p>
 * Like {@link ScalarSemantics} / {@link ArithmeticSemantics} for the per-row operators, this class
 * is the single home for the partitioning and per-group algorithms that <b>both</b> the legacy
 * operator engine ({@link OperatorRegistry}) and the native expression evaluator (package
 * {@code net.cumba.corej.core.expr.eval}) compute over. Parity between the two backends is
 * therefore by construction — the native group-execution path is not a runtime dependency on the
 * legacy operator, it is the same shared code (Phase 4b / decision Q2).
 * </p>
 *
 * <p>
 * The partition is built from a {@link IDataTableIndex} over the {@code within} columns — the same
 * datatable-level index primitive the legacy operators used inline — and what a blank key component
 * means is decided by a {@link GroupKeyPolicy} rather than by each call site independently. The
 * single entry point is {@link #group(IDataTable, List, GroupKeyPolicy)}; {@link #partition} is
 * that primitive under {@link GroupKeyPolicy#DROP_MISSING_KEYS}, the shipped default for the
 * {@code within:} operator family.
 * </p>
 */
public final class GroupSemantics
{

    private static final System.Logger LOGGER = System.getLogger(GroupSemantics.class.getName());

    private GroupSemantics()
    {
    }

    // -------------------------------------------------------------------------
    // Partitioning
    // -------------------------------------------------------------------------


    /**
     * Partitions the table rows by the {@code withinCols} key tuple. Returns the groups as arrays
     * of absolute row indices, in index-block order. A group whose key has a missing/invalid
     * component (per {@code IDataValue.isMissingOrInvalid}, the legacy {@code isBlockKeyMissing}
     * test) is excluded.
     *
     * <p>
     * <b>EC-44 (Fix #134).</b> A {@code within} column <em>absent</em> from the table is
     * <b>ignored</b>, and the surviving columns do the partitioning; when none survives — including
     * when {@code withinCols} is itself empty — the whole table is one group. An absent column
     * cannot differentiate any row from any other, so every row is homogeneous with respect to that
     * key and it partitions nothing. Before this, any absent column returned {@code null} and the
     * caller yielded no violations, so a check silently stopped running on every study that did not
     * collect an optional (Perm/Cond) partitioning variable. Fix #133 established this contract for
     * the grouped operation evaluators (see {@link IndexHelper#groupByPresent}); Fix #134 completes
     * it for the Check-level {@code within:} operators.
     * </p>
     *
     * <p>
     * <b>Absence is not missingness.</b> The blank-key exclusion above still applies to the
     * <em>surviving</em> columns: a missing <em>value</em> in a column that <em>exists</em> is a
     * key that is unknown relative to known peers, which is a different thing from a column the
     * study never collected. Whether that unknown key drops its group is now the
     * {@link GroupKeyPolicy#keepMissings()} decision rather than a fact hard-coded here.
     * </p>
     *
     * <p>
     * ⚠ The warrant previously cited here — "the EC-26 / Fix #122 parity contract" — is <b>void</b>
     * and has been removed. The ledger records {@code Fix #122} as <em>Python fork only; Java
     * unchanged</em>: it moved the <b>fork</b> to match coreJ, so coreJ's discard was never derived
     * from parity and the citation was retroactive. See {@link GroupKeyPolicy#DROP_MISSING_KEYS}.
     * </p>
     *
     * @param table
     *            the dataset
     * @param withinCols
     *            the partitioning column names; entries absent from the table are ignored
     * @return the row-index groups; never {@code null}, and empty only for an empty table
     */
    public static List<int[]> partition(IDataTable table, List<String> withinCols)
    {
        return group(table, withinCols, GroupKeyPolicy.DROP_MISSING_KEYS);
    }


    /**
     * <b>The single grouping primitive.</b> Every index-based grouping path in the engine goes
     * through this method, and the EC-44 absent-column contract (an absent column is ignored; if
     * none survives, one whole-table group) is applied <b>once</b>, here.
     *
     * <p>
     * {@link #partition(IDataTable, List)} is this primitive under
     * {@link GroupKeyPolicy#DROP_MISSING_KEYS} and {@code IndexHelper.groupByPresent} is its
     * reporting-key twin under {@link GroupKeyPolicy#KEEP_MISSING_KEYS}. Before the unification
     * each grouping path decided the blank-key question for itself, which is how one authoring
     * surface ({@code Operations[].group:}) ended up folding for five operators and discarding for
     * a sixth with nothing in the YAML to tell the author which they would get.
     * </p>
     *
     * @param table
     *            the dataset
     * @param keyCols
     *            the grouping column names; entries absent from the table are ignored
     * @param policy
     *            what a blank key component means
     * @return the row-index groups; never {@code null}, and empty only for an empty table
     */
    public static List<int[]> group(IDataTable table, List<String> keyCols, GroupKeyPolicy policy)
    {
        DataTableMeta meta = table.getMetaData();
        List<String> present = new ArrayList<>(keyCols.size());
        for (String col : keyCols)
        {
            if (col != null && meta.getColumnIndex(col) >= 0)
            {
                present.add(col);
            }
        }
        logWidened(keyCols, present);
        long rowCount = table.getRowCount();
        if (present.isEmpty())
        {
            // No surviving partition column ⇒ one group over the whole table (EC-44). Mirrors
            // RuleRunner.executeGrouped's whole-dataset fallback and pandas' groupby of an empty
            // key list.
            return rowCount == 0 ? List.of() : List.of(allRows(rowCount));
        }
        int[] keyIdx = new int[present.size()];
        for (int i = 0; i < present.size(); i++)
        {
            keyIdx[i] = meta.getColumnIndex(present.get(i));
        }
        IDataTableIndex index = DataTableIndexFactory.createInstance().createIndex(table,
                present.toArray(String[]::new));
        List<int[]> groups = new ArrayList<>();
        long blockCount = index.getBlockCount();
        for (long b = 0; b < blockCount; b++)
        {
            IDataTableView block = index.getBlock(b);
            // The disposition lives here, not in the predicate: a keep-missings policy simply never
            // asks the existence question. isBlockKeyMissing stays a pure blankness test and stays
            // a
            // separate function from IndexHelper.buildGroupKey.
            if (!policy.keepMissings()
                    && IndexHelper.isBlockKeyMissing(block, table, keyIdx, policy))
            {
                continue;
            }
            groups.add(IndexHelper.blockRows(block, table));
        }
        return groups;
    }


    /**
     * EC-44 visibility: a widened partition is a judgement call, so say so. Fix #133 made the
     * equivalent log non-optional on the operation evaluators ({@code IndexHelper.groupByPresent});
     * this is its Check-level twin. There is no rule or operation id at this level, so the message
     * names the columns only.
     */
    private static void logWidened(List<String> declared, List<String> present)
    {
        if (declared.size() == present.size() || !LOGGER.isLoggable(System.Logger.Level.INFO))
        {
            return;
        }
        List<String> dropped = new ArrayList<>(declared);
        dropped.removeAll(present);
        LOGGER.log(System.Logger.Level.INFO,
                "within column(s) {0} absent from the dataset — ignored for partitioning; "
                        + "partitioning by {1}",
                dropped, present.isEmpty() ? "the whole dataset (one group)" : present);
    }


    /**
     * Every row index of the table, as one group. Used by the EC-44 degenerate partitions.
     */
    private static int[] allRows(long rowCount)
    {
        int n = Math.toIntExact(rowCount);
        int[] rows = new int[n];
        for (int i = 0; i < n; i++)
        {
            rows[i] = i;
        }
        return rows;
    }


    /**
     * Partitions the table rows by a list of key <b>components</b> (EC-24, coalesced grouping key).
     * Each component is one column tuple: a singleton {@code [COL]} keys on that column directly (a
     * blank component — a genuine missing or {@code ""}, one bucket since {@code W32-E3} / Fix #241
     * — drops the row; a whitespace-only value is a real key), while a multi-column
     * {@code [C0, C1, …]} coalesce-group keys on the <b>first populated</b> column (first {@code C}
     * that is non-missing <b>and</b> not {@code strip().isEmpty()}); if every column of a
     * coalesce-group is unpopulated the component is missing and the row drops. The row's group key
     * is the {@link KeyPart} tuple of its component identities, so distinct component tuples cannot
     * collide by construction (operator-examples.md D.1; {@code W38-A1}).
     *
     * <p>
     * When <b>no</b> component is a coalesce-group (every component is a singleton) this delegates
     * to the index-based {@link #partition(IDataTable, List)} over the flattened column list, so
     * the result — group membership and order — is <b>bit-for-bit identical</b> to the pre-EC-24
     * behaviour. Only the presence of a genuine coalesce-group switches to the computed-key path.
     * </p>
     *
     * <p>
     * <b>Deliberate deviation.</b> Inside a coalesce-group, a <b>whitespace-only</b> value counts
     * as <i>unpopulated</i> alongside {@code ""} and a genuine missing
     * ({@link GroupKeyPolicy.Blankness#MISSING_OR_WHITESPACE} — an approved deviation, Q-5,
     * FDA-SE2279): pooled rows carry a blank {@code USUBJID} and identify by {@code POOLID}, so
     * {@code within: [[USUBJID, POOLID]]} must fall through the blank — even a space-filled —
     * {@code USUBJID} to {@code POOLID}. Singleton components share the group-wide
     * {@code MISSING_OR_EMPTY} notion instead ({@code W32-E3} / Fix #241), so for them a
     * whitespace-only value stays a real key.
     * </p>
     *
     * @param table
     *            the dataset
     * @param components
     *            the key components (normalised {@code List<List<String>>}; must be non-empty)
     * @return the row-index groups (index-block order for the delegated path, first-seen key order
     *         for the computed-key path); never {@code null}. An absent column inside a surviving
     *         grouping is tolerated by dropping it and regrouping on the remaining columns, and
     *         when no component survives at all the whole table is one group (EC-44 / Fix #134)
     */
    public static List<int[]> partitionCoalesced(IDataTable table, List<List<String>> components)
    {
        return partitionCoalesced(table, components, GroupKeyPolicy.DROP_MISSING_KEYS);
    }


    /**
     * {@link #partitionCoalesced(IDataTable, List)} under an explicit {@link GroupKeyPolicy}.
     *
     * <p>
     * ⚠⚠ <b>Two blankness notions are load-bearing inside this one method</b> and the policy
     * carries only one of them. The {@code policy} governs <b>singleton</b> components — under
     * {@link GroupKeyPolicy#DROP_MISSING_KEYS} a blank component (a genuine missing or {@code ""} —
     * {@code MISSING_OR_EMPTY} since {@code W32-E3} / Fix #241) drops the row, and under a keeping
     * policy a kept blank keys its own {@link KeyPart} identity ({@code W38-A1} / Fix #249). A
     * <b>coalesce</b> component always uses {@link GroupKeyPolicy#COALESCE_COMPONENT}'s
     * whitespace-aware notion for deciding which column is populated, because that is what "fall
     * through to the next column" means (EC-24 / Q-5, {@code FDA-SE2279}); only its
     * {@link GroupKeyPolicy#keepMissings()} disposition — what happens when <em>no</em> column of
     * the component qualifies — follows {@code policy}. Collapsing the two notions would silently
     * change {@code FDA-SE2279}.
     * </p>
     *
     * @param table
     *            the dataset
     * @param components
     *            the key components (normalised; must be non-empty)
     * @param policy
     *            what a blank singleton component means, and the disposition for an all-unpopulated
     *            coalesce component
     * @return the row-index groups
     */
    public static List<int[]> partitionCoalesced(IDataTable table, List<List<String>> components,
            GroupKeyPolicy policy)
    {
        boolean anyCoalesce = false;
        List<String> flat = new ArrayList<>();
        for (List<String> comp : components)
        {
            if (comp.size() >= 2)
            {
                anyCoalesce = true;
            }
            flat.addAll(comp);
        }
        if (!anyCoalesce)
        {
            // Every component is a singleton -> identical to the pre-EC-24 index-based partition.
            return group(table, flat, policy);
        }

        DataTableMeta meta = table.getMetaData();
        // EC-24 addendum (2026-07-28, SE2279): a coalesce component tolerates ABSENT columns —
        // it groups by its present columns only (POOLID never collected => the plain per-USUBJID
        // check), keeping the populated-first coalesce semantics for the survivors (a blank
        // USUBJID still drops the row rather than forming a cross-pool "" group). A component
        // whose columns are all absent drops from the key entirely (the drop-and-regroup
        // convention); when no component survives, the whole table is one group — EC-44 / Fix #134
        // finished what this addendum started, so total absence no longer yields no flags.
        List<int[]> compIdsList = new ArrayList<>();
        List<Boolean> compIsCoalesce = new ArrayList<>();
        for (List<String> comp : components)
        {
            int[] ids = new int[comp.size()];
            int n = 0;
            for (String c : comp)
            {
                int idx = meta.getColumnIndex(c);
                if (idx >= 0)
                {
                    ids[n++] = idx;
                }
            }
            if (n == 0)
            {
                continue;
            }
            compIdsList.add(Arrays.copyOf(ids, n));
            compIsCoalesce.add(comp.size() >= 2);
        }
        if (compIdsList.isEmpty())
        {
            // EC-44 (Fix #134): no component survives ⇒ nothing can differentiate one row from
            // another ⇒ one group over the whole table, the same answer partition() gives when no
            // singleton column survives. Fix #133 left this branch returning null ("the previous
            // total-absence contract"); that inherited behaviour was the last place the engine
            // disagreed with its own documented contract.
            long rowCount = table.getRowCount();
            return rowCount == 0 ? List.of() : List.of(allRows(rowCount));
        }

        long rowCount = table.getRowCount();
        Map<List<KeyPart>, List<Integer>> keyed = new LinkedHashMap<>();
        for (int r = 0; r < rowCount; r++)
        {
            List<KeyPart> key = new ArrayList<>(compIdsList.size());
            boolean missing = false;
            for (int ci = 0; ci < compIdsList.size(); ci++)
            {
                KeyPart v = componentKeyValue(table, compIdsList.get(ci), r, compIsCoalesce.get(ci),
                        policy);
                if (v == null)
                {
                    missing = true;
                    break;
                }
                key.add(v);
            }
            if (missing)
            {
                // A missing component (genuine-missing singleton, or all-unpopulated coalesce
                // group) drops the row from grouping — the isBlockKeyMissing contract. Reached only
                // when the policy discards; a keep-missings policy resolves every component to a
                // KeyPart, so `missing` never becomes true (see componentKeyValue).
                continue;
            }
            keyed.computeIfAbsent(key, _ -> new ArrayList<>()).add(r);
        }
        List<int[]> out = new ArrayList<>(keyed.size());
        for (List<Integer> g : keyed.values())
        {
            int[] arr = new int[g.size()];
            for (int i = 0; i < arr.length; i++)
            {
                arr[i] = g.get(i);
            }
            out.add(arr);
        }
        return out;
    }


    /**
     * The EC-24 key identity of one component at absolute row {@code r}, or {@code null} when the
     * component is missing (the row then drops from grouping). A singleton component reads its
     * single column: a blank drops the row under a discarding policy and keeps its own
     * {@link KeyPart} identity under a keeping one ({@code W38-A1} / Fix #249 — a kept
     * {@code Missing(MIS)} used to render its display string {@code "."} here and key-collide with
     * a real {@code "."}, ruling part 4's named literal). A coalesce-group returns the first column
     * whose value is populated (non-missing and not {@code strip().isEmpty()}) as a
     * {@link KeyPart.Present}; if none qualifies the component is missing ({@code null}) under a
     * discarding policy and the single all-unpopulated bucket {@link KeyPart#EMPTY} under a keeping
     * one — the component is a <em>derived</em> "first populated" value, not a cell, so "nothing
     * populated" is one state, exactly the {@code ""} fold it replaces.
     */
    private static @Nullable KeyPart componentKeyValue(IDataTable table, int[] colIds, long r,
            boolean declaredCoalesce, GroupKeyPolicy policy)
    {
        if (!declaredCoalesce)
        {
            // Singleton component: the caller's policy decides both what counts as blank and what
            // happens to it. Under DROP_MISSING_KEYS (the shipped default) a blank key component
            // drops the row, matching the delegated index-based `group` path.
            //
            // ⭐ W32-E3 (owner, 2026-08-12): "blank" here now covers a literal "" as well as a
            // genuine missing marker. The comment this replaced said the opposite — "⚠
            // MISSING_ONLY,
            // not MISSING_OR_EMPTY: the blankness notion must NOT fold \"\" here" — and it was
            // correct until the ruling. It is kept in the record rather than deleted because the
            // constraint it protected still holds in a weaker form: this branch and the delegated
            // `group` path must agree about which cells are blank, and they now agree on
            // MISSING_OR_EMPTY. ⛔ They still must NOT adopt COALESCE_COMPONENT's whitespace notion,
            // which is the coalesce branch's alone (EC-24 / FDA-SE2279).
            KeyPart part = policy.keyPart(table.getColumn(colIds[0]).getDataValue(r));
            if (part instanceof KeyPart.Present)
            {
                return part;
            }
            return policy.keepMissings() ? part : null;
        }
        // ⚠⚠ A coalesce component uses COALESCE_COMPONENT's whitespace-aware notion regardless of
        // the caller's policy — "populated" is the question being asked, and a space-filled
        // USUBJID must fall through to POOLID (EC-24 / Q-5, FDA-SE2279). Only the disposition for
        // an all-unpopulated component follows the caller.
        for (int cid : colIds)
        {
            IDataValue dv = table.getColumn(cid).getDataValue(r);
            if (GroupKeyPolicy.COALESCE_COMPONENT.keyPart(dv) instanceof KeyPart.Present p)
            {
                return p;
            }
        }
        // Every column of the component is unpopulated. Discarding drops the row (today's
        // behaviour, reachable by FDA-SE2279 alone); keeping resolves the component to the one
        // all-unpopulated bucket so the row groups with the other rows that have neither a
        // subject nor a pool.
        return policy.keepMissings() ? KeyPart.EMPTY : null;
    }

    // -------------------------------------------------------------------------
    // has_multiple_values_for (functional dependency)
    // -------------------------------------------------------------------------


    /**
     * The {@code has_multiple_values_for} functional-dependency check over a sequence of rows
     * addressed by {@code rowAt} (position {@code i} ⇒ absolute row {@code rowAt.applyAsInt(i)}).
     * Returns a {@link java.util.BitSet} over <i>positions</i> {@code [0, sequenceSize)} — the
     * caller maps a set position {@code i} back to its absolute row {@code rowAt.applyAsInt(i)}.
     *
     * <p>
     * A position fires when its key value ({@code valueCol}) maps to more than one distinct
     * dependent value ({@code nameCol}) across the sequence. A position whose key <b>or</b>
     * dependent is blank ({@code ""} or a genuine missing — not {@link KeyPart.Present}) is
     * excluded from the dependency entirely — it neither seeds a key nor ever fires
     * (operator-examples.md D.13). Both engines apply this exclusion identically.
     * </p>
     *
     * @param nameCol
     *            the dependent column (the {@code name} operand)
     * @param valueCol
     *            the key column (the {@code value} operand)
     * @param rowAt
     *            position-to-row mapping
     * @param sequenceSize
     *            the number of positions
     * @return the violating positions
     */
    public static BitSet hasMultipleValuesForRows(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IntUnaryOperator rowAt, int sequenceSize)
    {
        return hasMultipleValuesForRows(nameCol, valueCol, rowAt, sequenceSize, false);
    }


    /**
     * The {@code has_multiple_values_for} check with an explicit emptiness switch (Fix #121,
     * Java-only). With {@code includeEmpty == false} this is exactly
     * {@link #hasMultipleValuesForRows(IDataTableColumn, IDataTableColumn, IntUnaryOperator, int)}
     * — the D.13 exclusion applies. With {@code includeEmpty == true} the exclusion is disabled: a
     * blank is a real key and a real dependent value, so a key mapping to a populated dependent on
     * one row and a blank dependent on another has two distinct dependents and fires — including on
     * the blank rows. Since {@code W38-A1} (Fix #249) a participating blank keeps its own
     * {@link KeyPart} identity — {@code ""} and each missing marker are distinct keys and distinct
     * dependent values, exactly as they are distinct groups everywhere blanks are kept.
     *
     * @param nameCol
     *            the dependent column (the {@code name} operand)
     * @param valueCol
     *            the key column (the {@code value} operand)
     * @param rowAt
     *            position-to-row mapping
     * @param sequenceSize
     *            the number of positions
     * @param includeEmpty
     *            {@code true} to let {@code ""} keys and dependents participate
     * @return the violating positions
     */
    public static BitSet hasMultipleValuesForRows(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IntUnaryOperator rowAt, int sequenceSize,
            boolean includeEmpty)
    {
        Map<KeyPart, Set<KeyPart>> keyToDependents = collectKeyToDependents(nameCol, valueCol,
                rowAt, sequenceSize, includeEmpty);
        Set<KeyPart> badKeys = keysWithMultipleDependents(keyToDependents);

        BitSet result = new BitSet(sequenceSize);
        if (badKeys.isEmpty())
        {
            return result;
        }
        for (int i = 0; i < sequenceSize; i++)
        {
            long r = rowAt.applyAsInt(i);
            KeyPart key = keyPart(valueCol, r);
            if (!includeEmpty && (!(key instanceof KeyPart.Present)
                    || !(keyPart(nameCol, r) instanceof KeyPart.Present)))
            {
                // D.13: a row with a blank key or dependent is excluded — it never fires. A TYPE
                // test, not a rendering test (W38-A1): Empty and Missing are excluded, whatever
                // they would render as.
                continue;
            }
            if (badKeys.contains(key))
            {
                result.set(i);
            }
        }
        return result;
    }


    private static Map<KeyPart, Set<KeyPart>> collectKeyToDependents(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IntUnaryOperator rowAt, int sequenceSize,
            boolean includeEmpty)
    {
        Map<KeyPart, Set<KeyPart>> keyToDependents = new HashMap<>();
        for (int i = 0; i < sequenceSize; i++)
        {
            long r = rowAt.applyAsInt(i);
            KeyPart key = keyPart(valueCol, r);
            KeyPart dep = keyPart(nameCol, r);
            if (!includeEmpty
                    && (!(key instanceof KeyPart.Present) || !(dep instanceof KeyPart.Present)))
            {
                // D.13: a blank key or dependent never enters the functional dependency (type
                // test — see hasMultipleValuesForRows).
                continue;
            }
            keyToDependents.computeIfAbsent(key, _ -> new HashSet<>()).add(dep);
        }
        return keyToDependents;
    }


    /**
     * Reads {@code col} at the absolute row {@code r} as its {@link KeyPart} identity under
     * {@link GroupKeyPolicy#FOLD_BLANK_KEYS} — the treatment of the grouping operators that
     * <em>keep</em> a blank as a real value. Until {@code W38-A1} (Fix #249) this folded every
     * blank to the one string {@code ""}; a blank now keeps its own identity ({@code Empty} vs
     * {@code Missing(marker)}), so kept blanks form separate groups and no missing marker can equal
     * a real value (see {@link KeyPart}).
     */
    private static KeyPart keyPart(IDataTableColumn col, long r)
    {
        return keyComponent(col, r, GroupKeyPolicy.FOLD_BLANK_KEYS);
    }


    /**
     * One <b>grouping-key</b> component: the {@link KeyPart} identity of {@code col} at absolute
     * row {@code r} under {@code policy} ({@link GroupKeyPolicy#keyPart}). Under
     * {@link GroupKeyPolicy#FOLD_BLANK_KEYS} this is exactly
     * {@link #keyPart(IDataTableColumn, long)} — which is the point:
     * {@code targetIsNotSortedByViolations} carried an inline <em>copy</em> of the old fold rather
     * than a call to it, so the engine had two implementations of one idea. Both now call here.
     *
     * <p>
     * ⚠ This is the <b>group-membership</b> axis only. It is deliberately <em>not</em> used for the
     * D.13 / D.2 <em>value-participation</em> decisions in {@link #hasMultipleValuesForRows} and
     * {@code flagInconsistentGroupMinority}, which decide whether a blank <em>target</em> counts as
     * a value — a different question, governed by {@code include_empty} (Fix #121), and one that
     * {@code keep_missings} must not silently reach. (Those sites test the {@code KeyPart}'s
     * <em>type</em>, never a rendering — the arm1 bug class this type retired.)
     * </p>
     */
    private static KeyPart keyComponent(IDataTableColumn col, long r, GroupKeyPolicy policy)
    {
        return policy.keyPart(col.getDataValue(r));
    }


    /**
     * {@code true} when {@code policy} discards blank keys and any of {@code keyColIds} is blank at
     * row {@code r} — the per-row twin of {@code IndexHelper.isBlockKeyMissing} for the key
     * builders that compute keys row by row instead of over index blocks. Always {@code false}
     * under a keep-missings policy, so the shipped fold sites are unaffected.
     */
    private static boolean rowKeyDiscarded(IDataTable table, int[] keyColIds, long r,
            GroupKeyPolicy policy)
    {
        if (policy.keepMissings())
        {
            return false;
        }
        for (int cid : keyColIds)
        {
            if (policy.isBlankKeyComponent(table.getColumn(cid).getDataValue(r)))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Builds the composite tuple key for row {@code r} over {@code keyColIds} — one {@link KeyPart}
     * per key column, so distinct tuples cannot collide by construction ({@code W38-A1}; record
     * equality is component-wise, no string joining is involved). For each key column with a
     * non-{@code null} {@code colPattern} entry, a {@link KeyPart.Present} value is first replaced
     * by its first regex match; a non-matching (or empty-matching) cell becomes
     * {@link KeyPart#EMPTY} — the bucket a literal-{@code ""} cell of that column occupies.
     * (Pre-W38-A1 every blank shared that bucket; a marker-missing cell now keeps its own identity,
     * so a non-matching value merges with {@code ""} cells but no longer with genuine missings —
     * the ruled identity, applied to the normalized value.) Blank cells are never regex-normalized:
     * whether to normalize is a question about a <em>value</em>, so it is answered by the
     * component's type, not by its rendering. Mirrors Python {@code is_unique_set}'s per-column
     * {@code apply_regex} normalization.
     *
     * @param table
     *            the dataset
     * @param keyColIds
     *            the key column indices
     * @param colPattern
     *            per-key-column pattern (parallel to {@code keyColIds}); {@code null} entry ⇒ no
     *            normalization for that column
     * @param r
     *            the absolute row
     * @return the composite key — the per-column {@link KeyPart} tuple (list equality is the key
     *         identity)
     */
    private static List<KeyPart> foldedKey(IDataTable table, int[] keyColIds,
            @Nullable Pattern[] colPattern, long r, GroupKeyPolicy policy)
    {
        List<KeyPart> parts = new ArrayList<>(keyColIds.length);
        for (int i = 0; i < keyColIds.length; i++)
        {
            KeyPart v = keyComponent(table.getColumn(keyColIds[i]), r, policy);
            Pattern p = colPattern[i];
            if (p != null && v instanceof KeyPart.Present(String s))
            {
                Matcher m = p.matcher(s);
                String g = m.find() ? m.group() : "";
                v = g.isEmpty() ? KeyPart.EMPTY : new KeyPart.Present(g);
            }
            parts.add(v);
        }
        return parts;
    }


    /**
     * For each key column, the {@link Pattern} to normalize it with, or {@code null} to leave it
     * verbatim. Mirrors Python {@code is_unique_set}: a column is normalized only when its first
     * non-missing value matches {@code regex} (so e.g. a non-date column under a date pattern is
     * left alone). A {@code null} / empty / invalid pattern disables normalization for every column
     * (the lenient legacy contract — a bad pattern never throws here).
     *
     * @param table
     *            the dataset
     * @param keyColIds
     *            the key column indices
     * @param rowCount
     *            the row count
     * @param regex
     *            the {@code regex=} kwarg, or {@code null}
     * @return a per-key-column pattern array (parallel to {@code keyColIds}), all {@code null} when
     *         normalization is disabled
     */
    private static @Nullable Pattern[] regexColumnPatterns(IDataTable table, int[] keyColIds,
            int rowCount, @Nullable String regex)
    {
        Pattern[] out = new Pattern[keyColIds.length];
        if (regex == null || regex.isEmpty())
        {
            return out;
        }
        Pattern p;
        try
        {
            p = Pattern.compile(regex);
        }
        catch (PatternSyntaxException _)
        {
            return out;
        }
        for (int i = 0; i < keyColIds.length; i++)
        {
            IDataTableColumn col = table.getColumn(keyColIds[i]);
            for (int r = 0; r < rowCount; r++)
            {
                if (keyPart(col, r) instanceof KeyPart.Present(String s))
                {
                    // Gate on the first non-blank value only (Python's sample_value), matching
                    // the whole-column normalize-or-leave decision. A type test: a blank never
                    // supplies the sample, whatever it would render as (W38-A1).
                    if (p.matcher(s).find())
                    {
                        out[i] = p;
                    }
                    break;
                }
            }
        }
        return out;
    }


    /**
     * The subset of keys whose dependent-value set has more than one distinct entry — the "key maps
     * to multiple values" predicate shared by {@code has_multiple_values_for} and the bidirectional
     * {@code is_not_unique_relationship}.
     *
     * @param <K>
     *            the key identity type ({@link KeyPart}, a {@code KeyPart} tuple, …)
     * @param <V>
     *            the dependent identity type
     * @param keyToDependents
     *            key ⇒ distinct-dependent-values map
     * @return the keys with two or more distinct dependents
     */
    public static <K, V> Set<K> keysWithMultipleDependents(Map<K, Set<V>> keyToDependents)
    {
        Set<K> badKeys = new HashSet<>();
        for (Map.Entry<K, Set<V>> e : keyToDependents.entrySet())
        {
            if (e.getValue().size() > 1)
            {
                badKeys.add(e.getKey());
            }
        }
        return badKeys;
    }

    // -------------------------------------------------------------------------
    // present_on_multiple_rows_within / not_present_on_multiple_rows_within
    // -------------------------------------------------------------------------


    /**
     * Flags every row of each group whose size matches the {@code flagMultiple} predicate:
     * {@code flagMultiple == true} fires groups of size &ge; 2 ({@code present_on_multiple_rows_
     * within}), {@code false} fires singletons ({@code not_present_on_multiple_rows_within}). The
     * groups are partitioned on the composite {@code (within, name)} key, so a "group" is the set
     * of rows sharing both the {@code within} value and the {@code name} value.
     *
     * @param groups
     *            the composite-key row groups (from {@link #partition})
     * @param flagMultiple
     *            fire size&ge;2 groups when {@code true}, singletons when {@code false}
     * @param result
     *            the absolute-row violation set to populate
     */
    public static void flagGroupsBySize(List<int[]> groups, boolean flagMultiple, BitSet result)
    {
        for (int[] g : groups)
        {
            if (flagMultiple == (g.length >= 2))
            {
                for (int r : g)
                {
                    result.set(r);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // empty_within_except_last_row
    // -------------------------------------------------------------------------


    /**
     * For one group, orders the rows by {@code orderCol} and flags every row except the last whose
     * {@code nameCol} value is missing/empty ({@code empty_within_except_last_row}).
     *
     * @param nameCol
     *            the checked column
     * @param orderCol
     *            the ordering column
     * @param rows
     *            the group's absolute row indices (sorted in place)
     * @param result
     *            the absolute-row violation set to populate
     */
    public static void flagEmptyExceptLastRow(IDataTableColumn nameCol, IDataTableColumn orderCol,
            int[] rows, BitSet result)
    {
        sortByOrderColumn(rows, orderCol);
        for (int i = 0; i < rows.length - 1; i++)
        {
            int r = rows[i];
            IDataValue dv = nameCol.getDataValue(r);
            if (ScalarSemantics.isMissing(dv) || dv.getValueAsString().isEmpty())
            {
                result.set(r);
            }
        }
    }

    // -------------------------------------------------------------------------
    // does_not_have_next_corresponding_record
    // -------------------------------------------------------------------------

    /**
     * EC-87 — the relation two neighbouring cells must stand in for a "corresponding record" to
     * exist. {@link #identityCorresponds} is the shipped default and the {@code W38-A1} contract; a
     * comparison relation supplied by {@code ExprCompiler} (the {@code relation=} kwarg) is applied
     * <b>in disjunction with</b> it, never instead of it (design decision D-1 of
     * {@code PLAN-next-record-value-comparison.md}), so a relation can only ever <em>widen</em>
     * what corresponds.
     *
     * <p>
     * ⚑ The relation is injected rather than computed here on purpose: the comparison primitive
     * lives in {@code expr.eval} ({@code Primitives.compareCells}), and the dependency direction is
     * {@code expr.eval → exec}. Calling it from this package would make the two mutually dependent.
     * Do not "simplify" this into a {@code String} relation parameter — that is the same inversion.
     * </p>
     */
    @FunctionalInterface
    public interface NeighbourRelation
    {

        /**
         * Decides whether the next ordered row corresponds to the current one.
         *
         * @param current
         *            the current row's {@code nameCol} cell
         * @param next
         *            the next ordered row's {@code valueCol} cell
         * @return {@code true} iff a corresponding record exists (i.e. the row does NOT fire)
         */
        boolean corresponds(IDataValue current, IDataValue next);
    }

    /**
     * The shipped, relation-free correspondence: {@link KeyPart} identity under
     * {@link GroupKeyPolicy#FOLD_BLANK_KEYS}. {@code "" == ""} means a corresponding record exists
     * and does not fire (operator-examples.md D.5), and since {@code W38-A1} (Fix #249) a genuine
     * missing corresponds only to the <em>same</em> missing marker — never to {@code ""} and never
     * to any real value.
     */
    public static boolean identityCorresponds(IDataValue aCurrent, IDataValue aNext)
    {
        return GroupKeyPolicy.FOLD_BLANK_KEYS.keyPart(aCurrent)
                .equals(GroupKeyPolicy.FOLD_BLANK_KEYS.keyPart(aNext));
    }


    /**
     * For one group, orders the rows by {@code orderCol} and flags every row (except the last)
     * whose {@code nameCol} value does not equal the {@code valueCol} value on the next ordered row
     * ({@code does_not_have_next_corresponding_record}). The two cells are compared as their
     * {@link KeyPart} identities ({@link #identityCorresponds}) — this overload is the shipped
     * behaviour of every rule that authors no {@code relation=}.
     *
     * @param nameCol
     *            the current-row column
     * @param valueCol
     *            the next-row comparison column
     * @param orderCol
     *            the ordering column
     * @param rows
     *            the group's absolute row indices (sorted in place)
     * @param result
     *            the absolute-row violation set to populate
     */
    public static void flagNoNextCorrespondingRecord(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IDataTableColumn orderCol, int[] rows, BitSet result)
    {
        flagNoNextCorrespondingRecord(nameCol, valueCol, orderCol, rows, result,
                GroupSemantics::identityCorresponds);
    }


    /**
     * {@link #flagNoNextCorrespondingRecord(IDataTableColumn, IDataTableColumn, IDataTableColumn, int[], BitSet)}
     * with the correspondence supplied by the caller (EC-87, {@code relation=}).
     *
     * @param relation
     *            see {@link NeighbourRelation}; the five-argument overload passes
     *            {@link #identityCorresponds}
     */
    public static void flagNoNextCorrespondingRecord(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IDataTableColumn orderCol, int[] rows, BitSet result,
            NeighbourRelation relation)
    {
        sortByOrderColumn(rows, orderCol);
        for (int i = 0; i < rows.length - 1; i++)
        {
            int currentRow = rows[i];
            int nextRow = rows[i + 1];
            if (!relation.corresponds(nameCol.getDataValue(currentRow),
                    valueCol.getDataValue(nextRow)))
            {
                result.set(currentRow);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Ordering
    // -------------------------------------------------------------------------


    /**
     * Stable-sorts a group's row indices in place by {@code orderCol}, comparing the cells' string
     * forms ({@code compareTo}, a missing cell treated as the empty string) — the legacy
     * {@code sortByColumn} contract. The sort is stable, so rows with equal ordering keys keep
     * their original relative order.
     *
     * @param rows
     *            the absolute row indices to sort in place
     * @param orderCol
     *            the ordering column
     */
    public static void sortByOrderColumn(int[] rows, IDataTableColumn orderCol)
    {
        Integer[] boxed = new Integer[rows.length];
        for (int i = 0; i < rows.length; i++)
        {
            boxed[i] = rows[i];
        }
        Arrays.sort(boxed, (a, b) ->
        {
            IDataValue dvA = orderCol.getDataValue(a);
            IDataValue dvB = orderCol.getDataValue(b);
            String sa = ScalarSemantics.isMissing(dvA) ? "" : dvA.getValueAsString();
            String sb = ScalarSemantics.isMissing(dvB) ? "" : dvB.getValueAsString();
            return sa.compareTo(sb);
        });
        for (int i = 0; i < rows.length; i++)
        {
            rows[i] = boxed[i];
        }
    }

    // -------------------------------------------------------------------------
    // target_is_not_sorted_by
    // -------------------------------------------------------------------------


    /**
     * The {@code target_is_not_sorted_by} verdict: groups rows by {@code withinColName}'s
     * {@link KeyPart} identity (one whole-table bucket when the column is {@code null} or absent;
     * blank within values are kept, each blank kind its own bucket — {@code W38-A1}), and within
     * each group of &ge; 2 rows orders by the {@code sortVars} key columns (multi-key string
     * {@code compareTo}, missing treated as {@code ""}); if the {@code targetCol} is then not
     * monotonically non-decreasing — compared numeric-first, string-fallback (so
     * {@code "2" < "10"}) — every row of that group is flagged. Empty {@code sortVars}, a missing
     * target column, or {@code rowCount <= 1} yields no violations.
     *
     * <p>
     * This deliberately uses its own value-keyed grouping (not {@link #partition}) because the
     * legacy operator pools missing/empty within values into the {@code ""} bucket rather than
     * dropping them. Both the legacy operator and the native evaluator call this one method, so
     * they agree by construction.
     * </p>
     *
     * @param table
     *            the dataset
     * @param rowCount
     *            the row count
     * @param targetCol
     *            the column whose ascending order is checked
     * @param sortVars
     *            the ordering key columns (in priority order)
     * @param withinColName
     *            the partitioning column, or {@code null} for a single group
     * @return the violating absolute rows
     */
    public static BitSet targetIsNotSortedByViolations(IDataTable table, int rowCount,
            @Nullable String targetCol, List<String> sortVars, @Nullable String withinColName)
    {
        return targetIsNotSortedByViolations(table, rowCount, targetCol, sortVars, withinColName,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
    }


    /**
     * {@link #targetIsNotSortedByViolations(IDataTable, int, String, List, String)} under an
     * explicit {@link GroupKeyPolicy}. The five-argument overload is this one under
     * {@link GroupKeyPolicy#FOLD_BLANK_KEYS} — the shipped behaviour, which <b>keeps</b> a blank
     * {@code within} key (each blank kind its own bucket since {@code W38-A1}).
     *
     * <p>
     * ⚠ Keeping blanks is questionable for an <em>ordering</em> operator and this is the surface
     * where that shows most sharply: pooling the blank-keyed rows (all-{@code ""}, or
     * all-one-marker) fabricates a record chain, so one subject's last row is compared against
     * another subject's first and the operator reports a sort violation that the data never
     * asserted. Six shipped rules reach this site ({@code CDISC-CG0620}, {@code CDISC-CG0662},
     * {@code CDISC-SEND-0130}, {@code CDISC-SEND-0130.1}, {@code CDISC-SEND-0354},
     * {@code CORE-000535}). Correcting them is an <b>authoring</b> change — declaring
     * {@code keep_missings: false} — and is deliberately <em>not</em> done by changing this
     * default, so that the change is visible per rule and its finding delta attributable.
     * </p>
     *
     * @param policy
     *            what a blank {@code within} key means
     */
    public static BitSet targetIsNotSortedByViolations(IDataTable table, int rowCount,
            @Nullable String targetCol, List<String> sortVars, @Nullable String withinColName,
            GroupKeyPolicy policy)
    {
        if (targetCol == null || sortVars.isEmpty())
        {
            return new BitSet();
        }
        DataTableMeta meta = table.getMetaData();
        int targetIdx = meta.getColumnIndex(targetCol);
        if (targetIdx < 0 || rowCount <= 1)
        {
            return new BitSet();
        }
        int[] sortColIdx = new int[sortVars.size()];
        for (int i = 0; i < sortVars.size(); i++)
        {
            sortColIdx[i] = meta.getColumnIndex(sortVars.get(i));
        }
        int withinColIdx = withinColName != null ? meta.getColumnIndex(withinColName) : -1;

        Map<KeyPart, List<Integer>> groups = new LinkedHashMap<>();
        int[] withinKeyIdx = withinColIdx >= 0 ? new int[]
        {
                withinColIdx
        } : new int[0];
        for (int r = 0; r < rowCount; r++)
        {
            if (rowKeyDiscarded(table, withinKeyIdx, r, policy))
            {
                // A discarding policy drops a blank-keyed row rather than chaining it to the other
                // blank-keyed rows. Never reached under the shipped FOLD_BLANK_KEYS default.
                continue;
            }
            KeyPart key = withinColIdx >= 0 ? keyComponent(table.getColumn(withinColIdx), r, policy)
                    : KeyPart.EMPTY;
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(r);
        }

        BitSet result = new BitSet(rowCount);
        for (List<Integer> groupRows : groups.values())
        {
            if (groupRows.size() < 2)
            {
                continue;
            }
            groupRows.sort((a, b) ->
            {
                for (int ci : sortColIdx)
                {
                    if (ci < 0)
                    {
                        continue;
                    }
                    IDataValue va = table.getColumn(ci).getDataValue(a);
                    IDataValue vb = table.getColumn(ci).getDataValue(b);
                    String sa = ScalarSemantics.isMissing(va) ? "" : va.getValueAsString();
                    String sb = ScalarSemantics.isMissing(vb) ? "" : vb.getValueAsString();
                    int c = sa.compareTo(sb);
                    if (c != 0)
                    {
                        return c;
                    }
                }
                return 0;
            });
            String prevTarget = null;
            for (int r : groupRows)
            {
                IDataValue dv = table.getColumn(targetIdx).getDataValue(r);
                String cur = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
                if (prevTarget != null && compareNumericFirstStringFallback(prevTarget, cur) > 0)
                {
                    for (int rr : groupRows)
                    {
                        result.set(rr);
                    }
                    break;
                }
                prevTarget = cur;
            }
        }
        return result;
    }


    /**
     * Comparator for {@link #targetIsNotSortedByViolations}. SDTM {@code --SEQ} values are integers
     * stored as strings; a plain string compare would mis-order {@code "10"} vs {@code "2"}. Prefer
     * numeric comparison, falling back to string when either value is non-numeric.
     */
    private static int compareNumericFirstStringFallback(String a, String b)
    {
        try
        {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        }
        catch (NumberFormatException _)
        {
            return a.compareTo(b);
        }
    }

    // -------------------------------------------------------------------------
    // is_not_unique_relationship (bidirectional 1:1)
    // -------------------------------------------------------------------------


    /**
     * The {@code is_not_unique_relationship} verdict: flags rows where the {@code nameCol} /
     * {@code valueCol} pair is not a 1:1 (bijective) mapping — i.e. some name maps to multiple
     * values <i>or</i> some value maps to multiple names. A row with a blank cell — empty or
     * genuine-missing, i.e. not {@link KeyPart.Present} — in either column is <b>excluded</b>: such
     * a cell is not a participant in the relationship, so it is neither a key nor ever flagged.
     * (This is the key-exclusion half of Python's {@code dropna(how="all")} + empty-key skip; Java
     * does not additionally implement Python's {@code has_null} decode-present/code-blank flag —
     * that signal is owned by the dedicated decode/code coverage rules, see plan J1/1b.)
     *
     * @param nameCol
     *            the first relation column
     * @param valueCol
     *            the second relation column
     * @param rowCount
     *            the row count
     * @return the violating absolute rows
     */
    public static BitSet relationshipNotUniqueViolations(IDataTableColumn nameCol,
            IDataTableColumn valueCol, int rowCount)
    {
        KeyPart[] aVals = new KeyPart[rowCount];
        Object[] bVals = new Object[rowCount];
        boolean[] participates = new boolean[rowCount];
        for (int r = 0; r < rowCount; r++)
        {
            KeyPart a = keyPart(nameCol, r);
            KeyPart b = keyPart(valueCol, r);
            aVals[r] = a;
            bVals[r] = b;
            // A blank cell is not a participant in the relationship — pairing it would make an
            // all-blank code column map its blank -> every term and flag every row. A TYPE test
            // (W38-A1): Empty and Missing are non-participants, whatever they render as.
            participates[r] = a instanceof KeyPart.Present && b instanceof KeyPart.Present;
        }
        return relationshipNotUniqueCore(aVals, bVals, participates, rowCount);
    }


    /**
     * Multi-column {@code is_not_unique_relationship(NAME, keys=[V1, V2, …])}: flags rows whose
     * {@code nameColName} value and the <i>tuple</i> {@code (valueColNames…)} are not a 1:1
     * mapping. Mirrors Python's list-comparator form (a name mapping to multiple distinct value
     * tuples, or a value tuple mapping to multiple names, is a violation). A value column absent
     * from the table is dropped (the legacy contract); if none remain the relationship has no
     * comparator side and nothing fires. A row whose name <i>or any</i> value component is blank
     * (not {@link KeyPart.Present}) is excluded as a non-participant — the same key-exclusion rule
     * the single-column form applies, generalised component-wise. (Like the single-column overload,
     * Java does not additionally implement Python's {@code has_null} present-code/blank-decode
     * flag; that signal is owned by the dedicated coverage rules.)
     *
     * @param table
     *            the dataset
     * @param rowCount
     *            the row count
     * @param nameColName
     *            the name (target) column
     * @param valueColNames
     *            the comparator tuple columns
     * @return the violating absolute rows
     */
    public static BitSet relationshipNotUniqueViolations(IDataTable table, int rowCount,
            @Nullable String nameColName, List<String> valueColNames)
    {
        if (nameColName == null || rowCount <= 0)
        {
            return new BitSet();
        }
        DataTableMeta meta = table.getMetaData();
        int nameIdx = meta.getColumnIndex(nameColName);
        // EC-53: unlike uniqueSetViolations this early-out is a FAST PATH, not a carve-out — it
        // agrees with the all-missing contract instead of overriding it. An absent name column is
        // blank on every row, and a blank cell is a NON-PARTICIPANT here (see `participates`
        // below), so the relation would end up with zero participants and flag nothing. Same
        // answer, computed in O(1). Pinned by
        // GroupSemanticsRelationshipTest.absentNameColumnMatchesAllBlankNameColumn.
        // ⚠ blank-vs-whitespace: `participates` is a KeyPart TYPE test, so a WHITESPACE-ONLY cell
        // (Present(" ")) does participate — unlike participatingTarget() below, which also
        // excludes whitespace-only values. That distinction does not affect this early-out (an
        // absent column is blank, never " "), but do not paraphrase the two as the same test.
        if (nameIdx < 0)
        {
            return new BitSet();
        }
        List<IDataTableColumn> valueCols = new ArrayList<>(valueColNames.size());
        for (String colName : valueColNames)
        {
            int idx = meta.getColumnIndex(colName);
            if (idx >= 0)
            {
                valueCols.add(table.getColumn(idx));
            }
        }
        if (valueCols.isEmpty())
        {
            return new BitSet();
        }
        IDataTableColumn nameCol = table.getColumn(nameIdx);
        KeyPart[] aVals = new KeyPart[rowCount];
        Object[] bVals = new Object[rowCount];
        boolean[] participates = new boolean[rowCount];
        for (int r = 0; r < rowCount; r++)
        {
            KeyPart a = keyPart(nameCol, r);
            boolean ok = a instanceof KeyPart.Present;
            // The value tuple is the per-column KeyPart list — component-wise record equality, so
            // distinct tuples cannot collide by construction (W38-A1; the SOH-joined string this
            // replaces was collision-free only for folded cell text).
            List<KeyPart> tuple = new ArrayList<>(valueCols.size());
            for (int i = 0; i < valueCols.size(); i++)
            {
                KeyPart comp = keyPart(valueCols.get(i), r);
                if (!(comp instanceof KeyPart.Present))
                {
                    ok = false;
                }
                tuple.add(comp);
            }
            aVals[r] = a;
            bVals[r] = tuple;
            participates[r] = ok;
        }
        return relationshipNotUniqueCore(aVals, bVals, participates, rowCount);
    }


    /**
     * Shared bidirectional 1:1 core for {@link #relationshipNotUniqueViolations}: given per-row
     * {@code (name, value)} keys and a participation mask, flags every participating row whose name
     * maps to multiple values <i>or</i> whose value maps to multiple names. The value side is
     * {@code Object}-typed because the two overloads key it differently — a single {@link KeyPart}
     * vs a {@code List<KeyPart>} tuple — and both carry value equality.
     */
    private static BitSet relationshipNotUniqueCore(KeyPart[] aVals, Object[] bVals,
            boolean[] participates, int rowCount)
    {
        Map<KeyPart, Set<Object>> aToB = new HashMap<>();
        Map<Object, Set<KeyPart>> bToA = new HashMap<>();
        for (int r = 0; r < rowCount; r++)
        {
            if (!participates[r])
            {
                continue;
            }
            aToB.computeIfAbsent(aVals[r], _ -> new HashSet<>()).add(bVals[r]);
            bToA.computeIfAbsent(bVals[r], _ -> new HashSet<>()).add(aVals[r]);
        }
        Set<KeyPart> nonUniqueA = keysWithMultipleDependents(aToB);
        Set<Object> nonUniqueB = keysWithMultipleDependents(bToA);

        BitSet result = new BitSet(rowCount);
        for (int r = 0; r < rowCount; r++)
        {
            if (!participates[r])
            {
                continue;
            }
            if (nonUniqueA.contains(aVals[r]) || nonUniqueB.contains(bVals[r]))
            {
                result.set(r);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // is_not_unique_set / is_unique_set (key-tuple uniqueness)
    // -------------------------------------------------------------------------


    /**
     * The {@code is_not_unique_set} ({@code flagDuplicates == true}) / {@code is_unique_set}
     * ({@code false}) verdict over the key tuple {@code keyCols}. <b>No member is privileged</b>
     * (owner requirement #1, 2026-08-23, {@code is_unique_set([V1, V2, …])}): a member absent from
     * the table is dropped and the check regroups on the survivors — including the first (EC-53 /
     * Fix #143, now stated without a "target"); a {@code null} member (an unresolved operand — a
     * {@code $}-ref that resolved to nothing, a non-column expression) takes the <em>same</em> drop
     * (D-4), because a member resolving to nothing cannot discriminate any more than an absent
     * column can. {@code ""} and a genuine missing are real, distinct key components (D.1 keep
     * axis; {@code W38-A1}). When every member drops the tuple is empty and every row shares one
     * key — the documented degenerate case ({@code flagDuplicates} floods, its complement is
     * empty); an <em>authored</em> empty list is rejected at load instead
     * ({@code RulePackageLoader.validateInlineUniqueSetShape}).
     *
     * <p>
     * <b>Why an absent member is dropped rather than treated as a carve-out (EC-53 / Fix #143).</b>
     * The house contract is that an absent column is a column whose values are all missing (EC-43 /
     * Fix #139), and D.1 makes {@code ""} a real key value here — so a present-but-all-blank member
     * contributes the <em>same</em> component to every row's tuple and cannot tell two rows apart.
     * Dropping it is therefore exact, not an approximation: it is the identical partition. Before
     * Fix #143 the first member ("target") answered absence with an empty {@code BitSet} for both
     * polarities ("not applicable"); that was the last surviving carve-out from the all-missing
     * contract. The historical over-firing defects (CORE-000213, CORE-001034) do <b>not</b> return:
     * those rules guard their member with {@code exists}, and the regrouped check flags a row only
     * when some other row carries the same surviving key tuple.
     * </p>
     *
     * <p>
     * <b>⚠ The degenerate case is not a defect but it is loud.</b> When every member is absent the
     * tuple is empty, so every row carries the same key and they are <b>all</b> duplicates of one
     * another — the whole dataset fires, exactly as a table of present-but-all-blank key columns
     * would. A <em>single-member</em> {@code not is_unique_set([X])} over an absent {@code X}
     * therefore reports one finding per record; the corpus's six single-member sites guard it with
     * {@code exists} (the sixth, {@code CDISC-AD0054}, was granted the absent-column policy's Q2
     * exception, user 2026-08-04, and carries both an {@code exists} leaf and a
     * {@code Scope.Variables.Include}). See EC-53 in {@code PLAN-rule-review-engine-changes.md}.
     * </p>
     *
     * <p>
     * Under {@link GroupKeyPolicy#FOLD_BLANK_KEYS} — the shipped behaviour — {@code ""} and a
     * genuine missing are both kept as real key components (operator-examples.md D.1's keep axis),
     * each with its own {@link KeyPart} identity since {@code W38-A1} (Fix #249). Under a
     * discarding policy a row with any blank key component takes no part in the uniqueness check at
     * all: it is neither a duplicate nor a unique candidate.
     * </p>
     *
     * @param table
     *            the dataset
     * @param rowCount
     *            the row count
     * @param keyCols
     *            the key tuple's members, in authored order; a {@code null} or absent member is
     *            dropped
     * @param regex
     *            the optional {@code regex=} normalization pattern, or {@code null}
     * @param flagDuplicates
     *            {@code true} flags duplicate tuples, {@code false} flags unique tuples
     * @param policy
     *            what a blank key-tuple component means
     * @return the violating absolute rows
     */
    public static BitSet uniqueSetViolations(IDataTable table, int rowCount,
            List<? extends @Nullable String> keyCols, @Nullable String regex,
            boolean flagDuplicates, GroupKeyPolicy policy)
    {
        if (rowCount <= 0)
        {
            return new BitSet();
        }
        DataTableMeta meta = table.getMetaData();
        int[] keyColIds = new int[keyCols.size()];
        int n = 0;
        for (String col : keyCols)
        {
            // The ONLY semantic edit of the 2026-08-23 flattening: a null member takes the same
            // drop as an absent column, where the old signature returned an empty BitSet for the
            // whole check when its first operand was null.
            int idx = col == null ? -1 : meta.getColumnIndex(col);
            if (idx >= 0)
            {
                keyColIds[n++] = idx;
            }
        }
        if (n < keyColIds.length)
        {
            keyColIds = Arrays.copyOf(keyColIds, n);
        }

        // Optional regex= normalization (mirrors Python is_unique_set): a key column whose first
        // non-missing value matches the (anchored) pattern is collapsed to its first regex match
        // before grouping (so e.g. a datetime key groups at date granularity); columns whose sample
        // does not match — and the no-regex case — are left verbatim.
        @Nullable
        Pattern[] colPattern = regexColumnPatterns(table, keyColIds, rowCount, regex);

        BitSet duplicates = new BitSet(rowCount);
        BitSet uniqueCandidates = new BitSet(rowCount);
        // Both {@code ""} and a genuine missing count as real key components (operator-examples.md
        // D.1's keep axis) — and since W38-A1 (Fix #249) they are DISTINCT key values, so the
        // KeyPart-tuple-keyed map now agrees with the raw-value HashLookup, which always treated a
        // MissingValue and a literal "" as distinct.
        Map<List<KeyPart>, Integer> firstSeen = new HashMap<>();
        for (int r = 0; r < rowCount; r++)
        {
            if (rowKeyDiscarded(table, keyColIds, r, policy))
            {
                // A discarding policy takes the row out of the uniqueness question entirely — it
                // is neither a duplicate nor a unique candidate. Never reached under the shipped
                // FOLD_BLANK_KEYS default.
                continue;
            }
            List<KeyPart> key = foldedKey(table, keyColIds, colPattern, r, policy);
            Integer existing = firstSeen.putIfAbsent(key, r);
            if (existing == null)
            {
                uniqueCandidates.set(r);
            }
            else
            {
                duplicates.set(existing);
                duplicates.set(r);
                uniqueCandidates.clear(existing);
            }
        }
        return flagDuplicates ? duplicates : uniqueCandidates;
    }

    // -------------------------------------------------------------------------
    // is_inconsistent_across_dataset (one value per group)
    // -------------------------------------------------------------------------


    /**
     * The {@code is_inconsistent_across_dataset} verdict: groups rows by {@code groupColNames}
     * (silently dropping any column absent from the table, the legacy contract), and within each
     * inconsistent group (more than one distinct non-blank {@code nameColName} value) flags the
     * <b>minority</b> rows — those not holding the group's most-common value, all rows on a tie —
     * matching Python {@code _check_inconsistency} (operator-examples.md D.2). A blank target
     * (empty, genuine missing, or whitespace-only) is excluded from both the count and the flag
     * pass — a deliberate emptiness-exception. Groups with a missing/invalid <i>key</i> are skipped
     * (via {@link #partition}).
     *
     * @param table
     *            the dataset
     * @param nameColName
     *            the column whose per-group consistency is checked
     * @param groupColNames
     *            the grouping columns
     * @param rowCount
     *            the row count
     * @return the violating absolute rows
     */
    public static BitSet inconsistentAcrossDatasetViolations(IDataTable table,
            @Nullable String nameColName, List<String> groupColNames, int rowCount)
    {
        return inconsistentAcrossDatasetViolations(table, nameColName, groupColNames, rowCount,
                false);
    }


    /**
     * The {@code is_inconsistent_across_dataset} verdict with an explicit emptiness switch (Fix
     * #121, Java-only). With {@code includeEmpty == false} this is exactly
     * {@link #inconsistentAcrossDatasetViolations(IDataTable, String, List, int)} — the D.2
     * emptiness-exception applies. With {@code includeEmpty == true} a blank target participates as
     * a real value: every blank flavour (empty {@code ""}, genuine missing, whitespace-only)
     * contributes the single canonical blank value ({@link KeyPart#EMPTY}) — mirroring the
     * operator's own whitespace-aware blankness notion — which counts toward the group's distinct
     * values and is flagged when it is a minority (or on a tie) like any other value. Grouping-key
     * semantics are unchanged.
     *
     * @param table
     *            the dataset
     * @param nameColName
     *            the column whose per-group consistency is checked
     * @param groupColNames
     *            the grouping columns
     * @param rowCount
     *            the row count
     * @param includeEmpty
     *            {@code true} to let blank targets participate (folded to {@code ""})
     * @return the violating absolute rows
     */
    public static BitSet inconsistentAcrossDatasetViolations(IDataTable table,
            @Nullable String nameColName, List<String> groupColNames, int rowCount,
            boolean includeEmpty)
    {
        return inconsistentAcrossDatasetViolations(table, nameColName, groupColNames, rowCount,
                includeEmpty, GroupKeyPolicy.DROP_MISSING_KEYS);
    }


    /**
     * {@link #inconsistentAcrossDatasetViolations(IDataTable, String, List, int, boolean)} under an
     * explicit {@link GroupKeyPolicy}. The five-argument overload is this one under
     * {@link GroupKeyPolicy#DROP_MISSING_KEYS} — the shipped behaviour, where a group whose key
     * carries a genuine missing marker is skipped.
     *
     * <p>
     * ⚠ {@code policy} is the <b>group-membership</b> axis and {@code includeEmpty} is the
     * <b>value-participation</b> axis; they are independent and must not be conflated.
     * {@code includeEmpty} (Fix #121) decides whether a blank <em>target</em> counts as a value,
     * {@code policy} decides whether a row with a blank <em>key</em> is in a group at all.
     * </p>
     *
     * @param policy
     *            what a blank grouping key means
     */
    public static BitSet inconsistentAcrossDatasetViolations(IDataTable table,
            @Nullable String nameColName, List<String> groupColNames, int rowCount,
            boolean includeEmpty, GroupKeyPolicy policy)
    {
        if (nameColName == null)
        {
            return new BitSet();
        }
        DataTableMeta meta = table.getMetaData();
        int nameIdx = meta.getColumnIndex(nameColName);
        // EC-53 (the plan's Q3): a FAST PATH, not a carve-out. An absent target folds to a blank
        // on every row, and flagInconsistentGroupMinority answers a blank target the same way
        // under either emptiness switch — with includeEmpty == false the D.2 exception drops
        // every row so `counts` is empty; with includeEmpty == true (Fix #121) every row folds to
        // the single value "" so `counts.size() == 1`. Both hit `counts.size() <= 1` and flag
        // nothing, in every group. Same answer, computed in O(1). Pinned by
        // GroupSemanticsCoalesceTest.absentTargetMatchesAllBlankTargetInBothEmptinessModes.
        // ⚠ The plan's Q3 asked this because it believed is_inconsistent_across_dataset shared
        // is_unique_set's membership of the fork's ABSENT_TARGET_AWARE_OPERATORS. IT DOES NOT —
        // that set holds `inconsistent_enumerated_columns`, a different operator (verified in
        // check_operators/dataframe_operators.py:76-88). So this operator was never in the
        // carve-out class on either lane, and the two lanes still agree here. The conclusion the
        // plan reached is right; its premise was not.
        if (nameIdx < 0)
        {
            return new BitSet();
        }
        IDataTableColumn nameCol = table.getColumn(nameIdx);
        List<String> validGroupCols = new ArrayList<>();
        for (String col : groupColNames)
        {
            if (meta.getColumnIndex(col) >= 0)
            {
                validGroupCols.add(col);
            }
        }
        // EC-44 (Fix #134): no early return when every comparator is absent. partition() now
        // answers that case with one whole-table group — an absent comparator cannot tell two rows
        // apart, so they are all in one consistency class. The pre-Fix-#134 all-False here was
        // inherited from EC-25 / Fix #116, which introduced it only to stop the fork's
        // `groupby([])` raising on conformant data; one group avoids that raise just as well.
        List<int[]> groups = group(table, validGroupCols, policy);
        BitSet result = new BitSet(rowCount);
        for (int[] g : groups)
        {
            flagInconsistentGroupMinority(nameCol, g, result, includeEmpty);
        }
        return result;
    }


    /**
     * For one {@code is_inconsistent_across_dataset} group: a blank target value (empty, genuine
     * missing, or whitespace-only — see {@link #participatingTarget}) is a deliberate
     * emptiness-exception — it neither counts toward the group's distinct values nor is ever
     * flagged (operator-examples.md D.2). When the remaining non-blank targets hold more than one
     * distinct value, flag the minority non-blank rows — every non-blank row whose value is not the
     * unique most-common value of the group; on a tie for most-common (no strict majority winner)
     * every non-blank row is flagged. A group with at most one distinct non-blank value flags
     * nothing. The blankness notion (whitespace-only counts as blank) mirrors Python
     * {@code _check_inconsistency} exactly.
     *
     * <p>
     * With {@code includeEmpty == true} (Fix #121) the emptiness-exception is disabled: every blank
     * flavour contributes the one canonical blank value ({@link KeyPart#EMPTY}), which participates
     * in the count and the flag pass as one real value.
     * </p>
     */
    private static void flagInconsistentGroupMinority(IDataTableColumn nameCol, int[] g,
            BitSet result, boolean includeEmpty)
    {
        Map<KeyPart, Integer> counts = new HashMap<>();
        for (int r : g)
        {
            KeyPart v = participatingTarget(nameCol, r, includeEmpty);
            if (v != null)
            {
                counts.merge(v, 1, Integer::sum);
            }
        }
        if (counts.size() <= 1)
        {
            return;
        }
        int maxCount = 0;
        int maxCountOccurrences = 0;
        for (int c : counts.values())
        {
            if (c > maxCount)
            {
                maxCount = c;
                maxCountOccurrences = 1;
            }
            else if (c == maxCount)
            {
                maxCountOccurrences++;
            }
        }
        // A unique most-common value exists only when exactly one value holds maxCount; otherwise
        // it is a tie and every non-blank row is a "minority" → flag all.
        boolean tie = maxCountOccurrences > 1;
        for (int r : g)
        {
            KeyPart v = participatingTarget(nameCol, r, includeEmpty);
            if (v == null)
            {
                // A blank target neither conflicts nor is flagged (D.2 emptiness-exception).
                continue;
            }
            if (tie || counts.getOrDefault(v, 0) < maxCount)
            {
                result.set(r);
            }
        }
    }


    /**
     * The value row {@code r} contributes to the {@code is_inconsistent_across_dataset} group, or
     * {@code null} when it is excluded. A non-blank cell contributes its {@link KeyPart.Present}
     * identity. A blank cell — {@code Empty}, {@code Missing}, or a whitespace-only {@code Present}
     * — is excluded under the D.2 emptiness-exception ({@code includeEmpty == false}), and
     * contributes the canonical {@link KeyPart#EMPTY} with {@code includeEmpty == true} (Fix #121)
     * so all blank flavours are one real value.
     *
     * <p>
     * ⚠ The blank/non-blank split is a {@code KeyPart} <b>type test</b> plus the whitespace check
     * on a {@code Present} value — never a test of a rendered string ({@code W38-A1}; the old
     * {@code strip().isEmpty()} on the folded rendering is exactly what turned every missing into a
     * participating value under a distinct-as-strings encoding). The whitespace half is a real
     * property of a real value, so testing it on {@code Present.value} infers nothing from a
     * rendering.
     * </p>
     */
    private static @Nullable KeyPart participatingTarget(IDataTableColumn nameCol, long r,
            boolean includeEmpty)
    {
        KeyPart v = keyPart(nameCol, r);
        if (v instanceof KeyPart.Present(String s) && !s.strip().isEmpty())
        {
            return v;
        }
        return includeEmpty ? KeyPart.EMPTY : null;
    }

    // -------------------------------------------------------------------------
    // inconsistent_enumerated_columns (gaps in TSVAL/TSVAL1/TSVAL2…)
    // -------------------------------------------------------------------------


    /**
     * The {@code inconsistent_enumerated_columns} verdict: for a base column {@code baseName} and
     * its numbered variants {@code baseName1, baseName2, …} (in order, stopping at the first absent
     * variant), flags a row where a lower-numbered column is missing but a higher-numbered one is
     * populated (a gap).
     *
     * @param table
     *            the dataset
     * @param baseName
     *            the base column name
     * @param rowCount
     *            the row count
     * @return the violating absolute rows
     */
    public static BitSet inconsistentEnumeratedColumnsViolations(IDataTable table,
            @Nullable String baseName, int rowCount)
    {
        if (baseName == null)
        {
            return new BitSet();
        }
        DataTableMeta meta = table.getMetaData();
        List<Integer> colIndices = new ArrayList<>();
        int baseIdx = meta.getColumnIndex(baseName);
        if (baseIdx >= 0)
        {
            colIndices.add(baseIdx);
        }
        for (int num = 1; num <= 99; num++)
        {
            int idx = meta.getColumnIndex(baseName + num);
            if (idx < 0)
            {
                break;
            }
            colIndices.add(idx);
        }
        if (colIndices.size() <= 1)
        {
            return new BitSet();
        }
        BitSet result = new BitSet(rowCount);
        for (int r = 0; r < rowCount; r++)
        {
            boolean sawEmpty = false;
            for (int ci : colIndices)
            {
                IDataValue dv = table.getColumn(ci).getDataValue(r);
                if (ScalarSemantics.isMissing(dv))
                {
                    sawEmpty = true;
                }
                else if (sawEmpty)
                {
                    result.set(r);
                    break;
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // has_same_values / not_contains_all (whole-column aggregates)
    // -------------------------------------------------------------------------


    /**
     * The {@code has_same_values} verdict: when every value in {@code nameColName} is identical
     * (exactly one distinct value over all {@code rowCount} rows) the categorization is
     * meaningless, so all rows are flagged; otherwise none are. {@code ""} and a genuine missing
     * both count as a real value (operator-examples.md D.3), each with its own {@link KeyPart}
     * identity since {@code W38-A1} (Fix #249) — a column holding one {@code ""} row and one
     * missing-marker row has <em>two</em> distinct values.
     *
     * @param table
     *            the dataset
     * @param nameColName
     *            the checked column
     * @param rowCount
     *            the row count
     * @return the violating absolute rows
     */
    public static BitSet hasSameValuesViolations(IDataTable table, @Nullable String nameColName,
            int rowCount)
    {
        if (nameColName == null)
        {
            return new BitSet();
        }
        int colIdx = table.getMetaData().getColumnIndex(nameColName);
        if (colIdx < 0)
        {
            return new BitSet();
        }
        IDataTableColumn col = table.getColumn(colIdx);
        Set<KeyPart> distinctValues = new HashSet<>();
        for (int r = 0; r < rowCount; r++)
        {
            distinctValues.add(keyPart(col, r));
            if (distinctValues.size() > 1)
            {
                return new BitSet();
            }
        }
        return distinctValues.size() == 1 ? allSet(rowCount) : new BitSet();
    }


    /**
     * The distinct values of {@code colName}, in first-seen order, counting {@code ""} and a
     * genuine missing as real values (operator-examples.md D.6); {@code null} when {@code colName}
     * is {@code null} or absent from the table (the source the legacy {@code not_contains_all}
     * treats as "certainly does not contain all").
     *
     * <p>
     * The set holds each cell's {@link KeyPart#reportingForm()}: consumers compare these against
     * <em>authored / real string</em> values ({@code not_contains_all}'s required list, per-row
     * {@code split_by} tokens), and the rendering keeps the ruled identity in that string domain —
     * {@code ""} satisfies a required {@code ""}, distinct missing markers stay distinct, and a
     * {@code Missing} renders a token no real string can equal, so a missing cell can never satisfy
     * a required real value such as {@code "."} ({@code W38-A1} part 4). ⚠ The rendering is
     * compared for whole-string equality only — never parsed back into an identity.
     * </p>
     *
     * @param table
     *            the dataset
     * @param colName
     *            the source column name
     * @param rowCount
     *            the row count
     * @return the distinct values, or {@code null} when the column is absent
     */
    public static @Nullable Set<String> distinctColumnValues(IDataTable table,
            @Nullable String colName, int rowCount)
    {
        if (colName == null)
        {
            return null;
        }
        int idx = table.getMetaData().getColumnIndex(colName);
        if (idx < 0)
        {
            return null;
        }
        IDataTableColumn col = table.getColumn(idx);
        Set<String> distinctValues = new LinkedHashSet<>();
        for (int r = 0; r < rowCount; r++)
        {
            distinctValues.add(keyPart(col, r).reportingForm());
        }
        return distinctValues;
    }


    /**
     * The {@code not_contains_all} verdict: flags all rows when the {@code distinctValues} source
     * is absent ({@code null}) or does not contain every entry of {@code requiredValues}; otherwise
     * flags none.
     *
     * @param distinctValues
     *            the source's distinct values, or {@code null} when the source is absent
     * @param requiredValues
     *            the values that must all be present
     * @param rowCount
     *            the row count
     * @return the violating absolute rows
     */
    public static BitSet notContainsAllVerdict(@Nullable Set<String> distinctValues,
            List<String> requiredValues, int rowCount)
    {
        if (distinctValues == null || !distinctValues.containsAll(requiredValues))
        {
            return allSet(rowCount);
        }
        return new BitSet();
    }


    /**
     * Verdict of {@code shares_no_elements_with}. Both operands are converted to string sets
     * ({@code Collection} → per-element {@code toString}, scalar → singleton); when <b>either</b>
     * operand is unresolvable ({@code null}) every row is flagged; when the two sets share at least
     * one element no row is flagged; otherwise (disjoint sets) every row is flagged. The verdict is
     * row-independent (broadcast).
     */
    public static BitSet sharesNoElementsVerdict(@Nullable Object nameSet,
            @Nullable Object valueSet, int rowCount)
    {
        if (nameSet == null || valueSet == null)
        {
            return allSet(rowCount);
        }
        Set<String> setA = toStringSet(nameSet);
        Set<String> setB = toStringSet(valueSet);
        for (String s : setA)
        {
            if (setB.contains(s))
            {
                return new BitSet(); // shared element found → not flagged
            }
        }
        return allSet(rowCount); // no shared elements → flag all
    }


    /**
     * Verdict of {@code is_not_ordered_subset_of}. Both operands are converted to string lists;
     * when <b>either</b> operand is unresolvable ({@code null}) no row is flagged; when the
     * {@code name} list appears in the same order within the {@code value} list (gaps allowed) no
     * row is flagged; otherwise every row is flagged. Row-independent (broadcast).
     */
    public static BitSet isNotOrderedSubsetVerdict(@Nullable Object nameVal,
            @Nullable Object valueVal, int rowCount)
    {
        if (nameVal == null || valueVal == null)
        {
            return new BitSet();
        }
        List<String> subset = toStringList(nameVal);
        List<String> superList = toStringList(valueVal);
        int superIdx = 0;
        for (String item : subset)
        {
            boolean found = false;
            while (superIdx < superList.size())
            {
                if (superList.get(superIdx).equals(item))
                {
                    found = true;
                    superIdx++;
                    break;
                }
                superIdx++;
            }
            if (!found)
            {
                return allSet(rowCount); // not an ordered subset
            }
        }
        return new BitSet(); // is an ordered subset
    }


    /**
     * The distinct string values of a resolved {@code $}-operation source for
     * {@code not_contains_all} — the {@code $}-branch of the distinct-source-value contract: a
     * {@code Collection} maps per-element {@code toString} (insertion order kept, {@code null}
     * elements skipped); an absent or non-collection value yields the <b>empty</b> set (so any
     * non-empty requirement flags every row).
     */
    public static Set<String> distinctOperationValues(@Nullable Object resolved)
    {
        if (resolved instanceof java.util.Collection<?> col)
        {
            Set<String> out = LinkedHashSet.newLinkedHashSet(col.size());
            for (Object item : col)
            {
                if (item != null)
                {
                    out.add(item.toString());
                }
            }
            return out;
        }
        return Set.of();
    }


    /**
     * The string list of a resolved {@code $}-operation value — mirrors the {@code $}-branch of The
     * string-list contract: a {@code Collection} maps per-element {@code toString} with
     * {@code null} elements contributing the EMPTY string; a non-null scalar is a singleton; an
     * absent value yields the empty list.
     */
    public static List<String> operationStringList(@Nullable Object resolved)
    {
        if (resolved instanceof java.util.Collection<?> col)
        {
            List<String> out = new ArrayList<>(col.size());
            for (Object item : col)
            {
                out.add(item != null ? item.toString() : "");
            }
            return out;
        }
        return resolved != null ? List.of(resolved.toString()) : List.of();
    }


    /** Per-element {@code toString} set of a {@code Collection} value; scalar → singleton. */
    private static Set<String> toStringSet(Object value)
    {
        Set<String> result = new HashSet<>();
        if (value instanceof java.util.Collection<?> col)
        {
            for (Object item : col)
            {
                if (item != null)
                {
                    result.add(item.toString());
                }
            }
        }
        else
        {
            result.add(value.toString());
        }
        return result;
    }


    /** Per-element {@code toString} list of a {@code Collection} value; scalar → singleton. */
    private static List<String> toStringList(Object value)
    {
        List<String> result = new ArrayList<>();
        if (value instanceof java.util.Collection<?> col)
        {
            for (Object item : col)
            {
                if (item != null)
                {
                    result.add(item.toString());
                }
            }
        }
        else
        {
            result.add(value.toString());
        }
        return result;
    }


    private static BitSet allSet(int rowCount)
    {
        BitSet bs = new BitSet(rowCount);
        if (rowCount > 0)
        {
            bs.set(0, rowCount);
        }
        return bs;
    }

}
