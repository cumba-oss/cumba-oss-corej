package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} §2.3 steps 3 and 4 at the resolver:
 * {@link DynamicColumnRead#cell} over a context, and the compiled {@code colref} plan reading its
 * site's kind per EVALUATION (phase 0 D4: compiled plans are shared across rules through
 * {@code NativeExprEvaluator.CACHE}, so a kind bound at compile time would leak from one rule into
 * the next).
 */
class DynamicColumnReadTest
{

    private static final IDataTable TABLE = new SyntheticDataTable("DM",
            Map.of("USUBJID", DataValueType.STRING), 3);

    private static EvaluationContext ctx(Set<Expr.Call> sites)
    {
        return EvaluationContext.builder().table(TABLE).numericExpectedDynamicSites(sites).build();
    }


    @Test
    void aSitesKindIsReadPerEvaluationNeverBoundAtCompileTime()
    {
        Expr check = CheckExpressionParser.parse("colref(\"ZZ\") < 3");
        Expr.Call site = (Expr.Call) ((Expr.Binary) check).left();
        BitSet numeric = NativeExprEvaluator.evaluate(check, ctx(Set.of(site)));
        BitSet character = NativeExprEvaluator.evaluate(check, ctx(Set.of()));
        assertEquals(3, numeric.cardinality(), "numeric-expected site: MIS < 3 holds on every row");
        assertEquals(0, character.cardinality(),
                "the SAME cached program under a context without the site answers \"\" (no fire)");
        assertEquals(3, NativeExprEvaluator.evaluate(check, ctx(Set.of(site))).cardinality(),
                "and flips back — nothing was memoised from the first context");
    }


    @Test
    void anUnsuppliedDatasetTakesTheOverloadsDefault()
    {
        EvaluationContext c = ctx(Set.of());
        IDataValue numeric = DynamicColumnRead.cell(c, "ADSL.AP01SDT", 0, true);
        IDataValue character = DynamicColumnRead.cell(c, "ADSL.AP01SDT", 0, false);
        assertSame(MissingValue.MIS, numeric.getValue());
        assertEquals("", character.getValueAsString());
        assertEquals(DataValueType.STRING, character.getType());
        assertEquals(ExprCompiler.dottedNotSuppliedDefault(true).getValue(), numeric.getValue());
    }


    @Test
    void theResolvedNamesRuleWideKindIsTheSecondTerm()
    {
        EvaluationContext c = EvaluationContext.builder().table(TABLE)
                .numericExpectedColumns(Set.of("ZZ")).build();
        assertSame(MissingValue.MIS, DynamicColumnRead.cell(c, "ZZ", 0, false).getValue(),
                "a name the rule authors numerically elsewhere defaults as that name does");
        assertEquals("", DynamicColumnRead.cell(c, "YY", 0, false).getValueAsString());
        assertEquals("", DynamicColumnRead.cell(c, "", 0, false).getValueAsString(),
                "\"\" names no column: the absent default");
    }


    @Test
    void aDashNameReachingEvaluationAsserts()
    {
        assertThrows(ExpressionException.class,
                () -> DynamicColumnRead.cell(ctx(Set.of()), "--SEQ", 0, false),
                "D77b: an unresolved -- name is a specialiser defect");
    }


    @Test
    void aListFirstHopKeepsEveryElementAsATypedCell()
    {
        EvaluationContext c = ctx(Set.of());
        Vector names = ConstVector.of(List.of("USUBJID", "ZZ"));
        Vector out = DynamicColumnRead.vector(EvalRun.fullRange(c), names, false, true);
        Object row0 = out.value(0).resolved();
        assertEquals(2, ((List<?>) row0).size(), "one element per name, in the list's order");
        assertEquals("", ((IDataValue) ((List<?>) row0).get(1)).getValueAsString(),
                "an absent element takes its default");
    }


    /**
     * Review round 2 (engine 4): names that read no column are memoised only up to a cap, so
     * per-row unique data values cannot grow the evaluation's memo per row — and every row still
     * answers its absent default, before and after the cap.
     */
    @Test
    void perRowUniqueAbsentNamesAnswerPastTheMemoCap()
    {
        EvaluationContext c = ctx(Set.of());
        int rows = 3000;
        Vector names = new ComputedVector(rows, DataValueType.STRING, row -> "ZZ" + row);
        Vector out = DynamicColumnRead.vector(new EvalRun(c, 0, rows), names, false, false);
        for (int r : new int[]
        {
                0, 1023, 1024, 2999
        })
        {
            assertEquals("", out.value(r).cell().getValueAsString(), "row " + r);
        }
    }
}
