package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.loadCheckless;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.RegistryTestSeam;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W4 pack P2 at rule level ({@code PLAN-elementwise-list-functions} Q4 / Q7 / §3.3,
 * {@code PLAN-elementwise-boolean-predicates} P4): what a lifted function does with a list it may
 * not lift, when only the run knows the value is a list (an unknown-typed producer - stage A cannot
 * refuse it at load).
 * <ul>
 * <li>E6 (Q4): a list at a parameter other than 0 ({@code substring(X, $u)}) is the rule's ERROR -
 * before, {@code substring} read the list as a non-number and answered the computed missing,
 * silently;</li>
 * <li>E7a (Q7): a list element that is itself a list ({@code distinct([A, B])}'s tuples) is the
 * rule's ERROR - before, the lift folded the element's rendering ({@code "[A, B]"});</li>
 * <li>E8 residue: a bare-name list at the lifted parameter of a Check-less rule's binding
 * ({@code $u: upper([A, B])}) is a load error naming {@code values(…)};</li>
 * <li>EB4 residue: a predicate binding over a list known only at run time, read in a value position
 * ({@code $b == true}), is the rule's ERROR with the binding hint, never a silent answer.</li>
 * </ul>
 */
class LiftedListArgumentRuleTest
{

    private static final String NUMBERS = "__p2_numbers_probe__";

    private static final String DATES = "__p2_dates_probe__";

    /** An unknown-typed producer of a list known only at run time. */
    private static FunctionDescriptor probe(String name, List<?> value)
    {
        return new FunctionDescriptor(name, List.of(), FunctionKind.VALUE,
                (run, args) -> ConstVector.of(value));
    }


    /**
     * One rule: {@code check}, the bindings as (name, expression) pairs, every binding excluded
     * from the report ({@code !$x}) - a probe declares no result type, so a reported one would be
     * refused at load for want of a type source.
     */
    private static Rule load(String check, String... bindings)
    {
        StringBuilder b = new StringBuilder("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"R\"}");
        StringBuilder outputs = new StringBuilder("\"USUBJID\"");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"")
                        .append(SequenceFunctionTest.esc(bindings[i + 1])).append("\"}");
                if (bindings[i + 1].startsWith("__"))
                {
                    outputs.append(",\"!").append(bindings[i]).append('"');
                }
            }
            b.append(']');
        }
        b.append(",\"Check\":{\"expression\":\"").append(SequenceFunctionTest.esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[").append(outputs)
                .append("]}}}}");
        try
        {
            Rule r = net.cumba.corej.core.RulePackageLoader.loadFromString(b.toString()).getRules()
                    .get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static IDataTable ae()
    {
        return WithinOrderingSpliceTest.ae();
    }


    /**
     * The rule's ERROR: the run throws the {@link RuleDefinitionException} (D35 "written wrong"),
     * which {@code LibraryValidator.executeRule} turns into the ERROR result with its message.
     */
    private static String error(String check, String... bindings)
    {
        Rule rule = load(check, bindings);
        assertNull(rule.getLoadError(), () -> check + " -> " + rule.getLoadError());
        return String.valueOf(assertThrows(RuleDefinitionException.class,
                () -> RuleRunnerCalls.execute(rule, ae()), check).getMessage());
    }


    @Test
    void aListAtAnotherParameterKnownOnlyAtRunTimeErrorsTheRule()
    {
        try (RegistryTestSeam.Registration _ = RegistryTestSeam
                .register(probe(NUMBERS, List.of(1, 2))))
        {
            for (String check : List.of("not empty(substring(AETERM, $u))",
                    "not empty(substring(AETERM, 1, $u))", "not empty(prefix(AETERM, $u))",
                    "not empty(lpad(AETERM, $u))"))
            {
                String message = error(check, "$u", NUMBERS + "()");
                assertTrue(message.contains("not a list"), check + ": " + message);
                assertTrue(message.contains("lifted only at 'x'"), check + ": " + message);
            }
        }
    }


    @Test
    void aNestedListAtTheLiftedParameterErrorsTheRule()
    {
        String value = error("not empty(upper($d))", "$d", "distinct([AETERM, AEDECOD])");
        assertTrue(value.contains("argument 'x' of 'upper'"), value);
        assertTrue(value.contains("one list level only"), value);
        String inline = error("not empty(upper(distinct([AETERM, AEDECOD])))");
        assertTrue(inline.contains("one list level only"), inline);
        // the predicate path shares the element adapter
        String predicate = error("any(is_valid_date(distinct([AESTDTC, AEENDTC])))");
        assertTrue(predicate.contains("one list level only"), predicate);
    }


    @Test
    void aBareNameListAtTheLiftedParameterOfACheckLessBindingIsALoadError()
    {
        Rule checkless = loadCheckless("$u", "upper([AETERM, AEDECOD])");
        String error = checkless.getLoadError();
        assertNotNull(error, "a Check-less binding is judged at load too");
        assertTrue(error.contains("to read their values write values([AETERM, AEDECOD])"), error);
    }


    @Test
    void aPredicateBindingOverARunTimeListReadAsAValueErrorsTheRule()
    {
        try (RegistryTestSeam.Registration _ = RegistryTestSeam
                .register(probe(DATES, List.of("2020-01-01", "bogus"))))
        {
            String message = error("$b == true", "$u", DATES + "()", "$b", "is_valid_date($u)");
            assertTrue(message.contains("$b holds a list<boolean>"), message);
            assertTrue(message.contains("any($b)"), message);
        }
    }
}
