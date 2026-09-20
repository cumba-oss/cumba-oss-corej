package net.cumba.corej.core.expr.typed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;

/**
 * The rule's own <b>type expectations</b>, read off the expression alone — the stage-A fact of
 * D76c: <i>"the expectation is read off the expression, so it is a stage-A, dataset-independent
 * fact computed once at load; only which column turns out absent is stage B, per dataset"</i>.
 *
 * <p>
 * ⭐ <b>This walk mirrors the shipped {@code ColumnTypeGate}'s positions, it does not define new
 * ones</b> (D15/D76a — the gate already carries "numeric-expected position" and "character-expected
 * position" verbatim; this class reuses those notions in the one case the gate deliberately skips,
 * the absent column). Each rule below names the gate call site it shadows:
 * </p>
 * <ul>
 * <li><b>NUMERIC</b> — an order-comparison operand ({@code ExprCompiler.compilePlain}), an
 * arithmetic operand and the fused arithmetic-comparison shapes
 * ({@code ExprCompiler.compileArithmeticComparison}), a numeric-literal membership probe
 * ({@code ExprCompiler}'s numeric member set), an equality side whose other side is statically
 * numeric (a numeric literal or a {@code num(...)} conversion — {@code ExprCompiler.staticKind}),
 * and the numeric arguments of {@code between} / {@code abs} / {@code round} / {@code floor} /
 * {@code ceil} and the affix length operand ({@code BuiltinFunctions} / {@code Primitives} hoisted
 * gates).</li>
 * <li><b>CHARACTER</b> — a regex-match subject ({@code ExprCompiler.compileRegex}), a
 * string-literal membership probe, and an equality side whose other side is a string literal.</li>
 * <li><b>ISO_TEXT</b> — the argument of a {@code date(...)} / {@code time(...)} conversion: an
 * ISO-8601 <em>text</em> read (D55's shape when the column turns out numeric; the stage-B
 * classification of {@code ColumnTypeGate.observeIsoConversionRead}).</li>
 * </ul>
 *
 * <p>
 * Expectations attach to <b>bare column operands</b> ({@link OperandKind#COLUMN}) and to <b>dotted
 * cross-dataset references</b> ({@link OperandKind#DOTTED_REF}), under the dotted operand's FULL
 * name: a {@code $}-reference, a computed value, a {@code ${...}} template and a
 * {@code num(...)}-wrapped operand never carry one, and a comparison in the {@code date} /
 * {@code time} / {@code date_part} / {@code time_part} families routes past the plain gate entirely
 * and therefore sets no expectation here either. Presence probes ({@code var_exists} family) are
 * <b>not value reads</b>: their argument names a column whose absence is the question, so it is
 * excluded from {@link #valueReadColumns()}.
 * </p>
 *
 * <p>
 * ⭐⭐ <b>The dotted form was excluded until 2026-09-18, and that exclusion WAS the defect</b> (owner
 * ruling, {@code PLAN-null-free-value-channel} §3a: <i>"a dotted variable should behave like a
 * first-class variable, so if the variable is absent and the expression expects a numeric variable,
 * then use {@code MissingValue.MIS} as default value"</i> — {@code D72}'s <i>"a merged column
 * behaves in EVERY respect like a primary column"</i> applied to the dotted form). ⚠ The asymmetry
 * that remains is deliberate and is NOT the old exclusion in disguise: a dotted name carries an
 * {@link #expectationsOf(String) expectation} and appears in {@link #numericDefaultColumns()}, but
 * it is kept out of {@link #valueReadColumns()} and out of {@link #equalityPairs()} — both of those
 * are resolved against the PRIMARY dataset's metadata by {@code StageBChecker}, which cannot see a
 * joined dataset's columns at all. See {@code expect}.
 * </p>
 *
 * <p>
 * ⚠ Because plans evaluate lazily, the gate only fires on plans that are actually evaluated, while
 * this static walk sees every position — so it can over-approximate what the gate raises (never the
 * reverse for the covered positions). That is exactly why the stage-B checker's
 * {@code COLUMN_TYPE_MISMATCH} kind stays observe-only (D15: the <b>armed</b> half is the gate
 * itself, at {@code RuleRunner}'s single catch site — D74a).
 * </p>
 */
