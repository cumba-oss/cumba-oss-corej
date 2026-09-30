package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import org.junit.jupiter.api.Test;

/**
 * The self-check of the inverted load-time registry-call seam (combined review of runbook W2–W8, W4
 * M4): the names exempt from the generic seam are exactly the compiler-dispatched calls, the set is
 * never empty, and no registry-evaluated function hides in it. Kept apart from
 * {@link RegistryCallSeamsTest} because {@code RulePackageLoader.registryCallSeamExemptions()} is a
 * new symbol — this class does not compile on the pre-fix tree, the behavioural pins there do.
 */
class RegistryCallSeamsExemptionTest
{

    @Test
    void theExemptionSetIsExactlyTheCompilerDispatchedCallsAndIsNeverVacuous()
    {
        Set<String> exempt = RulePackageLoader.registryCallSeamExemptions();
        assertFalse(exempt.isEmpty(), "a vacuous exemption set would exempt nothing by accident");
        for (String tailored : List.of("read_value", "max", "max_date", "min_date", "record_count",
                "distinct", "has_multiple_values_for", "is_unique_set", "is_sorted_by"))
        {
            assertTrue(exempt.contains(tailored), tailored + " keeps its tailored seam");
        }
        Set<String> evaluated = new java.util.TreeSet<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            if (d.fn() != null)
            {
                evaluated.add(d.name());
                assertFalse(exempt.contains(d.name()),
                        d.name() + " is registry-evaluated and must meet the generic seam");
            }
            else
            {
                assertTrue(exempt.contains(d.name()),
                        d.name() + " is compiler-dispatched and must be exempt");
            }
        }
        // Non-vacuity of the generic side: the functions the finding named are in it.
        assertTrue(evaluated.containsAll(List.of("row_max", "minus", "referenced_dataset_variables",
                "valid_external_dictionary_value", "valid_external_dictionary_code_term_pair")),
                evaluated.toString());
        for (String name : exempt)
        {
            FunctionDescriptor d = FunctionRegistry.descriptor(name);
            assertNotNull(d, name + " is a registered name");
            assertNull(d.fn(), name + " carries no EvalFunction");
        }
    }

}
