package net.cumba.cdisc.core.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import org.jspecify.annotations.Nullable;

/**
 * Lowers a boolean-typed {@link Expr} into the existing
 * {@link net.cumba.cdisc.core.model.CheckCondition} AST so the current engine evaluates it
 * unchanged (v1 evaluation path). This is the operator-mapping core: infix {@link Expr.Binary} and
 * predicate {@link Expr.Call} nodes become {@link CheckConditionLeaf}s carrying the corresponding
 * raw {@code operator} string.
 *
 * <p>
 * Constructs the v1 mapping does not cover (group/order operators, one-sided {@code lowcase},
 * arithmetic, deep composition) are rejected with an {@link ExpressionException} that names them as
 * native-evaluator constructs — never silently mis-lowered. The converter leaves such leaves in
 * old-style form (partial by design).
 * </p>
 */
public final class ExprLowering
{

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Unary operand wrappers that select an operator family (or transform). */
    private static final Set<String> UNARY_WRAPPERS = Set.of("date", "num", "len", "date_part",
            "time_part", "lowcase", "upcase");

    private ExprLowering()
    {
    }


    /**
     * Lowers a boolean expression tree to a {@link CheckCondition}.
     *
     * @param expr
     *            the (boolean-typed) expression
     * @return the equivalent check-condition tree
     * @throws ExpressionException
     *             if the expression cannot be represented in the operator-leaf AST
     */
    public static CheckCondition toCheckCondition(Expr expr)
    {
        return lowerBool(expr);
    }


    /**
     * Lowers a boolean expression tree to a {@link CheckCondition} in <b>conversion mode</b>:
     * identical to {@link #toCheckCondition(Expr)} except that the expression is first run through
     * {@link #restoreLegacyForms(Expr)}, which undoes the two raise-side rewrites that have no
     * legacy operator surface of their own — {@code CheckToExpr}'s regex&rarr;predicate
     * optimisation table and its promotion of metadata operands to per-record accessor calls.
     *
     * <p>
     * ⚠⚠ <b>This is deliberately NOT the load path.</b> {@code CheckConditionDeserializer} calls
     * the strict {@link #toCheckCondition(Expr)} and keeps the rule as a
     * {@link net.cumba.cdisc.core.model.CheckConditionExpression} (native evaluation) when it
     * throws. Widening the strict lowering would therefore <em>move shipped rules off the native
     * evaluator onto the v1 operator path</em> and change what {@code OutputVariableDeriver} /
     * {@code WildcardExpander} / {@code RuleRunner} see — a runtime change, not a conversion one.
     * Conversion mode exists so the offline {@code rules-src}&nbsp;&harr;&nbsp;{@code rules-legacy}
     * direction can be made (nearly) total without touching a single evaluation.
     * </p>
     *
     * @param expr
     *            the (boolean-typed) expression
     * @return the equivalent check-condition tree
     * @throws ExpressionException
     *             if the expression cannot be represented in the operator-leaf AST even after the
     *             legacy-form restoration
     */
    public static CheckCondition toCheckConditionForConversion(Expr expr)
    {
        return lowerBool(restoreLegacyForms(expr));
    }

    // ---- conversion mode: restoring the legacy-representable form ---------

    /**
     * The {@code CheckToExpr.optimiseMatches} / {@code optimiseNotMatches} recognition table, read
     * backwards: predicate function name &rarr; the {@code matches_regex} pattern it was raised
     * from.
     *
     * <p>
     * ⚠ The forward table is <b>not injective</b> for {@code is_numeric} (it also accepts
     * {@code ^-?(\d+(\.\d+)?$)|(\.\d+$)}) or for {@code is_integer} (two {@code \s*} spellings), so
     * the reverse necessarily canonicalises. Measured over {@code rules-src} on 2026-08-10: the
     * alternate {@code is_numeric} spelling occurs <b>0</b> times, so the canonicalisation is exact
     * on today's corpus. {@code is_integer} and {@code invalid_duration} are deliberately absent
     * from this table — they already have a v1 lowering ({@code is_not_integer} /
     * {@code invalid_duration}), so restoring the regex here would <em>change</em> a leaf that
     * lowers today rather than recover one that does not.
     * </p>
     */
    private static final Map<String, String> PREDICATE_TO_REGEX = Map.of("is_numeric",
            "^-?(0|[1-9]\\d*)(\\.\\d+)?$", "has_alpha", ".*[a-zA-Z].*", "has_digit", ".*[0-9].*",
            "is_valid_name", "^[A-Z_][A-Z0-9_]{0,7}$", "is_valid_testcd",
            "^[a-zA-Z_][a-zA-Z0-9_]{0,7}$");

    /** The first-letter range test {@code CheckToExpr} raises {@code ^[A-Z]} to (negated sense). */
    private static final String FIRST_LETTER_REGEX = "^[A-Z]";

