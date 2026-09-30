package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * {@code get_parent_model_column_order(name)} — the SDTM Model column order of each row's
 * <em>parent</em> domain, the one named by the row's {@code name} column ({@code RDOMAIN}: a
 * {@code SUPPAE} row's parent is {@code AE}). The one <b>per-row</b> list function of wave 4
 * ({@code PLAN-list-functions} §2.2): the answer is a {@link ComputedVector} of {@code List} cells,
 * one list instance per distinct parent value shared by every row naming it, so
 * {@code QNAM in $model_variables} checks each row against <em>its</em> parent's variables
 * ({@code ExprCompiler.boundMembership}'s per-row arm — W0's list shape, option 2, proved on a real
 * producer here).
 *
 * <p>
 * ⭐ <b>The parent column is explicit</b> (D-W4-2, the D-W3-3 / wave-1 {@code dy} precedent: a
 * default re-creates an implicit read). It is also what makes the binding per-row without new
 * machinery — the column operand carries the ROW demand {@code DomainScan} reads.
 * </p>
 *
 * <p>
 * ⭐ <b>D2 (shape census, fixed here):</b> the retired operation resolved the parent by
 * <em>member</em> name, so a split parent (LB submitted as LBCH + LBHE) resolved to nothing and the
 * rule SKIPPED silently. The parent is now the dataset of that exact name, else every member whose
 * data-driven domain matches ({@code SplitDomainResolution.membersOf}: fault-tolerant per entry,
 * sorted by member name, memoised), their model orders unioned in that order; a non-inventory
 * resolver keeps the direct resolve.
 * </p>
 *
 * <p>
 * The arms, carried from the operation: no provider / a Library that could not be consulted ⇒ SKIP;
 * a parent the Library cannot serve at all ({@code null}: no product, degraded) ⇒ SKIP; no parent
 * resolving from any row — which is what an absent or all-blank {@code name} column folds to (D4) —
 * ⇒ SKIP; a row whose own parent is blank or unresolved while others resolve answers the empty list
 * (the retired {@code SET} default: {@code QNAM in []} never fires).
 * </p>
 */
public final class ParentModelColumnOrder
{

    /** The function name as authored. */
    public static final String NAME = "get_parent_model_column_order";

    private static final List<Object> NO_PARENT = List.of();

    private ParentModelColumnOrder()
    {
    }


    /**
     * The {@code EvalFunction} body: {@code args} holds the bound parent-domain column.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound {@code name} vector
     * @return the per-row lists
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, NAME);
        DatasetResolver resolver = run.ctx().getDatasetResolver();
        Vector parent = args.get(0);
        int rowCount = run.rowCount();
        Set<String> domains = new LinkedHashSet<>();
        for (int r = 0; r < rowCount; r++)
        {
            String domain = text(parent.value(r).cell());
            if (!domain.isEmpty())
            {
                domains.add(domain);
            }
        }
        Map<String, List<Object>> byDomain = new LinkedHashMap<>();
        for (String domain : domains)
        {
            Set<String> union = new LinkedHashSet<>();
            for (IDataTable parentTable : parentTables(resolver, domain, run.ctx().getRuleId()))
            {
                List<String> vars = provider.getStandardModelVariables(parentTable, resolver);
                if (vars == null)
                {
                    // No product / degraded — the whole rule SKIPs, as get_model_column_order.
                    throw ListFunctionSupport.unusable(NAME, ProviderNeed.Kind.LIBRARY,
                            "no model column order for parent domain " + domain);
                }
                union.addAll(vars);
            }
            if (!union.isEmpty())
            {
                List<Object> order = Collections.unmodifiableList(new ArrayList<>(union));
                ListValueGuard.requireNoNullElement(order, () -> NAME + "(" + domain + ")");
                byDomain.put(domain, order);
            }
        }
        if (byDomain.isEmpty())
        {
            throw ListFunctionSupport.unusable(NAME, ProviderNeed.Kind.LIBRARY,
                    "no parent domain resolved to a model column order");
        }
        return new ComputedVector(rowCount, DataValueType.STRING,
                row -> byDomain.getOrDefault(text(parent.value(row).cell()), NO_PARENT));
    }


    /**
     * The datasets a parent domain resolves to: the dataset of that exact name (the retired
     * operation's only reach), else — with an inventory — every split-family member whose
     * data-driven domain is {@code domain} (D2), in upper-cased member-name order.
     *
     * <p>
     * ⚠ The member walk is {@link SplitDomainResolution#membersOf}, never
     * {@code WithInventory.tablesForDomain}: the latter resolves every inventory entry unguarded,
     * so one unopenable <em>unrelated</em> dataset turned every {@code SUPP--} rule using this
     * function into an ERROR (W4 M1), and it iterates in the inventory's order, which for the
     * production resolver is a {@code Map.copyOf} key set that differs per JVM (W4 L2). The shared
     * walk skips an unreadable entry, sorts, and is memoised per run.
     * </p>
     */
    private static List<IDataTable> parentTables(DatasetResolver resolver, String domain,
            @Nullable String ruleId)
    {
        IDataTable direct = resolver.resolve(domain);
        if (direct != null)
        {
            return List.of(direct);
        }
        if (resolver instanceof DatasetResolver.WithInventory inventory)
        {
            return SplitDomainResolution.membersOf(inventory, domain, ruleId);
        }
        return List.of();
    }


    private static String text(IDataValue cell)
    {
        if (cell.isMissingOrInvalid())
        {
            return "";
        }
        String value = cell.getValueAsString();
        return value == null ? "" : value;
    }

}
