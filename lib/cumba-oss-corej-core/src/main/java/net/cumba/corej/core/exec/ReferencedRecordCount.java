package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.merge.KeyMatchIndex;
import net.cumba.datatable.merge.JoinToken;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.GroupKeyPolicy.KeyPart;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Runbook W1b VA ({@code PLAN-engine-gap-value-addressed} V1, owner Q1–Q4 / Q9) — the
 * value-addressed record count {@code referenced_record_count(dataset, value, key_variable=,
 * keys=[…])}: per row, how many records of the dataset the row's {@code dataset} value names carry
 * the row's {@code value} in the column {@code key_variable} names and this row's {@code keys}
 * (same-named columns on both sides, a {@code Child} entry's standard keys). It is what a SUPP /
 * RELREC pointer or ADaM's {@code SRCDOM} / {@code SRCSEQ} traceability triple resolves to — a
 * count, never a merge: since {@code PLAN-merged-evaluation-table} a duplicate parent is
 * {@code n:m} (a {@code 1} side refuses it as a rule ERROR), so "resolves to exactly one record" is
 * observable only here.
 *
 * <p>
 * <b>What answers what.</b> A missing or blank {@code dataset}, {@code value} or column name
 * answers <b>missing</b>; a dataset no study member carries answers missing (Q4: "not loaded" is
 * {@code CDISC-AD0746}'s finding, not a broken pointer); a column the dataset lacks answers
 * {@code 0} (nothing can match); otherwise every matching record counts — a split family
 * ({@code lbch} / {@code lbhe}) sums its members. {@code key_variable} is either a per-row
 * expression naming the column ({@code IDVAR}) or a list of candidate templates resolved against
 * the <b>target</b> ({@code ["--SEQ", "ASEQ"]}: a leading {@code --} takes the target's domain
 * prefix, the first candidate the target carries wins — Q3; the bind-time resolver keeps the
 * templates, {@link ExprPrefixResolver#keepsTemplate}). A {@code keys} column absent on either side
 * is dropped from the key; a blank key component on either side matches nothing.
 * </p>
 *
 * <p>
 * <b>Identity.</b> The pointer compares as the join token of the text-carried joins
 * ({@link JoinToken#normalize}: stripped, canonicalised when the target column is numeric —
 * {@code "01"} / {@code "1.0"} are {@code 1}); the keys compare by their text-join identity
 * ({@link GroupKeyPolicy#textKeyIdentity}), the RELREC / SUPP convention (ruling D2 (c)). The
 * target is indexed once per (dataset member, pointer column, key shape) through the datatable's
 * {@link KeyMatchIndex} (Q9) — memoised for the run in {@link JoinCache.SharedIndexCache}, shared
 * across rules and threads — and probed once per row with no allocation beyond the probe key. The
 * composite key is the components' text joined with {@code NUL}: the index's own
 * {@code CompositeKey} is package-private to the datatable, and the text join is exactly the
 * identity the text-carried joins use.
 * </p>
 */
public final class ReferencedRecordCount
{

    /** The function name as authored. */
    public static final String NAME = "referenced_record_count";

    /** The dataset-naming parameter (a name slot: a string names the dataset). */
    public static final String DATASET_PARAMETER = "dataset";

    /** The pointer-value parameter. */
    public static final String VALUE_PARAMETER = "value";

    /** The pointer-column parameter: a per-row name, or the candidate templates (Q3). */
    public static final String KEY_VARIABLE_PARAMETER = "key_variable";

    /** The same-named key columns, a splice slot ({@link GroupSplice#KEYS_PARAMETER}). */
    public static final String KEYS_PARAMETER = GroupSplice.KEYS_PARAMETER;

    private static final char SEPARATOR = '\0';

    private ReferencedRecordCount()
    {
    }


    /**
     * The refusal of a qualified {@code J.X} at {@code keys=} (runbook W1b fold, R5 P6-L1): a key
     * names a column of BOTH the record's dataset and the referenced one, and the referenced side
     * is a raw dataset with no join — a qualified name never matched there and the key silently
     * dropped. One sentence for the loader ({@link #checkKeys}) and the compiler.
     *
     * @param fn
     *            the function
     * @param name
     *            the qualified name as written
     * @return the message
     */
    public static String qualifiedKeyRefusal(String fn, String name)
    {
        return "argument '" + KEYS_PARAMETER + "' of '" + fn
                + "' takes the key columns unqualified, not the qualified " + name
                + " — write it bare (" + name.substring(name.lastIndexOf('.') + 1)
                + "): a key is a column of both the record's dataset and the referenced one,"
                + " matched by name";
    }


    /**
     * The loader's seam of {@code referenced_record_count} / {@code referenced_key_is_unique}
     * ({@code RulePackageLoader}'s tailored readers): a qualified key is a load error on every
     * surface — the compiler meets the call only at evaluation.
     *
     * @param call
     *            the authored call
     * @throws net.cumba.corej.core.expr.ExpressionException
     *             for a qualified key
     */
    public static void checkKeys(net.cumba.corej.core.expr.ast.Expr.Call call)
    {
        net.cumba.corej.core.expr.eval.FunctionDescriptor descriptor = net.cumba.corej.core.expr.eval.FunctionRegistry
                .descriptor(call.name());
        if (descriptor == null)
        {
            return;
        }
        List<net.cumba.corej.core.expr.eval.Parameter> params = descriptor.parameters();
        List<net.cumba.corej.core.expr.ast.@Nullable Expr> bound = net.cumba.corej.core.expr.eval.ArgumentBinder
                .bind(descriptor, call);
        for (int i = 0; i < params.size() && i < bound.size(); i++)
        {
            if (KEYS_PARAMETER.equals(params.get(i).name())
                    && bound.get(i) instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit
                    && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof net.cumba.corej.core.expr.ast.Expr.Ref ref
                            && ref.kind() == net.cumba.corej.core.expr.OperandKind.DOTTED_REF)
                    {
                        throw new net.cumba.corej.core.expr.ExpressionException(
                                qualifiedKeyRefusal(call.name(), ref.name()));
                    }
                }
            }
        }
    }

    /**
     * The compiled {@code key_variable}: the per-row vector of column names, or the candidate
     * templates written as a string list (exactly one of the two). Built through the two factories;
     * read by the two readers of this package.
     */
    public static final class KeyVariable
    {

        private final @Nullable Vector perRow;

        private final @Nullable List<String> candidates;

        private KeyVariable(@Nullable Vector aPerRow, @Nullable List<String> aCandidates)
        {
            perRow = aPerRow;
            candidates = aCandidates;
        }


        /**
         * The per-row form: each row's cell names the column.
         *
         * @param aPerRow
         *            the names, one per row
         * @return the key variable
         */
        public static KeyVariable perRow(Vector aPerRow)
        {
            return new KeyVariable(aPerRow, null);
        }


        /**
         * The candidate form: the templates resolved against the referenced dataset (Q3).
         *
         * @param aCandidates
         *            the templates, in order (copied)
         * @return the key variable
         */
        public static KeyVariable candidates(List<String> aCandidates)
        {
            return new KeyVariable(null, List.copyOf(aCandidates));
        }


        @Nullable
        Vector perRow()
        {
            return perRow;
        }


        @Nullable
        List<String> candidates()
        {
            return candidates;
        }
    }


    /** One member of the referenced dataset with the pointer column resolved on it. */
    record Member(IDataTable table, int pointerColumn, boolean numeric)
    {
    }


    /** The resolved target of one distinct dataset value: its members, or none. */
    record Target(List<IDataTable> tables)
    {

        boolean absent()
        {
            return tables.isEmpty();
        }
    }

    /**
     * The function body: the count per row, as a {@code LONG} vector with the missing arms above.
     *
     * @param run
     *            the evaluation run
     * @param dataset
     *            the dataset-name vector
     * @param value
     *            the pointer-value vector
     * @param keyVariable
     *            the pointer column
     * @param keys
     *            the key members (spliced here; may be empty)
     * @return the per-row count
     */
    public static Vector evaluate(EvalRun run, Vector dataset, Vector value,
            KeyVariable keyVariable, List<GroupSplice.Member> keys)
    {
        EvaluationContext ctx = run.ctx();
        DatasetResolver resolver = ctx.getDatasetResolver();
        int rowCount = run.rowCount();
        List<String> keyNames = keyNames(NAME, keys, ctx);
        IDataTable primary = ctx.getTable();
        Map<String, Target> targets = new HashMap<>();
        Map<String, List<@Nullable Probe>> probes = new HashMap<>();
        long[] counts = new long[rowCount];
        boolean[] missing = new boolean[rowCount];
        for (int row = 0; row < rowCount; row++)
        {
            String datasetText = text(dataset.value(row).cell());
            String valueText = text(value.value(row).cell());
            String column = keyVariable.perRow() == null ? null
                    : text(keyVariable.perRow().value(row).cell());
            if (datasetText == null || valueText == null
                    || (keyVariable.perRow() != null && column == null))
            {
                missing[row] = true;
                continue;
            }
            Target target = targets.computeIfAbsent(datasetText,
                    d -> new Target(tablesNamed(resolver, d)));
            if (target.absent())
            {
                missing[row] = true;
                continue;
            }
            String probeKey = datasetText + SEPARATOR + (column == null ? "" : column);
            List<@Nullable Probe> members = probes.computeIfAbsent(probeKey, _ -> probesOf(ctx,
                    target, column, keyVariable.candidates(), keyNames, primary));
            long count = 0;
            for (Probe probe : members)
            {
                if (probe != null)
                {
                    count += probe.count(primary, row, valueText);
                }
            }
            counts[row] = count;
        }
        return new ComputedVector(rowCount, DataValueType.LONG,
                row -> missing[row] ? ScalarSemantics.computedMissing() : (Object) counts[row]);
    }

    /**
     * One member's index and the key column pairs the probe reads: {@code primaryKeys[i]} on the
     * evaluated table against the same-named column on the member (folded into the index).
     */
    private static final class Probe
    {

        private final KeyMatchIndex index;

        private final int[] primaryKeys;

        private final boolean numeric;

        Probe(KeyMatchIndex aIndex, int[] aPrimaryKeys, boolean aNumeric)
        {
            index = aIndex;
            primaryKeys = aPrimaryKeys;
            numeric = aNumeric;
        }


        long count(IDataTable primary, int row, String valueText)
        {
            StringBuilder key = new StringBuilder();
            for (int col : primaryKeys)
            {
                IDataValue cell = primary.getColumn(col).getDataValue(row);
                if (GroupKeyPolicy.FOLD_BLANK_KEYS.isBlankKeyComponent(cell))
                {
                    return 0; // a blank key component matches nothing
                }
                key.append(GroupKeyPolicy.FOLD_BLANK_KEYS.textKeyIdentity(cell)).append(SEPARATOR);
            }
            String token = JoinToken.normalize(valueText, numeric);
            if (token == null || token.isEmpty())
            {
                return 0;
            }
            key.append(token);
            int id = index.find(KeyPart.ofIdentity(key.toString()));
            return id == KeyMatchIndex.NOT_FOUND ? 0 : index.end(id) - index.start(id);
        }
    }

    /**
     * The probes of one (dataset, pointer column) pair, one per member that carries the pointer
     * column ({@code null} for a member that does not — it contributes 0).
     */
    private static List<@Nullable Probe> probesOf(EvaluationContext ctx, Target target,
            @Nullable String column, @Nullable List<String> candidates, List<String> keyNames,
            IDataTable primary)
    {
        List<@Nullable Probe> out = new ArrayList<>(target.tables().size());
        DataTableMeta primaryMeta = primary.getMetaData();
        for (IDataTable table : target.tables())
        {
            Member member = member(table, ctx.getDatasetResolver(), column, candidates);
            if (member == null)
            {
                out.add(null);
                continue;
            }
            DataTableMeta meta = table.getMetaData();
            List<int[]> pairs = new ArrayList<>(keyNames.size());
            for (String name : keyNames)
            {
                int onPrimary = primaryMeta.getColumnIndex(name);
                int onTarget = meta.getColumnIndex(name);
                if (onPrimary >= 0 && onTarget >= 0)
                {
                    pairs.add(new int[]
                    {
                            onPrimary, onTarget
                    });
                }
            }
            int[] primaryKeys = new int[pairs.size()];
            int[] targetKeys = new int[pairs.size()];
            StringBuilder shape = new StringBuilder(NAME).append(':').append(member.pointerColumn())
                    .append(':').append(member.numeric());
            for (int i = 0; i < pairs.size(); i++)
            {
                primaryKeys[i] = pairs.get(i)[0];
                targetKeys[i] = pairs.get(i)[1];
                shape.append(',').append(targetKeys[i]);
            }
            JoinCache.SharedIndexCache cache = ctx.getSharedIndexCache();
            KeyMatchIndex index = cache != null
                    ? cache.getOrBuildPointerIndex(table, shape.toString(),
                            t -> index(t, member.pointerColumn(), member.numeric(), targetKeys))
                    : index(table, member.pointerColumn(), member.numeric(), targetKeys);
            out.add(new Probe(index, primaryKeys, member.numeric()));
        }
        return out;
    }


    /** The index of {@code table} on ({@code keys}, the pointer column), the text-join identity. */
    private static KeyMatchIndex index(IDataTable table, int pointerColumn, boolean numeric,
            int[] keys)
    {
        int rows = Math.toIntExact(table.getRowCount());
        return KeyMatchIndex.build(rows, row ->
        {
            StringBuilder key = new StringBuilder();
            for (int col : keys)
            {
                IDataValue cell = table.getColumn(col).getDataValue(row);
                if (GroupKeyPolicy.FOLD_BLANK_KEYS.isBlankKeyComponent(cell))
                {
                    return null;
                }
                key.append(GroupKeyPolicy.FOLD_BLANK_KEYS.textKeyIdentity(cell)).append(SEPARATOR);
            }
            String token = JoinToken
                    .normalize(text(table.getColumn(pointerColumn).getDataValue(row)), numeric);
            if (token == null || token.isEmpty())
            {
                return null;
            }
            return KeyPart.ofIdentity(key.append(token).toString());
        });
    }


    /**
     * The pointer column of {@code table}: the per-row {@code column} when the table carries it,
     * else the first {@code candidates} template the table carries, resolved against the table (a
     * leading {@code --} is the table's domain prefix); {@code null} when none is carried.
     */
    static @Nullable Member member(IDataTable table, DatasetResolver resolver,
            @Nullable String column, @Nullable List<String> candidates)
    {
        DataTableMeta meta = table.getMetaData();
        String name = column;
        if (name == null && candidates != null)
        {
            name = candidateColumn(table, resolver, candidates);
        }
        int index = name == null ? -1 : meta.getColumnIndex(name);
        if (index < 0)
        {
            return null;
        }
        DataValueType type = meta.getColumn(index).getType();
        return new Member(table, index, type == DataValueType.LONG || type == DataValueType.DOUBLE);
    }


    /**
     * The first candidate template {@code table} carries, resolved against it, or {@code null}.
     */
    static @Nullable String candidateColumn(IDataTable table, DatasetResolver resolver,
            List<String> candidates)
    {
        DataTableMeta meta = table.getMetaData();
        String prefix = null;
        boolean prefixResolved = false;
        for (String candidate : candidates)
        {
            String name = candidate;
            if (candidate.startsWith("--"))
            {
                if (!prefixResolved)
                {
                    String dataset = meta.getName();
                    prefix = dataset == null ? null
                            : DatasetIdentity.domainOfDataset(dataset, resolver);
                    prefixResolved = true;
                }
                if (prefix == null || prefix.isEmpty())
                {
                    continue;
                }
                name = prefix.toUpperCase(Locale.ROOT) + candidate.substring(2);
            }
            if (meta.getColumnIndex(name) >= 0)
            {
                return name;
            }
        }
        return null;
    }


    /** The spliced key names of a {@code keys=} slot (empty for none). */
    static List<String> keyNames(String fn, List<GroupSplice.Member> keys, EvaluationContext ctx)
    {
        return keys.isEmpty() ? List.of()
                : GroupSplice.splice(fn, "keys= member", keys, ctx, false, "a keys= member of " + fn
                        + " is a column of the dataset under evaluation, written bare");
    }


    /**
     * The members a dataset value names ({@link ReferencedDatasetVariables}' resolution: the one
     * dataset of that name, else the split family's members, else a SUPP family's).
     */
    static List<IDataTable> tablesNamed(@Nullable DatasetResolver resolver, String dataset)
    {
        return ReferencedDatasetVariables.tablesNamed(resolver, dataset);
    }


    /**
     * The stripped text of a cell, or {@code null} for a missing or blank one — the reading of a
     * value that names a dataset or a column (shared with the computed accessor forms of V2).
     */
    public static @Nullable String text(IDataValue cell)
    {
        if (cell.isMissingOrInvalid())
        {
            return null;
        }
        String text = cell.getValueAsString();
        return text == null || text.isBlank() ? null : text.strip();
    }
}
