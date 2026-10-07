package net.cumba.corej.core.expr.eval;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Outcome;
import net.cumba.datatable.ColumnContent;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * The present-but-empty column <b>collapse</b> ({@code PLAN-present-empty-column-fold} §4.3, owner
 * D5/D6): a record-level rule that declares {@code Outcome.Message_Folded} reports its per-row
 * findings as <b>one</b> dataset-level finding when an {@code empty}-family leaf of its Check reads
 * a present column whose content makes that leaf row-constant — the column is blank on every row,
 * so the leaf answers the same on every row and the rule's verdict is a fact about the dataset, not
 * about a record (the owner's example: {@code empty(AEBDSYCD)} over an AE whose {@code AEBDSYCD} is
 * empty on all 961 records is one finding, not 961).
 *
 * <p>
 * The <b>data-time</b> decision only ({@link #foldableColumns}); the load-time classifier (C7 R2)
 * lives in {@link FoldCapability} since {@code PLAN-message-folded-corpus-alignment} (row 1).
 * </p>
 *
 * <p>
 * <b>Truth table — when an {@code empty}-family leaf over a present column X is row-constant</b>
 * (fill / uniformity as {@link ColumnContent}; {@code is_missing} is strictly {@code MissingValue},
 * register {@code D50}, so {@code ""} is not missing):
 * </p>
 * <table>
 * <caption>foldable leaves</caption>
 * <tr>
 * <th>leaf</th>
 * <th>FILLED</th>
 * <th>EMPTY + SINGLE_VALUE (all "")</th>
 * <th>EMPTY + MULTI_VALUES ("" mixed with missing)</th>
 * <th>MISSING</th>
 * </tr>
 * <tr>
 * <td>{@code empty(X)}</td>
 * <td>no</td>
 * <td>TRUE</td>
 * <td>TRUE</td>
 * <td>TRUE</td>
 * </tr>
 * <tr>
 * <td>{@code non_empty(X)} / {@code present(X)} / {@code is_present(X)} / {@code not empty(X)}</td>
 * <td>no</td>
 * <td>FALSE</td>
 * <td>FALSE</td>
 * <td>FALSE</td>
 * </tr>
 * <tr>
 * <td>{@code is_missing(X)}</td>
 * <td>no</td>
 * <td>FALSE</td>
 * <td><b>no</b> (row-dependent)</td>
 * <td>TRUE</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>An ABSENT column of an {@code empty}-family leaf is a trigger too</b>
 * ({@code PLAN-message-folded-corpus-alignment} P3, C7 D9 read literally: the sister retirement
 * <i>"makes the collapse treat an absent X like MISSING"</i>). The absent column reads as
 * all-missing on every row (D34 #3/#4, EC-43 / D76), so {@code empty(X)} over it is row-constant
 * TRUE exactly as over a column of fill {@code MISSING}, and the leaf's constant is folded into the
 * per-row verdict as D5's residual asks. ⛔ <b>Any other operator over an absent column stays per
 * row</b> (today's D111 shape): the plan's wider form — every leaf the level calculus folds — was
 * <em>not</em> taken, because it turns an absent <em>guard</em> column ({@code X != "N"} in an
 * {@code or}-branch, a qualifier the domain never carries) into one dataset finding for defects the
 * other conjuncts locate per record, which only a Type 1 verdict per column can rule (authoring
 * guideline §4.4, Q1). Only when the whole Check was left {@code UNKNOWN} by {@link BroadcastFold}
 * does the collapse see it. The calculus is <b>read</b>, never changed.
 * </p>
 *
 * <p>
 * <b>Why a reporting step equals D5.</b> The row evaluation of the full Check computes, on every
 * row, exactly D5's residual with the constant substituted: residual constant FALSE ⇔ no row fires
 * ⇒ 0 findings; constant TRUE ⇔ every row fires ⇒ 1 finding; row-dependent ⇒ 1 finding iff some row
 * fires. No partial evaluator, no second evaluation. A dotted {@code J.X} or SUPP-qualifier
 * argument of a <em>present</em>-column leaf is not a column of the evaluation table and never
 * triggers on its content (R5); a column named in {@code Keep_Row_Findings} never triggers (R1,
 * D9), absent or present; a 0-row table never collapses (C5).
 * </p>
 *
 * <p>
 * <b>Only a polarity-live leaf triggers, absent or present</b> (owner ruling 2026-10-07,
 * {@code PLAN-message-folded-corpus-alignment} fold part 2, after the CG0114 walkthrough): a
 * collapse happens only on a column whose emptiness DECIDES the rule — its leaf's constant, negated
 * once per enclosing {@code Not}, is TRUE — and never because some other column is absent. A column
 * PRESENT and blank on every record and a column ABSENT trigger alike; {@code not empty(X)} over a
 * blank or absent {@code X} is FALSE, decides nothing the collapse may report, and keeps the row
 * path (CG0114: an absent CMDOSTXT is no trigger, only --DOSU is). An absent column of a non-live
 * leaf folds, inside a collapse another column triggered, to its absent-column constant (P3).
 * </p>
 */
public final class EmptyColumnCollapse
{

    /**
     * The {@code empty} family: the leaves decided from a column's fill. {@code not empty(X)} is
     * {@code Not} over {@code empty}; the others are {@code empty}'s negation or, for
     * {@code is_missing}, the strict missing test ({@code D50}).
     */
    public static final Set<String> EMPTY_FAMILY = Set.of("empty", "is_missing", "non_empty",
            "present", "is_present");

    private EmptyColumnCollapse()
    {
    }


    /**
     * The columns whose {@code empty}-family leaves are row-constant on this evaluation table — the
     * truth table of the class javadoc over a present column, all-missing over an absent one (P3) —
     * and therefore make the collapse apply. Empty when nothing folds (the rule takes today's
     * per-row path).
     *
     * @param check
     *            the specialised Check of this execution (names concrete)
     * @param ctx
     *            the evaluation context; its table is the evaluation table (a merged view where one
     *            is installed), never the raw primary
     * @param keepRowFindings
     *            the resolved {@code Keep_Row_Findings} names ({@link #resolveKeepRowFindings})
     * @return the folded columns, sorted, as named in the Check
     */
    public static Set<String> foldableColumns(Expr check, EvaluationContext ctx,
            Set<String> keepRowFindings)
    {
        if (ctx.rowCount() <= 0)
        {
            return new TreeSet<>(); // C5: a 0-row table never collapses
        }
        LiveLeaves live = new LiveLeaves();
        collectFoldable(check, ctx, keepRowFindings, live);
        // ⭐ Owner refinement 2026-10-07: a collapse only when EVERY polarity-live leaf is decided —
        // its column absent or blank on every record. One live leaf still varying per record keeps
        // the rule per row (FDA-SD0021 on AE: AEENRF / AEENRTPT / AEDUR absent, AEENDTC filled on
        // some records → per row, count unchanged; on MH every end column absent → one finding).
        return live.allDecided() ? new TreeSet<>(live.columns) : new TreeSet<>();
    }


    /**
     * Whether every polarity-live {@code empty}-family leaf of {@code check} is decided on
     * {@code ctx}'s table (owner refinement 2026-10-07) — the test the absent-column fold applies
     * before it reports an opted-in rule's absent-decided TRUE once ({@code RuleRunner}), the same
     * one {@link #foldableColumns} applies to the collapse. A Check without a live leaf answers
     * {@code true} (nothing left to vary: the fold decided through other operators over the absent
     * column, D111).
     *
     * @param check
     *            the compiled Check
     * @param ctx
     *            the evaluation context
     * @param keepRowFindings
     *            the resolved {@code Keep_Row_Findings} names
     * @return whether every live leaf is decided
     */
    public static boolean everyLiveLeafDecided(Expr check, EvaluationContext ctx,
            Set<String> keepRowFindings)
    {
        LiveLeaves live = new LiveLeaves();
        collectFoldable(check, ctx, keepRowFindings, live);
        // vacuously true without a live leaf: the fold then decided through other operators over
        // the absent column (`X != "literal"`, D111) and there is no live leaf left to vary
        return live.decided == live.live;
    }

    /**
     * The polarity-live {@code empty}-family leaves of a Check and the ones decided (their column
     * absent or blank on every record, the leaf's effective constant TRUE): the collapse reports
     * iff {@link #allDecided()}, naming {@link #columns}.
     */
    static final class LiveLeaves
    {

        /** Polarity-live leaves seen (a leaf counts once per occurrence). */
        int live;

        /** Live leaves decided TRUE-effective. */
        int decided;

        /** The decided leaves' columns, as named in the Check. */
        final Set<String> columns = new LinkedHashSet<>();

        boolean allDecided()
        {
            return live > 0 && decided == live;
        }
    }

    private static void collectFoldable(Expr e, EvaluationContext ctx, Set<String> keep,
            LiveLeaves out)
    {
        collectLive(e, false, keep, (c, name) ->
        {
            ColumnContent content = presentColumnContent(ctx, name);
            if (content != null)
            {
                // a present column triggers on its content (R5)
                return rowConstant(c.name(), content)
                        ? (leafConstant(c.name(), content) ? BroadcastFold.Verdict.TRUE
                                : BroadcastFold.Verdict.FALSE)
                        : BroadcastFold.Verdict.UNKNOWN;
            }
            // ⭐ A column ABSENT from the evaluation table triggers exactly like one blank on every
            // record — iff its leaf is polarity-live (owner, 2026-10-07, after the CG0114
            // walkthrough: a collapse happens only on a column whose emptiness DECIDES the rule,
            // never because some other column is absent). The leaf's constant is the one the
            // absent-column contract gives it: empty TRUE; is_missing TRUE only where the column
            // reads MIS (numeric-expected, D76) and FALSE where it reads ""; the negated family
            // FALSE — so `not empty(CMDOSTXT)` over an absent CMDOSTXT decides nothing.
            return BroadcastFold.bindColumnLevel(name,
                    ctx) == BroadcastFold.BindColumnLevel.DATASET_ABSENT
                            ? absentLeafConstant(c.name(), name, ctx)
                            : BroadcastFold.Verdict.UNKNOWN;
        },
                // a branch's dataset-constant verdict from presence and dataset facts only —
                // absent-blind, so a column's absence never kills a branch here (owner
                // 2026-10-07: never because another column is absent)
                branch -> BroadcastFold.fold(branch, ctx, false, true), out);
    }


    /**
     * The polarity-aware trigger walk, shared with the census ({@code FoldCapability.triggers}): a
     * plain {@code empty}-family leaf over a column outside {@code keep} is a trigger iff
     * {@code constantOf} answers its truth-table constant ({@code UNKNOWN}: not row-constant) and
     * that constant, negated once per enclosing {@code Not}, is TRUE — the leaf contributes TRUE on
     * every row (polarity-live). A leaf constant FALSE decides nothing the collapse may report — in
     * an {@code And} it kills the rule (no row fires either way), in an {@code Or} the other branch
     * decides per row — so it never triggers (PACK 4 review HIGH-1, polarity). Leaves under a
     * quantifier or inside a target-table reader are never triggers (R5's spirit, MED-2).
     *
     * <p>
     * <b>A leaf in a dead branch is not live</b> (PACK 4 confirmation look MED-1, by the owner's
     * 2026-10-07 ruling): a leaf is live only where blanking its column can change the Check's
     * verdict. An {@code And} with a part {@code branchConstant} answers FALSE is FALSE whatever
     * its leaves read, an {@code Or} with a part answering TRUE is TRUE — their leaves are skipped:
     * neither triggers nor blocks. The CDISC-CG0655 / FDA-SD1017 shape:
     * {@code var_exists("SVPRESP") and SVPRESP == "Y" or not var_exists("SVPRESP") and
     * empty(SVUPDES)} with SVPRESP present must not collapse on an absent SVUPDES.
     * </p>
     *
     * @param e
     *            the (sub)expression
     * @param negated
     *            whether an odd number of {@code Not}s encloses {@code e}
     * @param keep
     *            the resolved {@code Keep_Row_Findings} names
     * @param constantOf
     *            (leaf call, plain column name) → the leaf's constant, or {@code UNKNOWN}
     * @param branchConstant
     *            a combinator part → its verdict where it is the same for every record of every
     *            dataset this caller reasons about (presence and dataset facts), else
     *            {@code UNKNOWN}
     * @param out
     *            the trigger columns, as named in the Check
     */
    static void collectLive(Expr e, boolean negated, Set<String> keep,
            java.util.function.BiFunction<Expr.Call, String, BroadcastFold.Verdict> constantOf,
            java.util.function.Function<Expr, BroadcastFold.Verdict> branchConstant, LiveLeaves out)
    {
        switch (e)
        {
        case Expr.Not not -> collectLive(not.inner(), !negated, keep, constantOf, branchConstant,
                out);
        case Expr.And and ->
        {
            // MED-1: a part constant FALSE makes the And FALSE whatever its leaves read (a dead
            // branch) — none of its leaves can change the verdict, none is live
            if (and.parts().stream()
                    .noneMatch(p -> branchConstant.apply(p) == BroadcastFold.Verdict.FALSE))
            {
                and.parts().forEach(
                        p -> collectLive(p, negated, keep, constantOf, branchConstant, out));
            }
        }
        case Expr.Or or ->
        {
            // ... and a part constant TRUE makes the Or TRUE whatever its leaves read
            if (or.parts().stream()
                    .noneMatch(p -> branchConstant.apply(p) == BroadcastFold.Verdict.TRUE))
            {
                or.parts().forEach(
                        p -> collectLive(p, negated, keep, constantOf, branchConstant, out));
            }
        }
        case Expr.Call c ->
        {
            if (net.cumba.corej.core.exec.JoinedRowFold.isQuantifier(c.name())
                    || readsAnotherTable(c))
            {
                // Never a trigger: a leaf under any / all / none over joined(…) is evaluated per
                // joined row of the merged table — a column that IS a column of the evaluation
                // table, but the quantifier's verdict depends on the row set it folds, not on the
                // leaf alone (kept out deliberately; R5's spirit) — and a leaf inside a
                // target-table reader names the other table's column (MED-2).
                return;
            }
            String name = plainColumnLeaf(c);
            if (name == null)
            {
                // L6 (conservative): a leaf inside another call's arguments is not in boolean
                // position of the Check and is never live.
                return;
            }
            if (FoldCapability.containsIgnoreCase(keep, name))
            {
                // a Type 1 column (Keep_Row_Findings) is not counted as live: blank or absent it
                // never triggers, filled on some record it never blocks the other live leaves'
                // collapse (the FDA-SD1320 shape)
                return;
            }
            // the leaf's polarity: empty / is_missing are TRUE over an empty column, the rest
            // FALSE; negated once per enclosing Not — only a TRUE-polarity leaf is live
            boolean positive = "empty".equals(c.name()) || "is_missing".equals(c.name());
            if (positive == negated)
            {
                return; // `not empty(X)`, `non_empty(X)`: FALSE over an empty X, decides nothing
            }
            // ⚑ A live leaf plays two roles (PACK 4 confirmation LOW-5): DECIDED (its column absent
            // or blank on every record, constant TRUE-effective) it is a trigger and is named;
            // UNDECIDED (the column filled on some record, or not row-constant: is_missing over ""
            // mixed with missings) it BLOCKS the collapse — one undecided live leaf keeps the rule
            // per row (owner refinement 2026-10-07). Speculative edges, kept conservative: a live
            // leaf whose constant is FALSE-effective (is_missing over an absent character column,
            // which reads "") counts as undecided and blocks; the same column under two leaves
            // counts twice; liveness is syntactic below the dead-branch test (MED-1), so a leaf
            // whose blanking could only keep an already-decided Or TRUE still counts.
            out.live++;
            BroadcastFold.Verdict constant = constantOf.apply(c, name);
            boolean effectiveTrue = constant == BroadcastFold.Verdict.TRUE;
            if (constant != BroadcastFold.Verdict.UNKNOWN && effectiveTrue != negated)
            {
                out.decided++;
                out.columns.add(name);
            }
        }
        default ->
        {
            // a comparison (L6: a boolean leaf under `==` / `!=` is not in boolean position), a
            // reference or a literal: no live leaf of its own
        }
        }
    }


    /**
     * The constant an {@code empty}-family leaf evaluates to over an ABSENT column (the
     * absent-column contract, EC-43 / D76): {@code empty} TRUE; {@code is_missing} TRUE where the
     * column is numeric-expected (it reads MIS) and FALSE where it reads {@code ""};
     * {@code non_empty} / {@code present} / {@code is_present} FALSE.
     *
     * @param function
     *            the leaf's function name
     * @param name
     *            the column, as named in the Check
     * @param ctx
     *            the evaluation context (its type expectations)
     * @return the constant
     */
    static BroadcastFold.Verdict absentLeafConstant(String function, String name,
            EvaluationContext ctx)
    {
        if ("empty".equals(function))
        {
            return BroadcastFold.Verdict.TRUE;
        }
        if ("is_missing".equals(function))
        {
            return ctx.getNumericExpectedColumns().contains(name) ? BroadcastFold.Verdict.TRUE
                    : BroadcastFold.Verdict.FALSE;
        }
        return BroadcastFold.Verdict.FALSE;
    }


    /**
     * The constant a row-constant {@code empty}-family leaf evaluates to over a column of this
     * content ({@link #rowConstant} must hold): {@code empty} TRUE, {@code is_missing} TRUE on a
     * MISSING fill and FALSE on an all-{@code ""} one (D50), {@code non_empty} / {@code present} /
     * {@code is_present} FALSE.
     *
     * @param function
     *            the leaf's function name
     * @param content
     *            the column's content
     * @return the constant
     */
    public static boolean leafConstant(String function, ColumnContent content)
    {
        return "empty".equals(function)
                || ("is_missing".equals(function) && content.fill() == ColumnContent.Fill.MISSING);
    }


    /**
     * The content of a column PRESENT in the evaluation table's metadata, {@code null} when the
     * table lacks it (an absent column triggers through its absent-column constant).
     */
    private static @Nullable ColumnContent presentColumnContent(EvaluationContext ctx, String name)
    {
        DataTableMeta meta = ctx.getTable().getMetaData();
        int idx = meta.getColumnIndex(name);
        // ⚠ Never let a null answer mean "absent" (PACK 4 review L4): a table that cannot answer
        // (a plain mock) must fail loudly, or an opted-in rule silently loses its collapse.
        return idx < 0 ? null
                : java.util.Objects.requireNonNull(ctx.getTable().getColumnContent(idx),
                        () -> "getColumnContent(" + idx + ") answered null on " + meta.getName()
                                + " — a table must answer the content status");
    }


    /**
     * Whether a call reads ANOTHER table's columns: the target-table readers ({@code read_value},
     * {@code record_count}, {@code distinct}, the grouped aggregates {@code max} / {@code max_date}
     * / {@code min_date}) and any call carrying a {@code domain=} keyword. A leaf inside such a
     * call's arguments ({@code record_count(domain="EX", filter=(empty(EXDOSE)))}) names a column
     * of THAT table, never of the evaluation table, so it is no collapse trigger and no R2 leaf
     * (PACK 4 review MED-2).
     *
     * @param c
     *            the call
     * @return whether its arguments are read against another table
     */
    public static boolean readsAnotherTable(Expr.Call c)
    {
        return net.cumba.corej.core.exec.ReadValue.NAME.equals(c.name())
                || net.cumba.corej.core.exec.RecordCount.NAME.equals(c.name())
                || net.cumba.corej.core.exec.Distinct.NAME.equals(c.name())
                || net.cumba.corej.core.exec.GroupedAggregate.isFunction(c.name())
                || c.kwargs().containsKey("domain");
    }


    /**
     * The truth table: whether the named {@code empty}-family leaf is row-constant on a column of
     * this content.
     *
     * @param function
     *            the leaf's function name
     * @param content
     *            the column's content
     * @return whether the leaf answers the same on every row
     */
    public static boolean rowConstant(String function, ColumnContent content)
    {
        if (content.fill() == ColumnContent.Fill.FILLED)
        {
            return false;
        }
        if ("is_missing".equals(function))
        {
            // D50: strictly MissingValue. All-missing ⇒ TRUE; all-"" ⇒ FALSE; "" mixed with
            // missing ⇒ row-dependent.
            return content.fill() == ColumnContent.Fill.MISSING
                    || content.uniformity() == ColumnContent.Uniformity.SINGLE_VALUE;
        }
        return true; // empty ⇒ TRUE, non_empty / present / is_present ⇒ FALSE
    }


    /**
     * The {@code Keep_Row_Findings} names resolved for this execution: a {@code --} entry takes the
     * dataset's variable wildcard prefix (the same resolution {@code Requirements.Variables} uses),
     * every other entry stays as written.
     *
     * @param outcome
     *            the rule's Outcome, or {@code null}
     * @param ctx
     *            the evaluation context (its variable wildcard prefix)
     * @return the resolved names; empty when none is declared
     */
    public static Set<String> resolveKeepRowFindings(@Nullable Outcome outcome,
            EvaluationContext ctx)
    {
        Set<String> out = new LinkedHashSet<>();
        if (outcome == null || outcome.getKeepRowFindings() == null)
        {
            return out;
        }
        String prefix = ctx.getVariableWildcardPrefix();
        for (String entry : outcome.getKeepRowFindings())
        {
            if (entry == null || entry.isBlank())
            {
                continue;
            }
            String name = entry.trim();
            if (name.startsWith("--") && prefix != null)
            {
                name = prefix + name.substring(2);
            }
            out.add(name);
        }
        return out;
    }


    /** The plain column name an {@code empty}-family call reads, or {@code null}. */
    static @Nullable String plainColumnLeaf(Expr.Call c)
    {
        if (!EMPTY_FAMILY.contains(c.name()) || c.args().size() != 1 || !c.kwargs().isEmpty())
        {
            return null;
        }
        return c.args().get(0) instanceof Expr.Ref r && isPlainColumn(r) ? r.name() : null;
    }


    private static boolean isPlainColumn(Expr.Ref r)
    {
        return (r.kind() == OperandKind.COLUMN || r.kind() == OperandKind.WILDCARD_COLUMN)
                && r.name().indexOf('.') < 0 && !r.name().startsWith("$");
    }
}
