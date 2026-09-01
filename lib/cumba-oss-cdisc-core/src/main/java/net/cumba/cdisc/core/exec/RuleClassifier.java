package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.MetadataOperandMapping;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.convert.OperationExpressionParser;
import net.cumba.cdisc.core.expr.eval.BroadcastFold;
import net.cumba.cdisc.core.expr.eval.MetadataAttribute;
import net.cumba.cdisc.core.expr.eval.MetadataLevel;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Sensitivity;
import org.jspecify.annotations.Nullable;

/**
 * Derives a rule's {@code Rule_Type} and {@code Sensitivity} from the rest of the rule body.
 *
 * <p>
 * See {@code plans/PLAN-derive-rule-type-sensitivity.md} — &sect;4.3 for the {@code Rule_Type}
 * cascade, &sect;4.4 for {@code Sensitivity}, &sect;4.9 for the {@code $}-only operation table —
 * and {@code plans/PLAN-classifier-redesign.md} for the grounding: the classifier reads the native
 * {@link Expr} every corpus form raises to ({@link #toExprOrNull}), plus an <b>id-free
 * operation-usage view</b> collected from both declared {@code Operations} entries and inlined
 * operation-operator calls, so the same rule derives identically as a {@code rules-src} leaf, a
 * {@code rules-legacy} lowered Check and a {@code rules/} inlined expression. The derivation
 * mirrors decisions the engine already makes at run time, so a derived value cannot disagree with
 * how the rule will actually be evaluated.
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
     * mixed-granularity lint of {@code plans/PLAN-split-mixed-granularity-rules.md} &sect;4.4): the
     * membership list lives in {@link BroadcastFold#WHOLE_COLUMN_VERDICT_OPERATORS} so the
     * operator-leaf view here and the raised-expression view there cannot drift apart.
     * </p>
     */
    private static final Set<String> BROADCAST_OPERATORS = BroadcastFold.WHOLE_COLUMN_VERDICT_OPERATORS;

    /**
     * Columns whose value is constant within a dataset, so a check on them yields one verdict per
     * dataset rather than per record. {@code DOMAIN} is already special-cased by
     * {@code OperationExecutor.domainPrefix}.
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
    // Operation scope (§4.9) — GROUNDED from OperationExecutor
    // ------------------------------------------------------------------

    /**
     * Operations whose result is per-record (their {@code eval} returns a {@code GroupedResult}).
     * Derived mechanically from {@code OperationExecutor}'s dispatch, not hand-guessed.
     */
    private static final Set<String> RECORD_SCOPED_OPERATIONS = Set.of("date_diff_days",
            "dictionary_has_decode", "has_mixed_emptiness_within_group",
            "interval_uncertainty_precision_mismatch", "is_last_in_group", "row_max", "row_min",
            "valid_external_dictionary_code", "valid_external_dictionary_code_term_pair",
            "valid_external_dictionary_hierarchy", "valid_external_dictionary_value");

    /** Operations whose result is per-variable (returns a {@code VariableMetadataResult}). */
    private static final Set<String> VARIABLE_SCOPED_OPERATIONS = Set
            .of("cross_dataset_variable_metadata");

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
     * One operation the Check uses — <b>id-free</b> (plan {@code PLAN-classifier-redesign} §3):
     * only {@code (operator, group, filter, domain, args)} carry classification signal, never the
     * authoring-artifact {@code $}-id. Usages are collected from <em>both</em> sources — a declared
     * {@code Operations} entry referenced by {@code $}-id, and a direct operation-operator call in
     * the expression (the shape {@code OperationInliner} produces) — through the same
     * {@link OperationExpressionParser} coercion the engine's own inline-operation path uses, so a
     * declared and an inlined operation are indistinguishable here by construction.
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
    private record OperationUsage(@Nullable String operator, List<String> group, boolean filtered,
            @Nullable String domain, List<String> args)
    {

        /**
         * An unresolvable {@code $}-reference or malformed declaration — assume the worst on every
         * axis (per-record and data-bound), so the rule is not mis-classified as a broadcast
         * dataset check.
         */
        private static final OperationUsage UNRESOLVED = new OperationUsage(null, List.of(), true,
                null, List.of());

        private static OperationUsage of(Operation op)
        {
            List<String> args = new ArrayList<>(2);
            if (op.getName() != null)
            {
                args.add(op.getName());
            }
            if (op.getNames() != null)
            {
                args.addAll(op.getNames());
            }
            return new OperationUsage(op.getOperator(),
                    op.getGroup() == null ? List.of() : List.copyOf(op.getGroup()),
                    op.getFilter() != null && !op.getFilter().isEmpty(), op.getDomain(),
                    List.copyOf(args));
        }
    }

    /**
     * The usage a direct operation-operator call denotes, or {@code null} when the call is not one.
     * Recognition mirrors the engine ({@code ExprCompiler.operationCallPlan}): the
     * {@link OperationType} registry is the single authority for what counts as an operation, and
     * the call is coerced through the same {@link OperationExpressionParser#fromCall} mapping the
     * evaluator uses — including the {@code group=} / {@code filter=} / {@code domain=} keyword
     * arguments, which carry the same classification weight as the fields of a declared operation.
     * A call that shares an operation's name but does not parse as one (wrong shape for the
     * operation grammar) is left to the ordinary call handling.
     *
     * <p>
     * Deliberately <em>broader</em> than {@code ExprCompiler.isInlineOperation}, which routes a
     * builtin-shadowed no-kwarg call ({@code record_count()}) to the builtin: the engine documents
     * the two as semantically identical, and to classification a bare {@code record_count()} IS the
     * ungrouped, unfiltered operation (the FDA-SD0001 shape). Known edges of the view, all
     * corpus-inert and watched by the committed cross-corpus gate
     * ({@code CrossCorpusDerivationTest}): the inliner's two non-{@code OperationType} rewrites —
     * {@code variable_exists} → {@code var_exists(…)} and {@code split_by} → the {@code
     * split_by(col, "sep")} value function — fall outside the usage view (their declared and
     * inlined forms agree on every shipped rule through sibling leaves, not by construction), and
     * {@code dictionary_available(…)}, whose builtin gate form shares the operation's name, occurs
     * in no shipped Check.
     * </p>
     */
    private static @Nullable OperationUsage callUsage(Expr.Call call)
    {
        if (OperationType.fromJson(call.name()) == null)
        {
            return null;
        }
        try
        {
            return OperationUsage.of(OperationExpressionParser.fromCall(call, null));
        }
        catch (RuntimeException _)
        {
            // Not a well-formed operation call (wrong shape for the operation grammar, or a
            // malformed argument): leave it to the ordinary call handling. Broad on purpose —
            // derivation must degrade, never propagate (same stance as toExprOrNull).
            return null;
        }
    }


    /**
     * The usage a {@code $}-reference denotes, resolved against the rule's declared
     * {@code Operations}. A Form-B (expression-form) declaration is normalised through the same
     * parser the loader uses, so the classifier reads identical field values whichever pass runs
     * first; an unresolvable or malformed declaration degrades to
     * {@link OperationUsage#UNRESOLVED}.
     */
    private static OperationUsage declaredUsage(Map<String, Operation> ops, String ref)
    {
        Operation op = ops.get(ref);
        if (op == null)
        {
            return OperationUsage.UNRESOLVED;
        }
        try
        {
            return OperationUsage.of(OperationExpressionParser.normalize(op));
        }
        catch (RuntimeException _)
        {
            // Malformed declaration (bad Form-B expression, null-polluted lists, …): degrade to
            // the worst-case usage rather than propagate out of derivation.
            return OperationUsage.UNRESOLVED;
        }
    }


    private static OperationScope operationScope(OperationUsage usage)
    {
        if (usage.operator() == null)
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
        if (RECORD_SCOPED_OPERATIONS.contains(usage.operator()))
        {
            return OperationScope.RECORD;
        }
        if (VARIABLE_SCOPED_OPERATIONS.contains(usage.operator()))
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
     * @param valueIsReference
     *            whether the value side names an operand (an {@link Expr.Ref})
     * @param valueIsLiteral
     *            whether the value side is a literal (an {@link Expr.Lit})
     */
    private record Atom(@Nullable String operator, @Nullable String name, @Nullable String value,
            boolean valueIsReference, boolean valueIsLiteral)
    {
    }


    /**
     * A Check atom together with the polarity and entailment of its position in the tree, plus the
     * {@link OperationUsage}s its operands denote — a {@code $}-reference and an inlined
     * operation-operator call land here identically, never as an atom operand.
     */
    private record Positioned(Atom atom, boolean negated, boolean entailed,
            List<OperationUsage> usages)
    {
    }

    /**
     * Flattens the rule's Check tree to its leaves, tracking negation and whether each leaf is
     * <em>entailed</em> — necessarily true whenever the Check fires. A leaf directly under
     * {@code all} is entailed; under {@code any} it is entailed only when that {@code any} has a
     * single branch (the corpus's one-branch {@code any} idiom).
     *
     * @param rule
     *            the rule whose Check to walk; its declared {@code Operations} resolve the
     *            {@code $}-references the walk encounters
     * @return the leaves in document order
     */
    private static List<Positioned> leaves(Rule rule)
    {
        return leaves(rule, true);
    }


    /**
     * The walk behind {@link #leaves(Rule)}, with the operation-usage recognition switchable.
     *
     * <p>
     * The classifier walks <b>operation-aware</b> ({@code operationAware = true}): a
     * {@code $}-reference and an inlined operation-operator call both land in
     * {@link Positioned#usages()} and never as a leaf operand. The recognition-<b>off</b> view (an
     * inlined call contributing its literal operand text) had exactly one consumer, the
     * Python-frame relic {@code frameFit}, deleted by phase 2 of
     * {@code PLAN-leaf-scope-domain-inference.md}; the switch is kept so the walk stays one
     * function, but every production caller passes {@code true}.
     * </p>
     */
    private static List<Positioned> leaves(Rule rule, boolean operationAware)
    {
        List<Positioned> out = new ArrayList<>();
        // ⚑ Plan C §3.3: every declared check level, in ladder order. Sensitivity is a property of
        // the whole rule (one evaluation domain, one grouping), so the derivation reads every
        // level's leaves. One level, and it IS getCheck(), for every rule that authors a plain
        // Check:.
        for (CheckCondition condition : rule.checkConditions())
        {
            collect(toExprOrNull(condition), false, true, operationsById(rule), operationAware,
                    out);
        }
        return out;
    }


    /**
     * Raises a Check of <em>any</em> form to its {@link Expr}, or {@code null} when it has no
     * expression surface.
     *
     * <p>
     * The single front door for derivation. All three shipped corpora reach it: an operator-leaf
     * Check ({@code rules-src}, {@code rules-legacy}) is converted, and an expression Check
     * ({@code rules/} — 100% of it) already carries its parsed {@code Expr}, which
     * {@code CheckToExpr} returns as-is. One traversal therefore serves every corpus; two
     * front-ends that could disagree about the same rule would be worse than the duplication this
     * plan removes.
     * </p>
     *
     * <p>
     * Converted with a {@code null} {@code Rule_Type}, which is <strong>not</strong> circular:
     * {@code CheckToExpr.existsByRuleType} returns null for a null type, so a generic
     * {@code exists} stays generic rather than being guessed at, while the explicit
     * {@code ds_exists} / {@code var_exists} forms convert to themselves through the ordinary
     * unary-predicate path. The 59-rule {@code ds_exists} conversion is what made this possible: it
     * moved the ambiguity out of the data, so the type is no longer needed to read the type's own
     * signal.
     * </p>
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
            // No expression surface (boolean constant, unconvertible leaf): no operands, exactly
            // as an empty Check gave — the caller degrades to Confidence.NONE.
            return null;
        }
    }


    private static void collect(@Nullable Expr expr, boolean negated, boolean entailed,
            Map<String, Operation> ops, boolean operationAware, List<Positioned> out)
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
                collect(part, negated, entailed, ops, operationAware, out);
            }
        }
        case Expr.Or or ->
        {
            boolean single = or.parts().size() == 1;
            for (Expr part : or.parts())
            {
                collect(part, negated, entailed && single, ops, operationAware, out);
            }
        }
        case Expr.Not not -> collect(not.inner(), !negated, entailed, ops, operationAware, out);
        case Expr.Lit _ ->
        {
            // a literal carries no operand
        }
        default -> out.add(atom(expr, negated, entailed, ops, operationAware));
        }
    }


    /**
     * Classifies one {@link Expr} predicate/operand node into an {@link Atom} — operator token,
     * operand name and value — collecting any {@link OperationUsage} its operands denote as it
     * goes. The vocabularies apply unchanged: {@code CheckToExpr} emits the operator as the call
     * name, so the tokens they match on ({@code is_not_unique_set}, {@code has_same_values}, …) are
     * the same in every corpus form.
     */
    private static Positioned atom(Expr expr, boolean negated, boolean entailed,
            Map<String, Operation> ops, boolean operationAware)
    {
        List<OperationUsage> usages = new ArrayList<>(2);
        String operator = null;
        String name = null;
        String value = null;
        boolean valueIsReference = false;
        boolean valueIsLiteral = false;
        switch (expr)
        {
        case Expr.Call call ->
        {
            OperationUsage usage = operationAware ? callUsage(call) : null;
            if (usage != null)
            {
                // an operation call standing alone as a predicate — a boolean operation such as
                // supp_qnam_present(...); the usage is the whole signal, there is no atom operator
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
                name = operandText(args.get(0), ops, usages, operationAware);
            }
            if (args.size() > 1)
            {
                value = operandText(args.get(1), ops, usages, operationAware);
                // a call's second operand is a genuine operand, never the legacy var-or-literal
                // fallback, so it is safe to read as a reference when it names one
                valueIsReference = args.get(1) instanceof Expr.Ref;
                valueIsLiteral = args.get(1) instanceof Expr.Lit;
            }
        }
        case Expr.Binary binary ->
        {
            operator = BIN_OPERATORS.get(binary.op());
            name = operandText(binary.left(), ops, usages, operationAware);
            value = operandText(binary.right(), ops, usages, operationAware);
            valueIsReference = binary.right() instanceof Expr.Ref;
            valueIsLiteral = binary.right() instanceof Expr.Lit;
        }
        case Expr.Ref ref ->
        {
            if (operationAware && isOperationRef(ref.name()))
            {
                usages.add(declaredUsage(ops, ref.name()));
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
        return new Positioned(new Atom(operator, name, value, valueIsReference, valueIsLiteral),
                negated, entailed, usages);
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
    private static @Nullable String operandText(Expr expr, Map<String, Operation> ops,
            List<OperationUsage> usages, boolean operationAware)
    {
        return switch (expr)
        {
        case Expr.Ref ref ->
        {
            if (operationAware && isOperationRef(ref.name()))
            {
                // A $-operation reference is an operation usage, not a frame operand — the same
                // statement about the rule as the inlined call below, and indistinguishable from
                // it by construction.
                usages.add(declaredUsage(ops, ref.name()));
                yield null;
            }
            yield ref.name();
        }
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> String.valueOf(lit.value());
        case Expr.Call call ->
        {
            OperationUsage usage = operationAware ? callUsage(call) : null;
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
            yield operand != null && operand.equals(call.name()) && !namesItsOwnOperand
                    && !call.args().isEmpty()
                            ? operandText(call.args().get(0), ops, usages, operationAware)
                            : operand;
        }
        default -> null;
        };
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


    private static Map<String, Operation> operationsById(Rule rule)
    {
        List<Operation> ops = rule.getOperations();
        if (ops == null || ops.isEmpty())
        {
            return Map.of();
        }
        Map<String, Operation> byId = new java.util.LinkedHashMap<>();
        for (Operation op : ops)
        {
            if (op.getId() != null)
            {
                byId.put(op.getId(), op);
            }
        }
        return byId;
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
            for (OperationUsage usage : p.usages())
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
    private static boolean isStudyLevelUsage(OperationUsage usage)
    {
        return usage.operator() != null && usage.group().isEmpty()
                && StudyRuleClassifier.isStudyLevelOperator(usage.operator());
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
        if (operator != null && (DATASET_PRESENCE.contains(operator)
                || VARIABLE_PRESENCE.contains(operator) || BROADCAST_OPERATORS.contains(operator)))
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
        for (OperationUsage usage : p.usages())
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
    private static String operatorName(OperationUsage usage)
    {
        return usage.operator() == null ? "(unresolved)" : usage.operator();
    }

}