public final class TypeExpectations
{

    /** A typed read position, in the {@code ColumnTypeGate}'s own vocabulary (D76a). */
    public enum Expectation
    {
        /** The gate's "numeric-expected position" (its {@code requireNumericRead}). */
        NUMERIC,
        /** The gate's "character-expected position" (its {@code requireCharacterRead}). */
        CHARACTER,
        /**
         * A {@code date()}/{@code time()} conversion argument — an ISO-8601 text read (D55's
         * observe-only shape; {@code ColumnTypeGate.observeIsoConversionRead}). Counts as
         * "otherwise" (⇒ character) for the D76 absent-column default.
         */
        ISO_TEXT
    }


    /** A plain {@code ==}/{@code !=} between two bare columns: their kinds must agree (R4). */
    public record EqualityPair(String left, String right)
    {
    }

    /** Positional numeric arguments per function name, mirroring the hoisted builtin gates. */
    private static final Map<String, int[]> NUMERIC_ARGS = Map.of("between", new int[]
    {
            0, 1, 2
    }, "abs", new int[]
    {
            0
    }, "round", new int[]
    {
            0
    }, "floor", new int[]
    {
            0
    }, "ceil", new int[]
    {
            0
    }, "prefix", new int[]
    {
            1
    }, "suffix", new int[]
    {
            1
    });

    /** Call names that route a comparison into the temporal families (no plain gate). */
    private static final Set<String> TEMPORAL_CALLS = Set.of("date", "time", "date_part",
            "time_part");

    private final Map<String, EnumSet<Expectation>> expectations = new LinkedHashMap<>();

    private final List<EqualityPair> equalityPairs = new ArrayList<>();

    private final Set<String> valueReadColumns = new LinkedHashSet<>();

    private TypeExpectations()
    {
    }


    /** Computes the expectations of the given expression roots (a rule's levels, precondition). */
    public static TypeExpectations of(Collection<Expr> roots)
    {
        TypeExpectations te = new TypeExpectations();
        for (Expr root : roots)
        {
            te.walk(root);
        }
        return te;
    }


    /** The expectations recorded for one column name (empty set when none). */
    public Set<Expectation> expectationsOf(String column)
    {
        EnumSet<Expectation> set = expectations.get(column);
        return set == null ? Set.of() : Set.copyOf(set);
    }


    /** Every column name carrying at least one expectation, in first-seen order. */
    public Set<String> expectedColumns()
    {
        return Set.copyOf(expectations.keySet());
    }


    /**
     * The plain column-vs-column equality pairs (both kinds must agree at stage B). ⛔ <b>Bare
     * primary columns only</b> — a dotted side is excluded because the pair check reads both sides'
     * DECLARED kinds off the primary dataset's metadata, which holds no entry for a joined
     * dataset's column.
     */
    public List<EqualityPair> equalityPairs()
    {
        return List.copyOf(equalityPairs);
    }


    /**
     * Every bare, foldable column name read in a value position (presence-probe arguments excluded)
     * — the candidates for the D34 #3/#4 absent-column fold over the PRIMARY dataset, whose stage-B
     * default type D76 derives from {@link #expectationsOf(String)}.
     *
     * <p>
     * ⛔ <b>Dotted cross-dataset references are deliberately absent from this set</b>, although they
     * do carry expectations. Its one production consumer, {@code StageBChecker.checkColumnTypes},
     * probes every member with {@code meta.getColumnIndex(...)} against the primary dataset's
     * metadata and records a {@code < 0} answer as an {@code ABSENT_COLUMN} finding; a dotted name
     * is never a primary column name, so admitting one here would file a false absent-column
     * finding for every dotted reference in every rule. A dotted reference's absence is a
     * joined-dataset fact the stage-B seam cannot observe.
     * </p>
     */
    public Set<String> valueReadColumns()
    {
        return Set.copyOf(valueReadColumns);
    }


