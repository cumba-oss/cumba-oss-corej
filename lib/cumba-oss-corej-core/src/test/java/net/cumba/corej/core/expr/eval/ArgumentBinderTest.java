package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
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


    /**
     * Runbook W4 U2 ({@code PLAN-union-function} §3.2, owner Q3 #5 "Positional-only"): a collector
     * call takes positional arguments only. Before U2 the binder checked the FIRST keyword
     * argument's name only and, when it named a leading parameter, returned the positionals alone —
     * so {@code printf("%s", format="x")}, {@code tuple(A, B, c1=C)} and
     * {@code union($a, $b, first=$c)} bound and silently dropped the keyword argument, and a second
     * keyword argument was never checked at all.
     */
    @Test
    void aCollectorCallRefusesEveryKeywordArgument()
    {
        Expr str = new Expr.Lit(Expr.LitKind.STRING, "%s");
        Expr x = new Expr.Lit(Expr.LitKind.STRING, "x");
        Expr a = new Expr.Ref("$a", OperandKind.OPERATION_REF);
        Expr b = new Expr.Ref("$b", OperandKind.OPERATION_REF);
        Expr c = new Expr.Ref("$c", OperandKind.OPERATION_REF);
        for (Expr.Call call : List.of(new Expr.Call("printf", List.of(str), Map.of("format", x)),
                new Expr.Call("tuple", List.of(ref("A"), ref("B")), Map.of("c1", ref("C"))),
                new Expr.Call("union", List.of(a, b), Map.of("first", c))))
        {
            FunctionDescriptor d = FunctionRegistry.descriptor(call.name());
            ExpressionException refused = assertThrows(ExpressionException.class,
                    () -> ArgumentBinder.bind(d, call), call.toString());
            assertTrue(
                    refused.getMessage()
                            .startsWith("'" + call.name() + "' takes positional arguments only"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("cannot address a collector's elements"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("(parameters: "), refused.getMessage());
        }
        // two keyword arguments: the second one was never looked at
        Map<String, Expr> two = new LinkedHashMap<>();
        two.put("c1", ref("C"));
        two.put("zz", ref("D"));
        Expr.Call twoKwargs = new Expr.Call("tuple", List.of(ref("A"), ref("B")), two);
        assertThrows(ExpressionException.class,
                () -> ArgumentBinder.bind(FunctionRegistry.descriptor("tuple"), twoKwargs));
    }


    /** U2: the positional forms of the three collector calls bind exactly as before. */
    @Test
    void aCollectorCallStillBindsItsPositionals()
    {
        Expr a = new Expr.Ref("$a", OperandKind.OPERATION_REF);
        Expr b = new Expr.Ref("$b", OperandKind.OPERATION_REF);
        Expr c = new Expr.Ref("$c", OperandKind.OPERATION_REF);
        assertEquals(List.of(a, b, c), ArgumentBinder.bind(FunctionRegistry.descriptor("union"),
                new Expr.Call("union", List.of(a, b, c), Map.of())));
        assertEquals(List.of(ref("A"), ref("B"), ref("C")),
                ArgumentBinder.bind(FunctionRegistry.descriptor("tuple"),
                        new Expr.Call("tuple", List.of(ref("A"), ref("B"), ref("C")), Map.of())));
        Expr str = new Expr.Lit(Expr.LitKind.STRING, "%s-%s");
        assertEquals(List.of(str, ref("A"), ref("B")),
                ArgumentBinder.bind(FunctionRegistry.descriptor("printf"),
                        new Expr.Call("printf", List.of(str, ref("A"), ref("B")), Map.of())));
    }

}
