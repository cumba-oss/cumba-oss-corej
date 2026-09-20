package net.cumba.corej.core.expr.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.metadata.LibraryVariableAttributes;
import net.cumba.corej.core.metadata.SdtmObservationClasses;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * Translates an {@code Operation} authored as a single function call (Form B) into the equivalent
 * field-form {@link Operation} the {@link net.cumba.corej.core.exec.OperationExecutor} consumes.
 *
 * <p>
 * The authoring contract: the function name is the {@code operator}
 * ({@link OperationType#fromJson(String)} must recognise it), the lone positional argument (if any)
 * is the target {@code name} (a column / {@code --}-wildcard / metadata key), and every keyword
 * argument names an {@code Operation} field. The {@code filter} map has no first-class grammar, so
 * it is authored as a nested marker call {@code filter(K="v", …)} whose own keyword arguments
 * become the {@code Map<String,String>} entries. Examples:
 * </p>
 *
 * <pre>{@code
 *   variable_count(--LNKGRP)
 *   variable_value_count(--LNKGRP)
 *   record_count(filter=filter(TSPARMCD="INDIC", TSVALNF="NA"))
 *   record_count(group=[USUBJID])
 *   distinct(IDVAR, value_is_reference=true)
 *   get_dataset_filtered_variables(key_name="role", key_value="Timing")
 *   constant("Y")
 * }</pre>
 *
 * <p>
 * The same {@link #fromCall(Expr.Call, String)} mapping is reused by the native compiler's inline
 * operation-function path (Form A), so the two authoring surfaces share one coercion
 * implementation. Any authoring error — an unknown function name, an unknown keyword, a malformed
 * {@code filter} marker, a non-scalar where a scalar is required — is a
 * {@link RuleDefinitionException}, surfaced through the established {@code Rule.loadError} channel.
 * </p>
 */
public final class OperationExpressionParser
{

    private OperationExpressionParser()
    {
    }


    /**
     * Returns the field-form equivalent of {@code op} when it carries an {@code expression} (Form
     * B), or {@code op} unchanged when it is already field form. The returned operation preserves
     * the source {@code id} and carries no {@code expression}, so downstream code treats it as an
     * ordinary field-form operation.
     *
     * @throws RuleDefinitionException
     *             if the expression is not a single recognised operation function call
     */
    public static Operation normalize(Operation op)
    {
        String expression = op.getExpression();
        if (expression == null)
        {
            return op;
        }
        if (op.getOperator() != null)
        {
            throw new RuleDefinitionException(
                    "operation declares both `expression` and `operator`; the field form is"
                            + " retired — author the whole operation as the `expression` function"
                            + " call: `" + expression + "`");
        }
        // ⛔ Phase 7b (owner ruling 2026-09-17): an expression operation carrying ANY sibling
        // parameter field is an error naming the offending key. fromCall builds a FRESH Operation
        // from the parsed expression, so a sibling field would otherwise be OVERWRITTEN and
        // silently discarded — measured: `level: "term"` beside an expression that does not name
        // it resolved to null with no error, and a disagreeing sibling lost to the expression with
        // no error. That is the FDA-SD1078 silent-drop shape ("no element field should overwrite
        // an expression / function parameter"). The Jackson surface already rejects these keys
        // (Binding.rejectRetiredKey); this guard is the programmatic-construction twin.
        for (Map.Entry<String, java.util.function.Function<Operation, @Nullable Object>> field : FIELD_READERS
                .entrySet())
        {
            if (field.getValue().apply(op) != null)
            {
                throw new RuleDefinitionException("binding `" + op.getId()
                        + "` carries both `expression` and the parameter field `" + field.getKey()
                        + "` — a parameter is authored inside the expression as a keyword"
                        + " argument (`" + field.getKey() + "=…`), never as a sibling field");
            }
        }
        Expr parsed;
        try
        {
            parsed = CheckExpressionParser.parse(expression);
        }
        catch (ExpressionException ex)
        {
            throw new RuleDefinitionException(
                    "invalid operation expression `" + expression + "`: " + ex.getMessage());
        }
        if (!(parsed instanceof Expr.Call call))
        {
            throw new RuleDefinitionException(
                    "operation expression must be a single function call: `" + expression + "`");
        }
        return fromCall(call, op.getId());
    }


    /**
     * Builds a field-form {@link Operation} from a parsed operation function call, assigning the
     * given {@code id} (the {@code $}-variable for Form B; {@code null} for an inline Form-A call
     * that is never referenced by name).
     *
     * @throws RuleDefinitionException
     *             if the call's name is not an operation, it has more than one positional argument,
     *             or any keyword argument is unknown / malformed
     */
    public static Operation fromCall(Expr.Call call, @Nullable String id)
    {
        String operator = call.name();
        FunctionDescriptor descriptor = OperationDescriptors.byName(operator);
        if (descriptor == null)
        {
            throw new RuleDefinitionException("unknown operation function `" + operator + "`");
        }
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        List<Expr> args = call.args();
        if (args.size() > 1)
        {
            throw new RuleDefinitionException("operation `" + operator
                    + "` accepts at most one positional argument (the target name) but got "
                    + args.size());
        }
        String positionalParam = null;
        if (args.size() == 1)
        {
            // A list literal in the sole positional slot is the composite `names` target (T3
            // `distinct([VISIT, VISITNUM], …)`); a scalar is the ordinary `name`; a CALL or an
            // arithmetic expression is a COMPUTED target (D3/D14 — this branch is what closes
            // R11: `max` / `date_diff_days` were excluded from the column-type gate only because
            // stringOf threw on any Call here, a positional artifact, not semantics).
            Expr sole = args.get(0);
            if (sole instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
            {
                op.setNames(listOf(sole));
                positionalParam = "names";
            }
            else if (isComputedTarget(sole))
            {
                validateComputedTarget(operator, sole);
                op.setNameExpr(sole);
                positionalParam = "name";
            }
            else
            {
                op.setName(stringOf(sole));
                positionalParam = "name";
            }
        }
        RuleDefinitionException undeclared = null;
        for (Map.Entry<String, Expr> kw : call.kwargs().entrySet())
        {
            String key = kw.getKey();
            if (key.equals(positionalParam))
            {
                // D19a's second error: a name the positional already bound may not be given
                // again.
                throw new RuleDefinitionException("argument `" + key + "` of operation `" + operator
                        + "` is already bound by the positional target and may not"
                        + " be given again by name");
            }
            // ⭐ Phase 6b (D16/D19a): the operation's own descriptor decides which keyword
            // arguments exist — per operation, not the union the old 35-arm switch accepted for
            // every operator and the executor then silently ignored (the FDA-SD1078 shape). A
            // globally-unknown name keeps applyKwarg's own rejection; a known parameter the
            // operator does not declare is remembered and raised AFTER the four value-validators,
            // whose operator-naming messages ({@code validateKeyName} et al.) take precedence.
            if (descriptor.parameter(key) == null && !VALIDATOR_OWNED.contains(key)
                    && undeclared == null)
            {
                undeclared = new RuleDefinitionException("operation `" + operator
                        + "` has no parameter `" + key + "`; " + parameterList(descriptor));
            }
            applyKwarg(op, operator, key, kw.getValue());
        }
        validateMissingValues(op);
        validateKeepMissings(op);
        validateModelClass(op);
        validateKeyName(op);
        if (undeclared != null)
        {
            throw undeclared;
        }
        return op;
    }


    /**
     * Phase 6b — the field-form twin of the {@link #fromCall} descriptor gate: every populated
     * parameter field of {@code op} must be a declared parameter of its operator's descriptor
     * ({@link OperationDescriptors}). A field-form (Jackson-bound) operation never passes through
     * {@code fromCall}, and every mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, so a
     * parameter the operation does not consume would otherwise bind silently and be dropped at
     * runtime — the same silent under-report {@link #validateKeyName} documents for FDA-SD1078,
     * generalised from four hand-kept allowlists to the whole parameter surface.
     *
     * <p>
     * Public and idempotent for the same three-surface reason as {@link #validateMissingValues};
     * {@code RulePackageLoader.normalizeOperations} runs it over normalised and field-form
     * operations alike.
     * </p>
     *
     * @param op
     *            the operation to check
     * @throws RuleDefinitionException
     *             if a populated field is not a parameter of the operator
     */
    public static void validateAgainstDescriptor(Operation op)
    {
        String operator = op.getOperator();
        if (operator == null)
        {
            return; // an unparsed Form-B operation; normalize() reports it on its own channel
        }
        FunctionDescriptor descriptor = OperationDescriptors.byName(operator);
        if (descriptor == null)
        {
            return; // unknown operator: reported by the loader's own operator validation
        }
        for (Map.Entry<String, java.util.function.Function<Operation, @Nullable Object>> field : FIELD_READERS
                .entrySet())
        {
            if (VALIDATOR_OWNED.contains(field.getKey()))
            {
                continue; // judged by its own shipped validator, whose message names the consumers
            }
            if (field.getValue().apply(op) != null && descriptor.parameter(field.getKey()) == null)
            {
                throw new RuleDefinitionException("operation `" + operator + "` has no parameter `"
                        + field.getKey() + "`, so the declaration would be silently dropped; "
                        + parameterList(descriptor));
            }
        }
    }

    /**
     * The four parameters whose per-operation legality is owned by a shipped, message-bearing
     * validator ({@link #validateMissingValues}, {@link #validateKeepMissings},
     * {@link #validateModelClass}, {@link #validateKeyName}) rather than the generic descriptor
     * gate — their allowlists agree with the descriptors by construction
     * ({@code OperationDescriptorsTest} pins the equality), and their messages name the consuming
     * operators, which the generic message cannot.
     */
    private static final java.util.Set<String> VALIDATOR_OWNED = java.util.Set.of("missing_values",
            "keep_missings", "model_class", "key_name");

    /**
     * Every author-facing parameter field of {@link Operation}, keyed by its authored keyword.
     * Deliberately excludes the engine-internal fields ({@code id}, {@code operator},
     * {@code expression}, {@code originalName} — the specialiser's provenance carrier).
     */
    private static final Map<String, java.util.function.Function<Operation, @Nullable Object>> FIELD_READERS = buildFieldReaders();

    private static Map<String, java.util.function.Function<Operation, @Nullable Object>> buildFieldReaders()
    {
        Map<String, java.util.function.Function<Operation, @Nullable Object>> m = new LinkedHashMap<>();
        m.put("name", Operation::getName);
        m.put("names", Operation::getNames);
        m.put("subtract", Operation::getSubtract);
        m.put("value", Operation::getValue);
        m.put("domain", Operation::getDomain);
        m.put("reference", Operation::getReference);
        m.put("offset", Operation::getOffset);
        m.put("reference_extreme", Operation::getReferenceExtreme);
        m.put("minuend_domain", Operation::getMinuendDomain);
        m.put("minuend_match", Operation::getMinuendMatch);
        m.put("delimiter", Operation::getDelimiter);
        m.put("ordering", Operation::getOrdering);
        m.put("group", Operation::getGroup);
        m.put("filter", Operation::getFilter);
        m.put("codelists", Operation::getCodelists);
        m.put("level", Operation::getLevel);
        m.put("returntype", Operation::getReturntype);
        m.put("key_name", Operation::getKeyName);
        m.put("key_value", Operation::getKeyValue);
        m.put("model_class", Operation::getModelClass);
        m.put("ct_attribute", Operation::getCtAttribute);
        m.put("version", Operation::getVersion);
        m.put("ct_package_types", Operation::getCtPackageTypes);
        m.put("regex", Operation::getRegex);
        m.put("name_pattern", Operation::getNamePattern);
        m.put("min_length", Operation::getMinLength);
        m.put("value_is_reference", Operation::getValueIsReference);
        m.put("external_dictionary_type", Operation::getExternalDictionaryType);
        m.put("dictionary_term_type", Operation::getDictionaryTermType);
        m.put("case_sensitive", Operation::getCaseSensitive);
        m.put("external_dictionary_term_variable", Operation::getExternalDictionaryTermVariable);
        m.put("dictionary_parent", Operation::getDictionaryParent);
        m.put("qualifying_any_populated", Operation::getQualifyingAnyPopulated);
        m.put("missing_values", Operation::getMissingValues);
        m.put("keep_missings", Operation::getKeepMissings);
        return m;
    }


    /**
     * Every author-facing parameter keyword of {@link Operation} — the {@link #FIELD_READERS} key
     * set. Public for {@code Binding.rejectRetiredKey}, whose per-key rejection message
     * distinguishes a retired field-form parameter from a plainly unknown key.
     *
     * @return the authored parameter keywords, unmodifiable
     */
    public static java.util.Set<String> parameterKeys()
    {
        return java.util.Collections.unmodifiableSet(FIELD_READERS.keySet());
    }


    private static String parameterList(FunctionDescriptor descriptor)
    {
        return "parameters are (" + descriptor.parameters().stream()
                .map(net.cumba.corej.core.expr.eval.Parameter::name)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }


    /**
     * Rejects a {@code key_name:} declaration that can never match anything, on the same
     * {@code loadError} channel as {@link #validateMissingValues}.
     *
     * <p>
     * <b>Silent under-report is this field's failure mode, and it has already cost a shipped
     * rule.</b> {@code FDA-SD1078} was authored as
     * {@code get_model_filtered_variables(key_name="core", key_value="Perm")} — a Model-level walk
     * with a key that walk does not publish. {@code varRow.get("core")} was {@code null} for every
     * variable, the filter matched nothing, {@code is_contained_by []} was always false and the
     * rule could never fire: a 100 % under-report with no SKIP, no error and no log line. It was
     * caught by a human reading metadata (corpus review R2, {@code OPS-MISC-03}), not by a test.
     * </p>
     *
     * <p>
     * Two rejections, both by allowlist:
     * </p>
     * <ol>
     * <li><b>the operator</b> must be one that reads the field at all —
     * {@code get_model_filtered_variables} and {@code get_dataset_filtered_variables} (the library
     * variable filters) or {@code ts_parameter_value} (whose {@code key_name} is a <em>dataset
     * column</em>, e.g. {@code TSPARMCD}, not a library attribute). ⚠⚠ Note what this catches:
     * {@code get_column_order_from_library} <b>documents</b> {@code key_name}/{@code key_value} and
     * the Python engine filters on them, but coreJ's arm never reads them — so a declaration there
     * is not "empty", it is worse: the operation silently returns the <em>unfiltered</em> column
     * order. Nothing else in the engine touches {@code Operation.getKeyName()};</li>
     * <li><b>the value</b>, on the two library filters only, must be one of
     * {@link LibraryVariableAttributes#KEYS} — the closed set of attribute keys any resolver
     * populates a variable row with. ⚠ Since P3 widened the row that set is the full
     * <em>scalar</em> field union, so the SEND authoring template's whole legal vocabulary
     * ({@code definition} / {@code examples} / {@code notes} / {@code variableCcode} included) now
     * loads. What remains rejected is a key outside it: the three list-valued stored fields
     * ({@code valueList}, {@code codelistSubmissionValues}, {@code codelistIds}), which no row can
     * carry because the row is {@code Map<String, String>} and the Python comparison against a list
     * is always false — plus every typo. Such a key is published by no level, so the filter
     * provably matches nothing on every dataset.</li>
     * </ol>
     *
     * <p>
     * ⛔ <b>What this deliberately does NOT reject is {@code core} on
     * {@code get_model_filtered_variables}</b> — the very shape FDA-SD1078 got wrong. Measured in
     * {@code MetadataLibraryProvider}: the Model walk publishes {@code core} for SUPP--/SQ--
     * datasets (the cascade's tier A projects IG {@code SUPPQUAL} dataset variables, its tier C is
     * the hard-coded RELATIONSHIP fallback) and for <em>every</em> ADaM dataset. Rejecting it at
     * load would refuse rules that legitimately work. Whether the key is served is a per-dataset,
     * per-level runtime fact, so that half is a runtime diagnostic —
     * {@code OperationExecutor.warnUnservedKeyName} — which names the level, the dataset and the
     * keys the resolved rows actually published, and which fires on every standard domain a rule of
     * FDA-SD1078's shape is run against.
     * </p>
     *
     * <p>
     * Public and idempotent for the three-surface reason of {@link #validateMissingValues}: a
     * field-form operation never reaches {@link #fromCall}, and an inline operation never reaches
     * the rule's {@code Operations} list at all.
     * </p>
     *
     * @param op
     *            the operation to check
     * @throws RuleDefinitionException
     *             if the declaration can never match
     */
    public static void validateKeyName(Operation op)
    {
        String keyName = op.getKeyName();
        if (keyName == null)
        {
            return;
        }
        OperationType type = OperationType.fromJson(op.getOperator());
        if (type == OperationType.TS_PARAMETER_VALUE)
        {
            // T7's key_name names a column of the TS/TX dataset, not a library attribute — the
            // library vocabulary below does not apply to it.
            return;
        }
        if (type != OperationType.GET_MODEL_FILTERED_VARIABLES
                && type != OperationType.GET_DATASET_FILTERED_VARIABLES)
        {
            throw new RuleDefinitionException(
                    "`key_name` is not consumed by operation `" + op.getOperator() + "`; only `"
                            + OperationType.GET_MODEL_FILTERED_VARIABLES.getJsonValue() + "`, `"
                            + OperationType.GET_DATASET_FILTERED_VARIABLES.getJsonValue()
                            + "` and `" + OperationType.TS_PARAMETER_VALUE.getJsonValue()
                            + "` read it, so the filter would be silently dropped");
        }
        if (!LibraryVariableAttributes.KEYS.contains(keyName))
        {
            throw new RuleDefinitionException("`key_name` `" + keyName + "` on operation `"
                    + op.getOperator() + "` is not an attribute of a library variable, so the"
                    + " filter can never match; expected one of "
                    + new java.util.TreeSet<>(LibraryVariableAttributes.KEYS));
        }
    }


    /**
     * EC-85 — rejects a {@code model_class:} declaration that cannot mean what its author intends,
     * on the same {@code loadError} channel as {@link #validateMissingValues}, and normalises an
     * accepted value in place ({@code events} → {@code EVENTS}).
     *
     * <p>
     * Two rejections, both by allowlist. <b>Silent loss is this field's failure mode</b>: every
     * mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, and an unknown class name that
     * reached the resolver would come back as an empty walk — which a {@code varname() in $x} leaf
     * reads as "never a member", a false PASS no fixture can see:
     * </p>
     * <ol>
     * <li><b>the operator</b> must be {@code get_model_filtered_variables}, the only consumer;
     * anywhere else the field would be dead;</li>
     * <li><b>the value</b> must normalise to one of
     * {@link SdtmObservationClasses#MODEL_CLASS_NAMES}. Only the <em>spelling</em> is checked — the
     * loader has no library; whether the loaded model carries the class is the resolver's runtime
     * answer (D-6: an unserved class SKIPs the rule).</li>
     * </ol>
     *
     * <p>
     * Public and idempotent for the three-surface reason of {@link #validateMissingValues}: a
     * field-form operation never reaches {@link #fromCall}, and an inline operation never reaches
     * the rule's {@code Operations} list at all.
     * </p>
     *
     * @param op
     *            the operation to check and normalise
     * @throws RuleDefinitionException
     *             if the declaration is unusable
     */
    public static void validateModelClass(Operation op)
    {
        String raw = op.getModelClass();
        if (raw == null)
        {
            return;
        }
        if (OperationType.fromJson(op.getOperator()) != OperationType.GET_MODEL_FILTERED_VARIABLES)
        {
            throw new RuleDefinitionException("`model_class` is only valid on operation `"
                    + OperationType.GET_MODEL_FILTERED_VARIABLES.getJsonValue() + "`, not `"
                    + op.getOperator() + "`");
        }
        String norm = SdtmObservationClasses.normalise(raw.trim());
        if (norm == null || !SdtmObservationClasses.MODEL_CLASS_NAMES.contains(norm))
        {
            throw new RuleDefinitionException(
                    "unknown `model_class` value `" + raw + "`; expected one of "
                            + new java.util.TreeSet<>(SdtmObservationClasses.MODEL_CLASS_NAMES));
        }
        op.setModelClass(norm);
    }


    /**
     * Rejects a {@code keep_missings:} declaration that cannot mean what its author intends, on the
     * same {@code loadError} channel as {@link #validateMissingValues}.
     *
     * <p>
     * Two rejections, both by allowlist. <b>Silent loss is this field's failure mode</b> — every
     * mapper in the repo runs with {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, so anything not
     * explicitly rejected here is ignored at runtime and the author never learns:
     * </p>
     * <ol>
     * <li><b>the operator</b> must be one that forms groups from {@code group:} — {@code min_date},
     * {@code max_date}, {@code max}, {@code distinct}, {@code record_count},
     * {@code has_mixed_emptiness_within_group}, {@code is_last_in_group}. Anywhere else the field
     * would be dead;</li>
     * <li><b>there must be a {@code group:}</b> to apply it to. A grouping-key disposition on an
     * operation with no grouping key is a no-op, which is the silent shape this guard exists to
     * prevent — the same reasoning that makes {@code missing_values} require
     * {@code date_diff_days}' Mode 2.</li>
     * </ol>
     *
     * <p>
     * The value itself needs no check here: it is typed {@code Boolean}, so Jackson rejects a
     * non-boolean on the field form and {@link #applyKwarg} rejects a non-boolean literal on the
     * call forms.
     * </p>
     *
     * <p>
     * Public and idempotent, for the same three-surface reason as {@link #validateMissingValues}: a
     * field-form operation never reaches {@link #fromCall}, and an inline operation never reaches
     * the rule's {@code Operations} list at all.
     * </p>
     *
     * @param op
     *            the operation to check
     * @throws RuleDefinitionException
     *             if the declaration is unusable
     */
    public static void validateKeepMissings(Operation op)
    {
        if (op.getKeepMissings() == null)
        {
            return;
        }
        OperationType type = OperationType.fromJson(op.getOperator());
        if (type != OperationType.MIN_DATE && type != OperationType.MAX_DATE
                && type != OperationType.MAX && type != OperationType.DISTINCT
                && type != OperationType.RECORD_COUNT
                && type != OperationType.HAS_MIXED_EMPTINESS_WITHIN_GROUP
                && type != OperationType.IS_LAST_IN_GROUP)
        {
            throw new RuleDefinitionException("`keep_missings` is not supported by operation `"
                    + op.getOperator() + "`; only the grouped operations consume it");
        }
        if (op.getGroup() == null || op.getGroup().isEmpty())
        {
            throw new RuleDefinitionException(
                    "`keep_missings` on operation `" + op.getOperator() + "` requires a non-empty"
                            + " `group`; with no grouping key it would have no effect");
        }
    }


    /**
     * EC-51 Half B — rejects a {@code missing_values:} declaration that cannot mean what its author
     * intends, on the {@code loadError} channel (a {@link RuleDefinitionException} raised here is
     * turned into one by {@code RulePackageLoader.normalizeOperations}, so the rule ERRORs rather
     * than evaluating with the declaration silently dropped).
     *
     * <p>
     * Three rejections, the first two by allowlist rather than denylist. <b>Silent loss is this
     * field's failure mode</b> — every mapper in the repo runs with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and there is no global Jackson naming strategy, so
     * anything not explicitly rejected here is simply ignored at runtime and the author never
     * learns:
     * </p>
     * <ol>
     * <li><b>the value</b> must be exactly {@link Operation#MISSING_VALUES_SKIP} or
     * {@link Operation#MISSING_VALUES_INDETERMINATE}. Unlike {@code reference_extreme}'s lenient
     * {@code "max".equalsIgnoreCase(…)} read, a typo is an error, not a silent default;</li>
     * <li><b>the operator</b> must be one that consumes it: {@code min_date}, {@code max_date} or
     * {@code date_diff_days}. Everything else — including the four §5.2 names {@code record_count},
     * {@code distinct}, {@code row_max} and {@code row_min} — is rejected. Counting or collecting
     * blanks is not a determinability question; a blank horizontal cell ({@code TRxxEDT}) is how
     * "period not used" is encoded, so {@code indeterminate} would kill the rule; and the generic
     * {@code max} string fallback also serves Char <em>category</em> columns ({@code ANRIND},
     * {@code ATOXGR}), where a blank is "not assessed". A rule that wants date determinability
     * authors {@code max_date} — the form EC-46 OQ4 moved the corpus's date extremes onto.</li>
     * <li><b>{@code date_diff_days} must be in Mode 2</b> — {@code domain} plus a non-empty
     * {@code group}. Only Mode 2's grouped subtrahend consumes the disposition; a Mode 1
     * same-record reference already yields no value when it is missing, so a declaration there
     * would change nothing, which is the very silent no-op the other two rejections exist to
     * prevent.</li>
     * </ol>
     *
     * <p>
     * The <em>fourth</em> rejection — {@code indeterminate} on a positive-polarity consuming leaf —
     * cannot live here, because an {@link Operation} does not know its rule's {@code Check}. It is
     * raised on the same channel by {@code RulePackageLoader.validateMissingValuesPolarity(Rule)}.
     * </p>
     *
     * <p>
     * Public and idempotent so the other two authoring surfaces get identical treatment. A
     * <b>field-form</b> operation never reaches {@link #fromCall} and Jackson would bind an
     * unsupported combination without complaint; an <b>inline</b> operation authored inside a
     * native Check expression never reaches the rule's {@code Operations} list at all, and the
     * native compiler's own rejection would degrade the rule to LEGACY evaluation rather than
     * erroring it. {@code RulePackageLoader} re-runs this method over both.
     * </p>
     *
     * @throws RuleDefinitionException
     *             if the declaration is unusable
     */
    public static void validateMissingValues(Operation op)
    {
        String disposition = op.getMissingValues();
        if (disposition == null)
        {
            return;
        }
        if (!Operation.MISSING_VALUES_SKIP.equals(disposition)
                && !Operation.MISSING_VALUES_INDETERMINATE.equals(disposition))
        {
            throw new RuleDefinitionException("`missing_values` must be `"
                    + Operation.MISSING_VALUES_SKIP + "` or `"
                    + Operation.MISSING_VALUES_INDETERMINATE + "`, got `" + disposition + "`");
        }
        OperationType type = OperationType.fromJson(op.getOperator());
        if (type != OperationType.MIN_DATE && type != OperationType.MAX_DATE
                && type != OperationType.DATE_DIFF_DAYS)
        {
            throw new RuleDefinitionException(
                    "`missing_values` is not supported by operation `" + op.getOperator()
                            + "`; only `min_date`, `max_date` and `date_diff_days` consume it");
        }
        // `date_diff_days` consumes it on its Mode 2 GROUPED subtrahend only — the extreme taken
        // over the foreign `domain` within the `group` key. In Mode 1 (and in a Mode 3 without a
        // grouped subtrahend) the subtrahend is a same-record read, which already yields no value
        // when it is missing, so the declaration would change nothing at all. Accepting it there
        // would be exactly the silent no-op the rest of this guard exists to prevent.
        if (type == OperationType.DATE_DIFF_DAYS
                && (op.getDomain() == null || op.getGroup() == null || op.getGroup().isEmpty()))
        {
            throw new RuleDefinitionException(
                    "`missing_values` on `date_diff_days` requires the Mode 2 grouped subtrahend"
                            + " (`domain` plus a non-empty `group`); on a same-record reference it"
                            + " would have no effect");
        }
    }


    private static void applyKwarg(Operation op, String operator, String key, Expr value)
    {
        switch (key)
        {
        case "name" -> op.setName(stringOf(value));
        case "names" -> op.setNames(listOf(value));
        case "subtract" -> op.setSubtract(stringOf(value));
        case "value" -> op.setValue(listOf(value));
        case "domain" -> op.setDomain(stringOf(value));
        case "reference" -> op.setReference(stringOf(value));
        case "offset" -> op.setOffset(stringOf(value));
        case "reference_extreme" -> op.setReferenceExtreme(stringOf(value));
        case "minuend_domain" -> op.setMinuendDomain(stringOf(value));
        case "minuend_match" -> op.setMinuendMatch(listOf(value));
        case "delimiter" -> op.setDelimiter(stringOf(value));
        case "ordering" -> op.setOrdering(stringOf(value));
        case "group" -> op.setGroup(listOf(value));
        case "filter" -> op.setFilter(filterOf(value));
        case "codelists" -> op.setCodelists(listOf(value));
        case "level" -> op.setLevel(stringOf(value));
        case "returntype" -> op.setReturntype(stringOf(value));
        case "key_name" -> op.setKeyName(stringOf(value));
        case "key_value" -> op.setKeyValue(stringOf(value));
        case "model_class" -> op.setModelClass(stringOf(value));
        case "ct_attribute" -> op.setCtAttribute(stringOf(value));
        case "version" -> op.setVersion(stringOf(value));
        case "ct_package_types" -> op.setCtPackageTypes(listOf(value));
        case "regex" -> op.setRegex(stringOf(value));
        case "name_pattern" -> op.setNamePattern(stringOf(value));
        case "min_length" -> op.setMinLength(intOf(value));
        case "value_is_reference" -> op.setValueIsReference(boolOf(value));
        case "external_dictionary_type" -> op.setExternalDictionaryType(stringOf(value));
        case "dictionary_term_type" -> op.setDictionaryTermType(stringOf(value));
        case "case_sensitive" -> op.setCaseSensitive(boolOf(value));
        case "external_dictionary_term_variable" -> op
                .setExternalDictionaryTermVariable(stringOf(value));
        case "dictionary_parent" -> op.setDictionaryParent(stringOf(value));
        case "qualifying_any_populated" -> op.setQualifyingAnyPopulated(listOf(value));
        // EC-51 Half B. `stringOf` already rejects a list literal, which is the shape a PLURAL key
        // invites (`missing_values: ["", " "]`); validateMissingValues then rejects every value
        // that is not one of the two dispositions, so a number/boolean cannot slip through as its
        // rendered text either.
        case "missing_values" -> op.setMissingValues(stringOf(value));
        case "keep_missings" -> op.setKeepMissings(boolOf(value, "keep_missings"));
        default -> throw new RuleDefinitionException(
                "unknown argument `" + key + "` for operation `" + operator + "`");
        }
    }

    /**
     * The operations whose target reads ROWS of the resolved target table — the shapes a computed
     * target can be materialised for (a synthetic appended column). The metadata / library / walk
     * operations read names and keys, not row values, so a computed target there has no meaning.
     */
    private static final java.util.Set<OperationType> COMPUTED_TARGET_OPERATIONS = java.util.Set.of(
            OperationType.MAX, OperationType.MAX_DATE, OperationType.MIN_DATE,
            OperationType.DISTINCT, OperationType.DATE_DIFF_DAYS, OperationType.RECORD_COUNT);

    /**
     * Whether the sole positional is a computed target (D3/D14): a value function call, or an
     * arithmetic expression. The {@code filter(...)} marker is not a value and never a target.
     */
    private static boolean isComputedTarget(Expr e)
    {
        return switch (e)
        {
        case Expr.Call c -> !"filter".equals(c.name());
        case Expr.Binary b -> b.op() == Expr.BinOp.ADD || b.op() == Expr.BinOp.SUB
                || b.op() == Expr.BinOp.MUL || b.op() == Expr.BinOp.DIV;
        default -> false;
        };
    }


    /**
     * Guards the first increment of computed targets, loudly: only the row-reading operations
     * ({@link #COMPUTED_TARGET_OPERATIONS}) can materialise one, and a {@code --} domain-prefix
     * reference inside the expression is rejected because the specialiser (D77) does not descend
     * into operation target expressions yet — accepting it would defer the failure to the engine's
     * unresolved-{@code --} evaluation assertion (D93a), which D35 reserves for specialiser
     * defects, not authoring.
     */
    private static void validateComputedTarget(String operator, Expr target)
    {
        OperationType type = OperationType.fromJson(operator);
        if (!COMPUTED_TARGET_OPERATIONS.contains(type))
        {
            throw new RuleDefinitionException("operation `" + operator
                    + "` does not support a computed target expression; only the row-reading"
                    + " operations (max, max_date, min_date, distinct, date_diff_days,"
                    + " record_count) do");
        }
        rejectDomainPrefixRefs(target, operator);
    }


    private static void rejectDomainPrefixRefs(Expr e, String operator)
    {
        switch (e)
        {
        case Expr.Ref r ->
        {
            if (r.name().startsWith("--"))
            {
                throw new RuleDefinitionException("operation `" + operator
                        + "`: a `--` domain-prefix reference inside a computed target expression"
                        + " is not supported yet (`" + r.name()
                        + "`) — spell the concrete column name");
            }
        }
        case Expr.Call c ->
        {
            c.args().forEach(a -> rejectDomainPrefixRefs(a, operator));
            c.kwargs().values().forEach(a -> rejectDomainPrefixRefs(a, operator));
        }
        case Expr.Binary b ->
        {
            rejectDomainPrefixRefs(b.left(), operator);
            rejectDomainPrefixRefs(b.right(), operator);
        }
        default ->
        {
            // literals and the boolean combinators (which cannot appear in a value target)
        }
        }
    }


    /** A scalar operand: a bare reference's name, or a string / number / boolean literal's text. */
    private static String stringOf(Expr e)
    {
        return switch (e)
        {
        case Expr.Ref r -> r.name();
        case Expr.Lit lit -> litString(lit);
        default -> throw new RuleDefinitionException(
                "operation argument must be a name or scalar literal, got "
                        + e.getClass().getSimpleName());
        };
    }


    private static String litString(Expr.Lit lit)
    {
        return switch (lit.kind())
        {
        case STRING, REGEX -> (String) lit.value();
        case NUMBER ->
        {
            // Render an integral value without the ".0" tail, fractional verbatim. String-based
            // (no floating-point equality test) — operations never carry numeric arguments anyway,
            // so this is purely a defensive round-trip.
            String s = Double.toString((Double) lit.value());
            yield s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        }
        case BOOL -> lit.value().toString();
        case LIST -> throw new RuleDefinitionException(
                "expected a scalar operation argument but found a list literal");
        };
    }


    private static Integer intOf(Expr e)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER)
        {
            // CheckExpressionParser lexes an integer literal as a Double (e.g. 200.0); round back
            // to
            // the int the `min_length` field carries. A fractional value is a definitional error.
            double d = (Double) lit.value();
            if (Double.compare(d, Math.rint(d)) == 0)
            {
                return (int) d;
            }
        }
        throw new RuleDefinitionException("min_length must be an integer literal");
    }


    private static Boolean boolOf(Expr e)
    {
        return boolOf(e, "value_is_reference");
    }


    /** {@link #boolOf(Expr)} with the field named in the rejection message. */
    private static Boolean boolOf(Expr e, String field)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL)
        {
            return (Boolean) lit.value();
        }
        throw new RuleDefinitionException(field + " must be a boolean literal (true/false)");
    }


    private static List<String> listOf(Expr e)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            List<String> out = new ArrayList<>(items.size());
            for (Expr item : items)
            {
                out.add(stringOf(item));
            }
            return out;
        }
        throw new RuleDefinitionException("expected a list literal `[a, b, …]`");
    }


    private static Map<String, Object> filterOf(Expr e)
    {
        if (e instanceof Expr.Call call && "filter".equals(call.name()))
        {
            if (!call.args().isEmpty())
            {
                throw new RuleDefinitionException(
                        "filter(...) takes only key=value pairs, not positional arguments");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Expr> entry : call.kwargs().entrySet())
            {
                Expr v = entry.getValue();
                // A list literal `[a, b, …]` is a membership filter; a scalar keeps equality.
                if (v instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
                {
                    map.put(entry.getKey(), listOf(v));
                }
                else
                {
                    map.put(entry.getKey(), stringOf(v));
                }
            }
            return map;
        }
        throw new RuleDefinitionException("filter= must be a `filter(K=\"v\", …)` marker call");
    }

}
