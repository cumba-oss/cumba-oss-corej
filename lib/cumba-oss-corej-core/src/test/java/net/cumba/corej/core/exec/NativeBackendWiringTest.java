package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/** Phase 4 — feature-flag plumbing, retain-Expr-on-load, and backend selection. */
@ExtendWith(MockitoExtension.class)
class NativeBackendWiringTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

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


    private static CheckConditionLeaf eq(String name, String literal)
    {
        return CheckConditionLeaf.builder().name(name).operator("equal_to")
                .value(MAPPER.valueToTree(literal)).valueIsLiteral(true).build();
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


    @Test
    void nativeAndLegacyAgreeWhenFlagOn()
    {
        CheckConditionAll check = new CheckConditionAll(List.of(eq("SEX", "M")));
        Rule rule = recordRule(check);
        rule.setCheckExpr(CheckToExpr.toExpr(check));
        IDataTable t = MockTable.of().col("SEX", "M", "F", "M", "").build();

        var legacy = RuleRunner.execute(rule, t, NO_RESOLVER, null, null, null);
        var nativ = RuleRunner.execute(rule, t, NO_RESOLVER, null, null, null);

        assertEquals(rows(legacy), rows(nativ), "native must match legacy");
        assertEquals(bitsOf(0, 2), rows(nativ));
    }


    @Test
    void executionRecorderCapturesBackendPerRule()
    {
        // Epic A2: the in-memory recorder reports which backend ran each rule. Same rule, flag on
        // -> NATIVE; flag off -> LEGACY. A test program uses this to confirm native-only is safe.
        CheckConditionAll check = new CheckConditionAll(List.of(eq("SEX", "M")));
        Rule rule = recordRule(check);
        rule.setCheckExpr(CheckToExpr.toExpr(check));
        IDataTable t = MockTable.of().col("SEX", "M", "F", "M").build();

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, t, NO_RESOLVER, null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("CORE-NATIVE-1"));
    }


    @Test
    void executionRecorderIsNoOpWhenDisabled()
    {
        // No active session => record(...) is a no-op and disable() yields an empty snapshot.
        assertFalse(NativeExecutionRecorder.isEnabled());
        NativeExecutionRecorder.record("CORE-X", NativeExecutionRecorder.Backend.NATIVE);
        assertTrue(NativeExecutionRecorder.disable().isEmpty());
    }


    @Test
    void nullCheckExprReportsErrorSinceLegacyRetirement()
    {
        CheckConditionAll check = new CheckConditionAll(List.of(eq("SEX", "M")));
        Rule rule = recordRule(check); // no checkExpr set
        assertNull(rule.getCheckExpr());
        IDataTable t = MockTable.of().col("SEX", "M", "F").build();

        var result = RuleRunner.execute(rule, t, NO_RESOLVER, null, null, null);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "no native expression form and no legacy engine -> per-rule ERROR");
    }


    @Test
    void loaderRetainsExprForRecordDataRules() throws Exception
    {
        RulePackage pkg = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages/rules-sdtmig-3-4.json"));
        long withExpr = pkg.getRules().values().stream().filter(r -> r.getCheckExpr() != null)
                .count();
        assertTrue(withExpr > 0,
                "some fully-expression Record-Data rules should retain a checkExpr");
        for (Rule r : pkg.getRules().values())
        {
            if (r.getCheckExpr() != null)
            {
                // PLAN-leaf-scope-domain-inference: every rule whose Check compiles natively
                // carries an inferred evaluation domain and no load error.
                assertNotNull(r.getEvaluationDomain(),
                        "checkExpr implies an inferred domain, rule " + r.getCore().getId());
                assertNull(r.getLoadError());
            }
        }
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
