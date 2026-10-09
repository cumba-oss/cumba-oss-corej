package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.loadError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W4 KP ({@code PLAN-binding-substitutability} C6 note; coordinator W4-D3): a positional
 * {@code $} key of {@code is_unique_relationship} / {@code is_not_unique_relationship} (its
 * {@code value}) and of {@code is_inconsistent_across_dataset} (its {@code key}) is the rule's load
 * error with the hint that a binding of column names is spliced through {@code keys=[$k]}. Before
 * KP the compiler passed the {@code $} name RAW as a column name: the lookup missed it and the key
 * was dropped like an absent column, so the call silently checked fewer keys than written — while
 * {@code keys=[$k]} splices the names the binding holds.
 */
class KeyMemberShapeLoadTest
{

    private static final List<String> CALLS = List.of("is_unique_relationship(AETERM, $k)",
            "not is_not_unique_relationship(AETERM, $k)",
            "is_inconsistent_across_dataset(AETERM, $k)");

    private static void assertKp(String error)
    {
        assertTrue(error.contains("takes a column, not the binding $k"), error);
        assertTrue(error.contains("spliced through keys=[$k]"), error);
    }


    @Test
    void aPositionalBindingKeyIsALoadErrorInACheck()
    {
        for (String call : CALLS)
        {
            String error = loadError(call, "$k", "\"AEDECOD\"");
            assertKp(error);
            String parameter = call.contains("inconsistent") ? "'key'" : "'value'";
            assertTrue(error.contains("argument " + parameter), error);
        }
        // a name-list binding meets the same one message (decision 9's value-position hint is
        // stage A's, which never runs once the loader refused the rule)
        assertKp(loadError("is_unique_relationship(AETERM, $k)", "$k", "[AEDECOD]"));
        // a computed name in the binding: the same
        assertKp(loadError("is_unique_relationship(AETERM, $k)", "$k", "upper(\"aedecod\")"));
    }


    @Test
    void aPositionalBindingKeyIsALoadErrorInABindingAndInACheckLessRule()
    {
        assertKp(loadError("$b", "$k", "\"AEDECOD\"", "$b", "is_unique_relationship(AETERM, $k)"));
        Rule checkless = SequenceFunctionTest.loadCheckless("$k", "\"AEDECOD\"", "$b",
                "is_inconsistent_across_dataset(AETERM, $k)");
        assertKp(String.valueOf(checkless.getLoadError()));
    }


    @Test
    void keysSplicesTheBindingAndAWrittenPositionalColumnIsUnchanged()
    {
        IDataTable ae = WithinOrderingSpliceTest.ae();
        List<Long> written = SequenceFunctionTest.fired(ae,
                "not is_unique_relationship(AETERM, AEDECOD)");
        assertTrue(!written.isEmpty(), "premise: AETERM A maps to D1 and D2");
        assertEquals(written, SequenceFunctionTest.fired(ae,
                "not is_unique_relationship(AETERM, keys=[AEDECOD])"));
        assertEquals(written, SequenceFunctionTest.fired(ae,
                "not is_unique_relationship(AETERM, keys=[$k])", "$k", "\"AEDECOD\""));
        assertEquals("null", loadError("is_inconsistent_across_dataset(AETERM, AEDECOD)"));
    }
}
