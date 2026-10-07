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
        Set<String> out = new TreeSet<>();
        if (ctx.rowCount() <= 0)
        {
            return out; // C5: a 0-row table never collapses
        }
        collectFoldable(check, ctx, keepRowFindings, out);
        return out;
    }


    private static void collectFoldable(Expr e, EvaluationContext ctx, Set<String> keep,
            Set<String> out)
    {
        switch (e)
        {
        case Expr.Not not -> collectFoldable(not.inner(), ctx, keep, out);
        case Expr.And and -> and.parts().forEach(p -> collectFoldable(p, ctx, keep, out));
        case Expr.Or or -> or.parts().forEach(p -> collectFoldable(p, ctx, keep, out));
        case Expr.Binary b ->
        {
            collectFoldable(b.left(), ctx, keep, out);
            collectFoldable(b.right(), ctx, keep, out);
        }
        case Expr.Call c ->
        {
            if (net.cumba.corej.core.exec.JoinedRowFold.isQuantifier(c.name()))
            {
                // A leaf under joined(…) / any / all / none reads the joined rows, not a column
                // of the evaluation table as the row path reads it: never a trigger (R5).
                return;
            }
            String name = plainColumnLeaf(c);
            if (name != null && !FoldCapability.containsIgnoreCase(keep, name))
            {
                ColumnContent content = presentColumnContent(ctx, name);
                if (content != null ? rowConstant(c.name(), content)
                        : BroadcastFold.bindColumnLevel(name,
                                ctx) == BroadcastFold.BindColumnLevel.DATASET_ABSENT)
                {
                    // present and row-constant by its fill, or ABSENT (P3): the column reads as
                    // all-missing on every row, so the leaf is row-constant exactly as over a
                    // column of fill MISSING
                    out.add(name);
                }
            }
            c.args().forEach(a -> collectFoldable(a, ctx, keep, out));
            c.kwargs().values().forEach(a -> collectFoldable(a, ctx, keep, out));
        }
        default ->
        {
            // a reference or a literal: no leaf of its own
        }
        }
    }


    /**
     * The content of a column PRESENT in the evaluation table's metadata, {@code null} when the
     * table lacks it (an absent column triggers through its level, P3).
     */
    private static @Nullable ColumnContent presentColumnContent(EvaluationContext ctx, String name)
    {
        DataTableMeta meta = ctx.getTable().getMetaData();
        int idx = meta.getColumnIndex(name);
        return idx < 0 ? null : ctx.getTable().getColumnContent(idx);
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
