package net.cumba.corej.core.exec;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Runbook W7 ({@code PLAN-distinct-function} D-W7-6) — the registry function
 * {@code referenced_dataset_variables(name)}: per row, the <b>list of variable names</b> of the
 * dataset the row's {@code name} value names ({@code referenced_dataset_variables(RDOMAIN)} on a
 * SUPP-- / CO / RELREC record), so a Check tests the row's {@code IDVAR} against <em>its</em>
 * referenced dataset's columns — never a flat union over every referenced domain (Fix #11). Backs
 * {@code CDISC-CG0370} and {@code FDA-SD0075} / {@code PMDA-SD0075}.
 *
 * <p>
 * It replaces the retired {@code distinct(IDVAR, value_is_reference=true)}, which never read its
 * target ({@code IDVAR}) and read {@code RDOMAIN} implicitly (the audit's W7 row; P-Q8's second
 * silent drop): the column is <b>explicit and required</b> now, as {@link ReferencedDomainClass}
 * made it in wave 3. The answer per row is carried verbatim: the union of the column names of every
 * dataset member whose data-driven domain is the value (a split family — J7,
 * {@link DatasetResolver.WithInventory#tablesForDomain}), or the one dataset of that name for a
 * resolver without an inventory; a missing or blank cell, or a value no dataset carries, answers
 * the <b>empty list</b> (the retired grouped result's declared SET codomain — a list-valued
 * function has no missing, W4's precedent), so {@code IDVAR not in $vars} fires and
 * {@code IDVAR in $vars} does not. One inventory question per distinct value, not per row.
 * </p>
 *
 * <p>
 * The answer is W0's per-row list shape — an untyped {@link ComputedVector} whose cell is the
 * {@code List} — so {@code X not in $vars} reads it per row ({@code ExprCompiler.boundMembership})
 * and {@code upper($vars)} folds it element-wise ({@code BuiltinFunctions.caseFold}), exactly as
 * the retired per-row result object was read. No provider capability: the study's own datasets are
 * read through the run's resolver, never the CDISC Library.
 * </p>
 */
public final class ReferencedDatasetVariables
{

    /** The function name as authored. */
    public static final String NAME = "referenced_dataset_variables";

    /** The empty answer — one instance, so a run of such rows folds to one set. */
    private static final List<Object> EMPTY = List.of();

    private ReferencedDatasetVariables()
    {
    }


    /**
     * The {@code EvalFunction} body: {@code args} holds the bound {@code name} vector.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the per-row variable-name list
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        EvaluationContext ctx = run.ctx();
        DatasetResolver resolver = ctx.getDatasetResolver();
        Vector domain = args.get(0);
        int rowCount = run.rowCount();
        Map<String, List<Object>> byDomain = new HashMap<>();
        for (int row = 0; row < rowCount; row++)
        {
            String text = domainText(domain.value(row).cell());
            if (text != null)
            {
                byDomain.computeIfAbsent(text, d -> columnsOf(resolver, d));
            }
        }
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            String text = domainText(domain.value(row).cell());
            return text == null ? EMPTY : byDomain.getOrDefault(text, EMPTY);
        });
    }


    /** The dataset name a cell carries, or {@code null} for a missing or blank cell. */
    private static @Nullable String domainText(IDataValue cell)
    {
        if (cell.isMissingOrInvalid())
        {
            return null;
        }
        String text = cell.getValueAsString();
        return text == null || text.isEmpty() ? null : text;
    }


    /**
     * The column names of every dataset {@code domain} names, in declaration order, de-duplicated
     * across a split family's members; the empty list when no dataset carries the name.
     */
    private static List<Object> columnsOf(@Nullable DatasetResolver resolver, String domain)
    {
        if (resolver == null)
        {
            return EMPTY;
        }
        // J7: the value is a DOMAIN (e.g. "LB"), but a resolver keyed by member name (lbch / lbhe /
        // lbur for a split LB) answers null for it — a dataset named exactly like the domain is
        // used alone; otherwise the members whose data-driven domain matches, through the
        // fault-tolerant sorted walk (combined review of runbook W2–W8, W4 M1's twin: an
        // unopenable UNRELATED study dataset must not ERROR this read — tablesForDomain resolved
        // every inventory entry unguarded).
        IDataTable exact = resolver.resolve(domain);
        Iterable<IDataTable> tables;
        if (exact != null)
        {
            tables = List.of(exact);
        }
        else if (resolver instanceof DatasetResolver.WithInventory inventory)
        {
            tables = SplitDomainResolution.membersOf(inventory, domain, null);
        }
        else
        {
            tables = List.of();
        }
        Set<Object> columns = new LinkedHashSet<>();
        for (IDataTable table : tables)
        {
            DataTableMeta meta = table.getMetaData();
            for (int c = 0; c < meta.getColumnCount(); c++)
            {
                columns.add(meta.getColumn(c).getName());
            }
        }
        if (columns.isEmpty())
        {
            return EMPTY;
        }
        List<Object> names = List.copyOf(columns);
        // Register NNL §1 — the birth site of a per-row list value.
        ListValueGuard.requireNoNullElement(names, () -> NAME + "(" + domain + ")");
        return names;
    }
}
