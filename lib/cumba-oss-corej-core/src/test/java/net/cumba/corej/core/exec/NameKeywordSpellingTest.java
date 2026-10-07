package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-string-literal-prefix-rewrite} review LOW-5 / confirmation look MED-B: the
 * {@code name=} spelling of {@code max_value_length} / the {@code vlm_*} accessors is the
 * positional one everywhere — the compiler arm, the domain scan and stage A read it through ONE
 * helper ({@code NameSlots.nameArgument}) — so a named column is a dataset-level fact (one
 * finding), never the per-variable cursor form; and giving the name both ways is refused (D19a).
 */
class NameKeywordSpellingTest
{

    /** AETERM: lengths 4 and 1. */
    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1", "S2").str("AETERM", "abcd", "x").build();
    }


    private static Rule load(String check)
    {
        String json = "{\"rules\":{\"R\":{\"Core\":{\"Id\":\"T-NKS\"},\"Check\":{\"expression\":\""
                + check.replace("\"", "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        try
        {
            Rule r = RulePackageLoader.loadFromString(json).getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    private static List<Long> fired(String check)
    {
        Rule r = load(check);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = ae();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary), "AE");
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    @Test
    void theKeywordSpellingIsADatasetLevelFactAsThePositionalIs()
    {
        List<Long> positional = fired("max_value_length(\"AETERM\") > 3");
        assertEquals(1, positional.size(), "a dataset-level fact: one finding " + positional);
        assertEquals(positional, fired("max_value_length(name=\"AETERM\") > 3"));
    }


    @Test
    void theNameGivenBothWaysIsRefused()
    {
        assertNotNull(load("max_value_length(\"AETERM\", name=\"AETERM\") > 3").getLoadError(),
                "D19a: a name a positional already bound may not be given again");
        assertNotNull(load("vlm_length(\"AETERM\", name=\"AETERM\") > 3").getLoadError(),
                "D19a, the VLM accessors alike");
    }
}
