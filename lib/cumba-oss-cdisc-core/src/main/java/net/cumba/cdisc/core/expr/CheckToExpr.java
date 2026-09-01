package net.cumba.cdisc.core.expr;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionConstant;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import net.cumba.cdisc.core.model.NextRecordRelation;
import org.jspecify.annotations.Nullable;

/**
 * Raises a {@link CheckCondition} (old-style operator-leaf AST) into the {@link Expr} IR — the
 * inverse of {@link ExprLowering}. Used by the old→new converter and round-trip tests.
 *
 * <p>
 * Only operators (and leaves) with a faithful expression surface are raised; for anything else — an
 * unmapped operator, or a leaf carrying a field the surface does not reproduce
 * ({@code within}/{@code prefix}/{@code suffix}/{@code regex}/{@code negative}/{@code ordering}/
 * {@code type_insensitive}) — an {@link ExpressionException} is thrown so the converter leaves the
 * leaf in old-style form (partial by design, and byte-faithful for those leaves).
 * </p>
 */
public final class CheckToExpr
{

    private CheckToExpr()
    {
    }

    /**
     * Non-row-level operators raised to a like-named function call (Phase 4a). Each carries its
     * operands and modifier fields generically: {@code name} as the first argument, a scalar
     * {@code value} as a second positional argument, an array {@code value} as a {@code keys=[…]}
     * kwarg, and {@code within}/{@code ordering}/{@code regex} as kwargs — except the uniqueness
     * pair ({@link ExprLowering#UNIQUE_SET_OPERATORS}), whose {@code name} + {@code value} raise to
     * ONE list operand (2026-08-23). {@code ExprLowering} reverses this encoding back to the
     * identical operator-leaf.
     */
    private static final java.util.Set<String> FUNCTION_OPERATORS = java.util.Set.of(
            "has_multiple_values_for", "is_unique_set", "is_inconsistent_across_dataset",
            "has_same_values", "inconsistent_enumerated_columns", "present_on_multiple_rows_within",
            "empty_within_except_last_row", "does_not_equal_string_part");

    /**
     * Change #1: the negative group operators whose positive twin now exists in the engine (Task
     * EA). Each is spelled {@code not <positive>(…)} — like the
     * {@code does_not_have_next_corresponding_record} pair — so {@link ExprLowering} maps
     * {@code Not(<positive>)} straight back to the negative operator-leaf (preserving the
     * round-trip) and a hand-author's bare {@code <positive>(…)} reads naturally.
     */
    private static final java.util.Map<String, String> NEGATED_TO_POSITIVE = java.util.Map.of(
            "is_not_unique_relationship", "is_unique_relationship", "not_contains_all",
            "contains_all", "shares_no_elements_with", "shares_elements_with",
            "is_not_ordered_subset_of", "is_ordered_subset_of", "is_not_unique_value",
            "is_unique_value", "is_not_unique_set", "is_unique_set");

    /**
     * Raises a check-condition tree to the {@link Expr} IR.
     *
     * @param c
     *            the condition
     * @return the equivalent expression
     * @throws ExpressionException
     *             if any leaf has no faithful expression surface
     */
    public static Expr toExpr(CheckCondition c)
    {
        return switch (c)
        {
        case CheckConditionAll all -> new Expr.And(mapAll(all.getConditions()));
        case CheckConditionAny any -> new Expr.Or(mapAll(any.getConditions()));
        case CheckConditionNot not -> new Expr.Not(toExpr(not.getCondition()));
        case CheckConditionLeaf leaf -> leafToExpr(leaf);
        case CheckConditionConstant _ -> throw unsupported(
                "boolean constant has no expression surface");
        // A native-only expression Check already carries its compiled Expr — return it directly.
        case net.cumba.cdisc.core.model.CheckConditionExpression ce -> ce.expr();
        };
    }


    private static List<Expr> mapAll(List<CheckCondition> conditions)
    {
        List<Expr> out = new ArrayList<>(conditions.size());
        for (CheckCondition c : conditions)
        {
            out.add(toExpr(c));
        }
        return out;
    }


