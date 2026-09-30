package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;

/**
 * The provider-free dataset-level list functions of runbook wave 4 ({@code PLAN-list-functions}
 * §2.2): the walks over the current table's schema and over the study inventory. ⛔ The three
 * inventory walks are <b>loud</b> on a resolver that cannot enumerate the study (D-W4-5,
 * {@link ListFunctionSupport#inventory}); the retired operations answered {@code []} there,
 * silently.
 */
public final class InventoryLists
{

    /** {@code get_column_order_from_dataset()}. */
    public static final String GET_COLUMN_ORDER_FROM_DATASET = "get_column_order_from_dataset";

    /** {@code dataset_names()}. */
    public static final String DATASET_NAMES = "dataset_names";

    /** {@code study_domains()} (J7). */
    public static final String STUDY_DOMAINS = "study_domains";

    /** {@code split_sibling_length_mismatch()} (E10). */
    public static final String SPLIT_SIBLING_LENGTH_MISMATCH = "split_sibling_length_mismatch";

    /** {@code duplicate_label_variables()} (E7). */
    public static final String DUPLICATE_LABEL_VARIABLES = "duplicate_label_variables";

    private InventoryLists()
    {
    }


    /**
     * {@code get_column_order_from_dataset()} — the dataset's column names in declaration order, in
     * the dataset's own spelling.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector columnOrderFromDataset(EvalRun run, List<Vector> args)
    {
        DataTableMeta meta = run.ctx().getTable().getMetaData();
        int colCount = meta.getColumnCount();
        List<String> names = new ArrayList<>(colCount);
        for (int i = 0; i < colCount; i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return ListFunctionSupport.broadcast(GET_COLUMN_ORDER_FROM_DATASET, names);
    }


    /**
     * {@code dataset_names()} — the member names of every dataset in the study, upper-cased (FU-2:
     * case-invariant set compares in SD0061 / SD1063). Split members are listed as themselves
     * ({@code LBCH}, not {@code LB}) — that is {@link #studyDomains}' question.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector datasetNames(EvalRun run, List<Vector> args)
    {
        DatasetResolver.WithInventory inventory = ListFunctionSupport.inventory(run, DATASET_NAMES);
        return ListFunctionSupport.broadcast(DATASET_NAMES,
                DefineLists.upperCaseAll(inventory.availableDatasets()));
    }


    /**
     * {@code study_domains()} — J7, the distinct data-driven SDTM domains across the submission
     * (the {@code DOMAIN} cell, not the member name), so a split LB ({@code lbch} / {@code lbhe})
     * matches an {@code RDOMAIN} of {@code LB}; the no-domain datasets (SUPP, RELREC) contribute
     * {@code ""}.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector studyDomains(EvalRun run, List<Vector> args)
    {
        DatasetResolver.WithInventory inventory = ListFunctionSupport.inventory(run, STUDY_DOMAINS);
        return ListFunctionSupport.broadcast(STUDY_DOMAINS, inventory.availableDomains());
    }


    /**
     * {@code split_sibling_length_mismatch()} — E10, the variables whose declared column length
     * ({@link DataTableColumnMeta#getLength()}) differs across the current table's split-family
     * members (every dataset whose data-driven unsplit name equals the current table's,
     * {@link DatasetIdentity#unsplitNameFromData}). Empty when the dataset is not split or every
     * shared variable has a uniform length.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector splitSiblingLengthMismatch(EvalRun run, List<Vector> args)
    {
        DatasetResolver.WithInventory inventory = ListFunctionSupport.inventory(run,
                SPLIT_SIBLING_LENGTH_MISMATCH);
        String family = DatasetIdentity.unsplitNameFromData(run.ctx().getTable());
        List<IDataTable> members = new ArrayList<>();
        for (String name : inventory.availableDatasets())
        {
            IDataTable ds = inventory.resolve(name);
            if (ds != null && family.equals(DatasetIdentity.unsplitNameFromData(ds)))
            {
                members.add(ds);
            }
        }
        List<String> mismatched = new ArrayList<>();
        if (members.size() >= 2)
        {
            Map<String, Set<Integer>> lengthsByVar = new LinkedHashMap<>();
            for (IDataTable ds : members)
            {
                DataTableMeta meta = ds.getMetaData();
                int colCount = meta.getColumnCount();
                for (int c = 0; c < colCount; c++)
                {
                    DataTableColumnMeta colMeta = meta.getColumn(c);
                    lengthsByVar.computeIfAbsent(colMeta.getName(), _ -> new LinkedHashSet<>())
                            .add(colMeta.getLength());
                }
            }
            for (Map.Entry<String, Set<Integer>> e : lengthsByVar.entrySet())
            {
                if (e.getValue().size() > 1)
                {
                    mismatched.add(e.getKey());
                }
            }
        }
        return ListFunctionSupport.broadcast(SPLIT_SIBLING_LENGTH_MISMATCH, mismatched);
    }


    /**
     * {@code duplicate_label_variables()} — E7, the variable names whose declared label
     * ({@link DataTableColumnMeta#getLabel()}) is shared by another column of the current table (a
     * null / blank label is not a meaningful duplicate). Empty when every label is unique.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector duplicateLabelVariables(EvalRun run, List<Vector> args)
    {
        DataTableMeta meta = run.ctx().getTable().getMetaData();
        int colCount = meta.getColumnCount();
        Map<String, List<String>> namesByLabel = new LinkedHashMap<>();
        for (int c = 0; c < colCount; c++)
        {
            DataTableColumnMeta colMeta = meta.getColumn(c);
            String label = colMeta.getLabel();
            if (label == null || label.isEmpty())
            {
                continue;
            }
            namesByLabel.computeIfAbsent(label, _ -> new ArrayList<>()).add(colMeta.getName());
        }
        List<String> duplicates = new ArrayList<>();
        for (List<String> bucket : namesByLabel.values())
        {
            if (bucket.size() > 1)
            {
                duplicates.addAll(bucket);
            }
        }
        return ListFunctionSupport.broadcast(DUPLICATE_LABEL_VARIABLES, duplicates);
    }

}
