package net.cumba.corej.core.expr.typed;

import java.util.List;
import net.cumba.corej.core.expr.ast.Expr;

/**
 * One node of the typed AST: the underlying {@link Expr} node annotated with its static
 * {@link ExprType} and its {@link Level} — i.e. {@code (type, granularity, cursor)} per spec
 * §1.1/§1.3. Built by {@link StageAChecker}; phase 2 builds and reports, nothing evaluates it yet.
 */
public record TypedExpr(Expr node, ExprType type, Level level, List<TypedExpr> children)
{

    public TypedExpr
    {
        children = List.copyOf(children);
    }


    /** The granularity half of the level. */
    public Granularity granularity()
    {
        return level.granularity();
    }


    /** The cursor half of the level. */
    public Cursor cursor()
    {
        return level.cursor();
    }

}