    private static Expr leafToExpr(CheckConditionLeaf leaf)
    {
        String op = leaf.getOperator();
        if (op == null)
        {
            throw unsupported("leaf has no operator");
        }
        // T3 composite membership: a `names` list target raises to `tuple(c1, …) [not] in <value>`
        // (the value being the list-target distinct reference set). Runs before the single-name
        // guard, since a composite leaf carries `names` in place of `name`.
        List<String> names = leaf.getNames();
        if (names != null && !names.isEmpty())
        {
            return compositeMembershipLeaf(op, names, leaf);
        }
        String name = leaf.getName();
        if (name == null)
        {
            throw unsupported("leaf has no name");
        }

        // Phase 4a — non-row-level function operators: emit the operator as a function call
        // carrying
        // its operands (name, value, keys) and modifier fields (within/ordering/regex) as
        // positional/keyword arguments. These consume within/ordering/regex, so they run before the
        // group-field guard.
        if (FUNCTION_OPERATORS.contains(op))
        {
            return functionLeaf(op, name, leaf);
        }
        // Change #1: the four negative group operators with an engine positive twin (Task EA) —
        // spell them `not <positive>(…)`; ExprLowering reverses Not(positive) to the negative leaf.
        String positiveTwin = NEGATED_TO_POSITIVE.get(op);
        if (positiveTwin != null)
        {
            return new Expr.Not(functionLeaf(positiveTwin, name, leaf));
        }
        // Q1 negation pairs: spell the negative group operators as `not <positive>(…)` so the
        // expression surface reads naturally and ExprLowering maps Not(positive) straight back to
        // the negative operator-leaf (never a structural not).
        if ("does_not_have_next_corresponding_record".equals(op))
        {
            return new Expr.Not(functionLeaf("has_next_corresponding_record", name, leaf));
        }
        if ("not_present_on_multiple_rows_within".equals(op))
        {
            return new Expr.Not(functionLeaf("present_on_multiple_rows_within", name, leaf));
        }
        if ("target_is_not_sorted_by".equals(op))
        {
            return sortedByLeaf(name, leaf);
        }

        rejectGroupFields(leaf);

        // Phase 2 — operators that consume a prefix/suffix length, type_insensitive, or negative.
        Expr affix = affixLeafToExpr(op, name, leaf);
        if (affix != null)
        {
            return affix;
        }
        if ("invalid_duration".equals(op) && leaf.getNegative() != null)
        {
            return new Expr.Call("invalid_duration", List.of(ref(name)), java.util.Map
                    .of("negative", new Expr.Lit(Expr.LitKind.BOOL, leaf.getNegative())));
        }
        if (leaf.getTypeInsensitive() != null
                && ("equal_to".equals(op) || "not_equal_to".equals(op)))
        {
            Expr.BinOp binOp = "equal_to".equals(op) ? Expr.BinOp.EQ : Expr.BinOp.NEQ;
            return new Expr.Binary(binOp,
                    new Expr.Call("str", List.of(ref(name)), java.util.Map.of()),
                    new Expr.Call("str", List.of(value(leaf)), java.util.Map.of()));
        }

        // Plain operators: a stray prefix/suffix/negative/type_insensitive field has no surface.
        rejectAffixFields(leaf);
        Expr ref = migratingRef(name);
        // Regex-rule optimisation (Phase 3): recognise the known cheap regex shapes by their exact
        // (operator, pattern) key and emit a scalar predicate instead of a per-row MATCH/NMATCH.
        // Returns null for any unrecognised pattern, falling through to the MATCH/NMATCH cases.
        if ("matches_regex".equals(op) || "not_matches_regex".equals(op))
        {
            Expr optimised = optimiseRegexLeaf(leaf, "not_matches_regex".equals(op), name, ref);
            if (optimised != null)
            {
                return optimised;
            }
        }
        return switch (op)
        {
        case "equal_to" -> new Expr.Binary(Expr.BinOp.EQ, ref, value(leaf));
        case "not_equal_to" -> new Expr.Binary(Expr.BinOp.NEQ, ref, value(leaf));
        case "less_than" -> new Expr.Binary(Expr.BinOp.LT, ref, value(leaf));
        case "greater_than" -> new Expr.Binary(Expr.BinOp.GT, ref, value(leaf));
        case "less_than_or_equal_to" -> new Expr.Binary(Expr.BinOp.LE, ref, value(leaf));
        case "greater_than_or_equal_to" -> new Expr.Binary(Expr.BinOp.GE, ref, value(leaf));
        case "date_equal_to" -> dateCmp(Expr.BinOp.EQ, "date", name, leaf);
        case "date_not_equal_to" -> dateCmp(Expr.BinOp.NEQ, "date", name, leaf);
        case "date_less_than" -> dateCmp(Expr.BinOp.LT, "date", name, leaf);
        case "date_greater_than" -> dateCmp(Expr.BinOp.GT, "date", name, leaf);
        case "date_less_than_or_equal_to" -> dateCmp(Expr.BinOp.LE, "date", name, leaf);
        case "date_greater_than_or_equal_to" -> dateCmp(Expr.BinOp.GE, "date", name, leaf);
        case "date_part_equal_to" -> dateCmp(Expr.BinOp.EQ, "date_part", name, leaf);
        case "date_part_not_equal_to" -> dateCmp(Expr.BinOp.NEQ, "date_part", name, leaf);
        case "time_part_equal_to" -> dateCmp(Expr.BinOp.EQ, "time_part", name, leaf);
        case "time_part_not_equal_to" -> dateCmp(Expr.BinOp.NEQ, "time_part", name, leaf);
        case "longer_than" -> new Expr.Binary(Expr.BinOp.GT, wrap("len", name), value(leaf));
        case "longer_than_or_equal_to" -> new Expr.Binary(Expr.BinOp.GE, wrap("len", name),
                value(leaf));
        case "shorter_than" -> new Expr.Binary(Expr.BinOp.LT, wrap("len", name), value(leaf));
        case "shorter_than_or_equal_to" -> new Expr.Binary(Expr.BinOp.LE, wrap("len", name),
                value(leaf));
        case "has_equal_length" -> new Expr.Binary(Expr.BinOp.EQ, wrap("len", name),
                lengthValue(leaf));
        case "has_not_equal_length" -> new Expr.Binary(Expr.BinOp.NEQ, wrap("len", name),
                lengthValue(leaf));
        case "matches_regex" -> new Expr.Binary(Expr.BinOp.MATCH, ref, regex(leaf));
        case "not_matches_regex" -> new Expr.Binary(Expr.BinOp.NMATCH, ref, regex(leaf));
        case "is_contained_by" -> new Expr.Binary(Expr.BinOp.IN, ref, value(leaf));
        case "is_not_contained_by" -> new Expr.Binary(Expr.BinOp.NOT_IN, ref, value(leaf));
        case "equal_to_case_insensitive" -> call("equalsIgnoreCase", ref, value(leaf));
        case "not_equal_to_case_insensitive" -> new Expr.Not(
                call("equalsIgnoreCase", ref, value(leaf)));
        case "is_contained_by_case_insensitive" -> caseInsensitiveMembership(Expr.BinOp.IN, ref,
                leaf);
        case "is_not_contained_by_case_insensitive" -> caseInsensitiveMembership(Expr.BinOp.NOT_IN,
                ref, leaf);
        case "prefix_matches_regex" -> call("prefix_matches", ref, regex(leaf));
        case "suffix_matches_regex" -> call("suffix_matches", ref, regex(leaf));
        // Phase 5 (plan unified-callable-surface): the exists family takes a NAME operand, which
        // the generator prefers as a quoted string literal (engine + lowering accept both).
        case "exists", "not_exists", "ds_exists", "ds_not_exists", "var_exists", "var_not_exists" -> unaryPredicate(
                op, nameOperand(name), leaf);
        case "var_is_null", "empty", "is_complete_date", "is_incomplete_date", "invalid_date", "invalid_duration", "is_integer", "is_complete_date_part" -> unaryPredicate(
                op, ref, leaf);
        // Change #1 (Task EF): negative-named predicates whose positive already exists become
        // `not <positive>(…)`; ExprLowering reverses Not(positive) to the original negative leaf.
        case "non_empty" -> new Expr.Not(unaryPredicate("empty", ref, leaf));
        case "is_not_integer" -> new Expr.Not(unaryPredicate("is_integer", ref, leaf));
        // Fix #157 — is_not_complete_date_part is the form all 19 is_incomplete_date rules need.
        case "is_not_complete_date_part" -> new Expr.Not(
                unaryPredicate("is_complete_date_part", ref, leaf));
        case "contains", "starts_with", "ends_with" -> call(op, ref, substringValue(leaf));
        case "does_not_contain" -> new Expr.Not(call("contains", ref, substringValue(leaf)));
        // Case-insensitive contains fold to the established `contains(upper(ref), upper(lit))`
        // idiom (native reuses the upper/contains builtins; Python has the operators natively).
        case "contains_case_insensitive" -> call("contains", call("upper", ref),
                call("upper", substringValue(leaf)));
        case "does_not_contain_case_insensitive" -> new Expr.Not(
                call("contains", call("upper", ref), call("upper", substringValue(leaf))));
        case "not_equal_to_divide" -> arithmeticCompare(name, leaf, Expr.BinOp.DIV);
        case "not_equal_to_subtract" -> arithmeticCompare(name, leaf, Expr.BinOp.SUB);
        case "not_equal_to_pctchg" -> pctChangeCompare(name, leaf);
        default -> throw unsupported("operator '" + op + "' has no expression surface");
        };
    }


    /**
     * Raises a T3 composite-membership leaf ({@code names: [c1, c2, …]} +
     * {@code is_(not_)contained_by} + {@code value}) to {@code tuple(c1, c2, …) [not] in <value>}.
     * The {@code tuple(...)} value function builds the row's composite key; {@code value} is the
     * list-target {@code distinct} reference set (a {@code $}-reference, inlined to
     * {@code distinct([c1, c2, …], domain="D")} by
     * {@code net.cumba.cdisc.core.expr.convert.OperationInliner}). Only the two membership
     * operators are supported.
     */
    private static Expr compositeMembershipLeaf(String op, List<String> names,
            CheckConditionLeaf leaf)
    {
        Expr.BinOp binOp = switch (op)
        {
        case "is_contained_by" -> Expr.BinOp.IN;
        case "is_not_contained_by" -> Expr.BinOp.NOT_IN;
        default -> throw unsupported("composite `names` target supports only "
                + "is_contained_by / is_not_contained_by, got '" + op + "'");
        };
        List<Expr> cols = new ArrayList<>(names.size());
        for (String col : names)
        {
            cols.add(ref(col));
        }
        return new Expr.Binary(binOp, new Expr.Call("tuple", cols, java.util.Map.of()),
                value(leaf));
    }


    /**
     * Rejects the group/order/regex modifier fields, which belong to the non-row-level operator
     * family handled in Phase 4 (no row-level expression surface yet).
     */
    private static void rejectGroupFields(CheckConditionLeaf leaf)
    {
        if ((leaf.getWithin() != null && !leaf.getWithin().isNull()) || leaf.getOrdering() != null
                || leaf.getRegex() != null || leaf.getIncludeEmpty() != null
                || leaf.getKeepMissings() != null || leaf.getRelation() != null)
        {
            throw unsupported("leaf carries a group/order/regex field "
                    + "(within/ordering/regex/include_empty/keep_missings/relation) "
                    + "with no row-level expression surface");
        }
    }


