package net.cumba.corej.core.expr.eval;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.ast.Expr;

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
 * {@code $}-operations (per-variable)</td>
 * </tr>
 * <tr>
 * <td>ROW</td>
 * <td>{@code {ROW}}</td>
 * <td>plain, wildcard and dotted column references; per-row {@code $}-operations and per-row inline
 * operations (per row); an {@code exists} over a {@code ${...}} driver template; every per-row
 * value function over a column</td>
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
 * kinds come from {@link BindingDomains} (the load-time mirror of the {@code instanceof} tests
 * {@code BroadcastFold} applies to the materialised value), so the inference agrees with the
 * runtime routing by construction rather than by a parallel vocabulary.
 * </p>
 */
public final class DomainScan
{

    private DomainScan()
    {
    }

    /**
     * The varname-anchored calls: variable-cursor when unanchored (or anchored at the cursor).
     * Package-visible because {@code BroadcastFold.absentColumnLeafLevel} excludes the same family
     * — a shared census, so the two cascades cannot drift.
     *
     * <p>
     * ⚠ <b>Corrected 2026-09-21.</b> This said the family is <i>"dataset-level when anchored at an
     * explicit variable"</i>. That holds for {@code max_value_length}, which does accept a column
     * reference or a string literal, and the {@link Domain#DATASET} arm below serves it. It does
     * <b>not</b> hold for {@code library_variable_code_pair_matches} or
     * {@code define_variable_decode_matches}: both throw <i>"expects varname() or no argument"</i>
     * at compile time, so their explicit-name form never reaches here. ⛔ The DATASET arm is
     * therefore live and must not be removed as dead — it is {@code max_value_length}'s.
     * </p>
     */
    static final Set<String> VARNAME_ANCHORED_CALLS = Set.of("max_value_length",
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
    public static Domain infer(Expr expr, BindingDomains kinds)
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


    private static Domain joinAll(Collection<Expr> parts, BindingDomains kinds)
    {
        Domain d = Domain.DATASET;
        for (Expr p : parts)
        {
            d = d.join(infer(p, kinds));
        }
        return d;
    }


    private static Domain literal(Expr.Lit lit, BindingDomains kinds)
    {
        if (lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            return joinAll(items, kinds);
        }
        return Domain.DATASET;
    }


    private static Domain ref(Expr.Ref r, BindingDomains kinds)
    {
        return switch (r.kind())
        {
        // MATCHED_FLAG: a boolean at level record (spec §3.3) — a per-row verdict.
        case COLUMN, WILDCARD_COLUMN, DOTTED_REF, MATCHED_FLAG -> Domain.ROW;
        case OPERATION_REF -> kinds.domainOf(r.name());
        case BUILTIN -> builtin(r.name());
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


    private static Domain call(Expr.Call c, BindingDomains kinds)
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
        if (BroadcastFold.isBroadcastColumnPredicate(c))
        {
            // One dataset-wide verdict about a NAMED column (ExprCompiler.compileVarIsNull paints
            // a single boolean over every row), so the argument's ROW cursor demand is absorbed —
            // but the argument analysis is shared with the exists family, so the cursor form keeps
            // its per-variable answer and a ${...} driver template stays per row.
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
        FunctionDescriptor declared = FunctionRegistry.descriptor(name);
        if (declared != null && declared.perVariableMap())
        {
            // Wave 4b (PLAN-scalar-metadata-functions D-W4b-1): the function's one value is a
            // per-variable map that the per-variable loop projects onto the variable cursor — the
            // retired operation's per-variable result kind, carried over (declared on the
            // descriptor since the combined review of runbook W2–W8, W3+W4b L5: not keyed on the
            // name). Without this arm the `domain=` arm below reads it DATASET, and a Check whose
            // only per-variable read is this binding (`empty($sdtm_label)`) would fold at dataset
            // level instead of routing per variable (VariableMetadataNativeParityTest s5 / s7b).
            return Domain.VARIABLE;
        }
        if (net.cumba.corej.core.exec.RecordCount.NAME.equals(name))
        {
            // Runbook W6 (PLAN-record-count-function D-W6-9): the retired RECORD_COUNT
            // operation's result kind, carried over — per row when grouped, scalar otherwise. An
            // explicit arm on both sides: without it an ungrouped record_count(filter=(TSPARMCD
            // == "X")) would read TSPARMCD as a ROW-level read of the primary and turn a
            // dataset-level binding into a per-row one; the filter is a parameter, as the
            // operation's was.
            return net.cumba.corej.core.exec.RecordCount.isGrouped(c) ? Domain.ROW : Domain.DATASET;
        }
        if (net.cumba.corej.core.exec.Distinct.NAME.equals(name))
        {
            // Runbook W7 (PLAN-distinct-function D-W7-12): the retired DISTINCT operation's
            // result kind, carried over — per row when grouped, scalar otherwise; an explicit arm
            // on both sides, as record_count's (the target and the filter are parameters of the
            // target table, not reads of the primary).
            return net.cumba.corej.core.exec.Distinct.isGrouped(c) ? Domain.ROW : Domain.DATASET;
        }
        if (net.cumba.corej.core.exec.GroupedAggregate.isFunction(name)
                || (net.cumba.corej.core.exec.ReadValue.NAME.equals(name)
                        && c.kwargs().containsKey("group")))
        {
            // Runbook W5 (PLAN-grouped-aggregate-functions D-W5-7): a grouped aggregate answers
            // one value PER PRIMARY ROW (its groups are broadcast by key) — the retired
            // operations' per-row result kind, carried over. Before the `domain=` arm below, which
            // reads a foreign call as one dataset-level value.
            return Domain.ROW;
        }
        if (c.kwargs().containsKey("domain"))
        {
            // Runbook W2a (PLAN-operation-replacements §2.2, owner D10 / D13 Q4): a registry call
            // carrying `domain=` reads ANOTHER dataset — read_value(TSVAL, domain=TS, filter=(…))
            // — and answers one value for the run. Its arguments are that dataset's columns, so
            // they carry no ROW demand of the primary (the inline-operation arm above already
            // reasons this way for an operation's parameters); without this arm TSVAL / TS were
            // read as primary references and CDISC-SEND-0105's Domain moved {} → {ROW}.
            return Domain.DATASET;
        }
        Domain operands = joinAll(c.args(), kinds).join(joinAll(c.kwargs().values(), kinds));
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(name);
        if (descriptor != null && descriptor.perRow())
        {
            // A declared row reader (FunctionDescriptor.readingRows: row_max selects its columns
            // by a static regex and has no column operand) answers one value per row of the
            // evaluated table — the ROW demand its operands cannot show (combined review of
            // runbook W2–W8, XCUT H1: a `$trxx_max` binding classified {} handed row 0's maximum
            // to every row of CDISC-/PMDA-AD0084).
            return operands.join(Domain.ROW);
        }
        if (descriptor != null && descriptor.aggregate())
        {
            // SPEC §1.4 raising (PLAN-binding-expressions §4.1): an aggregate folds the row axis
            // of its operands into one broadcast value — the ROW demand is absorbed, a VAR cursor
            // survives (one value per variable), as for the whole-column verdict calls above.
            return Domain.of(operands.varCursor(), false);
        }
        // record_count() and every other value function / predicate: the join of its operands.
        return operands;
    }


    /**
     * A presence fact is a dataset-level fact — unless it names the cursor variable
     * ({@code var_exists(varname())}, the §3.7 universe discriminator) or carries a {@code ${...}}
     * per-row driver template (Fix #37: a row read).
     *
     * <p>
     * Also serves the {@linkplain BroadcastFold#BROADCAST_COLUMN_PREDICATES broadcast column
     * predicates}, which take the same three argument shapes and want the same three answers. It
     * casts the literal blind, so every caller must first have narrowed the argument to a reference
     * or a string literal.
     * </p>
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
        return (e instanceof Expr.Ref ref && "variable_name".equals(ref.name()))
                || (e instanceof Expr.Call c && "varname".equals(c.name()) && c.args().isEmpty()
                        && c.kwargs().isEmpty());
    }
}
