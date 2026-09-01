package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * §9.C: an inlined library operation guarded by a {@code library_available() and available(<op>)}
 * Precondition loads native (the inline op compiles, the gate raises to a broadcast verdict) and,
 * when no Library {@link net.cumba.cdisc.core.exec.MetadataProvider} is configured, the rule is
 * reported SKIPPED — exactly the legacy {@code $}-ref + Operations early-skip, not a false PASS.
 */
class LibrarySkipGateTest
{

    private static Rule load(String check, String precondition) throws Exception
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T\"}," + "\"Check\":{\"expression\":\""
                + check + "\"},\"Precondition\":{\"expression\":\"" + precondition + "\"}}}}";
        Rule r = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(r);
        return r;
    }


    @Test
    void inlinedLibraryOpWithGateLoadsNativeAndRaisesPrecondition() throws Exception
    {
        Rule r = load("domain_is_custom() == true",
                "library_available() and available(domain_is_custom())");
        assertNull(r.getLoadError());
        assertNotNull(r.getCheckExpr(), "inline library op compiles native");
        assertNotNull(r.getPreconditionExpr(), "precondition gate raised to a broadcast verdict");
    }


    @Test
    void skipsWhenNoLibraryProvider() throws Exception
    {
        Rule r = load("domain_is_custom() == true",
                "library_available() and available(domain_is_custom())");
        IDataTable dm = MockTable.of().col("STUDYID", "S1").col("DOMAIN", "DM").name("DM").build();

        // No library provider (the 6th argument is null) → the precondition gate folds FALSE.
        RuleExecutionResult result = RuleRunner.execute(r, dm,
                name -> name.equals("DM") ? dm : null, "DM", null, null, null);

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "no Library provider → the gate skips the rule (not a false PASS)");
    }
}