    /**
     * Rewrites the raise-side-only constructs back to shapes the operator-leaf AST can express, so
     * conversion-mode lowering succeeds where the strict lowering has nothing to map to:
     *
     * <ul>
     * <li><b>R1</b> — the regex&rarr;predicate optimisation ({@code is_numeric(X)},
     * {@code has_alpha}/{@code has_digit}, {@code is_valid_name}/{@code is_valid_testcd},
     * {@code not between(char(X), char("A"), char("Z"))}) becomes the {@code =~}/{@code !~} form
     * carrying the pattern it was raised from.</li>
     * <li><b>R2</b> — a per-record accessor call that {@link MetadataOperandMapping} can name but
     * {@link MetadataOperandMapping#reverseToOperand} cannot (the {@code vlm_*} family,
     * {@code max_value_length}, the paired code/decode matchers, and the {@code varname()} /
     * {@code value()} / {@code record_count()} cursors) is demoted to its bare metadata
     * <em>operand</em> reference — which is exactly what the rule authored before
     * {@code CheckToExpr.migratingRef} promoted it.</li>
     * </ul>
     *
     * <p>
     * ⚠ The {@code ends_with(varname(), "SUF")} broadcast rewrite is deliberately <b>not</b>
     * reversed here. {@code CheckToExpr.pureSuffix} accepts three fills ({@code ^.+SUF$},
     * {@code ^.*SUF$}, bare {@code SUF$}) that all collapse to the same call, and all three are
     * populated in {@code rules-src} (58 / 9 / 5 leaves, measured 2026-08-10). Any reverse would
     * restore one and corrupt the other two — see {@code plans/PLAN-guard-emission-relocation.md}
     * §"Results — P1–P4", P2.
     * </p>
     *
     * @param e
     *            the expression to rewrite
     * @return the rewritten expression (structurally new; the input is not mutated)
     */
    static Expr restoreLegacyForms(Expr e)
    {
        return switch (e)
        {
        case Expr.Not n ->
        {
            Expr restored = regexForm(n.inner(), true);
            yield restored != null ? restored : new Expr.Not(restoreLegacyForms(n.inner()));
        }
        case Expr.Call c ->
        {
            Expr restored = regexForm(c, false);
            if (restored != null)
            {
                yield restored;
            }
            String operand = demotedOperand(c);
            yield operand != null ? new Expr.Ref(operand, OperandKind.BUILTIN)
                    : new Expr.Call(c.name(), restoreAll(c.args()), restoreKwargs(c.kwargs()));
        }
        case Expr.Binary b -> new Expr.Binary(b.op(), restoreLegacyForms(b.left()),
                restoreLegacyForms(b.right()));
        case Expr.And a -> new Expr.And(restoreAll(a.parts()));
        case Expr.Or o -> new Expr.Or(restoreAll(o.parts()));
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                yield new Expr.Lit(Expr.LitKind.LIST, restoreAll(items));
            }
            yield lit;
        }
        case Expr.Ref r -> r;
        };
    }


    /**
     * The {@code X =~ /pattern/} ({@code negated == false}) or {@code X !~ /pattern/} form for a
     * predicate call the regex optimiser produced, or {@code null} when {@code e} is not such a
     * call.
     */
    private static @Nullable Expr regexForm(Expr e, boolean negated)
    {
        if (!(e instanceof Expr.Call c) || !c.kwargs().isEmpty())
        {
            return null;
        }
        Expr.BinOp op = negated ? Expr.BinOp.NMATCH : Expr.BinOp.MATCH;
        String pattern = PREDICATE_TO_REGEX.get(c.name());
        if (pattern != null && c.args().size() == 1)
        {
            return new Expr.Binary(op, restoreLegacyForms(c.args().get(0)),
                    new Expr.Lit(Expr.LitKind.REGEX, pattern));
        }
        Expr firstLetterOperand = firstLetterRangeOperand(c);
        if (firstLetterOperand != null)
        {
            return new Expr.Binary(op, restoreLegacyForms(firstLetterOperand),
                    new Expr.Lit(Expr.LitKind.REGEX, FIRST_LETTER_REGEX));
        }
        return null;
    }


    /**
     * The operand {@code X} of the first-letter range test
     * {@code between(char(X), char("A"), char("Z"))}, or {@code null} for any other call. Both
     * bounds must be the exact {@code char("A")} / {@code char("Z")} literals the optimiser emits —
     * a hand-written {@code between} over other bounds has no regex equivalent and must stay
     * native-only.
     */
    private static @Nullable Expr firstLetterRangeOperand(Expr.Call c)
    {
        if (!"between".equals(c.name()) || c.args().size() != 3)
        {
            return null;
        }
        Expr subject = charArgument(c.args().get(0));
        if (subject == null || !isCharLiteral(c.args().get(1), "A")
                || !isCharLiteral(c.args().get(2), "Z"))
        {
            return null;
        }
        return subject;
    }


    /** The single argument of a {@code char(X)} call, or {@code null}. */
    private static Expr.@Nullable Call charCall(Expr e)
    {
        return e instanceof Expr.Call c && "char".equals(c.name()) && c.args().size() == 1
                && c.kwargs().isEmpty() ? c : null;
    }


    private static @Nullable Expr charArgument(Expr e)
    {
        Expr.Call c = charCall(e);
        return c == null ? null : c.args().get(0);
    }


    /** Whether {@code e} is {@code char("<letter>")} for the given single-character literal. */
    private static boolean isCharLiteral(Expr e, String letter)
    {
        Expr arg = charArgument(e);
        return arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                && letter.equals(lit.value());
    }


    /**
     * The bare metadata-operand name a per-record accessor call demotes to in conversion mode, or
     * {@code null} when the call must be kept. Only calls the <em>narrow</em>
     * {@link MetadataOperandMapping#reverseToOperand} cannot name are demoted — the ones it can are
     * already handled by {@link #nameOf} / {@link #referenceName}, and rewriting them here would
     * change leaves that lower correctly today.
     */
    private static @Nullable String demotedOperand(Expr.Call c)
    {
        if (MetadataOperandMapping.reverseToOperand(c) != null)
        {
            return null;
        }
        return MetadataOperandMapping.reverseAnyAccessor(c);
    }


    private static List<Expr> restoreAll(List<Expr> items)
    {
        return items.stream().map(ExprLowering::restoreLegacyForms).toList();
    }


    private static Map<String, Expr> restoreKwargs(Map<String, Expr> kwargs)
    {
        Map<String, Expr> out = new java.util.LinkedHashMap<>();
        kwargs.forEach((k, v) -> out.put(k, restoreLegacyForms(v)));
        return out;
    }


    private static CheckCondition lowerBool(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> new CheckConditionAll(
                a.parts().stream().map(ExprLowering::lowerBool).toList());
        case Expr.Or o -> new CheckConditionAny(
                o.parts().stream().map(ExprLowering::lowerBool).toList());
        case Expr.Not n -> lowerNot(n.inner());
        case Expr.Binary b -> lowerBinary(b);
        case Expr.Call c -> lowerPredicate(c);
        case Expr.Ref r -> throw unsupported(
                "bare reference '" + r.name() + "' is not a boolean condition");
        case Expr.Lit _ -> throw unsupported("a literal is not a boolean condition");
        };
    }


    /**
     * Lowers {@code not <inner>}. A negated affix-regex call ({@code not prefix_matches(...)} /
     * {@code not suffix_matches(...)}) maps directly to the {@code not_*_matches_regex}
     * operator-leaf (the negative engine operator), not a structural {@link CheckConditionNot};
     * everything else is a structural negation.
     */
    private static CheckCondition lowerNot(Expr inner)
    {
        if (inner instanceof Expr.Call c)
        {
            if ("prefix_matches".equals(c.name()) || "suffix_matches".equals(c.name()))
            {
                return affixRegexLeaf(c, true);
            }
            if ("equalsIgnoreCase".equals(c.name()))
            {
                return twoArg(c, "not_equal_to_case_insensitive");
            }
            if ("is_sorted_by".equals(c.name()))
            {
                return sortedByLeaf(c);
            }
            // Q1 negation pairs: not <positive>(…) -> the negative operator-leaf directly (never a
            // structural CheckConditionNot, which would flip the unflagged rows into violations).
            if ("present_on_multiple_rows_within".equals(c.name()))
            {
                return functionOperatorLeaf(c, "not_present_on_multiple_rows_within");
            }
            if ("has_next_corresponding_record".equals(c.name()))
            {
                return functionOperatorLeaf(c, "does_not_have_next_corresponding_record");
            }
            // change #1: not <positive>(…) -> the negative operator-leaf directly (mirrors the
            // engine's double-invert and keeps the Check -> Expr -> Check round-trip).
            String negativeOperator = POSITIVE_TO_NEGATIVE.get(c.name());
            if (negativeOperator != null)
            {
                return functionOperatorLeaf(c, negativeOperator);
            }
            // change #1 (Task EF): not <positive>(…) for the positive-already-exists predicates ->
            // the original negative operator-leaf (a structural CheckConditionNot would not
            // reproduce the negative operator the round-trip asserts).
            if ("empty".equals(c.name()))
            {
                return unaryNegationLeaf(c, "non_empty");
            }
            if ("is_integer".equals(c.name()))
            {
                return unaryNegationLeaf(c, "is_not_integer");
            }
            // Fix #157 — the same move for the date-portion pair. Note that the neighbouring
            // is_complete_date is deliberately NOT listed: is_incomplete_date is not its negation
            // (an invalid date is neither), whereas is_not_complete_date_part is exactly the
            // complement of is_complete_date_part.
            if ("is_complete_date_part".equals(c.name()))
            {
                return unaryNegationLeaf(c, "is_not_complete_date_part");
            }
            if ("contains".equals(c.name()))
            {
                return substringNegationLeaf(c, "does_not_contain");
            }
        }
        return new CheckConditionNot(lowerBool(inner));
    }


    /**
     * Lowers {@code not <unaryPositive>(X)} back to the {@code X}-named negative operator-leaf
     * (change #1, Task EF): {@code not empty(X)} → {@code non_empty}, {@code not is_integer(X)} →
     * {@code is_not_integer}.
     */
    private static CheckCondition unaryNegationLeaf(Expr.Call c, String negativeOperator)
    {
        requireArgs(c.name(), c.args().size(), 1);
        return CheckConditionLeaf.builder().name(nameOf(c.args().get(0))).operator(negativeOperator)
                .build();
    }


    /**
     * Lowers {@code not <substringPositive>(X, "v")} back to the negative operator-leaf (change #1,
     * Task EF): {@code not contains(X, "v")} → {@code does_not_contain}.
     */
    private static CheckCondition substringNegationLeaf(Expr.Call c, String negativeOperator)
    {
        requireArgs(c.name(), c.args().size(), 2);
        return leaf(nameOf(c.args().get(0)), negativeOperator, c.args().get(1));
    }


    private static CheckCondition lowerBinary(Expr.Binary b)
    {
        if (b.op() == Expr.BinOp.MATCH || b.op() == Expr.BinOp.NMATCH)
        {
            Expr.Call affixOperand = affixCall(b.left());
            if (affixOperand != null)
            {
                return affixRegexMatchLeaf(b, affixOperand);
            }
            return regexLeaf(b);
        }
        Expr.Call affix = affixCall(b.left());
        if (affix != null)
        {
            return affixCompareLeaf(b, affix);
        }
        if (isStr(b.left()) && isStr(b.right()))
        {
            return typeInsensitiveLeaf(b);
        }
        if (b.op() == Expr.BinOp.IN || b.op() == Expr.BinOp.NOT_IN)
        {
            if (isUpper(b.left()))
            {
                return caseInsensitiveMembershipLeaf(b);
            }
            String op = b.op() == Expr.BinOp.IN ? "is_contained_by" : "is_not_contained_by";
            return leaf(nameOf(b.left()), op, b.right());
        }
        if (b.right() instanceof Expr.Binary rb && isArith(rb.op()))
        {
            return arithmeticLeaf(b, rb);
        }

        String lw = wrapperOf(b.left());
        String rw = wrapperOf(b.right());
        Expr li = unwrap(b.left());
        Expr ri = unwrap(b.right());

        if ("len".equals(lw) || "len".equals(rw))
        {
            return lengthLeaf(b, lw, li, ri, rw);
        }
        if (isCaseWrapper(lw) || isCaseWrapper(rw))
        {
            // Collapse to equal_to_case_insensitive only when the comparison is genuinely
            // symmetric: the column (case-wrapped) on the left, and the right side either
            // case-wrapped or a literal. A one-sided lowcase against a bare reference would
            // lowercase only one operand and cannot be expressed by the symmetric operator.
            if (!isCaseWrapper(lw))
            {
                throw unsupported("a case-insensitive comparison needs lowcase/upcase on the left "
                        + "(column) operand");
            }
            boolean rightOk = isCaseWrapper(rw) || b.right() instanceof Expr.Lit;
            if (!rightOk)
            {
                throw unsupported("one-sided lowcase/upcase against a bare reference is ambiguous; "
                        + "wrap both sides or use equalsIgnoreCase(...)");
            }
            return caseInsensitiveLeaf(b.op(), li, ri);
        }
        String family = comparisonFamily(lw, rw);
        return leaf(nameOf(li), comparisonOperator(family, b.op(), li), ri);
    }


    private static CheckCondition regexLeaf(Expr.Binary b)
    {
        Expr right = b.right();
        if (!(right instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.REGEX)
        {
            throw unsupported("the right-hand side of =~ / !~ must be a /regex/ literal");
        }
        String op = b.op() == Expr.BinOp.MATCH ? "matches_regex" : "not_matches_regex";
        return CheckConditionLeaf.builder().name(nameOf(b.left())).operator(op)
                .value(NODES.textNode((String) lit.value())).valueIsLiteral(Boolean.TRUE).build();
    }


    private static CheckCondition lengthLeaf(Expr.Binary b, @Nullable String lw, Expr li, Expr ri,
            @Nullable String rw)
    {
        // Convention: len(COLUMN) on the left, numeric literal on the right.
        if (!"len".equals(lw) || "len".equals(rw))
        {
            throw unsupported("len(...) must be the left operand of a length comparison");
        }
        String op = switch (b.op())
        {
        case GT -> "longer_than";
        case GE -> "longer_than_or_equal_to";
        case LT -> "shorter_than";
        case LE -> "shorter_than_or_equal_to";
        case EQ -> "has_equal_length";
        case NEQ -> "has_not_equal_length";
        default -> throw unsupported(
                "len(...) supports only '>' (longer_than), '>=' (longer_than_or_equal_to), "
                        + "'<' (shorter_than), '<=' (shorter_than_or_equal_to), "
                        + "'==' (has_equal_length) and '!=' (has_not_equal_length) in v1");
        };
        return CheckConditionLeaf.builder().name(nameOf(li)).operator(op).value(literalNode(ri))
                .valueIsLiteral(Boolean.TRUE).build();
    }


    private static CheckCondition caseInsensitiveLeaf(Expr.BinOp op, Expr li, Expr ri)
    {
        String operator = switch (op)
        {
        case EQ -> "equal_to_case_insensitive";
        case NEQ -> "not_equal_to_case_insensitive";
        default -> throw unsupported("lowcase/upcase comparisons support only == and !=");
        };
        return leaf(nameOf(li), operator, ri);
    }

    /**
     * Non-row-level operators raised to like-named function calls by {@code CheckToExpr} (Phase
     * 4a).
     */
    private static final Set<String> FUNCTION_OPERATORS = Set.of("has_multiple_values_for",
            "is_not_unique_relationship", "is_not_unique_set", "is_unique_set",
            "is_inconsistent_across_dataset", "shares_no_elements_with", "not_contains_all",
            "has_same_values", "is_not_ordered_subset_of", "inconsistent_enumerated_columns",
            "has_not_equal_length", "has_equal_length", "is_not_unique_value",
            "present_on_multiple_rows_within", "not_present_on_multiple_rows_within",
            "empty_within_except_last_row", "does_not_have_next_corresponding_record",
            "does_not_equal_string_part");

    /**
     * The uniqueness pair whose canonical authored form is a single list operand
     * ({@code is_unique_set([A, B, …])}, owner requirement #1, 2026-08-23) — the only function
     * operators whose leaf {@code name}/{@code value} split is a wire-format re-encoding of one
     * undifferentiated key tuple (D-2) rather than two roles.
     */
    static final Set<String> UNIQUE_SET_OPERATORS = Set.of("is_unique_set", "is_not_unique_set");

    /**
     * The four positive group functions (change #1) and the negative operator each complements. The
     * converter emits {@code not <positive>(…)} for the negative operator; lowering reverses it
     * back to the negative operator-leaf (so the {@code Check → Expr → Check} round-trip holds),
     * and a bare {@code <positive>(…)} lowers to {@code Not(<negative> leaf)}.
     */
    private static final Map<String, String> POSITIVE_TO_NEGATIVE = Map.of("is_unique_relationship",
            "is_not_unique_relationship", "contains_all", "not_contains_all",
            "shares_elements_with", "shares_no_elements_with", "is_ordered_subset_of",
            "is_not_ordered_subset_of", "is_unique_value", "is_not_unique_value", "is_unique_set",
            "is_not_unique_set");

    private static CheckCondition lowerPredicate(Expr.Call c)
    {
        String n = c.name();
        int argc = c.args().size();
        if (FUNCTION_OPERATORS.contains(n))
        {
            return functionOperatorLeaf(c);
        }
        // change #1: a bare positive group function lowers to Not(<negative> operator-leaf).
        String negativeOperator = POSITIVE_TO_NEGATIVE.get(n);
        if (negativeOperator != null)
        {
            return new CheckConditionNot(functionOperatorLeaf(c, negativeOperator));
        }
        // invalid_duration carrying the negative modifier: invalid_duration(X, negative=<bool>).
        if ("invalid_duration".equals(n) && c.kwargs().containsKey("negative"))
        {
            requireArgs(n, argc, 1);
            Expr neg = c.kwargs().get("negative");
            if (!(neg instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("invalid_duration negative= must be a boolean literal");
            }
            return CheckConditionLeaf.builder().name(nameOf(c.args().get(0))).operator(n)
                    .negative((Boolean) lit.value()).build();
        }
        // 1-argument boolean predicates.
        if (UNARY_PREDICATES.contains(n))
        {
            requireArgs(n, argc, 1);
            String name = EXISTS_PREDICATES.contains(n) ? existsArgName(n, c.args().get(0))
                    : nameOf(c.args().get(0));
            return CheckConditionLeaf.builder().name(name).operator(n).build();
        }
        // 2-argument substring predicates: f(COLUMN, "literal").
        if (SUBSTRING_PREDICATES.contains(n))
        {
            requireArgs(n, argc, 2);
            return leaf(nameOf(c.args().get(0)), n, c.args().get(1));
        }
        return switch (n)
        {
        case "equalsIgnoreCase" -> twoArg(c, "equal_to_case_insensitive");
        case "prefix_matches", "suffix_matches" -> affixRegexLeaf(c, false);
        default -> throw unsupported("function '" + n + "' has no v1 lowering");
        };
    }

    private static final Set<String> UNARY_PREDICATES = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists", "var_is_null", "empty", "non_empty", "is_complete_date",
            "is_incomplete_date", "invalid_date", "invalid_duration", "is_integer",
            "is_not_integer", "is_complete_date_part", "is_not_complete_date_part");

    /**
     * The exists family: the only predicates whose single argument may also be a string literal
     * carrying the name (equivalent to the bareword/backtick reference form).
     */
    private static final Set<String> EXISTS_PREDICATES = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists");

    /**
     * Resolves the exists-family argument to the leaf name: a string literal lowers exactly like
     * the bareword reference of the same name; anything else goes through the regular
     * {@link #nameOf} reference resolution. The {@code ds_exists}/{@code ds_not_exists} argument is
     * additionally restricted to a plain dataset name (no dotted/filter form, no {@code ${...}}
     * substitution, no {@code --} prefix), mirroring the native compiler so the lowering fails as
     * loudly as a native compile.
     */
    private static String existsArgName(String predicate, Expr arg)
    {
        if (net.cumba.cdisc.core.expr.eval.ExprCompiler.isCurrentVariableName(arg))
        {
            // §3.7 of PLAN-leaf-scope-domain-inference.md: var_exists(varname()) — or its bareword
            // twin var_exists(variable_name), the same cursor read to the compiler — is the
            // variable-universe discriminator, a cursor read with no operator-leaf surface.
            throw unsupported("function '" + predicate + "' over the varname() cursor has no v1"
                    + " lowering (needs the native evaluator)");
        }
        String name = arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                ? (String) lit.value()
                : nameOf(arg);
        if (("ds_exists".equals(predicate) || "ds_not_exists".equals(predicate))
                && (name.indexOf('.') >= 0 || name.indexOf('=') >= 0 || name.contains("${")
                        || name.contains("--")))
        {
            throw unsupported(predicate + " expects a plain dataset name");
        }
        return name;
    }

    private static final Set<String> SUBSTRING_PREDICATES = Set.of("contains", "does_not_contain",
            "starts_with", "ends_with");

    private static CheckCondition twoArg(Expr.Call c, String operator)
    {
        requireArgs(c.name(), c.args().size(), 2);
        return leaf(nameOf(c.args().get(0)), operator, c.args().get(1));
    }


    /**
     * Lowers an affix-regex call {@code prefix_matches(X, /re/[, n])} / {@code suffix_matches(...)}
     * to the {@code (not_)prefix_matches_regex}/{@code suffix_matches_regex} operator-leaf,
     * carrying the optional 3rd-argument length as the {@code prefix}/{@code suffix} field.
     */
    private static CheckCondition affixRegexLeaf(Expr.Call c, boolean negated)
    {
        String kind = c.name().startsWith("prefix") ? "prefix" : "suffix";
        int argc = c.args().size();
        if (argc != 2 && argc != 3)
        {
            throw unsupported(c.name() + "(...) expects 2 or 3 arguments but got " + argc);
        }
        Expr pat = c.args().get(1);
        if (!(pat instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.REGEX)
        {
            throw unsupported(c.name() + "(...) requires a /regex/ literal as its second argument");
        }
        String operator = (negated ? "not_" : "") + kind + "_matches_regex";
        CheckConditionLeaf.CheckConditionLeafBuilder b = CheckConditionLeaf.builder()
                .name(nameOf(c.args().get(0))).operator(operator)
                .value(NODES.textNode((String) lit.value())).valueIsLiteral(Boolean.TRUE);
        if (argc == 3)
        {
            int len = asInt(c.args().get(2));
            if ("prefix".equals(kind))
            {
                b.prefix(len);
            }
            else
            {
                b.suffix(len);
            }
        }
        return b.build();
    }


    /**
     * Lowers the anchored affix-regex match (Agreed change #2) — {@code prefix(X, n) =~ /re/} /
     * {@code suffix(X, n) =~ /re/} (and the {@code !~} negated forms) — back to the
     * {@code (not_)(prefix|suffix)_matches_regex} operator-leaf, carrying the affix length as the
     * {@code prefix}/{@code suffix} field and the pattern as the regex value.
     *
     * <p>
     * The converter anchors the pattern ({@code ^…$}) on the way out because the affix operator is
     * anchored ({@code matches()}) while {@code =~} is not ({@code find()}); the anchored pattern
     * is stored back verbatim here. The original (un-anchored) pattern is not always recoverable
     * byte-for-byte, but {@code CheckToExpr.anchored} is idempotent on an already-anchored pattern,
     * so re-raising this leaf yields the identical anchored {@code =~} expression — the
     * {@code Check → Expr → Check} round-trip holds up to the Expr-IR equivalence the corpus guard
     * uses. A hand-authored {@code prefix_matches(X, /re/, n)} call still lowers via
     * {@link #affixRegexLeaf}, so that surface is unaffected.
     * </p>
     */
    private static CheckCondition affixRegexMatchLeaf(Expr.Binary b, Expr.Call affix)
    {
        String kind = affix.name();
        Expr pat = b.right();
        if (!(pat instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.REGEX)
        {
            throw unsupported("the right-hand side of =~ / !~ must be a /regex/ literal");
        }
        boolean negated = b.op() == Expr.BinOp.NMATCH;
        String operator = (negated ? "not_" : "") + kind + "_matches_regex";
        int len = asInt(affix.args().get(1));
        CheckConditionLeaf.CheckConditionLeafBuilder bld = CheckConditionLeaf.builder()
                .name(((Expr.Ref) affix.args().get(0)).name()).operator(operator)
                .value(NODES.textNode((String) lit.value())).valueIsLiteral(Boolean.TRUE);
        if ("prefix".equals(kind))
        {
            bld.prefix(len);
        }
        else
        {
            bld.suffix(len);
        }
        return bld.build();
    }


    /** A {@code prefix(X, n)}/{@code suffix(X, n)} affix value-function call, or {@code null}. */
    private static Expr.@Nullable Call affixCall(Expr e)
    {
        if (e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 2
                && ("prefix".equals(c.name()) || "suffix".equals(c.name()))
                && c.args().get(0) instanceof Expr.Ref && c.args().get(1) instanceof Expr.Lit lit
                && lit.kind() == Expr.LitKind.NUMBER)
        {
            return c;
        }
        return null;
    }


    private static CheckCondition affixCompareLeaf(Expr.Binary b, Expr.Call affix)
    {
        String kind = affix.name();
        String name = ((Expr.Ref) affix.args().get(0)).name();
        int len = asInt(affix.args().get(1));
        String operator = switch (b.op())
        {
        case EQ -> kind + "_equal_to";
        case NEQ -> kind + "_not_equal_to";
        case IN -> kind + "_is_contained_by";
        case NOT_IN -> kind + "_is_not_contained_by";
        default -> throw unsupported(
                kind + "(...) supports only ==, !=, in and 'not in' comparisons");
        };
        CheckConditionLeaf.CheckConditionLeafBuilder bld = CheckConditionLeaf.builder().name(name)
                .operator(operator);
        if ("prefix".equals(kind))
        {
            bld.prefix(len);
        }
        else
        {
            bld.suffix(len);
        }
        LeafValue lv = leafValue(b.right());
        bld.value(lv.node());
        if (lv.reference() != null)
        {
            bld.valueIsReference(lv.reference());
        }
        if (lv.literal() != null)
        {
            bld.valueIsLiteral(lv.literal());
        }
        return bld.build();
    }


    /** A {@code str(X)} string type-tag wrapper (one argument, no kwargs). */
    private static boolean isStr(Expr e)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && "str".equals(c.name());
    }


    private static CheckCondition typeInsensitiveLeaf(Expr.Binary b)
    {
        String operator = switch (b.op())
        {
        case EQ -> "equal_to";
        case NEQ -> "not_equal_to";
        default -> throw unsupported("str(...) comparison supports only == and !=");
        };
        Expr lhs = ((Expr.Call) b.left()).args().get(0);
        Expr rhs = ((Expr.Call) b.right()).args().get(0);
        CheckConditionLeaf.CheckConditionLeafBuilder bld = CheckConditionLeaf.builder()
                .name(nameOf(lhs)).operator(operator).typeInsensitive(Boolean.TRUE);
        LeafValue lv = leafValue(rhs);
        bld.value(lv.node());
        if (lv.reference() != null)
        {
            bld.valueIsReference(lv.reference());
        }
        if (lv.literal() != null)
        {
            bld.valueIsLiteral(lv.literal());
        }
        return bld.build();
    }


    private static int asInt(Expr e)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER)
        {
            return (int) Math.rint((Double) lit.value());
        }
        throw unsupported("expected a numeric length but found " + describe(e));
    }


    private static boolean isArith(Expr.BinOp op)
    {
        return op == Expr.BinOp.ADD || op == Expr.BinOp.SUB || op == Expr.BinOp.MUL
                || op == Expr.BinOp.DIV;
    }


    /**
     * Lowers the three recognised arithmetic-comparison shapes back to their operator-leaf (value
     * {@code [A, B]}): {@code X != A / B} → {@code not_equal_to_divide}, {@code X != A - B} →
     * {@code not_equal_to_subtract}, {@code X != ((A - B) / B) * 100} →
     * {@code not_equal_to_pctchg}. General arithmetic that matches none of these is native-only and
     * is rejected here.
     */
    private static CheckCondition arithmeticLeaf(Expr.Binary b, Expr.Binary rhs)
    {
        if (b.op() != Expr.BinOp.NEQ)
        {
            throw unsupported("arithmetic comparison supports only != (not_equal_to_*)");
        }
        String name = nameOf(b.left());
        if (rhs.op() == Expr.BinOp.DIV && rhs.left() instanceof Expr.Ref a
                && rhs.right() instanceof Expr.Ref bb)
        {
            return arithLeaf(name, "not_equal_to_divide", a.name(), bb.name());
        }
        if (rhs.op() == Expr.BinOp.SUB && rhs.left() instanceof Expr.Ref a
                && rhs.right() instanceof Expr.Ref bb)
        {
            return arithLeaf(name, "not_equal_to_subtract", a.name(), bb.name());
        }
        if (rhs.op() == Expr.BinOp.MUL && isHundred(rhs.right())
                && rhs.left() instanceof Expr.Binary div && div.op() == Expr.BinOp.DIV
                && div.left() instanceof Expr.Binary sub && sub.op() == Expr.BinOp.SUB
                && sub.left() instanceof Expr.Ref a && sub.right() instanceof Expr.Ref b1
                && div.right() instanceof Expr.Ref b2 && b1.name().equals(b2.name()))
        {
            return arithLeaf(name, "not_equal_to_pctchg", a.name(), b1.name());
        }
        throw unsupported("unrecognised arithmetic shape (native-only, no legacy operator)");
    }


    private static CheckCondition arithLeaf(String name, String operator, String a, String b)
    {
        ArrayNode arr = NODES.arrayNode(2);
        arr.add(NODES.textNode(a));
        arr.add(NODES.textNode(b));
        return CheckConditionLeaf.builder().name(name).operator(operator).value(arr).build();
    }


    private static boolean isHundred(Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER
                && Double.compare((Double) lit.value(), 100.0) == 0;
    }


    /**
     * Lowers a non-row-level operator function call (see {@link #FUNCTION_OPERATORS}) back to its
     * operator-leaf: first argument → {@code name}; a scalar 2nd positional argument or
     * {@code keys=[…]} → {@code value}; {@code within}/{@code ordering}/{@code regex}/
     * {@code include_empty} kwargs → the corresponding fields.
     */
    private static CheckCondition functionOperatorLeaf(Expr.Call c)
    {
        return functionOperatorLeaf(c, c.name());
    }


    /**
     * Reconstructs a function-operator leaf from a call, using {@code operator} as the operator
     * name (which may differ from {@code c.name()} for the Q1 negation pairs — e.g. the positive
     * call {@code present_on_multiple_rows_within} lowered under {@code not} to the negative
     * operator {@code not_present_on_multiple_rows_within}).
     */
    private static CheckCondition functionOperatorLeaf(Expr.Call c, String operator)
    {
        if (c.args().isEmpty())
        {
            throw unsupported(c.name() + "(...) requires the column argument");
        }
        CheckConditionLeaf.CheckConditionLeafBuilder b = CheckConditionLeaf.builder()
                .operator(operator);
        var kw = c.kwargs();
        if (UNIQUE_SET_OPERATORS.contains(operator))
        {
            // Owner requirement #1 (2026-08-23): is_(not_)unique_set carries ONE list operand.
            // The LEAF wire form keeps its name/value split (design decision D-2) — member 0
            // becomes `name`, the rest `value` (an array, omitted when there is no rest) —
            // because collectOperandRefs, RuleFingerprint and SilencingGuardScan all read it. A
            // re-encoding, not a semantic privilege; CheckToExpr.functionLeaf is the inverse.
            // ⛔ The retired f(A, keys=[…]) / f(A, B) / f(A) spellings are REFUSED here (Plan A
            // Phase 2, ruling 2): the throw keeps the Check native (CheckConditionExpression), so
            // the loader's walk reaches the call and validateInlineUniqueSetShape turns it into
            // the load error carrying the migration text. Lowering it instead would produce the
            // identical leaf and the old grammar would silently survive.
            if (kw.containsKey("keys"))
            {
                throw unsupported(operator + " no longer takes keys= — write " + operator
                        + "([A, B, …]), one list operand");
            }
            if (c.args().size() != 1)
            {
                throw unsupported(operator + " takes exactly one list operand");
            }
            if (!(c.args().get(0) instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
            {
                throw unsupported(operator + "'s operand must be a list literal: " + operator
                        + "([A, B, …])");
            }
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            if (items.isEmpty())
            {
                throw unsupported(operator + "([]) has no members");
            }
            b.name(referenceName(items.get(0)));
            if (items.size() > 1)
            {
                b.value(operandArray(
                        new Expr.Lit(Expr.LitKind.LIST, items.subList(1, items.size()))));
            }
        }
        else
        {
            b.name(nameOf(c.args().get(0)));
            if (c.args().size() >= 2)
            {
                Expr v = c.args().get(1);
                if (v instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER)
                {
                    b.value(numberNode((Double) lit.value()));
                }
                else
                {
                    b.value(NODES.textNode(referenceName(v)));
                }
            }
            if (kw.containsKey("keys"))
            {
                b.value(operandArray(kw.get("keys")));
            }
        }
        // The SHARED kwarg tail — one copy for both shapes.
        if (kw.containsKey("within"))
        {
            b.within(withinNode(kw.get("within")));
        }
        if (kw.containsKey("ordering"))
        {
            b.ordering(referenceName(kw.get("ordering")));
        }
        if (kw.containsKey("regex"))
        {
            Expr r = kw.get("regex");
            if (!(r instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
            {
                throw unsupported("regex= must be a string literal");
            }
            b.regex((String) lit.value());
        }
        if (kw.containsKey("include_empty"))
        {
            Expr ie = kw.get("include_empty");
            if (!(ie instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("include_empty= must be a boolean literal");
            }
            b.includeEmpty((Boolean) lit.value());
        }
        if (kw.containsKey("keep_missings"))
        {
            Expr km = kw.get("keep_missings");
            if (!(km instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("keep_missings= must be a boolean literal");
            }
            b.keepMissings((Boolean) lit.value());
        }
        if (kw.containsKey("relation"))
        {
            // EC-87 — the next-record comparison relation; a string literal, read back verbatim
            // so the Check ⇄ Expr round-trip is lossless (the spelling is validated where it is
            // consumed).
            Expr rel = kw.get("relation");
            if (!(rel instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
            {
                throw unsupported("relation= must be a string literal");
            }
            b.relation((String) lit.value());
        }
        return b.build();
    }


    /** A {@code upper(X)} case-insensitive-membership marker (one argument, no kwargs). */
    private static boolean isUpper(Expr e)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && "upper".equals(c.name());
    }


    /**
     * Lowers {@code upper(X) in/not in […]} (Q1) back to the {@code (is_)(not_)contained_by_
     * case_insensitive} operator-leaf, unwrapping each {@code upper("lit")} term and bare string
     * term to the original value-list string.
     */
    private static CheckCondition caseInsensitiveMembershipLeaf(Expr.Binary b)
    {
        String op = b.op() == Expr.BinOp.IN ? "is_contained_by_case_insensitive"
                : "is_not_contained_by_case_insensitive";
        String name = nameOf(((Expr.Call) b.left()).args().get(0));
        if (b.right() instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            ArrayNode arr = NODES.arrayNode(items.size());
            for (Expr item : items)
            {
                arr.add(NODES.textNode(membershipTerm(item)));
            }
            return CheckConditionLeaf.builder().name(name).operator(op).value(arr).build();
        }
        // Dynamic membership set (an operation / metadata-operand reference, e.g.
        // upper(value()) in var_codelist_coded_values("LIBRARY")): reconstruct the operand-name
        // value exactly as the plain is_(not_)contained_by sibling does (applyValue -> the
        // reverse operand mapping), so the leaf round-trips byte-for-byte with the source rule
        // (CheckToExpr.caseInsensitiveMembership emits value() for such operands).
        return leaf(name, op, b.right());
    }


    private static String membershipTerm(Expr item)
    {
        Expr lit = isUpper(item) ? ((Expr.Call) item).args().get(0) : item;
        if (lit instanceof Expr.Lit l && l.kind() == Expr.LitKind.STRING)
        {
            return (String) l.value();
        }
        throw unsupported("case-insensitive membership term must be a string or upper(\"…\")");
    }


    /**
     * Lowers {@code not is_sorted_by(X, by=[asc/desc("col"…)], within=…)} back to a
     * {@code target_is_not_sorted_by} operator-leaf with its {@code {name, sort_order,
     * null_position}} sort-descriptor array and {@code within} field.
     */
    private static CheckCondition sortedByLeaf(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("is_sorted_by(...) requires the column argument");
        }
        Expr by = c.kwargs().get("by");
        if (!(by instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            throw unsupported("is_sorted_by requires a by=[…] descriptor list");
        }
        @SuppressWarnings("unchecked")
        List<Expr> descs = (List<Expr>) lit.value();
        ArrayNode arr = NODES.arrayNode(descs.size());
        for (Expr d : descs)
        {
            if (!(d instanceof Expr.Call dc)
                    || !("asc".equals(dc.name()) || "desc".equals(dc.name()))
                    || dc.args().size() != 1 || !(dc.args().get(0) instanceof Expr.Lit col)
                    || col.kind() != Expr.LitKind.STRING)
            {
                throw unsupported("sort descriptor must be asc(\"col\") or desc(\"col\")");
            }
            ObjectNode o = NODES.objectNode();
            o.put("name", (String) col.value());
            o.put("sort_order", dc.name());
            Expr nulls = dc.kwargs().get("nulls");
            o.put("null_position",
                    nulls instanceof Expr.Lit nl && nl.kind() == Expr.LitKind.STRING
                            ? (String) nl.value()
                            : "last");
            arr.add(o);
        }
        CheckConditionLeaf.CheckConditionLeafBuilder b = CheckConditionLeaf.builder()
                .name(nameOf(c.args().get(0))).operator("target_is_not_sorted_by").value(arr);
        Expr within = c.kwargs().get("within");
        if (within != null)
        {
            b.within(withinNode(within));
        }
        Expr keep = c.kwargs().get("keep_missings");
        if (keep != null)
        {
            if (!(keep instanceof Expr.Lit kl) || kl.kind() != Expr.LitKind.BOOL)
            {
                throw unsupported("keep_missings= must be a boolean literal");
            }
            b.keepMissings((Boolean) kl.value());
        }
        return b.build();
    }


    /**
     * Lowers a {@code within=} kwarg operand back to the leaf's {@code within} JSON (EC-24). A
     * single reference becomes a text node; a list literal becomes an array whose entries are
     * either a column-name text node or — for a nested list literal (a coalesce-group) — a nested
     * array of column-name text nodes. Inverse of {@link net.cumba.cdisc.core.expr.CheckToExpr}'s
     * {@code withinOperand}.
     */
    private static JsonNode withinNode(Expr within)
    {
        if (!(within instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            return NODES.textNode(referenceName(within));
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        ArrayNode arr = NODES.arrayNode(items.size());
        for (Expr item : items)
        {
            if (item instanceof Expr.Lit il && il.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> group = (List<Expr>) il.value();
                ArrayNode inner = NODES.arrayNode(group.size());
                for (Expr g : group)
                {
                    inner.add(NODES.textNode(referenceName(g)));
                }
                arr.add(inner);
            }
            else
            {
                arr.add(NODES.textNode(referenceName(item)));
            }
        }
        return arr;
    }


    /** Converts a {@code [list]} literal of reference/literal operands to a JSON array node. */
    private static ArrayNode operandArray(Expr e)
    {
        if (!(e instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            throw unsupported("expected a [list] operand but found " + describe(e));
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        ArrayNode arr = NODES.arrayNode(items.size());
        for (Expr item : items)
        {
            if (item instanceof Expr.Lit il)
            {
                arr.add(literalNode(il));
            }
            else
            {
                arr.add(NODES.textNode(referenceName(item)));
            }
        }
        return arr;
    }

    // ---- operand helpers -------------------------------------------------


    private static @Nullable String wrapperOf(Expr e)
    {
        if (e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && UNARY_WRAPPERS.contains(c.name()))
        {
            return c.name();
        }
        return null;
    }


    private static Expr unwrap(Expr e)
    {
        return wrapperOf(e) != null ? ((Expr.Call) e).args().get(0) : e;
    }


    private static boolean isCaseWrapper(@Nullable String w)
    {
        return "lowcase".equals(w) || "upcase".equals(w);
    }


    private static String comparisonFamily(@Nullable String lw, @Nullable String rw)
    {
        String w = lw != null ? lw : rw;
        if ("date".equals(w))
        {
            return "date";
        }
        if ("date_part".equals(w))
        {
            return "date_part";
        }
        if ("time_part".equals(w))
        {
            return "time_part";
        }
        // null, "num" -> plain numeric/equality.
        return "plain";
    }


    private static String comparisonOperator(String family, Expr.BinOp op, Expr li)
    {
        return switch (family)
        {
        case "date" -> switch (op)
        {
        case EQ -> "date_equal_to";
        case NEQ -> "date_not_equal_to";
        case LT -> "date_less_than";
        case GT -> "date_greater_than";
        case LE -> "date_less_than_or_equal_to";
        case GE -> "date_greater_than_or_equal_to";
        default -> throw unsupported("unsupported date comparison");
        };
        case "date_part" -> switch (op)
        {
        case EQ -> "date_part_equal_to";
        case NEQ -> "date_part_not_equal_to";
        default -> throw unsupported("date_part supports only == and !=");
        };
        case "time_part" -> switch (op)
        {
        case EQ -> "time_part_equal_to";
        case NEQ -> "time_part_not_equal_to";
        default -> throw unsupported("time_part supports only == and !=");
        };
        default -> switch (op)
        {
        case EQ -> "equal_to";
        case NEQ -> "not_equal_to";
        case LT -> "less_than";
        case GT -> "greater_than";
        case LE -> "less_than_or_equal_to";
        case GE -> "greater_than_or_equal_to";
        default -> throw unsupported("unsupported comparison for operand " + describe(li));
        };
        };
    }


    /** Builds a leaf whose value comes from a right-hand operand (reference or literal). */
    private static CheckConditionLeaf leaf(String name, String operator, Expr value)
    {
        CheckConditionLeaf.CheckConditionLeafBuilder b = CheckConditionLeaf.builder().name(name)
                .operator(operator);
        LeafValue lv = leafValue(value);
        b.value(lv.node());
        if (lv.reference() != null)
        {
            b.valueIsReference(lv.reference());
        }
        if (lv.literal() != null)
        {
            b.valueIsLiteral(lv.literal());
        }
        return b.build();
    }

    /**
     * The {@code value} fields a right-hand operand contributes to a leaf: the node itself plus the
     * optional {@code value_is_reference} / {@code value_is_literal} flags. A reference operand's
     * value is the referenced column / {@code $}-op / built-in name, left verbatim (a textual value
     * defaults to a reference in the engine's {@code ValueResolver}).
     *
     * @param node
     *            the value node.
     * @param reference
     *            {@code TRUE} when the value names a reference, else {@code null}.
     * @param literal
     *            {@code TRUE} when the value is a literal, else {@code null}.
     */
    private record LeafValue(JsonNode node, @Nullable Boolean reference, @Nullable Boolean literal)
    {
    }

    /**
     * Derive the {@code value} fields for a right-hand operand.
     *
     * <p>
     * ⚠ This RETURNS the pieces rather than applying them to a builder, and that is deliberate.
     * Naming Lombok's generated {@code CheckConditionLeafBuilder} in a method signature makes
     * javadoc fail outright with "cannot find symbol": javadoc parses source, not
     * annotation-processor output, so the generated type does not exist as far as it is concerned.
     * It resolves every signature it parses — even a private one, and even with
     * {@code -Dshow=public} — so the failure is not avoidable by scoping. Since Maven Central
     * requires a {@code -javadoc.jar}, a generated type in a signature blocks publishing entirely.
     * Local variables of that type (used freely above) are fine; javadoc does not resolve method
     * bodies.
     * </p>
     *
     * @param value
     *            the right-hand operand.
     * @return the value node and its optional flags.
     */
    private static LeafValue leafValue(Expr value)
    {
        if (isColRef(value))
        {
            // colref(X) -> value_is_reference:true (two-hop). The inner operand is the first-hop
            // column name; ValueResolver performs the second hop at runtime.
            Expr inner = ((Expr.Call) value).args().get(0);
            return new LeafValue(NODES.textNode(referenceName(inner)), Boolean.TRUE, null);
        }
        if (value instanceof Expr.Lit lit)
        {
            return new LeafValue(literalNode(lit), null, Boolean.TRUE);
        }
        return new LeafValue(NODES.textNode(referenceName(value)), null, null);
    }


    /** A {@code colref(X)} two-hop dereference wrapper (one reference arg, no kwargs). */
    private static boolean isColRef(Expr e)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && "colref".equals(c.name());
    }


    private static String referenceName(Expr e)
    {
        Expr inner = unwrap(e);
        String current = currentVariableOperand(inner);
        if (current != null)
        {
            return current; // varname()/value() -> variable_name/variable_value (round-trip)
        }
        String operand = MetadataOperandMapping.reverseToOperand(inner);
        if (operand != null)
        {
            return operand; // var_*/ds_* accessor -> its metadata operand name (round-trip, D6)
        }
        if (inner instanceof Expr.Ref r)
        {
            return r.name();
        }
        throw unsupported("expected a column/reference operand but found " + describe(e));
    }


    private static String nameOf(Expr e)
    {
        Expr inner = unwrap(e);
        String current = currentVariableOperand(inner);
        if (current != null)
        {
            return current; // varname()/value() -> variable_name/variable_value (round-trip)
        }
        String operand = MetadataOperandMapping.reverseToOperand(inner);
        if (operand != null)
        {
            return operand; // var_*/ds_* accessor -> its metadata operand name (round-trip, D6)
        }
        if (inner instanceof Expr.Ref r)
        {
            return r.name();
        }
        throw unsupported(
                "the left side of a comparison must be a column reference, found " + describe(e));
    }


    /**
     * Reverses the two current-variable native functions back to their standalone operand names:
     * {@code varname() → variable_name}, {@code value() → variable_value}. Returns {@code null} for
     * anything else. The inverse of {@code CheckToExpr.currentVariableCall}, keeping the
     * {@code Check → Expr → Check} round-trip and the legacy fallback lowering faithful.
     */
    private static @Nullable String currentVariableOperand(Expr e)
    {
        if (e instanceof Expr.Call c && c.args().isEmpty() && c.kwargs().isEmpty())
        {
            if ("varname".equals(c.name()))
            {
                return "variable_name";
            }
            if ("value".equals(c.name()))
            {
                return "variable_value";
            }
        }
        return null;
    }


    private static JsonNode literalNode(Expr e)
    {
        if (!(e instanceof Expr.Lit lit))
        {
            throw unsupported("expected a literal but found " + describe(e));
        }
        return literalNode(lit);
    }


    private static JsonNode literalNode(Expr.Lit lit)
    {
        return switch (lit.kind())
        {
        case STRING, REGEX -> NODES.textNode((String) lit.value());
        case BOOL -> NODES.booleanNode((Boolean) lit.value());
        case NUMBER -> numberNode((Double) lit.value());
        case LIST -> listNode(lit);
        };
    }


    private static JsonNode numberNode(Double d)
    {
        if (!d.isInfinite() && Double.compare(d, Math.rint(d)) == 0)
        {
            return NODES.numberNode(d.longValue());
        }
        return NODES.numberNode(d);
    }


    private static JsonNode listNode(Expr.Lit lit)
    {
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        ArrayNode arr = NODES.arrayNode(items.size());
        for (Expr item : items)
        {
            if (item instanceof Expr.Lit il)
            {
                arr.add(literalNode(il));
            }
            else
            {
                throw unsupported("list elements must be literals");
            }
        }
        return arr;
    }


    private static void requireArgs(String fn, int actual, int expected)
    {
        if (actual != expected)
        {
            throw unsupported(fn + "(...) expects " + expected + " argument(s) but got " + actual);
        }
    }


    private static String describe(Expr e)
    {
        return e.getClass().getSimpleName();
    }


    private static ExpressionException unsupported(String detail)
    {
        return new ExpressionException("Expression construct not supported by the v1 lowering "
                + "(needs the native evaluator): " + detail);
    }

}
