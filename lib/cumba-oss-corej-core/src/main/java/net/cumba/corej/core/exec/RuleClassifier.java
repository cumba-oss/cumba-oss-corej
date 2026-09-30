package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.MetadataOperandMapping;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.MetadataAttribute;
import net.cumba.corej.core.expr.eval.MetadataLevel;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import org.jspecify.annotations.Nullable;

/**
 * Derives a rule's {@code Rule_Type} and {@code Sensitivity} from the rest of the rule body.
 *
 * <p>
 * See {@code plans/done/PLAN-derive-rule-type-sensitivity.md} — &sect;4.3 for the {@code Rule_Type}
 * cascade, &sect;4.4 for {@code Sensitivity}, &sect;4.9 for the {@code $}-only operation table —
 * and {@code plans/done/PLAN-classifier-redesign.md} for the grounding: the classifier reads the
 * native {@link Expr} every corpus form raises to ({@link #toExprOrNull}), plus an <b>id-free
 * call-usage view</b> collected from the registry-function calls the walk meets — inline in the
 * Check or read through a compiled binding — so the same rule derives identically as a
 * {@code rules-src} leaf and a {@code rules/} inlined expression. The derivation mirrors decisions
 * the engine already makes at run time, so a derived value cannot disagree with how the rule will
 * actually be evaluated.
 * </p>
 *
 * <p>
 * <b>Two-pass ordering (&sect;4.2, revised by &sect;5d).</b> The walk converts with a {@code null}
 * {@code Rule_Type} — total and non-circular since the explicit {@code ds_exists} /
 * {@code var_exists} conversion (see {@link #toExprOrNull}). {@link #deriveSensitivity} still
 * accepts the already-derived type so a generic {@code exists} is read as dataset presence on a
 * Domain Presence Check and as column presence elsewhere.
 * </p>
 */
public final class RuleClassifier
{

    private RuleClassifier()
    {
    }

    /** How much the derivation trusts a result. */
    public enum Confidence
    {

        /** The rule body determines the value; safe to apply silently. */
        CERTAIN,

        /** Determined by a documented heuristic; applied, and surfaced by the lint. */
        LIKELY,

        /** No basis in the rule body; never applied — the field must be authored. */
        NONE

    }


    /**
     * A derived value together with the reason, for the lint report and the rule editor.
     *
     * @param <T>
     *            the derived field's type
     * @param value
     *            the derived value, or {@code null} when {@link Confidence#NONE}
     * @param confidence
     *            how far the result can be trusted
     * @param rationale
     *            a short human-readable reason, shown in reports and the editor
     */
    public record Derived<T>(@Nullable T value, Confidence confidence, String rationale)
    {
    }

    // ------------------------------------------------------------------
    // Operand classes (§4.3) — what a Check operand tells us about the rule
    // ------------------------------------------------------------------


    /** The operand families a Check can reference. */
    private enum OperandClass
    {
        /** {@code variable_value} — the current cell's value. */
        VALUE,
        /** {@code variable_*} other than {@code variable_value} — data variable metadata. */
        VARIABLE_META,
        /** {@code dataset_*} — data dataset metadata. */
        DATASET_META,
        /** {@code library_variable_*}. */
        LIBRARY_VARIABLE,
        /** {@code library_dataset_*}. */
        LIBRARY_DATASET,
        /** {@code define_variable_*}. */
        DEFINE_VARIABLE,
        /** {@code define_dataset_*}. */
        DEFINE_DATASET,
        /** {@code define_vlm_*} — Define-XML value-level metadata. */
        DEFINE_VLM
    }

    private static final String VARIABLE_VALUE = "variable_value";

