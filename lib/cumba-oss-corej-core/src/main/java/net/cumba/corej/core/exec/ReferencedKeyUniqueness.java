package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Runbook W1b VA ({@code PLAN-engine-gap-value-addressed} V4, owner Q5) — the value-addressed
 * uniqueness verdict {@code referenced_key_is_unique(dataset, key_variable=, keys=[…])}: per row,
 * whether the dataset the row's {@code dataset} value names has at most one record per
 * ({@code keys}, the column {@code key_variable} names) — RELREC's {@code RELTYPE = "ONE"}
 * declaration, checked against the dataset it is made for (sketch G). The verdict is computed once
 * per distinct (dataset, column) through {@link GroupSemantics#uniqueSetViolations}' key folding
 * under the shipped {@link GroupKeyPolicy#FOLD_BLANK_KEYS}: {@code ""} and a missing are real,
 * distinct key components, an absent column is dropped from the tuple — a stated answer, as
 * {@code is_unique_set} gives (Q5). A split family is judged as the ONE domain it is (SDTMIG: a
 * split domain's {@code --SEQ} is unique across its split datasets; runbook W1b fold, R5 P6-M2):
 * the keys are folded across every member, so a key carried by two members is a {@code false} — as
 * {@link ReferencedRecordCount} sums the members. A missing or blank {@code dataset} or column
 * name, and a dataset no study member carries, answer missing. {@code key_variable} and
 * {@code keys} are read exactly as {@link ReferencedRecordCount} reads them (a per-row name or the
 * candidate templates; the spliced same-named keys).
 */
public final class ReferencedKeyUniqueness
{

    /** The function name as authored. */
    public static final String NAME = "referenced_key_is_unique";

    private ReferencedKeyUniqueness()
    {
    }


    /**
     * The function body: the verdict per row, as a {@code BOOLEAN} vector with the missing arms
     * above.
     *
     * @param run
     *            the evaluation run
     * @param dataset
     *            the dataset-name vector
     * @param keyVariable
     *            the pointer column
     * @param keys
     *            the key members (spliced here; may be empty)
     * @return the per-row verdict
     */
    public static Vector evaluate(EvalRun run, Vector dataset,
            ReferencedRecordCount.KeyVariable keyVariable, List<GroupSplice.Member> keys)
    {
        EvaluationContext ctx = run.ctx();
        DatasetResolver resolver = ctx.getDatasetResolver();
        int rowCount = run.rowCount();
        List<String> keyNames = ReferencedRecordCount.keyNames(NAME, keys, ctx);
        Map<String, @Nullable Boolean> verdicts = new HashMap<>();
        Object[] cells = new Object[rowCount];
        for (int row = 0; row < rowCount; row++)
        {
            String datasetText = ReferencedRecordCount.text(dataset.value(row).cell());
            String column = keyVariable.perRow() == null ? null
                    : ReferencedRecordCount.text(keyVariable.perRow().value(row).cell());
            if (datasetText == null || (keyVariable.perRow() != null && column == null))
            {
                cells[row] = ScalarSemantics.computedMissing();
                continue;
            }
            String key = datasetText + '\0' + (column == null ? "" : column);
            Boolean verdict = verdicts.computeIfAbsent(key, _ -> verdict(resolver, datasetText,
                    column, keyVariable.candidates(), keyNames));
            cells[row] = verdict == null ? ScalarSemantics.computedMissing() : verdict;
        }
        return new ComputedVector(rowCount, DataValueType.BOOLEAN, row -> cells[row]);
    }


    /** The verdict of one (dataset, column) pair, or {@code null} for an absent dataset. */
    private static @Nullable Boolean verdict(@Nullable DatasetResolver resolver, String dataset,
            @Nullable String column, @Nullable List<String> candidates, List<String> keyNames)
    {
        List<IDataTable> tables = ReferencedRecordCount.tablesNamed(resolver, dataset);
        if (tables.isEmpty())
        {
            return null;
        }
        List<List<@Nullable String>> tuples = new ArrayList<>(tables.size());
        for (IDataTable table : tables)
        {
            String pointer = column;
            if (pointer == null && candidates != null && resolver != null)
            {
                pointer = ReferencedRecordCount.candidateColumn(table, resolver, candidates);
            }
            List<@Nullable String> tuple = new ArrayList<>(keyNames.size() + 1);
            tuple.addAll(keyNames);
            tuple.add(pointer); // an absent or unresolved column is dropped (Q5)
            tuples.add(tuple);
        }
        return !GroupSemantics.hasDuplicateKeyAcross(tables, tuples,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
    }
}
