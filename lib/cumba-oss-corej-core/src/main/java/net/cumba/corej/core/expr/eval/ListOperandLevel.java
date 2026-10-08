package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The ONE level derivation for an operand that is read once per call as a dataset-level value
 * ({@code PLAN-union-function} §3.3, {@code PLAN-binding-substitutability} G3 — ruled by D3 / D16:
 * where {@code $x} is accepted, the expression {@code $x} holds is accepted too, and the only
 * refusals are a type or a level mismatch, reported alike for both spellings). A {@code $}-binding
 * is judged by its installed domain ({@link CompiledBinding#needsCursor()}); any other expression —
 * and a {@code $} whose domain is not installed yet, in the loader's seam pass — by
 * {@link DomainScan#infer} over {@link BindingDomains#forRule}, the very derivation
 * {@code RulePackageLoader.installNativeExpr} installs on a binding, so
 * {@code $p: split_by(X, ",")} read as {@code $p} and {@code split_by(X, ",")} written inline get
 * the same answer.
 *
 * <p>
 * Three kinds of reader are judged here: the set functions ({@link #LIST_SET_FUNCTIONS}: every
 * operand of {@code minus} / {@code union} is a dataset-level list, W1a U3), the registry
 * parameters read <b>once per call</b> ({@link #PER_CALL_PARAMETERS}, W1a BS1 — C9–C11 of
 * {@code PLAN-binding-substitutability}, ruled Q4: the open-vocabulary strings, the dictionary
 * functions' {@code case_sensitive} and the static lists, which the compiler held to a literal
 * until BS1 because a per-row value there would have been read at row 0), and the {@code $}
 * references inside a {@code filter=(…)} ({@link #FILTER_FUNCTIONS}, W1a BS3 — C16, ruled Q6: a
 * filter runs over the TARGET table's rows, so a binding read inside it is handed in as one
 * pre-resolved dataset-level constant — {@code GroupedAggregate.broadcastBindings} — and a per-row
 * one has no value to hand).
 * </p>
 *
 * <p>
 * Two callers: stage A ({@code StageAChecker.listFunctionReads}, the armed kind
 * {@code OPERATION_READS_CURSOR_BINDING}) where it walks the rule, and the loader's seam pass
 * ({@link #firstRefusal}) where it does not — a Check-less rule's bindings, a
 * {@code Match_Datasets} Filter, the re-run after a {@code CHECKER_FAILURE} — so a per-row operand
 * never silently reads row 0 on either path.
 * </p>
 *
 * <p>
 * A third reader kind lives on the loader path only: the <b>splice slots</b>
 * ({@link #spliceRefusals}, W1a BS2 — C4 / C5 of {@code PLAN-binding-substitutability}), whose
 * stage A judgement is the G3 arm ({@code StageAChecker.findPerRowSplice}, {@code PARAMETER_TYPE})
 * with the same sentence ({@link #spliceRefusal}).
 * </p>
 */
public final class ListOperandLevel
{

    /** The set functions whose operands are dataset-level lists ({@code minus}, {@code union}). */
    public static final Set<String> LIST_SET_FUNCTIONS = Set
            .of(net.cumba.corej.core.exec.Minus.NAME, net.cumba.corej.core.exec.Union.NAME);

    /**
     * The registry parameters read <b>once per call</b> whose value is read at evaluation (never at
     * plan-build time), keyed <b>function → parameters</b> — W1a BS1 widened each from a literal to
     * any dataset-level expression of its declared type: {@code extract_metadata} /
     * {@code variable_count} {@code name} and the {@code key_value} of the two Library walks (C9),
     * the five dictionary functions' {@code case_sensitive} (C10), {@code codelist_terms}
     * {@code codelists} and {@code valid_codelist_dates} {@code ct_package_types} (C11). NOT here:
     * {@code keep_missings} (consumed at plan-build time — an owner question, coordinator decision
     * 8), the closed vocabularies and regex parameters (literal types, G2) and the name slots
     * {@code column_series_metadata.name} / {@code cross_dataset_variable_metadata.domain}
     * (coordinator decision 1).
     */
    public static final Map<String, Set<String>> PER_CALL_PARAMETERS = Map.ofEntries(
            Map.entry(net.cumba.corej.core.exec.ScalarMetadataFunctions.EXTRACT_METADATA,
                    Set.of(net.cumba.corej.core.exec.ScalarMetadataFunctions.NAME_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.ScalarMetadataFunctions.VARIABLE_COUNT,
                    Set.of(net.cumba.corej.core.exec.ScalarMetadataFunctions.NAME_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.LibraryLists.GET_DATASET_FILTERED_VARIABLES,
                    Set.of(net.cumba.corej.core.exec.LibraryLists.KEY_VALUE_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.LibraryLists.GET_MODEL_FILTERED_VARIABLES,
                    Set.of(net.cumba.corej.core.exec.LibraryLists.KEY_VALUE_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.LibraryLists.CODELIST_TERMS,
                    Set.of(net.cumba.corej.core.exec.LibraryLists.CODELISTS_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.LibraryLists.VALID_CODELIST_DATES,
                    Set.of(net.cumba.corej.core.exec.LibraryLists.CT_PACKAGE_TYPES_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.DictionaryFunctions.HAS_DECODE,
                    Set.of(net.cumba.corej.core.exec.DictionaryFunctions.CASE_SENSITIVE_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.DictionaryFunctions.VALUE,
                    Set.of(net.cumba.corej.core.exec.DictionaryFunctions.CASE_SENSITIVE_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.DictionaryFunctions.CODE,
                    Set.of(net.cumba.corej.core.exec.DictionaryFunctions.CASE_SENSITIVE_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.DictionaryFunctions.CODE_TERM_PAIR,
                    Set.of(net.cumba.corej.core.exec.DictionaryFunctions.CASE_SENSITIVE_PARAMETER)),
            Map.entry(net.cumba.corej.core.exec.DictionaryFunctions.HIERARCHY, Set
                    .of(net.cumba.corej.core.exec.DictionaryFunctions.CASE_SENSITIVE_PARAMETER)));

    /**
     * The functions with a {@code filter=(…)} over a target table (the five grouped readers and
     * {@code read_value}): a {@code $}-reference inside the filter is judged per reference — the
     * filter itself reads the target's columns per row, so the expression as a whole is never the
     * operand (W1a BS3, C16).
     */
    public static final Set<String> FILTER_FUNCTIONS = Set.of(
            net.cumba.corej.core.exec.GroupedAggregate.MAX,
            net.cumba.corej.core.exec.GroupedAggregate.MAX_DATE,
            net.cumba.corej.core.exec.GroupedAggregate.MIN_DATE,
            net.cumba.corej.core.exec.RecordCount.NAME, net.cumba.corej.core.exec.Distinct.NAME,
            net.cumba.corej.core.exec.ReadValue.NAME);

    private ListOperandLevel()
    {
    }


    /**
     * Whether the operand's value varies per row or per variable, so a reader that takes one value
     * per call has no row to pick it at.
     *
     * @param operand
     *            the operand, bound ({@code $x}) or inline
     * @param rule
     *            the rule it is written in (its bindings supply the {@code $} domains)
     * @return {@code true} for a per-row / per-variable operand
     */
    public static boolean needsCursor(Expr operand, Rule rule)
    {
        if (operand instanceof Expr.Ref ref && ref.kind() == OperandKind.OPERATION_REF)
        {
            CompiledBinding read = rule.compiledBinding(ref.name());
            if (read != null && read.domain() != null)
            {
                return read.needsCursor();
            }
        }
        return !DomainScan.infer(operand, BindingDomains.forRule(rule)).isBroadcast();
    }


    /**
     * The refusal's text — one sentence for both spellings and both set functions.
     *
     * @param function
     *            the reading function ({@code minus} / {@code union})
     * @param where
     *            where the call is written ("the Check (ERROR)", "the binding $v", …)
     * @param operand
     *            the refused operand, printed as written
     * @return the message
     */
    public static String refusal(String function, String where, Expr operand)
    {
        return "the list function " + function + "(…) in " + where
                + " cannot read the per-row or per-variable operand `"
                + ExpressionPrinter.print(operand) + "` — " + setOperation(function)
                + " reads only dataset-level lists";
    }


    /**
     * The refusal's text for a parameter read once per call — one sentence for both spellings.
     *
     * @param function
     *            the reading function
     * @param parameter
     *            the parameter
     * @param where
     *            where the call is written
     * @param operand
     *            the refused operand, printed as written
     * @return the message
     */
    public static String parameterRefusal(String function, String parameter, String where,
            Expr operand)
    {
        return "argument '" + parameter + "' of '" + function + "' in " + where
                + " is read once per call and cannot take the per-row or per-variable value `"
                + ExpressionPrinter.print(operand)
                + "` — bound or inline, it takes a dataset-level value";
    }


    /**
     * The refusal's text for a {@code $}-reference inside {@code filter=(…)} (W1a BS3, C16).
     *
     * @param function
     *            the filtering function
     * @param where
     *            where the call is written
     * @param operand
     *            the refused {@code $}-reference, printed as written
     * @return the message
     */
    public static String filterRefusal(String function, String where, Expr operand)
    {
        return "argument 'filter' of '" + function + "' in " + where
                + " reads the per-row or per-variable value `" + ExpressionPrinter.print(operand)
                + "` — a filter runs over the target dataset's rows, so a $-reference inside it"
                + " must hold one dataset-level value";
    }


    /**
     * Whether the operand's value is a per-row vector at evaluation — what the splice refuses
     * ({@code GroupSplice}: a dataset-level binding is handed over as its first value, a row-cursor
     * one as its vector). The variable cursor alone is not a reason: a per-variable binding at
     * dataset granularity hands over its first value, which the splice reads as one name (stage A's
     * {@code isPerRow} draws the same line by granularity).
     *
     * @param operand
     *            the operand, bound ({@code $x}) or inline
     * @param rule
     *            the rule it is written in
     * @return {@code true} for a row-cursor operand
     */
    public static boolean readsRowCursor(Expr operand, Rule rule)
    {
        if (operand instanceof Expr.Ref ref && ref.kind() == OperandKind.OPERATION_REF)
        {
            CompiledBinding read = rule.compiledBinding(ref.name());
            if (read != null && read.domain() != null)
            {
                return read.domain().rowCursor();
            }
        }
        return DomainScan.infer(operand, BindingDomains.forRule(rule)).rowCursor();
    }


    /**
     * The refusal's text for a per-row member of a splice slot (every {@code group=},
     * {@code is_(not_)unique_set}'s members, {@code values}' columns — {@code GroupSplice}'s
     * roster) — one sentence for both spellings; stage A files it as {@code PARAMETER_TYPE} (the
     * splice is a shape of the {@code list<column-reference>} parameter), the loader's seam pass as
     * the load error.
     *
     * @param function
     *            the splicing function
     * @param parameter
     *            the parameter
     * @param where
     *            where the call is written, or {@code null} when the caller says so itself
     * @param operand
     *            the refused member, printed as written
     * @return the message
     */
    public static String spliceRefusal(String function, String parameter, @Nullable String where,
            Expr operand)
    {
        return "argument '" + parameter + "' of '" + function + "'"
                + (where == null ? "" : " in " + where) + " cannot splice the per-row member `"
                + ExpressionPrinter.print(operand)
                + "` — only a dataset-level list of column names (a binding or the expression it"
                + " holds) can be spliced: at evaluation a per-row value hands over its vector,"
                + " which the splice refuses";
    }


    /**
     * The splice-slot refusals of one call ({@code GroupSplice.spliceSlots}): each spliced member —
     * a {@code $} reference or a computed expression — that reads the row cursor. The loader's twin
     * of stage A's G3 arm ({@code StageAChecker.findPerRowSplice}); only {@link #firstRefusal}
     * calls it, so stage A never files a per-row splice twice.
     *
     * @param c
     *            the call
     * @param rule
     *            the rule it is written in
     * @param where
     *            where it is written, for the message
     * @return the refusals, in member order
     */
    static List<String> spliceRefusals(Expr.Call c, Rule rule, String where)
    {
        List<String> out = List.of();
        for (net.cumba.corej.core.exec.GroupSplice.Slot slot : net.cumba.corej.core.exec.GroupSplice
                .spliceSlots(c))
        {
            for (Expr member : slot.operands())
            {
                if (net.cumba.corej.core.exec.GroupSplice.spliced(member) != null
                        && readsRowCursor(member, rule))
                {
                    if (out.isEmpty())
                    {
                        out = new ArrayList<>();
                    }
                    out.add(spliceRefusal(c.name(), slot.parameter(), where, member));
                }
            }
        }
        return out;
    }


    /**
     * Every level refusal of one call: each operand of a set function, each operand at a
     * {@link #PER_CALL_PARAMETERS per-call parameter} (positional operands mapped to their
     * parameter through the registry descriptor, keyword operands by name), and each
     * {@code $}-reference inside the {@code filter=} of a {@link #FILTER_FUNCTIONS filtering
     * function}, that varies per row or per variable. Empty for any other call, and for a call
     * whose every such operand is dataset-level. Stage A files each as a finding; the loader throws
     * the first.
     *
     * @param c
     *            the call
     * @param rule
     *            the rule it is written in
     * @param where
     *            where it is written, for the message
     * @return the refusals, in operand order
     */
    public static List<String> refusals(Expr.Call c, Rule rule, String where)
    {
        List<String> out = new ArrayList<>();
        if (LIST_SET_FUNCTIONS.contains(c.name()))
        {
            List<Expr> operands = new ArrayList<>(c.args());
            operands.addAll(c.kwargs().values());
            for (Expr operand : operands)
            {
                if (needsCursor(operand, rule))
                {
                    out.add(refusal(c.name(), where, operand));
                }
            }
            return out;
        }
        if (FILTER_FUNCTIONS.contains(c.name()))
        {
            Expr filter = c.kwargs()
                    .get(net.cumba.corej.core.exec.GroupedAggregate.FILTER_PARAMETER);
            if (filter != null)
            {
                filterReferences(filter, c.name(), rule, where, out);
            }
            return out;
        }
        Set<String> perCall = PER_CALL_PARAMETERS.get(c.name());
        if (perCall == null)
        {
            return out;
        }
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(c.name());
        List<Parameter> params = descriptor == null ? List.of() : descriptor.parameters();
        for (int i = 0; i < c.args().size() && i < params.size(); i++)
        {
            judge(params.get(i).name(), c.args().get(i), perCall, c.name(), rule, where, out);
        }
        for (Map.Entry<String, Expr> kw : c.kwargs().entrySet())
        {
            judge(kw.getKey(), kw.getValue(), perCall, c.name(), rule, where, out);
        }
        return out;
    }


    private static void judge(String parameter, Expr operand, Set<String> perCall, String function,
            Rule rule, String where, List<String> out)
    {
        if (perCall.contains(parameter) && needsCursor(operand, rule))
        {
            out.add(parameterRefusal(function, parameter, where, operand));
        }
    }


    /**
     * The {@code $}-references inside a {@code filter=} expression, each judged on its own
     * ({@link #needsCursor}): the filter's columns are the target table's and vary per row by
     * nature, so the walk never asks the whole expression. A nested call is walked through, except
     * for a nested filtering call's own {@code filter=}, which is judged when {@link #refusals}
     * reaches that call.
     */
    private static void filterReferences(Expr e, String function, Rule rule, String where,
            List<String> out)
    {
        switch (e)
        {
        case Expr.Ref ref ->
        {
            if (ref.kind() == OperandKind.OPERATION_REF && needsCursor(ref, rule))
            {
                out.add(filterRefusal(function, where, ref));
            }
        }
        case Expr.Call call ->
        {
            call.args().forEach(a -> filterReferences(a, function, rule, where, out));
            boolean nestedFilter = FILTER_FUNCTIONS.contains(call.name());
            call.kwargs().forEach((name, a) ->
            {
                // a nested filtering call's own filter= is that call's judgement (one finding,
                // attributed to the function that reads it), never the outer's as well
                if (!nestedFilter || !net.cumba.corej.core.exec.GroupedAggregate.FILTER_PARAMETER
                        .equals(name))
                {
                    filterReferences(a, function, rule, where, out);
                }
            });
        }
        case Expr.Binary binary ->
        {
            filterReferences(binary.left(), function, rule, where, out);
            filterReferences(binary.right(), function, rule, where, out);
        }
        case Expr.And and -> and.parts()
                .forEach(p -> filterReferences(p, function, rule, where, out));
        case Expr.Or or -> or.parts().forEach(p -> filterReferences(p, function, rule, where, out));
        case Expr.Not not -> filterReferences(not.inner(), function, rule, where, out);
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        filterReferences(inner, function, rule, where, out);
                    }
                }
            }
        }
        }
    }


    /** {@code "a set union"} / {@code "a set difference"}, for the message. */
    public static String setOperation(String function)
    {
        return net.cumba.corej.core.exec.Union.NAME.equals(function) ? "a set union"
                : "a set difference";
    }


    /**
     * The loader's twin of stage A's walk: the first per-row / per-variable operand of a set
     * function or at a per-call parameter anywhere in the expression ({@link #refusals}), or
     * {@code null} when every such operand is dataset-level.
     *
     * @param expr
     *            the expression to walk
     * @param rule
     *            the rule it belongs to
     * @param where
     *            where it is written, for the message
     * @return the refusal, or {@code null}
     */
    public static @Nullable String firstRefusal(Expr expr, Rule rule, String where)
    {
        switch (expr)
        {
        case Expr.And a ->
        {
            return firstRefusal(a.parts(), rule, where);
        }
        case Expr.Or o ->
        {
            return firstRefusal(o.parts(), rule, where);
        }
        case Expr.Not n ->
        {
            return firstRefusal(n.inner(), rule, where);
        }
        case Expr.Binary b ->
        {
            return firstRefusal(List.of(b.left(), b.right()), rule, where);
        }
        case Expr.Call c ->
        {
            List<String> own = new ArrayList<>(refusals(c, rule, where));
            own.addAll(spliceRefusals(c, rule, where));
            if (!own.isEmpty())
            {
                return own.get(0);
            }
            List<Expr> operands = new ArrayList<>(c.args());
            operands.addAll(c.kwargs().values());
            return firstRefusal(operands, rule, where);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr e)
                    {
                        String refusal = firstRefusal(e, rule, where);
                        if (refusal != null)
                        {
                            return refusal;
                        }
                    }
                }
            }
            return null;
        }
        case Expr.Ref _ ->
        {
            return null;
        }
        }
    }


    private static @Nullable String firstRefusal(List<Expr> parts, Rule rule, String where)
    {
        for (Expr part : parts)
        {
            String refusal = firstRefusal(part, rule, where);
            if (refusal != null)
            {
                return refusal;
            }
        }
        return null;
    }
}
