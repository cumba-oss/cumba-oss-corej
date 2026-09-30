package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.SubclassScope;
import net.cumba.corej.core.model.VariableRequirement;
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
 * the anchor's verdict differ from a per-dataset one: {@code LibraryValidator} keeps a rule outside
 * the run's use case out of the anchor pass, and {@code DatasetRuleResolver} reports it
 * {@code SKIPPED} per dataset.
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
     * which would resolve against the dataset under evaluation) and the call is ungrouped.
     * {@code read_value} is one of them (combined review XCUT L2): ungrouped, its answer is one
     * value of the pinned dataset broadcast to every row (R2), exactly as an ungrouped
     * {@code max_date} is.
     */
    private static final Set<String> DOMAIN_PINNED_CALLS = Set.of(RecordCount.NAME, Distinct.NAME,
            GroupedAggregate.MAX, GroupedAggregate.MAX_DATE, GroupedAggregate.MIN_DATE,
            ReadValue.NAME);

    /**
     * Registry functions whose result is a study-level fact — they interrogate the study inventory,
     * never the dataset under evaluation. {@code minus} composes other lists, so its operands are
     * checked recursively.
     */
    private static final Set<String> STUDY_LEVEL_FUNCTIONS = Set.of("dataset_names",
            "define_dataset_names", "study_domains", "standard_domains", "minus");

    /**
     * Whether {@code function} names a registry function whose result is a study-level fact — it
     * interrogates the study inventory rather than the dataset under evaluation. Shared with
     * {@link RuleClassifier} so the {@code Sensitivity} derivation and the anchor-eligibility
     * decision cannot drift apart.
     *
     * @param function
     *            the call's function name
     * @return {@code true} when the function is study-level
     */
    public static boolean isStudyLevelFunction(String function)
    {
        return STUDY_LEVEL_FUNCTIONS.contains(function);
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
     * one ({@code LibraryValidator} applies it before the anchor pass).
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
        // lives under Scope at all (plans/done/PLAN-scope-requirements-split.md phase 5), which is
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
        // Output_Variables can name `$`-operations that are executed and rendered into the
        // finding. One of those reading the dataset under evaluation would silently emit an empty
        // or wrong value on the anchor, so they gate eligibility too.
        return (precondition != null && readsPrimaryDataset(precondition, rule))
                || outputVariablesReadPrimaryDataset(rule);
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
        // under evaluation), so it very much reads that dataset. It is study-safe only as the
        // argument of a presence call,
        // where it is a pure metadata question; that case is decided in callReadsPrimaryDataset
        // before the operand walk ever sees the ref.
        case DOTTED_REF -> true;
        // The join-match flag is a per-row verdict over the current row of the dataset under
        // evaluation (spec §3.3) — a row read, exactly like a dotted ref in value position.
        case MATCHED_FLAG -> true;
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
        // PLAN-binding-expressions R27: a COMPILED binding reads the primary dataset exactly when
        // its expression does — walked like the Check, never assumed the worst.
        // Runbook W8: every binding is a compiled binding; a `$`-name no binding defines is the
        // dangling-reference load error's, and is assumed to read the primary here.
        net.cumba.corej.core.model.CompiledBinding compiled = rule.compiledBinding(opRef);
        return compiled == null || readsPrimaryDataset(compiled.expression(), rule);
    }


    private static boolean callReadsPrimaryDataset(Expr.Call call, Rule rule)
    {
        String name = call.name();
        if (STUDY_SAFE_CALLS.contains(name))
        {
            return false;
        }
        if (isStudyLevelFunction(name))
        {
            // Wave 4 (PLAN-list-functions D-W4-6): the study-level list functions
            // (dataset_names, define_dataset_names, study_domains, standard_domains) interrogate
            // the study inventory or the Define, never the primary table; minus is study-safe
            // exactly when every operand is — the operation-era recursive rule, as an operand walk.
            return anyReadsPrimaryDataset(call.args(), rule)
                    || anyReadsPrimaryDataset(List.copyOf(call.kwargs().values()), rule);
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
            if (!isPinnedDomain(domainName(call.kwargs().get(GroupedAggregate.DOMAIN_PARAMETER))))
            {
                return true;
            }
            // A GROUPED call resolves per primary row — its group key is read on the dataset under
            // evaluation too (the broadcast, runbook R2) — so it is dataset-dependent whatever its
            // domain says: the retired operation arm's rule, carried.
            Expr group = call.kwargs().get("group");
            if (group != null
                    && !(group instanceof Expr.Lit groupLit && groupLit.kind() == Expr.LitKind.LIST
                            && groupLit.value() instanceof List<?> items && items.isEmpty()))
            {
                return true;
            }
            // The domain is pinned: a BARE column argument names a column of THAT dataset (owner
            // D13 Q4 / D10 — the strict readers refuse a dotted or `--` reference under domain=),
            // exactly as the retired operation's target and filter keys did, so it is no read of
            // the dataset under evaluation (runbook W7, PLAN-distinct-function D-W7-12). The same
            // holds INSIDE filter=(…), a boolean over the pinned dataset's own columns
            // (GroupedAggregate.readFilter, ReadValue — combined review W7 MEDIUM-2: the filter
            // used to be walked as a primary read). What can still reach the primary is a `--`
            // name, a `$` reference, a dotted reference or a nested call, so those are walked.
            return anyPinnedArgumentReadsPrimaryDataset(call.args(), rule)
                    || anyPinnedArgumentReadsPrimaryDataset(List.copyOf(call.kwargs().values()),
                            rule);
        }
        // Anything outside the allowlist is assumed to read the primary dataset.
        return true;
    }


    /**
     * {@link #anyReadsPrimaryDataset} for the arguments of a domain-pinned call, with
     * pinned-dataset semantics: a <b>bare</b> column reference is the pinned dataset's column and
     * reads nothing of the dataset under evaluation — as the target, as a {@code domain=DS}
     * reference (D10) and anywhere inside {@code filter=(…)}. Everything else is walked as a
     * primary read: a {@code --} name (it resolves against the dataset under evaluation — the
     * readers refuse it under {@code domain=} at load, so this is defence in depth, combined review
     * W7 MEDIUM-1), a {@code $} or dotted reference, and a nested call.
     */
    private static boolean anyPinnedArgumentReadsPrimaryDataset(List<Expr> arguments, Rule rule)
    {
        for (Expr argument : arguments)
        {
            if (pinnedArgumentReadsPrimaryDataset(argument, rule))
            {
                return true;
            }
        }
        return false;
    }


    private static boolean pinnedArgumentReadsPrimaryDataset(Expr argument, Rule rule)
    {
        return switch (argument)
        {
        case Expr.Ref ref -> ref.kind() != OperandKind.COLUMN && readsPrimaryDataset(ref, rule);
        case Expr.Lit lit -> lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items
                && items.stream().anyMatch(item -> item instanceof Expr e
                        && pinnedArgumentReadsPrimaryDataset(e, rule));
        case Expr.Not n -> pinnedArgumentReadsPrimaryDataset(n.inner(), rule);
        case Expr.And a -> anyPinnedArgumentReadsPrimaryDataset(a.parts(), rule);
        case Expr.Or o -> anyPinnedArgumentReadsPrimaryDataset(o.parts(), rule);
        case Expr.Binary b -> pinnedArgumentReadsPrimaryDataset(b.left(), rule)
                || pinnedArgumentReadsPrimaryDataset(b.right(), rule);
        case Expr.Call c -> callReadsPrimaryDataset(c, rule);
        };
    }


    /**
     * The dataset a {@code domain=} argument names, in either spelling the readers accept (D10,
     * {@code GroupedAggregate.readDataset}): the bare reference {@code DS} or the quoted
     * {@code "DS"}; {@code null} for an absent or any other argument.
     */
    private static @Nullable String domainName(@Nullable Expr domain)
    {
        return switch (domain)
        {
        case null -> null;
        case Expr.Ref ref when ref.kind() == OperandKind.COLUMN -> ref.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> String.valueOf(lit.value());
        default -> null;
        };
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
     * All four {@code Requirements.Variables} facets — {@code All}, {@code Any}, {@code None}
     * <b>and</b> {@code All_Or_None} — are dataset restrictions: each can keep the rule off a
     * dataset.
     */
    private static boolean hasEntries(@Nullable VariableRequirement req)
    {
        return req != null && (notEmpty(req.getAll()) || notEmpty(req.anyUnion())
                || notEmpty(req.getNone()) || notEmpty(req.allOrNoneUnion()));
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
