package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/** Phase 4 — feature-flag plumbing and backend selection. */
@ExtendWith(MockitoExtension.class)
class NativeBackendWiringTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Rule recordRule(CheckConditionAll check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-NATIVE-1");
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("native test");
        rule.setOutcome(outcome);
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheck(check);
        return rule;
    }


    private static net.cumba.corej.core.model.CheckConditionExpression eq(String name,
            String literal)
    {
        return expr(name + " == \"" + literal + "\"");
    }


    private static BitSet rows(RuleExecutionResult result)
    {
        BitSet bs = new BitSet();
        for (var v : result.getViolations())
        {
            bs.set((int) v.getRow());
        }
        return bs;
    }


    /**
     * A record rule carrying its native expression form ({@code SEX == "M"}) runs on the native
     * backend — the only one since the legacy engine was retired — and flags exactly the matching
     * rows. (Formerly a native-vs-legacy comparison; with one backend both arms were the same call,
     * so the comparison proved nothing and was dropped.)
     */
    @Test
    void nativeBackendFlagsExactlyTheMatchingRows()
    {
        CheckConditionAll check = new CheckConditionAll(List.of(eq("SEX", "M")));
        Rule rule = recordRule(check);
        rule.setCheckExpr(CheckToExpr.toExpr(check));
        IDataTable t = MockTable.of().col("SEX", "M", "F", "M", "").build();

        var result = RuleRunnerCalls.execute(rule, t, NO_RESOLVER, null, null, null);

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(bitsOf(0, 2), rows(result), "rows 0 and 2 carry SEX == M");
    }


    @Test
    void nullCheckExprReportsErrorSinceLegacyRetirement()
    {
        CheckConditionAll check = new CheckConditionAll(List.of(eq("SEX", "M")));
        Rule rule = recordRule(check); // no checkExpr set
        assertNull(rule.getCheckExpr());
        IDataTable t = MockTable.of().col("SEX", "M", "F").build();

        var result = RuleRunnerCalls.execute(rule, t, NO_RESOLVER, null, null, null);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "no native expression form and no legacy engine -> per-rule ERROR");
    }


    private static BitSet bitsOf(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }
}
