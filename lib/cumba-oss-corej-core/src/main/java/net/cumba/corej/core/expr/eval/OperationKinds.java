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
     * The kinds declared by {@code rule}'s {@code Operations} block. A Form-B ({@code expression})
     * declaration is normalised through the same parser the loader uses; a declaration the parser
     * rejects degrades to {@link ResultKind#SCALAR} rather than propagating — the loader reports
     * that rule's error on its own channel.
     */
    static OperationKinds forRule(Rule rule)
    {
        return forOperations(rule.getOperations());
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
            if (id == null)
            {
                continue;
            }
            ResultKind kind;
            try
            {
                kind = OperationExecutor.resultKind(OperationExpressionParser.normalize(op));
            }
            catch (RuntimeException _)
            {
                kind = ResultKind.SCALAR;
            }
            kinds.put(id, kind);
        }
        return ref -> kinds.getOrDefault(ref, ResultKind.SCALAR);
    }
}
