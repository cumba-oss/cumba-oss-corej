package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * The drift guard of the element-wise rosters ({@code PLAN-elementwise-list-functions} §3.4, owner
 * Q2, runbook W1a E4; {@code PLAN-elementwise-boolean-predicates} P2 / Q5, runbook W1a EB1):
 * exactly the flagged names by kind — the VALUE functions, each a pure scalar function over a plain
 * value parameter 0 whose registered function is the lift, and the BOOLEAN predicates, each a pure
 * {@code BitSet} kernel whose lift answers a {@link BoolListVector}; the descriptor guard refusing
 * every shape that cannot lift; the language surface listing the marker. A new flagged function
 * joins its roster on purpose (runbook W2 E5 flags the rest of census class (a); a predicate joins
 * {@link #BOOLEAN_ROSTER} only by an owner ruling — Q1 is opt-in), never by accident.
 */
class ElementwiseLiftRosterDriftTest
{

    /**
     * The flagged VALUE set after runbook W2 E5: E4's case folds and their aliases, the 17 further
     * names of census class (a) ({@code PLAN-elementwise-list-functions} §7.1) and the five
     * conversions / formatters registered since {@code PLAN-dynamic-column-functions} (owner Q2:
     * {@code num}, {@code str}, {@code date}, {@code time}, {@code lpad}) — 26 names, 22 of them
     * flagged by E5.
     */
    static final Set<String> ROSTER = Set.of("upper", "upcase", "lower", "lowcase", "abs", "ceil",
            "floor", "round", "char", "day", "month", "year", "earliest_possible",
            "latest_possible", "len", "length", "normalize_space", "trim", "prefix", "suffix",
            "substring", "num", "str", "date", "time", "lpad");

    /**
     * The value parameter 0 each roster function's purity probe hands its kernel: an ISO date for
     * the temporal and date-part functions, a numeric text for the numeric ones, plain text for the
     * rest — chosen so the kernel answers a present value.
     */
    private static String probeValue(String name)
    {
        return switch (name)
        {
        case "day", "month", "year", "earliest_possible", "latest_possible", "date" -> "2020-01-15";
        case "time" -> "09:15";
        case "abs", "ceil", "floor", "round", "num" -> "-1.5";
        default -> " Ab c ";
        };
    }


    /**
     * The other arguments a roster function's probe binds — one slot per declared parameter,
     * {@code null} at an absent optional as the compiler binds a call; the required numbers of
     * {@code prefix} / {@code suffix} ({@code n}), {@code substring} ({@code start}) and
     * {@code lpad} ({@code width}) broadcast constants.
     */
    private static java.util.List<Vector> probeArgs(FunctionDescriptor d, Vector x)
    {
        Vector[] args = new Vector[d.parameters().size()];
        args[0] = x;
        for (int i = 1; i < args.length; i++)
        {
            Parameter p = d.parameters().get(i);
            args[i] = p.required() ? ConstVector.of(2L) : null;
        }
        return java.util.Arrays.asList(args);
    }

    /**
     * The flagged BOOLEAN set after EB1: the 16 one-subject well-formedness predicates the owner
     * ruled (Q5, 2026-10-05); the 11 two-operand predicates wait for
     * {@code PLAN-engine-gap-list-elementwise}.
     */
    static final Set<String> BOOLEAN_ROSTER = Set.of("is_valid_date", "invalid_date",
            "is_complete_date", "is_incomplete_date", "is_partial_date", "is_complete_date_part",
            "is_not_complete_date_part", "is_valid_duration", "invalid_duration", "is_integer",
            "is_not_integer", "is_numeric", "has_alpha", "has_digit", "is_valid_name",
            "is_valid_testcd");

    private static final EvalFunction NOOP = (run, args) -> args.get(0);

    @Test
    void theFlaggedSetIsExactlyTheRosterByKind()
    {
        Set<String> values = new TreeSet<>();
        Set<String> predicates = new TreeSet<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            if (d.elementwise())
            {
                (d.kind() == FunctionKind.BOOLEAN ? predicates : values).add(d.name());
            }
        }
        assertEquals(new TreeSet<>(ROSTER), values,
                "a flagged value function joined or left the roster — read"
                        + " PLAN-elementwise-list-functions §3.4 / §7.1 before changing ROSTER");
        assertEquals(new TreeSet<>(BOOLEAN_ROSTER), predicates,
                "a flagged predicate joined or left the roster — read"
                        + " PLAN-elementwise-boolean-predicates Q1 / Q5 before changing BOOLEAN_ROSTER");
        assertEquals(16, BOOLEAN_ROSTER.size());
    }


    @Test
    void everyFlaggedPredicateIsAPureBitSetKernelWhoseFunctionIsTheLift()
    {
        for (String name : BOOLEAN_ROSTER)
        {
            FunctionDescriptor d = FunctionRegistry.descriptor(name);
            assertNotNull(d, name);
            assertEquals(FunctionKind.BOOLEAN, d.kind(), name);
            assertFalse(d.aggregate() || d.perRow() || d.perVariableMap(), name);
            Parameter x = d.parameters().get(0);
            assertTrue(x.required() && !x.collector() && !x.nameSlot(), name + " parameter 0");
            assertFalse(x.type() == Primitive.COLUMN_REFERENCE || x.type() == Primitive.REGEX
                    || x.type() instanceof ListOf, name + " parameter 0 type");
            EvalFunction fn = d.fn();
            assertNotNull(fn, name);
            assertTrue(ElementwiseLift.isLifted(fn), name + " publishes the lifted function");
            // purity (§3.4): a context-free run; a scalar answers the kernel's BitSet, a list a
            // BoolListVector of the list's cardinality — never run.ctx()
            EvalRun run = EvalRun.ofRowCount(1);
            // one slot per declared parameter, null at an absent optional (invalid_duration's
            // negative=), as the compiler binds a call
            Vector[] scalarArgs = new Vector[d.parameters().size()];
            scalarArgs[0] = ConstVector.of("2020-01-01");
            Object scalar = fn.apply(run, java.util.Arrays.asList(scalarArgs));
            assertTrue(scalar instanceof java.util.BitSet, name + " answers a BitSet on a scalar");
            Vector[] listArgs = new Vector[d.parameters().size()];
            listArgs[0] = ConstVector.of(List.of("2020-01-01", "x", "1"));
            Object lifted = fn.apply(run, java.util.Arrays.asList(listArgs));
            BoolListVector list = assertInstanceOf(BoolListVector.class, lifted, name);
            assertEquals(3, ((List<?>) list.value(0).resolved()).size(), name + " lifts a list");
        }
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
            // purity (§3.4): a context-free run (EvalRun.ofRowCount, no context — a kernel that
            // read run.ctx() throws here), a scalar and a list; the list answers, element by
            // element, exactly what the kernel answers for each element alone. len's unit= /
            // encoding= are absent here (the default chars arm): BL1 resolves a byte count's
            // default encoding in the COMPILER (W2-D11), so the kernel never reads the context.
            EvalRun run = EvalRun.ofRowCount(1);
            String value = probeValue(name);
            EvalFunction kernel = ElementwiseLift.kernelOf(fn);
            Object scalar = ((Vector) fn.apply(run, probeArgs(d, ConstVector.of(value)))).value(0)
                    .resolved();
            Object alone = ((Vector) kernel.apply(run, probeArgs(d, ConstVector.of(value))))
                    .value(0).resolved();
            assertEquals(String.valueOf(alone), String.valueOf(scalar),
                    name + " answers a scalar as its kernel does");
            Object lifted = ((Vector) fn.apply(run,
                    probeArgs(d, ConstVector.of(List.of(value, value))))).value(0).resolved();
            assertTrue(lifted instanceof List<?> list && list.size() == 2,
                    name + " lifts a list: " + lifted);
            for (Object element : (List<?>) lifted)
            {
                String text = element instanceof net.cumba.datatable.values.IDataValue cell
                        ? cell.getValueAsString()
                        : String.valueOf(element);
                assertEquals(String.valueOf(alone), text,
                        name + " answers each element as the kernel answers it alone");
            }
        }
    }


    @Test
    void theAliasesShareTheirKernel()
    {
        // upcase / lowcase are registered over the same EvalFunction as upper / lower, so they lift
        // identically (W2 UA relies on it); so is length over len (E5)
        assertSame(kernel("upper"), kernel("upcase"));
        assertSame(kernel("lower"), kernel("lowcase"));
        assertSame(kernel("len"), kernel("length"));
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

        // runbook W1a EB1: a BOOLEAN kind lifts too (PLAN-elementwise-boolean-predicates P2)
        FunctionDescriptor predicate = new FunctionDescriptor("f",
                List.of(Parameter.required("x", Unknown.UNKNOWN)), FunctionKind.BOOLEAN,
                (run, args) -> new java.util.BitSet()).liftingElementwise();
        assertTrue(predicate.elementwise(), "a BOOLEAN predicate lifts");
        assertEquals(FunctionKind.BOOLEAN, predicate.kind(), "the kind is kept");
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
            // the parameter rendering differs (prefix's n, substring's start / length, len's
            // unit= / encoding=, lpad's width / fill)
            assertTrue(surface.lines().anyMatch(line -> line.startsWith(name + "(x:unknown")
                    && line.endsWith(" kind=value elementwise")), name);
        }
        assertTrue(surface.contains(
                "\nlen(x:unknown, unit:string?, encoding:string?) kind=value elementwise\n"),
                "len is flagged in E5 with BL1's unit= / encoding= kept");
        assertTrue(surface.contains("\nduration(x:unknown) kind=value\n"),
                "duration is no census class (a) name and stays unflagged");
        for (String name : BOOLEAN_ROSTER)
        {
            // the parameter rendering differs (x:string, x:unknown, invalid_duration's negative=)
            assertTrue(surface.lines().anyMatch(line -> line.startsWith(name + "(")
                    && line.endsWith(" kind=boolean elementwise")), name);
        }
        assertTrue(
                surface.contains("\nbetween(x:unknown, lo:unknown, hi:unknown) kind=boolean\n")
                        || surface.lines()
                                .anyMatch(line -> line.startsWith("between(")
                                        && line.endsWith(" kind=boolean")),
                "a two-operand predicate is not flagged in EB1");
    }
}
