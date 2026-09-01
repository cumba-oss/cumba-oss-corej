package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.ClassScope;
import net.cumba.cdisc.core.model.DataStructureScope;
import net.cumba.cdisc.core.model.DatasetScope;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.model.SubclassScope;
import net.cumba.cdisc.core.model.VariableRequirement;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether a {@code Sensitivity: "Study"} rule can be executed <em>once</em> against a
 * synthetic study anchor instead of once per dataset.
 *
 * <p>
 * A study-level finding describes the submission, not a dataset — "the DM dataset is missing" has
 * no dataset to attach it to. Such a rule need not be evaluated against every dataset in turn: its
 * verdict is the same each time, and running it N times only to collapse the N identical results
 * back into one is wasted work that also makes the rule invisible when a study has no analysable
 * datasets at all.
 * </p>
 *
 * <p>
 * A rule is <b>anchor-eligible</b> only when all three hold:
 * </p>
 * <ol>
 * <li>its {@code Sensitivity} is {@code Study};</li>
 * <li>its {@code Check}, its <em>raised</em> {@code Precondition} and its {@code Output_Variables}
 * operations read nothing about the dataset under evaluation (see
 * {@link #readsPrimaryDataset});</li>
 * <li>its {@code Scope} is unrestricted: {@code Domains.Include: [ALL]} (or no domain facet) with
 * no {@code Classes} / {@code Data_Structures} / {@code Subclasses} / {@code Variables} facet and
 * no {@code Domains.Exclude}.</li>
 * </ol>
 *
 * <p>
 * Criterion 3 is an authoring invariant rather than a runtime accommodation: under the attachment
 * principle a rule that declares a dataset scope executes on that dataset and its finding belongs
 * there, so it is not a study rule at all. A {@code Use_Case} facet is deliberately allowed —
 * {@link ScopeMatcher#matchesUseCase} filters per <em>run</em>, not per dataset, so it cannot make
 * the anchor's verdict differ from a per-dataset one.
 * </p>
 *
 * <p>
 * A study rule that fails criterion 2 stays on the per-dataset path and is collapsed afterwards, so
 * the classifier is a fast path and never a correctness gate.
 * </p>
 */
public final class StudyRuleClassifier
{

    private StudyRuleClassifier()
    {
    }

    /** The scope token meaning "every dataset". */
    private static final String ALL = "ALL";

    /**
     * Calls whose result cannot depend on the dataset under evaluation — they interrogate the study
     * inventory, not the primary table.
     */
    private static final Set<String> STUDY_SAFE_CALLS = Set.of("ds_exists", "ds_not_exists");

    /**
     * Calls that read a dataset but accept an explicit {@code domain=} naming which one. They are
     * study-safe exactly when that domain is pinned to a concrete name (no {@code --} wildcard,
     * which would resolve against the dataset under evaluation).
     */
    private static final Set<String> DOMAIN_PINNED_CALLS = Set.of("record_count",
            "variable_value_count", "variable_count", "distinct", "max", "min", "max_date",
            "min_date");

    /**
     * Operators whose result is a study-level fact. {@code minus} composes other operations, so its
     * operands are checked recursively.
     */
    private static final Set<String> STUDY_LEVEL_OPERATORS = Set.of("dataset_names",
            "define_dataset_names", "study_domains", "standard_domains", "minus");

    /**
     * Operators that read a dataset but are study-safe when pinned to an explicit {@code domain}.
     * An <em>allowlist</em>, mirroring {@link #DOMAIN_PINNED_CALLS}: the operator vocabulary is
     * large and most of it ({@code dy}, {@code date_diff_days}, {@code is_last_in_group},
     * {@code extract_metadata}, the dictionary validators, …) resolves against the record under
     * evaluation, so anything unrecognised must be assumed to read the primary dataset.
     */
    private static final Set<String> DOMAIN_PINNED_OPERATORS = Set.of("record_count",
            "variable_value_count", "variable_count", "distinct", "max", "min", "max_date",
            "min_date");

    /**
     * Whether {@code operator} names an operation whose result is a study-level fact — it
     * interrogates the study inventory rather than the dataset under evaluation. Shared with
     * {@link RuleClassifier} so the {@code Sensitivity} derivation and the anchor-eligibility
     * decision cannot drift apart.
     *
     * @param operator
     *            the operation's {@code operator} value
     * @return {@code true} when the operation is study-level
     */
    public static boolean isStudyLevelOperator(String operator)
    {
        return STUDY_LEVEL_OPERATORS.contains(operator);
    }


    /**
     * Whether this rule may be executed once against the study anchor.
     *
     * @param rule
     *            the rule to classify
     * @return {@code true} when all three eligibility criteria hold
     */
    public static boolean isAnchorEligible(Rule rule)
    {
        return rule.getSensitivity() == Sensitivity.STUDY && hasUnrestrictedScope(rule)
                && !readsPrimaryDataset(rule);
    }


    /**
     * Whether the rule's {@code Scope} places no dataset restriction on it (criterion 3).
     *
     * <p>
     * {@code Use_Case} is intentionally not consulted: it is a per-run filter, not a per-dataset
     * one.
     * </p>
     *
     * @param rule
     *            the rule to inspect
     * @return {@code true} when no scope facet restricts which datasets the rule runs against
     */
    public static boolean hasUnrestrictedScope(Rule rule)
    {
        // ⚠⚠ The variable requirement is read FIRST and through effectiveVariableRequirement():
        // this is a NEGATIVE predicate feeding a LOAD ERROR
        // (RulePackageLoader.checkStudySensitivityScope), so a facet it stops seeing makes rules
        // PASS a gate they should fail — nothing goes red, the weakening is silent. It no longer
        // lives under Scope at all (plans/PLAN-scope-requirements-split.md phase 5), which is
        // exactly why it cannot be folded into the Scope walk below.
        if (hasEntries(rule.effectiveVariableRequirement()))
        {
            return false;
        }
        Scope scope = rule.getScope();
        if (scope == null)
        {
            return true;
        }
        if (hasEntries(scope.getClasses()) || hasEntries(scope.getDataStructures())
                || hasEntries(scope.getSubclasses()) || hasEntries(scope.getDatasets()))
        {
            return false;
        }
        DomainScope domains = scope.getDomains();
        if (domains == null)
        {
            return true;
        }
        if (domains.getExclude() != null && !domains.getExclude().isEmpty())
        {
            return false;
        }
        List<String> include = domains.getInclude();
        if (include == null || include.isEmpty())
        {
            return true;
        }
        return include.size() == 1 && ALL.equalsIgnoreCase(include.get(0).trim());
    }


    /**
     * Whether anything the rule evaluates reads the dataset under evaluation (criterion 2): its
     * {@code Check}, its {@code Precondition}, and the {@code $}-operations named by
     * {@code Outcome.Output_Variables}. A rule with no compiled {@code Check} expression is treated
     * as dataset-reading — the conservative answer, since its behaviour cannot be inspected.
     *
     * <p>
     * Only a fold-equivalent (broadcast) {@code Precondition} is compiled into
     * {@code preconditionExpr}; the engine evaluates nothing else ({@code RuleRunner} guards on
     * that field), so a row-level {@code Precondition} is a runtime no-op and is deliberately not
     * consulted here.
     * </p>
     *
     * @param rule
     *            the rule to inspect
     * @return {@code true} when the rule's verdict could differ per dataset
     */
    public static boolean readsPrimaryDataset(Rule rule)
    {
        Expr check = rule.getCheckExpr();
        if (check == null)
        {
            return true;
        }
        if (readsPrimaryDataset(check, rule))
        {
            return true;
        }
        Expr precondition = rule.getPreconditionExpr();
        if (precondition != null && readsPrimaryDataset(precondition, rule))
        {
            return true;
        }
        // Output_Variables can name `$`-operations that are executed and rendered into the
        // finding. One of those reading the dataset under evaluation would silently emit an empty
        // or wrong value on the anchor, so they gate eligibility too.
        return outputVariablesReadPrimaryDataset(rule);
    }


    private static boolean outputVariablesReadPrimaryDataset(Rule rule)
    {
        // EC-37: the effective list — a derived `$op` renders into the finding exactly like an
        // authored one, so it gates study-anchor eligibility the same way.
        List<String> outputs = rule.effectiveOutputVariablesOrAuthored();
        for (String output : outputs)
        {
            if (output != null && output.startsWith("$")
                    && operationReadsPrimaryDataset(output, rule, new ArrayList<>()))
            {
                return true;
            }
        }
        return false;
    }


    private static boolean readsPrimaryDataset(Expr expr, Rule rule)
    {
        return switch (expr)
        {
        // A list literal's elements come from the operand parser, so a list can legitimately hold
        // column / operation refs (e.g. `"Y" in [DTHFL, "N"]`) — walk them.
        case Expr.Lit lit -> lit.value() instanceof List<?> elements
                && anyElementReadsPrimaryDataset(elements, rule);
        case Expr.Ref ref -> refReadsPrimaryDataset(ref, rule);
        case Expr.Not n -> readsPrimaryDataset(n.inner(), rule);
        case Expr.And a -> anyReadsPrimaryDataset(a.parts(), rule);
        case Expr.Or o -> anyReadsPrimaryDataset(o.parts(), rule);
        case Expr.Binary b -> readsPrimaryDataset(b.left(), rule)
                || readsPrimaryDataset(b.right(), rule);
        case Expr.Call c -> callReadsPrimaryDataset(c, rule);
        };
    }


    private static boolean anyReadsPrimaryDataset(List<Expr> parts, Rule rule)
    {
        for (Expr p : parts)
        {
            if (readsPrimaryDataset(p, rule))
            {
                return true;
            }
        }
        return false;
    }


    private static boolean refReadsPrimaryDataset(Expr.Ref ref, Rule rule)
    {
        return switch (ref.kind())
        {
        // A bare column, or a wildcard column, names a column of the dataset under evaluation.
        case COLUMN, WILDCARD_COLUMN -> true;
        // Every variable_/dataset_/library_/define_ fact is relative to the dataset under
        // evaluation.
        case BUILTIN -> true;
        // A dotted ref names its dataset, but in VALUE position it is a per-primary-row join
        // lookup (the joined-value lookup keys off the current row of the dataset
        // under evaluation), so it very much reads that dataset — BroadcastFold.readsRowData
        // classifies it the same way. It is study-safe only as the argument of a presence call,
        // where it is a pure metadata question; that case is decided in callReadsPrimaryDataset
        // before the operand walk ever sees the ref.
        case DOTTED_REF -> true;
        case OPERATION_REF -> operationReadsPrimaryDataset(ref.name(), rule, new ArrayList<>());
        };
    }


    /**
     * @param seen
     *            operation ids already visited, so a cyclic {@code minus} chain cannot recurse
     *            forever
     */
    private static boolean operationReadsPrimaryDataset(String opRef, Rule rule, List<String> seen)
    {
        if (seen.contains(opRef))
        {
            return true;
        }
        seen.add(opRef);
        Operation op = findOperation(opRef, rule);
        if (op == null)
        {
            // Unresolvable reference — assume the worst.
            return true;
        }
        String operator = op.getOperator();
        if (operator != null && STUDY_LEVEL_OPERATORS.contains(operator))
        {
            if (!"minus".equals(operator))
            {
                return false;
            }
            // `minus` is a set difference over two other operands; both must be study-level. A
            // non-`$` operand names a column of the dataset under evaluation.
            for (String operand : new String[]
            {
                    op.getName(), op.getSubtract()
            })
            {
                if (operand == null || operand.isBlank())
                {
                    continue;
                }
                if (!operand.startsWith("$") || operationReadsPrimaryDataset(operand, rule, seen))
                {
                    return true;
                }
            }
            return false;
        }
        // A grouped aggregate resolves per primary row: OperationExecutor routes it to the
        // *Grouped variants and GroupedResult.getForRow reads the evaluation table's group
        // columns, so it is dataset-dependent whatever its domain says.
        if (op.getGroup() != null && !op.getGroup().isEmpty())
        {
            return true;
        }
        // `--` is resolved against the dataset under evaluation in several operation fields, not
        // just `domain`. The anchor pass runs with domainPrefix == null, so an unresolved token
        // would reach the executor as a non-existent column and silently yield null.
        if (hasUnresolvedWildcard(op))
        {
            return true;
        }
        return operator == null || !DOMAIN_PINNED_OPERATORS.contains(operator)
                || !isPinnedDomain(op.getDomain());
    }


    /** The rule's {@code Operations} entry with this {@code $}-id, or {@code null}. */
    private static @Nullable Operation findOperation(String opRef, Rule rule)
    {
        List<Operation> operations = rule.getOperations();
        if (operations == null)
        {
            return null;
        }
        for (Operation op : operations)
        {
            if (opRef.equals(op.getId()))
            {
                return op;
            }
        }
        return null;
    }


    private static boolean callReadsPrimaryDataset(Expr.Call call, Rule rule)
    {
        String name = call.name();
        if (STUDY_SAFE_CALLS.contains(name))
        {
            return false;
        }
        if ("var_exists".equals(name) || "var_not_exists".equals(name))
        {
            // Study-safe only when the argument names its dataset (DM.ARM); a bare column asks
            // about the dataset under evaluation.
            return !(call.args().size() == 1 && call.args().get(0) instanceof Expr.Ref ref
                    && ref.kind() == OperandKind.DOTTED_REF);
        }
        if (DOMAIN_PINNED_CALLS.contains(name))
        {
            Expr domain = call.kwargs().get("domain");
            if (!(domain instanceof Expr.Lit lit) || !isPinnedDomain(String.valueOf(lit.value())))
            {
                return true;
            }
            // The domain is pinned, but a filter= (or any other) argument may still reach into the
            // dataset under evaluation.
            return anyReadsPrimaryDataset(call.args(), rule)
                    || anyReadsPrimaryDataset(List.copyOf(call.kwargs().values()), rule);
        }
        // Anything outside the allowlist is assumed to read the primary dataset.
        return true;
    }


    private static boolean anyElementReadsPrimaryDataset(List<?> elements, Rule rule)
    {
        for (Object element : elements)
        {
            if (element instanceof Expr e && readsPrimaryDataset(e, rule))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Whether any operation field carries an unresolved {@code --} wildcard. Mirrors the field set
     * {@code OperationExecutor.resolvePrefixes} rewrites.
     */
    private static boolean hasUnresolvedWildcard(Operation op)
    {
        return containsWildcard(op.getName()) || containsWildcard(op.getSubtract())
                || containsWildcard(op.getExternalDictionaryTermVariable())
                || anyContainsWildcard(op.getNames()) || anyContainsWildcard(op.getGroup());
    }


    private static boolean containsWildcard(@Nullable String value)
    {
        return value != null && value.contains("--");
    }


    private static boolean anyContainsWildcard(@Nullable List<String> entries)
    {
        if (entries == null)
        {
            return false;
        }
        for (String e : entries)
        {
            if (containsWildcard(e))
            {
                return true;
            }
        }
        return false;
    }


    /** A domain is pinned when it names a concrete dataset — no wildcard, no blank. */
    private static boolean isPinnedDomain(@Nullable String domain)
    {
        return domain != null && !domain.isBlank() && !domain.contains("--");
    }


    private static boolean hasEntries(@Nullable ClassScope scope)
    {
        return scope != null && (notEmpty(scope.getInclude()) || notEmpty(scope.getExclude()));
    }


    /**
     * All three {@code Requirements.Variables} facets — {@code All}, {@code Any} <b>and</b>
     * {@code None} — are dataset restrictions: each can keep the rule off a dataset.
     */
    private static boolean hasEntries(@Nullable VariableRequirement req)
    {
        return req != null
                && (notEmpty(req.getAll()) || notEmpty(req.getAny()) || notEmpty(req.getNone()));
    }


    private static boolean hasEntries(@Nullable DatasetScope scope)
    {
        return scope != null && (notEmpty(scope.getInclude()) || notEmpty(scope.getExclude()));
    }


    private static boolean hasEntries(@Nullable DataStructureScope scope)
    {
        return scope != null && (notEmpty(scope.getInclude()) || notEmpty(scope.getExclude()));
    }


    private static boolean hasEntries(@Nullable SubclassScope scope)
    {
        return scope != null && (notEmpty(scope.getInclude()) || notEmpty(scope.getExclude()));
    }


    private static boolean notEmpty(@Nullable List<String> entries)
    {
        return entries != null && !entries.isEmpty();
    }
}