    /**
     * Rejects an affix/negative/type-insensitive modifier on an operator that does not consume it
     * (the consuming operators are handled before this guard in {@link #leafToExpr}).
     */
    private static void rejectAffixFields(CheckConditionLeaf leaf)
    {
        if (leaf.getPrefix() != null || leaf.getSuffix() != null || leaf.getNegative() != null
                || leaf.getTypeInsensitive() != null)
        {
            throw unsupported("operator '" + leaf.getOperator() + "' does not support a "
                    + "prefix/suffix/negative/type_insensitive modifier");
        }
    }


    /**
     * Raises the prefix/suffix affix operators (Phase 2): {@code prefix(X, n)}/{@code suffix(X, n)}
     * value functions composed with comparison/membership, and the 3-argument
     * {@code prefix_matches(X, /re/, n)}/{@code suffix_matches(X, /re/, n)} (negated via
     * {@code not}). Returns {@code null} when {@code op} is not an affix operator.
     */
    private static @Nullable Expr affixLeafToExpr(String op, String name, CheckConditionLeaf leaf)
    {
        return switch (op)
        {
        case "prefix_equal_to" -> affixCompare("prefix", Expr.BinOp.EQ, name, leaf);
        case "prefix_not_equal_to" -> affixCompare("prefix", Expr.BinOp.NEQ, name, leaf);
        case "prefix_is_contained_by" -> affixCompare("prefix", Expr.BinOp.IN, name, leaf);
        case "prefix_is_not_contained_by" -> affixCompare("prefix", Expr.BinOp.NOT_IN, name, leaf);
        case "suffix_equal_to" -> affixCompare("suffix", Expr.BinOp.EQ, name, leaf);
        case "suffix_not_equal_to" -> affixCompare("suffix", Expr.BinOp.NEQ, name, leaf);
        case "suffix_is_contained_by" -> affixCompare("suffix", Expr.BinOp.IN, name, leaf);
        case "suffix_is_not_contained_by" -> affixCompare("suffix", Expr.BinOp.NOT_IN, name, leaf);
        case "prefix_matches_regex" -> affixMatches("prefix", name, leaf, false);
        case "not_prefix_matches_regex" -> affixMatches("prefix", name, leaf, true);
        case "suffix_matches_regex" -> affixMatches("suffix", name, leaf, false);
        case "not_suffix_matches_regex" -> affixMatches("suffix", name, leaf, true);
        default -> null;
        };
    }


    private static Expr affixCompare(String kind, Expr.BinOp op, String name,
            CheckConditionLeaf leaf)
    {
        Expr affix = new Expr.Call(kind, List.of(ref(name), affixLength(kind, leaf)),
                java.util.Map.of());
        return new Expr.Binary(op, affix, value(leaf));
    }


    /**
     * Raises the affix-regex operators (Agreed change #2): {@code (not_)(prefix|suffix)_matches_
     * regex} becomes an anchored regex match over the affix value function —
     * {@code prefix(X, n) =~ /^re$/} (or {@code suffix(X, n) =~ …}); with no length the affix wraps
     * the whole operand ({@code X =~ /^re$/}). The {@code not_} forms use the negated regex
     * operator {@code !~}. Anchoring is mandatory: the affix operator evaluates with anchored
     * {@code pattern.matcher(sub).matches()}, whereas {@code =~}/{@code !~} evaluate with
     * unanchored {@code find()}, so the pattern is wrapped {@code ^…$} (see {@link #anchored}) to
     * preserve the whole-affix match. {@link ExprLowering} reverses this back to the affix-regex
     * operator-leaf; because {@code anchored} is idempotent on an already-anchored pattern, the
     * round-trip holds via the Expr-IR equivalence rescue in {@code RulePackageConverterCorpusTest}
     * even where the original (un-anchored) pattern is not recovered byte-for-byte.
     */
    private static Expr affixMatches(String kind, String name, CheckConditionLeaf leaf,
            boolean negated)
    {
        JsonNode v = leaf.getValue();
        if (v == null || !v.isTextual())
        {
            throw unsupported("affix-regex operator expects a textual pattern value");
        }
        Integer n = "prefix".equals(kind) ? leaf.getPrefix() : leaf.getSuffix();
        Expr operand = n == null ? ref(name)
                : new Expr.Call(kind,
                        List.of(ref(name), new Expr.Lit(Expr.LitKind.NUMBER, (double) n)),
                        java.util.Map.of());
        Expr pattern = new Expr.Lit(Expr.LitKind.REGEX, anchored(v.asText()));
        return new Expr.Binary(negated ? Expr.BinOp.NMATCH : Expr.BinOp.MATCH, operand, pattern);
    }


    /**
     * The pattern anchored as a full-string match for the {@code =~} form, kept as clean as is
     * safe: a bare {@code ^…$} when it has no top-level {@code |}; a plain capturing group
     * {@code ^(…)$} for a top-level {@code |} (nothing reads the captures — the engine does boolean
     * {@code find()}/{@code matches()} only); a non-capturing {@code ^(?:…)$} only when a numeric
     * backreference would otherwise be renumbered. An existing leading {@code ^} / trailing
     * (unescaped) {@code $} is not duplicated, so this is idempotent on an already-anchored
     * pattern.
     */
    private static String anchored(String pattern)
    {
        if (hasTopLevelAlternation(pattern))
        {
            String group = hasNumericBackreference(pattern) ? "(?:" + pattern + ")"
                    : "(" + pattern + ")";
            return "^" + group + "$";
        }
        String head = pattern.startsWith("^") ? pattern : "^" + pattern;
        return endsWithAnchor(head) ? head : head + "$";
    }


