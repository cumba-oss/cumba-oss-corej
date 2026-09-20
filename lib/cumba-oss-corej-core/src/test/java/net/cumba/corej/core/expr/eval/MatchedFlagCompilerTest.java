package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetLookup;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Compiler-level contracts of the join-match flag (phase 5b-J, spec §3.3): the boolean-position
 * plan reads {@link JoinLookup#matchedRow}; a missing lookup fails LOUD (never an empty BitSet,
 * which {@code not} would invert into the absent-dataset flood); and value position is rejected
 * ({@code PLAN-join-match-flag.md} §3.4 / gate J3). The retired {@code ExprLowering}'s named
 * rejection went with the leaf model (phase 7d, D121).
 */
class MatchedFlagCompilerTest
{

    private static final IDataTable PRIMARY = MockTable.of().name("ADAE")
            .col("USUBJID", "P1", "P2", "P3").build();

    private static EvaluationContext ctx(Map<String, JoinLookup> joins)
    {
        return EvaluationContext.builder().table(PRIMARY).ruleId("TEST-MF").domainName("ADAE")
                .joinedDatasets(joins).build();
    }


    private static JoinLookup dmLookup()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P3").build();
        DatasetLookup lookup = DatasetLookup.build("DM", dm, List.of("USUBJID"));
        return java.util.Objects.requireNonNull(lookup);
    }


    @Test
    void booleanPositionCompilesToTheMatchVerdict()
    {
        Expr expr = CheckExpressionParser.parse("DM._matched_");
        BitSet matched = NativeExprEvaluator.evaluate(expr, ctx(Map.of("DM", dmLookup())));
        assertTrue(matched.get(0));
        assertTrue(!matched.get(1));
        assertTrue(matched.get(2));
    }


    @Test
    void notInvertsTheVerdict()
    {
        Expr expr = CheckExpressionParser.parse("not DM._matched_");
        BitSet unmatched = NativeExprEvaluator.evaluate(expr, ctx(Map.of("DM", dmLookup())));
        assertEquals(1, unmatched.cardinality());
        assertTrue(unmatched.get(1));
    }


    @Test
    void aMissingLookupFailsLoudNeverEmpty()
    {
        // An empty BitSet here would flood under `not` — the 2026-08 plan's Q1. Stage B owns the
        // decision; reaching evaluation without a lookup is a sequencing defect and must throw.
        Expr expr = CheckExpressionParser.parse("not DM._matched_");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NativeExprEvaluator.evaluate(expr, ctx(Map.of())));
        assertTrue(ex.getMessage().contains("DM._matched_"));
    }


    @Test
    void valuePositionIsRejectedAtEvaluationToo()
    {
        // Stage A parks this shape at load (MATCHED_FLAG_INVALID); the compiler guard is the belt
        // behind it for expressions that never went through the loader.
        Expr expr = CheckExpressionParser.parse("DM._matched_ == \"Y\"");
        assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.evaluate(expr, ctx(Map.of("DM", dmLookup()))));
    }
}
