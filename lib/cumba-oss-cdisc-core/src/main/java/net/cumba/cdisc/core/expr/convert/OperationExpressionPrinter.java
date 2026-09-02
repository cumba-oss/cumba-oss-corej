package net.cumba.cdisc.core.expr.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.cumba.cdisc.core.model.Operation;
import org.jspecify.annotations.Nullable;

/**
 * Serialises a field-form {@link Operation} to its function-call authoring string (Form B) — the
 * inverse of {@link OperationExpressionParser#fromCall}. Used by {@code RulePackageConverter} to
 * rewrite the shipped corpus from field-form {@code Operations} to the function-call form, and
 * round-trip-tested against every shipped operation so the rewrite is provably loss-free.
 *
 * <p>
 * The function name is the {@code operator}; the {@code name} field is the lone positional
 * argument; every other populated field is a keyword argument. String-valued keyword arguments are
 * always quoted (robust for regexes, wildcards, and values with punctuation); list fields render as
 * {@code [a, b]}; {@code value_is_reference} renders as a bare {@code true}/{@code false}; the
 * {@code filter} map renders as the nested marker call {@code filter(K="v", …)}.
 * </p>
 */
public final class OperationExpressionPrinter
{

    /**
     * A token safe to emit unquoted: an upper-case column or a {@code --}-domain wildcard. A
     * lower-case / mixed-case token (e.g. the metadata field {@code label}, {@code dataset_name})
     * re-lexes as a built-in reference, not a column, so it must be quoted; quoting is always
     * round-trip-safe because {@link OperationExpressionParser} reads a string literal back to the
     * identical text.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("(--)?[A-Z][A-Z0-9_]*");

    private OperationExpressionPrinter()
    {
    }


    /**
     * Renders {@code op} as a single function-call string equivalent under
     * {@link OperationExpressionParser#normalize(Operation)}.
     */
    public static String print(Operation op)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(op.getOperator()).append('(');
        List<String> parts = new ArrayList<>();
        if (op.getNames() != null)
        {
            // T3 composite target: the ordered column list as the first positional argument, e.g.
            // `distinct([VISIT, VISITNUM], domain="TV")`. Round-trips via the parser's
            // list-positional
            // branch back to `names`.
            List<String> items = new ArrayList<>(op.getNames().size());
            for (String n : op.getNames())
            {
                items.add(printName(n));
            }
            parts.add("[" + String.join(", ", items) + "]");
        }
        else if (op.getName() != null)
        {
            // The `constant` operator's name is a literal value, never a column ⇒ always quote it.
            parts.add("constant".equals(op.getOperator()) ? quote(op.getName())
                    : printName(op.getName()));
        }
        if (op.getSubtract() != null)
        {
            // The minus subtrahend is a `$`-ref to a prior operation result; printName keeps the
            // bare `$name` form (round-trips back through OperationExpressionParser).
            parts.add("subtract=" + printName(op.getSubtract()));
        }
        // EC-7: minus literal value-list minuend as a kwarg (a positional list is the T3 composite
        // `names` form), e.g. minus(value=["AGEU", "SDESIGN"], subtract=$present).
        addList(parts, "value", op.getValue());
        addString(parts, "domain", op.getDomain());
        addString(parts, "reference", op.getReference());
        addString(parts, "offset", op.getOffset());
        addString(parts, "reference_extreme", op.getReferenceExtreme());
        // EC-51 Half B — the missing-candidate disposition. Omitted when null, which is the
        // `skip` default, so the shipped corpus is byte-identical until a rule declares it.
        addString(parts, "missing_values", op.getMissingValues());
        // EC-18 / P5c — date_diff_days Mode 3 foreign-minuend kwargs.
        addString(parts, "minuend_domain", op.getMinuendDomain());
        addList(parts, "minuend_match", op.getMinuendMatch());
        addString(parts, "delimiter", op.getDelimiter());
        addString(parts, "ordering", op.getOrdering());
        addList(parts, "group", op.getGroup());
        // The grouping-key disposition, printed next to the `group` it applies to. Omitted when
        // null — which is the engine default on every operator — so the shipped corpus stays
        // byte-identical until a rule declares it.
        //
        // ⚠⚠ Omitting this from the printer ERASES the field from every rule on the next corpus
        // regeneration, because `rules/` stores operations in exactly this inline form. That is not
        // hypothetical: OperationExpressionRoundTripTest caught precisely this omission here.
        if (op.getKeepMissings() != null)
        {
            parts.add("keep_missings=" + op.getKeepMissings());
        }
        if (op.getFilter() != null)
        {
            parts.add("filter=" + printFilter(op.getFilter()));
        }
        addList(parts, "codelists", op.getCodelists());
        addString(parts, "level", op.getLevel());
        addString(parts, "returntype", op.getReturntype());
        addString(parts, "key_name", op.getKeyName());
        addString(parts, "key_value", op.getKeyValue());
        // EC-85 — the class selector of get_model_filtered_variables; printed beside the filter
        // pair it composes with. Omitting it here would erase it from CDISC-SEND-0268/0269/0270
        // on the next regen (see the keep_missings warning above).
        addString(parts, "model_class", op.getModelClass());
        addString(parts, "ct_attribute", op.getCtAttribute());
        addString(parts, "version", op.getVersion());
        addList(parts, "ct_package_types", op.getCtPackageTypes());
        addString(parts, "regex", op.getRegex());
        addString(parts, "name_pattern", op.getNamePattern());
        if (op.getMinLength() != null)
        {
            parts.add("min_length=" + op.getMinLength());
        }
        addString(parts, "external_dictionary_type", op.getExternalDictionaryType());
        addString(parts, "dictionary_term_type", op.getDictionaryTermType());
        if (op.getCaseSensitive() != null)
        {
            parts.add("case_sensitive=" + op.getCaseSensitive());
        }
        addString(parts, "external_dictionary_term_variable",
                op.getExternalDictionaryTermVariable());
        addString(parts, "dictionary_parent", op.getDictionaryParent());
        addList(parts, "qualifying_any_populated", op.getQualifyingAnyPopulated());
        if (op.getValueIsReference() != null)
        {
            parts.add("value_is_reference=" + op.getValueIsReference());
        }
        sb.append(String.join(", ", parts)).append(')');
        return sb.toString();
    }


    private static void addString(List<String> parts, String key, @Nullable String value)
    {
        if (value != null)
        {
            parts.add(key + "=" + quote(value));
        }
    }


    private static void addList(List<String> parts, String key, @Nullable List<String> values)
    {
        if (values != null)
        {
            List<String> items = new ArrayList<>(values.size());
            for (String v : values)
            {
                items.add(printName(v));
            }
            parts.add(key + "=[" + String.join(", ", items) + "]");
        }
    }


    private static String printFilter(Map<String, Object> filter)
    {
        List<String> entries = new ArrayList<>(filter.size());
        for (Map.Entry<String, Object> e : filter.entrySet())
        {
            Object value = e.getValue();
            if (value instanceof List<?> members)
            {
                List<String> items = new ArrayList<>(members.size());
                for (Object m : members)
                {
                    items.add(quote(String.valueOf(m)));
                }
                entries.add(e.getKey() + "=[" + String.join(", ", items) + "]");
            }
            else
            {
                entries.add(e.getKey() + "=" + quote(String.valueOf(value)));
            }
        }
        return "filter(" + String.join(", ", entries) + ")";
    }


    /** A column / wildcard / {@code $}-reference name: raw when a safe bareword, else quoted. */
    private static String printName(String name)
    {
        if (name.startsWith("$") || SAFE_NAME.matcher(name).matches())
        {
            return name;
        }
        return quote(name);
    }


    private static String quote(String s)
    {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

}