    /** Whether {@code pattern} has a top-level (not parenthesised) {@code |} alternation. */
    private static boolean hasTopLevelAlternation(String pattern)
    {
        int depth = 0;
        for (int i = 0; i < pattern.length(); i++)
        {
            char c = pattern.charAt(i);
            if (c == '\\')
            {
                i++;
            }
            else if (c == '(')
            {
                depth++;
            }
            else if (c == ')')
            {
                depth--;
            }
            else if (c == '|' && depth == 0)
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Whether {@code pattern} contains a numeric backreference ({@code \1}..{@code \9}) — a
     * backslash followed by a digit, skipping escaped backslashes.
     */
    private static boolean hasNumericBackreference(String pattern)
    {
        for (int i = 0; i < pattern.length(); i++)
        {
            if (pattern.charAt(i) == '\\')
            {
                if (i + 1 < pattern.length() && Character.isDigit(pattern.charAt(i + 1)))
                {
                    return true;
                }
                i++;
            }
        }
        return false;
    }


    /**
     * Whether {@code s} ends with a real {@code $} anchor (an even run of backslashes precedes).
     */
    private static boolean endsWithAnchor(String s)
    {
        if (!s.endsWith("$"))
        {
            return false;
        }
        int backslashes = 0;
        for (int i = s.length() - 2; i >= 0 && s.charAt(i) == '\\'; i--)
        {
            backslashes++;
        }
        return backslashes % 2 == 0;
    }


    private static Expr affixLength(String kind, CheckConditionLeaf leaf)
    {
        Integer n = "prefix".equals(kind) ? leaf.getPrefix() : leaf.getSuffix();
        if (n == null)
        {
            throw unsupported(
                    "operator '" + leaf.getOperator() + "' requires a " + kind + " length");
        }
        return new Expr.Lit(Expr.LitKind.NUMBER, (double) n);
    }


    /**
     * Raises a non-row-level operator (see {@link #FUNCTION_OPERATORS}) to a like-named function
     * call: {@code name} as the first argument, a scalar {@code value} as a second positional
     * argument, an array {@code value} as {@code keys=[…]}, and {@code within}/{@code ordering}/
     * {@code regex}/{@code include_empty} as kwargs ({@code include_empty} only on the two
     * consistency operators, Fix #121). The uniqueness pair
     * ({@link ExprLowering#UNIQUE_SET_OPERATORS}) raises {@code name} + {@code value} to ONE list
     * operand instead — {@code is_unique_set([A, B,
     * …])}, the canonical form since 2026-08-23.
     */
    private static Expr functionLeaf(String op, String name, CheckConditionLeaf leaf)
    {
        List<Expr> args = new ArrayList<>();
        java.util.Map<String, Expr> kwargs = new java.util.LinkedHashMap<>();
        JsonNode value = leaf.getValue();
        if (ExprLowering.UNIQUE_SET_OPERATORS.contains(op))
        {
            // Owner requirement #1 (2026-08-23): the leaf's `name` + array/text `value` raise to
            // ONE list operand, f([name, …value]) — the inverse of ExprLowering's D-2 re-encoding.
            // ⚠ This is the PRODUCTION compile path (RulePackageLoader.installNativeExpr →
            // tryRaiseToExpr), not a round-trip courtesy: ExprCompiler compiles what is raised
            // here, so this arm, ExprLowering's LIST arm and ExprCompiler.uniqueSetMembers are one
            // atomic change.
            List<Expr> members = new ArrayList<>();
            members.add(ref(name));
            if (value != null && !value.isNull())
            {
                if (value.isArray())
                {
                    for (JsonNode e : value)
                    {
                        members.add(e.isTextual() ? ref(e.asText()) : literal(e));
                    }
                }
                else if (value.isTextual())
                {
                    members.add(ref(value.asText()));
                }
                else
                {
                    throw unsupported("operator '" + op + "' has an unsupported value shape: "
                            + value.getNodeType());
                }
            }
            args.add(new Expr.Lit(Expr.LitKind.LIST, members));
        }
        else
        {
            args.add(ref(name));
            if (value != null && !value.isNull())
            {
                if (value.isArray())
                {
                    kwargs.put("keys", arrayOperand(value));
                }
                else if (value.isTextual())
                {
                    args.add(ref(value.asText()));
                }
                else if (value.isNumber())
                {
                    args.add(new Expr.Lit(Expr.LitKind.NUMBER, value.asDouble()));
                }
                else
                {
                    throw unsupported("operator '" + op + "' has an unsupported value shape: "
                            + value.getNodeType());
                }
            }
        }
        JsonNode within = leaf.getWithin();
        if (within != null && !within.isNull())
        {
            kwargs.put("within", withinOperand(within));
        }
        if (leaf.getOrdering() != null)
        {
            kwargs.put("ordering", ref(leaf.getOrdering()));
        }
        if (leaf.getRegex() != null)
        {
            kwargs.put("regex", new Expr.Lit(Expr.LitKind.STRING, leaf.getRegex()));
        }
        if (leaf.getIncludeEmpty() != null)
        {
            // Fix #121: only the two consistency operators consume the emptiness switch; anywhere
            // else the field would be silently dead, so fail loudly.
            if (!"has_multiple_values_for".equals(op)
                    && !"is_inconsistent_across_dataset".equals(op))
            {
                throw unsupported("operator '" + op + "' does not support include_empty");
            }
            kwargs.put("include_empty", new Expr.Lit(Expr.LitKind.BOOL, leaf.getIncludeEmpty()));
        }
        if (leaf.getKeepMissings() != null)
        {
            // Only the group-aware operators consume the grouping-key disposition; anywhere else
            // the
            // field would be silently dead, so fail loudly (the include_empty precedent above).
            if (!KEEP_MISSINGS_OPERATORS.contains(op))
            {
                throw unsupported("operator '" + op + "' does not support keep_missings");
            }
            kwargs.put("keep_missings", new Expr.Lit(Expr.LitKind.BOOL, leaf.getKeepMissings()));
        }
        if (leaf.getRelation() != null)
        {
            // EC-87: only the next-record operator (and, per the KEEP_MISSINGS_OPERATORS note, its
            // POSITIVE twin — the name this method is actually called with) consumes the relation;
            // anywhere else the field would be silently dead, so fail loudly. The spelling is
            // validated where it is consumed (ExprCompiler) and on the inline surface
            // (RulePackageLoader).
            if (!NextRecordRelation.OPERATORS.contains(op))
            {
                throw unsupported("operator '" + op + "' does not support relation");
            }
            kwargs.put("relation", new Expr.Lit(Expr.LitKind.STRING, leaf.getRelation()));
        }
        return new Expr.Call(op, args, kwargs);
    }

    /**
     * The operators that consume {@code keep_missings} — every operator that forms a group from a
     * key, whether that key comes from {@code within:} or from an array {@code value:}.
     * {@code target_is_not_sorted_by} is handled on its own path (see
     * {@code targetIsNotSortedByLeaf}) and is listed here so the guard covers it too.
     *
     * <p>
     * ⚠⚠ <b>The POSITIVE twin of a negated group operator must be listed too.</b> The guard in
     * {@code functionLeaf} tests the name it is <em>called with</em>, and the Q1 negation pairs
     * call it with the positive twin — {@code does_not_have_next_corresponding_record} raises via
     * {@code functionLeaf("has_next_corresponding_record", …)}. Listing only the negative name
     * therefore rejected a perfectly valid declaration on the negative operator with a message
     * naming an operator the author never wrote. {@code present_on_multiple_rows_within} was
     * already listed for its own sake and so was accidentally fine;
     * {@code has_next_corresponding_record} was not, which made {@code keep_missings} unauthorable
     * on the six shipped {@code does_not_have_next_corresponding_record} rules.
     * {@code RulePackageLoader.CHECK_KEEP_MISSINGS_OPERATORS} — which this set is documented to
     * mirror — already listed it, so the two surfaces disagreed.
     * </p>
     */
    private static final java.util.Set<String> KEEP_MISSINGS_OPERATORS = java.util.Set.of(
            "has_multiple_values_for", "is_inconsistent_across_dataset", "is_not_unique_set",
            "is_unique_set", "present_on_multiple_rows_within",
            "not_present_on_multiple_rows_within", "does_not_have_next_corresponding_record",
            "has_next_corresponding_record", "empty_within_except_last_row",
            "target_is_not_sorted_by", "is_sorted_by");

    /**
     * The {@code [A, B]} column pair for the arithmetic comparison operators
     * ({@code not_equal_to_divide}/{@code subtract}/{@code pctchg}).
     */
    private static Expr[] arithmeticOperands(CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        if (v == null || !v.isArray() || v.size() != 2 || !v.get(0).isTextual()
                || !v.get(1).isTextual())
        {
            throw unsupported(
                    "operator '" + leaf.getOperator() + "' expects a [A, B] column pair value");
        }
        return new Expr[]
        {
                ref(v.get(0).asText()), ref(v.get(1).asText())
        };
    }


    /**
     * {@code X != A <op> B} for {@code not_equal_to_divide} (/) and {@code not_equal_to_subtract}
     * (-).
     */
    private static Expr arithmeticCompare(String name, CheckConditionLeaf leaf, Expr.BinOp arithOp)
    {
        Expr[] ab = arithmeticOperands(leaf);
        return new Expr.Binary(Expr.BinOp.NEQ, ref(name), new Expr.Binary(arithOp, ab[0], ab[1]));
    }


    /** {@code X != ((A - B) / B) * 100} for {@code not_equal_to_pctchg}. */
    private static Expr pctChangeCompare(String name, CheckConditionLeaf leaf)
    {
        Expr[] ab = arithmeticOperands(leaf);
        Expr diff = new Expr.Binary(Expr.BinOp.SUB, ab[0], ab[1]);
        Expr ratio = new Expr.Binary(Expr.BinOp.DIV, diff, ab[1]);
        Expr pct = new Expr.Binary(Expr.BinOp.MUL, ratio, new Expr.Lit(Expr.LitKind.NUMBER, 100.0));
        return new Expr.Binary(Expr.BinOp.NEQ, ref(name), pct);
    }


    /** Raises a JSON array of column-name operands to a list literal of reference operands. */
    private static Expr arrayOperand(JsonNode array)
    {
        List<Expr> items = new ArrayList<>(array.size());
        for (JsonNode e : array)
        {
            items.add(e.isTextual() ? ref(e.asText()) : literal(e));
        }
        return new Expr.Lit(Expr.LitKind.LIST, items);
    }


    /**
     * Raises the {@code within} field to its Expr operand (EC-24). A string field is a single
     * column reference; an array field is a list literal whose entries are either a column
     * reference (a plain string entry) or a nested list literal of column references (a
     * coalesce-group, {@code [[USUBJID, POOLID]]}), so the printed native form carries the
     * nested-list shape as bare {@code --}/column references.
     */
    private static Expr withinOperand(JsonNode within)
    {
        if (!within.isArray())
        {
            return ref(within.asText());
        }
        List<Expr> items = new ArrayList<>(within.size());
        for (JsonNode e : within)
        {
            if (e.isArray())
            {
                List<Expr> group = new ArrayList<>(e.size());
                for (JsonNode g : e)
                {
                    group.add(ref(g.asText()));
                }
                items.add(new Expr.Lit(Expr.LitKind.LIST, group));
            }
            else
            {
                items.add(ref(e.asText()));
            }
        }
        return new Expr.Lit(Expr.LitKind.LIST, items);
    }


    /**
     * Raises case-insensitive membership (Agreed change #3): {@code upper(X) in/not in […]} with
     * each right-hand string term pre-uppercased to a plain string literal
     * ({@code upper(ARM) in ["SCREEN FAILURE", …]}) rather than wrapped in {@code upper("…")}. The
     * fold is exact — the runtime operator uppercases both the cell and the value set with
     * {@code Locale.ROOT} (see {@code resolveStringSet(…, true)}), and {@code upper()} folds the
     * same way — so the pre-folded literal matches the original value's behaviour on every row. The
     * {@code upper(X)} LHS stays (the column is upcased per row). Lowering recovers the uppercased
     * value list; because the operator's value list is intrinsically case-insensitive, the
     * round-trip comparator treats the uppercased terms as equal to the original mixed-case terms
     * (see {@code RulePackageConverterCorpusTest}).
     */
    private static Expr caseInsensitiveMembership(Expr.BinOp op, Expr ref, CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        if (v == null || v.isNull())
        {
            throw unsupported("operator '" + leaf.getOperator() + "' expects a value");
        }
        Expr probe = new Expr.Call("upper", List.of(ref), java.util.Map.of());
        if (!v.isArray())
        {
            // Dynamic membership set: the value is an operation / metadata-operand reference (e.g.
            // library_variable_codelist_coded_values -> var_codelist_coded_values("LIBRARY")), not
            // a
            // literal term list. Reuse the same value() lowering the plain is_(not_)contained_by
            // sibling uses; the upper(X) LHS marks the whole membership case-insensitive, and the
            // engine folds BOTH the probe and the run-time-resolved set
            // (ExprCompiler.compileMembership -> listAccessorSet/buildSet(…,
            // caseInsensitive=true)).
            // No compile-time fold is possible (or needed) because the set is resolved per run.
            return new Expr.Binary(op, probe, value(leaf));
        }
        List<Expr> terms = new ArrayList<>(v.size());
        for (JsonNode e : v)
        {
            if (!e.isTextual())
            {
                throw unsupported("case-insensitive membership terms must be strings");
            }
            terms.add(new Expr.Lit(Expr.LitKind.STRING,
                    e.asText().toUpperCase(java.util.Locale.ROOT)));
        }
        return new Expr.Binary(op, probe, new Expr.Lit(Expr.LitKind.LIST, terms));
    }


    /**
     * Raises {@code target_is_not_sorted_by} to {@code not is_sorted_by(X, by=[asc/desc("col"…)],
     * within=…)}. Each sort descriptor {@code {name, sort_order, null_position}} becomes
     * {@code asc("col")} / {@code desc("col")} with an optional {@code nulls=} kwarg (omitted when
     * the position is the default {@code "last"}).
     *
     * <p>
     * Per plan decision (ii) the round-trip reconstructs the corpus's <em>uniform</em>
     * name/sort_order/null_position descriptor shape: a missing {@code sort_order} defaults to
     * {@code asc}, a missing {@code null_position} to {@code last}, and the order is normalised to
     * lowercase {@code asc}/{@code desc}. This is byte-faithful for the corpus (every descriptor is
     * fully specified, lowercase {@code asc}, {@code null_position} {@code last}); a hypothetical
     * under-specified or mixed-case descriptor would be normalised to this canonical shape rather
     * than preserved verbatim.
     * </p>
     */
    private static Expr sortedByLeaf(String name, CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        if (v == null || !v.isArray())
        {
            throw unsupported("target_is_not_sorted_by expects an array of sort descriptors");
        }
        List<Expr> descriptors = new ArrayList<>(v.size());
        for (JsonNode d : v)
        {
            JsonNode col = d.get("name");
            if (col == null || !col.isTextual())
            {
                throw unsupported("sort descriptor is missing a textual 'name'");
            }
            String order = d.path("sort_order").asText("asc");
            String nulls = d.path("null_position").asText("last");
            String fn = "desc".equalsIgnoreCase(order) ? "desc" : "asc";
            java.util.Map<String, Expr> dkw = "last".equals(nulls) ? java.util.Map.of()
                    : java.util.Map.of("nulls", new Expr.Lit(Expr.LitKind.STRING, nulls));
            descriptors.add(new Expr.Call(fn,
                    List.of(new Expr.Lit(Expr.LitKind.STRING, col.asText())), dkw));
        }
        java.util.Map<String, Expr> kwargs = new java.util.LinkedHashMap<>();
        kwargs.put("by", new Expr.Lit(Expr.LitKind.LIST, descriptors));
        JsonNode within = leaf.getWithin();
        if (within != null && !within.isNull())
        {
            kwargs.put("within", withinOperand(within));
        }
        if (leaf.getKeepMissings() != null)
        {
            kwargs.put("keep_missings", new Expr.Lit(Expr.LitKind.BOOL, leaf.getKeepMissings()));
        }
        return new Expr.Not(new Expr.Call("is_sorted_by", List.of(ref(name)), kwargs));
    }


    private static Expr dateCmp(Expr.BinOp op, String wrapper, String name, CheckConditionLeaf leaf)
    {
        return new Expr.Binary(op, wrap(wrapper, name), value(leaf));
    }


    /**
     * Raises a 1-argument boolean predicate. Defensive: a predicate leaf that unexpectedly carries
     * a {@code value} has no faithful expression surface (the surface drops it), so it is kept
     * old-style rather than silently converted to a value-less predicate.
     */
    private static Expr unaryPredicate(String op, Expr ref, CheckConditionLeaf leaf)
    {
        if (leaf.getValue() != null && !leaf.getValue().isNull())
        {
            throw unsupported("predicate '" + op + "' unexpectedly carries a value");
        }
        return new Expr.Call(op, List.of(ref), java.util.Map.of());
    }


    private static Expr wrap(String fn, String name)
    {
        return new Expr.Call(fn, List.of(ref(name)), java.util.Map.of());
    }


    /**
     * A NAME operand in the generator's preferred quoted form (Phase 5, plan
     * unified-callable-surface): a plain column / dataset name (optionally {@code --}-prefixed)
     * becomes a STRING literal — the engine and {@code ExprLowering} accept both spellings — while
     * anything carrying structure (dots, {@code ${...}} substitution, wildcards, filters) keeps the
     * bare-reference form whose {@link net.cumba.cdisc.core.expr.ast.OperandKind} classification
     * encodes that structure.
     */
    private static Expr nameOperand(String name)
    {
        return name.matches("(--)?[A-Za-z_][A-Za-z0-9_]*") ? new Expr.Lit(Expr.LitKind.STRING, name)
                : ref(name);
    }


    private static Expr call(String fn, Expr... args)
    {
        return new Expr.Call(fn, List.of(args), java.util.Map.of());
    }


    private static Expr ref(String name)
    {
        if (name == null)
        {
            throw unsupported("leaf has no name");
        }
        Expr current = currentVariableCall(name);
        if (current != null)
        {
            return current;
        }
        requirePrintableOperand(name);
        return new Expr.Ref(name, OperandClassifier.classify(name, -1));
    }


    /**
     * Emits the current-variable native function for the two standalone "current variable"
     * operands: {@code variable_name → varname()} (the variable's NAME — a broadcast scalar
     * constant across rows) and {@code variable_value → value()} (the variable's per-row column
     * cells). These mirror the legacy per-variable cascade's two cursor operands and let an
     * operand-based Variable-Metadata-Check / Value-Check-with-Variable-Metadata rule run on the
     * native evaluator (the function reads the same per-column cursor the broadcast /
     * per-variable-row paths set). {@link ExprLowering} reverses them back to the bare operands.
     * Returns {@code null} for any other name. The {@code var_*}/{@code ds_*} accessor anchor is
     * built directly by {@link MetadataOperandMapping#forwardOperand} (not through this helper), so
     * it is unaffected.
     */
    private static @Nullable Expr currentVariableCall(String name)
    {
        if ("variable_name".equals(name))
        {
            return new Expr.Call("varname", List.of(), java.util.Map.of());
        }
        if ("variable_value".equals(name))
        {
            return new Expr.Call("value", List.of(), java.util.Map.of());
        }
        return null;
    }


    /**
     * Like {@link #ref(String)} but prefers the {@code var_*}/{@code ds_*} accessor for a metadata
     * operand (D6). Used only for the <em>plain comparison / membership</em> name and value
     * positions — never inside affix / group / wrapper structures, which require a bare reference.
     */
    private static Expr migratingRef(String name)
    {
        Expr migrated = MetadataOperandMapping.forwardOperand(name);
        return migrated != null ? dropVariableNameAnchor(migrated) : ref(name);
    }


    /**
     * Change #6: a {@code var_<attr>(variable_name, level)} accessor anchored on the implicit
     * current variable drops the {@code variable_name} anchor and uses the engine's level-only
     * overload {@code var_<attr>(level)} (added in Task EB) — mirroring the
     * {@code ds_<attr>(level)} accessors, which already take a bare level. Only the VARIABLE-scope
     * accessor carrying the bare {@code variable_name} anchor is rewritten; the {@code ds_*(level)}
     * form and any other call are returned unchanged. {@link ExprLowering} reverses
     * {@code var_<attr>(level)} to the same metadata operand, so the {@code Check → Expr → Check}
     * round-trip holds.
     */
    private static Expr dropVariableNameAnchor(Expr migrated)
    {
        if (migrated instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 2
                && c.args().get(0) instanceof Expr.Ref r && "variable_name".equals(r.name()))
        {
            return new Expr.Call(c.name(), List.of(c.args().get(1)), java.util.Map.of());
        }
        return migrated;
    }


    /**
     * Rejects an operand the printer cannot emit and re-lex back to the identical name. The printer
     * renders a reference as a bare identifier when possible (including {@code --}/{@code *}
     * wildcard and {@code ${...}} substitution names) and otherwise backtick-quotes it (whitespace
     * and other non-identifier names). An operand that does not survive {@code print → re-lex} as a
     * single {@code IDENT} equal to the original has no faithful surface and stays old-style.
     */
    private static void requirePrintableOperand(String name)
    {
        List<Token> tokens;
        String printed;
        try
        {
            printed = ExpressionPrinter.operand(name);
            tokens = ExpressionLexer.tokenize(printed);
        }
        catch (ExpressionException ex)
        {
            throw unsupported("operand '" + name + "' is not a printable operand");
        }
        if (tokens.size() != 2 || tokens.get(0).type() != TokenType.IDENT
                || tokens.get(1).type() != TokenType.EOF || !name.equals(tokens.get(0).text()))
        {
            throw unsupported("operand '" + name + "' is not a printable operand");
        }
    }


    /**
     * Regex-rule optimisation (Phase 3 of PLAN-regex-rule-optimization): recognises the known cheap
     * regex shapes by their exact {@code (operator, pattern)} key and rewrites the leaf to a scalar
     * predicate (first-character code-point, numeric/integer classification, length bound,
     * duration, substring) instead of a per-row {@code Matcher.find()}. This is a fixed lookup over
     * the finite shipped corpus — not a regex-to-predicate compiler — so any pattern not in the
     * table returns {@code null} and the caller keeps the existing {@code MATCH}/{@code NMATCH}
     * surface.
     *
     * <p>
     * The negated forms are spelled {@code not <positive>(…)} (never a negative-named function),
     * matching the established convention. {@code char(X)} returns a number, so first-character
     * range tests use the inclusive {@code between(char(X), char("A"), char("Z"))}.
     * </p>
     *
     * @param leaf
     *            the regex leaf
     * @param negate
     *            {@code true} for {@code not_matches_regex}, {@code false} for
     *            {@code matches_regex}
     * @param name
     *            the leaf's operand name (used to exclude the broadcast {@code variable_name}
     *            cursor from the first-letter rewrite — CDISC-AD0014 stays as regex)
     * @param ref
     *            the already-built operand expression {@code X}
     * @return the optimised expression, or {@code null} when the pattern is not recognised
     */
    private static @Nullable Expr optimiseRegexLeaf(CheckConditionLeaf leaf, boolean negate,
            String name, Expr ref)
    {
        JsonNode v = leaf.getValue();
        if (v == null || !v.isTextual())
        {
            return null;
        }
        String pattern = v.asText();
        // Phase 5 (broadcast readability): the pure-suffix varname() family
        // (`varname() =~ /…SUF$/` → `ends_with(varname(), "SUF")`) and the dataset-name
        // `^AD` prefix family (`ds_name("DATA") =~ /^AD/` → `starts_with(ds_name("DATA"), "AD")`).
        // Both are once-per-variable / once-per-dataset (broadcast), so this is readability only —
        // no new functions. Gated on the operand so a hypothetical plain-column regex of the same
        // shape is untouched.
        Expr broadcast = optimiseBroadcastRegex(pattern, name, ref, negate);
        if (broadcast != null)
        {
            return broadcast;
        }
        if (!negate)
        {
            return optimiseMatches(pattern, ref);
        }
        return optimiseNotMatches(pattern, name, ref);
    }


    /**
     * Phase 5 broadcast (per-variable / per-dataset) readability rewrites — no new functions.
     *
     * <ul>
     * <li>{@code variable_name} ({@code varname()}) pure-suffix patterns ({@code ^.+FL$},
     * {@code .+DT$}, {@code TM$}, {@code .*DTM$}, …) → {@code ends_with(varname(),
     * "SUF")} ({@code not ends_with(…)} for the negated sense). The literal uppercase suffix is
     * extracted from the pattern; only patterns whose body is purely {@code [A-Z]+} (after an
     * optional {@code ^} and an optional {@code .+}/{@code .*} fill, before a trailing {@code $})
     * are recognised, so the {@code \d{2}} / {@code \d} index patterns (which contain {@code \})
     * and the charset patterns (which contain {@code [}/{@code (}) fall through and stay as
     * regex.</li>
     * <li>{@code dataset_name} ({@code ds_name("DATA")}) {@code ^AD} →
     * {@code starts_with(ds_name("DATA"), "AD")} ({@code not starts_with(…)} negated).</li>
     * </ul>
     *
     * Returns {@code null} for any other operand / pattern.
     */
    private static @Nullable Expr optimiseBroadcastRegex(String pattern, String name, Expr ref,
            boolean negate)
    {
        if ("variable_name".equals(name))
        {
            String suffix = pureSuffix(pattern);
            if (suffix != null)
            {
                Expr endsWith = call("ends_with", ref, strLit(suffix));
                return negate ? new Expr.Not(endsWith) : endsWith;
            }
            return null;
        }
        if ("dataset_name".equals(name) && "^AD".equals(pattern))
        {
            Expr startsWith = call("starts_with", ref, strLit("AD"));
            return negate ? new Expr.Not(startsWith) : startsWith;
        }
        return null;
    }


    /**
     * Extracts the literal uppercase suffix from a pure-suffix regex, or {@code null} when the
     * pattern is not a pure suffix. A pure suffix is an optional leading {@code ^}, an optional
     * {@code .+}/{@code .*} "any prefix" fill, then one or more uppercase ASCII letters
     * ({@code [A-Z]+}), then a trailing {@code $} — and nothing else. The {@code [A-Z]+}-only body
     * is what excludes the {@code \d{2}} / {@code \d} index patterns (a backslash is not
     * {@code [A-Z]}) and the charset / token-class patterns (a {@code [} or {@code (} is not
     * {@code [A-Z]}), so those are left as regex.
     */
    private static @Nullable String pureSuffix(String pattern)
    {
        int i = 0;
        int end = pattern.length();
        if (i < end && pattern.charAt(i) == '^')
        {
            i++;
        }
        if (i + 1 < end && pattern.charAt(i) == '.'
                && (pattern.charAt(i + 1) == '+' || pattern.charAt(i + 1) == '*'))
        {
            i += 2;
        }
        if (end == 0 || pattern.charAt(end - 1) != '$')
        {
            return null;
        }
        end--;
        if (i >= end)
        {
            return null;
        }
        for (int j = i; j < end; j++)
        {
            char c = pattern.charAt(j);
            if (c < 'A' || c > 'Z')
            {
                return null;
            }
        }
        return pattern.substring(i, end);
    }


    /** The {@code matches_regex} recognition table (positive sense). */
    private static @Nullable Expr optimiseMatches(String pattern, Expr ref)
    {
        Expr exact = switch (pattern)
        {
        // Leading-space (CORE-000867, ADAM-ADD-100024): flag a leading ASCII char <= space. The
        // operand is the variable_value / value() cursor; ref already carries it.
        case "^\\s" -> new Expr.And(List.of(new Expr.Not(call("empty", ref)),
                new Expr.Binary(Expr.BinOp.LE, call("char", ref), numLit(32))));
        case "^(.){9,}$" -> new Expr.Binary(Expr.BinOp.GT, call("len", ref), numLit(8));
        case "^(.){21,}$" -> new Expr.Binary(Expr.BinOp.GT, call("len", ref), numLit(20));
        // CORE-000094's loose `^\d*\.?\d*$` is NOT recognised: it has no sign, so is_numeric
        // would newly flag negatives and differs on `1.`/lone-dot — keep it as the regex.
        case "^-?(0|[1-9]\\d*)(\\.\\d+)?$" -> call("is_numeric", ref);
        case "(?i)(AM|PM)" -> new Expr.Or(
                List.of(call("contains", call("upper", ref), strLit("AM")),
                        call("contains", call("upper", ref), strLit("PM"))));
        case "/" -> call("contains", ref, strLit("/"));
        // Has-letter / has-digit (CORE-000169): unanchored find for a letter / a digit.
        case ".*[a-zA-Z].*" -> call("has_alpha", ref);
        case ".*[0-9].*" -> call("has_digit", ref);
        default -> null;
        };
        if (exact != null)
        {
            return exact;
        }
        // Anchored "ends-with" literal on a plain column (`.*<LIT>$`) → the efficient
        // `suffix(X, n) == "<LIT>"` form that `suffix_equal_to` also lowers to — no per-row regex.
        // ONLY the `.*` fill is recognised, because only it is byte-exact: Python's matches_regex
        // is
        // start-anchored `str.match`, so `.*<LIT>$` is exactly "ends with <LIT>" (== suffix==),
        // whereas `.+<LIT>$` additionally requires a leading char (differs on a value equal to the
        // literal) and a bare `<LIT>$` means *equals* the literal (str.match anchors the start).
        // Those two stay regex. The broadcast varname()/ds_name() operands are handled upstream, so
        // this reaches only a plain column. Lets a rule author write a Python-portable regex and
        // still get the native suffix form (CORE-DRAFT-900007).
        String suffix = anchoredEndsWithLiteral(pattern);
        if (suffix != null)
        {
            return new Expr.Binary(Expr.BinOp.EQ, call("suffix", ref, numLit(suffix.length())),
                    strLit(suffix));
        }
        return null;
    }


    /**
     * The uppercase-letter literal {@code <LIT>} of an anchored ends-with pattern
     * {@code ^?.*<LIT>$}, or {@code null} for any other shape. Strict on the {@code .*} fill (see
     * {@link #optimiseMatches}): {@code .+}, a bare suffix, and a non-uppercase body are rejected
     * so the {@code suffix(X, |LIT|) == "<LIT>"} rewrite is byte-exact against Python's
     * start-anchored {@code str.match}.
     */
    private static @Nullable String anchoredEndsWithLiteral(String pattern)
    {
        int i = 0;
        int end = pattern.length();
        if (i < end && pattern.charAt(i) == '^')
        {
            i++;
        }
        // Require the ".*" zero-or-more fill (exact ends-with); ".+" / bare suffix are not this
        // shape.
        if (i + 1 < end && pattern.charAt(i) == '.' && pattern.charAt(i + 1) == '*')
        {
            i += 2;
        }
        else
        {
            return null;
        }
        if (end == i || pattern.charAt(end - 1) != '$')
        {
            return null;
        }
        end--;
        if (i >= end)
        {
            return null;
        }
        for (int j = i; j < end; j++)
        {
            char c = pattern.charAt(j);
            if (c < 'A' || c > 'Z')
            {
                return null;
            }
        }
        return pattern.substring(i, end);
    }


    /** The {@code not_matches_regex} recognition table (negated sense). */
    private static @Nullable Expr optimiseNotMatches(String pattern, String name, Expr ref)
    {
        return switch (pattern)
        {
        // Valid test code (CORE-000220, 000541, 100005, 100009): first char [A-Za-z_], rest
        // [A-Za-z0-9_], length 1..8 (mixed case).
        case "^[a-zA-Z_][a-zA-Z0-9_]{0,7}$" -> new Expr.Not(call("is_valid_testcd", ref));
        // Valid variable name (CORE-000221, 100007): first char [A-Z_], rest [A-Z0-9_], length
        // 1..8 (uppercase only).
        case "^[A-Z_][A-Z0-9_]{0,7}$" -> new Expr.Not(call("is_valid_name", ref));
        case "^-?(0|[1-9]\\d*)(\\.\\d+)?$", "^-?(\\d+(\\.\\d+)?$)|(\\.\\d+$)" -> new Expr.Not(
                call("is_numeric", ref));
        // Whitespace-tolerant integer (CORE-DRAFT-900007): `^\s*[+-]?\d+\s*$` → `not
        // is_integer(X)`.
        // OPT-IN (unlike the strict integer regexes below): this rule's intent IS the lenient,
        // whitespace-trimming is_integer check (a --SEQ IDVARVAL must be a valid integer), and the
        // `\s*` fences let the Python-portable regex agree with is_integer on every clean/padded
        // signed integer — the only IDVARVAL shape a --SEQ reference carries. is_integer is
        // parseDouble-backed (trims, integral), so Java runs the native check while Python runs the
        // regex; they would diverge only on a parseDouble-only form (`5.0`/`1e1`/`5d`) that --SEQ
        // values never take. The `\s*` is essential: without it the strict regex over-fires on the
        // space-padded IDVARVAL that SUPP datasets carry (`" 1"`), where is_integer does not.
        case "^\\s*[+-]?[0-9]+\\s*$", "^\\s*[+-]?\\d+\\s*$" -> new Expr.Not(
                call("is_integer", ref));
        // The strict integer regexes (`^\d+$`, `^[1-9]\d*$`, `^(-?[1-9]\d*|0)$`,
        // `(-?[1-9]\d*|0)$`) are NOT recognised: is_integer is parseDouble-backed and lenient
        // (accepts `5.0`, `1e5`, `+5`, ` 5 `, `5d`, leading zeros) which the strict regexes
        // reject — so these rules (CDISC-AD0169, CORE-000338/000340/000534/000587) keep the regex
        // (FALSE-NEGATIVE risk otherwise, e.g. TAETORD="5.0" should fire but wouldn't).
        // First-letter (CDISC-AD0144). The broadcast varname() form (CDISC-AD0014, operand
        // variable_name) stays as regex (Phase 5 Cast) — return null to fall through.
        case "^[A-Z]" -> "variable_name"
                .equals(name)
                        ? null
                        : new Expr.Not(new Expr.Call("between", List.of(call("char", ref),
                                call("char", strLit("A")), call("char", strLit("Z"))),
                                java.util.Map.of()));
        // Positive-only ISO 8601 duration regex (no leading minus) ⇒ invalid_duration with
        // negative=false pinned. EC-20 flipped the invalid_duration absent-negative default to
        // true (accept the signed grammar), so this canonicalisation must state negative=false
        // explicitly to keep rejecting signed values (e.g. CORE-000779 / CG0376: "TDSTOFF must be
        // 0 or a positive ISO 8601 duration"). Relying on the default would silently accept -P1D.
        case "^P(?=\\d+[YMWD])(\\d+Y)?(\\d+M)?(\\d+W)?(\\d+D)?(T(?=\\d+[HMS])(\\d+H)?(\\d+M)?"
                + "(\\d+S)?)?$" -> new Expr.Call("invalid_duration", List.of(ref),
                        java.util.Map.of("negative", new Expr.Lit(Expr.LitKind.BOOL, false)));
        default -> null;
        };
    }


    private static Expr numLit(double n)
    {
        return new Expr.Lit(Expr.LitKind.NUMBER, n);
    }


    private static Expr strLit(String s)
    {
        return new Expr.Lit(Expr.LitKind.STRING, s);
    }


    private static Expr regex(CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        if (v == null || !v.isTextual())
        {
            throw unsupported("regex operator expects a textual pattern value");
        }
        // The /regex/ surface escapes both '\' (-> \\) and the bare delimiter '/' (-> \/), and the
        // lexer reverses both, so any pattern — including one already containing an escaped slash
        // (\/) — round-trips faithfully.
        return new Expr.Lit(Expr.LitKind.REGEX, v.asText());
    }


    /**
     * The value operand for the substring predicates ({@code contains}/{@code does_not_contain}/
     * {@code starts_with}/{@code ends_with}). The engine resolves these with a literal (the value
     * is intrinsically a literal substring, never a column), so it is emitted as a string literal
     * regardless of any {@code value_is_literal}/{@code value_is_reference} flag — which also lets
     * non-bareword substrings (an ellipsis, a stray brace, embedded whitespace) print and re-lex
     * faithfully. Round-trip fidelity of the (semantically irrelevant) flag is handled by treating
     * these operators as intrinsically literal in the round-trip checks.
     */
    private static Expr substringValue(CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        if (v == null || v.isNull())
        {
            throw unsupported("operator '" + leaf.getOperator() + "' expects a value");
        }
        return literal(v);
    }


    /**
     * The integer-truncated length operand for the {@code has_equal_length}/
     * {@code has_not_equal_length} length comparisons ({@code len(X) == n} / {@code len(X) != n}).
     * The length is read as an int — a missing / null value folds to {@code 0} and any numeric
     * value is truncated to an {@code int} ({@code JsonNode.asInt}) — so the emitted literal
     * mirrors that exactly, keeping the comparison semantics-preserving for the corpus's integer
     * literals.
     */
    private static Expr lengthValue(CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        int n = (v == null || v.isNull()) ? 0 : v.asInt();
        return new Expr.Lit(Expr.LitKind.NUMBER, (double) n);
    }


    private static Expr value(CheckConditionLeaf leaf)
    {
        JsonNode v = leaf.getValue();
        if (v == null || v.isNull())
        {
            throw unsupported("operator '" + leaf.getOperator() + "' expects a value");
        }
        boolean explicitLiteral = Boolean.TRUE.equals(leaf.getValueIsLiteral());
        boolean explicitReference = Boolean.TRUE.equals(leaf.getValueIsReference());
        if (explicitReference && !explicitLiteral)
        {
            // value_is_reference:true is a TWO-HOP dereference (ValueResolver "Fix #6"): the named
            // column's value is itself a column name, read again on the same row. Emit
            // colref(<ref>)
            // to distinguish it from a flag-less single-hop reference. The two-hop applies only to
            // a
            // plain column reference; a metadata operand maps to a var_*/ds_* accessor that already
            // resolves its referenced metadata level, so it is left unwrapped.
            Expr rv = refValue(v);
            return rv instanceof Expr.Ref ? new Expr.Call("colref", List.of(rv), java.util.Map.of())
                    : rv;
        }
        if (explicitLiteral)
        {
            return literal(v);
        }
        // No flag: textual defaults to a single-hop reference (matching the engine's
        // ValueResolver);
        // non-textual values are literals. A standalone current-variable operand
        // (variable_name / variable_value) on the value side maps to varname()/value().
        if (v.isTextual())
        {
            Expr current = currentVariableCall(v.asText());
            return current != null ? current : refValue(v);
        }
        return literal(v);
    }


    private static Expr refValue(JsonNode v)
    {
        if (!v.isTextual())
        {
            throw unsupported("reference value must be textual");
        }
        String text = v.asText();
        requirePrintableOperand(text);
        Expr migrated = MetadataOperandMapping.forwardOperand(text);
        if (migrated != null)
        {
            // value-position metadata operand -> var_*/ds_* accessor (D6); change #6 drops the
            // variable_name anchor to the level-only var_<attr>(level) overload.
            return dropVariableNameAnchor(migrated);
        }
        return new Expr.Ref(text, OperandClassifier.classify(text, -1));
    }


    private static Expr literal(JsonNode v)
    {
        if (v.isTextual())
        {
            return new Expr.Lit(Expr.LitKind.STRING, v.asText());
        }
        if (v.isBoolean())
        {
            return new Expr.Lit(Expr.LitKind.BOOL, v.asBoolean());
        }
        if (v.isNumber())
        {
            return new Expr.Lit(Expr.LitKind.NUMBER, v.asDouble());
        }
        if (v.isArray())
        {
            List<Expr> items = new ArrayList<>(v.size());
            for (JsonNode e : v)
            {
                items.add(literal(e));
            }
            return new Expr.Lit(Expr.LitKind.LIST, items);
        }
        throw unsupported("unsupported literal value shape: " + v.getNodeType());
    }


    private static ExpressionException unsupported(String detail)
    {
        return new ExpressionException(
                "Cannot raise to expression form (kept old-style): " + detail);
    }

}
