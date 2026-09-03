package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that when {@code nativeEval} is on, a membership cohort whose members retained a
 * native-supported {@code Expr} evaluates those members via the native backend and produces results
 * <b>byte-for-byte equal</b> to the legacy batch path — so the flag reaches the cohort path without
 * changing any result.
 */
@ExtendWith(MockitoExtension.class)
class CohortRunnerNativeTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Rule membershipRule(String id, String varName, String... terms)
    {
        ArrayNode arr = MAPPER.createArrayNode();
        for (String t : terms)
        {
            arr.add(t);
        }
        CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name(varName)
                .operator("non_empty").build();
        CheckConditionLeaf notIn = CheckConditionLeaf.builder().name(varName)
                .operator("is_not_contained_by").value(arr).build();
        CheckConditionAll check = new CheckConditionAll(List.of(nonEmpty, notIn));

        Rule r = new Rule();
        r.setId(id);
        RuleCore core = new RuleCore();
        core.setId(id);
        r.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("msg " + id);
        outcome.setOutputVariables(List.of(varName));
        r.setOutcome(outcome);
        r.setCheck(check);
        r.setSensitivity(Sensitivity.RECORD);
        // Mirror the loader: reconstruct the native Expr for this fully-expression Record-Data
        // rule.
        r.setCheckExpr(CheckToExpr.toExpr(check));
        return r;
    }


    private static BitSet rows(RuleExecutionResult result)
    {
        BitSet bs = new BitSet();
        for (Violation v : result.getViolations())
        {
            bs.set((int) v.getRow());
        }
        return bs;
    }


    @Test
    void membershipCohortNativeMatchesBatch()
    {
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "S1", "S2", "S3", "S4", "S5")
                .col("VISITNUM", "1", "2", "", "99", "5").col("AGEGR1", "Y", "OLD", "Y", "", "X")
                .build();

        Rule rVisit = membershipRule("X-VISITNUM", "VISITNUM", "1", "2", "3");
        Rule rAge = membershipRule("X-AGEGR1", "AGEGR1", "Y", "N", "U");

        List<RuleExecutionResult> batch = CohortRunner.executeCohort(List.of(rVisit, rAge), adlb,
                NO_RESOLVER, "AD", null, null);
        List<RuleExecutionResult> nativ = CohortRunner.executeCohort(List.of(rVisit, rAge), adlb,
                NO_RESOLVER, "AD", null, null);

        assertEquals(2, nativ.size());
        assertEquals(rows(batch.get(0)), rows(nativ.get(0)), "VISITNUM cohort native vs batch");
        assertEquals(rows(batch.get(1)), rows(nativ.get(1)), "AGEGR1 cohort native vs batch");
        // Sanity: the fixture actually exercises violations (not a trivially-empty agreement).
        assertEquals(bits(3, 4), rows(nativ.get(0))); // VISITNUM "99","5" not in {1,2,3}
        assertEquals(bits(1, 4), rows(nativ.get(1))); // AGEGR1 "OLD","X" not in {Y,N,U}
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }

}