    /**
     * Classifies an operand name into its family, or {@code null} for a plain column, a
     * {@code $}-reference, or anything unrecognised. Prefixes are tested longest-first, mirroring
     * {@code MetadataOperandMapping.PREFIXES}.
     *
     * @param name
     *            the operand text
     * @return the family, or {@code null} when the operand is not a metadata accessor
     */
    private static @Nullable OperandClass classify(@Nullable String name)
    {
        if (name == null)
        {
            return null;
        }
        if (VARIABLE_VALUE.equals(name))
        {
            return OperandClass.VALUE;
        }
        if (name.startsWith("define_vlm_"))
        {
            return OperandClass.DEFINE_VLM;
        }
        if (name.startsWith("library_variable_"))
        {
            return OperandClass.LIBRARY_VARIABLE;
        }
        if (name.startsWith("define_variable_"))
        {
            return OperandClass.DEFINE_VARIABLE;
        }
        if (name.startsWith("library_dataset_"))
        {
            return OperandClass.LIBRARY_DATASET;
        }
        if (name.startsWith("define_dataset_"))
        {
            return OperandClass.DEFINE_DATASET;
        }
        if (name.startsWith("variable_"))
        {
            return OperandClass.VARIABLE_META;
        }
        if (name.startsWith("dataset_"))
        {
            return OperandClass.DATASET_META;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Operator vocabularies
    // ------------------------------------------------------------------

    /** Explicit dataset-presence assertions — the only decidable Domain-Presence-Check signal. */
    private static final Set<String> DATASET_PRESENCE = Set.of("ds_exists", "ds_not_exists");

    /** Explicit variable-presence assertions. */
    private static final Set<String> VARIABLE_PRESENCE = Set.of("var_exists", "var_not_exists");

    /**
     * Operators whose verdict is a dataset-wide fact even though their operands look like columns.
     * Grounded in {@code ExprCompiler}: {@code shares_no_elements_with} is documented as "a
     * broadcast set-intersection verdict over {@code $}-operation lists" and
     * {@code is_not_ordered_subset_of} as "a broadcast order-preserving subsequence verdict" —
     * neither reads a row.
     *
     * <p>
     * {@code contains_all} / {@code not_contains_all} are here for the same reason: both engines
     * evaluate them over the source column's <em>distinct values</em> and broadcast one verdict.
     * Java — "flags every row when the distinct values of the source do not contain all required
     * values"; Python — {@code set(values).issubset(set(self.value[target].unique()))}, a scalar
     * fed through {@code convert_to_series}. Treating the named column as a per-record read was a
     * defect (fixed 2026-07-29).
     * </p>
     *
     * <p>
     * <b>Single source</b> with the {@link Expr}-level consumers (the runtime fold and the
     * mixed-granularity lint of {@code plans/done/PLAN-split-mixed-granularity-rules.md}
     * &sect;4.4): the membership list lives in {@link BroadcastFold#WHOLE_COLUMN_VERDICT_OPERATORS}
     * so the operator-leaf view here and the raised-expression view there cannot drift apart.
     * </p>
     * <p>
     * ⚠ <b>This view is keyed on the operator NAME alone</b>, while {@link DomainScan} and the
     * corpus mixed-granularity lint go through {@code BroadcastFold.isBroadcastColumnPredicate},
     * which also tests the argument shape. An {@code Atom} carries no {@code Expr} tree, so there
     * is nothing here to inspect. For a shape the guard excludes the three views therefore disagree
     * by construction — but no such rule can execute: {@code ExprCompiler.compileVarIsNull} throws
     * {@code unsupported} for exactly those shapes, so the disagreement is unreachable rather than
     * benign. Stated because the premise of the shared set is that these views cannot drift.
     * </p>
     */
    private static final Set<String> BROADCAST_OPERATORS = BroadcastFold.WHOLE_COLUMN_VERDICT_OPERATORS;

    /**
     * Predicates whose verdict is one dataset-wide fact about a <b>named</b> column, even though
     * the operand is spelled as an ordinary column reference — {@code var_is_null}, whose compiled
     * plan ({@code ExprCompiler.compileVarIsNull}) computes a single boolean and paints it over
     * every row.
     *
     * <p>
     * <b>Single source</b>, for the same reason as {@link #BROADCAST_OPERATORS}: the membership
     * list lives in {@link BroadcastFold#BROADCAST_COLUMN_PREDICATES} so this operator-leaf view
     * and the raised-expression views ({@code DomainScan}, the corpus mixed-granularity lint)
     * cannot drift apart. {@code var_is_null} was in none of these sets until 2026-09-11, which is
     * why {@code FDA-SD9714} / {@code PMDA-SD9714} — minted to report a dataset-wide absence
     * <em>once</em> — derived {@code Record} and emitted one finding per record.
     * </p>
     *
     * <p>
     * Keying on the operator name alone is correct here and does <em>not</em> move the cursor-form
     * rules ({@code FDA-SD1078}, {@code PMDA-SD1078}, {@code FDA-SD1149}): their other leaf,
     * {@code varname() in $…}, lowers to {@code is_contained_by(variable_name)} and yields a
     * non-dataset reason on its own, and {@link #deriveSensitivity} scans every leaf.
     * </p>
     */
    private static final Set<String> BROADCAST_COLUMN_PREDICATES = BroadcastFold.BROADCAST_COLUMN_PREDICATES;

    /**
     * Columns whose value is constant within a dataset, so a check on them yields one verdict per
     * dataset rather than per record. {@code DOMAIN} is already special-cased by
     * {@code DatasetIdentity.domainPrefix}.
     */
    private static final Set<String> DATASET_CONSTANT_COLUMNS = Set.of("DOMAIN");

    /**
     * Group keys that cannot vary within a dataset, so grouping by them yields exactly one group
     * and the "grouped result" is a single broadcast value rather than a per-record one.
     * {@code STUDYID} identifies the submission and {@code DOMAIN} the dataset, so a
     * {@code group: [STUDYID]} on a per-dataset operation (the {@code CDISC-CG027x} TS rules) is a
     * no-op grouping.
     *
     * <p>
     * Deliberately separate from {@link #DATASET_CONSTANT_COLUMNS}: this set licenses a claim about
     * <em>grouping</em> only. Whether {@code STUDYID} should also count as a dataset-level
     * <em>operand</em> is a wider question — {@code CDISC-SEND-0249.1} exists precisely because it
     * can vary in non-conformant data — and is left alone here.
     * </p>
     */
    private static final Set<String> DATASET_CONSTANT_GROUP_KEYS = Set.of("DOMAIN", "STUDYID");

    /**
     * Operators whose {@code value} is <em>never</em> a reference — a regex pattern, a date bound,
     * a sort spec. Derived from the Python reference engine's own implementations
     * ({@code check_operators/dataframe_operators.py}: these use the comparator as a raw value and
     * never consult {@code value_is_literal}), recorded with per-operator evidence in
     * {@code documentation/derivation/operator-value-kind.tsv}. Without this set a regex operand
     * would be misread as a column reference, and a check on a dataset-constant column such as
     * {@code matches_regex(DOMAIN, "^[^A-Z]")} would look per-record.
     */
    private static final Set<String> VALUE_IS_LITERAL = Set.of("matches_regex", "not_matches_regex",
            "prefix_matches_regex", "not_prefix_matches_regex", "suffix_matches_regex",
            "not_suffix_matches_regex", "date_equal_to", "date_not_equal_to", "date_greater_than",
            "date_greater_than_or_equal_to", "date_less_than", "date_less_than_or_equal_to",
            "target_is_sorted_by", "target_is_not_sorted_by", "empty_within_except_last_row",
            "has_next_corresponding_record", "does_not_have_next_corresponding_record");

    /**
     * Operators whose {@code value} names column(s) rather than carrying a literal. Mirrors the
     * Python reference engine's own split ({@code check_operators/dataframe_operators.py}: these
     * index the frame by the comparator) and {@code ExprCompiler.keyColumns} on the Java side.
     */
    private static final Set<String> VALUE_IS_COLUMN = Set.of("is_inconsistent_across_dataset",
            "is_not_unique_set", "is_unique_set", "is_not_unique_relationship",
            "is_unique_relationship", "has_multiple_values_for");

    /**
     * The uniqueness pair whose canonical authored form is a single list operand
     * ({@code is_unique_set([A, B, …])}, 2026-08-23) — the one call shape whose operands sit inside
     * a LIST literal rather than in the positional slots {@link #atom} reads.
     */
    private static final Set<String> UNIQUE_SET_LIST_OPERATORS = Set.of("is_unique_set",
            "is_not_unique_set");

    // ------------------------------------------------------------------
    // Call scope (§4.9) — the scope a registry function's result resolves at
    // ------------------------------------------------------------------

    // (RECORD_SCOPED_OPERATIONS held date_diff_days, the last operation whose result was per record
    // whatever its group; it is a registry function since runbook W2b, whose operands the walk
    // reads
    // as any call's: its minuend is a column of the record, so the rule stays record-scoped.)

    /**
     * Whether {@code name} is a registered function whose result is per-variable (a
     * {@code VariableMetadataResult}) — read from its descriptor
     * ({@code FunctionDescriptor.perVariableMap}), not a name list (combined review of runbook
     * W2–W8, W3+W4b L5).
     */
    private static boolean isVariableScopedFunction(String name)
    {
        FunctionDescriptor d = FunctionRegistry.descriptor(name);
        return d != null && d.perVariableMap();
    }

    // Library permissibility operations (required_variables / expected_variables /
    // permissible_variables) deliberately do NOT route to
    // VARIABLE_METADATA_CHECK_AGAINST_LIBRARY_METADATA. On a $-only Check the frame is never
    // read, so the only things the Rule_Type decides are cost and the frame's row count — and
    // the Python `…against Library Metadata` builder is the most expensive of the three (it
    // reads the whole dataset contents, fetches library metadata, merges, then scans the
    // contents once per variable for null stats), while ContentMetadataDatasetBuilder is a
    // single metadata call that also yields one row on an EMPTY dataset, so the rule still
    // fires. The corpus's Dataset Metadata Check labelling is therefore the better engineering
    // choice and the 14 rules were normalised onto it (user decision 2026-07-29).

    /** The scope at which an operation's result varies. */
    private enum OperationScope
    {
        DATASET, VARIABLE, RECORD
    }


    /**
     * One registry-function call the Check uses — <b>id-free</b> (plan
     * {@code PLAN-classifier-redesign} §3): only {@code (operator, group, filter, domain, args)}
     * carry classification signal, never the authoring-artifact {@code $}-name. Usages are
     * collected from the call itself, inline in the Check or read through a compiled binding
     * ({@code BindingInliner}), so the two spellings are indistinguishable here by construction.
     * (Until runbook W8 a declared operation record was the second source.)
     *
     * @param operator
     *            the operation's operator name, or {@code null} for an unresolvable reference
     *            (classified worst-case)
     * @param group
     *            the grouping keys, never {@code null}
     * @param filtered
     *            whether the operation carries a row {@code filter}
     * @param domain
     *            the pinned foreign domain, or {@code null}
     * @param args
     *            the target operand name(s) ({@code name} / {@code names}), never {@code null};
     *            carried to complete the plan's usage tuple — no vocabulary currently keys on the
     *            target operand, and it must never leak into the frame-operand view
     */
    private record CallUsage(@Nullable String operator, List<String> group, boolean filtered,
            @Nullable String domain, List<String> args)
    {

        /**
         * An unresolvable {@code $}-reference or malformed declaration — assume the worst on every
         * axis (per-record and data-bound), so the rule is not mis-classified as a broadcast
         * dataset check.
         */
        private static final CallUsage UNRESOLVED = new CallUsage(null, List.of(), true, null,
                List.of());
    }

    /**
     * The usage a registry-function call denotes — the classification weight a declared operation
     * of the same name used to carry (its operator, group columns, filter, pinned {@code domain=},
     * target) — or {@code null} when the call is an ordinary function whose operands are read as
     * leaves. Runbook W8: every such call reaches the walk through an inlined compiled binding
     * ({@code BindingInliner}, R19) or written inline in the Check; the per-wave arms below carry
     * the usage each ported callable's operation denoted, so row K of the routing census holds by
     * construction.
     */
    private static @Nullable CallUsage callUsage(Expr.Call call)
    {
        // Wave 4 (PLAN-list-functions D-W4-6): a dataset-level list FUNCTION reached through
        // an inlined compiled binding denotes the usage its declared operation denoted —
        // the operator name, no group, no domain, no target — so the study-level test and
        // the operand walk read it exactly as before the port (row K holds by construction).
        if (ListFunctionSupport.FUNCTION_NAMES.contains(call.name()))
        {
            return new CallUsage(call.name(), List.of(), false, null, List.of());
        }
        // PLAN-dynamic-column-functions §2.4 / §2.8: find_vars over a string LITERAL entry reads
        // the dataset's column inventory once — a dataset-level list usage, like the wave-4 list
        // functions above. Without this arm the walk read the entry's TEXT (`TRTxxP`) as a
        // per-record column and derived CDISC-AD0581 Record-sensitive (routing census). A computed
        // entry keeps the default walk, whose columns make it per record.
        if (FindVars.NAME.equals(call.name()) && call.args().size() == 1
                && call.args().get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            return new CallUsage(call.name(), List.of(), false, null, List.of());
        }
        // Wave 5 (PLAN-grouped-aggregate-functions D-W5-7): a grouped aggregate call — and
        // read_value with a group= — denotes the usage its declared operation denoted: the
        // operator, its group columns, whether it filters, its domain and its target, so
        // operationScope reads it RECORD (a grouped aggregate resolves per primary row) and
        // the study-level test sees the pinned domain exactly as before the port.
        if (GroupedAggregate.isFunction(call.name())
                || (ReadValue.NAME.equals(call.name()) && call.kwargs().containsKey("group"))
                || RecordCount.NAME.equals(call.name()) || Distinct.NAME.equals(call.name()))
        {
            // (record_count since runbook W6, PLAN-record-count-function D-W6-9: the same
            // usage its RECORD_COUNT operation denoted — the group members with a raw `$`
            // one kept, whether it filters, its domain; no target since R6 dropped it.)
            return groupedCallUsage(call);
        }
        if (ReferencedDatasetVariables.NAME.equals(call.name()))
        {
            // Runbook W7 (PLAN-distinct-function D-W7-6): the per-row variable-name list
            // denotes the usage its distinct(…, value_is_reference=true) operation denoted —
            // the operator, no group, no domain, its target column.
            return new CallUsage(call.name(), List.of(), false, null, targetsOf(call));
        }
        // Wave 4b (PLAN-scalar-metadata-functions D-W4b-7): the same for the dataset-level
        // scalar functions — cross_dataset_variable_metadata keeps its per-variable scope
        // (isVariableScopedFunction reads the descriptor's perVariableMap flag) and carries its
        // source dataset.
        return ScalarMetadataFunctions.FUNCTION_NAMES.contains(call.name())
                ? new CallUsage(call.name(), List.of(), false, scalarDomainOf(call), List.of())
                : null;
    }


    /**
     * The usage of a W5 grouped aggregate call ({@code max(AVAL, group=[…], filter=(…))},
     * {@code max_date(DSSTDTC, domain="DS", group=[USUBJID])}): the group column names, the
     * {@code domain=} (bare or quoted, D10) and the target when it is a bare column.
     */
    private static CallUsage groupedCallUsage(Expr.Call call)
    {
        List<String> group = new ArrayList<>();
        Expr groupExpr = call.kwargs().get("group");
        if (groupExpr instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST
                && lit.value() instanceof List<?> items)
        {
            for (Object item : items)
            {
                if (item instanceof Expr.Ref ref)
                {
                    group.add(ref.name());
                }
                else if (item instanceof Expr.Lit member && member.kind() == Expr.LitKind.STRING)
                {
                    group.add(String.valueOf(member.value()));
                }
            }
        }
        else if (groupExpr instanceof Expr.Ref ref)
        {
            group.add(ref.name());
        }
        Expr domainExpr = call.kwargs().get("domain");
        String domain = domainExpr instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                ? String.valueOf(lit.value())
                : domainExpr instanceof Expr.Ref ref ? ref.name() : null;
        return new CallUsage(call.name(), List.copyOf(group), call.kwargs().containsKey("filter"),
                domain, targetsOf(call));
    }


    /**
     * The target column(s) a call's first positional names: a reference, or each reference of a
     * list literal (a {@code distinct([A, B], …)} tuple target — the retired {@code names}, which
     * the declared usage listed member by member; runbook W7). Empty for no positional.
     */
    private static List<String> targetsOf(Expr.Call call)
    {
        if (call.args().isEmpty())
        {
            return List.of();
        }
        Expr first = call.args().get(0);
        if (first instanceof Expr.Ref target)
        {
            return List.of(target.name());
        }
        List<String> names = new ArrayList<>();
        if (first instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST
                && lit.value() instanceof List<?> items)
        {
            for (Object item : items)
            {
                if (item instanceof Expr.Ref ref)
                {
                    names.add(ref.name());
                }
            }
        }
        return List.copyOf(names);
    }


    /**
     * The {@code domain=} string literal of a wave-4b scalar call
     * ({@code cross_dataset_variable_metadata(…, domain="ADSL")}), the field its declared operation
     * carried; {@code null} for any other call.
     */
    private static @Nullable String scalarDomainOf(Expr.Call call)
    {
        return call.kwargs().get(ScalarMetadataFunctions.DOMAIN_PARAMETER) instanceof Expr.Lit lit
                && lit.kind() == Expr.LitKind.STRING ? (String) lit.value() : null;
    }


    private static OperationScope operationScope(CallUsage usage)
    {
        if (usage.operator() == null || PER_ROW_NAME.equals(usage))
        {
            return OperationScope.RECORD;
        }
        if (!usage.group().isEmpty() && !DATASET_CONSTANT_GROUP_KEYS.containsAll(usage.group()))
        {
            // A grouped aggregate resolves per primary row whatever its operator says — unless
            // every group key is dataset-constant, in which case there is a single group and the
            // result is broadcast.
            return OperationScope.RECORD;
        }
        if (isVariableScopedFunction(usage.operator()))
        {
            return OperationScope.VARIABLE;
        }
        return OperationScope.DATASET;
    }

    // ------------------------------------------------------------------
    // Check traversal
    // ------------------------------------------------------------------

    /**
     * One classified Check atom — the operator token under its legacy vocabulary name, the operand
     * name(s), and the reference/literal resolution of the value side, all read from the
     * {@link Expr} at walk time. This is the walk's own shape: it replaces the former adaptation of
     * {@code Expr} nodes back into {@code CheckConditionLeaf} (source plan §5d), so no legacy model
     * object stands between the expression and the verdict.
     *
     * @param operator
     *            the predicate's operator token ({@code equal_to}, {@code ds_exists}, …), or
     *            {@code null} for a bare operand / usage-only atom
     * @param name
     *            the first operand's name, or {@code null}
     * @param value
     *            the second operand's text, or {@code null}
     * @param valueIsLiteral
     *            whether the value side is a literal (an {@link Expr.Lit})
     */
    private record Atom(@Nullable String operator, @Nullable String name, @Nullable String value,
            boolean valueIsLiteral)
    {
    }


    /**
     * A Check atom together with the polarity and entailment of its position in the tree, plus the
     * {@link CallUsage}s its operands denote — a {@code $}-reference and an inlined
     * operation-operator call land here identically, never as an atom operand.
     */
    private record Positioned(Atom atom, boolean negated, boolean entailed, List<CallUsage> usages)
    {
    }

    /**
     * Flattens the rule's Check tree to its leaves, tracking negation and whether each leaf is
     * <em>entailed</em> — necessarily true whenever the Check fires. A leaf directly under
     * {@code all} is entailed; under {@code any} it is entailed only when that {@code any} has a
     * single branch (the corpus's one-branch {@code any} idiom).
     *
     * @param rule
     *            the rule whose Check to walk; its compiled bindings are read through
     *            ({@code BindingInliner}, R19)
     * @return the leaves in document order
     */
    private static List<Positioned> leaves(Rule rule)
    {
        List<Positioned> out = new ArrayList<>();
        // ⚑ Plan C §3.3: every declared check level, in ladder order. Sensitivity is a property of
        // the whole rule (one evaluation domain, one grouping), so the derivation reads every
        // level's leaves. One level, and it IS getCheck(), for every rule that authors a plain
        // Check:.
        for (CheckCondition condition : rule.checkConditions())
        {
            // PLAN-binding-expressions R19: a COMPILED binding is read through — its expression
            // classified at the reference's position, exactly as if it were written inline —
            // never resolved against the operations and degraded to UNRESOLVED (the worst case).
            collect(net.cumba.corej.core.expr.convert.BindingInliner.inline(toExprOrNull(condition),
                    rule), false, true, out);
        }
        return out;
    }


    /**
     * Raises a Check of <em>any</em> form to its {@link Expr}, or {@code null} when it has no
     * expression surface.
     *
     * <p>
     * The single front door for derivation. Every corpus is expression-form (phase 7d, D121 — the
     * operator-leaf model is retired), so an expression Check already carries its parsed
     * {@code Expr}, which {@code CheckToExpr} returns as-is, and a composite
     * ({@code all}/{@code any}/{@code not} over expressions) is raised structurally. One traversal
     * serves every corpus; two front-ends that could disagree about the same rule would be worse
     * than the duplication this plan removed.
     * </p>
     *
     */
    private static @Nullable Expr toExprOrNull(@Nullable CheckCondition condition)
    {
        if (condition == null)
        {
            return null;
        }
        try
        {
            return CheckToExpr.toExpr(condition);
        }
        catch (RuntimeException _)
        {
            // No expression surface (boolean constant): no operands, exactly as an empty Check
            // gave — the caller degrades to Confidence.NONE.
            return null;
        }
    }


    private static void collect(@Nullable Expr expr, boolean negated, boolean entailed,
            List<Positioned> out)
    {
        switch (expr)
        {
        case null ->
        {
            // nothing to collect
        }
        case Expr.And and ->
        {
            for (Expr part : and.parts())
            {
                collect(part, negated, entailed, out);
            }
        }
        case Expr.Or or ->
        {
            boolean single = or.parts().size() == 1;
            for (Expr part : or.parts())
            {
                collect(part, negated, entailed && single, out);
            }
        }
        case Expr.Not not -> collect(not.inner(), !negated, entailed, out);
        case Expr.Lit _ ->
        {
            // a literal carries no operand
        }
        default -> out.add(atom(expr, negated, entailed));
        }
    }


    /**
     * Classifies one {@link Expr} predicate/operand node into an {@link Atom} — operator token,
     * operand name and value — collecting any {@link CallUsage} its operands denote as it goes. The
     * vocabularies apply unchanged: {@code CheckToExpr} emits the operator as the call name, so the
     * tokens they match on ({@code is_not_unique_set}, {@code has_same_values}, …) are the same in
     * every corpus form.
     */
    private static Positioned atom(Expr expr, boolean negated, boolean entailed)
    {
        List<CallUsage> usages = new ArrayList<>(2);
        String operator = null;
        String name = null;
        String value = null;
        boolean valueIsLiteral = false;
        switch (expr)
        {
        case Expr.Call call ->
        {
            CallUsage usage = callUsage(call);
            if (usage != null)
            {
                // an operation call standing alone as a predicate — a boolean operation such as
                // domain_is_custom(...); the usage is the whole signal, there is no atom operator
                usages.add(usage);
                break;
            }
            String operand = accessorOperand(call);
            if (operand != null && !operand.equals(call.name()))
            {
                // a metadata accessor standing alone as a predicate is an operand, not an operator
                name = operand;
                break;
            }
            operator = call.name();
            List<Expr> args = call.args();
            if (UNIQUE_SET_LIST_OPERATORS.contains(operator) && args.size() == 1
                    && args.get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST
                    && lit.value() instanceof List<?> members && !members.isEmpty())
            {
                // Owner requirement #1 (2026-08-23): is_(not_)unique_set([A, B, …]) carries its
                // whole key tuple as ONE list operand. Read it exactly as the two-positional
                // spelling f(A, B) was read — member 0 is the operand name, member 1 (when
                // present) the value — so the atom keeps naming the columns it did before the
                // flattening; without this arm the call would classify with NO operands.
                args = new ArrayList<>(members.size());
                for (Object member : members)
                {
                    if (member instanceof Expr e)
                    {
                        args.add(e);
                    }
                }
            }
            if (!args.isEmpty())
            {
                name = operandText(args.get(0), usages);
            }
            if (args.size() > 1)
            {
                value = operandText(args.get(1), usages);
                valueIsLiteral = args.get(1) instanceof Expr.Lit;
            }
            if (readsAnotherDataset(call))
            {
                // Runbook W2a (PLAN-operation-replacements §2.2, owner D10 / D13 Q4): a registry
                // call carrying `domain=` reads ANOTHER dataset — its positionals and its filter
                // name that dataset's columns, never the primary's (`read_value(TSVAL, domain=TS,
                // …)`). It contributes no column operand, exactly as OutputVariableDeriver's D4b
                // skips such a call's target; otherwise TSVAL would derive a Record sensitivity
                // for a rule whose own dataset it never reads (CDISC-SEND-0105 moved Dataset →
                // Record until this arm existed).
                name = null;
                value = null;
                valueIsLiteral = false;
            }
            else if (name == null && (value == null || valueIsLiteral))
            {
                // A call whose positionals yield NO column operand — none at all
                // (`is_last_in_group(ordering=SESEQ, group=[USUBJID])`), or only non-column ones
                // (a numeric literal, a $-operation reference, a nested call reading nothing) —
                // while its columns arrive through its declared column parameters. Read through
                // the descriptor (see declaredColumnOperands) so the leaf names what the call
                // reads; without this the leaf had no operand, the Check was "signal-free", no
                // Sensitivity was derived, and the runner collapsed N per-row findings to one
                // (review round 1 of PLAN-function-surface-wave1, M3; generalised from "no
                // positional at all" by review round 2, L-1). A call whose positionals DO yield a
                // column operand keeps today's reading — its first two positionals — so no
                // existing classification moves.
                List<String> declared = declaredColumnOperands(call, usages);
                if (!declared.isEmpty())
                {
                    name = declared.get(0);
                }
                if (declared.size() > 1 && value == null)
                {
                    value = declared.get(1);
                    valueIsLiteral = false;
                }
            }
        }
        case Expr.Binary binary ->
        {
            operator = BIN_OPERATORS.get(binary.op());
            name = operandText(binary.left(), usages);
            value = operandText(binary.right(), usages);
            valueIsLiteral = binary.right() instanceof Expr.Lit;
        }
        case Expr.Ref ref ->
        {
            if (isOperationRef(ref.name()))
            {
                usages.add(CallUsage.UNRESOLVED);
            }
            else
            {
                name = ref.name();
            }
        }
        default ->
        {
            // no operand surface
        }
        }
        if (namesAColumnPerRow(expr))
        {
            usages.add(PER_ROW_NAME);
        }
        return new Positioned(new Atom(operator, name, value, valueIsLiteral), negated, entailed,
                usages);
    }

    /**
     * ⭐ {@code PLAN-dynamic-column-functions}, review round 1 (lane C F9, lane A L7): the usage of
     * a leaf that reads a column NAMED PER ROW — a {@code var_exists} / {@code var_not_exists} over
     * a computed name (its verdict varies with the row's name), or a {@code colref} over a list, a
     * list-valued call ({@code find_vars}) or a {@code $}-binding (it reads each row's cells). Its
     * scope is RECORD ({@link #operationScope}), and it outranks the dataset-level reading a
     * presence operator or a literal {@code find_vars} would otherwise give the leaf
     * ({@link #nonDatasetReason}). A literal or reference {@code var_exists} argument and a scalar
     * {@code colref} over a column or a literal keep today's classification.
     */
    private static final CallUsage PER_ROW_NAME = new CallUsage("colref", List.of(), false, null,
            List.of());

    /** Whether {@code expr} holds a column named per row ({@link #PER_ROW_NAME}). */
    private static boolean namesAColumnPerRow(Expr expr)
    {
        return switch (expr)
        {
        case Expr.Call call ->
        {
            if (call.args().size() == 1 && VARIABLE_PRESENCE.contains(call.name())
                    && !(call.args().get(0) instanceof Expr.Ref)
                    && !(call.args().get(0) instanceof Expr.Lit))
            {
                yield true;
            }
            if (call.args().size() == 1 && "colref".equals(call.name())
                    && isListFormArgument(call.args().get(0)))
            {
                yield true;
            }
            boolean nested = false;
            for (Expr arg : call.args())
            {
                nested |= namesAColumnPerRow(arg);
            }
            for (Expr arg : call.kwargs().values())
            {
                nested |= namesAColumnPerRow(arg);
            }
            yield nested;
        }
        case Expr.Binary binary -> namesAColumnPerRow(binary.left())
                || namesAColumnPerRow(binary.right());
        case Expr.Not not -> namesAColumnPerRow(not.inner());
        case Expr.And and -> and.parts().stream().anyMatch(RuleClassifier::namesAColumnPerRow);
        case Expr.Or or -> or.parts().stream().anyMatch(RuleClassifier::namesAColumnPerRow);
        default -> false;
        };
    }


    /** A list literal, a list-valued call or a {@code $}-binding — a list-form colref argument. */
    private static boolean isListFormArgument(Expr arg)
    {
        return (arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
                || (arg instanceof Expr.Call call && net.cumba.corej.core.expr.typed.ElementTable
                        .resultType(call.name()) instanceof ExprType.ListOf)
                || (arg instanceof Expr.Ref ref && isOperationRef(ref.name()));
    }


    /** Whether an operand name is a {@code $}-operation reference. */
    private static boolean isOperationRef(@Nullable String name)
    {
        return name != null && !name.isEmpty() && name.charAt(0) == '$';
    }

    /** Expression binary operators under the legacy operator names the vocabularies use. */
    private static final Map<Expr.BinOp, String> BIN_OPERATORS = Map.ofEntries(
            Map.entry(Expr.BinOp.EQ, "equal_to"), Map.entry(Expr.BinOp.NEQ, "not_equal_to"),
            Map.entry(Expr.BinOp.LT, "less_than"), Map.entry(Expr.BinOp.GT, "greater_than"),
            Map.entry(Expr.BinOp.LE, "less_than_or_equal_to"),
            Map.entry(Expr.BinOp.GE, "greater_than_or_equal_to"),
            Map.entry(Expr.BinOp.MATCH, "matches_regex"),
            Map.entry(Expr.BinOp.NMATCH, "not_matches_regex"),
            Map.entry(Expr.BinOp.IN, "is_contained_by"),
            Map.entry(Expr.BinOp.NOT_IN, "is_not_contained_by"), Map.entry(Expr.BinOp.ADD, "add"),
            Map.entry(Expr.BinOp.SUB, "subtract"), Map.entry(Expr.BinOp.MUL, "multiply"),
            Map.entry(Expr.BinOp.DIV, "divide"));

    /**
     * The operand text of a node.
     *
     * <p>
     * <b>The metadata level lives in the call, not the name.</b> {@code MetadataOperandMapping}
     * names accessors by <em>scope only</em> —
     * {@code (scope == VARIABLE ? "var_" : "ds_") + suffix} — so {@code variable_label},
     * {@code library_variable_label} and {@code define_variable_label} all render as
     * {@code var_label(…)}, with DATA / LIBRARY / DEFINE carried as a positional level literal.
     * Reading the call name alone would collapse the three levels the frame model (§4.8) and the
     * {@code Rule_Type} cascade (§4.3) are built on, so the accessor is reversed through
     * {@link MetadataOperandMapping#reverseToOperand} — the same table, used backwards, rather than
     * a second one that could drift from it.
     * </p>
     */
    private static @Nullable String operandText(Expr expr, List<CallUsage> usages)
    {
        return switch (expr)
        {
        case Expr.Ref ref ->
        {
            if (isOperationRef(ref.name()))
            {
                // A $-operation reference is an operation usage, not a frame operand — the same
                // statement about the rule as the inlined call below, and indistinguishable from
                // it by construction.
                usages.add(CallUsage.UNRESOLVED);
                yield null;
            }
            yield ref.name();
        }
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> String.valueOf(lit.value());
        case Expr.Call call ->
        {
            CallUsage usage = callUsage(call);
            if (usage != null)
            {
                // An inlined operation call in operand position (`record_count(filter=…) == 0`) —
                // everything inside it, args and kwargs alike, belongs to the usage; nothing leaks
                // into the classified operand view, exactly as a declared operation's fields never
                // did.
                usages.add(usage);
                yield null;
            }
            String operand = accessorOperand(call);
            // In OPERAND position a wrapper such as len(...) contributes no operand of its own —
            // the operand it reads is its first argument (`len(varname()) > 8` is a check on
            // variable_name). Deliberately not done in predicate position, where the call name IS
            // the operator and recursing would discard it (ds_exists("EX") is a dataset-presence
            // assertion, not a check on a column called EX).
            boolean namesItsOwnOperand = operand != null && classify(operand) != null;
            if (readsAnotherDataset(call))
            {
                yield null; // W2a: a `domain=` registry call names no column of the primary
            }
            if (operand != null && operand.equals(call.name()) && !namesItsOwnOperand)
            {
                String positional = call.args().isEmpty() ? null
                        : operandText(call.args().get(0), usages);
                if (positional != null)
                {
                    yield positional;
                }
                // The first positional yields no operand — there is none, or it is a non-column
                // (a numeric literal, a $-operation reference): the operand it reads may be bound
                // to a declared column parameter (the same case as the predicate-position arm in
                // atom — `is_last_in_group(ordering=…) == true`; review round 2, L-1).
                List<String> declared = declaredColumnOperands(call, usages);
                if (!declared.isEmpty())
                {
                    yield declared.get(0);
                }
                if (!call.args().isEmpty())
                {
                    yield null; // unchanged: a non-column positional and no declared column
                }
            }
            yield operand;
        }
        default -> null;
        };
    }


    /**
     * Whether {@code call} is a registry call that reads another dataset — it carries a
     * {@code domain=} keyword. Its columns are that dataset's (D10 / D13 Q4), so it names no
     * operand of the dataset under evaluation.
     */
    private static boolean readsAnotherDataset(Expr.Call call)
    {
        return call.kwargs().containsKey("domain");
    }


    /**
     * The column references a registry or compiler-dispatched call reads through its
     * <em>declared</em> parameters, in declaration order: every bound slot whose parameter is typed
     * {@code COLUMN_REFERENCE} or {@code list<column-reference>} contributes its plain or
     * {@code --}-prefix references. Empty for an unknown name, a call that does not bind, or a call
     * whose descriptor declares no column parameter. This is the general form the later waves need
     * (W3+ port callables whose columns arrive as keywords, {@code group=} / {@code keys=} /
     * {@code reference=}); the positional reading of {@link #atom} stays first so that today's
     * derivations do not move — this is consulted only when the positionals yield no column
     * operand.
     *
     * <p>
     * Each bound reference is read as {@link #operandText} reads one, so the two never disagree: a
     * {@code $}-reference no compiled binding inlined is an unresolved <b>usage</b> (added to
     * {@code usages} unless the positional walk already recorded it), never a column name.
     * </p>
     *
     * <p>
     * ⚠ <b>Limit:</b> only parameters <em>declared</em> {@code COLUMN_REFERENCE} or
     * {@code list<column-reference>} are read. A parameter declared {@code Unknown} that carries a
     * column in practice — the {@code ordering} of {@code empty_within_except_last_row} and
     * {@code has_next_corresponding_record}, {@code within}, the {@code req("name")} subjects of
     * the older compiler-dispatched readers — contributes nothing here, so a call whose ONLY
     * columns arrive through such a parameter by keyword still classifies without an operand. A
     * wave that ports such a callable retypes the parameter, which is what makes it visible here.
     * </p>
     */
    private static List<String> declaredColumnOperands(Expr.Call call, List<CallUsage> usages)
    {
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(call.name());
        if (descriptor == null)
        {
            return List.of();
        }
        List<@Nullable Expr> bound;
        try
        {
            bound = ArgumentBinder.bind(descriptor, call);
        }
        catch (RuntimeException _)
        {
            return List.of(); // the compiler reports the malformed call on its own terms
        }
        List<Parameter> params = descriptor.parameters();
        List<String> out = new ArrayList<>(2);
        for (int i = 0; i < bound.size() && i < params.size(); i++)
        {
            ExprType type = params.get(i).type();
            boolean column = type == ExprType.Primitive.COLUMN_REFERENCE
                    || (type instanceof ExprType.ListOf list
                            && list.element() == ExprType.Primitive.COLUMN_REFERENCE);
            if (column)
            {
                collectColumnRefs(bound.get(i), usages, out);
            }
        }
        return out;
    }


    private static void collectColumnRefs(@Nullable Expr e, List<CallUsage> usages,
            List<String> out)
    {
        if (e instanceof Expr.Ref ref)
        {
            if (isOperationRef(ref.name()))
            {
                // as operandText: a usage, never a column name
                if (!usages.contains(CallUsage.UNRESOLVED))
                {
                    usages.add(CallUsage.UNRESOLVED);
                }
                return;
            }
            out.add(ref.name());
        }
        else if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST
                && lit.value() instanceof List<?> items)
        {
            for (Object item : items)
            {
                if (item instanceof Expr member)
                {
                    collectColumnRefs(member, usages, out);
                }
            }
        }
    }


    /**
     * The legacy operand name a {@code var_*} / {@code ds_*} accessor call denotes, or the call's
     * own name when it is not a metadata accessor.
     *
     * <p>
     * <b>Why not just {@code MetadataOperandMapping.reverseToOperand}.</b> That method answers a
     * stricter question — "is this call losslessly reversible to an authorable operand?" — and so
     * returns {@code null} for a literal-named target ({@code var_label("AESEV", "DATA")}), a
     * cross-dataset {@code dataset=} kwarg, and other native-only shapes. Derivation does not need
     * reversibility; it needs the operand's <em>class</em>. Those calls are still perfectly good
     * variable- or dataset-metadata reads, and treating them as unclassified collapsed 55 metadata
     * rules to {@code Record Data} on the first attempt.
     * </p>
     *
     * <p>
     * So the class is reconstructed from the two things that actually carry it:
     * {@link MetadataAttribute#scope()} (from the function name) and the positional level literal —
     * {@code DATA} / {@code LIBRARY} / {@code DEFINE}. The accessor name itself holds only the
     * scope ({@code MetadataOperandMapping} names them {@code var_}/{@code ds_} + suffix), which is
     * why the level must be read from the arguments. The reversible case still goes through
     * {@code reverseToOperand} first, so the authoritative table stays in charge wherever it
     * applies.
     * </p>
     */
    private static @Nullable String accessorOperand(Expr.Call call)
    {
        // The two per-record built-ins render as bare zero-arg calls rather than var_* accessors:
        // `variable_name` -> varname() and `variable_value` -> value(). MetadataOperandMapping
        // special-cases both on the way out, so they need naming on the way back.
        if (call.args().isEmpty() && call.kwargs().isEmpty())
        {
            if ("varname".equals(call.name()))
            {
                return "variable_name";
            }
            if ("value".equals(call.name()))
            {
                return VARIABLE_VALUE;
            }
        }
        String reversed = MetadataOperandMapping.reverseToOperand(call);
        if (reversed != null)
        {
            return reversed;
        }
        // define_vlm_* operands map to their own vlm_* accessors rather than the scope+level
        // scheme, so they are named directly (MetadataOperandMapping keeps the same explicit
        // table on the way out).
        if (call.name().startsWith("vlm_"))
        {
            return "define_vlm_" + call.name().substring(4);
        }
        MetadataAttribute attr = MetadataAttribute.fromFunction(call.name());
        boolean variableScope = attr != null && attr.scope() == MetadataAttribute.Scope.VARIABLE;
        for (Expr arg : attr == null ? List.<Expr> of() : call.args())
        {
            if (!(arg instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
            {
                continue;
            }
            MetadataLevel level = MetadataLevel.tryParse(String.valueOf(lit.value()));
            if (level != null)
            {
                String prefix = switch (level)
                {
                case DATA -> variableScope ? "variable_" : "dataset_";
                case LIBRARY -> variableScope ? "library_variable_" : "library_dataset_";
                case DEFINE -> variableScope ? "define_variable_" : "define_dataset_";
                };
                return prefix + suffixOf(call.name());
            }
        }
        return call.name();
    }


    /**
     * The attribute suffix of an accessor function name ({@code var_label} &rarr; {@code label}).
     */
    private static String suffixOf(String functionName)
    {
        String suffix = functionName.startsWith("var_") ? functionName.substring(4)
                : functionName.substring(3);
        return "type".equals(suffix) ? "data_type" : suffix;
    }


    /**
     * The operand names an atom references — its {@code name}, plus its {@code value} when that
     * value is a reference rather than a literal.
     *
     * @param atom
     *            the atom to read
     * @return the referenced operand names, possibly empty
     */
    private static List<String> operands(Atom atom)
    {
        List<String> out = new ArrayList<>(2);
        if (atom.name() != null)
        {
            out.add(atom.name());
        }
        String value = atom.value();
        if (value == null)
        {
            return out;
        }
        String operator = atom.operator();
        if (operator != null && VALUE_IS_LITERAL.contains(operator))
        {
            return out;
        }
        if (operator != null && VALUE_IS_COLUMN.contains(operator))
        {
            // The value names a column.
            out.add(value);
            return out;
        }
        // Otherwise the reference/literal split mirrors the Python engine's own rule
        // (dataframe_operators: `value_is_literal or not isinstance(comparator, str)`): a
        // non-string value is a literal; a string is a reference unless flagged literal.
        if (!atom.valueIsLiteral())
        {
            out.add(value);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // §4.3 — Rule_Type
    // ------------------------------------------------------------------


    private static <T> Derived<T> certain(T value, String why)
    {
        return new Derived<>(value, Confidence.CERTAIN, why);
    }

    // ------------------------------------------------------------------
    // §4.4 — Sensitivity
    // ------------------------------------------------------------------


    /**
     * Derives the rule's {@code Sensitivity}. Type-free: the one reading that used to depend on the
     * rule's type — the generic {@code exists} as dataset presence on a Domain Presence Check —
     * died with that operator (phase 1 of {@code PLAN-leaf-scope-domain-inference.md}; every
     * presence leaf now spells its own fact).
     *
     * @param rule
     *            the rule to classify
     * @return the derived sensitivity with its confidence and rationale
     */
    public static Derived<Sensitivity> deriveSensitivity(Rule rule)
    {
        List<String> grouping = rule.effectiveGroupingVariables();
        if (grouping != null && !grouping.isEmpty())
        {
            return certain(Sensitivity.GROUP, "Grouping_Variables present");
        }
        List<Positioned> leaves = leaves(rule);
        if (leaves.isEmpty())
        {
            return new Derived<>(null, Confidence.NONE, "no Check leaves to classify");
        }
        if (leaves.stream().allMatch(p -> operands(p.atom()).isEmpty() && p.usages().isEmpty()))
        {
            // A signal-free Check (plan PLAN-classifier-redesign §2.2): nothing is read, so there
            // is no basis to distinguish a study-, dataset- or record-level verdict — saying
            // "Study" here would be a confident answer built on absence.
            return new Derived<>(null, Confidence.NONE,
                    "the Check reads no column and uses no operation — nothing to attach a"
                            + " sensitivity to; the field must be authored");
        }
        if (!hasPositiveDatasetAnchor(leaves) && !readsPrimaryDataset(leaves))
        {
            return certain(Sensitivity.STUDY,
                    "no dataset-presence anchor and nothing read from the dataset under evaluation"
                            + " — the finding has no dataset to attach to");
        }
        for (Positioned p : leaves)
        {
            String why = nonDatasetReason(p);
            if (why != null)
            {
                return new Derived<>(Sensitivity.RECORD, Confidence.LIKELY, why);
            }
        }
        return certain(Sensitivity.DATASET, "every Check leaf is a dataset-level fact ("
                + describeLeaves(leaves) + ") — one verdict per dataset");
    }


    /**
     * The datasets an entailed conjunct asserts to be <em>present</em> — the rule's attachment
     * points. A rule with a named anchor reports against that dataset, so if its {@code Scope} does
     * not pin the anchor the same verdict is emitted once per dataset in the study (plan
     * &sect;3.9).
     *
     * <p>
     * ⚑ No production caller. Its consumer is the corpus derivation census in the rule-corpus
     * repository ({@code DerivationCorpusTest}, the §3.9 lint behind the tracked
     * {@code generated/derivation/unanchored-scope.tsv}) — corpus tooling, which is why it is kept
     * rather than retired with U2 / A38 (2026-09-25); the plan's criterion is about the product,
     * and tooling that reads the classifier's private leaf walk cannot re-derive this itself.
     * </p>
     *
     * @param rule
     *            the rule to inspect
     * @return the anchor dataset names, in document order; empty when the rule has no anchor
     */
    public static List<String> datasetAnchors(Rule rule)
    {
        List<String> names = new ArrayList<>();
        for (Positioned p : leaves(rule))
        {
            if (isPositiveDatasetAnchor(p) && p.atom().name() != null)
            {
                names.add(p.atom().name());
            }
        }
        return names;
    }


    /**
     * Whether some entailed conjunct asserts that a <em>dataset</em> is present. That dataset is
     * the finding's attachment point, so its existence is what distinguishes a dataset-level
     * finding from a study-level one.
     */
    private static boolean hasPositiveDatasetAnchor(List<Positioned> leaves)
    {
        for (Positioned p : leaves)
        {
            if (isPositiveDatasetAnchor(p))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Whether this leaf, in its position, asserts that a dataset <em>is</em> present and is
     * entailed — necessarily true whenever the Check fires.
     */
    private static boolean isPositiveDatasetAnchor(Positioned p)
    {
        String operator = p.atom().operator();
        if (operator == null || !DATASET_PRESENCE.contains(operator))
        {
            return false;
        }
        boolean positive = "ds_exists".equals(operator) != p.negated();
        return positive && p.entailed();
    }


    /** Whether anything in the Check reads the dataset under evaluation. */
    private static boolean readsPrimaryDataset(List<Positioned> leaves)
    {
        for (Positioned p : leaves)
        {
            String operator = p.atom().operator();
            if (operator != null && DATASET_PRESENCE.contains(operator))
            {
                continue;
            }
            if (!operands(p.atom()).isEmpty())
            {
                // Every column operand — plain or metadata accessor — reads the dataset under
                // evaluation. (A leaked $-text is an unresolvable reference: assume it reads.)
                return true;
            }
            for (CallUsage usage : p.usages())
            {
                // Only an ungrouped study-level operation tells us nothing about the dataset
                // under evaluation; every other usage reads it.
                if (!isStudyLevelUsage(usage))
                {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Whether {@code usage} is an ungrouped study-level operation — its result interrogates the
     * study inventory, so it tells us nothing about the dataset under evaluation.
     */
    private static boolean isStudyLevelUsage(CallUsage usage)
    {
        return usage.operator() != null && usage.group().isEmpty()
                && StudyRuleClassifier.isStudyLevelFunction(usage.operator());
    }


    /**
     * {@code operator(name)} for an atom, for use in a rationale; a usage-only atom names its
     * operation instead of a {@code null} operand.
     */
    private static String describe(Positioned p)
    {
        Atom atom = p.atom();
        String name = atom.name();
        if (name == null && !p.usages().isEmpty())
        {
            name = operatorName(p.usages().get(0)) + "()";
        }
        return atom.operator() == null && name != null ? name : atom.operator() + "(" + name + ")";
    }


    /** A short, comma-separated rendering of the leaves, capped so a rationale stays readable. */
    private static String describeLeaves(List<Positioned> leaves)
    {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(leaves.size(), 4);
        for (int i = 0; i < shown; i++)
        {
            sb.append(i == 0 ? "" : ", ").append(describe(leaves.get(i)));
        }
        if (leaves.size() > shown)
        {
            sb.append(", … +").append(leaves.size() - shown);
        }
        return sb.toString();
    }


    /**
     * Why this atom is <em>not</em> a dataset-level fact, naming the operand or operation
     * responsible — or {@code null} when it is dataset-level. The distinction the message must
     * preserve: a variable-level metadata operand is evaluated once <em>per variable</em>, not per
     * record, even though both route away from a single dataset verdict.
     */
    private static @Nullable String nonDatasetReason(Positioned p)
    {
        Atom atom = p.atom();
        String operator = atom.operator();
        if (p.usages().contains(PER_ROW_NAME))
        {
            // Before the presence-operator exemption below: a var_exists over a COMPUTED name is
            // no dataset fact — its verdict varies with each row's name (lane C F9).
            return describe(p) + " — a column named per row (a computed var_exists name, or a"
                    + " colref over a list)";
        }
        if (operator != null && (DATASET_PRESENCE.contains(operator)
                || VARIABLE_PRESENCE.contains(operator) || BROADCAST_OPERATORS.contains(operator)
                || BROADCAST_COLUMN_PREDICATES.contains(operator)))
        {
            return null;
        }
        for (String operand : operands(atom))
        {
            if (!isDatasetLevelOperand(operand))
            {
                return describe(p) + " — " + explainOperand(operand);
            }
        }
        for (CallUsage usage : p.usages())
        {
            OperationScope scope = operationScope(usage);
            if (scope != OperationScope.DATASET)
            {
                return describe(p) + " — operation " + operatorName(usage) + " resolves "
                        + (scope == OperationScope.VARIABLE ? "per variable" : "per record");
            }
        }
        return null;
    }


    /** Why a single operand is not dataset-level, for the rationale. */
    private static String explainOperand(String operand)
    {
        if (isOperationRef(operand))
        {
            return "operation " + operand + " (unresolved) resolves per record";
        }
        if (classify(operand) == null)
        {
            return operand + " is a per-record column";
        }
        return operand + " is variable-level metadata, evaluated once per variable rather than"
                + " yielding a single dataset verdict";
    }


    /**
     * Whether a single operand resolves to a dataset-level value: a column that is constant within
     * the dataset, or a dataset-level metadata accessor. A leaked {@code $}-text is an unresolvable
     * reference and classified worst-case.
     */
    private static boolean isDatasetLevelOperand(String operand)
    {
        if (isOperationRef(operand))
        {
            return false;
        }
        if (DATASET_CONSTANT_COLUMNS.contains(operand))
        {
            return true;
        }
        OperandClass c = classify(operand);
        return c == OperandClass.DATASET_META || c == OperandClass.DEFINE_DATASET
                || c == OperandClass.LIBRARY_DATASET;
    }


    /** The usage's operator for a rationale, naming the unresolved case explicitly. */
    private static String operatorName(CallUsage usage)
    {
        return usage.operator() == null ? "(unresolved)" : usage.operator();
    }

}
