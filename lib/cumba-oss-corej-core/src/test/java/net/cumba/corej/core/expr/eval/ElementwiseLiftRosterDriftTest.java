package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RuleLanguage;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import org.junit.jupiter.api.Test;

/**
 * The drift guard of the element-wise roster ({@code PLAN-elementwise-list-functions} §3.4, owner
 * Q2; runbook W1a E4): exactly the flagged names, each a pure scalar value function over a plain
 * value parameter 0 whose registered function is the lift; the descriptor guard refusing every
 * shape that cannot lift; the language surface listing the marker. A new flagged function joins
 * {@link #ROSTER} on purpose (runbook W2 E5 flags the rest of census class (a)), never by accident.
 */
class ElementwiseLiftRosterDriftTest
{

    /** The flagged set after E4: the case folds and their aliases. */
    static final Set<String> ROSTER = Set.of("upper", "upcase", "lower", "lowcase");

    private static final EvalFunction NOOP = (run, args) -> args.get(0);

    @Test
    void theFlaggedSetIsExactlyTheRoster()
    {
        Set<String> flagged = new TreeSet<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            if (d.elementwise())
            {
                flagged.add(d.name());
            }
        }
        assertEquals(new TreeSet<>(ROSTER), flagged,
                "a flagged descriptor joined or left the roster — read PLAN-elementwise-list-functions"
                        + " §3.4 / §7.1 before changing ROSTER");
    }


    @Test
    void everyFlaggedDescriptorIsAPureScalarValueFunctionWhoseFunctionIsTheLift()
    {
        for (String name : ROSTER)
        {
            FunctionDescriptor d = FunctionRegistry.descriptor(name);
            assertNotNull(d, name);
            assertEquals(FunctionKind.VALUE, d.kind(), name);
            assertFalse(d.aggregate() || d.perRow() || d.perVariableMap(), name);
            Parameter x = d.parameters().get(0);
            assertTrue(x.required() && !x.collector() && !x.nameSlot(), name + " parameter 0");
            assertFalse(x.type() == Primitive.COLUMN_REFERENCE || x.type() == Primitive.REGEX
                    || x.type() instanceof ListOf, name + " parameter 0 type");
            EvalFunction fn = d.fn();
            assertNotNull(fn, name);
            assertTrue(ElementwiseLift.isLifted(fn), name + " publishes the lifted function");
            // purity (§3.4): a context-free run, a scalar and a list — never run.ctx()
            EvalRun run = EvalRun.ofRowCount(1);
            assertEquals("A", ((Vector) fn.apply(run, List.of(ConstVector.of("a")))).value(0)
                    .resolved().toString().toUpperCase(java.util.Locale.ROOT), name);
            Object folded = ((Vector) fn.apply(run, List.of(ConstVector.of(List.of("a", "B")))))
                    .value(0).resolved();
            assertTrue(folded instanceof List<?> list && list.size() == 2, name + " lifts a list");
        }
    }


    @Test
    void theAliasesShareTheirKernel()
    {
        // upcase / lowcase are registered over the same EvalFunction as upper / lower, so they lift
        // identically (W2 UA relies on it)
        assertSame(kernel("upper"), kernel("upcase"));
        assertSame(kernel("lower"), kernel("lowcase"));
    }


    private static EvalFunction kernel(String name)
    {
        FunctionDescriptor d = FunctionRegistry.descriptor(name);
        assertNotNull(d);
        EvalFunction fn = d.fn();
        assertNotNull(fn);
        return ElementwiseLift.kernelOf(fn);
    }


    @Test
    void theGuardRefusesEveryShapeThatCannotLift()
    {
        FunctionDescriptor plain = new FunctionDescriptor("f",
                List.of(Parameter.required("x", Unknown.UNKNOWN)), FunctionKind.VALUE, NOOP);
        assertTrue(plain.liftingElementwise().elementwise(), "a plain value function lifts");
        FunctionDescriptor lifted = plain.liftingElementwise();
        assertSame(lifted, lifted.liftingElementwise(), "idempotent");

        refuses(new FunctionDescriptor("f", List.of(Parameter.required("x", Unknown.UNKNOWN)),
                FunctionKind.BOOLEAN, NOOP), "a BOOLEAN kind (EB lifts predicates later)");
        refuses(new FunctionDescriptor("f", List.of(), FunctionKind.VALUE, NOOP), "nullary");
        refuses(new FunctionDescriptor("f", List.of(Parameter.required("x", Unknown.UNKNOWN)),
                FunctionKind.VALUE, null), "no implementation");
        refuses(plain.aggregating(), "an aggregate");
        refuses(plain.readingRows(), "a row reader");
        refuses(plain.answeringPerVariable(), "a per-variable map");
        refuses(new FunctionDescriptor("f",
                List.of(Parameter.collector("x", new ListOf(Unknown.UNKNOWN))), FunctionKind.VALUE,
                NOOP), "a collector parameter 0");
        refuses(new FunctionDescriptor("f",
                List.of(Parameter.required("x", Primitive.COLUMN_REFERENCE)), FunctionKind.VALUE,
                NOOP), "a column-reference parameter 0");
        refuses(new FunctionDescriptor("f", List.of(Parameter.required("x", Primitive.REGEX)),
                FunctionKind.VALUE, NOOP), "a regex parameter 0");
        refuses(new FunctionDescriptor("f",
                List.of(Parameter.required("x", new ListOf(Primitive.STRING))), FunctionKind.VALUE,
                NOOP), "a list parameter 0");
        refuses(new FunctionDescriptor("f",
                List.of(Parameter.required("x", Unknown.UNKNOWN).asNameSlot()), FunctionKind.VALUE,
                NOOP), "a name slot at parameter 0 (runbook R2)");

        // and the other builders refuse a flagged descriptor in turn
        assertThrows(IllegalArgumentException.class, lifted::aggregating);
        assertThrows(IllegalArgumentException.class, lifted::readingRows);
        assertThrows(IllegalArgumentException.class, lifted::answeringPerVariable);
        assertTrue(lifted.withProvider(ProviderNeed.LIBRARY).elementwise(),
                "a provider keeps the flag");
    }


    private static void refuses(FunctionDescriptor d, String why)
    {
        assertThrows(IllegalArgumentException.class, d::liftingElementwise, why);
    }


    @Test
    void theLanguageSurfaceListsTheMarker()
    {
        String surface = RuleLanguage.surface();
        for (String name : ROSTER)
        {
            assertTrue(surface.contains("\n" + name + "(x:unknown) kind=value elementwise\n"),
                    name);
        }
        assertTrue(surface.contains("\nlen(x:unknown) kind=value\n"), "len is not flagged in E4");
    }
}
