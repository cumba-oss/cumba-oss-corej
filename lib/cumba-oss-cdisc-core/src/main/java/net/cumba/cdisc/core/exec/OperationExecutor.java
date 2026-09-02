package net.cumba.cdisc.core.exec;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import lombok.CustomLog;
import net.cumba.cdisc.core.expr.eval.IsoDateBounds;
import net.cumba.cdisc.core.gen.WildcardExpander;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

@CustomLog
public final class OperationExecutor
{

    private static final String LIBRARY_PROVIDER_UNAVAILABLE_MSG = "[{0}] Library provider not available for operation {1} — rule will be skipped";

    private static final String LIBRARY_DEGRADED_MSG = "[{0}] {1}: the CDISC Library could not be consulted — rule will be skipped rather than answered from a non-library source";

    private static final String LIBRARY_DEGRADED_NO_DEFINE_MSG = "[{0}] {1}: define fallback requested but the study metadata is not Define-XML backed — rule will be skipped";

    private static final String LIBRARY_DEGRADED_DEFINE_EMPTY_MSG = "[{0}] {1}: define fallback engaged but the Define-XML could not answer this operation — rule will be skipped";

    /**
     * Fix #369 — system property enabling the Define-XML substitution on a degraded run. Opt-in,
     * <b>default off</b>; see {@link #defineFallbackPreference()}.
     */
    public static final String DEGRADED_DEFINE_FALLBACK_PROPERTY = "corej.degradedDefineFallback";

    private static final String USUBJID = "USUBJID";

    private static final String SDTMCT = "sdtmct";

    /**
     * Domain wildcard meaning "no single source dataset — scan the whole study inventory". Used by
     * {@code cross_dataset_variable_metadata} (e.g. CDISC-AD0002/0199) where the SDTM variable to
     * compare against may live in any submitted dataset.
     */
    private static final String WILDCARD_DOMAIN = "*";

    /**
     * EC-8 — a decimal numeric literal (optionally signed, with an optional fraction and scientific
     * exponent). Used by {@code row_max}/{@code row_min} to decide numeric-vs-lexicographic
     * ordering mode. Deliberately narrower than {@link Double#parseDouble} (which also accepts hex
     * floats, {@code d}/{@code f} suffixes, {@code Infinity} and {@code NaN}) so the numeric-mode
     * gate stays in lock-step with the Python reference engine's {@code float(...)} — a value like
     * an ISO date ({@code "2020-01-01"}) or a suffixed token falls to string mode identically in
     * both lanes.
     */
    private static final Pattern ROW_EXTREME_NUMERIC = Pattern
            .compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /**
     * Sentinel value indicating that an operation was skipped due to missing Library provider. When
     * this value is present in the variables map, the rule should be reported as SKIPPED.
     */
    static final Object LIBRARY_NOT_AVAILABLE = new Object()
    {

        @Override
        public String toString()
        {
            return "<library not available>";
        }
    };

    private OperationExecutor()
    {
    }


    public static Map<String, Object> execute(List<Operation> operations, IDataTable table,
            DatasetResolver resolver)
    {
        return execute(operations, table, resolver, null);
    }


