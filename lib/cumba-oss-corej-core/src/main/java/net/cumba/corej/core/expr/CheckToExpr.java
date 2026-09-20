package net.cumba.corej.core.expr;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionNot;

/**
 * Raises a {@link CheckCondition} tree into the {@link Expr} IR.
 *
 * <p>
 * ⭐ Phase 7d of {@code PLAN-typed-expression-engine} (D121): the v1 operator-leaf model is retired,
 * so the only tree shapes left are the composites ({@code all} / {@code any} / {@code not}) and
 * {@link net.cumba.corej.core.model.CheckConditionExpression} — whose compiled {@link Expr} is
 * returned directly. What remains of this class is the structural raise over those composites,
 * which {@code RulePackageLoader.tryRaiseToExpr}, {@code RuleClassifier},
 * {@code ProviderRequirements} and the expression-JSON converter all still need for a {@code Check}
 * / {@code Precondition} authored as {@code all:}/{@code any:}/{@code
 * not:} over {@code expression:} nodes. The ~1 300 lines of operator-leaf raising that used to live
 * here (the operator switch, the affix / regex-optimisation / sorted-by / composite-membership /
 * value helpers) went with the leaf model.
 * </p>
 */
public final class CheckToExpr
{

    private CheckToExpr()
    {
    }


    /**
     * Raises a check-condition tree to the {@link Expr} IR.
     *
     * @param c
     *            the condition
     * @return the equivalent expression
     */
    public static Expr toExpr(CheckCondition c)
    {
        return switch (c)
        {
        case CheckConditionAll all -> new Expr.And(mapAll(all.getConditions()));
        case CheckConditionAny any -> new Expr.Or(mapAll(any.getConditions()));
        case CheckConditionNot not -> new Expr.Not(toExpr(not.getCondition()));
        // An expression Check already carries its compiled Expr — return it directly.
        case net.cumba.corej.core.model.CheckConditionExpression ce -> ce.expr();
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

}