    /**
     * The D76 default type of an absent column under this rule's expectations: {@code number}
     * (missing ⇒ {@code MissingValue.MIS}) when any position expects numeric, otherwise
     * {@code string} (missing ⇒ {@code ""}) — "otherwise" deliberately absorbing
     * character-expected, ISO-text and no-expectation alike (D76a).
     *
     * <p>
     * {@code column} is a bare primary column name, or a dotted reference's FULL name
     * ({@code DM.AGE}) — the dotted form answers here exactly as a first-class one does, which is
     * the owner's 2026-09-18 ruling.
     * </p>
     */
    public boolean numericExpected(String column)
    {
        return expectationsOf(column).contains(Expectation.NUMERIC);
    }


    /**
     * The columns whose D76 absent-column default is <b>numeric</b> ({@code MissingValue.MIS}) —
     * every column {@link #numericExpected(String)} answers {@code true} for, in first-seen order.
     * This is the set the <em>engine's</em> absent-column value fold reads off the
     * {@code EvaluationContext} ({@code ExprCompiler.valueRefPlan}): a name in it folds to
     * all-{@code MissingValue.MIS} exactly as before D76 landed; every other absent column folds to
     * all-{@code ""} (D34 #3/#4 — "otherwise char" absorbs character-expected, ISO-text and
     * no-expectation alike, D76a).
     *
     * <p>
     * ⚑ TARGET-INVARIANT(null-free-value-channel) — <b>this set now also carries DOTTED names</b>
     * ({@code DM.AGE}), because {@code expect} records the dotted form's expectation as of
     * 2026-09-18. The engine's dotted not-supplied arms ({@code ExprCompiler.dottedVector},
     * {@code ExprCompiler.substitutedScalarCell}) are the readers they are FOR, and until
     * {@code PLAN-null-free-value-channel} phase 5 lands they do <b>not</b> read them: this is the
     * recording half only, so no verdict moves from it. ⚠ A dotted entry cannot perturb the four
     * existing BARE-name readers of this set — every dotted name carries a {@code '.'} and
     * {@code BroadcastFold.isFoldableColumnReference} rejects every bare name that does, so the two
     * namespaces are disjoint by construction.
     * </p>
     */
    public Set<String> numericDefaultColumns()
    {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, EnumSet<Expectation>> e : expectations.entrySet())
        {
            if (e.getValue().contains(Expectation.NUMERIC))
            {
                out.add(e.getKey());
            }
        }
        return Set.copyOf(out);
    }

    // ------------------------------------------------------------------
    // The walk
    // ------------------------------------------------------------------


    private void walk(Expr e)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(this::walk);
        case Expr.Or o -> o.parts().forEach(this::walk);
        case Expr.Not n -> walk(n.inner());
        case Expr.Binary b -> binary(b);
        case Expr.Lit lit -> literalItems(lit).forEach(this::walk);
        case Expr.Ref r -> ref(r);
        case Expr.Call c -> call(c);
        }
    }


    private void binary(Expr.Binary b)
    {
        Expr left = stripStr(b.left());
        Expr right = stripStr(b.right());
        switch (b.op())
        {
        case ADD, SUB, MUL, DIV ->
        {
            // Arithmetic is numeric per se (R3/R4; ExprCompiler.compileArithmeticComparison).
            expect(left, Expectation.NUMERIC);
            expect(right, Expectation.NUMERIC);
        }
        case LT, GT, LE, GE ->
        {
            if (!temporalFamily(left) && !temporalFamily(right))
            {
                // compilePlain's order branch: both sides numeric regardless of the other.
                expect(left, Expectation.NUMERIC);
                expect(right, Expectation.NUMERIC);
            }
        }
        case EQ, NEQ ->
        {
            if (!temporalFamily(left) && !temporalFamily(right))
            {
                if (isArithmetic(left) || isArithmetic(right))
                {
                    // The fused X != A/B shapes: name, a and b are all numeric reads.
                    expect(left, Expectation.NUMERIC);
                    expect(right, Expectation.NUMERIC);
                }
                else
                {
                    equality(left, right);
                }
            }
        }
        case MATCH, NMATCH ->
                // compileRegex: the subject asserts a property of the TEXT form (R9).
                expect(left, Expectation.CHARACTER);
        case IN, NOT_IN -> membership(left, right);
        }
        walk(b.left());
        walk(b.right());
    }


    private void equality(Expr left, Expr right)
    {
        Expectation fromRight = staticKind(right);
        Expectation fromLeft = staticKind(left);
        if (fromRight != null)
        {
            expect(left, fromRight);
        }
        if (fromLeft != null)
        {
            expect(right, fromLeft);
        }
        String lc = bareColumn(left);
        String rc = bareColumn(right);
        if (lc != null && rc != null)
        {
            equalityPairs.add(new EqualityPair(lc, rc));
        }
    }


    private void membership(Expr left, Expr right)
    {
        if (expectationKey(left) == null || !(right instanceof Expr.Lit lit)
                || lit.kind() != Expr.LitKind.LIST)
        {
            // Only a STATIC list literal states an expectation the gate holds a probe to
            // (mirroring numericMemberSet / isAllStringList's literal-only classification); a
            // list-LHS membership (D81d) and every dynamic set stay out.
            return;
        }
        Expr.LitKind element = null;
        for (Expr item : literalItems(lit))
        {
            if (!(item instanceof Expr.Lit el)
                    || (el.kind() != Expr.LitKind.NUMBER && el.kind() != Expr.LitKind.STRING)
                    || (element != null && element != el.kind()))
            {
                return; // mixed / non-literal members never gate
            }
            element = el.kind();
        }
        if (element == Expr.LitKind.NUMBER)
        {
            expect(left, Expectation.NUMERIC);
        }
        else if (element == Expr.LitKind.STRING)
        {
            expect(left, Expectation.CHARACTER);
        }
    }


    private void ref(Expr.Ref r)
    {
        if (r.kind() == OperandKind.COLUMN && BroadcastFold.isFoldableColumnReference(r.name()))
        {
            valueReadColumns.add(r.name());
        }
    }


    private void call(Expr.Call c)
    {
        String name = c.name();
        if (BroadcastFold.isExistsCall(c))
        {
            // A presence probe's argument is not a value read: its absence is the QUESTION, so
            // it neither joins the absent-fold candidates nor carries an expectation.
            return;
        }
        if (("date".equals(name) || "time".equals(name)) && c.args().size() == 1)
        {
            expect(c.args().get(0), Expectation.ISO_TEXT);
        }
        int[] numericArgs = NUMERIC_ARGS.get(name);
        if (numericArgs != null)
        {
            for (int i : numericArgs)
            {
                if (i < c.args().size())
                {
                    expect(c.args().get(i), Expectation.NUMERIC);
                }
            }
        }
        c.args().forEach(this::walk);
        c.kwargs().values().forEach(this::walk);
    }


    /**
     * Records an expectation when the operand carries one — a bare foldable column
     * ({@link OperandKind#COLUMN}) or a dotted cross-dataset reference
     * ({@link OperandKind#DOTTED_REF}); {@code num(...)}-wrapped, {@code $}-referenced, templated
     * and computed operands never carry one (the gate's eligibility, §10 F9).
     *
     * <p>
     * ⭐⭐ <b>The dotted form records its expectation too</b> — {@code D72} applied to the dotted
     * form (owner ruling, 2026-09-18: <i>"a dotted variable should behave like a first-class
     * variable, so if the variable is absent and the expression expects a numeric variable, then
     * use {@code MissingValue.MIS} as default value"</i>, and <i>"if a dotted ref is used like
     * {@code DM.AGE > 30} then this should record the expected type to be numeric"</i>). A
     * first-class variable records its expectation, so a merged one must: excluding
     * {@code DOTTED_REF} <em>was</em> the defect, and the engine's dotted not-supplied arms
     * ({@code ExprCompiler.dottedVector} and {@code ExprCompiler.substitutedScalarCell}) read the
     * recorded expectation back under the operand's FULL dotted name.
     * </p>
     *
     * <p>
     * ⛔ <b>A dotted name joins {@link #expectations} only — never {@link #valueReadColumns()}.</b>
     * That set is the PRIMARY dataset's absent-column fold candidate set, and
     * {@code StageBChecker.checkColumnTypes} probes each member with
     * {@code meta.getColumnIndex(...)} against the primary metadata. A dotted name is never a
     * primary column name, so admitting it there would report EVERY dotted reference in EVERY rule
     * as an {@code ABSENT_COLUMN} finding — a false positive, not a defect. A dotted reference's
     * absence is decided by the joined-dataset map at evaluation time, which is exactly where
     * {@link #numericExpected(String)} is consulted.
     * </p>
     */
    private void expect(Expr e, Expectation expectation)
    {
        Expr operand = stripStr(e);
        String name = expectationKey(operand);
        if (name == null)
        {
            return;
        }
        expectations.computeIfAbsent(name, _ -> EnumSet.noneOf(Expectation.class)).add(expectation);
        if (bareColumn(operand) != null)
        {
            valueReadColumns.add(name);
        }
    }


    /**
     * The expectation key of an operand: a bare foldable column's name, else a dotted cross-dataset
     * reference's FULL name ({@code DM.AGE}), else {@code null}. These are the two operand shapes
     * that carry a type expectation — {@code D72}'s <i>"a merged column behaves in EVERY respect
     * like a primary column"</i>.
     */
    private static @org.jspecify.annotations.Nullable String expectationKey(Expr e)
    {
        String bare = bareColumn(e);
        return bare != null ? bare : dottedColumn(e);
    }


    /**
     * A dotted cross-dataset reference's full name ({@code DM.AGE}), or {@code null} when the
     * operand is not one.
     *
     * <p>
     * ⚠ Deliberately NOT routed through {@code BroadcastFold.isFoldableColumnReference}: that
     * predicate rejects every name containing a {@code '.'} by construction, so applying it here
     * would keep the exclusion this method exists to remove. {@link OperandKind#DOTTED_REF}
     * <em>is</em> the eligibility — {@code OperandClassifier}'s pattern admits exactly
     * {@code [A-Z][A-Z0-9]*\.[A-Z][A-Z0-9_]*}, so such a name always carries its one dot and never
     * a wildcard, a {@code $}, or a lowercase engine-metadata bareword. That the dot is always
     * present is load-bearing for the four {@code ctx.getNumericExpectedColumns().contains(name)}
     * readers over BARE names: a dotted entry can never collide with a bare one.
     * </p>
     */
    private static @org.jspecify.annotations.Nullable String dottedColumn(Expr e)
    {
        return e instanceof Expr.Ref r && r.kind() == OperandKind.DOTTED_REF ? r.name() : null;
    }


    private static @org.jspecify.annotations.Nullable String bareColumn(Expr e)
    {
        return e instanceof Expr.Ref r && r.kind() == OperandKind.COLUMN
                && BroadcastFold.isFoldableColumnReference(r.name()) ? r.name() : null;
    }


    /**
     * {@code ExprCompiler.staticKind}'s mirror: num-literal / num() ⇒ NUMERIC, string literal ⇒
     * CHARACTER.
     */
    private static @org.jspecify.annotations.Nullable Expectation staticKind(Expr e)
    {
        if (e instanceof Expr.Call c && "num".equals(c.name()))
        {
            return Expectation.NUMERIC;
        }
        if (e instanceof Expr.Lit lit)
        {
            return switch (lit.kind())
            {
            case NUMBER -> Expectation.NUMERIC;
            case STRING -> Expectation.CHARACTER;
            default -> null;
            };
        }
        return null;
    }


    /** Whether the operand routes the comparison into a temporal family (never the plain gate). */
    private static boolean temporalFamily(Expr e)
    {
        return e instanceof Expr.Call c && TEMPORAL_CALLS.contains(c.name());
    }


    private static boolean isArithmetic(Expr e)
    {
        return e instanceof Expr.Binary b && switch (b.op())
        {
        case ADD, SUB, MUL, DIV -> true;
        default -> false;
        };
    }


    /** Strips the one remaining erased mode tag, {@code str(...)} (D91f/D97c). */
    private static Expr stripStr(Expr e)
    {
        return e instanceof Expr.Call c && "str".equals(c.name()) && c.args().size() == 1
                ? c.args().get(0)
                : e;
    }


    @SuppressWarnings("unchecked")
    private static List<Expr> literalItems(Expr.Lit lit)
    {
        return lit.kind() == Expr.LitKind.LIST ? (List<Expr>) lit.value() : List.of();
    }

}
