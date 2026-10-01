package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.spi.BuiltinFunctions;
import net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.StageAChecker;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-stage-a-parameter-type-arming} §3.3 (phase 4): the R1 literal seam is retired in the
 * loader's seam pass for every descriptor the armed stage A parameter-checks, and KEPT there for
 * the column-reference-bearing descriptors stage A's {@code call()} short-circuits before binding a
 * descriptor ({@code ExprCompiler.STAGE_A_SHORT_CIRCUITED_COLUMN_CALLS}). That kept set is a hand
 * transcription, so this test re-derives it on every run from the <b>providers directly</b> (not
 * only the registry — a stub registered through the package-private seam cannot hide or add a name)
 * and the checker's own routing predicate ({@link StageAChecker#judgesParameters}), and holds the
 * two equal.
 *
 * <p>
 * Non-vacuity: the population of column-reference-bearing descriptors is asserted to be large, the
 * predicate is shown to distinguish (controls on both sides), and every short-circuited
 * column-reference descriptor is shown to be compiler-dispatched — exempt from the seam pass by
 * {@code RulePackageLoader.registryCallSeamExemptions()} — which is WHY the kept set is empty
 * today.
 * </p>
 */
class StageAResidueDriftTest
{

    private static List<FunctionDescriptor> providers()
    {
        List<FunctionDescriptor> all = new ArrayList<>();
        all.addAll(new BuiltinFunctions().functions());
        all.addAll(new CompilerDispatchedCalls().functions());
        return all;
    }


    private static boolean hasColumnReferenceParameter(FunctionDescriptor d)
    {
        for (Parameter p : d.parameters())
        {
            ExprType t = p.type();
            if (t == Primitive.COLUMN_REFERENCE
                    || (t instanceof ListOf list && list.element() == Primitive.COLUMN_REFERENCE))
            {
                return true;
            }
        }
        return false;
    }


    @Test
    void theKeptSeamSetEqualsTheShortCircuitedRegistryEvaluatedColumnDescriptors()
    {
        List<FunctionDescriptor> all = providers();
        Set<String> columnBearing = new TreeSet<>();
        Set<String> registryEvaluated = new TreeSet<>();
        Set<String> residue = new TreeSet<>();
        Set<String> shortCircuitedDispatched = new TreeSet<>();
        for (FunctionDescriptor d : all)
        {
            if (!hasColumnReferenceParameter(d))
            {
                continue;
            }
            columnBearing.add(d.name());
            boolean judged = StageAChecker.judgesParameters(d.name());
            if (d.fn() != null)
            {
                registryEvaluated.add(d.name());
                if (!judged)
                {
                    residue.add(d.name());
                }
            }
            else if (!judged)
            {
                shortCircuitedDispatched.add(d.name());
            }
        }
        // population floors: the providers were read, and the registry agrees with them by name
        assertTrue(columnBearing.size() >= 15, "column-reference descriptors: " + columnBearing);
        assertTrue(registryEvaluated.size() >= 8,
                "registry-evaluated column-reference descriptors: " + registryEvaluated);
        Set<String> registry = new TreeSet<>();
        FunctionRegistry.all().forEach(d -> registry.add(d.name()));
        Set<String> provided = new TreeSet<>();
        all.forEach(d -> provided.add(d.name()));
        assertEquals(provided, registry, "the registry serves exactly the two providers' names");
        // the invariant: the loader's kept set IS the residue the checker's routing leaves
        assertEquals(residue, new TreeSet<>(ExprCompiler.STAGE_A_SHORT_CIRCUITED_COLUMN_CALLS),
                "every registry-evaluated column-reference descriptor stage A short-circuits must"
                        + " keep the R1 seam in the loader's pass, and only those");
        // and why it is empty today: no column-reference-bearing descriptor is short-circuited at
        // all — every short-circuited arm of call() (presence, broadcast predicate, accessor,
        // vlm_*, varname-anchored, temporal, colref, num) declares its name slot unknown or
        // dispatches through the compiler. Should one ever be declared, it is either exempt from
        // the seam pass (compiler-dispatched) or must join the kept set — which the equality
        // above then demands.
        for (String name : shortCircuitedDispatched)
        {
            assertTrue(RulePackageLoader.registryCallSeamExemptions().contains(name),
                    name + " is short-circuited by stage A and must be exempt from the seam pass");
        }
    }


    @Test
    void theRoutingPredicateDistinguishes()
    {
        // short-circuited arms of StageAChecker.call(): presence, broadcast predicate, accessor,
        // vlm_*, varname-anchored, the temporal and conversion names
        for (String name : List.of("var_exists", "ds_exists", "var_is_null", "var_label",
                "vlm_length", "max_value_length", "date", "colref", "num"))
        {
            assertFalse(StageAChecker.judgesParameters(name), name);
        }
        // registered(): the R1 seam's own descriptors, and the gate arm since the Precondition root
        for (String name : List.of("tuple", "dy", "date_diff_days", "referenced_dataset_variables",
                "valid_external_dictionary_code_term_pair", "record_count", "library_available",
                "dictionary_available"))
        {
            assertTrue(StageAChecker.judgesParameters(name), name);
        }
    }
}
