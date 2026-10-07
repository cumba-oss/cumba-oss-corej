package net.cumba.corej.core.expr.eval;

import java.util.List;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.ExprType;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ The one answer to <em>"is this argument of this call a name?"</em>
 * ({@code PLAN-string-literal-prefix-rewrite} S2 / S6 / S7): whether a STRING literal at a call's
 * position or keyword names a column or a dataset — and so is specialised by the bind-time
 * {@code --} resolver ({@code exec.ExprPrefixResolver}) and may stand for a name-form binding
 * ({@code expr.convert.BindingInliner}) — or is a value, verbatim.
 *
 * <p>
 * Three sources, in order:
 * </p>
 * <ol>
 * <li><b>the registry</b> — the call's {@link FunctionDescriptor}: the parameter the argument binds
 * to ({@link ArgumentBinder}'s rule: a positional binds by position, a trailing collector absorbs
 * the rest, a keyword by name) is a name slot when it declares one ({@link Parameter#nameSlot()})
 * or is typed {@link ExprType.Primitive#DATASET_REFERENCE};</li>
 * <li><b>the metadata accessors</b> ({@link MetadataAttribute}: {@code var_label},
 * {@code ds_class}, …, which have no descriptor — {@code ExprCompiler.metadataPlan} compiles them):
 * argument 0 of the two-argument form {@code (name, level)} — in the one-argument form
 * {@code (level)} argument 0 is the level — and, for a variable-scope accessor,
 * {@code dataset=};</li>
 * <li><b>anything else</b> has no name slot (S7): its STRING literals are values.</li>
 * </ol>
 *
 * <p>
 * ⛔ Never a name list here. {@code NameSlotRosterTest} measures which slots read a literal as a
 * name, by behaviour, and holds the declarations to it, so a function added with an undeclared name
 * slot turns that test red instead of silently losing its {@code --} specialisation.
 * </p>
 */
public final class NameSlots
{

    /** The question, as a value — the resolver's seam for a planted control. */
    public interface Lookup
    {

        /**
         * Whether a STRING literal at a positional argument of {@code call} is a name.
         *
         * @param call
         *            the call
         * @param position
         *            the positional argument index
         * @return whether a STRING literal at that position is a name
         */
        boolean isNameSlot(Expr.Call call, int position);


        /**
         * Whether a STRING literal given for a keyword of {@code call} is a name.
         *
         * @param call
         *            the call
         * @param keyword
         *            the keyword argument's name
         * @return whether a STRING literal given for that keyword is a name
         */
        boolean isNameSlot(Expr.Call call, String keyword);
    }

    /** The registry-backed lookup (the class javadoc's three sources). */
    public static final Lookup REGISTRY = new Lookup()
    {

        @Override
        public boolean isNameSlot(Expr.Call call, int position)
        {
            return NameSlots.isNameSlot(call, position);
        }


        @Override
        public boolean isNameSlot(Expr.Call call, String keyword)
        {
            return NameSlots.isNameSlot(call, keyword);
        }
    };

    /** The keyword of a variable-scope metadata accessor's foreign-dataset read (§9.D). */
    private static final String DATASET_KEYWORD = "dataset";

    private NameSlots()
    {
    }


    /**
     * Whether a declared parameter is a name slot: it declares one, or its type is a dataset
     * reference.
     *
     * @param parameter
     *            the parameter
     * @return whether a STRING literal bound to it is a name
     */
    public static boolean isNameSlot(Parameter parameter)
    {
        return parameter.nameSlot() || parameter.type() == ExprType.Primitive.DATASET_REFERENCE;
    }


    /**
     * Whether a STRING literal at positional argument {@code position} of {@code call} is a name.
     *
     * @param call
     *            the call
     * @param position
     *            the positional index
     * @return the answer of the first source that knows the call (class javadoc)
     */
    public static boolean isNameSlot(Expr.Call call, int position)
    {
        FunctionDescriptor d = FunctionRegistry.descriptor(call.name());
        if (d != null)
        {
            Parameter p = parameterAt(d, position);
            return p != null && isNameSlot(p);
        }
        MetadataAttribute attr = MetadataAttribute.fromFunction(call.name());
        return attr != null && position == 0 && call.args().size() == 2;
    }


    /**
     * Whether a STRING literal given for keyword {@code keyword} of {@code call} is a name.
     *
     * @param call
     *            the call
     * @param keyword
     *            the keyword
     * @return the answer of the first source that knows the call (class javadoc)
     */
    public static boolean isNameSlot(Expr.Call call, String keyword)
    {
        FunctionDescriptor d = FunctionRegistry.descriptor(call.name());
        if (d != null)
        {
            Parameter p = d.parameter(keyword);
            return p != null && isNameSlot(p);
        }
        MetadataAttribute attr = MetadataAttribute.fromFunction(call.name());
        return attr != null && attr.scope() == MetadataAttribute.Scope.VARIABLE
                && DATASET_KEYWORD.equals(keyword);
    }


    /**
     * The parameter positional argument {@code position} binds to, or {@code null} when the call
     * carries more positionals than the descriptor declares (a binding error reported elsewhere).
     *
     * @param d
     *            the descriptor
     * @param position
     *            the positional index
     * @return the parameter, or {@code null}
     */
    public static @Nullable Parameter parameterAt(FunctionDescriptor d, int position)
    {
        List<Parameter> params = d.parameters();
        if (position < params.size())
        {
            return params.get(position);
        }
        Parameter last = params.isEmpty() ? null : params.get(params.size() - 1);
        return last != null && last.collector() ? last : null;
    }
}
