package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.metadata.CdiscDomainResolver;

/**
 * The three sponsor-Define-XML-backed dataset-level list functions of runbook wave 4
 * ({@code PLAN-list-functions} §2.2): what the Define declares — dataset names, a domain's variable
 * names, a domain's key variables — read through the run's define provider
 * ({@code DefineXmlMetadataProvider} / {@code OdmDefineXMLProvider}). {@code RuleRunner} SKIPs a
 * rule binding one of these before any row is read when no Define-XML is supplied (the descriptor's
 * {@code DEFINE} capability); {@link ListFunctionSupport#define} is the second layer.
 */
public final class DefineLists
{

    /** {@code define_variable_names()}. */
    public static final String DEFINE_VARIABLE_NAMES = "define_variable_names";

    /** {@code define_dataset_names()}. */
    public static final String DEFINE_DATASET_NAMES = "define_dataset_names";

    /** {@code define_key_variables()}. */
    public static final String DEFINE_KEY_VARIABLES = "define_key_variables";

    private DefineLists()
    {
    }


    /**
     * {@code define_variable_names()} — the variable names the Define declares for the current
     * domain (its {@code ItemGroupDef}'s {@code ItemRef}s resolved to {@code ItemDef} names).
     * Diffed against the data's variables (FDA-SD0054); an empty declaration is an empty list.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector defineVariableNames(EvalRun run, List<Vector> args)
    {
        MetadataProvider define = ListFunctionSupport.define(run, DEFINE_VARIABLE_NAMES);
        String domain = CdiscDomainResolver.cdiscDomainOf(run.ctx().getTable());
        return ListFunctionSupport.broadcast(DEFINE_VARIABLE_NAMES, define.getColumnOrder(domain));
    }


    /**
     * {@code define_dataset_names()} — every dataset the Define declares for the study (the
     * {@code MetaDataVersion}'s {@code ItemGroupDef} names), upper-cased (FU-2, so the set compares
     * of SD0061 / SD1063 are case-invariant). Not domain-scoped.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector defineDatasetNames(EvalRun run, List<Vector> args)
    {
        MetadataProvider define = ListFunctionSupport.define(run, DEFINE_DATASET_NAMES);
        return ListFunctionSupport.broadcast(DEFINE_DATASET_NAMES,
                upperCaseAll(define.getDatasetNames()));
    }


    /**
     * {@code define_key_variables()} — the Define's key variables of the current domain, ordered by
     * {@code KeySequence}. An empty key set SKIPs (PMDA-SD1152, M4): it would collapse an
     * {@code is_not_unique_set} key to the constant anchor and flag every record.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list, never empty
     */
    public static Vector defineKeyVariables(EvalRun run, List<Vector> args)
    {
        MetadataProvider define = ListFunctionSupport.define(run, DEFINE_KEY_VARIABLES);
        String domain = CdiscDomainResolver.cdiscDomainOf(run.ctx().getTable());
        List<String> keys = define.getKeyVariables(domain);
        if (keys == null || keys.isEmpty())
        {
            throw ListFunctionSupport.unusable(DEFINE_KEY_VARIABLES, ProviderNeed.Kind.DEFINE,
                    "the Define-XML declares no key variables for " + domain);
        }
        return ListFunctionSupport.broadcast(DEFINE_KEY_VARIABLES, keys);
    }


    /**
     * Upper-cases every element ({@code Locale.ROOT}), preserving order. ⚠ A {@code null} element
     * is not a value and is not folded: it is handed on so that the {@link ListValueGuard} at the
     * broadcast — the birth site of the list — reports it naming the function (NNL §1), rather than
     * an anonymous {@code NullPointerException} here. The producers are null-free
     * ({@code OdmDefineXMLProvider.getDatasetNames} skips a nameless {@code ItemGroupDef}).
     */
    static List<String> upperCaseAll(Collection<String> names)
    {
        List<String> out = new ArrayList<>(names.size());
        for (String n : names)
        {
            out.add(n == null ? null : n.toUpperCase(Locale.ROOT));
        }
        return out;
    }

}
