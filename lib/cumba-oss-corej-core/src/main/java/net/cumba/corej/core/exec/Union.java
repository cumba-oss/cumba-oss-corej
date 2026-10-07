package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The registry function {@code union(a, b, more…)} ({@code PLAN-union-function}, runbook W1a U1):
 * the set union of two or more dataset-level lists, in the order of first occurrence — the first
 * operand's members in its order, then each later operand's members not yet seen. One list for the
 * dataset ({@link ListFunctionSupport#broadcast}), the {@code ListValueGuard} birth site, exactly
 * as {@link Minus}.
 *
 * <ul>
 * <li><b>Operands are lists only</b> (owner Q7): a scalar operand is a
 * {@link RuleDefinitionException} at evaluation — the authored form for a single value is its
 * singleton literal {@code ["X"]}. {@code minus} keeps its singleton coercion until
 * {@code PLAN-minus-lists-only} (W3).</li>
 * <li><b>Duplicates</b> (Q4): a member occurs once, its first occurrence kept with its original
 * element — within one operand as across operands.</li>
 * <li><b>Elements compare as set elements</b> — the same identity {@code minus} uses
 * ({@link Minus#setElement}): a text by its text, a missing value by its identity ({@code MIS_A} ≠
 * {@code MIS_B} ≠ the present text {@code "."}), a row tuple structurally.</li>
 * <li><b>One shape</b> (Q6): texts and tuples do not mix, nor tuples of different arity — a mixed
 * union is a {@link RuleDefinitionException}; a missing is exempt (it is a member of either
 * shape).</li>
 * <li>An operand read from a non-constant vector is read at row 0 (a dataset-level list arrives
 * either as a {@code ConstVector} or as the same list on every row, {@code upper($list)}); a
 * zero-row non-constant operand is the empty list, as for {@code minus}.</li>
 * </ul>
 */
public final class Union
{

    public static final String NAME = "union";

    public static final String FIRST_PARAMETER = "first";

    public static final String SECOND_PARAMETER = "second";

    public static final String MORE_PARAMETER = "more";

    private Union()
    {
    }


    /**
     * Evaluates {@code union} over the flat operand list (the collector form hands every operand
     * positionally).
     *
     * @param run
     *            the evaluation run (its row count decides how a non-constant operand is read)
     * @param args
     *            the operand vectors, two or more
     * @return one {@code ConstVector} holding the union
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        // first occurrence wins, insertion order kept: LinkedHashMap keyed by the set element
        Map<Object, Object> members = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++)
        {
            for (Object element : listOnly(args.get(i), run.rowCount(), i))
            {
                members.putIfAbsent(element, element);
            }
        }
        List<Object> union = new ArrayList<>(members.values());
        requireOneShape(union);
        return ListFunctionSupport.broadcast(NAME, union);
    }


    /**
     * The list-only operand reader: {@link Minus#rawOperand}'s value, which must be a
     * {@link Collection} (an array is refused as in {@code normalizeToList}, NNL §1), each element
     * keyed by {@link Minus#setElement}.
     */
    private static List<Object> listOnly(@Nullable Vector operand, int rowCount, int position)
    {
        Object value = Minus.rawOperand(operand, rowCount);
        if (value == null)
        {
            return List.of();
        }
        if (value.getClass().isArray())
        {
            throw new IllegalStateException("an array is not a list value (NNL §1)");
        }
        if (!(value instanceof Collection<?> c))
        {
            throw new RuleDefinitionException(
                    NAME + "(…) operand " + (position + 1) + " is not a list (" + value
                            + "): a set union reads lists only — a single value"
                            + " is written as its singleton list literal [\"" + value + "\"]");
        }
        List<Object> out = new ArrayList<>(c.size());
        for (Object item : ListValueGuard.elements(c))
        {
            out.add(Minus.setElement(item));
        }
        return out;
    }


    /** Q6: the kept members are all texts, or all tuples of one arity; a missing is exempt. */
    private static void requireOneShape(List<Object> union)
    {
        int tupleArity = -1;
        boolean text = false;
        for (Object member : union)
        {
            if (member instanceof MissingValue)
            {
                continue;
            }
            if (member instanceof List<?> tuple)
            {
                if (text || (tupleArity >= 0 && tupleArity != tuple.size()))
                {
                    throw mixed(union);
                }
                tupleArity = tuple.size();
            }
            else
            {
                if (tupleArity >= 0)
                {
                    throw mixed(union);
                }
                text = true;
            }
        }
    }


    private static RuleDefinitionException mixed(List<Object> union)
    {
        return new RuleDefinitionException(NAME + "(…) mixes texts and row tuples, or tuples of"
                + " different arity, in one set: " + union
                + " — a set union has one element shape");
    }
}