    public static Map<String, Object> execute(List<Operation> operations, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider)
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (Operation op : operations)
        {
            Object result = executeOne(op, table, resolver, libraryProvider, variables);
            if (result != null && op.getId() != null)
            {
                variables.put(op.getId(), result);
            }
        }
        return variables;
    }


    /**
     * Backwards-compatible overload — see
     * {@link #executeOne(Operation, IDataTable, DatasetResolver, MetadataProvider, Map, String)}.
     * {@code ruleId} defaults to {@code null}; diagnostic log lines from this Operation will render
     * with {@code [?]} for the rule context. Used by tests and the legacy multi-op driver where
     * rule context isn't available.
     */
    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults)
    {
        return executeOne(op, table, resolver, libraryProvider, priorResults, null);
    }


    /**
     * Single-Operation entry point used by {@link RuleRunner}'s lazy wrapper (Fix #36). The caller
     * supplies the prior-Operations result map (with all referenced {@link LazyValue}s already
     * forced) so {@link #expandGroupRefs} can resolve {@code $variable} references in this
     * Operation's {@code group} list.
     *
     * <p>
     * Returns {@code null} when the Operation is skipped (unknown type). Q17-a: a <em>missing
     * target dataset</em> is no longer a skip — it yields the operator's declared
     * {@link net.cumba.cdisc.core.model.EmptyResult} value ({@code 0L} for a count, {@code false}
     * for a predicate, {@code List.of()} for a set, {@code null} for the {@code MISSING} codomain),
     * so every operation is total over an absent {@code domain}. Returns
     * {@link #LIBRARY_NOT_AVAILABLE} when a library-dependent Operation is invoked without a
     * provider — the caller decides how to surface that.
     * </p>
     *
     * @param ruleId
     *            CORE id of the rule whose Operations are being evaluated, used as a leading
     *            {@code [<ruleId>]} prefix on every diagnostic emitted from this dispatch.
     *            {@code null} renders as {@code [?]}.
     */
    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults, @Nullable String ruleId)
    {
        return executeOne(op, table, resolver, libraryProvider, priorResults, ruleId, null);
    }


    /**
     * T1 overload carrying the runtime {@link RuntimeDictionaryProvider} for the
     * external-dictionary operations ({@code valid_external_dictionary_*},
     * {@code dictionary_available}). All other behaviour is identical to
     * {@link #executeOne(Operation, IDataTable, DatasetResolver, MetadataProvider, Map, String)};
     * the dictionary provider is {@code null} on every non-dictionary path.
     */
    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults, @Nullable String ruleId,
            @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        return executeOne(op, table, resolver, libraryProvider, priorResults, ruleId,
                dictionaryProvider, null);
    }


    /**
     * T2-residual overload carrying the sponsor {@link MetadataProvider} Define-XML overlay for the
     * define-set operations ({@code define_variable_names}, {@code define_key_variables}). All
     * other behaviour is identical to
     * {@link #executeOne(Operation, IDataTable, DatasetResolver, MetadataProvider, Map, String, RuntimeDictionaryProvider)};
     * the define provider is {@code null} on every non-define path (and the two define operations
     * return {@code null} — an unresolvable result — when it is {@code null}, so the caller SKIPs
     * the rule; see {@link #isDefineDependent}).
     */
    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults, @Nullable String ruleId,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable MetadataProvider defineProvider)
    {
        OperationType type = op.getOperationType();
        if (type == null)
        {
            LOGGER.log(System.Logger.Level.WARNING, "[{0}] Unknown operation type: {1} (id={2})",
                    ruleId != null ? ruleId : "?", op.getOperator(), op.getId());
            return null;
        }
        // minus (set difference) operates purely on prior operation results referenced via
        // name/subtract — it needs no source dataset. Resolve it before targetTable so the
        // missing-target skip below doesn't apply, mirroring Python's operations/minus.py which
        // reads only prior $-refs.
        if (type == OperationType.MINUS)
        {
            return evalMinus(op, priorResults);
        }
        // variable_exists answers a question ABOUT a dataset, so an absent `domain` dataset is a
        // legitimate `false` — not a reason to skip. Resolving it before targetTable is what keeps
        // it total: the missing-target skip would otherwise leave the $-result null, and the
        // dotted var_exists("D.X") function this operation must agree with answers false there.
        //
        // ⚠ Q17-a does NOT make this short-circuit redundant, though its EmptyResult (PREDICATE =
        // false) does now coincide with the absent-domain answer below. The short-circuit is the
        // ONLY evaluator for variable_exists — `dispatch` has no VARIABLE_EXISTS arm, so a
        // RESOLVABLE domain would fall through to the default arm and yield null. It also composes
        // the "DOMAIN.NAME" column itself and delegates to OperatorRegistry.existsAsVariable (the
        // SUPP-QNAM pivot, the lower-case-domain shape), which the absent-domain path cannot do.
        // Removing it reddens OperationExecutorVariableExistsTest's resolvable-domain cases.
        if (type == OperationType.VARIABLE_EXISTS)
        {
            return evalVariableExists(op, table, resolver);
        }
        IDataTable targetTable = resolveTargetTable(op, table, resolver, ruleId);
        if (targetTable == null)
        {
            // cross_dataset_variable_metadata with the "*" wildcard has no single source
            // dataset to resolve — its handler (VariableMetadataResult.buildFromAllDatasets)
            // scans the whole inventory itself. resolveTargetTable would otherwise resolve "*"
            // to null (no dataset is named "*") and skip the operation, leaving the $-result
            // absent so the rule could never fire. Bypass the missing-target skip and dispatch
            // with the primary table; the CROSS_DATASET_VARIABLE_METADATA case ignores it and
            // re-reads the "*" domain off the operation.
            if (type == OperationType.CROSS_DATASET_VARIABLE_METADATA
                    && WILDCARD_DOMAIN.equals(op.getDomain()))
            {
                targetTable = table;
            }
            else
            {
                // Q17-a — an absent `domain` dataset is answered with the operator's OWN declared
                // EmptyResult, not with an unclassified null. EmptyResult is "the value an
                // OperationType publishes when it has no answer for a row" and an absent dataset
                // is precisely that: record_count over a dataset that is not in the study counted
                // ZERO records, supp_qnam_present found no such QNAM, distinct matched nothing.
                // Deciding it here, per type, keeps every operation TOTAL — applicability is
                // scope's job (Scope.Variables / the qualified DATASET.VARIABLE form), never the
                // algebra's, so a rule that should not have run is SKIPped rather than silenced
                // inside a leaf by a null that folds to "no value" at one consumer and to the
                // empty set at another.
                //
                // ⚠ MISSING-typed operators (max_date, min_date, max, ts_parameter_value,
                // cross_dataset_variable_metadata, date_diff_days) declare a null value, so they
                // return exactly what they returned before — this is a behaviour change only for
                // the COUNT / PREDICATE / SET / EMPTY_TEXT codomains.
                //
                // ⚠ NOT a `missing_values` (EC-51) concern: that governs missing INPUT values
                // inside a dataset that IS present, not a dataset the study does not contain.
                return OperationType.emptyValueOf(type);
            }
        }
        // Expand any {@code $-variable} references in the operation's group list using
        // results from prior operations. E.g. {@code group: [USUBJID, --TESTCD,
        // $TIMING_VARIABLES]} where {@code $TIMING_VARIABLES = [VSDTC]} becomes
        // {@code [USUBJID, VSTESTCD, VSDTC]}. Necessary for rules like CORE-001034 that
        // feed a {@code get_dataset_filtered_variables} result into {@code record_count}'s
        // grouping.
        Operation runOp = expandGroupRefs(op, priorResults);
        return dispatch(type, runOp, targetTable, resolver, libraryProvider, ruleId,
                dictionaryProvider, defineProvider);
    }


    /**
     * Returns whether the given Operation requires a {@link MetadataProvider} — i.e., would return
     * {@link #LIBRARY_NOT_AVAILABLE} when invoked without one. Used by {@link RuleRunner}'s
     * phase-2a lazy wrapper (Fix #36) to keep the early-skip behaviour: if any op in the rule is
     * library-dependent and no provider is configured, the rule is reported SKIPPED before any
     * Operation supplier runs.
     */
    public static boolean isLibraryDependent(@Nullable OperationType type)
    {
        if (type == null)
        {
            return false;
        }
        return switch (type)
        {
        case REQUIRED_VARIABLES, EXPECTED_VARIABLES, GET_COLUMN_ORDER_FROM_LIBRARY, GET_MODEL_COLUMN_ORDER, GET_PARENT_MODEL_COLUMN_ORDER, VARIABLE_NAMES, STANDARD_DOMAINS, GET_DATASET_FILTERED_VARIABLES, NATURAL_KEY_VARIABLES, GET_MODEL_FILTERED_VARIABLES, VALID_CODELIST_DATES, DATASET_CLASS_FROM_LIBRARY, REFERENCED_DOMAIN_CLASS, DOMAIN_IS_CUSTOM, CODELIST_TERMS, GET_CODELIST_ATTRIBUTES -> true;
        default -> false;
        };
    }


    /**
     * T1 — whether the operation validates against a runtime external dictionary
     * ({@code valid_external_dictionary_*} / {@code dictionary_available}). Such operations SKIP
     * (never false-PASS) when no dictionary of their {@code external_dictionary_type} is loaded: an
     * <b>inlined</b> call is gated by the {@code dictionary_available(<type>)} precondition the
     * native converter injects for it, a <b>declared</b> ({@code $}-ref) one by
     * {@link RuleRunner}'s eager dictionary arm ({@code Fix #268}), which is the path the whole
     * shipped corpus takes. Kept distinct from {@link #isLibraryDependent} so a dictionary rule is
     * not spuriously skipped merely because no CDISC-Library provider is configured.
     *
     * <p>
     * &#9888; This predicate answers "is this operation dictionary-backed", not "must the rule skip
     * when the dictionary is absent". {@link OperationType#DICTIONARY_AVAILABLE} is a member and
     * must be <b>excluded</b> by any eager-skip caller — it is the gate itself and returns a
     * well-defined {@code false} with no provider, whereas the validating operations return
     * {@code null}. {@link RuleRunner} excludes it explicitly.
     * </p>
     */
    public static boolean isDictionaryDependent(@Nullable OperationType type)
    {
        if (type == null)
        {
            return false;
        }
        return switch (type)
        {
        case DICTIONARY_AVAILABLE, VALID_EXTERNAL_DICTIONARY_VALUE, VALID_EXTERNAL_DICTIONARY_CODE, VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR, VALID_EXTERNAL_DICTIONARY_HIERARCHY, DICTIONARY_HAS_DECODE -> true;
        default -> false;
        };
    }


    /**
     * T2-residual — whether the operation reads the sponsor Define-XML overlay
     * ({@code define_variable_names} / {@code define_key_variables}). Such operations SKIP (never
     * PASS/FAIL) when no Define-XML is supplied: {@link RuleRunner} reports the rule SKIPPED before
     * any supplier fires when {@code defineProvider == null} and any operation is define-dependent,
     * the same input-availability discipline as {@link #isLibraryDependent}. Kept distinct from
     * {@code isLibraryDependent} so these operations are neither spuriously skipped for a missing
     * CDISC Library nor inlined to a native function (they stay {@code $}-ref Operations — see
     * {@code OperationInliner.isEngineReady}).
     */
    public static boolean isDefineDependent(@Nullable OperationType type)
    {
        if (type == null)
        {
            return false;
        }
        return switch (type)
        {
        case DEFINE_VARIABLE_NAMES, DEFINE_DATASET_NAMES, DEFINE_KEY_VARIABLES -> true;
        default -> false;
        };
    }


    /**
     * Whether {@code type} is a <em>boolean-valued</em> operation — its {@code executeOne} arm
     * returns a scalar {@link Boolean}. Determined by auditing the {@code dispatch} switch (plus
     * the {@code VARIABLE_EXISTS} bypass that runs ahead of it): {@code DOMAIN_IS_CUSTOM} (via the
     * library {@code isDomainCustom} call), {@code VARIABLE_IS_NULL} and {@code VARIABLE_EXISTS}
     * return a Boolean; every other operation returns a number, string, list,
     * {@code GroupedResult}, or metadata object. ⚠ {@code VARIABLE_EXISTS} is listed here for
     * completeness of the audit, <b>not</b> as an invitation to route verdicts through it — column
     * existence is decided by the {@code var_exists(X)} / {@code var_exists("D.X")} function and
     * the operation only carries the answer into {@code Output_Variables}; see
     * {@code plans/done/PLAN-variable-exists-cross-dataset.md} for why the verdict lives on the
     * function.
     *
     * <p>
     * Used by the native compiler ({@code ExprCompiler.compileBoolCall}) to admit an inline boolean
     * operation to <b>boolean position</b>, bridging its broadcast result to a verdict
     * {@code BitSet}. ⚠ That gate ({@code ExprCompiler.isUnifiableBooleanOperation}) is this
     * predicate <em>minus</em> {@link #isLibraryDependent}, so it admits {@code VARIABLE_IS_NULL}
     * and {@code VARIABLE_EXISTS} and excludes {@code DOMAIN_IS_CUSTOM} (library-dependent: its
     * {@code LIBRARY_NOT_AVAILABLE} sentinel under a {@code not}/{@code invert} would mis-fire
     * every row). ⚠⚠ This paragraph used to say the gate "currently admits no inline operation";
     * that was already false when written — {@code VARIABLE_IS_NULL} has qualified since T5a.
     * </p>
     *
     * @param type
     *            the operation type (may be {@code null})
     * @return {@code true} iff the operation returns a scalar boolean
     */
    public static boolean isBooleanValued(@Nullable OperationType type)
    {
        return type == OperationType.DOMAIN_IS_CUSTOM || type == OperationType.VARIABLE_IS_NULL
                || type == OperationType.VARIABLE_EXISTS;
    }

    /**
     * The shape of an operation's <em>runtime value</em> — what {@code BroadcastFold} and the
     * {@code RuleRunner} routing read off the materialised {@code $}-variable by
     * {@code instanceof}: a per-row {@link GroupedResult}, a per-variable
     * {@link VariableMetadataResult}, or anything else (a scalar, list or set, broadcast-constant
     * over the dataset).
     */
    public enum ResultKind
    {
        /** A dataset-constant value: scalar, list, set, sentinel or {@code null}. */
        SCALAR,
        /** A {@link VariableMetadataResult} — one value per variable of the dataset. */
        PER_VARIABLE,
        /** A {@link GroupedResult} — one value per row (or per group key) of the dataset. */
        PER_ROW
    }

    /**
     * Operations whose evaluator returns a {@link GroupedResult} <b>unconditionally</b> — with or
     * without a {@code group} keyword. Grounded from {@link #dispatch}: every {@code eval*} arm
     * reached from these constants is declared to return {@code GroupedResult}, or builds one
     * through {@code declaredGrouped}.
     */
    private static final Set<OperationType> ALWAYS_PER_ROW = Set.of(OperationType.DY,
            OperationType.HAS_MIXED_EMPTINESS_WITHIN_GROUP,
            OperationType.VALID_EXTERNAL_DICTIONARY_VALUE,
            OperationType.VALID_EXTERNAL_DICTIONARY_CODE,
            OperationType.VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR,
            OperationType.VALID_EXTERNAL_DICTIONARY_HIERARCHY, OperationType.DICTIONARY_HAS_DECODE,
            OperationType.INTERVAL_UNCERTAINTY_PRECISION_MISMATCH, OperationType.DATE_DIFF_DAYS,
            OperationType.IS_LAST_IN_GROUP, OperationType.ROW_MAX, OperationType.ROW_MIN,
            OperationType.SUPP_QNAM_PRESENT, OperationType.SUPP_QNAM_VALUE,
            OperationType.REFERENCED_DOMAIN_CLASS,
            // evalParentModelColumnOrder builds a GroupedResult keyed on RDOMAIN unconditionally
            // (review finding 3 of the leaf-scope plan, 2026-08-22).
            OperationType.GET_PARENT_MODEL_COLUMN_ORDER);

    /**
     * Operations whose evaluator returns a {@link GroupedResult} <b>only when grouped</b> (a
     * non-empty {@code group} list) and a scalar / list otherwise — the {@code grouped ? … : …}
     * arms of {@link #dispatch}.
     */
    private static final Set<OperationType> PER_ROW_WHEN_GROUPED = Set.of(
            OperationType.RECORD_COUNT, OperationType.DISTINCT, OperationType.MAX,
            OperationType.MAX_DATE, OperationType.MIN_DATE);

    /**
     * The {@link ResultKind} {@link #executeOne} would produce for {@code op}, decided
     * <em>statically</em> from the operation's type and shape — so the same classification the
     * runtime routing derives from the materialised value ({@code instanceof GroupedResult} /
     * {@code instanceof VariableMetadataResult}) is available at load time, before any dataset
     * exists. This is what {@code DomainScan} reads to give a {@code $}-reference its cursor
     * demand.
     *
     * <p>
     * <b>Single source.</b> The two sets above transcribe {@link #dispatch}; a new operation whose
     * evaluator returns a {@link GroupedResult} must be added here, and
     * {@code OperationResultKindTest} executes every constant to keep the transcription honest.
     * </p>
     *
     * @param op
     *            a declared ({@code Operations} entry) or inline (Form A) operation; a Form-B
     *            {@code expression} declaration must be normalised first
     * @return the runtime value shape; {@link ResultKind#SCALAR} for an unknown operator (the load
     *         guards reject it separately)
     */
    public static ResultKind resultKind(Operation op)
    {
        OperationType type = op.getOperationType();
        if (type == null)
        {
            return ResultKind.SCALAR;
        }
        if (type == OperationType.CROSS_DATASET_VARIABLE_METADATA)
        {
            return ResultKind.PER_VARIABLE;
        }
        if (ALWAYS_PER_ROW.contains(type))
        {
            return ResultKind.PER_ROW;
        }
        List<String> group = op.getGroup();
        boolean grouped = group != null && !group.isEmpty();
        if (grouped && PER_ROW_WHEN_GROUPED.contains(type))
        {
            return ResultKind.PER_ROW;
        }
        // distinct(VAR, value_is_reference=true) resolves per row (evalDistinctVariableNames)
        // even ungrouped.
        if (type == OperationType.DISTINCT && Boolean.TRUE.equals(op.getValueIsReference()))
        {
            return ResultKind.PER_ROW;
        }
        return ResultKind.SCALAR;
    }


    /**
     * Whether an operation {@code result} is usable — not {@code null}, not the
     * {@link #LIBRARY_NOT_AVAILABLE} skip sentinel, and not an empty list (an unresolved codelist /
     * model lookup). The {@code available(<op>)} builtin (§9.C) folds this to the Precondition
     * skip-gate so an inlined library operation skips the rule (rather than passing) when the
     * Library cannot answer.
     *
     * @param result
     *            an {@link #executeOne} result
     * @return {@code true} iff the operation resolved to a usable value
     */
    public static boolean isResultAvailable(@Nullable Object result)
    {
        return result != null && result != LIBRARY_NOT_AVAILABLE
                && !(result instanceof java.util.Collection<?> c && c.isEmpty());
    }


    /**
     * Returns either the original operation (when no expansion is needed) or a copy with its
     * {@code group} list rewritten to resolve any {@code $}-variable references against the given
     * variables map. A {@code $}-reference that is absent, scalar, or neither a string nor a
     * collection of strings is passed through unchanged so the downstream grouping code surfaces
     * the failure in its usual way (missing-column error, etc.).
     */
    private static Operation expandGroupRefs(Operation op, Map<String, Object> variables)
    {
        List<String> group = op.getGroup();
        if (group == null || group.isEmpty())
        {
            return op;
        }
        boolean needsExpand = false;
        for (String g : group)
        {
            if (g != null && g.startsWith("$") && variables.containsKey(g))
            {
                needsExpand = true;
                break;
            }
        }
        if (!needsExpand)
        {
            return op;
        }
        List<String> expanded = new ArrayList<>(group.size());
        for (String g : group)
        {
            if (g == null || !g.startsWith("$"))
            {
                expanded.add(g);
                continue;
            }
            Object val = variables.get(g);
            switch (val)
            {
            case List<?> list ->
            {
                for (Object item : list)
                {
                    if (item != null)
                    {
                        expanded.add(item.toString());
                    }
                }
            }
            case java.util.Collection<?> col ->
            {
                for (Object item : col)
                {
                    if (item != null)
                    {
                        expanded.add(item.toString());
                    }
                }
            }
            case String s -> expanded.add(s);
            case null, default ->
                    // Unresolved ($-variable absent) or unrecognised shape — leave as-is so the
                    // downstream path can report a meaningful error rather than silently mangling
                    // the grouping. The explicit null case avoids a pattern-switch NPE.
                    expanded.add(g);
            }
        }
        // Full copy of EVERY {@link Operation} field except {@code group} (which is replaced by the
        // expanded list). The order below mirrors the field declaration order in
        // {@code model/Operation.java} one-to-one so that a future field addition is easy to audit
        // in — if a field is added to {@code Operation} and NOT mirrored here it would be silently
        // dropped on any op whose {@code group} carries a {@code $}-ref, so keep the two in sync.
        Operation copy = new Operation();
        copy.setId(op.getId());
        copy.setOperator(op.getOperator());
        copy.setExpression(op.getExpression());
        copy.setName(op.getName());
        copy.setNames(op.getNames());
        copy.setSubtract(op.getSubtract());
        copy.setValue(op.getValue());
        copy.setDomain(op.getDomain());
        copy.setReference(op.getReference());
        copy.setDelimiter(op.getDelimiter());
        copy.setGroup(expanded);
        copy.setOffset(op.getOffset());
        copy.setReferenceExtreme(op.getReferenceExtreme());
        // EC-51 Half B — the missing-candidate disposition. Dropping it here would silently revert
        // an `indeterminate` grouped extreme to `skip` on exactly the operations that carry a
        // `$`-ref group, i.e. the ones this field was added for.
        copy.setMissingValues(op.getMissingValues());
        copy.setKeepMissings(op.getKeepMissings());
        // EC-18 / P5c — date_diff_days Mode 3 foreign-minuend fields (copied verbatim; the
        // minuend_match `--` tokens are resolved per-side at evaluation time, not here).
        copy.setMinuendDomain(op.getMinuendDomain());
        copy.setMinuendMatch(op.getMinuendMatch());
        copy.setOrdering(op.getOrdering());
        copy.setFilter(op.getFilter());
        copy.setCodelists(op.getCodelists());
        copy.setLevel(op.getLevel());
        copy.setReturntype(op.getReturntype());
        copy.setKeyName(op.getKeyName());
        copy.setKeyValue(op.getKeyValue());
        copy.setModelClass(op.getModelClass());
        copy.setCtAttribute(op.getCtAttribute());
        copy.setVersion(op.getVersion());
        copy.setCtPackageTypes(op.getCtPackageTypes());
        copy.setRegex(op.getRegex());
        copy.setNamePattern(op.getNamePattern());
        copy.setValueIsReference(op.getValueIsReference());
        copy.setMinLength(op.getMinLength());
        copy.setExternalDictionaryType(op.getExternalDictionaryType());
        copy.setDictionaryTermType(op.getDictionaryTermType());
        copy.setCaseSensitive(op.getCaseSensitive());
        copy.setExternalDictionaryTermVariable(op.getExternalDictionaryTermVariable());
        copy.setDictionaryParent(op.getDictionaryParent());
        copy.setQualifyingAnyPopulated(op.getQualifyingAnyPopulated());
        copy.setOriginalName(op.getOriginalName());
        return copy;
    }


    private static @Nullable IDataTable resolveTargetTable(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable String ruleId)
    {
        // E3 date_diff_days keeps the primary (rule) dataset as its target: its {@code name}
        // minuend date column lives there, and the {@code domain} names only the FOREIGN reference
        // dataset it looks up itself via the resolver (grouped-min). Redirecting the target to that
        // domain would hide the minuend column and skip the operation. Mirrors the Python op, which
        // reads params.dataframe (primary) and loads the foreign domain via data_service.
        if (op.getOperationType() == OperationType.DATE_DIFF_DAYS)
        {
            return table;
        }
        if (op.getDomain() == null || op.getDomain().isEmpty())
        {
            return table;
        }
        // op.getDomain() is non-null here, so resolveWildcard (poly-null) returns non-null.
        String domain = Objects.requireNonNull(resolveWildcard(op.getDomain(), table));
        IDataTable resolved = resolver.resolve(domain);
        if (resolved == null)
        {
            // J7 (split self-reference): a SUPP--/SQAP-- operation domain whose wildcard collapsed
            // to the unsplit family name (e.g. "SUPP--" -> "SUPPLB") has no standalone dataset when
            // the SUPP/SQAP itself is split (supplbch/lbhe/lbur). Such an operation is
            // self-referential — it targets the SUPP/SQAP dataset being validated — so run it
            // against the current table when that table is a split member of the named family. This
            // lets CORE-000712's value_is_reference distinct ($rdomain_variables) read its own
            // RDOMAIN column instead of being skipped to an empty membership set (which fired every
            // row). Restricted to SUPP/SQAP current tables so a literal cross-domain reference
            // whose
            // name merely prefixes a split member (e.g. domain "LB" while validating "LBCH") is NOT
            // redirected and stays correctly skipped.
            String currentName = table.getMetaData().getName();
            if (currentName != null)
            {
                String upper = currentName.toUpperCase(Locale.ROOT);
                if ((upper.startsWith("SUPP") || upper.startsWith("SQAP"))
                        && upper.startsWith(domain.toUpperCase(Locale.ROOT)))
                {
                    return table;
                }
            }
            LOGGER.log(System.Logger.Level.DEBUG,
                    "[{0}] Domain {1} not available for operation {2}",
                    ruleId != null ? ruleId : "?", domain, op.getId());
        }
        return resolved;
    }


    /**
     * Replaces the CDISC {@code --} wildcard with the target table's domain prefix. For example,
     * {@code "SUPP--"} becomes {@code "SUPPAE"} when the target table is named {@code "AE"}.
     * <p>
     * Fix #33: when the target table is itself a SUPP/SQAP primary (e.g. {@code "SUPPAE"}), the
     * {@code --} in patterns like {@code "SUPP--"} refers to the parent-domain prefix, not the full
     * primary name. Substituting with the full name would produce {@code "SUPPSUPPAE"} and the
     * downstream resolver would fail. Derive the parent prefix via
     * {@link SplitDatasetUtil#unsplitName(String)} applied to the {@code tableName.substring(4)}.
     * Non-SUPP/SQAP primaries retain the original substitution behaviour.
     * </p>
     */
    static @Nullable String resolveWildcard(@Nullable String domain, IDataTable table)
    {
        if (domain == null || !domain.contains("--"))
        {
            return domain;
        }
        String tableName = table.getMetaData().getName();
        if (tableName == null || tableName.isEmpty())
        {
            return domain;
        }
        String substitutionPrefix = tableName;
        if ((tableName.startsWith("SUPP") || tableName.startsWith("SQAP"))
                && tableName.length() > 4)
        {
            substitutionPrefix = SplitDatasetUtil.unsplitName(tableName.substring(4));
        }
        return domain.replace("--", substitutionPrefix);
    }


    /**
     * Returns the dataset's CDISC domain code (the row-0 {@code DOMAIN} value, else the unsplit
     * table name). This is the dataset's <em>identity</em>, used for {@code --} in an Operation's
     * {@code domain:} and for the injected {@code DOMAIN} value — <b>not</b> the variable-name
     * replacement, which is {@link #variableWildcardPrefix} (EC-36). It formerly claimed to mirror
     * Python's {@code wildcard_replacement}; that claim belongs to the sibling. Prefers the
     * first-row {@code DOMAIN} column value (authoritative for split datasets where the table name
     * carries a suffix); falls back to the unsplit table name via
     * {@link SplitDatasetUtil#unsplitName(String)} (e.g. {@code "LB1"} → {@code "LB"}).
     * <p>
     * Used by {@code --} template re-resolution (Fix #1 {@code variable_count}), library-metadata
     * operations (Fixes #2/#3), RELREC per-row {@code **} resolution (Fix #5), and the Child-match
     * pre-merger (Fix #6). Public (Phase 4, PLAN-extend-expression-engine) so
     * {@link net.cumba.cdisc.core.gen.RuleGenerator} can derive the prefix for resolving {@code --}
     * placeholders in {@code Scope.Variables} entries at generation time with the same semantics as
     * execution-time resolution.
     * </p>
     *
     * @param table
     *            the dataset (must be non-null)
     * @return the domain prefix, or {@code ""} when no prefix can be derived
     */
    public static String domainPrefix(IDataTable table)
    {
        if (table == null)
        {
            return "";
        }
        String domainVal = firstRowValue(table, "DOMAIN");
        if (domainVal != null)
        {
            return domainVal;
        }
        String name = table.getMetaData().getName();
        if (name == null || name.isEmpty())
        {
            return "";
        }
        return SplitDatasetUtil.unsplitName(name);
    }


    /**
     * Python's {@code SDTMDatasetMetadata.ap_suffix}: the 2-character parent-domain suffix of an
     * Associated Persons dataset ({@code APMH} &rarr; {@code MH}), or {@code ""} when the dataset
     * is not an AP dataset.
     * <p>
     * The predicate mirrors Python's {@code is_ap} + {@code ap_suffix} pair exactly: an
     * {@code APID} column must be present ({@code "APID" in first_record}), the {@code DOMAIN}
     * value must be at least 4 characters and start with {@code AP}, and SUPP/SQ datasets return
     * {@code ""} unconditionally.
     * </p>
     * <p>
     * {@code LibraryValidator.classNameFor} computes the same Python {@code ap_suffix} to inherit
     * an AP dataset's class from its parent domain. The two are deliberately <em>not</em> shared:
     * that one gates on the {@code DOMAIN} <em>column</em> and reads an already-resolved CDISC
     * domain, this one gates on a non-empty row-0 {@code DOMAIN} <em>value</em>. Unifying them
     * would change which class an AP dataset inherits — a {@code Scope.Classes}-wide blast radius
     * unrelated to EC-36. If either is edited, re-check the other.
     * </p>
     */
    static String apSuffixOf(IDataTable table, @Nullable String domainCode)
    {
        if (domainCode == null || domainCode.length() < AP_DOMAIN_MIN_LENGTH
                || !domainCode.toUpperCase(Locale.ROOT).startsWith("AP")
                || isSuppOrSqName(domainCode))
        {
            return "";
        }
        // Python is_ap (non-supp) is exactly `"APID" in first_record`. Java additionally requires
        // the domain code to look like an AP code (the checks above) — a DELIBERATE deviation:
        // upstream chops the leading two characters off ANY >=4-character domain that happens to
        // carry an APID column, so `POOLDEF` + APID yields "OLDEF" there. Recorded in EC-36.
        return datasetColumnNames(table).contains("APID") ? domainCode.substring(2) : "";
    }

    /**
     * Minimum {@code DOMAIN} length for an AP suffix to exist — Python's {@code len(domain) >= 4}.
     */
    private static final int AP_DOMAIN_MIN_LENGTH = 4;

    /** SUPP / SQ supplemental-qualifier datasets, by name prefix. */
    private static boolean isSuppOrSqName(String name)
    {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQ");
    }


    /**
     * Returns the prefix that substitutes {@code --} in a <em>variable name</em>, derived from the
     * caller-supplied {@code domainCode}.
     *
     * <p>
     * <b>The domain code stays the source of truth.</b> For every dataset except AP and SUPP/SQ
     * this returns {@code domainCode} unchanged, so EC-36 alters nothing outside the two families
     * it was scoped to fix. An earlier revision re-derived the prefix from the table's row-0
     * {@code DOMAIN} cell instead; that silently changed the answer for <em>every</em> dataset
     * whose {@code DOMAIN} value disagrees with its identity — precisely the corruption
     * {@code CORE-000015} exists to detect — making rules resolve to columns that cannot exist and
     * report "no finding". It failed <em>open</em>, and {@code SdtmAllRuleTest.CORE_000544_invalid}
     * caught it.
     * </p>
     *
     * <p>
     * The two deviations, mirroring Python's {@code wildcard_replacement}
     * ({@code ap_suffix or domain or ""}):
     * </p>
     * <ul>
     * <li><b>AP</b> — an AP dataset carries <em>parent-prefixed</em> variables ({@code APMH} holds
     * {@code MHTERM}, not {@code APMHTERM}), so the replacement is the AP suffix
     * ({@code domainCode.substring(2)}), gated on an {@code APID} column.</li>
     * <li><b>SUPP/SQ</b> — the replacement is {@code ""}, so {@code --QNAM} resolves to
     * {@code QNAM}, the column that actually exists. Contrast job (a): {@code --} in an Operation's
     * {@code domain:} is a <em>dataset-name</em> wildcard and keeps the full code (Fix
     * #59/#33).</li>
     * </ul>
     *
     * <p>
     * Returns {@code null} only when {@code domainCode} is itself {@code null} — a degraded or
     * synthetic context with no domain at all. Callers substitute nothing in that case, exactly as
     * they did before EC-36.
     * </p>
     *
     * @param table
     *            the dataset, consulted only for the {@code APID} column (may be {@code null})
     * @param domainCode
     *            the caller-supplied CDISC domain code — the same value used for job (a)
     * @return the variable-wildcard prefix, or {@code null} when {@code domainCode} is {@code null}
     */
    public static @Nullable String variableWildcardPrefix(@Nullable IDataTable table,
            @Nullable String domainCode)
    {
        if (domainCode == null)
        {
            return null;
        }
        if (isSuppOrSqName(domainCode))
        {
            return "";
        }
        if (table != null)
        {
            String apSuffix = apSuffixOf(table, domainCode);
            if (!apSuffix.isEmpty())
            {
                return apSuffix;
            }
        }
        return domainCode;
    }


    /**
     * Returns the canonical unsplit (base) name of a dataset, mirroring Python's
     * {@code SDTMDatasetMetadata.unsplit_name} — the <em>data-driven</em> split-detection key used
     * by scope matching and split-family dedup. Unlike {@link SplitDatasetUtil#unsplitName(String)}
     * (which guesses the base from the name alone), this reads the dataset's {@code DOMAIN} column
     * (row 0): a dataset named {@code FAAE} carrying {@code DOMAIN=FA} resolves to {@code FA}, so
     * it is correctly recognised as a split of FA. Resolution order, faithful to Python:
     * <ol>
     * <li>row-0 {@code DOMAIN} value, when present and non-empty (Python
     * {@code if self.domain});</li>
     * <li>for SUPP/SQ datasets with no {@code DOMAIN} value, the base is reconstructed as
     * {@code SUPP}/{@code SQ} + the row-0 {@code RDOMAIN} value; when {@code RDOMAIN} is absent the
     * raw name is returned (treated as not-split — this guards against Python's literal
     * {@code "SUPPNone"} quirk for a SUPP dataset with no resolvable parent);</li>
     * <li>otherwise the raw name (Python {@code return self.name}).</li>
     * </ol>
     * The final fallback is the raw name, <em>not</em>
     * {@link SplitDatasetUtil#unsplitName(String)}, so a rows-less / metadata-only dataset is
     * treated as not-split — matching Python, whose {@code unsplit_name} reduces to the name when
     * there is no {@code first_record}.
     *
     * @param table
     *            the dataset (must be non-null)
     * @return the data-driven unsplit name, or {@code ""} when no name is available
     */
    public static String unsplitNameFromData(IDataTable table)
    {
        if (table == null)
        {
            return "";
        }
        String name = table.getMetaData().getName();
        if (name == null)
        {
            name = "";
        }
        String domainVal = firstRowValue(table, "DOMAIN");
        if (domainVal != null)
        {
            return domainVal;
        }
        if (name.startsWith("SUPP") || name.startsWith("SQ"))
        {
            String rdomain = firstRowValue(table, "RDOMAIN");
            if (rdomain != null)
            {
                return (name.startsWith("SUPP") ? "SUPP" : "SQ") + rdomain;
            }
        }
        return name;
    }


    /**
     * Reads the row-0 value of {@code column} as a non-empty string, or {@code null} when the
     * column is absent, the table has no rows, or the value is missing/empty. Shared by
     * {@link #domainPrefix(IDataTable)} and {@link #unsplitNameFromData(IDataTable)} so both read
     * the data the same way.
     *
     * <p>
     * <b>Fix #370</b> widened this from {@code private} to {@code public}: the {@code SUPP--}/
     * {@code SQ--} tier of the {@code ds_*("LIBRARY")} accessor
     * ({@code ExprCompiler#readProviderLevel}) reads {@code RDOMAIN} to substitute the Library
     * {@code SUPPQUAL} label template, and a second row-0 reader is exactly how the
     * {@code DOMAIN}/{@code RDOMAIN} conventions drift apart. One definition, three callers.
     * </p>
     */
    public static @Nullable String firstRowValue(IDataTable table, String column)
    {
        DataTableMeta meta = table.getMetaData();
        int idx = meta.getColumnIndex(column);
        if (idx < 0 || table.getRowCount() <= 0)
        {
            return null;
        }
        IDataValue dv = table.getColumn(idx).getDataValue(0);
        if (dv.isMissingOrInvalid())
        {
            return null;
        }
        String val = dv.getValueAsString();
        return val == null || val.isEmpty() ? null : val;
    }


    /**
     * Re-resolves a {@code --}-bearing template against the given dataset's prefix. Returns the
     * template unchanged when it contains no {@code --}. Used by study-wide operations that iterate
     * every dataset (e.g. {@code variable_count} with name {@code --LNKGRP}) and must resolve the
     * template per-iterated-dataset rather than once at rule-prep time.
     */
    static @Nullable String resolveTemplate(@Nullable String template, IDataTable table)
    {
        if (template == null || !template.contains("--"))
        {
            return template;
        }
        // EC-36: `--` in a variable-name template takes the variable prefix (Python's
        // wildcard_replacement), not the CDISC domain code. Null = unresolvable; the callers
        // already treat a null column name as "nothing to read from this dataset".
        String prefix = variableWildcardPrefix(table, domainPrefix(table));
        return prefix != null ? template.replace("--", prefix) : null;
    }


    /**
     * Resolves the {@code --} domain-prefix wildcard in an Operation's {@code name},
     * {@code domain}, and {@code group} against {@code prefix}, returning a new Operation (the
     * original is left untouched). Returns the Operation unchanged when {@code prefix} is
     * {@code null} or no field carries a {@code --}.
     *
     * <p>
     * This is the single source of the {@code --}-resolution applied before an Operation runs. The
     * legacy path calls it once at rule-prep time (see {@code RuleRunner.resolveOperationPrefix});
     * the native inline-operation path ({@code ExprCompiler.inlineOperationResult}) calls it at
     * eval time, because the compiled program is domain-agnostic and shared across domains. Without
     * it an inline operation whose {@code group}/{@code name} names a {@code --}-prefixed column
     * (e.g. {@code record_count(group=[USUBJID, --TESTCD, …])}) would hand
     * {@code OperationExecutor} a non-existent column and silently resolve to {@code null}.
     * </p>
     */
    public static Operation resolvePrefixes(Operation op, @Nullable String prefix)
    {
        // Passing null (not `prefix`) is what preserves the pre-EC-36 contract: the two-prefix
        // form falls back to the Fix #33 SUPP/SQAP-stripped `subPrefix` for variable fields, so
        // resolvePrefixes(op, "SUPPAE") still yields name=AEQNAM as it always did. Delegating with
        // (prefix, prefix) silently disabled Fix #33 on this overload.
        return resolvePrefixes(op, prefix, null);
    }


    /**
     * Two-prefix form (EC-36). {@code domainCodePrefix} substitutes {@code --} in {@code op.domain}
     * — a <em>dataset-name</em> wildcard, so it keeps the CDISC domain code and the Fix #33
     * SUPP/SQAP parent-stripping. {@code variablePrefix} substitutes {@code --} everywhere else
     * ({@code name}, {@code names}, {@code group}, {@code external_dictionary_term_variable}),
     * which are <em>variable names</em> and therefore follow Python's {@code wildcard_replacement}:
     * the 2-character AP suffix for an AP dataset, {@code ""} for SUPP/SQ.
     * <p>
     * A {@code null} {@code variablePrefix} falls back to the Fix #33-stripped domain prefix, which
     * is exactly the pre-EC-36 behaviour and is what the single-argument overload passes.
     * </p>
     */
    public static Operation resolvePrefixes(Operation op, @Nullable String domainCodePrefix,
            @Nullable String variablePrefix)
    {
        String prefix = domainCodePrefix;
        if (prefix == null)
        {
            return op;
        }
        String name = op.getName();
        String domain = op.getDomain();
        List<String> group = op.getGroup();

        List<String> nameList = op.getNames();
        String termVar = op.getExternalDictionaryTermVariable();
        boolean needsResolve = (name != null && name.contains("--"))
                || (domain != null && domain.contains("--"))
                || (group != null && group.stream().anyMatch(g -> g != null && g.contains("--")))
                || (nameList != null
                        && nameList.stream().anyMatch(n -> n != null && n.contains("--")))
                || (termVar != null && termVar.contains("--"))
                || (op.getQualifyingAnyPopulated() != null && op.getQualifyingAnyPopulated()
                        .stream().anyMatch(q -> q != null && q.contains("--")))
                || (op.getDictionaryParent() != null && op.getDictionaryParent().contains("--"))
                // EC-28(b) / Fix #131: a filter KEY can be the operation's ONLY wildcard, so it
                // must be part of the gate — otherwise the early return below hands back the
                // unresolved operation and the resolution below never runs.
                || (op.getFilter() != null && op.getFilter().keySet().stream()
                        .anyMatch(k -> k != null && k.contains("--")));
        if (!needsResolve)
        {
            return op;
        }

        // Fix #33: for SUPP/SQAP primaries, `--` in op.domain refers to the parent-domain prefix,
        // not the full primary name. Substituting with `prefix="SUPPAE"` would produce
        // `SUPPSUPPAE` — a broken dataset reference. Derive the parent prefix by stripping the
        // SUPP/SQAP segment from the primary name, mirroring ChildMatchPreMerger.preMerge and the
        // SUPP-aware branch of OperationExecutor.resolveWildcard.
        String substitutionPrefix = prefix;
        if ((prefix.startsWith("SUPP") || prefix.startsWith("SQAP")) && prefix.length() > 4)
        {
            substitutionPrefix = SplitDatasetUtil.unsplitName(prefix.substring(4));
        }
        final String subPrefix = substitutionPrefix;
        // EC-36: variable-name fields take the variable prefix; only op.domain keeps subPrefix.
        final String varPrefix = variablePrefix != null ? variablePrefix : substitutionPrefix;

        Operation resolved = new Operation();
        resolved.setId(op.getId());
        resolved.setOperator(op.getOperator());
        resolved.setName(name != null ? name.replace("--", varPrefix) : null);
        resolved.setOriginalName(name);
        List<String> names = op.getNames();
        resolved.setNames(names != null ? names.stream()
                .map(n -> n != null && n.contains("--") ? n.replace("--", varPrefix) : n).toList()
                : null);
        resolved.setDomain(domain != null ? domain.replace("--", subPrefix) : null);
        // EC-28(b) / Fix #131: a filter KEY names a column, so it resolves with the VARIABLE
        // prefix — the same side-of-dot rule name/names use (EC-36 / Fix #125). Before this the
        // map was copied verbatim, so a `--`-keyed filter reached the row matcher raw, matched
        // nothing, and said nothing: the FDA-SD1240 re-shape (`USUBJID is_not_contained_by
        // $filtered_distinct`) was simply not authorable for wildcard columns, which is why
        // FDA-SD0006 / PMDA-SD0006 / FDA-SE2319 stayed on the substring idiom EC-28(a) fixes.
        // Per decision D8 an unresolvable / never-matching key stays SILENT — consistent with
        // coreJ's absent-column fold; a rule that needs a skip authors a Scope.Variables gate.
        resolved.setFilter(resolveFilterKeys(op.getFilter(), varPrefix));
        resolved.setCodelists(op.getCodelists());
        resolved.setLevel(op.getLevel());
        resolved.setReturntype(op.getReturntype());
        resolved.setKeyName(op.getKeyName());
        resolved.setKeyValue(op.getKeyValue());
        resolved.setModelClass(op.getModelClass());
        resolved.setCtAttribute(op.getCtAttribute());
        resolved.setVersion(op.getVersion());
        resolved.setCtPackageTypes(op.getCtPackageTypes());
        resolved.setRegex(op.getRegex());
        resolved.setValueIsReference(op.getValueIsReference());
        resolved.setReference(op.getReference());
        // date_diff_days carries offset + reference_extreme, and row_max/row_min carry
        // name_pattern;
        // expandGroup copies these but resolvePrefixes historically did not — so a `--`-prefixed
        // date_diff_days (e.g. CDISC-SEND-0202..0205: name "--DTC", offset "1") silently lost its
        // offset here, yielding (date − reference) with no +1 ⇒ false positives on conformant rows.
        resolved.setOffset(op.getOffset());
        resolved.setReferenceExtreme(op.getReferenceExtreme());
        // EC-51 Half B — the missing-candidate disposition (mirror of the expandGroup copy). A
        // `--`-prefixed min_date/max_date/date_diff_days is the common shape, so losing it here
        // would be a live defect, not a defensive one.
        resolved.setMissingValues(op.getMissingValues());
        resolved.setKeepMissings(op.getKeepMissings());
        resolved.setNamePattern(op.getNamePattern());
        // EC-18 / P5c — date_diff_days Mode 3 foreign-minuend fields. Copied verbatim (mirror of
        // the expandGroup copy). The minuend_match `--` tokens are intentionally NOT rewritten to
        // the evaluation-domain prefix here: evalDateDiffDays resolves them per-side (evaluation
        // prefix on the left, minuend_domain prefix on the right) so a sided TFSPID = PMSPID match
        // can be derived. minuend_domain is a plain domain name and carries no `--`.
        resolved.setMinuendDomain(op.getMinuendDomain());
        resolved.setMinuendMatch(op.getMinuendMatch());
        // EC-7: the minus literal value-list minuend must survive prefix resolution too (mirror of
        // the expandGroup copy) — minus is a study-wide $-ref op that never carries a `--` today,
        // but the copy keeps the two routines in lock-step so a future field addition is not
        // dropped.
        resolved.setValue(op.getValue());
        resolved.setDelimiter(op.getDelimiter());
        resolved.setExternalDictionaryType(op.getExternalDictionaryType());
        resolved.setCaseSensitive(op.getCaseSensitive());
        resolved.setDictionaryTermType(op.getDictionaryTermType());
        resolved.setExternalDictionaryTermVariable(
                termVar != null && termVar.contains("--") ? termVar.replace("--", varPrefix)
                        : termVar);
        // EC-23: has_mixed_emptiness_within_group qualifier columns must survive prefix resolution
        // too (mirror of the expandGroup copy) — a `--`-prefixed qualifying column is resolved.
        List<String> qualifying = op.getQualifyingAnyPopulated();
        resolved.setQualifyingAnyPopulated(
                qualifying != null
                        ? qualifying.stream()
                                .map(q -> q != null && q.contains("--") ? q.replace("--", varPrefix)
                                        : q)
                                .toList()
                        : null);
        // Remaining fields expandGroupRefs copies — mirrored here verbatim to keep the two copy
        // routines in complete lock-step (the §2.1 silent-drop hazard, class of the EC-21/Fix-#99
        // offset bug). No shipped rule combines any of these with a `--` token today, so this is a
        // defensive completeness fix, not a live change.
        resolved.setExpression(op.getExpression());
        resolved.setSubtract(op.getSubtract());
        resolved.setOrdering(op.getOrdering());
        resolved.setMinLength(op.getMinLength());
        // EC-36: dictionary_parent names a COLUMN (the candidate-ancestor term), so it is a
        // variable-name position and must be `--`-resolved like name/names/group. It never was:
        // CDISC-CG0460 and CG0461 ship `dictionary_parent: "--SOC"`, which reached
        // evalValidExternalDictionaryHierarchy as the literal "--SOC", missed the column lookup
        // and returned null — both rules were dead on every dataset.
        String dictParent = op.getDictionaryParent();
        resolved.setDictionaryParent(dictParent != null && dictParent.contains("--")
                ? dictParent.replace("--", varPrefix)
                : dictParent);
        if (group != null)
        {
            resolved.setGroup(group.stream()
                    .map(g -> g != null && g.contains("--") ? g.replace("--", varPrefix) : g)
                    .toList());
        }
        return resolved;
    }


    private static @Nullable Object dispatch(OperationType type, Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            @Nullable String ruleId, @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable MetadataProvider defineProvider)
    {
        List<String> groupCols = op.getGroup();
        boolean grouped = groupCols != null && !groupCols.isEmpty();

        return switch (type)
        {
        case VARIABLE_COUNT -> evalVariableCount(op, table, resolver, ruleId);
        case VARIABLE_VALUE_COUNT -> evalVariableValueCount(op, table, resolver);
        case RECORD_COUNT -> grouped ? evalRecordCountGrouped(op, table, groupCols, ruleId)
                : evalRecordCount(op, table);
        case DISTINCT -> evalDistinctDispatch(op, table, resolver, groupCols, grouped, ruleId);
        case MAX -> grouped ? evalMaxGrouped(op, table, groupCols, ruleId) : evalMax(op, table);
        case MAX_DATE -> grouped ? evalDateExtremeGrouped(op, table, true, groupCols, ruleId)
                : evalMaxDate(op, table);
        case MIN_DATE -> grouped ? evalDateExtremeGrouped(op, table, false, groupCols, ruleId)
                : evalMinDate(op, table);
        case EXTRACT_METADATA -> evalExtractMetadata(op, table);
        case GET_COLUMN_ORDER_FROM_DATASET -> evalGetColumnOrder(table);
        case DY ->
        {
            // DY must be per-row: include both USUBJID and the date column in the
            // grouping key so each (subject, date) combination gets its own result.
            // Without the date column, all rows for a subject share one DY value
            // (the last one computed), producing false positives.
            List<String> dyGroupCols;
            if (grouped)
            {
                dyGroupCols = groupCols;
            }
            else
            {
                dyGroupCols = new ArrayList<>();
                dyGroupCols.add(USUBJID);
                if (op.getName() != null)
                {
                    dyGroupCols.add(op.getName());
                }
            }
            yield evalDyGrouped(op, table, resolver, dyGroupCols);
        }
        // The Operations carriage of the dataset_domain fact — the SAME derivation the
        // ds_domain("DATA") accessor reads, so the two surfaces cannot drift.
        case DATASET_DOMAIN -> unsplitNameFromData(table);
        case DATASET_NAMES -> evalDatasetNames(table, resolver);
        // J7: study_domains is the data-driven DOMAINS (split members collapse to their domain),
        // distinct from dataset_names (the member names).
        case STUDY_DOMAINS -> evalStudyDomains(resolver);
        // Library-dependent operations
        case REQUIRED_VARIABLES -> evalCoreVariables(libraryProvider, op, table, defineProvider,
                ruleId, false);
        case EXPECTED_VARIABLES -> evalCoreVariables(libraryProvider, op, table, defineProvider,
                ruleId, true);
        case GET_COLUMN_ORDER_FROM_LIBRARY ->
        {
            // J10 parity fix: a domain absent from the library variable model (e.g. DI under
            // SDTMIG 3-4) resolves to an empty column order. Map empty/null to
            // LIBRARY_NOT_AVAILABLE so RuleRunner Phase 2a.1 SKIPS the rule for that dataset —
            // mirroring the GET_MODEL_COLUMN_ORDER arm below and Python's "absent library order
            // => not applicable". Otherwise the empty list defeats the rule's own
            // `not empty($column_order_from_library)` guard, because the per-row scalar `empty`
            // is not list-aware (`empty([])` is false), and the rule false-fires. A domain that
            // IS in the model (e.g. SE/SV) yields a non-empty order and evaluates normally.
            @Nullable
            Object r = evalLibrary(libraryProvider, op, table,
                    p -> p.getColumnOrder(
                            net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(table)),
                    ruleId);
            if (r == null || (r instanceof List<?> list && list.isEmpty()))
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1} resolved an empty/absent library column order for domain {2} "
                                + "— rule will be skipped",
                        ruleId != null ? ruleId : "?", type, table.getMetaData().getName());
                yield LIBRARY_NOT_AVAILABLE;
            }
            yield r;
        }
        case GET_MODEL_COLUMN_ORDER ->
        {
            // Fix #42 Phase 2 (final): route through the class-aware resolver
            // (getStandardModelVariables) which walks the SDTM Model hierarchy with custom-
            // domain class detection (Fix #41), GENERAL OBSERVATIONS Identifier/Timing splice,
            // FINDINGS ABOUT class-vars merge, AP-prefix shimming and the IG-override merge.
            // Returns null when the provider has no products configured (or is in degraded
            // mode); empty list when the resolver couldn't find allowed variables for this
            // domain. Both translate to LIBRARY_NOT_AVAILABLE so RuleRunner Phase 2a.1
            // reports the rule SKIPPED rather than fanning out per-column on an empty
            // is_not_contained_by check (the CORE-000550 fan-out trigger).
            //
            // The Phase 1 empty-list defensive shim from Fix #55 stays as a backstop for
            // legacy code paths that still call getModelColumnOrder directly (e.g.
            // pre-Phase-2 IMetadataLibrary paths via MetadataKeys.MODEL_COLUMN_ORDER); when
            // the new resolver returns nothing usable we likewise treat it as
            // library-not-available.
            final DatasetResolver finalResolver = resolver;
            @Nullable
            Object r = evalLibrary(libraryProvider, op, table,
                    p -> p.getStandardModelVariables(table, finalResolver), ruleId);
            if (r == null)
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1} resolver returned null for domain {2} (no product / "
                                + "degraded mode) — rule will be skipped",
                        ruleId != null ? ruleId : "?", type, table.getMetaData().getName());
                yield LIBRARY_NOT_AVAILABLE;
            }
            if (r instanceof List<?> list && list.isEmpty())
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1} resolver returned empty for domain {2} — rule will be skipped",
                        ruleId != null ? ruleId : "?", type, table.getMetaData().getName());
                yield LIBRARY_NOT_AVAILABLE;
            }
            yield r;
        }
        case GET_PARENT_MODEL_COLUMN_ORDER -> evalParentModelColumnOrder(libraryProvider, op, table,
                resolver, ruleId);
        case VARIABLE_NAMES ->
        {
            // EC-13 — union of variable names across every dataset the IG standard defines. An
            // empty/absent enumeration (no product / degraded mode) maps to LIBRARY_NOT_AVAILABLE
            // so RuleRunner SKIPs the rule rather than evaluating a `$`-ref membership against an
            // empty set (which would misfire). Stays a `$`-ref (never inlined).
            @Nullable
            Object r = evalLibrary(libraryProvider, op, table,
                    MetadataProvider::getStandardVariableNames, ruleId);
            if (r == null || (r instanceof List<?> list && list.isEmpty()))
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1} resolved no standard variable names — rule will be skipped",
                        ruleId != null ? ruleId : "?", type);
                yield LIBRARY_NOT_AVAILABLE;
            }
            yield r;
        }
        case STANDARD_DOMAINS ->
        {
            // EC-14 layer (i) — canonical union of standard domain names (IG datasets ∪ model
            // datasets). CRITICAL empty-enumeration guard: an empty/absent enumeration maps to
            // LIBRARY_NOT_AVAILABLE so RuleRunner SKIPs, else `SRCDOM is_not_contained_by
            // $sdtm_domains` fires on every populated SRCDOM in a degraded run. Stays a `$`-ref.
            @Nullable
            Object r = evalLibrary(libraryProvider, op, table,
                    MetadataProvider::getStandardDatasetNames, ruleId);
            if (r == null || (r instanceof List<?> list && list.isEmpty()))
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1} resolved no standard domain names — rule will be skipped",
                        ruleId != null ? ruleId : "?", type);
                yield LIBRARY_NOT_AVAILABLE;
            }
            yield r;
        }
        case GET_DATASET_FILTERED_VARIABLES -> evalGetDatasetFilteredVariables(libraryProvider, op,
                table, resolver, ruleId);
        case NATURAL_KEY_VARIABLES -> evalNaturalKeyVariables(libraryProvider, op, table, resolver,
                ruleId);
        case GET_MODEL_FILTERED_VARIABLES -> evalGetModelFilteredVariables(libraryProvider, op,
                table, resolver, ruleId);
        case VALID_CODELIST_DATES -> evalValidCodelistDates(libraryProvider, op, ruleId);
        case CONSTANT -> op.getName(); // return the name field as a literal string
        case CROSS_DATASET_VARIABLE_METADATA -> VariableMetadataResult.build(resolver,
                resolveWildcard(op.getDomain(), table), op.getName(),
                table.getMetaData().getName());
        case DATASET_CLASS_FROM_LIBRARY -> evalLibrary(libraryProvider, op, table, p ->
        {
            Map<String, String> dsMeta = p.getDatasetMetadata(
                    net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(table));
            return dsMeta != null ? dsMeta.get("className") : null;
        }, ruleId, LibraryArmAnswer.TEXT);
        // Fix #369 — NEVER: `false` ("not custom") is a real answer AND the one that lets a rule
        // fire, so it is indistinguishable from "could not tell". A define cannot supply it either
        // — "custom" means "not in the standard", and the standard is what is missing.
        case DOMAIN_IS_CUSTOM -> evalLibrary(libraryProvider, op, table,
                p -> p.isDomainCustom(
                        net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(table)),
                ruleId, LibraryArmAnswer.NEVER);
        case CODELIST_TERMS ->
        {
            @Nullable
            Object r = evalLibrary(libraryProvider, op, table, p -> codelistTerms(p, op), ruleId);
            // An empty term list means the codelist could not be resolved (no define.xml / CT
            // package, or an unknown codelist). Treat it as library-not-available so the rule is
            // SKIPPED, mirroring the Python engine's MissingDataError — otherwise the downstream
            // `<x> not in $list` check would flag every row against an empty list.
            if (r instanceof List<?> list && list.isEmpty())
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1} resolved no codelist terms for op {2} — rule will be skipped",
                        ruleId != null ? ruleId : "?", type, op.getId());
                yield LIBRARY_NOT_AVAILABLE;
            }
            yield r;
        }
        case GET_CODELIST_ATTRIBUTES ->
        {
            @Nullable
            Object r = evalLibrary(libraryProvider, op, table,
                    p -> codelistAttributes(p, op, table), ruleId);
            // Empty attribute set ⇒ unresolved CT package / unknown attribute. Skip the rule
            // (Python raises MissingDataError / ValueError) rather than fan out a contained-by
            // check against an empty set.
            if (r instanceof List<?> list && list.isEmpty())
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1} resolved no codelist attributes for op {2} — rule will be "
                                + "skipped",
                        ruleId != null ? ruleId : "?", type, op.getId());
                yield LIBRARY_NOT_AVAILABLE;
            }
            yield r;
        }
        case HAS_MIXED_EMPTINESS_WITHIN_GROUP -> evalHasMixedEmptinessWithinGroup(op, table,
                groupCols, ruleId);
        case VARIABLE_IS_NULL -> evalVariableIsNull(op, table);
        case TS_PARAMETER_VALUE -> evalTsParameterValue(op, resolver);
        case SUPP_QNAM_PRESENT -> evalSuppQnamJoin(op, resolver, true);
        case SUPP_QNAM_VALUE -> evalSuppQnamJoin(op, resolver, false);
        case DICTIONARY_AVAILABLE -> Boolean.valueOf(dictionaryProvider != null
                && dictionaryProvider.isAvailable(op.getExternalDictionaryType()));
        case VALID_EXTERNAL_DICTIONARY_VALUE, VALID_EXTERNAL_DICTIONARY_CODE -> evalValidExternalDictionaryValue(
                op, table, dictionaryProvider);
        case VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR -> evalValidExternalDictionaryCodeTermPair(op,
                table, dictionaryProvider);
        case VALID_EXTERNAL_DICTIONARY_HIERARCHY -> evalValidExternalDictionaryHierarchy(op, table,
                dictionaryProvider);
        case DEFINE_VARIABLE_NAMES -> evalDefineVariableNames(table, defineProvider);
        case DEFINE_DATASET_NAMES -> evalDefineDatasetNames(defineProvider);
        case DEFINE_KEY_VARIABLES ->
        {
            List<String> keys = evalDefineKeyVariables(table, defineProvider);
            // An empty key set (Define present but this dataset declares no KeySequence) would
            // collapse an is_not_unique_set key to the constant anchor and flag every record as a
            // duplicate (PMDA-SD1152, M4). Treat it as library-not-available so the rule SKIPs —
            // matching Python define_key_variables (raises DefineXMLNotProvidedError on empty).
            yield keys != null && keys.isEmpty() ? LIBRARY_NOT_AVAILABLE : keys;
        }
        case REFERENCED_DOMAIN_CLASS -> evalReferencedDomainClass(libraryProvider, op, table,
                ruleId);
        case INTERVAL_UNCERTAINTY_PRECISION_MISMATCH -> evalIntervalUncertaintyPrecisionMismatch(op,
                table);
        case DICTIONARY_HAS_DECODE -> evalDictionaryHasDecode(op, table, dictionaryProvider);
        case SPLIT_SIBLING_LENGTH_MISMATCH -> evalSplitSiblingLengthMismatch(table, resolver);
        case DUPLICATE_LABEL_VARIABLES -> evalDuplicateLabelVariables(table);
        case COLUMN_SERIES_METADATA -> evalColumnSeriesMetadata(op, table);
        case DATE_DIFF_DAYS -> evalDateDiffDays(op, table, resolver);
        case IS_LAST_IN_GROUP -> evalIsLastInGroup(op, table);
        case ROW_MAX -> evalRowExtreme(op, table, true);
        case ROW_MIN -> evalRowExtreme(op, table, false);
        default ->
        {
            LOGGER.log(System.Logger.Level.DEBUG, "[{0}] Unsupported operation type: {1} (id={2})",
                    ruleId != null ? ruleId : "?", type, op.getId());
            yield null;
        }
        };
    }


    private static Long evalVariableCount(Operation op, IDataTable table, DatasetResolver resolver,
            @Nullable String ruleId)
    {
        // Regex-driven count: matches columns whose names satisfy the pattern. Used by
        // dataset-level "no column matching X exists" rules (CDISC-AD0048 family) without
        // going through wildcard expansion. The pattern is anchored — empty regex matches
        // nothing, full-string match required.
        String namePattern = op.getNamePattern();
        if (namePattern != null && !namePattern.isEmpty())
        {
            return evalVariableCountByPattern(namePattern, table, ruleId);
        }
        // Whole-dataset column count (no name) — preserve original fast path.
        if (op.getName() == null && op.getOriginalName() == null)
        {
            return (long) table.getMetaData().getColumnCount();
        }
        // Prefer the pre-resolution template so `--LNKGRP` re-resolves per iterated dataset
        // (AE→AELNKGRP, CM→CMLNKGRP, …). Python parity: VariableCount._get_dataset_variable_count.
        String template = op.getOriginalName() != null ? op.getOriginalName() : op.getName();
        if (resolver instanceof DatasetResolver.WithInventory inv)
        {
            return countVariableAcrossInventory(template, inv, resolver);
        }
        // Fallback: only the current table is available — re-resolve the template against it.
        String resolved = resolveTemplate(template, table);
        return resolved != null && table.getMetaData().getColumnIndex(resolved) >= 0 ? 1L : 0L;
    }


    /**
     * Counts datasets in the inventory whose resolved {@code --}-template column exists. Split-
     * dataset families are deduplicated by their <em>data-driven</em> unsplit name
     * ({@link #unsplitNameFromData}, read from the {@code DOMAIN}/{@code RDOMAIN} columns) so each
     * logical family counts once, exactly mirroring Python's
     * {@code VariableCount._get_all_study_variable_counts} which groups by
     * {@code SDTMDatasetMetadata.unsplit_name}. The dataset is resolved <em>before</em> the dedup
     * key is computed (resolve-then-key) because the key now comes from the data, not the name — so
     * a letter-suffix split like {@code FAAE}/{@code DOMAIN=FA} groups with {@code FACM} under
     * {@code FA}, which a name-only key ({@link SplitDatasetUtil#unsplitName}) would miss.
     */
    private static long countVariableAcrossInventory(@Nullable String template,
            DatasetResolver.WithInventory inv, DatasetResolver resolver)
    {
        long count = 0;
        Set<String> seenKeys = new LinkedHashSet<>();
        for (String dsName : inv.availableDatasets())
        {
            IDataTable ds = resolver.resolve(dsName);
            if (ds == null)
            {
                continue;
            }
            String key = unsplitNameFromData(ds);
            if (!seenKeys.add(key))
            {
                continue;
            }
            String resolved = resolveTemplate(template, ds);
            if (resolved != null && ds.getMetaData().getColumnIndex(resolved) >= 0)
            {
                count++;
            }
        }
        return count;
    }


    /**
     * Counts columns of {@code table} whose names match {@code pattern} (anchored / full-string
     * semantics via {@link java.util.regex.Matcher#matches}). Empty / null pattern returns 0.
     * Invalid pattern returns 0 and logs at INFO so misauthored rules surface in the logs but don't
     * break execution.
     */
    private static Long evalVariableCountByPattern(String pattern, IDataTable table,
            @Nullable String ruleId)
    {
        Pattern compiled;
        try
        {
            compiled = Pattern.compile(pattern);
        }
        catch (java.util.regex.PatternSyntaxException ex)
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] Invalid name_pattern regex {1} on variable_count: {2}",
                    ruleId != null ? ruleId : "?", pattern, ex.getDescription());
            return 0L;
        }
        DataTableMeta meta = table.getMetaData();
        int n = meta.getColumnCount();
        long count = 0;
        for (int i = 0; i < n; i++)
        {
            String name = meta.getColumn(i).getName();
            if (compiled.matcher(name).matches())
            {
                count++;
            }
        }
        return count;
    }


    /**
     * Returns a map from each distinct non-empty value of the target variable to the number of
     * <em>study dataset families</em> in which that value occurs. A family is the set of inventory
     * datasets sharing a data-driven unsplit name ({@link #unsplitNameFromData}), so every member
     * of a split family is unioned and contributes at most one to each value's count.
     * <p>
     * Python reference: {@code operations/variable_value_count.py}, which takes
     * {@code Counter(series.unique())} per (split-concatenated) dataset and sums the counters — the
     * same dataset-presence semantics. The remaining deviations are recorded as EC-30 in
     * {@code plans/PLAN-rule-review-engine-changes.md}:
     * </p>
     * <ol>
     * <li><b>Family key (mirrored into the parity fork).</b> Families are keyed by
     * {@link #unsplitNameFromData} (row-0 {@code DOMAIN}, else {@code SUPP}/{@code SQ} +
     * {@code RDOMAIN}, else the raw name) — the same key {@code variable_count} uses. Upstream
     * Python keys by {@code SDTMDatasetMetadata.domain} alone, so every DOMAIN-less dataset
     * (SUPP--, SQ--, RELREC, …) collapses under a single {@code None} key and only the last one is
     * counted. That is a defect, not a contract. <em>Caveat:</em> the fork matches only when
     * {@code RDOMAIN} resolves — for a SUPP/SQ dataset with no usable {@code RDOMAIN} Python still
     * builds the literal {@code "SUPPNone"}/{@code "SUPP"} and collapses, where this method falls
     * back to the raw name and keeps such datasets apart.</li>
     * <li><b>Empty/missing cells (Java-only — deliberately NOT mirrored).</b> Missing/invalid and
     * empty-string cells contribute no key here; pandas {@code .unique()} retains {@code NaN} and
     * {@code ""}. The fork must keep them: its {@code value_has_multiple_references} looks the map
     * up per row through a bare {@code dict.get()} and compares {@code > 1}, so a dropped key
     * raises {@code TypeError} for every blank-target row (CG0022). No Java operator consumes this
     * map, so the skip is unobservable on this side.</li>
     * <li><b>Key type (Java-only).</b> Keys here are {@code String} (via
     * {@code IDataValue.getValueAsString()}); Python's are the raw cell values.</li>
     * <li><b>{@code --} resolution (Java-only).</b> {@link #domainPrefix} uses the full
     * {@code DOMAIN} cell where Python's {@code wildcard_replacement} uses the 2-char AP suffix for
     * AP datasets and {@code ""} for SUPP — so for a {@code --}-templated target an AP or SUPP
     * family resolves to a different column name in the two engines. Shared cross-operation
     * resolver; out of scope for EC-30.</li>
     * </ol>
     * <p>
     * With no dataset inventory (degraded mode) the current table is the only family, so every
     * distinct value maps to {@code 1}.
     * </p>
     */
    private static Map<String, Long> evalVariableValueCount(Operation op, IDataTable table,
            DatasetResolver resolver)
    {
        String template = op.getOriginalName() != null ? op.getOriginalName() : op.getName();
        if (template == null)
        {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        if (resolver instanceof DatasetResolver.WithInventory inv)
        {
            // Resolve-then-key, exactly as countVariableAcrossInventory does: the family key comes
            // from the data, not the name, so a letter-suffix split like FAAE/DOMAIN=FA groups
            // with FACM under FA — which a name-only key (SplitDatasetUtil.unsplitName) misses.
            Map<String, List<IDataTable>> families = new LinkedHashMap<>();
            for (String dsName : inv.availableDatasets())
            {
                IDataTable ds = resolver.resolve(dsName);
                if (ds == null)
                {
                    continue;
                }
                families.computeIfAbsent(unsplitNameFromData(ds), _ -> new ArrayList<>()).add(ds);
            }
            for (List<IDataTable> family : families.values())
            {
                accumulateFamilyValuePresence(counts, family, template);
            }
            return counts;
        }
        accumulateFamilyValuePresence(counts, List.of(table), template);
        return counts;
    }


    /**
     * Unions the distinct non-empty values of the resolved target column across every member of one
     * split family, then bumps each such value's count by exactly one. The {@code --} template is
     * resolved per member so a family whose members disagree on the {@code DOMAIN} cell still reads
     * the right column from each.
     */
    private static void accumulateFamilyValuePresence(Map<String, Long> counts,
            List<IDataTable> family, String template)
    {
        Set<String> distinct = new LinkedHashSet<>();
        for (IDataTable ds : family)
        {
            collectDistinctValues(distinct, ds, resolveTemplate(template, ds));
        }
        for (String value : distinct)
        {
            counts.merge(value, 1L, Long::sum);
        }
    }


    /** Adds every non-missing, non-empty value of {@code colName} in {@code ds} to {@code out}. */
    private static void collectDistinctValues(Set<String> out, IDataTable ds,
            @Nullable String colName)
    {
        if (colName == null)
        {
            return;
        }
        DataTableMeta meta = ds.getMetaData();
        int colIdx = meta.getColumnIndex(colName);
        if (colIdx < 0)
        {
            return;
        }
        IDataTableColumn col = ds.getColumn(colIdx);
        long rowCount = ds.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            if (dv.isMissingOrInvalid())
            {
                continue;
            }
            String val = dv.getValueAsString();
            if (val == null || val.isEmpty())
            {
                continue;
            }
            out.add(val);
        }
    }


    private static Long evalRecordCount(Operation op, IDataTable table)
    {
        Map<String, Object> filter = op.getFilter();
        if (filter == null || filter.isEmpty())
        {
            return table.getRowCount();
        }
        DataTableMeta meta = table.getMetaData();
        long rowCount = table.getRowCount();
        long count = 0;
        for (long r = 0; r < rowCount; r++)
        {
            if (rowMatchesFilter(table, meta, filter, r))
            {
                count++;
            }
        }
        return count;
    }


    private static boolean rowMatchesFilter(IDataTable table, DataTableMeta meta,
            @Nullable Map<String, Object> filter, long row)
    {
        if (filter == null)
        {
            return true;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet())
        {
            int colIdx = meta.getColumnIndex(entry.getKey());
            if (colIdx < 0)
            {
                return false;
            }
            IDataValue dv = table.getColumn(colIdx).getDataValue(row);
            if (dv.isMissingOrInvalid())
            {
                return false;
            }
            String colValue = dv.getValueAsString();
            // CDISC convention: empty string is treated as missing (mirrors
            // the non-empty contract). Without this guard the empty-
            // prefix wildcard "&" would count "" rows as populated, which
            // contradicts the rule-author intent.
            if (colValue == null || colValue.isEmpty())
            {
                return false;
            }
            Object rawValue = entry.getValue();
            // List value ⇒ membership: the cell (as a string) must be one of the listed terms.
            // Mirrors the Python engine's `filtered_df[variable].isin(list(value))`. String compare
            // is consistent with the scalar branch's equality coercion.
            if (rawValue instanceof List<?> members)
            {
                boolean member = false;
                for (Object m : members)
                {
                    if (m != null && colValue.equals(m.toString()))
                    {
                        member = true;
                        break;
                    }
                }
                if (!member)
                {
                    return false;
                }
                continue;
            }
            String filterValue = String.valueOf(rawValue);
            boolean matches;
            if (filterValue.endsWith("&") || filterValue.endsWith("%"))
            {
                // Trailing prefix wildcard: "RACE&" or "RACE%" matches any value starting with
                // "RACE". The corpus authors prefix filters with the SQL-LIKE-style "%" (e.g.
                // CORE-000846 QNAM="RACE%"), which the vendored Python engine reads as a LIKE
                // prefix; "&" is the engine's own historical marker. Accept both so the same
                // filter evaluates identically across the offline corpus and both engines.
                matches = colValue.startsWith(filterValue.substring(0, filterValue.length() - 1));
            }
            else
            {
                matches = colValue.equals(filterValue);
            }
            if (!matches)
            {
                return false;
            }
        }
        return true;
    }


    /**
     * When value_is_reference is true on a {@code distinct} Operation, the column values are
     * references to variable names in the domains identified by RDOMAIN. Returns a
     * {@link GroupedResult} keyed by {@code RDOMAIN} so that downstream containment operators (e.g.
     * {@code is_not_contained_by}) check each row's IDVAR against <em>its</em> RDOMAIN's column
     * names — not a flat union. Fix #11.
     * <p>
     * Python parity: {@code operations/distinct.py} per-row validation against the row's
     * RDOMAIN-referenced dataset.
     * </p>
     */
    private static @Nullable Object evalDistinctDispatch(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable List<String> groupCols, boolean grouped,
            @Nullable String ruleId)
    {
        if (Boolean.TRUE.equals(op.getValueIsReference()))
        {
            return evalDistinctVariableNames(op, table, resolver);
        }
        // T3 composite target: a `names` list yields a Set<List<String>> of the reference dataset's
        // distinct row-tuples (ungrouped only — the subject column is carried inside the tuple),
        // the
        // reference set for a `tuple(...) [not] in distinct([...], domain="D")` membership.
        if (op.getNames() != null && !op.getNames().isEmpty())
        {
            return evalDistinctTuples(op, table);
        }
        return grouped ? evalDistinctGrouped(op, table, groupCols, ruleId)
                : evalDistinct(op, table);
    }


    private static @Nullable GroupedResult evalDistinctVariableNames(Operation op, IDataTable table,
            DatasetResolver resolver)
    {
        DataTableMeta meta = table.getMetaData();
        int rdomainIdx = meta.getColumnIndex("RDOMAIN");
        if (rdomainIdx < 0)
        {
            return null;
        }
        // Collect distinct RDOMAIN values actually present in the data.
        Set<String> domains = new LinkedHashSet<>();
        long rowCount = table.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = table.getColumn(rdomainIdx).getDataValue(r);
            if (!dv.isMissingOrInvalid())
            {
                String val = dv.getValueAsString();
                if (val != null && !val.isEmpty())
                {
                    domains.add(val);
                }
            }
        }
        // Build one column-name list per RDOMAIN so GroupedResult.getForRow can resolve
        // per-row using the row's RDOMAIN value as the group key.
        Map<String, Object> resultsByDomain = new LinkedHashMap<>();
        for (String domain : domains)
        {
            // J7: the RDOMAIN value is a DOMAIN (e.g. "LB"), but resolver.resolve is keyed by
            // member
            // name (lbch/lbhe/lbur for a split LB), so it returns null. Union the column names of
            // every member whose data-driven domain matches (tablesForDomain); fall back to a
            // direct
            // resolve for a non-inventory resolver.
            Set<String> cols = new LinkedHashSet<>();
            Iterable<IDataTable> domainTables = resolver instanceof DatasetResolver.WithInventory inv
                    ? inv.tablesForDomain(domain)
                    : singletonOrEmpty(resolver.resolve(domain));
            for (IDataTable domainTable : domainTables)
            {
                DataTableMeta domainMeta = domainTable.getMetaData();
                for (int c = 0; c < domainMeta.getColumnCount(); c++)
                {
                    cols.add(domainMeta.getColumn(c).getName());
                }
            }
            if (!cols.isEmpty())
            {
                resultsByDomain.put(domain, new ArrayList<>(cols));
            }
        }
        return declaredGrouped(op, List.of("RDOMAIN"), resultsByDomain);
    }


    /**
     * {@code get_parent_model_column_order}: the library Model column order of each row's
     * <em>parent</em> domain (the value of its {@code RDOMAIN} column), not the SUPP dataset's own
     * model. Mirrors Python {@code operations/parent_library_model_column_order.py}: for a SUPP
     * dataset the parent is the domain named in {@code RDOMAIN} (e.g. {@code SUPPAE → AE}). Returns
     * a {@link GroupedResult} keyed by {@code RDOMAIN} so a downstream containment operator (e.g.
     * CORE-000783's {@code QNAM is_contained_by $model_variables}) checks each row against
     * <em>its</em> parent's model variables. When no parent could be resolved with library data,
     * yields {@link #LIBRARY_NOT_AVAILABLE} so the rule is SKIPPED (the pre-fix behaviour for an
     * unconfigured library).
     */
    private static @Nullable Object evalParentModelColumnOrder(
            @Nullable MetadataProvider libraryProvider, Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable String ruleId)
    {
        if (libraryProvider == null)
        {
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_PROVIDER_UNAVAILABLE_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        Object degraded = degradedSkip(libraryProvider, op, ruleId);
        if (degraded != null)
        {
            return degraded;
        }
        DataTableMeta meta = table.getMetaData();
        int rdomainIdx = meta.getColumnIndex("RDOMAIN");
        if (rdomainIdx < 0)
        {
            return LIBRARY_NOT_AVAILABLE;
        }
        Set<String> domains = new LinkedHashSet<>();
        long rowCount = table.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = table.getColumn(rdomainIdx).getDataValue(r);
            if (!dv.isMissingOrInvalid())
            {
                String val = dv.getValueAsString();
                if (val != null && !val.isEmpty())
                {
                    domains.add(val);
                }
            }
        }
        Map<String, Object> byDomain = new LinkedHashMap<>();
        for (String domain : domains)
        {
            IDataTable parentTable = resolver.resolve(domain);
            if (parentTable == null)
            {
                // Python raises DomainNotFoundError; the harness/engine treats a missing parent as
                // not-resolvable — skip this group (rule SKIPPED if no group resolves).
                continue;
            }
            List<String> vars = libraryProvider.getStandardModelVariables(parentTable, resolver);
            if (vars == null)
            {
                // No product / degraded — match GET_MODEL_COLUMN_ORDER and skip the whole rule.
                return LIBRARY_NOT_AVAILABLE;
            }
            if (!vars.isEmpty())
            {
                byDomain.put(domain, vars);
            }
        }
        if (byDomain.isEmpty())
        {
            return LIBRARY_NOT_AVAILABLE;
        }
        return new GroupedResult(List.of("RDOMAIN"), byDomain,
                OperationType.emptyValueOf(OperationType.GET_PARENT_MODEL_COLUMN_ORDER));
    }


    /**
     * T3 composite cross-dataset membership: the set of distinct row-tuples of the {@code names}
     * columns in the reference {@code table}. Each tuple is a {@code List<String>} whose elements
     * are the columns' string values in {@code names} order (a missing / invalid cell contributes
     * the empty string), so it compares equal to the native {@code tuple(...)} value function's
     * per-row cell (which uses the same missing-to-empty-string convention). An absent column
     * contributes an empty string for every row. Mirrors the Python reference engine's list-target
     * {@code distinct} ({@code set(map(tuple, df[target].itertuples(...)))}).
     *
     * <p>
     * Review H1 (ruled 2026-08-19): an {@code IDVARVAL} slot is a SUPP-- / RELREC <b>join token</b>
     * — a Char rendering of the parent's {@code IDVAR}-named key — and is normalised with
     * {@link ChildMatchIndex#normalizeJoinToken} exactly as the {@code Match_Datasets} child join
     * normalises it at merge time (the J5 coercion in {@code ChildMatchPreMerger}, mirroring
     * Python's {@code dataset_preprocessor}). Without this, a SAS-padded {@code " 1"} or a float
     * rendering {@code "1.0"} in {@code IDVARVAL} never compares equal to the probe side's
     * {@code tuple(USUBJID, AESEQ)} cell, whose numeric rendering is already canonical
     * ({@code DataValueDouble} renders an integral double without the {@code .0} — byte-equal to
     * {@code normalizeJoinToken}'s canonical form, which is why the probe side needs no change).
     * {@code numericParent} is passed {@code true} because this path cannot see the parent column's
     * type: a numeric-looking token is canonicalised, a non-numeric token is only stripped, which
     * is safe for Char keys such as {@code --SPID} values. Applied by column <em>name</em>:
     * {@code IDVARVAL} is the one column whose contract <i>is</i> the join-token contract (sole
     * corpus carrier today: {@code PMDA-SD1143}).
     */
    private static Set<List<String>> evalDistinctTuples(Operation op, IDataTable table)
    {
        List<String> cols = op.getNames();
        if (cols == null || cols.isEmpty())
        {
            return Set.of();
        }
        DataTableMeta meta = table.getMetaData();
        int[] idx = new int[cols.size()];
        boolean[] joinToken = new boolean[cols.size()];
        for (int c = 0; c < cols.size(); c++)
        {
            idx[c] = meta.getColumnIndex(cols.get(c));
            joinToken[c] = "IDVARVAL".equals(cols.get(c));
        }
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();
        long rowCount = table.getRowCount();
        Set<List<String>> seen = new LinkedHashSet<>();
        for (long r = 0; r < rowCount; r++)
        {
            if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
            {
                continue;
            }
            List<String> tuple = new ArrayList<>(idx.length);
            for (int c = 0; c < idx.length; c++)
            {
                int col = idx[c];
                if (col < 0)
                {
                    tuple.add("");
                    continue;
                }
                IDataValue dv = table.getColumn(col).getDataValue(r);
                String cell = dv.isMissingOrInvalid() ? "" : dv.getValueAsString();
                tuple.add(joinToken[c] && cell != null
                        ? ChildMatchIndex.normalizeJoinToken(cell, true)
                        : cell);
            }
            seen.add(tuple);
        }
        return seen;
    }


    private static List<String> evalDistinct(Operation op, IDataTable table)
    {
        if (op.getName() == null)
        {
            return List.of();
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return List.of();
        }
        IDataTableColumn col = table.getColumn(colIdx);
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();
        long rowCount = table.getRowCount();
        Set<String> seen = new LinkedHashSet<>();
        for (long r = 0; r < rowCount; r++)
        {
            if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
            {
                continue;
            }
            IDataValue dv = col.getDataValue(r);
            if (!dv.isMissingOrInvalid())
            {
                String val = dv.getValueAsString();
                if (val != null && !val.isEmpty())
                {
                    seen.add(val);
                }
            }
        }
        return new ArrayList<>(seen);
    }


    /**
     * Set difference {@code name \ subtract} (order-preserving), mirroring Python's
     * {@code operations/minus.py}: the result is the elements of the {@code name} (minuend) list
     * that are not in the {@code subtract} (subtrahend) list, in {@code name}'s order. Both
     * operands are {@code $}-refs to prior operation results, read from {@code resolved}.
     *
     * <p>
     * Edge cases (matching {@code minus.py}): an absent/empty minuend yields {@code []}; an
     * absent/null subtrahend yields the minuend unchanged. {@code null} normalises to {@code []}, a
     * scalar to a singleton list, any collection to its stringified elements.
     * </p>
     */
    private static List<String> evalMinus(Operation op, Map<String, Object> resolved)
    {
        // EC-7: a literal `value` list takes the minuend slot when present (subtract stays a
        // $-ref);
        // otherwise the minuend is the `name` $-ref to a prior operation result, as before.
        List<String> minuend = op.getValue() != null ? normalizeToList(op.getValue())
                : normalizeToList(resolved.get(op.getName()));
        if (minuend.isEmpty())
        {
            return List.of();
        }
        if (op.getSubtract() == null || resolved.get(op.getSubtract()) == null)
        {
            return minuend;
        }
        Set<String> subtrahend = new java.util.HashSet<>(
                normalizeToList(resolved.get(op.getSubtract())));
        return minuend.stream().filter(x -> !subtrahend.contains(x)).toList();
    }


    /**
     * Coerces an operation result to a {@code List<String>} for set operations, mirroring
     * {@code minus.py}'s {@code _normalize_to_list}: {@code null} → {@code []}; any
     * {@link java.util.Collection} → its non-null elements stringified; an array → the same; a
     * scalar → a singleton list of its string form.
     */
    private static List<String> normalizeToList(@Nullable Object value)
    {
        if (value == null)
        {
            return List.of();
        }
        if (value instanceof java.util.Collection<?> c)
        {
            List<String> out = new ArrayList<>(c.size());
            for (Object item : c)
            {
                if (item != null)
                {
                    out.add(item.toString());
                }
            }
            return out;
        }
        if (value instanceof Object[] arr)
        {
            // Object[] only — a primitive array is not an operation result here and falls through
            // to the scalar branch rather than risking a ClassCastException.
            List<String> out = new ArrayList<>(arr.length);
            for (Object item : arr)
            {
                if (item != null)
                {
                    out.add(item.toString());
                }
            }
            return out;
        }
        return List.of(value.toString());
    }


    /**
     * T5a — per-variable all-null. Returns {@code true} when the {@code name} column is absent from
     * {@code table}, or present but empty ("" / missing) for every row. Mirrors the Python
     * reference engine's {@code variable_is_null}
     * ({@code (series.isnull() | (series == "")).all()}), which treats an absent column as null.
     * Dataset-level boolean, broadcast to every row by the caller.
     */
    private static Boolean evalVariableIsNull(Operation op, IDataTable table)
    {
        if (op.getName() == null)
        {
            return Boolean.TRUE;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return Boolean.TRUE; // absent column ⇒ null (Python variable_is_null parity)
        }
        IDataTableColumn col = table.getColumn(colIdx);
        long rowCount = table.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            if (!dv.isMissingOrInvalid() && !dv.getValueAsString().isEmpty())
            {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }


    /**
     * Column presence for the {@code variable_exists} operation — the reporting carriage of the
     * {@code var_exists} check function (see {@link OperationType#VARIABLE_EXISTS}).
     *
     * <p>
     * ⚠⚠ <b>It does not decide anything itself — it composes the column name exactly as
     * {@link net.cumba.cdisc.core.expr.convert.VariableExistsInliner#candidateColumns} does and
     * hands it to {@link OperatorRegistry#existsAsVariable}, the identical entry point
     * {@code ExprCompiler.compileExists(ExistsMode.VARIABLE)} calls for {@code var_exists(X)}.</b>
     * A rule reports {@code $X} beside a verdict the <em>function</em> decided, so the two must
     * agree; delegating is the only way to make that true by construction rather than by a
     * hand-copy that drifts. An earlier draft re-implemented two of the arms and was measurably
     * wrong on four inputs (a dotted name in {@code name}, a lower-case {@code domain} — which the
     * function's {@code DOTTED_DATASET_COLUMN} gate rejects, a {@code ${…}} placeholder, and the
     * {@code D.COL=value} dotted-filter form).
     * </p>
     *
     * <p>
     * Total by construction: {@code existsAsVariable} answers {@code false} for an absent column,
     * an absent foreign dataset and a null name, never {@code null}. That totality — negation-safe
     * existence — is precisely what the pre-2026-07 {@code variable_exists} operation lacked and
     * what got it retired as a verdict surface.
     * </p>
     *
     * <p>
     * The context is minimal on purpose: the operation asks a <em>schema</em> question, so it
     * carries only the table and the resolver. An empty variables map means {@code existsCommon}'s
     * pre-injected-Boolean arm never fires here — a {@code $}-operation is not a Domain-Presence
     * check leaf and has no such pre-resolution to read.
     * </p>
     *
     * <p>
     * ⚠ {@code --} is normally already resolved ({@code RuleRunner.resolveOperationPrefix} runs
     * {@link #resolvePrefixes} before execution), but only when the runner was given a domain
     * prefix; {@link #resolveTemplate} / {@link #resolveWildcard} are applied here as the same
     * second chance {@code evalVariableCount} takes.
     * </p>
     */
    private static Boolean evalVariableExists(Operation op, IDataTable table,
            DatasetResolver resolver)
    {
        String name = resolveTemplate(op.getName(), table);
        if (name == null)
        {
            // No name (or an unresolvable `--`) asks about no column. Answered here rather than
            // through the delegate so a `domain` cannot compose the string "EX.null".
            return Boolean.FALSE;
        }
        String domain = resolveWildcard(op.getDomain(), table);
        String column = domain == null || domain.isEmpty() ? name : domain + "." + name;
        EvaluationContext ctx = EvaluationContext.builder().table(table).datasetResolver(resolver)
                .variables(new LinkedHashMap<>()).build();
        return Boolean.valueOf(OperatorRegistry.existsAsVariable(ctx, column));
    }


    /**
     * T1 — per-record dictionary membership. Builds a {@link GroupedResult} keyed by the
     * {@code name} column whose value for each distinct term is {@code true} when that term (or
     * code) is valid in the {@code external_dictionary_type} dictionary at the
     * {@code dictionary_term_type} level. When no dictionary of the type is loaded the operation is
     * unresolvable and returns {@code null} — the belt-and-suspenders leg of the no-false-PASS
     * contract, whose primary leg is the SKIP that has already fired: {@link RuleRunner}'s eager
     * dictionary arm for the declared ({@code $}-ref) form ({@code Fix #268}), the injected
     * {@code dictionary_available(<type>)} precondition gate for the inlined one. Mirrors the
     * Python {@code valid_external_dictionary_value} / {@code _code} operations (validity keyed per
     * distinct value, default {@code false} for an unseen value).
     *
     * <p>
     * D-TA-3 / Fix #266: the comparison is case-SENSITIVE unless the rule authors an explicit
     * {@code case_sensitive: false} — the sensitive default validates the term against the
     * dictionary's preferred case ({@code caseMatches}); the authored-insensitive path is folded
     * membership ({@code isValidTerm}).
     * </p>
     */
    private static @Nullable GroupedResult evalValidExternalDictionaryValue(Operation op,
            IDataTable table, @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        String type = op.getExternalDictionaryType();
        if (op.getName() == null || dictionaryProvider == null
                || !dictionaryProvider.isAvailable(type))
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return null; // absent column — nothing to validate, rule SKIPs
        }
        String level = op.getDictionaryTermType();
        // D-TA-3 / Fix #266: the default compare is case-SENSITIVE — a flag-less rule validates
        // against the dictionary's preferred case; insensitive intent must be visible in the rule
        // as an explicit `case_sensitive: false`.
        boolean caseSensitive = !Boolean.FALSE.equals(op.getCaseSensitive());
        IDataTableColumn col = table.getColumn(colIdx);
        long rowCount = table.getRowCount();
        List<String> groupCols = List.of(op.getName());
        Map<String, Object> results = new LinkedHashMap<>();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            String term = dv.isMissingOrInvalid() ? "" : dv.getValueAsString();
            // Membership is identical for a term value and its code in the value-map model (the
            // code sits in its own level). Sensitive (the default): validity requires the term to
            // match the dictionary's preferred case (caseMatches — the case-conformance rules,
            // e.g. SD0008C, author it explicitly as case_sensitive: true). Insensitive (explicit
            // case_sensitive: false, e.g. SD0008): folded membership only. A blank value is
            // treated as valid (no fire) — an absent term is a completeness concern for a
            // different rule, not a dictionary-membership failure; both engines agree so parity
            // holds.
            boolean valid = term.isEmpty()
                    || (caseSensitive ? dictionaryProvider.caseMatches(type, level, term)
                            : dictionaryProvider.isValidTerm(type, level, term));
            // ⚑ LOCKSTEP (W38-A1 / Fix #249): the map key comes from the same cell-classified
            // builder getForRow probes with, so a blank-keyed row always finds its own verdict —
            // keying by the folded term string would strand every missing-cell row on the group
            // default (a fire) the moment the probe stopped rendering a missing as "".
            results.computeIfAbsent(GroupedResult.buildKey(meta, table, groupCols, r), _ -> valid);
        }
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * T1 — per-record code&harr;decode pairing. Builds a {@link GroupedResult} keyed by the
     * {@code name} (code) column plus the {@code external_dictionary_term_variable} (decode/term)
     * column, whose value for each distinct pair is {@code true} when the dictionary maps that code
     * to that decode. Backs FDA SD2262 (TSVALCD&rarr;TSVAL against FDA-SRS/UNII) and the NEOPLASM
     * benign/malignant alignment SE2229 ({@code --STRESC}&rarr;{@code --RESCAT} against the
     * neoplasm attribute map). Returns {@code null} when the type is not loaded or a column is
     * absent (rule SKIPs). Mirrors the Python {@code valid_external_dictionary_code_term_pair}
     * operation. D-TA-3 / Fix #266: code and decode compare case-sensitively unless the rule
     * authors {@code case_sensitive: false}.
     */
    private static @Nullable GroupedResult evalValidExternalDictionaryCodeTermPair(Operation op,
            IDataTable table, @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        String type = op.getExternalDictionaryType();
        String termVar = op.getExternalDictionaryTermVariable();
        if (op.getName() == null || termVar == null || dictionaryProvider == null
                || !dictionaryProvider.isAvailable(type))
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int codeIdx = meta.getColumnIndex(op.getName());
        int termIdx = meta.getColumnIndex(termVar);
        if (codeIdx < 0 || termIdx < 0)
        {
            return null;
        }
        IDataTableColumn codeCol = table.getColumn(codeIdx);
        IDataTableColumn termCol = table.getColumn(termIdx);
        long rowCount = table.getRowCount();
        // D-TA-3 / Fix #266: flag-aware with a case-SENSITIVE default (pre-#266 the flag was
        // ignored and the code side compared case-folded while the decode compared verbatim).
        boolean caseSensitive = !Boolean.FALSE.equals(op.getCaseSensitive());
        List<String> groupCols = List.of(op.getName(), termVar);
        Map<String, Object> results = new LinkedHashMap<>();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue codeDv = codeCol.getDataValue(r);
            IDataValue termDv = termCol.getDataValue(r);
            String codeVal = codeDv.isMissingOrInvalid() ? "" : codeDv.getValueAsString();
            String termVal = termDv.isMissingOrInvalid() ? "" : termDv.getValueAsString();
            // A blank code OR blank decode is a valid pair (H1): completeness of the cell is a
            // different rule's concern, and a failed lookup on "" would otherwise false-fire the
            // `== false` consequent. Mirrors Python is_valid_code_term_pair
            // (value_map_validator.py):
            // `if code is None or code == "" or decode is None or decode == "": return True`.
            boolean paired = codeVal.isEmpty() || termVal.isEmpty() || dictionaryProvider
                    .codeDecodePair(type, type, codeVal, termVal, caseSensitive);
            // ⚑ LOCKSTEP (W38-A1 / Fix #249): cell-classified key, same builder as the probe —
            // see evalValidExternalDictionaryValue.
            results.computeIfAbsent(GroupedResult.buildKey(meta, table, groupCols, r), _ -> paired);
        }
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * T1 — per-record dictionary hierarchy-path membership. Builds a {@link GroupedResult} keyed by
     * the {@code name} (child term) column plus the {@code dictionary_parent} (candidate ancestor)
     * column, whose value for each distinct pair is {@code true} when the child lies on the
     * dictionary hierarchy path of — has as an ancestor — the parent. A blank child OR blank parent
     * is treated as {@code true} (no fire), so a missing cell never false-fires the
     * {@code == false} consequent — completeness is a different rule's concern. Returns
     * {@code null} when the type is not loaded or a column is absent (rule SKIPs). Mirrors the
     * Python {@code valid_external_dictionary_hierarchy} operation. D-TA-3 / Fix #266: child and
     * parent compare case-sensitively against the as-authored hierarchy unless the rule authors
     * {@code case_sensitive: false}.
     */
    private static @Nullable GroupedResult evalValidExternalDictionaryHierarchy(Operation op,
            IDataTable table, @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        String type = op.getExternalDictionaryType();
        String parentVar = op.getDictionaryParent();
        if (op.getName() == null || parentVar == null || dictionaryProvider == null
                || !dictionaryProvider.isAvailable(type))
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int childIdx = meta.getColumnIndex(op.getName());
        int parentIdx = meta.getColumnIndex(parentVar);
        if (childIdx < 0 || parentIdx < 0)
        {
            return null;
        }
        IDataTableColumn childCol = table.getColumn(childIdx);
        IDataTableColumn parentCol = table.getColumn(parentIdx);
        long rowCount = table.getRowCount();
        // D-TA-3 / Fix #266: flag-aware with a case-SENSITIVE default (pre-#266 the flag was
        // ignored and both operands were case-folded).
        boolean caseSensitive = !Boolean.FALSE.equals(op.getCaseSensitive());
        List<String> groupCols = List.of(op.getName(), parentVar);
        Map<String, Object> results = new LinkedHashMap<>();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue childDv = childCol.getDataValue(r);
            IDataValue parentDv = parentCol.getDataValue(r);
            String childVal = childDv.isMissingOrInvalid() ? "" : childDv.getValueAsString();
            String parentVal = parentDv.isMissingOrInvalid() ? "" : parentDv.getValueAsString();
            // A blank child OR blank parent is on-path (H1): completeness of the cell is a
            // different
            // rule's concern, and a failed lookup on "" would otherwise false-fire the `== false`
            // consequent. Mirrors Python on_hierarchy_path (value_map_validator.py):
            // `if child is None or child == "" or parent is None or parent == "": return True`.
            boolean onPath = childVal.isEmpty() || parentVal.isEmpty()
                    || dictionaryProvider.onHierarchyPath(type, childVal, parentVal, caseSensitive);
            // ⚑ LOCKSTEP (W38-A1 / Fix #249): cell-classified key, same builder as the probe —
            // see evalValidExternalDictionaryValue.
            results.computeIfAbsent(GroupedResult.buildKey(meta, table, groupCols, r), _ -> onPath);
        }
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * E1 — the CDISC-Library observation class of the domain named in each record's {@code name}
     * column (default {@code RDOMAIN}). Builds a {@link GroupedResult} keyed by that column; each
     * distinct domain value maps to its Library class ({@code MetadataProvider.getDatasetClass}),
     * or {@code ""} when the Library cannot classify it. Returns {@link #LIBRARY_NOT_AVAILABLE}
     * when no provider is configured (via {@link #evalLibrary}) or the referenced column is absent,
     * so the rule SKIPs. Backs FDA-SD0095 / PMDA-SD0095.
     */
    private static @Nullable Object evalReferencedDomainClass(
            @Nullable MetadataProvider libraryProvider, Operation op, IDataTable table,
            @Nullable String ruleId)
    {
        // Fix #369 — GROUPED_TEXT: this arm yields a GroupedResult mapping each referenced domain
        // to its Library class, or "" when the Library cannot classify it. On a degraded provider
        // EVERY entry is "", and a plain isResultAvailable() check would call that map an answer.
        return evalLibrary(libraryProvider, op, table, p ->
        {
            String col = op.getName() != null ? op.getName() : "RDOMAIN";
            int idx = table.getMetaData().getColumnIndex(col);
            if (idx < 0)
            {
                return LIBRARY_NOT_AVAILABLE; // referenced column absent — rule SKIPs
            }
            IDataTableColumn column = table.getColumn(idx);
            long rowCount = table.getRowCount();
            Map<String, Object> byRdomain = new LinkedHashMap<>();
            for (long r = 0; r < rowCount; r++)
            {
                IDataValue dv = column.getDataValue(r);
                if (dv.isMissingOrInvalid())
                {
                    continue;
                }
                String dom = dv.getValueAsString();
                byRdomain.computeIfAbsent(GroupedResult.buildKey(List.of(dom)), _ ->
                {
                    // Upper-cased so rules can compare against the canonical class tokens
                    // (EVENTS, FINDINGS ABOUT, ...) regardless of the provider tier: the
                    // Library product walk returns mixed-case names ('Events'), while the
                    // curated DomainClassMap and the Python fork are already upper-case.
                    String c = p.getDatasetClass(dom, dom);
                    return c != null ? c.toUpperCase(Locale.ROOT) : "";
                });
            }
            return declaredGrouped(op, List.of(col), byRdomain);
        }, ruleId, LibraryArmAnswer.GROUPED_TEXT);
    }


    /**
     * E6 — ISO-8601 interval-of-uncertainty precision comparator. Builds a {@link GroupedResult}
     * keyed by the {@code name} column whose per-value verdict is {@code true} (fires) when the
     * value contains the {@code delimiter} (default {@code "/"}) and the two halves carry a
     * different ISO-8601 precision tier ({@link ScalarSemantics#detectIsoPrecision}, measured after
     * timezone and fractional-second stripping); {@code false} (no fire) when there is no delimiter
     * or either half is blank. Returns {@code null} (rule SKIPs) when the {@code name} column is
     * absent. Backs CDISC-SEND-0070.
     */
    private static @Nullable GroupedResult evalIntervalUncertaintyPrecisionMismatch(Operation op,
            IDataTable table)
    {
        if (op.getName() == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int idx = meta.getColumnIndex(op.getName());
        if (idx < 0)
        {
            return null;
        }
        String delimiter = op.getDelimiter() != null && !op.getDelimiter().isEmpty()
                ? op.getDelimiter()
                : "/";
        IDataTableColumn col = table.getColumn(idx);
        long rowCount = table.getRowCount();
        List<String> groupCols = List.of(op.getName());
        Map<String, Object> results = new LinkedHashMap<>();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            String value = dv.isMissingOrInvalid() ? "" : dv.getValueAsString();
            // ⚑ LOCKSTEP (W38-A1 / Fix #249): cell-classified key, same builder as the probe —
            // see evalValidExternalDictionaryValue.
            results.computeIfAbsent(GroupedResult.buildKey(meta, table, groupCols, r),
                    _ -> intervalPrecisionMismatch(value, delimiter));
        }
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * Whether an ISO-8601 interval-of-uncertainty value's two halves (split on {@code delimiter})
     * carry different precision tiers. No delimiter, or either half blank, ⇒ {@code false} (no
     * fire).
     */
    private static boolean intervalPrecisionMismatch(String value, String delimiter)
    {
        int cut = value.indexOf(delimiter);
        if (cut < 0)
        {
            return false;
        }
        String head = value.substring(0, cut);
        String tail = value.substring(cut + delimiter.length());
        if (head.isEmpty() || tail.isEmpty())
        {
            return false;
        }
        return intervalHalfPrecision(head) != intervalHalfPrecision(tail);
    }


    /**
     * Precision tier of one half of an interval of uncertainty.
     * {@link ScalarSemantics#detectIsoPrecision} buckets purely on string length and is documented
     * as operating on an already-timezone-stripped value, so a UTC offset or a fractional-seconds
     * tail carried on one half only would otherwise read as a precision difference
     * ({@code 2003-12-15T10:00+02:00} is length 22 ⇒ tier 19, against length 16 ⇒ tier 16 for
     * {@code 2003-12-15T10:30}, though both are minute precision). Normalises exactly as
     * {@code CalendarDates.isValidDate} does.
     *
     * <p>
     * ⚠ Deliberately <em>not</em> {@code IsoDateBounds.core}: that normalisation applies the offset
     * instant-preserving, which can re-render the value into a different tier. SEND70 asks about
     * the <em>representation</em> of the two halves ("the completeness of the representation … must
     * be the same on both sides of the solidus"), not about the instant they denote.
     */
    private static int intervalHalfPrecision(String half)
    {
        return ScalarSemantics.detectIsoPrecision(
                ScalarSemantics.stripFractionalSeconds(ScalarSemantics.stripTimezone(half)));
    }


    /**
     * E8 — WHODrug decode-presence precondition. Builds a {@link GroupedResult} keyed by the
     * {@code name} column whose per-value verdict is {@code true} when the
     * {@code external_dictionary_type} dictionary holds any decode for the code (a
     * {@code containsKey} over its {@code pairs}/{@code attributes} registries). A blank code ⇒
     * {@code false} (no fire). Returns {@code null} (rule SKIPs) when no dictionary of the type is
     * loaded or the column is absent. Backs CDISC-CG0096; mirrors the blank/absence discipline of
     * {@link #evalValidExternalDictionaryHierarchy}. D-TA-3 / Fix #266: the code lookup is
     * case-sensitive unless the rule authors {@code case_sensitive: false}.
     */
    private static @Nullable GroupedResult evalDictionaryHasDecode(Operation op, IDataTable table,
            @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        String type = op.getExternalDictionaryType();
        if (op.getName() == null || dictionaryProvider == null
                || !dictionaryProvider.isAvailable(type))
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int idx = meta.getColumnIndex(op.getName());
        if (idx < 0)
        {
            return null;
        }
        IDataTableColumn col = table.getColumn(idx);
        long rowCount = table.getRowCount();
        // D-TA-3 / Fix #266: flag-aware with a case-SENSITIVE default (pre-#266 the flag was
        // ignored and the code lookup was case-folded).
        boolean caseSensitive = !Boolean.FALSE.equals(op.getCaseSensitive());
        List<String> groupCols = List.of(op.getName());
        Map<String, Object> results = new LinkedHashMap<>();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            String code = dv.isMissingOrInvalid() ? "" : dv.getValueAsString();
            // A blank code holds no decode (no fire). reg defaults to the dictionary type, as in
            // evalValidExternalDictionaryCodeTermPair.
            // ⚑ LOCKSTEP (W38-A1 / Fix #249): cell-classified key, same builder as the probe —
            // see evalValidExternalDictionaryValue.
            results.computeIfAbsent(GroupedResult.buildKey(meta, table, groupCols, r),
                    _ -> !code.isEmpty()
                            && dictionaryProvider.hasDecode(type, type, code, caseSensitive));
        }
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * E10 — split-family declared-length divergence. Enumerates the split-family members (every
     * available dataset whose data-driven unsplit name equals the current table's, via
     * {@link #unsplitNameFromData}) and returns the variables whose declared column length
     * ({@link net.cumba.datatable.DataTableColumnMeta#getLength()}) differs across the members it
     * appears in. Returns an empty list when the dataset is not split (fewer than two members) or
     * every shared variable has a uniform length, and when the resolver cannot enumerate the study.
     * Library-INDEPENDENT — needs only a {@link DatasetResolver.WithInventory}.
     */
    private static List<String> evalSplitSiblingLengthMismatch(IDataTable table,
            DatasetResolver resolver)
    {
        if (!(resolver instanceof DatasetResolver.WithInventory inv))
        {
            return List.of();
        }
        String family = unsplitNameFromData(table);
        List<IDataTable> members = new ArrayList<>();
        for (String name : inv.availableDatasets())
        {
            IDataTable ds = resolver.resolve(name);
            if (ds != null && family.equals(unsplitNameFromData(ds)))
            {
                members.add(ds);
            }
        }
        if (members.size() < 2)
        {
            return List.of();
        }
        // Gather the distinct declared lengths per variable across the members it appears in.
        Map<String, Set<Integer>> lengthsByVar = new LinkedHashMap<>();
        for (IDataTable ds : members)
        {
            DataTableMeta meta = ds.getMetaData();
            int colCount = meta.getColumnCount();
            for (int c = 0; c < colCount; c++)
            {
                net.cumba.datatable.DataTableColumnMeta colMeta = meta.getColumn(c);
                lengthsByVar.computeIfAbsent(colMeta.getName(), _ -> new LinkedHashSet<>())
                        .add(colMeta.getLength());
            }
        }
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> e : lengthsByVar.entrySet())
        {
            if (e.getValue().size() > 1)
            {
                mismatched.add(e.getKey());
            }
        }
        return mismatched;
    }


    /**
     * E7 — duplicate variable-label detection. Groups the current table's columns by their declared
     * label ({@link net.cumba.datatable.DataTableColumnMeta#getLabel()}) and returns the variable
     * NAMES whose label bucket holds more than one variable. Columns with no label (null / blank)
     * are ignored (a blank label is not a meaningful duplicate). Returns an empty list when every
     * label is unique. Consumed as a membership right-hand side
     * ({@code variable_name is_contained_by $result}). Backs CDISC-SEND-0273.
     */
    private static List<String> evalDuplicateLabelVariables(IDataTable table)
    {
        DataTableMeta meta = table.getMetaData();
        int colCount = meta.getColumnCount();
        // Preserve column order for both the bucketing and the emitted name list.
        Map<String, List<String>> namesByLabel = new LinkedHashMap<>();
        for (int c = 0; c < colCount; c++)
        {
            net.cumba.datatable.DataTableColumnMeta colMeta = meta.getColumn(c);
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
        return duplicates;
    }


    /**
     * E7 — numbered column-series completeness / continuation check (e.g. {@code COVAL1..n}).
     * Selects the series members (columns whose name matches {@code name_pattern}, plus the
     * optional un-numbered {@code name} base column as suffix 0), parses each member's trailing
     * integer suffix, and returns a dataset-level {@code Boolean} that is {@code true} ("series
     * incomplete", the check fires) when the present suffixes are not contiguous from lowest to
     * highest, or when {@code min_length} is set and a non-terminal member declares a length below
     * it. Returns {@code false} when fewer than two members are present. See
     * {@link net.cumba.cdisc.core.model.OperationType#COLUMN_SERIES_METADATA}.
     */
    private static @Nullable Object evalColumnSeriesMetadata(Operation op, IDataTable table)
    {
        String pattern = op.getNamePattern();
        if (pattern == null || pattern.isEmpty())
        {
            return null;
        }
        Pattern compiled;
        try
        {
            compiled = Pattern.compile(pattern);
        }
        catch (java.util.regex.PatternSyntaxException _)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colCount = meta.getColumnCount();
        // suffix -> declared length, keyed by the parsed trailing integer of each matched member.
        Map<Integer, Integer> lengthBySuffix = new java.util.TreeMap<>();
        String base = op.getName();
        for (int c = 0; c < colCount; c++)
        {
            net.cumba.datatable.DataTableColumnMeta colMeta = meta.getColumn(c);
            String name = colMeta.getName();
            Integer suffix = null;
            if (base != null && base.equals(name))
            {
                suffix = Integer.valueOf(0);
            }
            else if (compiled.matcher(name).matches())
            {
                suffix = trailingInteger(name);
            }
            if (suffix != null)
            {
                lengthBySuffix.putIfAbsent(suffix, Integer.valueOf(colMeta.getLength()));
            }
        }
        if (lengthBySuffix.size() < 2)
        {
            return false;
        }
        List<Integer> suffixes = new ArrayList<>(lengthBySuffix.keySet());
        int lo = suffixes.get(0);
        int hi = suffixes.get(suffixes.size() - 1);
        // Gap detection: the contiguous run [lo..hi] must be fully present.
        if (hi - lo + 1 != lengthBySuffix.size())
        {
            return true;
        }
        // Continuation length: every non-terminal member must reach min_length (when specified).
        Integer minLength = op.getMinLength();
        if (minLength != null)
        {
            for (Map.Entry<Integer, Integer> e : lengthBySuffix.entrySet())
            {
                if (e.getKey().intValue() != hi && e.getValue().intValue() < minLength.intValue())
                {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * The trailing run of ASCII digits in {@code name} as an {@code Integer}, or {@code null} when
     * the name has no trailing digits. Used by {@link #evalColumnSeriesMetadata} to order a
     * numbered column series ({@code COVAL1} → {@code 1}).
     */
    private static @Nullable Integer trailingInteger(String name)
    {
        int end = name.length();
        int i = end;
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
        {
            i--;
        }
        if (i == end)
        {
            return null;
        }
        try
        {
            return Integer.valueOf(name.substring(i));
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }


    /**
     * T7 — TS/TX-parameter scalar lookup. Resolves the {@code name} column value (e.g.
     * {@code TSVAL}) of the first row in the parameter dataset ({@code domain}, default
     * {@code "TS"}) whose {@code key_name} column (e.g. {@code TSPARMCD}) equals {@code key_value}
     * (e.g. {@code EXPSTDTC}). The returned scalar is broadcast to every row by the caller and used
     * as the {@code value} operand of a date / comparison operator. Returns {@code null} when the
     * parameter dataset is absent, the {@code key_name} or {@code name} column is missing, or no
     * row matches — so the dependent comparison operand is null and no row fires (rule SKIPs).
     * Mirrors the Python reference engine's {@code ts_parameter_value} ({@code df[target].iloc[0]}
     * or {@code None}).
     */
    private static @Nullable Object evalTsParameterValue(Operation op, DatasetResolver resolver)
    {
        String keyName = op.getKeyName();
        String targetCol = op.getName();
        if (keyName == null || targetCol == null)
        {
            return null;
        }
        String domain = op.getDomain() != null && !op.getDomain().isEmpty() ? op.getDomain() : "TS";
        IDataTable ts = resolver.resolve(domain);
        if (ts == null)
        {
            return null;
        }
        DataTableMeta meta = ts.getMetaData();
        int keyIdx = meta.getColumnIndex(keyName);
        int targetIdx = meta.getColumnIndex(targetCol);
        if (keyIdx < 0 || targetIdx < 0)
        {
            return null;
        }
        String keyValue = op.getKeyValue();
        IDataTableColumn keyCol = ts.getColumn(keyIdx);
        IDataTableColumn valCol = ts.getColumn(targetIdx);
        long rowCount = ts.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue kv = keyCol.getDataValue(r);
            String k = kv.isMissingOrInvalid() ? null : kv.getValueAsString();
            if (Objects.equals(k, keyValue))
            {
                IDataValue vv = valCol.getDataValue(r);
                return vv.isMissingOrInvalid() ? null : vv.getValueAsString();
            }
        }
        return null;
    }


    /**
     * T8 — SUPP-- QNAM-scoped presence / value join to the parent record. The primary {@code table}
     * is the parent findings/interventions/events dataset (e.g. {@code PC}); {@code op.getDomain()}
     * names the supplemental dataset (e.g. {@code "SUPPPC"}) and {@code op.getKeyValue()} the
     * target {@code QNAM} (e.g. {@code "PCCALCN"}). Filters the supplemental dataset to rows with
     * {@code QNAM == key_value}, resolves each such row's {@code IDVAR}/{@code IDVARVAL} against
     * the parent (matching {@code parent[IDVAR] == IDVARVAL} within the same {@code USUBJID}), and
     * returns a per-parent-row {@link GroupedResult} keyed by {@code USUBJID} + the resolved
     * {@code IDVAR} column.
     *
     * <p>
     * When {@code present} is {@code true} the values are {@link Boolean#TRUE} for a matched parent
     * record with the group default {@code false} (a parent record with no matching supplemental
     * row is "not present"). When {@code present} is {@code false} the values are the matching
     * {@code QVAL} strings with a {@code null} group default. A supplemental dataset that is
     * present but carries a missing {@code QNAM}/{@code IDVAR}/{@code IDVARVAL} column or no
     * matching {@code QNAM} row degrades to an empty {@link GroupedResult} — every parent record
     * resolves to the group default, so a {@code == false} consequent fires. A supplemental dataset
     * that is entirely absent returns {@code null} so the rule SKIPs (in practice unreachable —
     * {@code executeOne}'s {@code resolveTargetTable} resolves {@code op.getDomain()} and returns
     * {@code null} before dispatch when the SUPP dataset is absent).
     * </p>
     *
     * <p>
     * A single supplemental {@code QNAM} in real data is always delivered against one {@code IDVAR}
     * (the parent {@code --SEQ}); should the matched rows reference more than one {@code IDVAR},
     * the first-seen {@code IDVAR} anchors the join and the divergent rows are dropped with a
     * warning (the {@link GroupedResult} keys on a single parent column).
     * </p>
     */
    private static @Nullable Object evalSuppQnamJoin(Operation op, DatasetResolver resolver,
            boolean present)
    {
        String qnam = op.getKeyValue();
        String suppDomain = op.getDomain();
        if (qnam == null || suppDomain == null || suppDomain.isEmpty())
        {
            return null;
        }
        IDataTable supp = resolver.resolve(suppDomain);
        if (supp == null)
        {
            // The supplemental dataset is not in the study — the operation is unresolvable and the
            // rule SKIPs (null operand, no row fires), the same "absent domain ⇒ skip" contract as
            // ts_parameter_value. (In practice unreachable: executeOne's resolveTargetTable already
            // resolves op.getDomain() and returns null before dispatch when it is absent.)
            return null;
        }
        // A SUPP dataset that IS present but carries no matching QNAM row resolves to an empty
        // per-parent-row GroupedResult keyed by USUBJID (default false / null) rather than a
        // broadcast scalar, so the "qualifier absent for this record" outcome reads the per-row
        // default on the identical path as a populated join.
        GroupedResult empty = new GroupedResult(List.of(USUBJID), Map.of(), present ? false : null);
        DataTableMeta sm = supp.getMetaData();
        int qnamIdx = sm.getColumnIndex("QNAM");
        int idvarIdx = sm.getColumnIndex("IDVAR");
        int idvarvalIdx = sm.getColumnIndex("IDVARVAL");
        int usubjidIdx = sm.getColumnIndex(USUBJID);
        int qvalIdx = present ? -1 : sm.getColumnIndex("QVAL");
        if (qnamIdx < 0 || idvarIdx < 0 || idvarvalIdx < 0)
        {
            return empty;
        }
        String anchorIdvar = null;
        Map<String, Object> results = new LinkedHashMap<>();
        int dropped = 0;
        long rowCount = supp.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            if (!qnam.equals(stringAt(supp, qnamIdx, r)))
            {
                continue;
            }
            String idvar = stringAt(supp, idvarIdx, r);
            String idvarval = stringAt(supp, idvarvalIdx, r);
            if (idvar == null || idvar.isEmpty() || idvarval == null)
            {
                continue;
            }
            if (anchorIdvar == null)
            {
                anchorIdvar = idvar;
            }
            else if (!anchorIdvar.equals(idvar))
            {
                dropped++;
                continue;
            }
            String usubjid = usubjidIdx < 0 ? "" : stringAt(supp, usubjidIdx, r);
            String key = GroupedResult.buildKey(List.of(usubjid == null ? "" : usubjid, idvarval));
            if (present)
            {
                results.put(key, true);
            }
            else
            {
                String qval = qvalIdx < 0 ? null : stringAt(supp, qvalIdx, r);
                results.putIfAbsent(key, qval);
            }
        }
        if (dropped > 0)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "supp_qnam_{0} in {1} (QNAM={2}) saw {3} row(s) on a second IDVAR — anchored on "
                            + "''{4}'', divergent rows dropped",
                    present ? "present" : "value", suppDomain, qnam, dropped, anchorIdvar);
        }
        if (anchorIdvar == null)
        {
            // No matching QNAM row at all — the qualifier is absent for every parent record.
            return empty;
        }
        List<String> groupCols = List.of(USUBJID, anchorIdvar);
        return present ? new GroupedResult(groupCols, results, false)
                : new GroupedResult(groupCols, results);
    }


    /**
     * Reads a table cell as a string, or {@code null} when the column index is negative / missing.
     */
    private static @Nullable String stringAt(IDataTable table, int colIdx, long row)
    {
        if (colIdx < 0)
        {
            return null;
        }
        IDataValue dv = table.getColumn(colIdx).getDataValue(row);
        return dv.isMissingOrInvalid() ? null : dv.getValueAsString();
    }


    /**
     * EC-28(b) / Fix #131 — resolves {@code --} wildcards in an Operation's {@code filter:} KEYS.
     *
     * <p>
     * A filter key names a column, so it takes the <em>variable</em> prefix — the same side-of-dot
     * rule {@code name} / {@code names} follow (EC-36 / Fix #125): {@code ""} for SUPP/SQ, the
     * 2-character AP suffix for AP datasets, and the caller-supplied domain code otherwise. Values
     * are left untouched: they are data literals, not column references.
     * </p>
     *
     * <p>
     * Before this, {@code resolvePrefixes} copied the map verbatim, so a {@code --}-keyed filter
     * reached {@code rowMatchesFilter} raw. Since no column is ever literally named {@code --XYZ},
     * the filter matched nothing and reported nothing — a silent trap that made the FDA-SD1240
     * re-shape ({@code USUBJID is_not_contained_by $filtered_distinct}) unauthorable for wildcard
     * columns. Zero corpus rules use a {@code --} filter key today precisely because it did not
     * work; this is enabling work.
     * </p>
     *
     * <p>
     * Decision D8: a key that resolves to a non-existent column stays <b>silent</b> (it simply
     * matches no row), consistent with coreJ's general absent-column fold. A rule that needs to be
     * skipped instead authors a {@code Scope.Variables} gate.
     * </p>
     */
    private static @Nullable Map<String, Object> resolveFilterKeys(
            @Nullable Map<String, Object> filter, String variablePrefix)
    {
        if (filter == null || filter.isEmpty())
        {
            return filter;
        }
        boolean anyWildcard = false;
        for (String key : filter.keySet())
        {
            if (key != null && key.contains("--"))
            {
                anyWildcard = true;
                break;
            }
        }
        if (!anyWildcard)
        {
            return filter;
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : filter.entrySet())
        {
            String key = e.getKey();
            resolved.put(
                    key != null && key.contains("--") ? key.replace("--", variablePrefix) : key,
                    e.getValue());
        }
        return resolved;
    }


    private static @Nullable Object evalMax(Operation op, IDataTable table)
    {
        if (op.getName() == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return null;
        }
        IDataTableColumn col = table.getColumn(colIdx);
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();
        long rowCount = table.getRowCount();
        double max = Double.NaN;
        for (long r = 0; r < rowCount; r++)
        {
            if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
            {
                continue;
            }
            IDataValue dv = col.getDataValue(r);
            if (!dv.isMissingOrInvalid())
            {
                double val = dv.getValueAsDouble();
                if (!Double.isNaN(val))
                {
                    max = Double.isNaN(max) ? val : Math.max(max, val);
                }
            }
        }
        if (!Double.isNaN(max))
        {
            return max;
        }
        // Fallback: string comparison (handles date strings like ISO 8601).
        // EC-46 OQ4 — GENERIC semantics, not date semantics: this fallback also serves plain Char
        // columns, so it must not require a calendar position. See genericStringExtreme.
        return genericStringExtreme(ungroupedCandidates(op, table), true);
    }


    /** The non-blank cells of {@code op}'s column, honouring its {@code filter:}, in row order. */
    private static List<String> ungroupedCandidates(Operation op, IDataTable table)
    {
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(Objects.requireNonNull(op.getName()));
        if (colIdx < 0)
        {
            return List.of();
        }
        IDataTableColumn col = table.getColumn(colIdx);
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();
        long rowCount = table.getRowCount();
        List<String> candidates = new ArrayList<>();
        for (long r = 0; r < rowCount; r++)
        {
            if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
            {
                continue;
            }
            String val = extremeCandidate(col.getDataValue(r));
            if (val != null)
            {
                candidates.add(val);
            }
        }
        return candidates;
    }


    /**
     * EC-46 OQ4 — the extreme for the <b>generic</b> {@code max}/{@code min} string fallback, which
     * is <i>not</i> date-only.
     *
     * <p>
     * Measured over the shipped corpus, the generic {@code max()} reaches this path for
     * {@code ANRIND} (5 rules) and {@code ATOXGR} (4) — Char <i>category</i> columns;
     * {@code AVAL}/{@code DSSTDY} take the numeric branch. Applying date semantics unconditionally
     * would make those 9 rules yield no value at all, because a category code cannot be positioned
     * on a calendar. So EC-46's rule is applied only when the group is unambiguously dates — every
     * candidate positionable, which no category column satisfies — and plain lexicographic order is
     * kept otherwise, exactly as before.
     * </p>
     *
     * <p>
     * ⚠ <b>Known limit:</b> a genuine date column carrying a junk token ({@code UNK}) fails the
     * all-dates test and so keeps lexicographic treatment — Defect E is not caught on <i>this</i>
     * path. That is acceptable because the generic operator has no date consumer left:
     * {@code CORE-000717}, its only one, now authors {@code max_date} (EC-46 OQ4) and runs through
     * {@link #evalDateExtreme}, where the rule applies in full. The routing here is
     * forward-looking.
     * </p>
     */
    private static @Nullable String genericStringExtreme(List<String> candidates, boolean findMax)
    {
        if (candidates.isEmpty())
        {
            return null;
        }
        if (candidates.stream().allMatch(IsoDateBounds::canPosition))
        {
            // EC-51 Half B: `false` is permanent here, not a default. This is the GENERIC
            // max fallback, whose operator cannot declare `missing_values` at all
            // (OperationExpressionParser.validateMissingValues) — and the list it is handed has
            // already had its missing cells dropped, so the disposition has nothing left to see.
            DateExtreme extreme = new DateExtreme(findMax, false);
            candidates.forEach(extreme::add);
            return extreme.result();
        }
        Comparator<String> order = Comparator.naturalOrder();
        return candidates.stream().max(findMax ? order : order.reversed()).orElse(null);
    }


    private static @Nullable String evalMaxDate(Operation op, IDataTable table)
    {
        return evalDateExtreme(op, table, true);
    }


    private static @Nullable String evalMinDate(Operation op, IDataTable table)
    {
        return evalDateExtreme(op, table, false);
    }


    /**
     * EC-51 — the single candidate filter shared by every extreme selector. Returns the cell's
     * <b>raw</b> text when it may serve as a candidate, or {@code null} when it may not.
     *
     * <p>
     * Before EC-51 this test was re-inlined at five sites in three syntactic shapes, and nothing in
     * the rule layer declared it: {@code Operation} has no field for it, and a {@code Check}
     * conjunct filters the <i>evaluation</i> table while an extreme is taken over the
     * <i>foreign</i> one. An operation {@code filter:} cannot express it either — it is
     * equality/membership only ({@code rowMatchesFilter}), so bare non-emptiness is not
     * expressible, though filtering <i>on</i> a column does incidentally drop rows where that
     * column is blank.
     * </p>
     *
     * <p>
     * <b>Deliberately stricter than {@link ScalarSemantics#isMissing}, which does not strip.</b> A
     * whitespace-only cell used to win every {@code min} — {@code " "} sorts below every digit —
     * and, at {@code row_max}/{@code row_min}, its mere presence forced {@code rowExtreme} out of
     * numeric mode (its gate needs <i>every</i> value to match {@code ROW_EXTREME_NUMERIC}), so a
     * {@code max} could move too. Providers are expected to right-trim, but a stray blank must not
     * decide a group's extreme. {@code isMissing} itself is left untouched: its 37 call sites carry
     * {@code empty}/{@code non_empty}, grouping keys and comparison folding, and widening those is
     * not this change.
     * </p>
     *
     * <p>
     * <b>Blankness is defined explicitly rather than via {@code String.strip()}</b>, because
     * {@code strip()} follows {@code Character.isWhitespace}, which excludes the non-breaking
     * spaces that Python's {@code str.strip()} removes. Leaving it to the two runtimes' defaults
     * would make an NBSP-only cell a candidate on one lane and not the other.
     * </p>
     *
     * <p>
     * The <b>raw</b> string is returned, never a trimmed one, so the selected extreme stays the
     * verbatim cell text (Fix #137, {@code Q2 = raw string}). Mirrored in the fork by
     * {@code BaseOperation._extreme_candidate}; the two must stay in step.
     * </p>
     */
    private static @Nullable String extremeCandidate(@Nullable IDataValue dv)
    {
        if (dv == null || dv.isMissingOrInvalid())
        {
            return null;
        }
        String s = dv.getValueAsString();
        return s == null || isBlank(s) ? null : s;
    }


    /**
     * EC-51 — {@code true} when every code point is blank, using a set that matches Python's
     * {@code str.strip()}: {@link Character#isWhitespace} plus the four separators it excludes —
     * NEL ({@code U+0085}), NBSP ({@code U+00A0}), FIGURE SPACE ({@code U+2007}) and NARROW NBSP
     * ({@code U+202F}). An empty string is blank.
     */
    private static boolean isBlank(String s)
    {
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp) || cp == 0x0085
                || cp == 0x00A0 || cp == 0x2007 || cp == 0x202F);
    }

    /**
     * EC-46 — accumulates date-extreme candidates and yields the extreme <b>only when it is
     * determinate</b>.
     *
     * <p>
     * The rule (EC-46 §4.2, verdicts OQ1–OQ3):
     * </p>
     *
     * <blockquote>The extreme yields a value only when a <i>determined</i> candidate wins against
     * every possible completion of every other candidate.</blockquote>
     *
     * <p>
     * Operationally: track (a) the best determined candidate by its own bound and (b) the extreme
     * bound over <i>all</i> candidates. The result is the former iff it reaches the latter. Both
     * accumulations are associative, so the two logical passes run in one loop and the outcome does
     * not depend on row order — which a running {@code compareTo} fold could not guarantee
     * ({@code {2012-06-02, 2012-06-01, 2012-06}} mis-answers pairwise).
     * </p>
     *
     * <p>
     * A present-but-unpositionable candidate (year-masked, junk token, structurally-invalid date)
     * needs <b>no special case</b>: its hull is unbounded, so no determined candidate can reach it
     * and the group yields nothing. That is EC-46 OQ6/OQ7's "it wins" outcome, arrived at without a
     * short-circuit — and, unlike a short-circuit, it never returns the junk token as the value.
     * </p>
     *
     * <p>
     * <b>Missing candidates are the rule's business, not this class's</b> —
     * {@link #extremeCandidate} (EC-51 Half A, Fix #141) drops them, on both lanes, and by default
     * an empty cell stays <i>skipped</i> while only a present unusable value makes the extreme
     * indeterminate. Inverting that unconditionally would make nearly every group indeterminate,
     * which is why EC-51 Half B (Fix #145) makes it a per-operation <b>declaration</b>
     * ({@code missing_values: indeterminate}) rather than a new default. Both dispositions land in
     * the same {@link #unbounded} state, so there is one determinability rule, not two.
     * </p>
     */
    private static final class DateExtreme
    {

        private final boolean findMax;

        /**
         * EC-51 Half B — {@code true} when the declaring operation says a missing candidate makes
         * the extreme undeterminable ({@code missing_values: indeterminate}); {@code false} for the
         * default {@code skip}.
         */
        private final boolean missingIsIndeterminate;

        /** Raw cell text of the best determined candidate — the value ultimately returned. */
        private @Nullable String best;

        /** {@code best}'s own bound: upper for a max, lower for a min. */
        private @Nullable String bestBound;

        /** The extreme bound across every candidate, determined or not. */
        private @Nullable String limit;

        /** Set when some candidate cannot be positioned at all, i.e. the hull is unbounded. */
        private boolean unbounded;

        DateExtreme(boolean findMax, boolean missingIsIndeterminate)
        {
            this.findMax = findMax;
            this.missingIsIndeterminate = missingIsIndeterminate;
        }


        /**
         * EC-51 — the single entry point for a <b>raw cell</b>: it applies the shared candidate
         * filter ({@link #extremeCandidate}) and this accumulator's missing-value disposition, so
         * no selector site re-inlines either. A missing cell is dropped under {@code skip} and
         * makes the extreme undeterminable under {@code indeterminate}.
         */
        void addCell(@Nullable IDataValue dv)
        {
            String raw = extremeCandidate(dv);
            if (raw == null)
            {
                if (missingIsIndeterminate)
                {
                    unbounded = true;
                }
                return;
            }
            add(raw);
        }


        void add(String raw)
        {
            String bound = findMax ? IsoDateBounds.upper(raw) : IsoDateBounds.lower(raw);
            if (bound == null)
            {
                unbounded = true;
                return;
            }
            if (limit == null || beats(bound, limit))
            {
                limit = bound;
            }
            if (IsoDateBounds.isDetermined(raw) && (bestBound == null || beats(bound, bestBound)))
            {
                best = raw;
                bestBound = bound;
            }
        }


        /** {@code null} when the extreme cannot be determined — the caller then emits no value. */
        @Nullable
        String result()
        {
            if (unbounded || best == null || bestBound == null || limit == null)
            {
                return null;
            }
            // The winner must reach the extreme of every rival's hull. Equality counts: it is the
            // non-strict case that makes min{2012-06, 2012-06-01} resolve to the complete date
            // (OQ1) and max{2012-06, 2012-06-30} to the complete date (the benign tie).
            return bestBound.equals(limit) ? best : null;
        }


        private boolean beats(String a, String b)
        {
            return findMax ? a.compareTo(b) > 0 : a.compareTo(b) < 0;
        }

    }

    private static @Nullable String evalDateExtreme(Operation op, IDataTable table, boolean findMax)
    {
        if (op.getName() == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return null;
        }
        IDataTableColumn col = table.getColumn(colIdx);
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();
        long rowCount = table.getRowCount();
        DateExtreme extreme = new DateExtreme(findMax, missingIsIndeterminate(op));
        for (long r = 0; r < rowCount; r++)
        {
            if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
            {
                continue;
            }
            // A filtered-out row is not a candidate at all, so it is never "a missing candidate":
            // the disposition applies to the rows the operation actually reads.
            extreme.addCell(col.getDataValue(r));
        }
        return extreme.result();
    }


    /**
     * EC-51 Half B — {@code true} when {@code op} declares {@code missing_values: indeterminate},
     * i.e. a missing candidate makes its extreme undeterminable rather than being skipped.
     * {@code null} (the overwhelming majority) and {@code "skip"} both mean today's behaviour;
     * every other value has already been rejected at load by
     * {@link net.cumba.cdisc.core.expr.convert.OperationExpressionParser#validateMissingValues}, so
     * this is an exact match rather than {@code reference_extreme}'s lenient
     * {@code equalsIgnoreCase}.
     */
    private static boolean missingIsIndeterminate(Operation op)
    {
        return Operation.MISSING_VALUES_INDETERMINATE.equals(op.getMissingValues());
    }

    // -----------------------------------------------------------------------
    // Grouped variants: produce GroupedResult instead of a single value
    // -----------------------------------------------------------------------


    private static @Nullable GroupedResult evalDateExtremeGrouped(Operation op, IDataTable table,
            boolean findMax, @Nullable List<String> groupCols, @Nullable String ruleId)
    {
        if (op.getName() == null || groupCols == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return null;
        }
        IDataTableColumn col = table.getColumn(colIdx);
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();

        // EC-44: absent group columns are ignored; all absent ⇒ the dataset is one group.
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, groupCols,
                groupLogContext(ruleId, op), groupKeyPolicy(op, GroupKeyPolicy.KEEP_MISSING_KEYS));
        if (grouping == null)
        {
            return null; // an unexpanded $-ref in the group list — not a dataset-shape fact
        }

        Map<String, Object> results = new LinkedHashMap<>();
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            String key = block.key();

            DateExtreme extreme = new DateExtreme(findMax, missingIsIndeterminate(op));
            for (int r : block.rows())
            {
                if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
                {
                    continue;
                }
                extreme.addCell(col.getDataValue(r));
            }
            // EC-46: an indeterminate group emits NO KEY, exactly as an all-blank group already
            // did. Downstream that is "no value for this subject", and the consumer decides.
            // EC-51 Half B routes a MISSING candidate into the same state, on declaration.
            String resolved = extreme.result();
            if (resolved != null)
            {
                results.put(key, resolved);
            }
        }
        return declaredGrouped(op, groupCols, results);
    }


    private static @Nullable GroupedResult evalMaxGrouped(Operation op, IDataTable table,
            @Nullable List<String> groupCols, @Nullable String ruleId)
    {
        if (op.getName() == null || groupCols == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return null;
        }
        IDataTableColumn col = table.getColumn(colIdx);
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();

        // EC-44: absent group columns are ignored; all absent ⇒ the dataset is one group. The
        // grouping is computed once and reused by both passes below.
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, groupCols,
                groupLogContext(ruleId, op), groupKeyPolicy(op, GroupKeyPolicy.KEEP_MISSING_KEYS));
        if (grouping == null)
        {
            return null; // an unexpanded $-ref in the group list — not a dataset-shape fact
        }

        // Try numeric max first
        Map<String, Object> results = new LinkedHashMap<>();
        boolean anyNumeric = false;
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            double max = Double.NaN;
            for (int r : block.rows())
            {
                if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
                {
                    continue;
                }
                IDataValue dv = col.getDataValue(r);
                if (dv.isMissingOrInvalid())
                {
                    continue;
                }
                double val = dv.getValueAsDouble();
                if (!Double.isNaN(val))
                {
                    anyNumeric = true;
                    max = Double.isNaN(max) ? val : Math.max(max, val);
                }
            }
            if (!Double.isNaN(max))
            {
                results.put(block.key(), max);
            }
        }
        if (anyNumeric)
        {
            return declaredGrouped(op, groupCols, results);
        }
        // Fallback: string comparison (handles date strings like ISO 8601). EC-46 OQ4 — GENERIC
        // semantics, the grouped twin of evalMax's fallback; see genericStringExtreme.
        results.clear();
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            List<String> candidates = new ArrayList<>();
            for (int r : block.rows())
            {
                if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
                {
                    continue;
                }
                String val = extremeCandidate(col.getDataValue(r));
                if (val != null)
                {
                    candidates.add(val);
                }
            }
            String max = genericStringExtreme(candidates, true);
            if (max != null)
            {
                results.put(block.key(), max);
            }
        }
        return declaredGrouped(op, groupCols, results);
    }


    private static @Nullable GroupedResult evalDistinctGrouped(Operation op, IDataTable table,
            @Nullable List<String> groupCols, @Nullable String ruleId)
    {
        if (op.getName() == null || groupCols == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(op.getName());
        if (colIdx < 0)
        {
            return null;
        }
        IDataTableColumn col = table.getColumn(colIdx);
        // EC-44 residual §7: honour the operation's `filter:`, as the three sibling grouped
        // evaluators (evalRecordCountGrouped, evalMaxGrouped, evalDateExtremeGrouped) already
        // do. This evaluator read op.getFilter() nowhere, so a `distinct` carrying a filter
        // meant different things on the two lanes — the fork's distinct.py applies
        // _filter_data and groups the filtered frame. coreJ was the outlier, not the fork.
        // Measured before the change: of the shipped corpus (deduped by rule id) 13 rules
        // carry a grouped `distinct` and 11 a filtered one, and NONE carries both — so this
        // aligns a latent inconsistency and moves no shipped rule's result.
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();

        // EC-44: absent group columns are ignored; all absent ⇒ the dataset is one group.
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, groupCols,
                groupLogContext(ruleId, op), groupKeyPolicy(op, GroupKeyPolicy.KEEP_MISSING_KEYS));
        if (grouping == null)
        {
            return null; // an unexpanded $-ref in the group list — not a dataset-shape fact
        }

        Map<String, Object> results = new LinkedHashMap<>();
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            Set<String> seen = new LinkedHashSet<>();
            for (int r : block.rows())
            {
                if (hasFilter && !rowMatchesFilter(table, meta, filter, r))
                {
                    continue;
                }
                IDataValue dv = col.getDataValue(r);
                if (dv.isMissingOrInvalid())
                {
                    continue;
                }
                String val = dv.getValueAsString();
                if (val != null && !val.isEmpty())
                {
                    seen.add(val);
                }
            }
            if (!seen.isEmpty())
            {
                results.put(block.key(), new ArrayList<>(seen));
            }
        }
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * EC-45 §1.4 — builds the grouped result for {@code op} carrying the absent-key default the
     * operator <em>declares</em> ({@link OperationType#emptyValueOf}), rather than whichever
     * {@link GroupedResult} constructor the neighbouring evaluator happened to use.
     *
     * <p>
     * Hard-coding the default at each construction site is how the pre-EC-45 drift arose — the same
     * {@code distinct} operator answered {@code List.of()} ungrouped and {@code null} grouped, and
     * {@code has_mixed_emptiness_within_group} answered {@code false} for an all-absent EC-23
     * qualifier list but {@code null} for an absent subject column. Routing every site through the
     * declaration makes the classification the single source of truth, and
     * {@link net.cumba.cdisc.core.model.EmptyResult}'s mandatory constructor argument makes it
     * impossible to add an operator without choosing one.
     * </p>
     *
     * <p>
     * One evaluator deliberately does <b>not</b> use this: {@code evalSuppQnamJoin} conditions its
     * default on what is <em>knowable</em> (SUPP present but this record has no qualifier ⇒
     * {@code false}, a real answer; SUPP absent entirely ⇒ {@code null}, no basis). That is a
     * per-call refinement of the static declaration, not a contradiction of it, and it is the model
     * the classification was derived from.
     * </p>
     */
    private static GroupedResult declaredGrouped(Operation op, List<String> groupCols,
            Map<String, Object> results)
    {
        return new GroupedResult(groupCols, results,
                OperationType.emptyValueOf(op.getOperationType()));
    }


    /**
     * The effective {@link GroupKeyPolicy} for a grouped operation: the operator's shipped default,
     * with an authored {@code keep_missings:} overriding its disposition.
     *
     * <p>
     * ⚠⚠ {@code base} is <b>not</b> uniform across the {@code Operations[].group:} surface, and
     * that is the defect this parameter exists to make visible. The five key-building evaluators
     * pass {@link GroupKeyPolicy#KEEP_MISSING_KEYS} (fold) while {@code evalIsLastInGroup} passes
     * {@link GroupKeyPolicy#DROP_MISSING_KEYS} (discard) — one authoring surface, two behaviours,
     * with nothing in the YAML to distinguish them. An author can now settle it by declaring
     * {@code keep_missings}; the <em>defaults</em> stay asymmetric so that adding the parameter
     * moves no findings.
     * </p>
     */
    private static GroupKeyPolicy groupKeyPolicy(Operation op, GroupKeyPolicy base)
    {
        return base.withDeclared(op.getKeepMissings());
    }


    /**
     * EC-44 — the label used by {@link IndexHelper#groupByPresent}'s "group column absent" INFO
     * log. Names the rule and the operation so an operator reading the log can tell which check
     * silently widened its grouping on this study.
     */
    private static String groupLogContext(@Nullable String ruleId, Operation op)
    {
        String opId = op.getId();
        if (opId == null)
        {
            // An operation inlined into the Check by RuleExprGenerator carries no id — the
            // shipped corpus form for most grouped rules. Name the operator instead of "?".
            OperationType type = op.getOperationType();
            opId = type != null ? type.toString() : "?";
        }
        return (ruleId != null ? ruleId : "?") + " operation " + opId;
    }


    private static @Nullable GroupedResult evalRecordCountGrouped(Operation op, IDataTable table,
            @Nullable List<String> groupCols, @Nullable String ruleId)
    {
        if (groupCols == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        Map<String, Object> filter = op.getFilter();
        boolean hasFilter = filter != null && !filter.isEmpty();

        // EC-44: group columns absent from this dataset are ignored rather than zeroing out the
        // whole operation; all absent ⇒ the dataset is one group.
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, groupCols,
                groupLogContext(ruleId, op), groupKeyPolicy(op, GroupKeyPolicy.KEEP_MISSING_KEYS));
        if (grouping == null)
        {
            return null; // an unexpanded $-ref in the group list — not a dataset-shape fact
        }

        Map<String, Object> results = new LinkedHashMap<>();
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            String key = block.key();
            int[] rows = block.rows();

            if (!hasFilter)
            {
                results.put(key, (long) rows.length);
            }
            else
            {
                // Pre-populate with 0 so groups with no matching rows still appear
                results.putIfAbsent(key, 0L);
                long count = 0;
                for (int r : rows)
                {
                    if (rowMatchesFilter(table, meta, filter, r))
                    {
                        count++;
                    }
                }
                results.put(key, count);
            }
        }
        // record_count: an absent group key means zero matching rows -> 0, not "no value".
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * Fix #26: returns a per-group {@code Boolean} indicating whether the {@code op.getName()}
     * column has mixed populated / unpopulated values within each group defined by
     * {@code groupCols} (typically rule-supplied via {@code Operation.group}). For each group:
     * {@code true} when at least one row has the column populated AND at least one row has it
     * unpopulated (missing or empty string); {@code false} when all rows are populated or all rows
     * are unpopulated.
     * <p>
     * Returns {@code null} only when {@code name} is missing — a malformed operation with nothing
     * to read.
     * </p>
     *
     * <p>
     * <b>EC-45 §1.3(2) — an absent {@code name} column no longer skips.</b> An absent column is
     * all-missing, all-missing is homogeneous, and homogeneous is <em>not mixed</em>: every group
     * answers {@code false}. The clinching argument is internal — the same method already returns
     * {@code false} for the same fact reached another way, because EC-23's
     * {@code qualifying_any_populated} filter drops non-existent qualifier columns, so no row is
     * tallied and {@code hasPopulated && hasUnpopulated} is {@code false}. One method, one fact,
     * two answers was the defect.
     * </p>
     *
     * <p>
     * <b>EC-45 §1.3(3) — no {@code group:} means one total group.</b> Every other family-1
     * operation dispatches {@code grouped ? evalXGrouped(...) : evalX(...)} and the ungrouped
     * branch computes the dataset-wide answer, which <em>is</em> one total group; this operator is
     * the only dispatch arm with no ternary because it never got an ungrouped sibling, and the
     * {@code null} was that gap rather than a decision. An empty declared list reaches
     * {@link IndexHelper#groupByPresent}'s "nothing survives" branch and yields exactly that one
     * whole-table block. The authoring smell — an author who <em>forgot</em> {@code group:} and
     * silently gets a dataset-wide check — is a lint's job, not a runtime {@code null}'s.
     * </p>
     */
    private static @Nullable GroupedResult evalHasMixedEmptinessWithinGroup(Operation op,
            IDataTable table, @Nullable List<String> groupCols, @Nullable String ruleId)
    {
        String colName = op.getName();
        if (colName == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(colName);
        // EC-45 §1.3(3): a null / empty declared group list is not a malformed operation — it is
        // the dataset-wide reading, which groupByPresent already expresses as one whole-table
        // block. Normalise it here so the key encoding stays the one GroupedResult.getForRow
        // computes for the same (empty) column list.
        List<String> keyCols = groupCols != null ? groupCols : List.of();
        // EC-44: absent group columns are ignored; all absent ⇒ the dataset is one group, i.e.
        // "mixed emptiness within the dataset" — the only reading left once no partition survives.
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, keyCols,
                groupLogContext(ruleId, op), groupKeyPolicy(op, GroupKeyPolicy.KEEP_MISSING_KEYS));
        if (grouping == null)
        {
            return null; // an unexpanded $-ref in the group list — not a dataset-shape fact
        }

        // EC-23: opt-in row qualifier. When present, a group row is skipped before the tally unless
        // at least one of the listed columns is populated (non-missing AND non-blank). Absent ⇒ the
        // qualifier column list is empty and every row is scanned (byte-identical to the original).
        List<String> qualifiers = op.getQualifyingAnyPopulated();
        int[] qualifierIdx = qualifiers == null ? new int[0]
                : qualifiers.stream().filter(Objects::nonNull).mapToInt(meta::getColumnIndex)
                        .filter(idx -> idx >= 0).toArray();
        boolean hasQualifier = qualifiers != null && !qualifiers.isEmpty();

        Map<String, Object> results = new LinkedHashMap<>();
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            boolean hasPopulated = false;
            boolean hasUnpopulated = false;
            for (int r : block.rows())
            {
                if (hasQualifier && !rowQualifies(table, qualifierIdx, r))
                {
                    continue; // none of the qualifying columns populated ⇒ row out of scope
                }
                // EC-45 §1.3(2): an absent subject column is all-missing, so every row of every
                // group counts as unpopulated and the group answers "not mixed".
                boolean populated = false;
                if (colIdx >= 0)
                {
                    IDataValue dv = table.getColumn(colIdx).getDataValue(r);
                    String s = dv.isMissingOrInvalid() ? null : dv.getValueAsString();
                    populated = s != null && !s.isEmpty();
                }
                if (populated)
                {
                    hasPopulated = true;
                }
                else
                {
                    hasUnpopulated = true;
                }
                if (hasPopulated && hasUnpopulated)
                {
                    break; // mixed detected — no need to scan further rows in this block
                }
            }
            results.put(block.key(), hasPopulated && hasUnpopulated);
        }
        return declaredGrouped(op, keyCols, results);
    }


    /**
     * EC-23 — a row qualifies when at least one of the {@code qualifier} columns is populated
     * (non-missing AND non-blank after {@code strip()}). Non-existent columns were already filtered
     * out (index {@code < 0}), so an all-absent qualifier list can never qualify any row.
     */
    private static boolean rowQualifies(IDataTable table, int[] qualifierIdx, long row)
    {
        for (int idx : qualifierIdx)
        {
            IDataValue dv = table.getColumn(idx).getDataValue(row);
            if (dv != null && !dv.isMissingOrInvalid())
            {
                String s = dv.getValueAsString();
                if (s != null && !s.strip().isEmpty())
                {
                    return true;
                }
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------


    private static @Nullable Object evalExtractMetadata(Operation op, IDataTable table)
    {
        if (op.getName() == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        return switch (op.getName())
        {
        case "dataset_name" -> meta.getName();
        case "dataset_label" -> meta.getLabel();
        case "dataset_size" -> meta.getMetaData("dataset_size");
        // Two keys, one value (the source-URI basename): dataset_location matches the Python
        // engine's metadata key (use it in legacy rules that run under parity); filename is the
        // intuitive alias for native expression-only rules (Java-only). Both resolve identically.
        case "dataset_location", "filename" -> fileNameFromUri(meta.getTableURI());
        default -> meta.getMetaData(op.getName());
        };
    }


    /**
     * Last path segment of a dataset's source URI, percent-decoded, in original casing and
     * including the extension (e.g. {@code "ae.xpt"}); {@code null} when the URI is absent or
     * carries no usable path. Backs the {@code extract_metadata("dataset_location")} /
     * {@code extract_metadata("filename")} accessor on both the legacy operation path and the
     * native expression path (which bridges here via {@code ExprCompiler.isInlineOperation} to
     * {@code OperationExecutor.executeOne}).
     */
    private static @Nullable String fileNameFromUri(@Nullable URI uri)
    {
        if (uri == null)
        {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty())
        {
            path = uri.getSchemeSpecificPart();
        }
        if (path == null || path.isEmpty())
        {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? null : name;
    }


    /**
     * Resolves {@code required_variables()} / {@code expected_variables()} — the two operations
     * that ask the Library <em>"which variables does the standard oblige this dataset to
     * carry?"</em>
     *
     * <h4>Why this is not one line</h4>
     *
     * <p>
     * SDTM's variable model is keyed by <b>domain</b>, ADaM's by <b>data structure</b>. Keying both
     * by domain — which is what this arm did until Fix #368 — works for SDTM and fails for ADaM
     * everywhere except {@code ADSL}, the single dataset name that happens to coincide with a
     * structure name. And it fails <em>silently</em>: a miss returns an empty list, {@code
     * contains_all($dataset_variables, [])} is vacuously true, and the rule reports {@code SUCCESS}
     * with the defect sitting in the data. Measured on 2026-08-27, {@code PMDA-AD0047} — then the
     * corpus' <em>only</em> ADaMIG carrier of either operation, joined since by its minted CDISC
     * twin {@code CDISC-AD9704} — passed green on a BDS dataset missing the Required {@code PARAM},
     * and simultaneously reported the naming <em>template</em> {@code TRTxxP} as a missing variable
     * on a conformant ADSL.
     * </p>
     *
     * <h4>What it does</h4>
     *
     * <ol>
     * <li>A provider that is not structure-keyed (SDTM, every stub) keeps the domain-keyed call,
     * byte-for-byte the prior behaviour.</li>
     * <li>Otherwise the dataset's structure set is derived through {@link AdamStructureContext} —
     * the <b>same</b> derivation {@link RuleRunner} used to admit the rule — and tried
     * most-specific first, so a device-BDS dataset under a product with no device structures falls
     * through to {@code BASIC DATA STRUCTURE}, exactly the is-a relation {@code structureSet}
     * encodes.</li>
     * <li><b>Phase 3 of {@code PLAN-metadata-product-selection} (ruling 2):</b> the dataset's
     * resolved <em>subclass</em> set — the same one the {@code Scope.Subclasses} gate used, via
     * {@link AdamStructureContext#detectSubclasses} — travels with each token, so the published
     * {@code subClass} selects which of that token's data structures <b>governs</b> the list. With
     * no detected subclass the answer comes from the base structures only; the most-strict-wins
     * union over every same-class structure is gone.</li>
     * <li>Published names are substituted against the dataset's real columns
     * ({@link #substituteNamingTemplates}).</li>
     * <li>If <b>no</b> token resolves, the result is {@link #LIBRARY_NOT_AVAILABLE} — the rule is
     * SKIPPED for this dataset, with the tokens tried named in the log. It is never a green pass.
     * An unresolvable structure is the honest reason a rule could not run; swallowing it is how
     * ADaM stayed invisible for as long as it did.</li>
     * </ol>
     *
     * <p>
     * ⚠ Step 4 is deliberately <b>not</b> symmetric with an empty list. A structure that resolves
     * and publishes nothing ({@code adamig-1-3}'s {@code TTE}) is a legitimate pass; a structure
     * that does not resolve is not. See {@link MetadataProvider#getRequiredVariablesForStructure}.
     * </p>
     */
    private static @Nullable Object evalCoreVariables(@Nullable MetadataProvider libraryProvider,
            Operation op, IDataTable table, @Nullable MetadataProvider defineProvider,
            @Nullable String ruleId, boolean expected)
    {
        return evalLibrary(libraryProvider, op, table, p ->
        {
            if (!p.supportsStructureKeyedVariables())
            {
                String domain = net.cumba.cdisc.core.metadata.CdiscDomainResolver
                        .cdiscDomainOf(table);
                return expected ? p.getExpectedVariables(domain) : p.getRequiredVariables(domain);
            }
            List<String> structures = AdamStructureContext.detectAll(table.getMetaData(),
                    defineProvider, libraryProvider);
            // Phase 3 of PLAN-metadata-product-selection (ruling 2). ⚠⚠ The loop below is ALREADY
            // a precedence chain — on the structure axis — and this does NOT add a second one.
            // The subclass set is a second DIMENSION consumed *inside* one token's lookup: it
            // decides which of that token's data structures governs. The outer loop, the null
            // semantics (null = "no such structure" ⇒ SKIP; empty list = "resolves and publishes
            // nothing" ⇒ legitimate pass) and the SKIP log below are unchanged.
            List<String> subclasses = AdamStructureContext.detectSubclasses(table.getMetaData(),
                    defineProvider, libraryProvider, structures);
            for (String token : structures)
            {
                List<String> published = expected
                        ? p.getExpectedVariablesForStructure(token, subclasses)
                        : p.getRequiredVariablesForStructure(token, subclasses);
                if (published != null)
                {
                    return substituteNamingTemplates(published, table);
                }
            }
            List<String> declaredProducts = p.declaredStructureKeyedProducts();
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1}: the run''s declared product(s) {2} publish no data structure for "
                            + "dataset {3} (tried {4}, subclasses {5}) — rule will be skipped "
                            + "rather than pass",
                    ruleId != null ? ruleId : "?", op.getId(),
                    declaredProducts.isEmpty() ? "<unknown>" : declaredProducts,
                    table.getMetaData().getName(), structures, subclasses);
            return LIBRARY_NOT_AVAILABLE;
        }, ruleId);
    }


    /**
     * Substitutes ADaM naming templates in a published variable list against the dataset's actual
     * columns, using the <b>same</b> compiled pattern the scope gate matches with
     * ({@link WildcardExpander#scopeVariableWildcardPattern}), so the two cannot drift.
     *
     * <p>
     * Per published name: a literal passes through; a template with <b>≥1</b> matching column
     * contributes the matching column names (the obligation is satisfied); a template with
     * <b>no</b> matching column contributes <b>the template verbatim</b>, so it is reported as
     * missing.
     * </p>
     *
     * <p>
     * ⚠⚠ Both directions are load-bearing and each was wrong once. Contributing the template
     * unconditionally is the {@code TRTxxP}-on-every-conformant-ADSL false positive. Contributing
     * only the matches would make a dataset carrying <em>no</em> planned-treatment variable at all
     * pass silently — the absent-column trap, in the one place where absence <b>is</b> the defect.
     * </p>
     *
     * <p>
     * ⚑ {@code --}-prefixed names ({@code --TERM}, {@code --SEQ}) are <b>not</b> wildcards by
     * {@code WildcardExpander}'s definition and are not substituted here. They occur only in the
     * OCCDS supplement products, which no {@code adamig-1-x} run resolves — such a dataset takes
     * the skip path above. Recorded as a decision, not an oversight; it belongs to the open
     * supplement-resolution question (R2), not to this fix.
     * </p>
     */
    private static List<String> substituteNamingTemplates(List<String> published, IDataTable table)
    {
        List<String> columns = AdamStructureContext.columnNamesOf(table.getMetaData());
        // Insertion-ordered and de-duplicating: a template and a literal could in principle
        // resolve to the same concrete column, and the list is reported to the user verbatim.
        Set<String> out = new LinkedHashSet<>();
        for (String name : published)
        {
            Pattern pattern = WildcardExpander.scopeVariableWildcardPattern(name);
            if (pattern == null)
            {
                out.add(name);
                continue;
            }
            List<String> matches = columns.stream().filter(c -> pattern.matcher(c).matches())
                    .toList();
            if (matches.isEmpty())
            {
                out.add(name);
            }
            else
            {
                out.addAll(matches);
            }
        }
        return List.copyOf(out);
    }


    /**
     * Fix #369 — the process-wide {@code corej.degradedDefineFallback} preference: whether a run
     * whose CDISC Library could not be consulted may answer library-citing rules from the study's
     * Define-XML instead.
     *
     * <p>
     * <b>Opt-in, default {@code false}</b>, and deliberately so. Owner ruling, 2026-08-27:
     * <em>"these rules state they check against the library, which they do not do if the source is
     * not the library. Therefore the correct result is always a SKIPPED (library not
     * available)."</em> A Define-XML {@code ItemRef/@Mandatory} is the <em>sponsor's</em>
     * declaration, not the <em>standard's</em> Required list; answering from it is a different
     * question wearing the rule's message. A sponsor who knowingly wants their own declarations
     * used as the basis may ask for it — but it must be their decision, not an accident of an
     * expired subscription key.
     * </p>
     *
     * <p>
     * ⚑ Note the asymmetry with {@code corej.defineFirst}, which is opt-<em>out</em>: this one is
     * plain {@link Boolean#getBoolean}, so anything but an explicit {@code true} leaves the
     * substitution off.
     * </p>
     *
     * @return {@code true} only when {@code -Dcorej.degradedDefineFallback=true} was given
     */
    public static boolean defineFallbackPreference()
    {
        return Boolean.getBoolean(DEGRADED_DEFINE_FALLBACK_PROPERTY);
    }


    /**
     * Fix #369 — whether {@code provider} may be asked a library-dependent question at all.
     *
     * <p>
     * {@code false} for an absent provider, and for a <b>degraded</b> one
     * ({@link MetadataProvider#isLibraryUnavailable()}) unless the caller opted into the Define-XML
     * substitution <em>and</em> the fallback really is a Define-XML library. That last test is
     * exact rather than heuristic: {@link MetadataProvider#getDefineVersion()} reads
     * {@code IMetadataLibrary.META_KEY_DEFINE_VERSION}, and {@code DefineMetadataLibrary} is the
     * only implementation in the repository that publishes it — so no per-answer provenance
     * plumbing is needed to know that a non-{@code null} version means "the fallback is a define".
     * </p>
     *
     * <p>
     * ⚠ Whether a fallback tier <em>happens</em> to hold something is deliberately <b>not</b> part
     * of this test on the default path. The question is not "could anything answer?" but "was the
     * rule's own claim met?", and it was not.
     * </p>
     */
    public static boolean libraryAnswerable(@Nullable MetadataProvider provider)
    {
        if (provider == null)
        {
            return false;
        }
        if (!provider.isLibraryUnavailable())
        {
            return true;
        }
        return defineFallbackPreference() && provider.getDefineVersion() != null;
    }

    /**
     * Fix #369 — how a given library arm's result is judged to be an <em>answer</em> when the
     * Define-XML fallback is engaged.
     *
     * <p>
     * ⚠ This is an explicit per-arm property and not an {@code instanceof} ladder on the result,
     * because <b>the type does not carry the intent</b>. {@code domain_is_custom} returns a
     * {@code boolean} whose {@code false} ("not custom") is indistinguishable from "could not tell"
     * — and {@code false} is the value that lets a rule fire.
     * </p>
     */
    private enum LibraryArmAnswer
    {
        /** A non-empty {@link java.util.Collection} (or any non-{@code null} scalar) answers. */
        VALUE,
        /** A non-blank {@link String} answers; {@code null} / blank does not. */
        TEXT,
        /**
         * A {@link GroupedResult} answers only if at least one group carries a non-blank value.
         *
         * <p>
         * ⚠ Without this, {@link #isResultAvailable} reads <em>every</em> {@code GroupedResult} as
         * an answer — it is neither {@code null}, nor the sentinel, nor an empty
         * {@link java.util.Collection}. {@code referenced_domain_class} maps every referenced
         * domain to {@code ""} on a degraded provider ({@code getDatasetClass} returns
         * {@code null}), so under the opt-in the rule would execute against a map of empty strings.
         * </p>
         */
        GROUPED_TEXT,
        /**
         * Nothing this arm can return counts as an answer once the Library is gone — the value
         * space has no room to express absence. {@code domain_is_custom} is the case: "custom"
         * means <em>"not in the standard"</em>, and the standard is precisely what is missing.
         */
        NEVER;
    }

    /**
     * Fix #369 — whether {@code result} is an answer, under the arm's own {@link LibraryArmAnswer}
     * contract. Used <b>only</b> inside the opt-in branch of {@link #evalLibrary}: there the user
     * asked for <em>"whatever the define can answer"</em>, so an arm the define cannot serve skips
     * while the ones it can serve execute.
     */
    private static boolean isAnswer(LibraryArmAnswer kind, @Nullable Object result)
    {
        return switch (kind)
        {
        case NEVER -> false;
        case TEXT -> result instanceof String str && !str.isBlank();
        case GROUPED_TEXT -> result instanceof GroupedResult g && g.results().values().stream()
                .anyMatch(v -> v instanceof String str ? !str.isBlank() : v != null);
        case VALUE -> isResultAvailable(result);
        };
    }


    /**
     * Delegates to the MetadataProvider. Returns the {@link #LIBRARY_NOT_AVAILABLE} skip sentinel
     * when the provider is not configured, and — <b>Fix #369</b> — when it is configured but its
     * CDISC Library could not be consulted at all.
     *
     * <p>
     * <b>The degraded gate, in three conditions and one place.</b> A future library arm inherits
     * the behaviour instead of having to remember it:
     * </p>
     * <ol>
     * <li><b>Degraded and not opted in</b> ⇒ skip, <em>without evaluating {@code fn}</em>. The
     * owner's default: a rule that claims to check the Library has not done so if the source was
     * not the Library, so whether the study tier happens to hold something is irrelevant (and not
     * computing it is also cheaper).</li>
     * <li><b>Opted in, but the fallback is not a Define-XML library</b> ⇒ skip. There is nothing to
     * fall back <em>to</em>; a data-derived answer must never be admitted under a flag named for
     * Define-XML.</li>
     * <li><b>Opted in, define present, but this arm got nothing from it</b> ⇒ skip. That is exactly
     * <em>"the rules that can answer from the define"</em> — the ones that cannot, do not, with no
     * per-arm enumeration needed.</li>
     * </ol>
     *
     * <p>
     * ⚠ On a run with a working Library this method is behaviourally unchanged: {@code fn} is
     * applied and its result returned verbatim.
     * </p>
     */
    @SuppressWarnings(
    {
            "PMD.UnusedFormalParameter", "unused"
    })
    private static @Nullable Object evalLibrary(@Nullable MetadataProvider provider, Operation op,
            IDataTable table, Function<MetadataProvider, @Nullable Object> fn,
            @Nullable String ruleId)
    {
        return evalLibrary(provider, op, table, fn, ruleId, LibraryArmAnswer.VALUE);
    }


    /**
     * {@link #evalLibrary(MetadataProvider, Operation, IDataTable, Function, String)} with the
     * arm's explicit {@link LibraryArmAnswer} contract, for the arms whose result is not a
     * collection.
     */
    @SuppressWarnings(
    {
            "PMD.UnusedFormalParameter", "unused"
    })
    private static @Nullable Object evalLibrary(@Nullable MetadataProvider provider, Operation op,
            IDataTable table, Function<MetadataProvider, @Nullable Object> fn,
            @Nullable String ruleId, LibraryArmAnswer answerKind)
    {
        if (provider == null)
        {
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_PROVIDER_UNAVAILABLE_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        if (provider.isLibraryUnavailable() && !libraryAnswerable(provider))
        {
            // Conditions 1 and 2 — fn is deliberately NOT applied.
            LOGGER.log(System.Logger.Level.INFO,
                    defineFallbackPreference() ? LIBRARY_DEGRADED_NO_DEFINE_MSG
                            : LIBRARY_DEGRADED_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        Object result = fn.apply(provider);
        if (provider.isLibraryUnavailable() && !isAnswer(answerKind, result))
        {
            // Condition 3 — opted in, a define is present, but this arm got nothing from it.
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_DEGRADED_DEFINE_EMPTY_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        return result;
    }


    /**
     * Fix #369 — the degraded gate for the five library arms that do <b>not</b> funnel through
     * {@link #evalLibrary} because they hand-roll their own {@code provider == null} check
     * ({@code get_parent_model_column_order}, {@code get_dataset_filtered_variables},
     * {@code natural_key_variables}, {@code get_model_filtered_variables},
     * {@code valid_codelist_dates} — 19 corpus rules between them).
     *
     * <p>
     * ⚠⚠ <b>These five are why "one choke point" is not enough.</b> The plan this fix implements
     * asserted that every library arm funnels through {@code evalLibrary}; it does not — 12 of the
     * 17 do. A gate written only inside {@code evalLibrary} would have left these five silently
     * passing on exactly the degraded runs it exists to catch, which is the same class of
     * partial-fix-that-looks-complete as Fix #368's undelegated decorators.
     * </p>
     *
     * @return {@link #LIBRARY_NOT_AVAILABLE} when the arm must skip, or {@code null} to proceed
     */
    private static @Nullable Object degradedSkip(MetadataProvider provider, Operation op,
            @Nullable String ruleId)
    {
        if (provider.isLibraryUnavailable() && !libraryAnswerable(provider))
        {
            LOGGER.log(System.Logger.Level.INFO,
                    defineFallbackPreference() ? LIBRARY_DEGRADED_NO_DEFINE_MSG
                            : LIBRARY_DEGRADED_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        return null;
    }


    /**
     * Fix #369 — condition 3 for the five {@link #degradedSkip} arms: with the opt-in engaged, a
     * result the Define-XML could not actually produce still skips.
     */
    private static Object degradedAnswerOrSkip(MetadataProvider provider, Operation op,
            @Nullable String ruleId, Object result)
    {
        if (provider.isLibraryUnavailable() && !isResultAvailable(result))
        {
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_DEGRADED_DEFINE_EMPTY_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        return result;
    }


    /**
     * Resolves the term list for a {@code codelist_terms} / {@code get_codelist_attributes}
     * operation. The codelist(s) may be named via the {@code name} field
     * ({@code get_codelist_attributes}) or the {@code codelists} array ({@code codelist_terms} —
     * e.g. CORE-000929's {@code "codelists":["DOMAIN"]}); the term codes are unioned across all
     * named codelists, preserving order and dropping duplicates.
     *
     * <p>
     * {@link MetadataProvider#getCodelistTerms} returns each term's <em>code</em>, which matches
     * the {@code level=term, returntype=code} shape of every bundled {@code codelist_terms} rule.
     * Other {@code level} / {@code returntype} combinations are not honoured (no shipping rule uses
     * them); add explicit handling here if such a rule is introduced.
     * </p>
     */
    private static List<String> codelistTerms(MetadataProvider provider, Operation op)
    {
        List<String> names;
        if (op.getName() != null)
        {
            names = List.of(op.getName());
        }
        else if (op.getCodelists() != null && !op.getCodelists().isEmpty())
        {
            names = op.getCodelists();
        }
        else
        {
            throw new IllegalArgumentException(
                    "codelist operation requires a name or codelists (op id=" + op.getId() + ")");
        }
        return names.stream().filter(Objects::nonNull).distinct()
                .flatMap(n -> provider.getCodelistTerms(n).stream()).distinct().toList();
    }


    /**
     * Resolves the {@code get_codelist_attributes} operation result (CORE-001080). Mirrors Python's
     * {@code operations/get_codelist_attributes.py}: per row, derive a CT package id from two data
     * columns — the target column ({@code op.getName()}, e.g. {@code TSVCDREF}) and the version
     * column ({@code op.getVersion()}, e.g. {@code TSVCDVER}) — then extract
     * {@code op.getCtAttribute()} from each distinct resolved package, unioning the values
     * (order-preserving, deduped).
     *
     * <p>
     * The Java engine yields a single operation value broadcast to every row (it has no per-row
     * Series), so when distinct rows resolve to distinct packages the attribute sets are unioned.
     * For CORE-001080 the single row resolves to {@code sdtmct-2024-09-27}, giving exactly that
     * package's set.
     * </p>
     */
    private static List<String> codelistAttributes(MetadataProvider provider, Operation op,
            IDataTable table)
    {
        String ctAttribute = op.getCtAttribute();
        if (ctAttribute == null || op.getName() == null || op.getVersion() == null)
        {
            return List.of();
        }
        DataTableMeta meta = table.getMetaData();
        int targetIdx = meta.getColumnIndex(op.getName());
        int versionIdx = meta.getColumnIndex(op.getVersion());
        if (targetIdx < 0 || versionIdx < 0)
        {
            return List.of();
        }
        String standard = provider.getStandard();
        IDataTableColumn targetCol = table.getColumn(targetIdx);
        IDataTableColumn versionCol = table.getColumn(versionIdx);
        long rowCount = table.getRowCount();
        // Distinct CT package ids in row order.
        List<String> packageIds = new ArrayList<>();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue targetDv = targetCol.getDataValue(r);
            IDataValue versionDv = versionCol.getDataValue(r);
            String targetVal = targetDv.isMissingOrInvalid() ? "" : targetDv.getValueAsString();
            String versionVal = versionDv.isMissingOrInvalid() ? "" : versionDv.getValueAsString();
            String pkgId = ctPackageId(targetVal, versionVal, standard);
            if (pkgId != null && !packageIds.contains(pkgId))
            {
                packageIds.add(pkgId);
            }
        }
        // Order-preserving union with O(1) membership across the (typically one) resolved packages.
        Set<String> out = new LinkedHashSet<>();
        for (String pkgId : packageIds)
        {
            out.addAll(provider.getCodelistAttribute(pkgId, ctAttribute));
        }
        return List.copyOf(out);
    }


    /**
     * Derives a CT package id from the row's target value, version value and the active standard,
     * matching Python's {@code _get_ct_package}. Returns {@code null} when the version is blank.
     */
    private static @Nullable String ctPackageId(String aTargetVal, String aVersionVal,
            @Nullable String aStandard)
    {
        String version = aVersionVal == null ? "" : aVersionVal.strip();
        if (version.isEmpty())
        {
            return null;
        }
        String target = aTargetVal == null ? "" : aTargetVal.strip();
        if ("CDISC".equals(target) || "CDISC CT".equals(target))
        {
            String std = aStandard == null ? "" : aStandard.toLowerCase(Locale.ROOT);
            // (TIG substandard handling is not exercised by any shipping rule; the standard name is
            // used directly, matching the non-TIG branch of the Python logic.)
            String prefix;
            if (std.contains("adam"))
            {
                prefix = "adamct";
            }
            else if (std.contains("send"))
            {
                prefix = "sendct";
            }
            else
            {
                prefix = "sdtmct";
            }
            return prefix + "-" + version;
        }
        return target + "-" + version;
    }


    /**
     * Fix #2: Returns the IG-level variables of the current dataset's domain filtered by a
     * {@code key_name = key_value} attribute (e.g. {@code role = Timing}) and then intersected with
     * the dataset's actual columns. Python parity:
     * {@code operations/get_dataset_filtered_variables.py}.
     * <p>
     * When the provider is absent, returns the library-skipped sentinel so the rule is reported as
     * SKIPPED (not silently evaluated against an empty set).
     * </p>
     */
    private static Object evalGetDatasetFilteredVariables(@Nullable MetadataProvider provider,
            Operation op, IDataTable table, DatasetResolver resolver, @Nullable String ruleId)
    {
        if (provider == null)
        {
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_PROVIDER_UNAVAILABLE_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        Object degraded = degradedSkip(provider, op, ruleId);
        if (degraded != null)
        {
            return degraded;
        }
        // Fix #42 Phase 2 follow-up #1 — route through the algorithm-B class-aware resolver
        // (getStandardVariablesDetailed = IG-base + Model-merge) instead of the per-table
        // flattened IMetadataLibrary view. Mirrors Python's get_dataset_filtered_variables which
        // chains through library_column_order's get_variables_metadata_from_standard.
        // Falls back to the legacy provider.getDomainVariables path when the resolver
        // returns null (no products configured / degraded provider) so non-product
        // configurations still work. The walk itself (source selection, EC-36 `--` substitution,
        // dataset-column intersection) lives in StandardVariableSelector — shared verbatim with
        // natural_key_variables and the EC-40 record-key NATURAL tier.
        String keyName = op.getKeyName();
        String keyValue = op.getKeyValue();
        return degradedAnswerOrSkip(provider, op, ruleId, StandardVariableSelector.select(provider,
                table, resolver,
                varRow -> keyName == null || Objects.equals(varRow.get(keyName), keyValue)));
    }


    /**
     * Returns the current dataset's library variables whose SDTM {@code role} is a natural-key-
     * forming role ({@link StandardVariableSelector#NATURAL_KEY_ROLES}), intersected with the
     * dataset's actual columns. Same walk as {@link #evalGetDatasetFilteredVariables} but with the
     * fixed role-set membership filter in place of the {@code key_name = key_value} attribute
     * filter. Python parity: {@code operations/natural_key_variables.py}.
     * <p>
     * When the provider is absent, returns the library-skipped sentinel so the rule is reported as
     * SKIPPED (not silently evaluated against an empty set).
     * </p>
     */
    private static Object evalNaturalKeyVariables(@Nullable MetadataProvider provider, Operation op,
            IDataTable table, DatasetResolver resolver, @Nullable String ruleId)
    {
        if (provider == null)
        {
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_PROVIDER_UNAVAILABLE_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        Object degraded = degradedSkip(provider, op, ruleId);
        if (degraded != null)
        {
            return degraded;
        }
        // Same class-aware standard-variable source as evalGetDatasetFilteredVariables
        // (getStandardVariablesDetailed = IG-base + Model-merge), with the legacy
        // provider.getDomainVariables fallback when the resolver returns null. Shared with that
        // operation and with the EC-40 record-key NATURAL tier via StandardVariableSelector, so
        // there is exactly one definition of the natural-key role set in the tree.
        return degradedAnswerOrSkip(provider, op, ruleId, StandardVariableSelector.select(provider,
                table, resolver, StandardVariableSelector::isNaturalKeyRole));
    }


    /**
     * The dataset's column names in declaration order. Package-private so
     * {@link StandardVariableSelector} and {@link RecordKeyResolver} share the one implementation.
     */
    static Set<String> datasetColumnNames(IDataTable table)
    {
        DataTableMeta meta = table.getMetaData();
        int n = meta.getColumnCount();
        Set<String> names = LinkedHashSet.newLinkedHashSet(n);
        for (int i = 0; i < n; i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }


    /**
     * Fix #3: Returns the Model-level variables for the observation class of the current dataset's
     * domain, filtered by a {@code key_name = key_value} attribute (e.g. {@code role = Timing}).
     * Does <em>not</em> intersect with dataset columns — the caller compares that set against the
     * dataset's own columns (e.g.
     * {@code $dataset_variables shares_no_elements_with $timing_variables}). Python parity:
     * {@code operations/get_model_filtered_variables.py}.
     *
     * <p>
     * EC-85: with {@code model_class} set the walk is the <b>named</b> class's table rather than
     * the dataset's own (the prefix substitution still uses the dataset's domain), the filter tail
     * is shared, and an unserved class yields {@link #LIBRARY_NOT_AVAILABLE} (D-6) — coreJ-only;
     * the Python operation has no class parameter.
     * </p>
     */
    private static Object evalGetModelFilteredVariables(@Nullable MetadataProvider provider,
            Operation op, IDataTable table, DatasetResolver resolver, @Nullable String ruleId)
    {
        if (provider == null)
        {
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_PROVIDER_UNAVAILABLE_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        Object degraded = degradedSkip(provider, op, ruleId);
        if (degraded != null)
        {
            return degraded;
        }
        // Fix #42 Phase 2 follow-up #1 — route through the class-aware resolver. Mirrors
        // Python's get_model_filtered_variables which calls _get_variables_metadata_from
        // _standard_model directly (sdtm_utilities.py via base_operation.py). Falls back to
        // the legacy MODEL_VARIABLES per-table key when the resolver returns null (no
        // products / degraded mode).
        // EC-85: `model_class` asks for ANOTHER observation class's table (still substituted
        // with this dataset's prefix); absent, the dataset's own class is walked as before.
        String modelClass = op.getModelClass();
        List<Map<String, String>> source = modelClass != null
                ? provider.getStandardModelVariablesForClass(table, resolver, modelClass)
                : provider.getStandardModelVariablesDetailed(table, resolver);
        boolean fromResolver = source != null;
        if (!fromResolver)
        {
            // Fix #59: CDISC domain code, not member name (see sibling comment in
            // evalGetDatasetFilteredVariables). EC-85: a class-selecting call falls back to the
            // class-keyed harness map — the domain-keyed one answers a different question.
            source = modelClass != null ? provider.getModelVariablesForClass(modelClass)
                    : provider.getModelVariables(
                            net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(table));
        }
        if (source == null || source.isEmpty())
        {
            if (modelClass == null)
            {
                // Unchanged for the own-class callers: an empty walk is an empty set.
                // ⚠ Fix #369 — except under the degraded define opt-in, where an empty walk means
                // "the define could not answer this arm" (a define has no SDTM Model at all) and
                // must SKIP. Without this the early return bypasses the condition-3 gate at the
                // bottom of the method and the rule EXECUTES against an empty set — the exact
                // vacuity this fix exists to remove. Caught by
                // DegradedLibrarySkipTest.optInOn_armTheDefineCannotServe_stillSkips.
                return degradedAnswerOrSkip(provider, op, ruleId, List.of());
            }
            // D-6: a class the library cannot serve is library-not-available, so RuleRunner
            // reports the rule SKIPPED instead of evaluating `varname() in $x` against nothing
            // (which could never fire — a silent false PASS). Gated on the keyword so the
            // own-class callers keep the List.of() above.
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] get_model_filtered_variables resolved no variables for model class"
                            + " {1} — rule will be skipped",
                    ruleId != null ? ruleId : "?", modelClass);
            return LIBRARY_NOT_AVAILABLE;
        }
        // EC-36: variable names -> variable prefix; "" for SUPP, AP suffix for AP.
        String prefix = Objects
                .requireNonNullElse(variableWildcardPrefix(table, domainPrefix(table)), "");
        String keyName = op.getKeyName();
        String keyValue = op.getKeyValue();
        List<String> out = new ArrayList<>();
        for (Map<String, String> varRow : source)
        {
            if (keyName != null && !Objects.equals(varRow.get(keyName), keyValue))
            {
                continue;
            }
            String name = varRow.get("name");
            if (name == null)
            {
                continue;
            }
            // Same wildcard-substitution discipline as the dataset variant: the resolver
            // pre-substitutes; the legacy fallback does not.
            out.add((!fromResolver && name.contains("--")) ? name.replace("--", prefix) : name);
        }
        return degradedAnswerOrSkip(provider, op, ruleId, out);
    }


    /**
     * Fix #4: Returns the sorted list of published CT-package dates applicable to the current
     * standard (or the operation's explicit {@code ct_package_types} filter). Python parity:
     * {@code operations/valid_codelist_dates.py} + {@code _is_applicable_ct_package}.
     */
    private static Object evalValidCodelistDates(@Nullable MetadataProvider provider, Operation op,
            @Nullable String ruleId)
    {
        if (provider == null)
        {
            LOGGER.log(System.Logger.Level.INFO, LIBRARY_PROVIDER_UNAVAILABLE_MSG,
                    ruleId != null ? ruleId : "?", op.getId());
            return LIBRARY_NOT_AVAILABLE;
        }
        Object degraded = degradedSkip(provider, op, ruleId);
        if (degraded != null)
        {
            return degraded;
        }
        List<String> packages = provider.getPublishedCtPackages();
        if (packages == null || packages.isEmpty())
        {
            // Fix #369 — same early-return bypass as evalGetModelFilteredVariables above: under the
            // opt-in an empty package list is "the define could not answer", not "there are no
            // packages". Outside degraded mode this is the unchanged empty-set answer.
            return degradedAnswerOrSkip(provider, op, ruleId, List.of());
        }
        Set<String> applicable = applicableCtPackageTypes(op, provider);
        java.util.TreeSet<String> dates = new java.util.TreeSet<>();
        for (String pkg : packages)
        {
            int dash = pkg.indexOf('-');
            if (dash < 0)
            {
                continue;
            }
            String type = pkg.substring(0, dash);
            if (!applicable.contains(type))
            {
                continue;
            }
            dates.add(pkg.substring(dash + 1));
        }
        return degradedAnswerOrSkip(provider, op, ruleId, new ArrayList<>(dates));
    }

    /**
     * Maps a CDISC standard (e.g. {@code sdtmig}) to the set of eligible CT-package-type prefixes
     * used to split a package identifier like {@code "sdtmct-2023-10-26"}. Mirrors the Python table
     * in {@code _is_applicable_ct_package}.
     */
    private static final Map<String, Set<String>> STANDARD_TO_PACKAGE_TYPES = Map.of("sdtmig",
            Set.of(SDTMCT), "sendig", Set.of("sendct"), "cdashig", Set.of("cdashct"), "adamig",
            Set.of(SDTMCT, "adamct"), "usdm", Set.of("ddfct", SDTMCT));

    private static Set<String> applicableCtPackageTypes(Operation op, MetadataProvider provider)
    {
        if (op.getCtPackageTypes() != null && !op.getCtPackageTypes().isEmpty())
        {
            // Rule-level list entries look like "SDTM" / "ADAM" / "CDASH" — map each to the
            // corresponding package-type prefix by lowercasing and appending "ct".
            Set<String> set = new LinkedHashSet<>();
            for (String s : op.getCtPackageTypes())
            {
                if (s != null && !s.isEmpty())
                {
                    set.add(s.toLowerCase(Locale.ROOT) + "ct");
                }
            }
            return set;
        }
        String std = provider.getStandard();
        if (std == null)
        {
            return Set.of();
        }
        return STANDARD_TO_PACKAGE_TYPES.getOrDefault(std.toLowerCase(Locale.ROOT), Set.of());
    }


    private static List<String> evalGetColumnOrder(IDataTable table)
    {
        DataTableMeta meta = table.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> names = new ArrayList<>(colCount);
        for (int i = 0; i < colCount; i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }


    /**
     * T2-residual {@code define_variable_names}: the set of variable names the sponsor Define-XML
     * declares for the current domain, read from the Define provider. Returns {@code null} (an
     * unresolvable result) when no Define-XML is supplied — RuleRunner has already SKIPped the rule
     * for that case (see {@link #isDefineDependent}); the null guard here is belt-and-braces.
     */
    private static @Nullable List<String> evalDefineVariableNames(IDataTable table,
            @Nullable MetadataProvider defineProvider)
    {
        if (defineProvider == null)
        {
            return null;
        }
        String domain = net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(table);
        return new ArrayList<>(defineProvider.getColumnOrder(domain));
    }


    /**
     * T2-residual {@code define_dataset_names}: the set of dataset (domain) names the sponsor
     * Define-XML declares for the study (the {@code ItemGroupDef} names), read from the Define
     * provider. Unlike {@link #evalDefineVariableNames} this is not domain-scoped — it returns
     * every declared dataset. Returns {@code null} (an unresolvable result) when no Define-XML is
     * supplied — RuleRunner has already SKIPped the rule for that case (see
     * {@link #isDefineDependent}); the null guard here is belt-and-braces.
     */
    private static @Nullable List<String> evalDefineDatasetNames(
            @Nullable MetadataProvider defineProvider)
    {
        if (defineProvider == null)
        {
            return null;
        }
        return upperCaseAll(defineProvider.getDatasetNames());
    }


    /**
     * T2-residual {@code define_key_variables}: the sponsor Define-XML KEY variable names for the
     * current domain, ordered by {@code KeySequence}. Returns {@code null} when no Define-XML is
     * supplied (RuleRunner SKIPs the rule).
     */
    private static @Nullable List<String> evalDefineKeyVariables(IDataTable table,
            @Nullable MetadataProvider defineProvider)
    {
        if (defineProvider == null)
        {
            return null;
        }
        String domain = net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(table);
        return new ArrayList<>(defineProvider.getKeyVariables(domain));
    }


    @SuppressWarnings(
    {
            "PMD.UnusedFormalParameter", "unused"
    })
    private static List<String> evalDatasetNames(IDataTable table, DatasetResolver resolver)
    {
        if (resolver instanceof DatasetResolver.WithInventory inv)
        {
            return upperCaseAll(inv.availableDatasets());
        }
        LOGGER.log(System.Logger.Level.DEBUG,
                "DatasetResolver does not implement WithInventory; " + "cannot enumerate datasets");
        return List.of();
    }


    /**
     * Uppercases every element (Locale.ROOT), preserving order and any {@code null} elements. FU-2:
     * {@code dataset_names} and {@code define_dataset_names} are uppercased in both engines so the
     * set-compares in SD0061/SD1063 are case-invariant and identical across Java and Python.
     */
    private static List<String> upperCaseAll(java.util.Collection<String> names)
    {
        List<String> out = new ArrayList<>(names.size());
        for (String n : names)
        {
            out.add(n == null ? null : n.toUpperCase(Locale.ROOT));
        }
        return out;
    }


    /**
     * J7: {@code study_domains()} — the distinct data-driven SDTM domains across the submission
     * (the {@code DOMAIN} cell, not member names), so a split LB
     * ({@code lbch}/{@code lbhe}/{@code lbur}) matches an {@code RDOMAIN} of {@code LB}. Mirrors
     * Python's {@code dataset.domain}.
     *
     * @param resolver
     *            the dataset resolver (must implement {@link DatasetResolver.WithInventory})
     * @return the distinct domains, or empty when the resolver cannot enumerate
     */
    private static List<String> evalStudyDomains(DatasetResolver resolver)
    {
        if (resolver instanceof DatasetResolver.WithInventory inv)
        {
            return new ArrayList<>(inv.availableDomains());
        }
        LOGGER.log(System.Logger.Level.DEBUG,
                "DatasetResolver does not implement WithInventory; cannot enumerate study domains");
        return List.of();
    }


    /**
     * J7: the data-driven SDTM domain of a dataset member, mirroring Python's
     * {@code SDTMDatasetMetadata.domain}. {@code SUPP}/{@code SQ}/{@code RELREC} have no
     * {@code DOMAIN} column (&rarr; {@code null}); an {@code AP--} member's domain is its full
     * 4-char name (e.g. {@code APQS}); otherwise the authoritative source is the {@code DOMAIN}
     * cell ({@link net.cumba.cdisc.core.metadata.CdiscDomainResolver#cdiscDomainOf}) when the table
     * resolves, else the first two characters of the (unsplit) member name.
     *
     * @param memberName
     *            the dataset member name (e.g. {@code lbch})
     * @param resolver
     *            resolver used to read the table's {@code DOMAIN} cell
     * @return the domain, or {@code null} for a no-domain dataset
     */
    static @Nullable String domainOfDataset(String memberName, DatasetResolver resolver)
    {
        if (memberName == null || memberName.isEmpty())
        {
            return null;
        }
        String n = memberName.toUpperCase(Locale.ROOT);
        if (n.startsWith("SUPP") || n.startsWith("SQ") || n.equals("RELREC"))
        {
            return null;
        }
        if (n.startsWith("AP") && n.length() >= 4)
        {
            return n.substring(0, 4);
        }
        if (n.length() >= 2)
        {
            IDataTable t = resolver.resolve(memberName);
            return t != null ? net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(t)
                    : n.substring(0, 2);
        }
        return n;
    }


    /** A non-{@code null} table as a singleton list, else an empty list (J7 fallback). */
    private static List<IDataTable> singletonOrEmpty(@Nullable IDataTable table)
    {
        return table == null ? List.of() : List.of(table);
    }

    // -----------------------------------------------------------------------
    // DY (Study Day) calculation
    // -----------------------------------------------------------------------


    private static @Nullable GroupedResult evalDyGrouped(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable List<String> groupCols)
    {
        // DY is always per-subject; produce GroupedResult
        if (op.getName() == null || groupCols == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int dateColIdx = meta.getColumnIndex(op.getName());
        if (dateColIdx < 0)
        {
            return null;
        }

        // Get the per-subject reference date from DM. Defaults to RFSTDTC (SDTM study day); a
        // rule may parameterise it (T6) to another DM reference column such as RFXSTDTC/RFCSTDTC.
        IDataTable dm = resolver.resolve("DM");
        if (dm == null)
        {
            return null;
        }
        String refCol = op.getReference() != null ? op.getReference() : "RFSTDTC";
        // Build USUBJID → reference-date map from DM
        Map<String, String> rfstdtcBySubject = new LinkedHashMap<>();
        DataTableMeta dmMeta = dm.getMetaData();
        int dmSubjIdx = dmMeta.getColumnIndex(USUBJID);
        int dmRfstIdx = dmMeta.getColumnIndex(refCol);
        if (dmSubjIdx >= 0 && dmRfstIdx >= 0)
        {
            for (long r = 0; r < dm.getRowCount(); r++)
            {
                IDataValue subj = dm.getColumn(dmSubjIdx).getDataValue(r);
                IDataValue rfst = dm.getColumn(dmRfstIdx).getDataValue(r);
                if (!subj.isMissingOrInvalid() && !rfst.isMissingOrInvalid())
                {
                    rfstdtcBySubject.put(subj.getValueAsString(), rfst.getValueAsString());
                }
            }
        }

        int subjIdx = meta.getColumnIndex(USUBJID);
        IDataTableColumn dateCol = table.getColumn(dateColIdx);
        long rowCount = table.getRowCount();
        Map<String, Object> results = new LinkedHashMap<>();

        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dateDv = dateCol.getDataValue(r);
            if (dateDv.isMissingOrInvalid())
            {
                continue;
            }
            String dateStr = dateDv.getValueAsString();
            if (dateStr == null || dateStr.length() < 10)
            {
                continue;
            }

            String subjId = subjIdx >= 0
                    ? table.getColumn(subjIdx).getDataValue(r).getValueAsString()
                    : "";
            String rfstdtc = rfstdtcBySubject.get(subjId);
            if (rfstdtc == null || rfstdtc.length() < 10)
            {
                continue;
            }

            Long dy = calculateStudyDay(dateStr, rfstdtc);
            if (dy != null)
            {
                String key = GroupedResult.buildKey(meta, table, groupCols, r);
                results.put(key, dy);
            }
        }
        return declaredGrouped(op, groupCols, results);
    }


    /**
     * SDTM study day calculation: If date &gt;= RFSTDTC: dy = daysBetween(RFSTDTC, date) + 1 If
     * date &lt; RFSTDTC: dy = daysBetween(RFSTDTC, date) (negative, no day 0)
     */
    private static @Nullable Long calculateStudyDay(String dateStr, String rfstdtc)
    {
        try
        {
            java.time.LocalDate date = java.time.LocalDate.parse(dateStr.substring(0, 10));
            java.time.LocalDate ref = java.time.LocalDate.parse(rfstdtc.substring(0, 10));
            long days = java.time.temporal.ChronoUnit.DAYS.between(ref, date);
            return days >= 0 ? days + 1 : days;
        }
        catch (Exception _)
        {
            return null;
        }
    }


    /**
     * E3 — {@code date_diff_days}: per-record integer days-between (no {@code +1}) of two dates
     * plus an offset. The <b>minuend</b> side is Mode 1/2 (the {@code name} date on the evaluation
     * record) unless {@code minuend_domain} is set — then Mode 3 reads the minuend date from the
     * record of that foreign domain that matches the evaluation row on the sided {@code
     * minuend_match} keys (the {@code --SPID} mass linkage). The <b>subtrahend</b> side is
     * independent: Mode 1 (no {@code domain}) subtracts the same-record {@code reference} column;
     * Mode 2 ({@code domain} + {@code group}) subtracts the grouped extreme of the
     * {@code reference} column sourced from the foreign {@code domain} and joined by the
     * {@code group} key. See {@link OperationType#DATE_DIFF_DAYS}.
     */
    private static @Nullable GroupedResult evalDateDiffDays(Operation op, IDataTable table,
            DatasetResolver resolver)
    {
        String minuendName = op.getName();
        String referenceName = op.getReference();
        if (minuendName == null || referenceName == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();

        List<String> group = op.getGroup();
        String domain = op.getDomain();

        // offset: an integer literal (default 0) or a per-record integer column name.
        String offsetSpec = op.getOffset();
        int offsetLiteral = 0;
        int offsetColIdx = -1;
        if (offsetSpec != null && !offsetSpec.isEmpty())
        {
            try
            {
                offsetLiteral = Integer.parseInt(offsetSpec.trim());
            }
            catch (NumberFormatException _)
            {
                offsetColIdx = meta.getColumnIndex(offsetSpec);
            }
        }
        IDataTableColumn offsetCol = offsetColIdx >= 0 ? table.getColumn(offsetColIdx) : null;

        // Key columns: the columns whose per-row values determine the computed value, all present
        // in the evaluation table so GroupedResult.getForRow can rebuild the key. The subtrahend
        // and (Mode 3) minuend resolvers return the per-row date, or "" (rejected by the length
        // gate below) when the record has no usable value.
        List<String> keyCols = new ArrayList<>();

        // --- Minuend side ---
        String minuendDomain = op.getMinuendDomain();
        java.util.function.LongFunction<String> minuendForRow;
        if (minuendDomain != null && !minuendDomain.isEmpty())
        {
            // Mode 3 — the minuend date is read from the foreign `minuend_domain` record that
            // matches the evaluation row on the sided `minuend_match` keys (the --SPID linkage).
            minuendForRow = buildForeignMinuendResolver(op, table, meta, resolver, minuendDomain,
                    minuendName, group, keyCols);
            if (minuendForRow == null)
            {
                return null; // foreign domain / match / minuend column unresolvable ⇒ SKIP
            }
        }
        else
        {
            int minuendIdx = meta.getColumnIndex(minuendName);
            if (minuendIdx < 0)
            {
                return null;
            }
            IDataTableColumn minuendCol = table.getColumn(minuendIdx);
            keyCols.add(minuendName);
            minuendForRow = r ->
            {
                IDataValue mdv = minuendCol.getDataValue(r);
                if (mdv.isMissingOrInvalid())
                {
                    return "";
                }
                String v = mdv.getValueAsString();
                return v != null ? v : "";
            };
        }

        // --- Subtrahend side ---
        java.util.function.LongFunction<String> subtrahendForRow;
        if (domain != null && group != null && !group.isEmpty())
        {
            // Mode 2 — grouped-extreme reference sourced from the foreign domain, joined by group
            // key. reference_extreme = "max" selects the latest date; anything else = earliest.
            boolean useMax = "max".equalsIgnoreCase(op.getReferenceExtreme());
            // EC-51 Half B / §5.3: the disposition is threaded in explicitly — this helper
            // deliberately takes no Operation, so before Fix #145 the Mode 2 subtrahend was
            // unfilterable by construction.
            Map<String, String> refByGroup = buildGroupedExtremeDate(resolver, domain,
                    referenceName, group, useMax, missingIsIndeterminate(op));
            if (refByGroup == null)
            {
                // Unresolvable: the domain, the reference column, or every group key is missing.
                // EC-45 — this does NOT skip the rule (only LIBRARY_NOT_AVAILABLE does). The
                // $-ref resolves to "no value" on every row, folds to "" and the check fires:
                // a populated derived day count whose inputs cannot support it is unverifiable
                // and worth reporting. Whole-operation applicability is Scope.Variables' job.
                return null;
            }
            List<String> groupCols = group;
            keyCols.addAll(group);
            subtrahendForRow = r ->
            {
                String v = refByGroup.get(GroupedResult.buildKey(meta, table, groupCols, r));
                return v != null ? v : "";
            };
        }
        else
        {
            // Mode 1 — the reference is a date column read from the same record.
            int refIdx = meta.getColumnIndex(referenceName);
            if (refIdx < 0)
            {
                return null;
            }
            IDataTableColumn refCol = table.getColumn(refIdx);
            keyCols.add(referenceName);
            subtrahendForRow = r ->
            {
                IDataValue rdv = refCol.getDataValue(r);
                if (rdv.isMissingOrInvalid())
                {
                    return "";
                }
                String v = rdv.getValueAsString();
                return v != null ? v : "";
            };
        }
        if (offsetColIdx >= 0 && offsetSpec != null)
        {
            keyCols.add(offsetSpec);
        }
        // Deduplicate key columns, first-seen order preserved: Mode 3's left match keys can overlap
        // the subtrahend group key (e.g. USUBJID appears in both). A no-op for Mode 1/2, so their
        // key encoding stays byte-identical.
        keyCols = new ArrayList<>(new LinkedHashSet<>(keyCols));

        Map<String, Object> results = new LinkedHashMap<>();
        long rowCount = table.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            String minuendStr = minuendForRow.apply(r);
            if (minuendStr.length() < 10)
            {
                continue;
            }

            String subtrahendStr = subtrahendForRow.apply(r);
            if (subtrahendStr.length() < 10)
            {
                continue;
            }

            Long diff = daysBetween(subtrahendStr, minuendStr);
            if (diff == null)
            {
                continue;
            }
            int offset = offsetLiteral;
            if (offsetCol != null)
            {
                IDataValue odv = offsetCol.getDataValue(r);
                if (!odv.isMissingOrInvalid())
                {
                    double d = odv.getValueAsDouble();
                    if (!Double.isNaN(d))
                    {
                        offset = (int) Math.round(d);
                    }
                }
            }
            String key = GroupedResult.buildKey(meta, table, keyCols, r);
            results.put(key, Long.valueOf(diff + offset));
        }
        return declaredGrouped(op, keyCols, results);
    }


    /**
     * EC-18 / P5c — Mode 3 minuend resolver for {@code date_diff_days}. Builds a per-evaluation-row
     * function returning the minuend date read from the record of the foreign {@code minuendDomain}
     * that matches the evaluation row on the sided {@code minuend_match} keys.
     * <p>
     * The match keys are resolved <b>per side</b>: a {@code --}-prefixed key resolves to the
     * evaluation domain's prefix on the left and to {@code minuendDomain} on the right (so
     * {@code "--SPID"} pairs {@code TFSPID} with {@code PMSPID}); a bare key is same-named. When
     * {@code minuend_match} is absent the operation falls back to {@code group} as the (same-named)
     * match key. Matching is first-wins; a foreign or evaluation row with any blank match key never
     * matches (so an empty {@code --SPID} does not spuriously join). The left match columns are
     * added to {@code keyCols} so {@link GroupedResult#getForRow} can rebuild each row's key.
     * <p>
     * Returns {@code null} (⇒ the operation SKIPs) when the foreign domain is absent, the minuend
     * column is absent from it, no usable match key is available, or a resolved match column is
     * absent from either side.
     */
    private static java.util.function.@Nullable LongFunction<String> buildForeignMinuendResolver(
            Operation op, IDataTable table, DataTableMeta meta, DatasetResolver resolver,
            String minuendDomain, String minuendName, @Nullable List<String> group,
            List<String> keyCols)
    {
        IDataTable minuendTable = resolver.resolve(minuendDomain);
        if (minuendTable == null)
        {
            return null;
        }
        DataTableMeta mMeta = minuendTable.getMetaData();
        int mDateIdx = mMeta.getColumnIndex(minuendName);
        if (mDateIdx < 0)
        {
            return null;
        }
        List<String> matchKeys = op.getMinuendMatch();
        if (matchKeys == null || matchKeys.isEmpty())
        {
            matchKeys = group;
        }
        if (matchKeys == null || matchKeys.isEmpty())
        {
            return null;
        }
        // EC-36: variable names -> variable prefix; "" for SUPP, AP suffix for AP.
        String evalPrefix = Objects
                .requireNonNullElse(variableWildcardPrefix(table, domainPrefix(table)), "");
        List<String> leftKeys = matchKeys.stream()
                .map(k -> k != null && k.contains("--") ? k.replace("--", evalPrefix) : k).toList();
        List<String> rightKeys = matchKeys.stream()
                .map(k -> k != null && k.contains("--") ? k.replace("--", minuendDomain) : k)
                .toList();
        for (String lk : leftKeys)
        {
            if (lk == null || meta.getColumnIndex(lk) < 0)
            {
                return null;
            }
        }
        for (String rk : rightKeys)
        {
            if (rk == null || mMeta.getColumnIndex(rk) < 0)
            {
                return null;
            }
        }
        IDataTableColumn mDateCol = minuendTable.getColumn(mDateIdx);
        Map<String, String> minuendByKey = new LinkedHashMap<>();
        long mRows = minuendTable.getRowCount();
        for (long r = 0; r < mRows; r++)
        {
            if (!allKeysPopulated(mMeta, minuendTable, rightKeys, r))
            {
                continue;
            }
            IDataValue dv = mDateCol.getDataValue(r);
            if (dv.isMissingOrInvalid())
            {
                continue;
            }
            String v = dv.getValueAsString();
            if (v == null || v.isEmpty())
            {
                continue;
            }
            minuendByKey.putIfAbsent(GroupedResult.buildKey(mMeta, minuendTable, rightKeys, r), v);
        }
        keyCols.addAll(leftKeys);
        List<String> finalLeftKeys = leftKeys;
        return r ->
        {
            if (!allKeysPopulated(meta, table, finalLeftKeys, r))
            {
                return "";
            }
            String v = minuendByKey.get(GroupedResult.buildKey(meta, table, finalLeftKeys, r));
            return v != null ? v : "";
        };
    }


    /**
     * {@code true} when every column in {@code keys} carries a non-missing, non-blank (after
     * {@code strip()}) value at {@code row}. Used by the Mode-3 minuend match so a blank
     * {@code --SPID} never joins.
     */
    private static boolean allKeysPopulated(DataTableMeta meta, IDataTable table, List<String> keys,
            long row)
    {
        for (String col : keys)
        {
            int idx = meta.getColumnIndex(col);
            if (idx < 0)
            {
                return false;
            }
            IDataValue dv = table.getColumn(idx).getDataValue(row);
            if (dv.isMissingOrInvalid())
            {
                return false;
            }
            String v = dv.getValueAsString();
            if (v == null || v.strip().isEmpty())
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Builds the per-group extreme value of {@code refCol}, read from the foreign {@code domain}
     * dataset, keyed with the same encoding
     * {@link GroupedResult#buildKey(DataTableMeta, IDataTable, List, long)} produces so the map is
     * joinable to the target rows by the {@code group} key. When {@code useMax} is {@code false}
     * the earliest ({@code min}) value per group is kept; when {@code true} the latest
     * ({@code max}) — both by lexicographic ISO-8601 order. Returns {@code null} when the domain is
     * absent from the study, when the {@code reference} column is absent from it, or when no
     * {@code group} column is present on the foreign dataset (see below); an empty map (domain
     * present, no rows) is a valid result. ⚠ {@code null} here does <b>not</b> skip the rule — only
     * {@link #LIBRARY_NOT_AVAILABLE} does that — it makes the {@code $}-ref resolve to "no value"
     * for every row, which the comparison folds to {@code ""} and the check fires over (EC-45
     * §1.1).
     *
     * <p>
     * <b>EC-45 §4.2 — the two key bases are coupled by <em>removing</em> code, not by intersecting
     * the column lists.</b> A literal intersection would drop a column present in the producer and
     * absent from the consumer, widening the join to the surviving keys: the row would receive the
     * study-wide extreme instead of its own group's and {@code !=} would fire with a <em>plausible
     * wrong number</em> instead of a null — worse than the defect. The coupling is already in
     * {@link GroupedResult#buildKey(DataTableMeta, IDataTable, List, long)}, which renders
     * {@code ""} on whichever side lacks the column, so:
     * </p>
     * <ul>
     * <li><b>absent from both ⇒ the column cancels</b> and the join proceeds on the surviving
     * columns — which is what the old all-or-nothing guard prevented, killing the whole operation
     * (and firing every row) over one column nobody could have keyed on anyway;</li>
     * <li><b>absent from the foreign side only ⇒ the column stops discriminating
     * <em>there</em></b>, so the foreign side collapses that component to {@code ""}. An evaluation
     * row whose own value for it is populated can then never match and reads "no value"; a row
     * whose value is a literal {@code ""} matches the collapsed bucket and receives the aggregate
     * over the whole foreign column. ⚠ That is deliberate and is the EC-43 contract's reachable
     * half. Since {@code W38-A1} (Fix #249) a <em>marker-missing</em> evaluation cell renders its
     * own identity token rather than {@code ""}, so it no longer matches the collapsed
     * absent-column bucket — a {@code MissingValue} equals no string key, ruling part 4 — and
     * likewise a present-but-all-marker-missing foreign column keys its marker token, not
     * {@code ""}. The absent-column case therefore behaves exactly as a present-but-all-{@code ""}
     * column; absence and <em>marker</em> missingness are distinguishable on this join, in the
     * ruled direction. Pinned by {@code RuleRunnerDateDiffKeyAbsenceTest} (its blanks are literal
     * {@code ""}); do not "tighten" it back into the all-or-nothing guard without re-opening
     * Q2;</li>
     * <li><b>absent from the evaluation side only ⇒ unmeetable</b>, since the foreign side keys
     * real values and every evaluation row keys {@code ""}; every row reads "no value", unchanged
     * from before;</li>
     * <li><b>absent from the foreign dataset for <em>every</em> declared column ⇒ there is no key
     * at all</b>, and one foreign extreme would broadcast onto every evaluation row. That is the
     * plausible-wrong-number shape again, so it is carved out and yields no value instead. The test
     * is deliberately one-sided (the <em>producer</em>'s columns): a key basis the foreign dataset
     * cannot express is no key basis, whatever the evaluation table happens to carry.</li>
     * </ul>
     *
     * <p>
     * An entry still carrying the {@code $} sigil is an unexpanded operation reference — a broken
     * chain in the rule's operation list, not a fact about the study's data — and keeps returning
     * {@code null}, exactly as {@link IndexHelper#groupByPresent} does for the same input.
     * </p>
     */
    private static @Nullable Map<String, String> buildGroupedExtremeDate(DatasetResolver resolver,
            String domain, String refColName, List<String> group, boolean useMax,
            boolean missingIsIndeterminate)
    {
        IDataTable ds = resolver.resolve(domain);
        if (ds == null)
        {
            return null;
        }
        DataTableMeta dsMeta = ds.getMetaData();
        int refIdx = dsMeta.getColumnIndex(refColName);
        if (refIdx < 0)
        {
            return null;
        }
        boolean anyKeyColumn = false;
        for (String g : group)
        {
            if (g == null || g.startsWith("$"))
            {
                return null;
            }
            anyKeyColumn |= dsMeta.getColumnIndex(g) >= 0;
        }
        if (!anyKeyColumn)
        {
            return null;
        }
        IDataTableColumn rc = ds.getColumn(refIdx);
        // EC-46: accumulate per group, then resolve. A group whose extreme is indeterminate emits
        // no entry at all — the same shape an all-blank group has always produced.
        // EC-51 Half B: the key is built for EVERY row, not only the populated ones, because a
        // missing candidate has to reach its own group's accumulator to make it undeterminable.
        // Under the `skip` default the extra keys are inert — a group with no usable candidate
        // resolves to null and is dropped below, exactly as when it was never created.
        Map<String, DateExtreme> byGroup = new LinkedHashMap<>();
        long rows = ds.getRowCount();
        for (long r = 0; r < rows; r++)
        {
            String key = GroupedResult.buildKey(dsMeta, ds, group, r);
            byGroup.computeIfAbsent(key, _ -> new DateExtreme(useMax, missingIsIndeterminate))
                    .addCell(rc.getDataValue(r));
        }
        Map<String, String> extremeByGroup = new LinkedHashMap<>();
        byGroup.forEach((key, extreme) ->
        {
            String resolved = extreme.result();
            if (resolved != null)
            {
                extremeByGroup.put(key, resolved);
            }
        });
        return extremeByGroup;
    }


    /**
     * The calendar days from {@code subtrahend} to {@code minuend} (both truncated to their leading
     * {@code yyyy-MM-dd}), i.e. {@code DAYS.between(subtrahend, minuend)} — no {@code +1}. Returns
     * {@code null} when either value cannot be parsed as a date.
     */
    private static @Nullable Long daysBetween(String subtrahend, String minuend)
    {
        try
        {
            java.time.LocalDate ref = java.time.LocalDate.parse(subtrahend.substring(0, 10));
            java.time.LocalDate date = java.time.LocalDate.parse(minuend.substring(0, 10));
            return java.time.temporal.ChronoUnit.DAYS.between(ref, date);
        }
        catch (Exception _)
        {
            return null;
        }
    }


    /**
     * E4 — {@code is_last_in_group}: per-record boolean, {@code true} for the last (maximum
     * {@code ordering}) row of each {@code group} partition. See
     * {@link OperationType#IS_LAST_IN_GROUP}.
     */
    private static @Nullable GroupedResult evalIsLastInGroup(Operation op, IDataTable table)
    {
        List<String> group = op.getGroup();
        String ordering = op.getOrdering();
        if (group == null || group.isEmpty() || ordering == null)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        int ordIdx = meta.getColumnIndex(ordering);
        if (ordIdx < 0)
        {
            return null;
        }
        // EC-44 (Fix #134): an entry still carrying the `$` sigil is an unresolved operation
        // reference, not an absent column — the same boundary IndexHelper.groupByPresent draws.
        // Widening the grouping there would let a broken operation chain produce a dataset-wide
        // answer.
        for (String g : group)
        {
            if (g != null && g.startsWith("$"))
            {
                return null;
            }
        }
        // EC-44 (Fix #134): partition() ignores absent group columns, so a PARTIAL drop just
        // groups on the survivors. TOTAL absence is the one case this operator cannot express:
        // its GroupedResult is keyed by (group… + ordering) and GroupedResult.buildKey renders
        // every absent component as "", so two rows sharing an ordering value would collapse to
        // the same key — and exactly one of them is the last row, so the second put() would
        // overwrite the first. Rather than emit a silently wrong verdict, degrade as before.
        // This is a limitation of the result-key space, not an exception to the EC-44 contract.
        if (group.stream().noneMatch(g -> g != null && meta.getColumnIndex(g) >= 0))
        {
            return null;
        }
        // ⚠⚠ DROP_MISSING_KEYS is this operator's shipped default and it DISAGREES with the other
        // five
        // evaluators on the same `Operations[].group:` surface, which fold (KEEP_MISSING_KEYS via
        // IndexHelper.groupByPresent). See groupKeyPolicy: the asymmetry is now declarable per rule
        // rather than silent, and correcting the default is deliberately a separate step so its
        // finding delta is attributable.
        List<int[]> groups = GroupSemantics.group(table, group,
                groupKeyPolicy(op, GroupKeyPolicy.DROP_MISSING_KEYS));
        IDataTableColumn ordCol = table.getColumn(ordIdx);
        List<String> keyCols = new ArrayList<>(group);
        keyCols.add(ordering);

        Map<String, Object> results = new LinkedHashMap<>();
        for (int[] g : groups)
        {
            if (g.length == 0)
            {
                continue;
            }
            // Numeric-aware ordering (Java↔Python parity): match Python is_last_in_group's
            // `idxmax` — a numeric ordering column (e.g. --SEQ) is compared numerically (so 12 > 9,
            // not lexicographic "9" > "12"), falling back to string compare when non-numeric; on
            // ties the FIRST row achieving the max is kept (mirrors pandas idxmax
            // first-occurrence).
            int lastRow = g[0];
            for (int r : g)
            {
                if (orderingCompare(ordCol.getDataValue(r), ordCol.getDataValue(lastRow)) > 0)
                {
                    lastRow = r;
                }
            }
            for (int r : g)
            {
                String key = GroupedResult.buildKey(meta, table, keyCols, r);
                boolean isLast = r == lastRow;
                results.put(key, isLast);
            }
        }
        return declaredGrouped(op, keyCols, results);
    }


    /**
     * Compares two ordering-column cell values numerically when both parse as numbers (so
     * {@code --SEQ} 12 &gt; 9), else lexicographically. Mirrors the Python {@code is_last_in_group}
     * {@code idxmax} on a numeric column, keeping Java↔Python parity for {@code is_last_in_group}.
     */
    private static int orderingCompare(IDataValue a, IDataValue b)
    {
        String sa = a.isMissingOrInvalid() ? "" : a.getValueAsString();
        String sb = b.isMissingOrInvalid() ? "" : b.getValueAsString();
        try
        {
            return Double.compare(Double.parseDouble(sa), Double.parseDouble(sb));
        }
        catch (NumberFormatException _)
        {
            return sa.compareTo(sb);
        }
    }


    /**
     * EC-8 — per-record horizontal max/min over the columns whose names match {@code name_pattern}.
     * For each row it collects the populated (non-missing, non-blank) cell values of the matched
     * columns and reduces them to the extreme with {@link #rowExtreme}; a row with no populated
     * matching cell is omitted (its absent key resolves to {@code null}, so the dependent
     * comparison skips it). The result is keyed by the matched columns themselves (the DY
     * per-row-resolution precedent). Returns {@code null} — so the rule SKIPs — when the pattern is
     * empty/invalid or no column matches.
     */
    private static @Nullable GroupedResult evalRowExtreme(Operation op, IDataTable table,
            boolean max)
    {
        String pattern = op.getNamePattern();
        if (pattern == null || pattern.isEmpty())
        {
            return null;
        }
        Pattern compiled;
        try
        {
            compiled = Pattern.compile(pattern);
        }
        catch (java.util.regex.PatternSyntaxException _)
        {
            return null;
        }
        DataTableMeta meta = table.getMetaData();
        List<String> cols = new ArrayList<>();
        for (int c = 0; c < meta.getColumnCount(); c++)
        {
            String colName = meta.getColumn(c).getName();
            if (compiled.matcher(colName).matches())
            {
                cols.add(colName);
            }
        }
        if (cols.isEmpty())
        {
            return null;
        }
        Map<String, Object> results = new LinkedHashMap<>();
        long rowCount = table.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            List<String> vals = new ArrayList<>();
            for (String col : cols)
            {
                String s = extremeCandidate(
                        table.getColumn(meta.getColumnIndex(col)).getDataValue(r));
                if (s != null)
                {
                    vals.add(s);
                }
            }
            if (vals.isEmpty())
            {
                continue; // no populated matching cell ⇒ omit (absent key ⇒ null ⇒ comparison
                          // skips)
            }
            // EC-46: an indeterminate row omits its key too, exactly as an all-blank one does.
            String extreme = rowExtreme(vals, max);
            if (extreme == null)
            {
                continue;
            }
            results.putIfAbsent(GroupedResult.buildKey(meta, table, cols, r), extreme);
        }
        return declaredGrouped(op, cols, results);
    }


    /**
     * EC-8 — two-mode horizontal reducer. When every value parses as a finite decimal number
     * ({@link #ROW_EXTREME_NUMERIC}) the numeric extreme is selected; otherwise the string branch
     * applies. Either way the winning <em>original cell string</em> is returned so a downstream
     * numeric-aware {@code not_equal_to} compares naturally. On ties the first value achieving the
     * extreme wins. {@code vals} is guaranteed non-empty by the caller.
     *
     * <p>
     * <b>EC-46</b> — the string branch is the same GENERIC policy as {@link #genericStringExtreme}:
     * the date rule only when every candidate is positionable, plain lexicographic order otherwise.
     * It therefore returns {@code null} when a date row is indeterminate, so the caller omits the
     * key exactly as it does for an all-blank row. No shipped rule is exposed here — ADaM
     * {@code TRxxEDT} is {@code Num}, so this reducer takes its numeric branch — but keeping the
     * five extreme sites on one policy is what stops the next date-valued {@code row_max} from
     * silently getting lexicographic treatment.
     * </p>
     */
    private static @Nullable String rowExtreme(List<String> vals, boolean max)
    {
        boolean allNumeric = true;
        double[] nums = new double[vals.size()];
        for (int i = 0; i < vals.size(); i++)
        {
            String s = vals.get(i);
            if (ROW_EXTREME_NUMERIC.matcher(s).matches())
            {
                nums[i] = Double.parseDouble(s);
            }
            else
            {
                allNumeric = false;
                break;
            }
        }
        if (!allNumeric)
        {
            return genericStringExtreme(vals, max);
        }
        int best = 0;
        for (int i = 1; i < vals.size(); i++)
        {
            if (max ? Double.compare(nums[i], nums[best]) > 0
                    : Double.compare(nums[i], nums[best]) < 0)
            {
                best = i;
            }
        }
        return vals.get(best);
    }

}
