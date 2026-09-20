package net.cumba.corej.core.expr.typed;

import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * Bind-time refinement hook for the level of a bare column reference (phase 5 of
 * {@code plans/PLAN-typed-expression-engine.md}). Stage A knows only the expression, so a column
 * reference is {@code record}-level by default; at bind time the dataset is known, and D39a makes
 * an <b>absent</b> column a {@code dataset}-level constant (<i>"that is precisely what D34 #3/#4
 * say, so D5 produces D39 with no extra machinery"</i>), while a name that resolves to a scalar
 * <b>context variable</b> (the Fix #10 {@code DOMAIN} injection — variables resolve before columns
 * in both engines) reads a dataset-level fact.
 *
 * <p>
 * Returning {@code null} keeps the stage-A default ({@link Level#RECORD} for a bare column). The
 * resolver is consulted for every {@code COLUMN} / {@code WILDCARD_COLUMN} / {@code DOTTED_REF}
 * reference; implementations decide which kinds they refine.
 * </p>
 */
@FunctionalInterface
public interface ColumnLevelResolver
{

    /** The stage-A default: no dataset knowledge, every column reference stays record-level. */
    ColumnLevelResolver STAGE_A = ref -> null;

    /**
     * The refined level of {@code ref} against the bound dataset, or {@code null} for the stage-A
     * default.
     */
    @Nullable
    Level resolve(Expr.Ref ref);

}
