package net.cumba.corej.core.expr.eval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.exec.OperationExecutor.ResultKind;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The {@code $}-operation result kinds {@link DomainScan} needs: which declared operation reference
 * resolves, at runtime, to a per-row {@code GroupedResult}, a per-variable
 * {@code VariableMetadataResult}, or a dataset-constant scalar. {@code BroadcastFold} reads the
 * same classification off the materialised value; this interface makes it available <em>before</em>
 * evaluation, from the rule's {@code Operations} declarations, through
 * {@link OperationExecutor#resultKind}.
 */
@FunctionalInterface
public interface OperationKinds
{

    /** Every reference is a scalar — for expressions that carry no {@code $}-reference. */
    OperationKinds NONE = _ -> ResultKind.SCALAR;

    /**
     * The result kind of the operation a {@code $}-reference names.
     *
     * @param ref
     *            the reference as it appears in the expression (with its leading {@code $})
     * @return the kind; {@link ResultKind#SCALAR} for a reference no declaration resolves (the
     *         dangling-operand load guard rejects that rule separately)
     */
    ResultKind kindOf(String ref);


    /**
     * The evaluation domain a {@code $}-reference contributes to {@link DomainScan} — the domain of
     * its {@link #kindOf kind} for an operation binding. A compiled binding answers its own derived
     * domain ({@link #forRule}), which may be the {@code {VAR,ROW}} cell no {@link ResultKind} can
     * express.
     *
     * @param ref
     *            the reference as it appears in the expression (with its leading {@code $})
     * @return the domain
     */
    default Domain domainOf(String ref)
    {
        return switch (kindOf(ref))
        {
        case PER_ROW -> Domain.ROW;
        case PER_VARIABLE -> Domain.VARIABLE;
        case SCALAR -> Domain.DATASET;
        };
    }


    /**
     * The kinds declared by {@code rule}'s bindings, <b>both kinds, in authored order</b>
     * ({@code PLAN-binding-expressions} R9). An operation binding's kind comes from
     * {@link OperationExecutor#resultKind} (a declaration the parser rejects degrades to
     * {@link ResultKind#SCALAR} — the loader reports that rule's error on its own channel). A
     * <b>compiled</b> binding's domain is its derived one ({@code CompiledBinding.domain()}), or —
     * before {@code installNativeExpr} installed it — inferred here from its expression with the
     * kinds of the bindings authored before it, so a record-level compiled binding is never
     * classified dataset-constant (which would fold the rule's level wrongly). Its {@link #kindOf
     * kind} is {@code PER_ROW} when the domain reads the row cursor, {@code PER_VARIABLE} for the
     * variable cursor alone, {@code SCALAR} otherwise.
     *
     * @param rule
     *            the rule
     * @return the kinds
     */
    static OperationKinds forRule(Rule rule)
    {
        List<net.cumba.corej.core.model.BoundBinding> order = rule.bindingOrder();
        if (order.isEmpty())
        {
            return NONE;
        }
        Map<String, ResultKind> kinds = new HashMap<>();
        Map<String, Domain> domains = new HashMap<>();
        OperationKinds sofar = new OperationKinds()
        {

            @Override
            public ResultKind kindOf(String ref)
            {
                return kinds.getOrDefault(ref, ResultKind.SCALAR);
            }


            @Override
            public Domain domainOf(String ref)
            {
                Domain compiled = domains.get(ref);
                return compiled != null ? compiled : OperationKinds.super.domainOf(ref);
            }
        };
        for (net.cumba.corej.core.model.BoundBinding binding : order)
        {
            switch (binding)
            {
            case net.cumba.corej.core.model.BoundBinding.OfOperation op ->
            {
                String id = op.name();
                if (id != null)
                {
                    kinds.put(id, operationKind(op.operation()));
                }
            }
            case net.cumba.corej.core.model.CompiledBinding compiled ->
            {
                Domain domain = compiled.domain() != null ? compiled.domain()
                        : DomainScan.infer(compiled.expression(), sofar);
                domains.put(compiled.name(), domain);
                kinds.put(compiled.name(), domain.rowCursor() ? ResultKind.PER_ROW
                        : domain.varCursor() ? ResultKind.PER_VARIABLE : ResultKind.SCALAR);
            }
            }
        }
        return sofar;
    }


    /** The kinds declared by an {@code Operations} list; see {@link #forRule}. */
    static OperationKinds forOperations(@Nullable List<Operation> operations)
    {
        if (operations == null || operations.isEmpty())
        {
            return NONE;
        }
        Map<String, ResultKind> kinds = new HashMap<>();
        for (Operation op : operations)
        {
            String id = op.getId();
            if (id != null)
            {
                kinds.put(id, operationKind(op));
            }
        }
        return ref -> kinds.getOrDefault(ref, ResultKind.SCALAR);
    }


    private static ResultKind operationKind(Operation op)
    {
        try
        {
            return OperationExecutor.resultKind(OperationExpressionParser.normalize(op));
        }
        catch (RuntimeException _)
        {
            return ResultKind.SCALAR;
        }
    }
}
