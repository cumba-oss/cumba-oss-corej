package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import org.junit.jupiter.api.Test;

/** Phase 6b — the descriptor model's own construction contracts (D19a / SPEC §3.1). */
class ParameterContractTest
{

    @Test
    void parameterRejectsAnEmptyNameANullTypeAndANonListCollector()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new Parameter("", Unknown.UNKNOWN, true, false));
        assertThrows(IllegalArgumentException.class, () -> new Parameter("x", null, true, false));
        // a collector parameter must have a list type (§1.5 — it IS the collection argument)
        assertThrows(IllegalArgumentException.class,
                () -> new Parameter("rest", Primitive.STRING, false, true));
    }


    @Test
    void descriptorRejectsAnEmptyNameANullKindAndANonTrailingCollector()
    {
        List<Parameter> ok = List.of(Parameter.required("x", Unknown.UNKNOWN));
        assertThrows(IllegalArgumentException.class,
                () -> new FunctionDescriptor("", ok, FunctionKind.VALUE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new FunctionDescriptor("f", ok, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new FunctionDescriptor("f",
                        List.of(Parameter.collector("rest", new ListOf(Primitive.STRING)),
                                Parameter.required("x", Unknown.UNKNOWN)),
                        FunctionKind.VALUE, null));
    }


    @Test
    void descriptorAccessors()
    {
        FunctionDescriptor d = new FunctionDescriptor("f",
                List.of(Parameter.required("a", Unknown.UNKNOWN),
                        Parameter.optional("b", Primitive.NUMBER)),
                FunctionKind.VALUE, null);
        assertEquals(1, d.minArity());
        assertEquals(2, d.maxArity());
        assertEquals("b", d.parameter("b").name());
        assertNull(d.parameter("nope"));
        FunctionDescriptor collector = new FunctionDescriptor("g",
                List.of(Parameter.collector("rest", new ListOf(Primitive.STRING))),
                FunctionKind.VALUE, null);
        assertEquals(Integer.MAX_VALUE, collector.maxArity());
        assertEquals(0, collector.minArity());
    }

}
