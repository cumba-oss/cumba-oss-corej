package net.cumba.corej.core.expr.convert;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import net.cumba.corej.core.model.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Phase 6b (D16/D17/D19a) — <b>one descriptor per operation</b>: every {@link OperationType}
 * carries an ordered, typed parameter list on the same {@link FunctionDescriptor} shape the
 * function registry uses. This is what retires the {@code Operation} field bag as an
 * <em>authoring</em> model: which keyword arguments an operation accepts, and what type each one
 * carries, is declared here — per operation — instead of being implied by the union of 39 fields a
 * closed 35-arm switch would bind for <em>any</em> operator and then silently drop at runtime (the
 * FDA-SD1078 shape). {@code OperationExpressionParser} binds call arguments against these
 * descriptors, and {@code validateAgainstDescriptor} holds field-form (Jackson-bound) operations to
 * the identical contract.
 *
 * <p>
 * <b>What stays deliberately unchanged (plan §5, "Keep")</b>: the operations' domain logic and its
 * carrier — {@code OperationExecutor} still consumes the field-form
 * {@link net.cumba.corej.core.model.Operation}, which is from here on an <em>executor-internal
 * bound- argument record</em>, not an authoring surface. The positional target keeps its shipped
 * discipline (at most ONE positional; a scalar is {@code name}, a list literal the composite
 * {@code names}) — declared as the leading {@code target} parameter of every descriptor.
 * </p>
 *
 * <p>
 * <b>Types</b> follow SPEC §1.6's mapping rule (what semantics require): {@code group} is a
 * {@code list<column-reference>} (level-raising, D5), {@code filter} is boolean-typed (D16 — its
 * only authored surface today is the {@code filter(K="v", …)} marker call, which lowers to a
 * conjunction of equalities), {@code domain} a cross-dataset reference (a string naming the foreign
 * dataset), {@code name_pattern} a {@code regex} (D92b), {@code level} the closed metadata-level
 * enum. Where an operation's target semantics vary ({@code constant}'s literal,
 * {@code extract_metadata}'s key), the target stays {@link Unknown#UNKNOWN}.
 * </p>
 *
 * <p>
 * ⛔ <b>{@code record_count} has exactly ONE descriptor</b> — this one. The D91e collision (the
 * arity-0 row-count builtin and the operation sharing {@code (name, arity=0)}, tiebroken by "does
 * the call carry a keyword argument") is unrepresentable under one-descriptor-per-name: the
 * registry's parameterless implementation is this descriptor's bare-form fast path, pinned
 * verdict-identical to the unfiltered/ungrouped executor path
 * ({@code RecordCountSingleDescriptorTest}), and an unknown keyword now errors instead of silently
 * selecting an implementation.
 * </p>
 */
public final class OperationDescriptors
{

    private static final Map<OperationType, FunctionDescriptor> DESCRIPTORS = build();

    private OperationDescriptors()
    {
    }


    /** The one descriptor of {@code type}; never {@code null} — every operation has one. */
    public static FunctionDescriptor of(OperationType type)
    {
        // Asserted, not assumed: build() is required to cover every OperationType constant, and a
        // constant added without its descriptor must fail HERE, naming itself, rather than as an
        // unattributed NPE in whichever evaluator dereferenced the result.
        return java.util.Objects.requireNonNull(DESCRIPTORS.get(type),
                () -> "no operation descriptor is registered for " + type
                        + " — OperationDescriptors.build() and OperationType have drifted");
    }


    /** The descriptor for an operation function name, or {@code null} if the name is unknown. */
    public static @Nullable FunctionDescriptor byName(String jsonName)
    {
        OperationType type = OperationType.fromJson(jsonName);
        return type == null ? null : DESCRIPTORS.get(type);
    }


    private static Map<OperationType, FunctionDescriptor> build()
    {
        Map<OperationType, FunctionDescriptor> map = new EnumMap<>(OperationType.class);
        for (OperationType type : OperationType.values())
        {
            map.put(type, descriptor(type));
        }
        return map;
    }


    private static FunctionDescriptor descriptor(OperationType type)
    {
        List<Parameter> params = new ArrayList<>();
        // The sole positional target (TSV: "at most ONE positional argument"): a scalar binds
        // `name`, a list literal the composite `names` — both spellable by keyword too.
        params.add(Parameter.optional("name", Primitive.COLUMN_REFERENCE));
        params.add(Parameter.optional("names", new ListOf(Primitive.COLUMN_REFERENCE)));
        switch (type)
        {
        case RECORD_COUNT -> add(params, domain(), filter(), group(), keepMissings(),
                Parameter.optional("regex", Primitive.REGEX));
        case DISTINCT -> add(params, domain(), filter(), group(), keepMissings(),
                Parameter.optional("value_is_reference", Primitive.BOOLEAN));
        case MAX -> add(params, domain(), filter(), group(), keepMissings());
        case MAX_DATE, MIN_DATE -> add(params, domain(), filter(), group(), keepMissings(),
                missingValues());
        case DATE_DIFF_DAYS -> add(params, domain(), group(), missingValues(), str("reference"),
                str("offset"), str("reference_extreme"), str("minuend_domain"),
                Parameter.optional("minuend_match", new ListOf(Primitive.STRING)));
        case DY -> add(params, group(), str("reference"));
        case MINUS -> add(params, str("subtract"),
                Parameter.optional("value", new ListOf(Primitive.STRING)));
        case CODELIST_TERMS -> add(params,
                Parameter.optional("codelists", new ListOf(Primitive.COLUMN_REFERENCE)),
                Parameter.optional("level", Primitive.METADATA_LEVEL), str("returntype"));
        case GET_CODELIST_ATTRIBUTES -> add(params, str("ct_attribute"), str("version"));
        case VALID_CODELIST_DATES -> add(params,
                Parameter.optional("ct_package_types", new ListOf(Primitive.STRING)));
        case GET_DATASET_FILTERED_VARIABLES -> add(params, str("key_name"), str("key_value"));
        case GET_MODEL_FILTERED_VARIABLES -> add(params, str("key_name"), str("key_value"),
                str("model_class"));
        case TS_PARAMETER_VALUE -> add(params, domain(), str("key_name"), str("key_value"));
        case SUPP_QNAM_PRESENT, SUPP_QNAM_VALUE -> add(params, domain(), str("key_value"));
        case VARIABLE_COUNT, ROW_MAX, ROW_MIN -> add(params,
                Parameter.optional("name_pattern", Primitive.REGEX));
        case COLUMN_SERIES_METADATA -> add(params,
                Parameter.optional("name_pattern", Primitive.REGEX),
                Parameter.optional("min_length", Primitive.NUMBER));
        case VARIABLE_EXISTS, CROSS_DATASET_VARIABLE_METADATA -> add(params, domain());
        case HAS_MIXED_EMPTINESS_WITHIN_GROUP -> add(params, group(), keepMissings(),
                Parameter.optional("qualifying_any_populated", new ListOf(Primitive.STRING)));
        case IS_LAST_IN_GROUP -> add(params, group(), keepMissings(), str("ordering"));
        case INTERVAL_UNCERTAINTY_PRECISION_MISMATCH -> add(params, str("delimiter"));
        case DICTIONARY_AVAILABLE -> add(params, str("external_dictionary_type"));
        case DICTIONARY_HAS_DECODE -> add(params, str("external_dictionary_type"), caseSensitive());
        case VALID_EXTERNAL_DICTIONARY_VALUE, VALID_EXTERNAL_DICTIONARY_CODE -> add(params,
                str("external_dictionary_type"), str("dictionary_term_type"), caseSensitive());
        case VALID_EXTERNAL_DICTIONARY_CODE_TERM_PAIR -> add(params,
                str("external_dictionary_type"), str("external_dictionary_term_variable"),
                caseSensitive());
        case VALID_EXTERNAL_DICTIONARY_HIERARCHY -> add(params, str("external_dictionary_type"),
                str("dictionary_parent"), caseSensitive());
        default ->
        {
            // Target-only operations (constant, extract_metadata, the library/define walks, …):
            // no keyword parameters beyond the target.
        }
        }
        return new FunctionDescriptor(type.getJsonValue(), params, FunctionKind.VALUE, null);
    }


    private static void add(List<Parameter> params, Parameter... more)
    {
        for (Parameter p : more)
        {
            params.add(p);
        }
    }


    private static Parameter str(String name)
    {
        return Parameter.optional(name, Primitive.STRING);
    }


    private static Parameter domain()
    {
        return Parameter.optional("domain", Primitive.STRING);
    }


    private static Parameter group()
    {
        return Parameter.optional("group", new ListOf(Primitive.COLUMN_REFERENCE));
    }


    private static Parameter filter()
    {
        // D16's doctrine: `filter=` is a boolean-typed argument. Its one authored surface today is
        // the `filter(K="v", …)` marker call (a conjunction of equalities over the target
        // dataset's own rows); a general boolean expression is the D60/phase-7b format migration.
        return Parameter.optional("filter", Primitive.BOOLEAN);
    }


    private static Parameter keepMissings()
    {
        return Parameter.optional("keep_missings", Primitive.BOOLEAN);
    }


    private static Parameter missingValues()
    {
        return Parameter.optional("missing_values", Primitive.STRING);
    }


    private static Parameter caseSensitive()
    {
        return Parameter.optional("case_sensitive", Primitive.BOOLEAN);
    }


    /** Unused reference to keep the import stable for the javadoc examples. */
    static ExprType unknownType()
    {
        return Unknown.UNKNOWN;
    }
}
