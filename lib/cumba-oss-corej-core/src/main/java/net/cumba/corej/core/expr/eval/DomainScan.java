package net.cumba.corej.core.expr.eval;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.OperationExecutor.ResultKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;

/**
 * Leaf-scope domain inference ({@code PLAN-leaf-scope-domain-inference.md} §3.1–§3.2): every
 * {@link Expr} node has an intrinsic scope — what its value can vary over — and a rule's evaluation
 * {@link Domain} is the join of the cursor demands of all leaves of its Check, with whole-column
 * verdict operators <em>absorbing</em> their operands' demand.
 *
 * <table>
 * <caption>Leaf scopes</caption>
 * <tr>
 * <th>Scope</th>
 * <th>Cursor demand</th>
 * <th>Constructs</th>
 * </tr>
 * <tr>
 * <td>STUDY / DATASET</td>
 * <td>{@code {}}</td>
 * <td>literals; {@code ds_*} accessors; presence calls on a literal or bare name
 * ({@code ds_exists("EX")}, {@code var_exists("AESEQ")}); {@code var_*} accessors naming an
 * <em>explicit</em> variable ({@code var_label("AESEV", "DATA")}); {@code record_count()}; the
 * library / dictionary skip-gate calls; scalar {@code $}-operations; whole-column verdict operators
 * ({@link BroadcastFold#WHOLE_COLUMN_VERDICT_OPERATORS}), whose operands are absorbed</td>
 * </tr>
 * <tr>
 * <td>VARIABLE</td>
 * <td>{@code {VAR}}</td>
 * <td>{@code varname()}; the {@code variable_name} anchor; cursor-form {@code var_*} accessors
 * ({@code var_label("DATA")}, {@code var_label(varname(), "DATA")}); {@code var_exists(varname())};
 * {@code max_value_length()} and the varname-anchored code/decode matchers; per-variable
 * {@code $}-operations ({@link ResultKind#PER_VARIABLE})</td>
 * </tr>
 * <tr>
 * <td>ROW</td>
 * <td>{@code {ROW}}</td>
 * <td>plain, wildcard and dotted column references; per-row {@code $}-operations and per-row inline
 * operations ({@link ResultKind#PER_ROW}); an {@code exists} over a {@code ${...}} driver template;
 * every per-row value function over a column</td>
 * </tr>
 * <tr>
 * <td>CELL</td>
 * <td>{@code {VAR,ROW}}</td>
 * <td>{@code value()} (the current variable's value in the current row); the bare
 * {@code variable_value} operand; the {@code vlm_*} per-(record × variable) accessors</td>
 * </tr>
 * </table>
 *
 * <p>
 * The scan is a statement about the raised {@link Expr} IR — the {@code checkExpr} the loader
 * installs after canonicalisation — not about authored {@code operator:} keys. {@code $}-operation
 * kinds come from {@link OperationKinds} (the load-time mirror of the {@code instanceof} tests
 * {@code BroadcastFold} applies to the materialised value), so the inference agrees with the
 * runtime routing by construction rather than by a parallel vocabulary.
 * </p>
 */
public final class DomainScan
{

    private DomainScan()
    {
    }

    private static final Set<String> VARNAME_ANCHORED_CALLS = Set.of("max_value_length",
            "library_variable_code_pair_matches", "define_variable_decode_matches");

    /** The bare-operand names that are the current variable's per-row value. */
    private static final Set<String> CELL_BUILTINS = Set.of("variable_value",
            "variable_value_length");

    /**
     * §3.2: the join of the leaves' cursor demands, with broadcast-verdict absorption.
     *
     * @param expr
     *            the Check (or Precondition) expression, canonicalised
     * @param kinds
     *            the rule's {@code $}-operation result kinds
     * @return the evaluation domain
     */
    public static Domain infer(Expr expr, OperationKinds kinds)
    {
        return switch (expr)
        {
        case Expr.And a -> joinAll(a.parts(), kinds);
        case Expr.Or o -> joinAll(o.parts(), kinds);
        case Expr.Not n -> infer(n.inner(), kinds);
        case Expr.Binary b -> infer(b.left(), kinds).join(infer(b.right(), kinds));
        case Expr.Lit lit -> literal(lit, kinds);
        case Expr.Ref r -> ref(r, kinds);
        case Expr.Call c -> call(c, kinds);
        };
    }


    private static Domain joinAll(Collection<Expr> parts, OperationKinds kinds)
    {
        Domain d = Domain.DATASET;
        for (Expr p : parts)
        {
            d = d.join(infer(p, kinds));
        }
        return d;
    }


    private static Domain literal(Expr.Lit lit, OperationKinds kinds)
    {
        if (lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            return joinAll(items, kinds);
        }
        return Domain.DATASET;
    }


    private static Domain ref(Expr.Ref r, OperationKinds kinds)
    {
        return switch (r.kind())
        {
        case COLUMN, WILDCARD_COLUMN, DOTTED_REF -> Domain.ROW;
        case OPERATION_REF -> ofKind(kinds.kindOf(r.name()));
        case BUILTIN -> builtin(r.name());
        };
    }


