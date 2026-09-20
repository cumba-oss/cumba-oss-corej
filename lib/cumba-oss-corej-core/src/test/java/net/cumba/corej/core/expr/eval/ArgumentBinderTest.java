package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import org.junit.jupiter.api.Test;

/**
 * Phase 6b — D19a's argument binding, verbatim: positional arguments bind by position; once a named
 * argument appears no positional may follow (enforced by {@code CheckExpressionParser}, covered
 * there); and a name a positional already bound may not be given again. Two distinct errors, two
 * distinct messages — plus the structural errors the retired {@code (name, arity)} key used to
 * express.
 */
class ArgumentBinderTest
{

    private static final FunctionDescriptor SUBSTRING = new FunctionDescriptor("substring",
            List.of(Parameter.required("x", Unknown.UNKNOWN),
                    Parameter.required("start", Primitive.NUMBER),
                    Parameter.optional("length", Primitive.NUMBER)),
            FunctionKind.VALUE, (_, _) -> null);

    private static final FunctionDescriptor TUPLE = new FunctionDescriptor("tuple",
            List.of(Parameter.required("c1", Primitive.COLUMN_REFERENCE),
                    Parameter.required("c2", Primitive.COLUMN_REFERENCE),
                    Parameter.collector("columns", new ListOf(Primitive.COLUMN_REFERENCE))),
            FunctionKind.VALUE, (_, _) -> null);

    private static Expr ref(String name)
    {
        return new Expr.Ref(name, OperandKind.COLUMN);
    }


    private static Expr num(double d)
    {
        return new Expr.Lit(Expr.LitKind.NUMBER, d);
    }


    private static Expr.Call call(List<Expr> args, Map<String, Expr> kwargs)
    {
        return new Expr.Call("substring", args, kwargs);
    }


    @Test
    void positionalsBindByPosition()
    {
        List<Expr> bound = ArgumentBinder.bind(SUBSTRING,
                call(List.of(ref("V"), num(1), num(4)), Map.of()));
        assertEquals(List.of(ref("V"), num(1), num(4)), bound);
    }


    @Test
    void absentOptionalParameterIsANullSlot()
    {
        List<Expr> bound = ArgumentBinder.bind(SUBSTRING,
                call(List.of(ref("V"), num(1)), Map.of()));
        assertEquals(3, bound.size(), "one slot per declared parameter");
        assertNull(bound.get(2), "absent optional = null slot (the implementation's default)");
    }


    @Test
    void namedArgumentBindsItsParameter()
    {
        List<Expr> bound = ArgumentBinder.bind(SUBSTRING,
                call(List.of(ref("V"), num(1)), Map.of("length", num(4))));
        assertEquals(num(4), bound.get(2));
        // and a named argument may bind a REQUIRED parameter a positional did not reach
        List<Expr> named = ArgumentBinder.bind(SUBSTRING,
                call(List.of(ref("V")), Map.of("start", num(2), "length", num(4))));
        assertEquals(num(2), named.get(1));
    }


    /** D19a's second rule: a name a positional already bound may not be given again. */
    @Test
    void rebindingAPositionallyBoundParameterIsItsOwnError()
    {
        ExpressionException ex = assertThrows(ExpressionException.class, () -> ArgumentBinder
                .bind(SUBSTRING, call(List.of(ref("V"), num(1)), Map.of("start", num(2)))));
        assertTrue(ex.getMessage().contains("already bound by position"), ex.getMessage());
    }


    @Test
    void unknownArgumentNameIsRejectedNamingTheParameters()
    {
        ExpressionException ex = assertThrows(ExpressionException.class, () -> ArgumentBinder
                .bind(SUBSTRING, call(List.of(ref("V"), num(1)), Map.of("frobnicate", num(2)))));
        assertTrue(ex.getMessage().contains("no parameter 'frobnicate'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("start"), "message lists the parameters");
    }


    @Test
    void missingRequiredParameterIsRejected()
    {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> ArgumentBinder.bind(SUBSTRING, call(List.of(ref("V")), Map.of())));
        assertTrue(ex.getMessage().contains("requires argument 'start'"), ex.getMessage());
    }


    @Test
    void tooManyPositionalsIsRejected()
    {
        ExpressionException ex = assertThrows(ExpressionException.class, () -> ArgumentBinder
                .bind(SUBSTRING, call(List.of(ref("V"), num(1), num(4), num(5)), Map.of())));
        assertTrue(ex.getMessage().contains("at most 3"), ex.getMessage());
    }


    /** §1.5's sugar: a trailing collector absorbs the remaining positionals, flat. */
    @Test
    void collectorAbsorbsRemainingPositionals()
    {
        Expr.Call two = new Expr.Call("tuple", List.of(ref("A"), ref("B")), Map.of());
        assertEquals(List.of(ref("A"), ref("B")), ArgumentBinder.bind(TUPLE, two));
        Expr.Call four = new Expr.Call("tuple", List.of(ref("A"), ref("B"), ref("C"), ref("D")),
                Map.of());
        assertEquals(4, ArgumentBinder.bind(TUPLE, four).size());
        Expr.Call one = new Expr.Call("tuple", List.of(ref("A")), Map.of());
        assertThrows(ExpressionException.class, () -> ArgumentBinder.bind(TUPLE, one),
                "the fixed leading parameters stay required");
    }

}
