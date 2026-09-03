package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract that replaced the legacy evaluator: a rule whose {@code Check} has no native
 * expression form is reported as a per-rule {@code ERROR}, never silently evaluated on a fallback
 * engine.
 *
 * <p>
 * Every shipped rule is native, a template whose expansions are native, or an intentional
 * {@code loadError} — so this can only be reached by an externally supplied rule. Both dispatch
 * paths carry the branch and both are pinned here: the row path and the group-sensitivity path.
 * Without these, a future change could quietly reintroduce a fallback on one of them.
 * </p>
 */
class RetiredLegacyEngineContractTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static final String RETIRED = "legacy evaluator has been retired";

    private static IDataTable table()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1", "S2").col("AETERM", "X", "Y").build();
    }


    /** A rule with a real Check but deliberately NO compiled checkExpr. */
    private static Rule ruleWithoutNativeForm(Sensitivity sensitivity,
            List<String> groupingVariables)
    {
        Rule r = new Rule();
        r.setId("EXTERNAL-1");
        RuleCore core = new RuleCore();
        core.setId("EXTERNAL-1");
        r.setCore(core);
        Outcome o = new Outcome();
        o.setMessage("external rule");
        o.setOutputVariables(List.of("AETERM"));
        r.setOutcome(o);
        r.setCheck(new CheckConditionAll(List.of(CheckConditionLeaf.builder().name("AETERM")
                .operator("equal_to").value(MAPPER.valueToTree("X")).build())));
        r.setSensitivity(sensitivity);
        r.setGroupingVariables(groupingVariables);
        // NOTE: setCheckExpr is deliberately NOT called — that is the whole point of the test.
        assertNull(r.getCheckExpr(), "the fixture must have no native form");
        return r;
    }


    @Test
    void rowPathReportsErrorInsteadOfFallingBackToALegacyEngine()
    {
        RuleExecutionResult result = RuleRunner.execute(
                ruleWithoutNativeForm(Sensitivity.RECORD, null), table(), NO_RESOLVER, "AE", null,
                null);
        assertNotNull(result);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "a Check with no native form must surface as an ERROR");
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains(RETIRED),
                "the status must name the retirement: " + result.getStatusMessage());
        assertTrue(result.getViolations().isEmpty(), "an ERROR reports no violations");
    }


    @Test
    void groupSensitivityPathReportsErrorInsteadOfFallingBackToALegacyEngine()
    {
        RuleExecutionResult result = RuleRunner.execute(
                ruleWithoutNativeForm(Sensitivity.GROUP, List.of("USUBJID")), table(), NO_RESOLVER,
                "AE", null, null);
        assertNotNull(result);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "the group-sensitivity path must also ERROR, not fall back");
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains(RETIRED),
                "the status must name the retirement: " + result.getStatusMessage());
        assertTrue(result.getViolations().isEmpty(), "an ERROR reports no violations");
    }
}
