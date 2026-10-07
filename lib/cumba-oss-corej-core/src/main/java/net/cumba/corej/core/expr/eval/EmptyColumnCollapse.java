package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
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
 * Two static entries, no state:
 * </p>
 * <ul>
 * <li>{@link #refusals(Rule)} — the <b>load-time</b> classifier (R2): why a rule declaring
 * {@code Message_Folded} can never collapse — (a) no {@code empty}-family leaf over a plain column
 * reference that {@code Keep_Row_Findings} leaves in, (b) a non-{@code Record} Sensitivity, (c) a
 * Check that reads the variable cursor and is routed per variable. Kept out of
 * {@code RulePackageLoader} so {@code PLAN-message-folded-corpus-alignment} can move it into its
 * {@code FoldCapability}.</li>
 * <li>{@link #foldableColumns(Expr, EvaluationContext, Set)} — the <b>data-time</b> decision (the
 * truth table below) on the evaluation table's
 * {@link net.cumba.datatable.IDataTable#getColumnContent(int)}. It never touches
 * {@link BroadcastFold#bindColumnLevel}: that calculus feeds the shipped absent-column fold for
 * <em>every</em> rule, and a new level class there would move rules that never opted in.</li>
 * </ul>
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
 * <b>Why a reporting step equals D5.</b> The row evaluation of the full Check computes, on every
 * row, exactly D5's residual with the constant substituted: residual constant FALSE ⇔ no row fires
 * ⇒ 0 findings; constant TRUE ⇔ every row fires ⇒ 1 finding; row-dependent ⇒ 1 finding iff some row
 * fires. No partial evaluator, no second evaluation. A leaf over an ABSENT column stays with
 * {@link BroadcastFold} exactly as today (R3); a dotted {@code J.X} or SUPP-qualifier argument is
 * not a column of the evaluation table and never triggers (R5); a column named in
 * {@code Keep_Row_Findings} never triggers (R1, D9); a 0-row table never collapses (C5).
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

    // ---- load time (R2) ---------------------------------------------------------------------


    /**
     * Whether a rule declaring {@code Message_Folded} can collapse at all: {@link #refusals(Rule)}
     * is empty.
     *
     * @param rule
     *            the loaded rule (Sensitivity derived, native expression installed)
     * @return {@code true} when nothing refuses it
     */
    public static boolean collapsible(Rule rule)
    {
        return refusals(rule).isEmpty();
    }


    /**
     * R2 — why {@code Outcome.Message_Folded} is refused on this rule, each as one load-error text
     * (without the rule-id prefix); empty when the rule can collapse. Read <b>after</b> Sensitivity
     * derivation and after the native expression (and its evaluation domain) is installed.
     *
     * @param rule
     *            the loaded rule
     * @return the refusals, in order (a) leaf, (b) Sensitivity, (c) variable cursor
     */
    public static List<String> refusals(Rule rule)
    {
        List<String> out = new ArrayList<>();
        Set<String> keep = keepRowFindingsAsWritten(rule.getOutcome());
        Set<String> leaves = new LinkedHashSet<>();
        for (CheckConditionExpression ce : authoredConditions(rule))
        {
            for (String name : plainColumnLeafNames(ce.expr()))
            {
                if (!containsIgnoreCase(keep, name))
                {
                    leaves.add(name);
                }
            }
        }
        if (leaves.isEmpty())
        {
            out.add("Outcome.Message_Folded is declared but the Check holds no empty-family leaf"
                    + " (empty / is_missing / non_empty / present / is_present) over a plain column"
                    + " reference" + (keep.isEmpty() ? "" : " outside Keep_Row_Findings")
                    + ", so the rule can never collapse (D6) — remove the key, or write the"
                    + " check on the column");
        }
        Sensitivity sensitivity = rule.getSensitivity();
        if (sensitivity != Sensitivity.RECORD)
        {
            out.add("Outcome.Message_Folded is the message of a RECORD-level rule folded to one"
                    + " dataset finding (D7); this rule's Sensitivity is "
                    + (sensitivity == null ? "undeclared and underived"
                            : sensitivity.getJsonValue())
                    + ", which never reaches the collapse — remove the key");
        }
        Domain domain = rule.getEvaluationDomain();
        if (domain != null && domain.varCursor())
        {
            out.add("Outcome.Message_Folded is declared but the Check reads the variable cursor"
                    + " (varname() / value() / a variable-metadata binding), so it is evaluated"
                    + " per variable, where the collapse never runs — remove the key");
        }
        return out;
    }


    /**
     * The {@code Keep_Row_Findings} entries the Check does not read, compared as written
     * ({@code --} templates included, names case-insensitive) against every column reference of
     * every authored level; and the blank entries.
     *
     * @param rule
     *            the loaded rule
     * @return one error text per offending entry, without the rule-id prefix
     */
    public static List<String> keepRowFindingsErrors(Rule rule)
    {
        List<String> out = new ArrayList<>();
        Outcome outcome = rule.getOutcome();
        if (outcome == null || outcome.getKeepRowFindings() == null)
        {
            return out;
        }
        Set<String> read = new LinkedHashSet<>();
        for (CheckConditionExpression ce : authoredConditions(rule))
        {
            read.addAll(referencedColumnNames(ce.expr()));
        }
        for (String entry : outcome.getKeepRowFindings())
        {
            if (entry == null || entry.isBlank())
            {
                out.add("Outcome.Keep_Row_Findings contains an empty entry");
            }
            else if (!containsIgnoreCase(read, entry.trim()))
            {
                out.add("Outcome.Keep_Row_Findings names '" + entry.trim()
                        + "', which the Check does not read (compared as written, --"
                        + " included) — a column the rule never tests cannot keep its row"
                        + " findings");
            }
        }
        return out;
    }

    // ---- data time -------------------------------------------------------------------------


    /**
     * The columns whose {@code empty}-family leaves are row-constant on this evaluation table — the
     * truth table of the class javadoc — and therefore make the collapse apply. Empty when nothing
     * folds (the rule takes today's per-row path).
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
            if (name != null && !containsIgnoreCase(keep, name))
            {
                ColumnContent content = presentColumnContent(ctx, name);
                if (content != null && rowConstant(c.name(), content))
                {
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
     * table lacks it (an absent column stays with {@link BroadcastFold}, R3).
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

    // ---- expression walks -------------------------------------------------------------------


    /**
     * The column names (as written) of every {@code empty}-family leaf whose argument is a plain
     * column reference — bare or {@code --}-templated, never dotted, never a {@code $}-binding.
     *
     * @param e
     *            the expression
     * @return the names, in encounter order
     */
    public static Set<String> plainColumnLeafNames(Expr e)
    {
        Set<String> out = new LinkedHashSet<>();
        collectLeafNames(e, out);
        return out;
    }


    private static void collectLeafNames(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.Not not -> collectLeafNames(not.inner(), out);
        case Expr.And and -> and.parts().forEach(p -> collectLeafNames(p, out));
        case Expr.Or or -> or.parts().forEach(p -> collectLeafNames(p, out));
        case Expr.Binary b ->
        {
            collectLeafNames(b.left(), out);
            collectLeafNames(b.right(), out);
        }
        case Expr.Call c ->
        {
            if (net.cumba.corej.core.exec.JoinedRowFold.isQuantifier(c.name()))
            {
                return;
            }
            String name = plainColumnLeaf(c);
            if (name != null)
            {
                out.add(name);
            }
            c.args().forEach(a -> collectLeafNames(a, out));
            c.kwargs().values().forEach(a -> collectLeafNames(a, out));
        }
        default ->
        {
            // a reference or a literal
        }
        }
    }


    /** The plain column name an {@code empty}-family call reads, or {@code null}. */
    private static @Nullable String plainColumnLeaf(Expr.Call c)
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


    /**
     * Every column reference an expression reads, as written: bare, {@code --}-templated and dotted
     * names alike (for the {@code Keep_Row_Findings} read check).
     *
     * @param e
     *            the expression
     * @return the names, in encounter order
     */
    public static Set<String> referencedColumnNames(Expr e)
    {
        Set<String> out = new LinkedHashSet<>();
        collectReferences(e, out);
        return out;
    }


    private static void collectReferences(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.COLUMN || r.kind() == OperandKind.WILDCARD_COLUMN
                    || r.kind() == OperandKind.DOTTED_REF)
            {
                out.add(r.name());
            }
        }
        case Expr.Not not -> collectReferences(not.inner(), out);
        case Expr.And and -> and.parts().forEach(p -> collectReferences(p, out));
        case Expr.Or or -> or.parts().forEach(p -> collectReferences(p, out));
        case Expr.Binary b ->
        {
            collectReferences(b.left(), out);
            collectReferences(b.right(), out);
        }
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectReferences(a, out));
            c.kwargs().values().forEach(a -> collectReferences(a, out));
        }
        default ->
        {
            // a literal
        }
        }
    }


    private static List<CheckConditionExpression> authoredConditions(Rule rule)
    {
        List<CheckConditionExpression> out = new ArrayList<>();
        for (LevelCheck level : rule.effectiveCheckLevels().values())
        {
            if (level.condition() instanceof CheckConditionExpression ce)
            {
                out.add(ce);
            }
        }
        return out;
    }


    private static Set<String> keepRowFindingsAsWritten(@Nullable Outcome outcome)
    {
        Set<String> out = new LinkedHashSet<>();
        if (outcome != null && outcome.getKeepRowFindings() != null)
        {
            for (String entry : outcome.getKeepRowFindings())
            {
                if (entry != null && !entry.isBlank())
                {
                    out.add(entry.trim());
                }
            }
        }
        return out;
    }


    private static boolean containsIgnoreCase(Collection<String> names, String name)
    {
        for (String n : names)
        {
            if (n.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }
}
