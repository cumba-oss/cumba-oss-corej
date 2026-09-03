package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Pins the value-position contract of the native engine: an unquoted identifier that resolves to no
 * column yields {@code null}, never the bareword itself. Bareword = column reference, quoted =
 * literal — unconditionally, with no per-rule opt-out.
 */
class UnresolvableIdentContractTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /**
     * Record-Data rule {@code AETERM == PLACEBO} — "PLACEBO" is a value-position fallback token.
     */
    private static Rule equalToLiteralRule()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AETERM").operator("equal_to")
                .value(MAPPER.valueToTree("PLACEBO")).build();
        Rule r = baseRule("R", leaf, "AETERM");
        r.setCheckExpr(CheckToExpr.toExpr(leaf));
        return r;
    }


    private static Rule baseRule(String id, net.cumba.corej.core.model.CheckCondition check,
            String outputVar)
    {
        Rule r = new Rule();
        r.setId(id);
        RuleCore core = new RuleCore();
        core.setId(id);
        r.setCore(core);
        Outcome o = new Outcome();
        o.setMessage("m " + id);
        o.setOutputVariables(List.of(outputVar));
        r.setOutcome(o);
        r.setCheck(check);
        r.setSensitivity(Sensitivity.RECORD);
        return r;
    }


    private static BitSet violatingRows(RuleExecutionResult result)
    {
        BitSet bs = new BitSet();
        for (Violation v : result.getViolations())
        {
            bs.set((int) v.getRow());
        }
        return bs;
    }


    @Test
    void unresolvableIdentIsNullNotLiteral()
    {
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "PLACEBO", "DRUG").build();
        RuleExecutionResult r = RuleRunner.execute(equalToLiteralRule(), ae, NO_RESOLVER, "AE",
                null, null, null);
        assertNotNull(r);
        assertTrue(violatingRows(r).isEmpty(),
                "the native engine must not treat PLACEBO as a literal — the unresolvable column "
                        + "reference yields null, so no row matches");
    }

}
