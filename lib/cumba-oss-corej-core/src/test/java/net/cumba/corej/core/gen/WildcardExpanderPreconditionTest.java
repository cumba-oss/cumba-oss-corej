package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-stage-a-parameter-type-arming} review round 1 (semantics M2): a wildcard expansion
 * carries the template's {@code Precondition} — the engine-injected availability gate included —
 * exactly as a token expansion does ({@code TokenExpander}). Without it a custom wildcard template
 * with an inline library call PASSed silently when no library provider was configured, where the
 * template itself (and every non-template rule) SKIPs. Zero shipped carriers: injection into the
 * shipped corpus is held at 0 by {@code CrossCorpusDerivationTest}.
 */
class WildcardExpanderPreconditionTest
{

    private static Rule template() throws Exception
    {
        // the emptiness-only inline library call gets the injected `library_available()` gate
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"WCP-GATE\"},\"Check\":{\"expression\":"
                + "\"empty(dataset_class_from_library()) and not empty(TRTxxP)\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals("library_available()", rule.getInjectedPreconditionGates());
        return rule;
    }


    @Test
    void anExpansionCarriesTheTemplatesInjectedGateAndSkipsWithoutTheProvider() throws Exception
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "P1").col("TRT01P", "A")
                .build();
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(template(),
                adsl.getMetaData());
        List<Rule> expanded = assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                result).rules();
        assertEquals(1, expanded.size());
        Rule concrete = expanded.get(0);
        assertNull(concrete.getLoadError(), concrete.getLoadError());
        assertNotNull(concrete.getPrecondition(), "the template's Precondition rides onto the"
                + " expansion — without it the gate is lost and the rule runs ungated");
        assertNotNull(concrete.getPreconditionExpr(), "…raised to its native broadcast form");
        // no library provider: the gate folds false and the expansion SKIPs (not a false PASS)
        RuleExecutionResult run = RuleRunnerCalls.execute(concrete, adsl,
                name -> "ADSL".equals(name) ? adsl : null, "ADSL", null, null, null);
        assertEquals(RuleExecutionStatus.SKIPPED, run.getStatus(), run.getStatusMessage());
    }
}
