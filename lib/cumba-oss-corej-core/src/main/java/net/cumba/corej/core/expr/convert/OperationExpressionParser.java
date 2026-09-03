package net.cumba.corej.core.expr.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
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
                    "operation declares both `expression` and `operator`; use one form: `"
                            + expression + "`");
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
        if (OperationType.fromJson(operator) == null)
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
        if (args.size() == 1)
        {
            // A list literal in the sole positional slot is the composite `names` target (T3
            // `distinct([VISIT, VISITNUM], …)`); a scalar is the ordinary `name`.
            Expr sole = args.get(0);
            if (sole instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
            {
                op.setNames(listOf(sole));
            }
            else
            {
                op.setName(stringOf(sole));
            }
        }
        for (Map.Entry<String, Expr> kw : call.kwargs().entrySet())
        {
            applyKwarg(op, operator, kw.getKey(), kw.getValue());
        }
        validateMissingValues(op);
        validateKeepMissings(op);
        validateModelClass(op);
        return op;
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
     * authors {@code max_date}, exactly as {@code CORE-000717} was moved to do by EC-46 OQ4.</li>
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