    private static Domain ofKind(ResultKind kind)
    {
        return switch (kind)
        {
        case PER_ROW -> Domain.ROW;
        case PER_VARIABLE -> Domain.VARIABLE;
        case SCALAR -> Domain.DATASET;
        };
    }


    /**
     * A bare builtin operand that survived canonicalisation: the {@code variable_name} anchor is
     * the variable cursor, the {@code variable_value} pair is the cell, the variable-metadata
     * operands ({@code variable_*}, {@code library_variable_*}, {@code define_variable_*}) read the
     * cursor variable's metadata, the VLM operands are per cell, and everything else is a dataset
     * fact.
     */
    private static Domain builtin(String name)
    {
        if ("variable_name".equals(name))
        {
            return Domain.VARIABLE;
        }
        if (CELL_BUILTINS.contains(name) || name.startsWith("define_vlm_"))
        {
            return Domain.CELL;
        }
        if (name.startsWith("variable_") || name.startsWith("library_variable_")
                || name.startsWith("define_variable_"))
        {
            return Domain.VARIABLE;
        }
        return Domain.DATASET;
    }


    private static Domain call(Expr.Call c, OperationKinds kinds)
    {
        String name = c.name();
        if ("value".equals(name) && c.args().isEmpty() && c.kwargs().isEmpty())
        {
            return Domain.CELL;
        }
        if ("varname".equals(name) && c.args().isEmpty() && c.kwargs().isEmpty())
        {
            return Domain.VARIABLE;
        }
        if (BroadcastFold.isExistsCall(c))
        {
            return existsCall(c);
        }
        if (BroadcastFold.isWholeColumnVerdictCall(c))
        {
            // One dataset fact per column: the ROW cursor demand of its operands is absorbed
            // (§3.1), but a per-variable operand (a library_*/define_* read, a varname() cursor)
            // keeps its VAR cursor — the verdict is then one per variable, not one per dataset
            // (review finding 10, 2026-08-22).
            Domain operands = joinAll(c.args(), kinds).join(joinAll(c.kwargs().values(), kinds));
            return operands.varCursor() ? Domain.VARIABLE : Domain.DATASET;
        }
        if (BroadcastFold.isLibraryGateCall(c))
        {
            return Domain.DATASET;
        }
        MetadataAttribute attr = MetadataAttribute.fromFunction(name);
        if (attr != null)
        {
            return accessor(attr, c);
        }
        if (name.startsWith("vlm_"))
        {
            return Domain.CELL;
        }
        if (VARNAME_ANCHORED_CALLS.contains(name))
        {
            return c.args().isEmpty() || isCurrentVariableName(c.args().get(0)) ? Domain.VARIABLE
                    : Domain.DATASET;
        }
        if (ExprCompiler.isInlineOperation(c))
        {
            // Form A: the call's arguments are operation parameters (names, keywords), not reads;
            // the result kind alone decides, exactly as for the declared `$` form.
            ResultKind kind;
            try
            {
                kind = net.cumba.corej.core.exec.OperationExecutor
                        .resultKind(OperationExpressionParser.fromCall(c, null));
            }
            catch (RuntimeException _)
            {
                kind = ResultKind.SCALAR;
            }
            return ofKind(kind);
        }
        // record_count() and every other value function / predicate: the join of its operands.
        return joinAll(c.args(), kinds).join(joinAll(c.kwargs().values(), kinds));
    }


    /**
     * A presence fact is a dataset-level fact — unless it names the cursor variable
     * ({@code var_exists(varname())}, the §3.7 universe discriminator) or carries a {@code ${...}}
     * per-row driver template (Fix #37: a row read).
     */
    private static Domain existsCall(Expr.Call c)
    {
        Expr arg = c.args().get(0);
        if (isCurrentVariableName(arg))
        {
            return Domain.VARIABLE;
        }
        String argName = arg instanceof Expr.Ref r ? r.name() : (String) ((Expr.Lit) arg).value();
        return argName.contains("${") ? Domain.ROW : Domain.DATASET;
    }


    /**
     * A {@code ds_*} accessor is a dataset fact. A {@code var_*} accessor reads the cursor variable
     * in its arity-1 form ({@code var_label("DATA")}) and when its name argument is
     * {@code varname()} / {@code variable_name}; with an explicit literal name it is a
     * dataset-level fact about a named column.
     */
    private static Domain accessor(MetadataAttribute attr, Expr.Call c)
    {
        if (attr.scope() == MetadataAttribute.Scope.DATASET)
        {
            return Domain.DATASET;
        }
        List<Expr> args = c.args();
        if (args.size() == 2 && !isCurrentVariableName(args.get(0)))
        {
            return Domain.DATASET;
        }
        return Domain.VARIABLE;
    }


    private static boolean isCurrentVariableName(Expr e)
    {
        if (e instanceof Expr.Ref ref && "variable_name".equals(ref.name()))
        {
            return true;
        }
        return e instanceof Expr.Call c && "varname".equals(c.name()) && c.args().isEmpty()
                && c.kwargs().isEmpty();
    }
}
